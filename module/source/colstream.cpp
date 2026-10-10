// Collision streaming (see colstream.hpp). Both realms.
#include "colstream.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>

namespace gc
{
// ---- worker ------------------------------------------------------------------------------------
void Worker::Post(std::function<void()> fn)
{
	std::lock_guard<std::mutex> l(mu_);
	if (stop_.load())
		return;
	q_.push_back(std::move(fn));
	if (!th_.joinable())
		th_ = std::thread([this] { Loop(); });
	cv_.notify_one();
}

void Worker::Stop()
{
	{
		std::lock_guard<std::mutex> l(mu_);
		stop_.store(true);
		q_.clear();
		cv_.notify_all();
	}
	if (th_.joinable())
		th_.join();
	std::lock_guard<std::mutex> l(mu_);
	th_ = std::thread();
	stop_.store(false);
	busy_ = false;
}

std::size_t Worker::Pending()
{
	std::lock_guard<std::mutex> l(mu_);
	return q_.size() + (busy_ ? 1 : 0);
}

void Worker::Loop()
{
	for (;;)
	{
		std::function<void()> fn;
		{
			std::unique_lock<std::mutex> l(mu_);
			busy_ = false;
			cv_.wait(l, [this] { return stop_.load() || !q_.empty(); });
			if (stop_.load())
				return;
			fn = std::move(q_.front());
			q_.pop_front();
			busy_ = true;
		}
		try
		{
			fn();
		}
		catch (...)
		{
			// bad_alloc on a corrupt map: that task is lost, the thread lives on (region jobs
			// catch for themselves, so the streamer learns about it)
			errors_.fetch_add(1);
		}
	}
}

// ---- map loading -------------------------------------------------------------------------------
namespace
{
const int kNeededLumps[] = { mcol::kLumpTexdata, mcol::kLumpVertexes, mcol::kLumpTexinfo, mcol::kLumpFaces, mcol::kLumpEdges,
	mcol::kLumpSurfedges, mcol::kLumpDispInfo, mcol::kLumpPhysCollide, mcol::kLumpDispVerts, mcol::kLumpGame, mcol::kLumpLeafWaterData,
	mcol::kLumpTexdataStringData, mcol::kLumpTexdataStringTable, mcol::kLumpDispTris };
constexpr std::int64_t kMaxLumpBytes = 256ll << 20;

std::string LowerStr(std::string s)
{
	for (char &c : s)
		if (c >= 'A' && c <= 'Z')
			c = static_cast<char>(c - 'A' + 'a');
	return s;
}
}  // namespace

bool MapLoader::Begin(const std::string &map, const std::uint8_t *h, std::size_t n, std::string &err)
{
	*this = MapLoader();
	if (n < 8 + 16 * mcol::kLumpCount || std::memcmp(h, "VBSP", 4) != 0)
	{
		err = "not a VBSP header";
		return false;
	}
	std::int32_t version = 0;
	std::memcpy(&version, h + 4, 4);
	if (version < 19 || version > 20)
	{
		err = "unsupported BSP version " + std::to_string(version);
		return false;
	}
	lumps_.bspVersion = version;
	for (int i = 0; i < mcol::kLumpCount; ++i)
	{
		std::int32_t ofs = 0, len = 0, ver = 0;
		std::memcpy(&ofs, h + 8 + 16 * i, 4);
		std::memcpy(&len, h + 12 + 16 * i, 4);
		std::memcpy(&ver, h + 16 + 16 * i, 4);
		lumps_.fileOffset[i] = ofs;
		lumps_.version[i] = static_cast<std::uint32_t>(ver);
	}
	for (int i : kNeededLumps)
	{
		std::int32_t ofs = 0, len = 0;
		std::memcpy(&ofs, h + 8 + 16 * i, 4);
		std::memcpy(&len, h + 12 + 16 * i, 4);
		if (len <= 0)
			continue;
		if (ofs < 0 || len > kMaxLumpBytes)
		{
			err = "lump " + std::to_string(i) + " has a bad range";
			return false;
		}
		needed_.push_back(Range{ i, ofs, len });
	}
	name_ = LowerStr(map);
	active_ = true;
	return true;
}

bool MapLoader::AddLump(int lump, const std::uint8_t *p, std::size_t n, std::string &err)
{
	auto it = std::find_if(needed_.begin(), needed_.end(), [&](const Range &r) { return r.lump == lump; });
	if (!active_ || it == needed_.end())
	{
		err = "lump " + std::to_string(lump) + " not requested";
		return false;
	}
	if (static_cast<std::int64_t>(n) != it->length)
	{
		err = "lump " + std::to_string(lump) + ": got " + std::to_string(n) + " bytes, want " + std::to_string(it->length);
		return false;
	}
	data_[lump].assign(p, p + n);
	if (n >= 4 && std::memcmp(p, "LZMA", 4) == 0)
	{
		lumps_.lump[lump] = mcol::Bytes{};
		err = "lump " + std::to_string(lump) + " is LZMA-compressed (unsupported)";
		return true;  // a warning: decoding reports what's missing
	}
	lumps_.lump[lump] = mcol::Bytes{ data_[lump].data(), data_[lump].size() };
	return true;
}

bool MapLoader::Prepare(std::vector<std::string> &phyModels, std::vector<std::string> &bboxModels, std::vector<std::string> &textures,
	std::string &err)
{
	phyModels.clear();
	bboxModels.clear();
	textures.clear();
	if (!active_)
	{
		err = "no map";
		return false;
	}
	std::string perr;
	props_.clear();
	if (!mcol::ParseStaticProps(lumps_, props_, sprpVersion_, perr))
	{
		err = "static props: " + perr;  // a warning: the map still loads without them
		props_.clear();
	}
	std::unordered_set<std::string> seen;
	for (const auto &p : props_)
	{
		if ((p.solid != 6 && p.solid != 2) || !seen.insert(p.model).second)
			continue;
		(p.solid == 6 ? phyModels : bboxModels).push_back(p.model);
	}
	mcol::DisplacementTextures(lumps_, textures);
	for (const auto &t : textures)
		if (tex_.find(t) == tex_.end())
			tex_[t] = mcol::SurfacePropPair{};  // placeholder: filled by AddVmt / heuristic
	return true;
}

void MapLoader::AddPhy(const std::string &model, const std::uint8_t *p, std::size_t n)
{
	auto &v = phy_[model];
	v.assign(p, p + n);
	models_[model].phy = mcol::Bytes{ v.data(), v.size() };
}

void MapLoader::AddBBox(const std::string &model, const float mins[3], const float maxs[3])
{
	auto &m = models_[model];
	m.hasBounds = true;
	m.mins = mcol::Vec3{ mins[0], mins[1], mins[2] };
	m.maxs = mcol::Vec3{ maxs[0], maxs[1], maxs[2] };
}

void MapLoader::AddSurfaceProps(const std::string &text)
{
	surfaceProps_.push_back(text);
}

bool MapLoader::AddVmt(const std::string &texture, const std::string &text, std::string &include)
{
	mcol::SurfacePropPair sp;
	if (!mcol::ParseVmtSurfaceProps(text, sp, include))
		return false;
	if (!sp.prop.empty() || !sp.prop2.empty())
		tex_[LowerStr(texture)] = sp;
	return true;
}

void MapLoader::AddTexProps(const std::string &texture, const std::string &prop, const std::string &prop2)
{
	tex_[LowerStr(texture)] = mcol::SurfacePropPair{ LowerStr(prop), LowerStr(prop2) };
}

std::size_t MapLoader::Bytes() const
{
	std::size_t n = 0;
	for (const auto &d : data_)
		n += d.size();
	for (const auto &p : phy_)
		n += p.second.size();
	return n;
}

std::string SurfacePropFromName(const std::string &texture)
{
	static const char *const table[][2] = { { "grass", "grass" }, { "dirt", "dirt" }, { "mud", "mud" }, { "sand", "sand" },
		{ "gravel", "gravel" }, { "snow", "snow" }, { "ice", "ice" }, { "rock", "rock" }, { "cliff", "rock" }, { "stone", "rock" },
		{ "concrete", "concrete" }, { "brick", "brick" }, { "tile", "tile" }, { "plaster", "plaster" }, { "wood", "wood" },
		{ "metal", "metal" }, { "glass", "glass" }, { "carpet", "carpet" } };
	const std::string t = LowerStr(texture);
	for (const auto &e : table)
		if (t.find(e[0]) != std::string::npos)
			return e[1];
	return "";
}

std::shared_ptr<MapData> MapLoader::Decode(std::string &err)
{
	const double t0 = NowMs();
	auto d = std::make_shared<MapData>();
	d->name = name_;
	d->inputBytes = Bytes();
	std::string warn;
	for (auto &t : tex_)
		if (t.second.prop.empty())
		{
			t.second.prop = SurfacePropFromName(t.first);
			++heuristic_;
		}
	mcol::DecodeOptions opts;
	opts.texProps = &tex_;
	opts.brushModels = true;  // "*N" brush entities (doors, func_brush): the dynamic layer places them
	if (!mcol::DecodeMap(lumps_, opts, d->map, err))
	{
		*this = MapLoader();
		return nullptr;
	}
	const double t1 = NowMs();
	mcol::AddStaticProps(d->map, props_, models_, d->props);
	d->propCount = props_.size();
	d->sprpVersion = sprpVersion_;
	const double t2 = NowMs();
	mcol::SurfaceProps sp;
	for (const auto &text : surfaceProps_)
	{
		std::string e;
		if (!mcol::ParseSurfaceProperties(text, sp, e))
			warn += "surfaceproperties: " + e + "\n";
	}
	mcol::ResolveMaterials(d->map, sp);
	for (const auto &e : d->props.errors)
		warn += "prop " + e + "\n";
	if (heuristic_)
		warn += std::to_string(heuristic_) + " displacement textures without a readable $surfaceprop: guessed from the name\n";
	d->warnings = warn;

	// Map stats for the debug panel.
	std::map<std::uint16_t, std::uint32_t> perMat;
	std::map<int, std::uint32_t> perDig;
	for (const auto &t : d->map.world.tris)
	{
		if (t.kind < 8)
			++d->trisByKind[t.kind];
		++perMat[t.material];
		const auto &m = d->map.materials[t.material];
		++perDig[m.diggable ? static_cast<int>(m.dig) : -1];
	}
	for (const auto &c : d->map.world.convexes)
		if (c.kind < 8)
			++d->convexesByKind[c.kind];
	for (const auto &e : perMat)
		d->materialTris.emplace_back(d->map.materials[e.first].name, e.second);
	std::sort(d->materialTris.begin(), d->materialTris.end(), [](const auto &a, const auto &b) { return a.second > b.second; });
	d->digTris.assign(perDig.begin(), perDig.end());
	d->decodeMs = t1 - t0;
	d->propsMs = t2 - t1;
	d->totalMs = NowMs() - t0;
	*this = MapLoader();  // frees the lump copies
	return d;
}

// ---- dig state ---------------------------------------------------------------------------------
namespace
{
int FloorDiv(int v, int d)
{
	return v >= 0 ? v / d : -((-v + d - 1) / d);
}
}  // namespace

std::uint64_t DigStore::Key(int sx, int sy, int sz)
{
	return (std::uint64_t(std::uint32_t(sx) & 0x1FFFFF) << 42) | (std::uint64_t(std::uint32_t(sy) & 0x1FFFFF) << 21) |
		(std::uint32_t(sz) & 0x1FFFFF);
}

void DigStore::Apply(std::uint32_t worldId, int sx, int sy, int sz, const std::uint8_t *bits512, std::vector<std::array<int, 3>> &changed)
{
	std::array<std::uint64_t, 64> next{};
	if (bits512 != nullptr)
		std::memcpy(next.data(), bits512, 512);
	std::lock_guard<std::mutex> l(mu_);
	auto &world = worlds_[worldId];
	std::array<std::uint64_t, 64> prev{};
	auto it = world.find(Key(sx, sy, sz));
	if (it != world.end())
		prev = it->second;
	for (int w = 0; w < 64; ++w)
	{
		std::uint64_t diff = prev[w] ^ next[w];
		while (diff)
		{
			int b = __builtin_ctzll(diff);
			diff &= diff - 1;
			int i = w * 64 + b;
			changed.push_back({ sx * 16 + (i & 15), sy * 16 + (i >> 8), sz * 16 + ((i >> 4) & 15) });
		}
	}
	bool any = false;
	for (auto v : next)
		any |= v != 0;
	if (prev != next)
		++gen_;
	if (any)
		world[Key(sx, sy, sz)] = next;
	else if (it != world.end())
		world.erase(it);
}

void DigStore::Snapshot(std::uint32_t worldId, std::vector<SectionBits> &out) const
{
	auto unpack = [](std::uint64_t v) {  // Key's 21-bit fields, sign-extended
		return static_cast<int>(static_cast<std::int32_t>(static_cast<std::uint32_t>(v & 0x1FFFFF) << 11) >> 11);
	};
	std::lock_guard<std::mutex> l(mu_);
	auto w = worlds_.find(worldId);
	if (w == worlds_.end())
		return;
	for (const auto &s : w->second)
		out.push_back(SectionBits{ unpack(s.first >> 42), unpack(s.first >> 21), unpack(s.first), s.second });
}

void DigStore::Collect(std::uint32_t worldId, const int lo[3], const int hi[3], std::vector<std::array<int, 3>> &out) const
{
	std::lock_guard<std::mutex> l(mu_);
	auto w = worlds_.find(worldId);
	if (w == worlds_.end() || w->second.empty())
		return;
	for (int sy = FloorDiv(lo[1], 16); sy <= FloorDiv(hi[1], 16); ++sy)
		for (int sz = FloorDiv(lo[2], 16); sz <= FloorDiv(hi[2], 16); ++sz)
			for (int sx = FloorDiv(lo[0], 16); sx <= FloorDiv(hi[0], 16); ++sx)
			{
				auto it = w->second.find(Key(sx, sy, sz));
				if (it == w->second.end())
					continue;
				for (int wd = 0; wd < 64; ++wd)
				{
					std::uint64_t bits = it->second[wd];
					while (bits)
					{
						int b = __builtin_ctzll(bits);
						bits &= bits - 1;
						int i = wd * 64 + b;
						int x = sx * 16 + (i & 15), y = sy * 16 + (i >> 8), z = sz * 16 + ((i >> 4) & 15);
						if (x >= lo[0] && x <= hi[0] && y >= lo[1] && y <= hi[1] && z >= lo[2] && z <= hi[2])
							out.push_back({ x, y, z });
					}
				}
			}
}

std::size_t DigStore::Cells(std::uint32_t worldId) const
{
	std::lock_guard<std::mutex> l(mu_);
	auto w = worlds_.find(worldId);
	std::size_t n = 0;
	if (w != worlds_.end())
		for (const auto &s : w->second)
			for (auto v : s.second)
				n += static_cast<std::size_t>(__builtin_popcountll(v));
	return n;
}

std::size_t DigStore::Sections() const
{
	std::lock_guard<std::mutex> l(mu_);
	std::size_t n = 0;
	for (const auto &w : worlds_)
		n += w.second.size();
	return n;
}

void DigStore::Clear()
{
	std::lock_guard<std::mutex> l(mu_);
	worlds_.clear();
	++gen_;
}

// ---- payloads ----------------------------------------------------------------------------------
bool SendColClear(ByteRingWriter &ring, std::uint32_t epoch)
{
	std::uint8_t *p = ring.Begin(P::kColClear, 4);
	if (p == nullptr)
		return false;
	std::memcpy(p, &epoch, 4);
	ring.Commit();
	return true;
}

bool SendWeaponIcon(ByteRingWriter &ring, std::uint32_t hash, std::uint32_t w, std::uint32_t h, const std::uint8_t *rgba, std::size_t bytes)
{
	if (hash == 0 || rgba == nullptr || w < 1 || h < 1 || w > P::kWeaponIconMaxSide || h > P::kWeaponIconMaxSide
		|| bytes != static_cast<std::size_t>(w) * h * 4)
		return false;
	const std::uint32_t payload = static_cast<std::uint32_t>(P::kWeaponIconHeaderBytes + bytes);
	std::uint8_t *p = ring.Begin(P::kColWeaponIcon, payload);
	if (p == nullptr)
		return false;
	P::WeaponIcon hdr{};
	hdr.hash = hash;
	hdr.w = static_cast<std::uint16_t>(w);
	hdr.h = static_cast<std::uint16_t>(h);
	hdr.format = P::kIconRgba8;
	std::memcpy(p, &hdr, sizeof hdr);
	std::memcpy(p + P::kWeaponIconHeaderBytes, rgba, bytes);
	ring.Commit();
	return true;
}

void BuildRegionNow(const MapView &view, const DynSnapshot *dyn, const DigStore &dig, std::uint32_t worldId, int rx, int ry, int rz,
	std::uint32_t epoch, std::vector<std::uint8_t> &tris, std::vector<std::uint8_t> &region, RegionInfo *info)
{
	const double t0 = NowMs();
	mcol::RegionJob job;
	mcol::GatherRegion(view.data->map, view.index, rx, ry, rz, epoch, mcol::GatherOptions{}, job);
	if (dyn != nullptr && dyn->frame == view.frame)
		mcol::GatherAppend(view.data->map.materials, dyn->index, mcol::GatherOptions{}, job);
	const double t1 = NowMs();
	// Dug cells near the region, and wherever its diggable triangles reach (they are sent whole),
	// bounded to 32 blocks around it.
	const int S = static_cast<int>(P::kColRegionSize);
	int lo[3] = { rx * S - 1, ry * S - 1, rz * S - 1 }, hi[3] = { rx * S + S, ry * S + S, rz * S + S };
	const int clo[3] = { rx * S - 32, ry * S - 32, rz * S - 32 }, chi[3] = { rx * S + S + 32, ry * S + S + 32, rz * S + S + 32 };
	auto grow = [&](const P::ColTri &t) {
		if (!(t.flags & P::kTriDiggable))
			return;
		for (int v = 0; v < 3; ++v)
			for (int k = 0; k < 3; ++k)
			{
				float f = t.v[3 * v + k];
				if (!std::isfinite(f))
					continue;
				int c = static_cast<int>(std::floor(std::max(std::min(f, 1e7f), -1e7f)));
				lo[k] = std::max(clo[k], std::min(lo[k], c));
				hi[k] = std::min(chi[k], std::max(hi[k], c));
			}
	};
	for (const auto &t : job.tris)
		grow(t);
	for (const auto &t : job.convexFaces)
		grow(t);
	dig.Collect(worldId, lo, hi, job.dug);
	mcol::BuildColTrisPayload(job, tris);
	const double t2 = NowMs();
	mcol::BuildColRegionPayload(job, region);
	const double t3 = NowMs();
	if (info != nullptr)
	{
		info->rx = rx;
		info->ry = ry;
		info->rz = rz;
		P::ColRegion h{};
		std::memcpy(&h, tris.data(), sizeof h);
		info->tris = h.count;
		info->ghosts = 0;
		for (std::uint32_t i = 0; i < h.count; ++i)
		{
			std::uint32_t flags;
			std::memcpy(&flags, tris.data() + sizeof h + i * sizeof(P::ColTri) + 36, 4);
			info->ghosts += (flags & P::kTriGhost) ? 1 : 0;
		}
		std::memcpy(&h, region.data(), sizeof h);
		info->blocks = h.count;
		info->bytes = static_cast<std::uint32_t>(tris.size() + region.size());
		info->gatherMs = static_cast<float>(t1 - t0);
		info->trisMs = static_cast<float>(t2 - t1);
		info->voxelMs = static_cast<float>(t3 - t2);
	}
}

// ---- streamer ----------------------------------------------------------------------------------
std::uint64_t Streamer::Key(int rx, int ry, int rz)
{
	return (std::uint64_t(std::uint32_t(rx) & 0x1FFFFF) << 42) | (std::uint64_t(std::uint32_t(ry) & 0x1FFFFF) << 21) |
		(std::uint32_t(rz) & 0x1FFFFF);
}

Streamer::~Streamer()
{
	Shutdown();
}

void Streamer::Shutdown()
{
	shared_->epoch.store(epoch_ + 0x80000000u);  // whatever is still queued skips its work
	{
		std::lock_guard<std::mutex> l(shared_->mu);
		shared_->done.clear();
	}
	shared_ = std::make_shared<Shared>();
	shared_->epoch.store(epoch_);
	inflight_.clear();
	ready_.clear();
	dynMarks_.clear();
}

void Streamer::Reset(std::uint32_t epoch)
{
	epoch_ = epoch;
	shared_->epoch.store(epoch);
	clearPending_ = true;
	harvested_.clear();
	inflight_.clear();
	ready_.clear();
	urgent_.clear();
	sent_.clear();
	dynMarks_.clear();
	digSent_.clear();
	digPending_.clear();
	digDirty_.clear();
	digGen_ = ~std::uint64_t(0);
}

void Streamer::Resend()
{
	harvested_.clear();
	digSent_.clear();
	digGen_ = ~std::uint64_t(0);
}

void Streamer::SetView(std::shared_ptr<const MapView> view, std::uint32_t worldId)
{
	bool had = view_ != nullptr;
	view_ = std::move(view);
	worldId_ = worldId;
	if (had)
		Reset(epoch_);  // what MC has came from the old view: clear it (same epoch is fine)
	else
	{
		harvested_.clear();
		ready_.clear();
		inflight_.clear();
	}
}

void Streamer::DigChanged(const std::vector<std::array<int, 3>> &cells)
{
	const int S = static_cast<int>(P::kColRegionSize);
	if (digDirty_.size() > 65536)
		digDirty_.clear();  // nobody around to stream for (the dig pass rescans anyway)
	std::unordered_set<std::uint64_t> queued;
	for (const auto &r : urgent_)
		queued.insert(Key(r[0], r[1], r[2]));
	for (const auto &b : cells)
		// Triangles go out with every region they come within half a block of.
		for (int rx = FloorDiv(b[0] - 1, S); rx <= FloorDiv(b[0] + 1, S); ++rx)
			for (int ry = FloorDiv(b[1] - 1, S); ry <= FloorDiv(b[1] + 1, S); ++ry)
				for (int rz = FloorDiv(b[2] - 1, S); rz <= FloorDiv(b[2] + 1, S); ++rz)
					if (queued.insert(Key(rx, ry, rz)).second)
					{
						urgent_.push_back({ rx, ry, rz });
						digSent_.erase(Key(rx, ry, rz));
						digDirty_.push_back({ rx, ry, rz });  // far ones go out again from the dig pass
					}
}

void Streamer::RegionsChanged(const std::vector<std::array<int, 3>> &regions, double markMs)
{
	std::unordered_set<std::uint64_t> queued;
	for (const auto &r : urgent_)
		queued.insert(Key(r[0], r[1], r[2]));
	for (const auto &r : regions)
	{
		const std::uint64_t key = Key(r[0], r[1], r[2]);
		dynMarks_[key] = markMs;  // the newest change counts
		++stats_.dynMarked;
		if (queued.insert(key).second)
			urgent_.push_back(r);
	}
	if (dynMarks_.size() > 65536)
		dynMarks_.clear();  // regions never sent (far from everyone): forget them
}

void Streamer::Queue(int rx, int ry, int rz, double now)
{
	const std::uint64_t key = Key(rx, ry, rz);
	harvested_[key] = now;
	inflight_.insert(key);
	++stats_.regionsQueued;
	std::shared_ptr<const MapView> view = view_;
	std::shared_ptr<const DynSnapshot> dyn = dyn_;
	std::shared_ptr<Shared> sh = shared_;
	const DigStore *dig = &dig_;
	const std::uint32_t epoch = epoch_, worldId = worldId_;
	worker_.Post([view, dyn, sh, dig, epoch, worldId, rx, ry, rz, now] {
		if (sh->epoch.load() != epoch)
			return;
		Result r;
		try
		{
			BuildRegionNow(*view, dyn.get(), *dig, worldId, rx, ry, rz, epoch, r.tris, r.region, &r.info);
		}
		catch (...)
		{
			// bad_alloc on a pathological region: report it, or its key stays in flight for good
			r = Result{};
			r.failed = true;
		}
		r.info.rx = rx;
		r.info.ry = ry;
		r.info.rz = rz;
		r.epoch = epoch;
		r.viewId = view->id;
		r.queuedMs = now;
		std::lock_guard<std::mutex> l(sh->mu);
		sh->done.push_back(std::move(r));
	});
}

bool Streamer::WriteResult(const Result &r, ByteRingWriter &ring)
{
	auto msg = [](std::size_t payload) { return (8 + static_cast<std::uint64_t>(payload) + 7) & ~std::uint64_t(7); };
	const std::uint64_t need = msg(r.tris.size()) + msg(r.region.size());
	// Both or neither: a wrap pad is always shorter than the message after it, so twice the pair's
	// size covers any padding.
	if (ring.Free() < 2 * need)
		return false;
	std::uint8_t *p = ring.Begin(P::kColTris, static_cast<std::uint32_t>(r.tris.size()));
	if (p == nullptr)
		return false;
	std::memcpy(p, r.tris.data(), r.tris.size());
	ring.Commit();
	p = ring.Begin(P::kColRegion, static_cast<std::uint32_t>(r.region.size()));
	if (p == nullptr)
		return false;  // can't happen after the check above, short of MC writing our head
	std::memcpy(p, r.region.data(), r.region.size());
	ring.Commit();
	return true;
}

int Streamer::Update(const std::vector<Pos> &players, ByteRingWriter &ring, double budgetMs)
{
	const double t0 = NowMs();
	if (offsets_.empty())
	{
		for (int dx = -kRadius; dx <= kRadius; ++dx)
			for (int dz = -kRadius; dz <= kRadius; ++dz)
				for (int dy = -kBelow; dy <= kAbove; ++dy)
					offsets_.push_back({ dx, dy, dz });
		std::stable_sort(offsets_.begin(), offsets_.end(),
			[](const std::array<int, 3> &a, const std::array<int, 3> &b) {
				return a[0] * a[0] + a[2] * a[2] + a[1] * a[1] * 2 < b[0] * b[0] + b[2] * b[2] + b[1] * b[1] * 2;
			});
	}
	if (ring.base == nullptr)
		return 0;
	if (clearPending_)
	{
		if (!SendColClear(ring, epoch_))
		{
			++stats_.ringWaits;
			return 0;
		}
		clearPending_ = false;
		++stats_.clears;
		++stats_.messages;
		stats_.bytes += 12;
	}

	// Finished jobs.
	{
		std::deque<Result> done;
		{
			std::lock_guard<std::mutex> l(shared_->mu);
			done.swap(shared_->done);
		}
		for (auto &r : done)
		{
			if (r.epoch != epoch_ || view_ == nullptr || r.viewId != view_->id)
			{
				++stats_.stale;
				continue;
			}
			const std::uint64_t key = Key(r.info.rx, r.info.ry, r.info.rz);
			inflight_.erase(key);
			if (r.failed)
			{
				++stats_.jobErrors;
				harvested_.erase(key);  // picked again by the region loop
				continue;
			}
			ready_.push_back(std::move(r));
		}
	}
	int written = 0;
	while (!ready_.empty() && NowMs() - t0 < budgetMs)
	{
		Result &r = ready_.front();
		if (r.tris.size() + r.region.size() > ring.dataBytes / 8)
		{
			++stats_.stale;  // can never fit (a pathological region): drop it
			ready_.pop_front();
			continue;
		}
		if (!WriteResult(r, ring))
		{
			++stats_.ringWaits;
			break;
		}
		r.info.sentMs = NowMs();
		const float ms = r.info.gatherMs + r.info.trisMs + r.info.voxelMs;
		stats_.gatherMs += r.info.gatherMs;
		stats_.trisMs += r.info.trisMs;
		stats_.voxelMs += r.info.voxelMs;
		stats_.maxRegionMs = std::max(stats_.maxRegionMs, ms);
		++stats_.regionsSent;
		stats_.messages += 2;
		stats_.bytes += r.tris.size() + r.region.size() + 16;
		const std::uint64_t key = Key(r.info.rx, r.info.ry, r.info.rz);
		sent_[key] = r.info;
		auto mark = dynMarks_.find(key);
		if (mark != dynMarks_.end() && r.queuedMs >= mark->second)
		{
			const float lat = static_cast<float>(r.info.sentMs - mark->second);
			++stats_.dynSent;
			stats_.dynLatencySumMs += lat;
			stats_.dynLatencyLastMs = lat;
			stats_.dynLatencyMaxMs = std::max(stats_.dynLatencyMaxMs, lat);
			dynMarks_.erase(mark);
		}
		ready_.pop_front();
		++written;
	}

	// Pick regions around the players: nearest first, the ones next to a player again every second.
	if (view_ != nullptr && !players.empty() && ready_.size() < kMaxInflight)
	{
		const int S = static_cast<int>(P::kColRegionSize);
		const double now = NowMs();
		std::vector<std::array<int, 3>> centres;
		for (const Pos &p : players)
		{
			if (!std::isfinite(p.x) || !std::isfinite(p.y) || !std::isfinite(p.z) || std::fabs(p.x) > 3e7f || std::fabs(p.y) > 3e7f ||
				std::fabs(p.z) > 3e7f)
				continue;
			centres.push_back({ static_cast<int>(std::floor(p.x / S)), static_cast<int>(std::floor(p.y / S)),
				static_cast<int>(std::floor(p.z / S)) });
		}
		auto nearAny = [&](const std::array<int, 3> &r, int slack) {
			for (const auto &c : centres)
				if (std::abs(r[0] - c[0]) <= kRadius + slack && std::abs(r[2] - c[2]) <= kRadius + slack && r[1] - c[1] >= -kBelow - slack &&
					r[1] - c[1] <= kAbove + slack)
					return true;
			return false;
		};
		// Regions around blocks just dug: their collision changed.
		while (!urgent_.empty() && inflight_.size() < kMaxInflight)
		{
			const auto r = urgent_.back();
			urgent_.pop_back();
			const std::uint64_t key = Key(r[0], r[1], r[2]);
			if (!nearAny(r, 1))
			{
				harvested_.erase(key);  // far away: sent again whenever it's needed
				dynMarks_.erase(key);   // (and not a dynamic change's latency then)
				continue;
			}
			if (inflight_.count(key))
			{
				harvested_.erase(key);  // in flight with the old dig state: send once more after it
				continue;
			}
			++stats_.urgent;
			Queue(r[0], r[1], r[2], now);
		}
		for (const auto &o : offsets_)
		{
			if (inflight_.size() >= kMaxInflight)
				break;
			for (const auto &c : centres)
			{
				const int rx = c[0] + o[0], ry = c[1] + o[1], rz = c[2] + o[2];
				const std::uint64_t key = Key(rx, ry, rz);
				const bool isNear = std::abs(o[0]) <= 1 && std::abs(o[2]) <= 1 && o[1] >= -1 && o[1] <= 0;
				auto it = harvested_.find(key);
				if (it != harvested_.end() && !(isNear && now - it->second > kRefreshNearMs))
					continue;
				if (inflight_.count(key))
					continue;
				if (it != harvested_.end())
					++stats_.refreshes;
				Queue(rx, ry, rz, now);
				if (inflight_.size() >= kMaxInflight)
					break;
			}
		}
		// Regions around dug cells beyond the near ones (P5c): Minecraft builds a hole's walls from
		// the map geometry there, so without them a hole seen from afar (another player's, or one
		// dug before a rejoin) shows no walls until someone comes near. Rescanned every
		// kDigScanMs, or soon after the dug cells change; each region goes out once per epoch.
		const std::uint64_t gen = dig_.Generation();
		if (now - digScanMs_ >= kDigScanMs || (gen != digGen_ && now - digScanMs_ >= kDigScanMinMs))
		{
			digScanMs_ = now;
			digGen_ = gen;
			digPending_.clear();
			std::unordered_set<std::uint64_t> seen;
			std::vector<std::array<int, 3>> cells;
			for (const auto &c : centres)
			{
				const int lo[3] = { (c[0] - kDigRange) * S, c[1] * S - kDigRangeY, (c[2] - kDigRange) * S };
				const int hi[3] = { (c[0] + kDigRange + 1) * S - 1, c[1] * S + kDigRangeY, (c[2] + kDigRange + 1) * S - 1 };
				cells.clear();
				dig_.Collect(worldId_, lo, hi, cells);
				for (const auto &b : cells)
					for (int rx = FloorDiv(b[0] - 1, S); rx <= FloorDiv(b[0] + 1, S); ++rx)
						for (int ry = FloorDiv(b[1] - 1, S); ry <= FloorDiv(b[1] + 1, S); ++ry)
							for (int rz = FloorDiv(b[2] - 1, S); rz <= FloorDiv(b[2] + 1, S); ++rz)
							{
								const std::array<int, 3> r{ rx, ry, rz };
								const std::uint64_t key = Key(rx, ry, rz);
								if (!digSent_.count(key) && !nearAny(r, 0) && seen.insert(key).second)
									digPending_.push_back(r);
							}
			}
		}
		// Dug cells that changed far away (the urgent loop above drops those): their regions again,
		// within kDigRange, even when nothing is dug there any more.
		for (const auto &r : digDirty_)
		{
			if (nearAny(r, 0))
				continue;
			for (const auto &c : centres)
				if (std::abs(r[0] - c[0]) <= kDigRange && std::abs(r[2] - c[2]) <= kDigRange && std::abs(r[1] - c[1]) * S <= kDigRangeY + S)
				{
					digPending_.push_back(r);
					break;
				}
		}
		digDirty_.clear();
		// At most half the job slots: the worker is one FIFO thread, a player's urgent region must
		// not wait behind a queue of far dig regions.
		while (!digPending_.empty() && inflight_.size() < kMaxInflight / 2)
		{
			const auto r = digPending_.back();
			digPending_.pop_back();
			const std::uint64_t key = Key(r[0], r[1], r[2]);
			if (digSent_.count(key))
				continue;
			// In flight already: that job may carry the dig state from before the change, so it
			// doesn't count; the next rescan sends the region (once its job is done).
			if (inflight_.count(key))
			{
				++stats_.digInflightSkips;
				continue;
			}
			digSent_.insert(key);
			++stats_.digQueued;
			Queue(r[0], r[1], r[2], now);
		}
		// Bound memory: forget regions far from every player (they go out again when needed).
		if (harvested_.size() > offsets_.size() * 4 * centres.size() + 64)
		{
			for (auto it = harvested_.begin(); it != harvested_.end();)
			{
				const std::array<int, 3> r = { static_cast<int>((it->first >> 42) & 0x1FFFFF), static_cast<int>((it->first >> 21) & 0x1FFFFF),
					static_cast<int>(it->first & 0x1FFFFF) };
				std::array<int, 3> s = r;
				for (int &v : s)
					if (v >= 0x100000)
						v -= 0x200000;
				if (!nearAny(s, 2) && !inflight_.count(it->first))
				{
					sent_.erase(it->first);
					dynMarks_.erase(it->first);
					it = harvested_.erase(it);
				}
				else
					++it;
			}
		}
	}
	stats_.inflight = static_cast<std::uint32_t>(inflight_.size());
	stats_.ready = static_cast<std::uint32_t>(ready_.size());
	stats_.known = static_cast<std::uint32_t>(sent_.size());
	stats_.lastWriteMs = static_cast<float>(NowMs() - t0);
	stats_.maxWriteMs = std::max(stats_.maxWriteMs, stats_.lastWriteMs);
	return written;
}

// ---- water -------------------------------------------------------------------------------------
int FillWater(const MapView &view, float x, float y, float z, std::int32_t &originX, std::int32_t &originZ,
	float surface[P::kWaterGridSize * P::kWaterGridSize])
{
	const int N = static_cast<int>(P::kWaterGridSize);
	originX = static_cast<std::int32_t>(std::floor(x)) - N / 2;
	originZ = static_cast<std::int32_t>(std::floor(z)) - N / 2;
	const float units = static_cast<float>(P::kUnitsPerBlock);
	const float oy = static_cast<float>(view.frame.originYUnits);  // v21: mc.y = (src.z + oy) / 40
	const float feetZ = y * units - oy;
	int count = 0;
	for (int dz = 0; dz < N; ++dz)
		for (int dx = 0; dx < N; ++dx)
		{
			const float mc[3] = { originX + dx + 0.5f, y, originZ + dz + 0.5f };
			const mcol::Vec3 s = mcol::FromMc(view.frame, mc);
			float best = P::kNoWater;
			bool found = false;
			for (const auto &w : view.data->map.water)
			{
				if (s.x < w.lo.x || s.x > w.hi.x || s.y < w.lo.y || s.y > w.hi.y || feetZ < w.lo.z - 2 * units)
					continue;
				// Inside the footprint: test at a height inside the volume.
				const float zz = std::min(std::max(feetZ, w.lo.z + 0.01f), std::max(w.lo.z, std::min(w.hi.z, w.surfaceZ) - 0.01f));
				bool inside = true;
				for (const auto &p : w.planes)
					if (p.n[0] * s.x + p.n[1] * s.y + p.n[2] * zz + p.d > 0.01f)
					{
						inside = false;
						break;
					}
				if (!inside)
					continue;
				const float sy = (w.surfaceZ + oy) / units;
				if (!found || std::fabs(sy - y) < std::fabs(best - y))
				{
					best = sy;
					found = true;
				}
			}
			surface[dz * N + dx] = best;
			count += found ? 1 : 0;
		}
	return count;
}
}  // namespace gc
