// Dynamic collision bookkeeping (see coldyn.hpp).
#include "coldyn.hpp"

#include "colstream.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <unordered_set>

namespace gc
{
const char *DynSourceName(DynSource s)
{
	switch (s)
	{
	case DynSource::kPhy: return "phy";
	case DynSource::kBrush: return "brush";
	case DynSource::kMesh: return "mesh";
	case DynSource::kObb: return "obb";
	default: return "none";
	}
}

namespace
{
float Dist(const mcol::Vec3 &a, const mcol::Vec3 &b)
{
	const float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
	return std::sqrt(dx * dx + dy * dy + dz * dz);
}

bool SameBox(const mcol::Vec3 &a, const mcol::Vec3 &b)
{
	return std::fabs(a.x - b.x) < 0.01f && std::fabs(a.y - b.y) < 0.01f && std::fabs(a.z - b.z) < 0.01f;
}

int FloorDivF(float v, int d)
{
	const float r = std::floor(v / static_cast<float>(d));
	return static_cast<int>(std::isfinite(r) ? std::max(std::min(r, 16777216.0f), -16777216.0f) : 0.0f);
}

// Appends src to dst (indices shifted).
void Append(mcol::Mesh &dst, const mcol::Mesh &src)
{
	const auto t0 = static_cast<std::uint32_t>(dst.tris.size()), p0 = static_cast<std::uint32_t>(dst.planes.size());
	const auto c0 = static_cast<std::int32_t>(dst.convexes.size());
	for (mcol::Tri t : src.tris)
	{
		if (t.convex >= 0)
			t.convex += c0;
		dst.tris.push_back(t);
	}
	dst.planes.insert(dst.planes.end(), src.planes.begin(), src.planes.end());
	for (mcol::Convex c : src.convexes)
	{
		c.firstTri += t0;
		c.firstPlane += p0;
		dst.convexes.push_back(c);
	}
}
}  // namespace

void DynamicSet::SetMap(std::shared_ptr<const MapData> map)
{
	map_ = std::move(map);
	models_.clear();
	ents_.clear();
	snap_.reset();
	stats_.entities = stats_.placed = stats_.fast = stats_.models = stats_.tris = stats_.convexes = 0;
}

void DynamicSet::Clear()
{
	SetMap(nullptr);
	stats_ = DynStats{};
}

bool DynamicSet::Known(const std::string &model) const
{
	if (!model.empty() && model[0] == '*')
		return true;  // brush models come from the map
	return models_.count(model) != 0;
}

DynSource DynamicSet::AddPhy(const std::string &model, const std::uint8_t *p, std::size_t n, std::string &err)
{
	Model m;
	if (p != nullptr && n > 0)
	{
		auto mesh = std::make_shared<mcol::Mesh>();
		if (mcol::DynamicFromPhy(mcol::Bytes{ p, n }, *mesh, m.surfaceprop, err))
		{
			m.source = DynSource::kPhy;
			m.mesh = std::move(mesh);
		}
	}
	else
		err = "no .phy";
	if (m.source == DynSource::kNone)
		m.source = DynSource::kObb;
	models_[model] = m;
	stats_.models = static_cast<std::uint32_t>(models_.size());
	// Entities already using this model pick it up on their next update.
	for (auto &e : ents_)
		if (e.second.model == model)
			e.second.source = DynSource::kNone;
	return m.source;
}

bool DynamicSet::AddMesh(const std::string &key, const std::vector<std::vector<mcol::Vec3>> &convexes, std::string &err)
{
	auto mesh = std::make_shared<mcol::Mesh>();
	Model m;
	if (mcol::DynamicFromTriangles(convexes, *mesh, err))
	{
		m.source = DynSource::kMesh;
		m.mesh = std::move(mesh);
	}
	else
		m.source = DynSource::kObb;
	models_[key] = m;
	stats_.models = static_cast<std::uint32_t>(models_.size());
	for (auto &e : ents_)
		if (e.second.model == key)
			e.second.source = DynSource::kNone;
	return m.source == DynSource::kMesh;
}

// Picks the entity's model-space mesh. False: nothing usable (not even an OBB).
bool DynamicSet::Resolve(Ent &e, const DynSample &s)
{
	e.model = s.model;
	e.mins = s.mins;
	e.maxs = s.maxs;
	e.local.reset();
	e.surfaceprop.clear();
	e.source = DynSource::kNone;
	if (!s.model.empty() && s.model[0] == '*' && map_)
	{
		char *end = nullptr;
		const long n = std::strtol(s.model.c_str() + 1, &end, 10);
		auto it = map_->map.brushModels.find(static_cast<int>(n));
		if (end != s.model.c_str() + 1 && *end == '\0' && it != map_->map.brushModels.end() && !it->second.convexes.empty())
		{
			// Cached per map: copied once per brush model.
			auto &m = models_[s.model];
			if (m.source != DynSource::kBrush || !m.mesh)
			{
				auto mesh = std::make_shared<mcol::Mesh>();
				mcol::DynamicFromMesh(it->second, *mesh);
				m.source = DynSource::kBrush;
				m.mesh = std::move(mesh);
				stats_.models = static_cast<std::uint32_t>(models_.size());
			}
			e.source = DynSource::kBrush;
			e.local = m.mesh;
			return true;
		}
	}
	auto it = models_.find(s.model);
	if (it != models_.end() && it->second.mesh && (it->second.source == DynSource::kPhy || it->second.source == DynSource::kMesh))
	{
		e.source = it->second.source;
		e.local = it->second.mesh;
		e.surfaceprop = it->second.surfaceprop;
		return true;
	}
	auto box = std::make_shared<mcol::Mesh>();
	if (!mcol::DynamicFromBox(s.mins, s.maxs, *box))
		return false;
	e.source = DynSource::kObb;
	e.local = std::move(box);
	return true;
}

// The regions (MC) within half a block of every convex of `world` (gather's halo).
void DynamicSet::Mark(const mcol::Mesh &world, const mcol::McFrame &frame, std::vector<std::array<int, 3>> &regions)
{
	const int S = static_cast<int>(P::kColRegionSize);
	const float U = static_cast<float>(P::kUnitsPerBlock);
	std::unordered_set<std::uint64_t> seen;
	std::vector<std::array<int, 3>> mine;
	for (const auto &c : world.convexes)
	{
		// Source AABB -> MC AABB (x, z, -y)
		const float oy = static_cast<float>(frame.originYUnits);
		const float lo[3] = { c.lo.x / U + frame.originX - 0.5f, (c.lo.z + oy) / U - 0.5f, -c.hi.y / U + frame.originZ - 0.5f };
		const float hi[3] = { c.hi.x / U + frame.originX + 0.5f, (c.hi.z + oy) / U + 0.5f, -c.lo.y / U + frame.originZ + 0.5f };
		const int x0 = FloorDivF(lo[0], S), y0 = FloorDivF(lo[1], S), z0 = FloorDivF(lo[2], S);
		const int x1 = FloorDivF(hi[0], S), y1 = FloorDivF(hi[1], S), z1 = FloorDivF(hi[2], S);
		const std::int64_t n = (std::int64_t(x1) - x0 + 1) * (std::int64_t(y1) - y0 + 1) * (std::int64_t(z1) - z0 + 1);
		if (n <= 0 || n > static_cast<std::int64_t>(kMaxMarkRegions) || mine.size() + static_cast<std::size_t>(n) > kMaxMarkRegions)
		{
			++stats_.tooBigToMark;
			return;  // a huge entity: the regions next to the players are refreshed every second anyway
		}
		for (int x = x0; x <= x1; ++x)
			for (int y = y0; y <= y1; ++y)
				for (int z = z0; z <= z1; ++z)
					if (seen.insert(Streamer::Key(x, y, z)).second)
						mine.push_back({ x, y, z });
	}
	stats_.regionsMarked += mine.size();
	regions.insert(regions.end(), mine.begin(), mine.end());
}

bool DynamicSet::Update(const std::vector<DynSample> &samples, double nowMs, const mcol::McFrame &frame, std::vector<std::array<int, 3>> &regions)
{
	const double t0 = NowMs();
	++stats_.scans;
	bool changed = false;
	for (auto &e : ents_)
		e.second.seen = false;
	for (const DynSample &s : samples)
	{
		Ent &e = ents_[s.ent];
		const bool first = e.lastMs == 0;
		e.seen = true;
		bool force = false;
		if (e.source == DynSource::kNone || e.model != s.model || (e.source == DynSource::kObb && (!SameBox(e.mins, s.mins) || !SameBox(e.maxs, s.maxs))))
		{
			if (!Resolve(e, s))
			{
				if (e.placed)
				{
					Mark(e.world, frame, regions);
					e.placed = false;
					e.world = mcol::Mesh{};
					changed = true;
				}
				++stats_.placeFailures;
				continue;
			}
			force = true;
		}
		// Speed since the previous sample (any scan rate).
		const double dt = first ? 0.0 : (nowMs - e.lastMs) / 1000.0;
		const float speed = dt > 1e-3 ? static_cast<float>(Dist(s.pos, e.lastPos) / dt) : 0.0f;
		const float turn = dt > 1e-3 ? static_cast<float>(mcol::AngleBetween(s.ang, e.lastAng) / dt) : 0.0f;
		e.lastPos = s.pos;
		e.lastAng = s.ang;
		e.lastMs = nowMs;
		if (s.physics && !s.asleep && (speed > kFastUnitsPerSec || turn > kFastDegPerSec))
		{
			if (!e.fast)
				++stats_.fastSkips;
			e.fast = true;
			if (e.placed)
			{
				// Out until it settles: where it was is free now, and where it will be isn't known.
				Mark(e.world, frame, regions);
				e.placed = false;
				e.world = mcol::Mesh{};
				++stats_.removals;
				changed = true;
			}
			continue;
		}
		e.fast = false;
		const bool moved = force || !e.placed || Dist(s.pos, e.placedPos) > kMoveUnits || mcol::AngleBetween(s.ang, e.placedAng) > kTurnDeg;
		if (!moved)
			continue;
		if (e.placed && !force && nowMs - e.placedMs < kMinIntervalMs)
		{
			++stats_.rateLimited;  // placed again on a later scan (compared with where it was placed)
			continue;
		}
		mcol::Mesh world;
		if (!mcol::PlaceDynamic(*e.local, s.pos, s.ang, s.ent, world))
		{
			++stats_.placeFailures;
			if (e.placed)
			{
				Mark(e.world, frame, regions);
				e.placed = false;
				e.world = mcol::Mesh{};
				changed = true;
			}
			continue;
		}
		if (e.placed)
			Mark(e.world, frame, regions);
		Mark(world, frame, regions);
		e.world = std::move(world);
		e.placed = true;
		e.placedPos = s.pos;
		e.placedAng = s.ang;
		e.placedMs = nowMs;
		++e.placements;
		++stats_.placements;
		changed = true;
	}
	for (auto it = ents_.begin(); it != ents_.end();)
	{
		if (it->second.seen)
		{
			++it;
			continue;
		}
		if (it->second.placed)
		{
			Mark(it->second.world, frame, regions);
			++stats_.removals;
			changed = true;
		}
		// Per-entity mesh models ("#mesh:<ent>:<creationid>") die with their entity; path models stay cached.
		if (it->second.model.compare(0, 6, "#mesh:") == 0)
		{
			models_.erase(it->second.model);
			stats_.models = static_cast<std::uint32_t>(models_.size());
		}
		it = ents_.erase(it);
	}
	const bool frameChanged = snap_ && snap_->frame != frame;
	if (frameChanged)
		for (const auto &e : ents_)
			if (e.second.placed)
				Mark(e.second.world, frame, regions);  // regions sent meanwhile had no dynamic layer
	if (changed || frameChanged || !snap_)
		Rebuild(frame, nowMs);
	stats_.entities = static_cast<std::uint32_t>(ents_.size());
	stats_.fast = 0;
	for (const auto &e : ents_)
		stats_.fast += e.second.fast ? 1 : 0;
	stats_.lastUpdateMs = static_cast<float>(NowMs() - t0);
	stats_.maxUpdateMs = std::max(stats_.maxUpdateMs, stats_.lastUpdateMs);
	return changed || frameChanged;
}

void DynamicSet::Rebuild(const mcol::McFrame &frame, double nowMs)
{
	const double t0 = NowMs();
	auto s = std::make_shared<DynSnapshot>();
	std::uint32_t placed = 0;
	for (const auto &e : ents_)
		if (e.second.placed)
		{
			Append(s->mesh, e.second.world);
			++placed;
		}
	s->frame = frame;
	s->id = ++snapIds_;
	s->entities = placed;
	s->builtMs = nowMs;
	s->index.Build(s->mesh, s->frame);
	stats_.placed = placed;
	stats_.tris = static_cast<std::uint32_t>(s->mesh.tris.size());
	stats_.convexes = static_cast<std::uint32_t>(s->mesh.convexes.size());
	++stats_.snapshots;
	snap_ = std::move(s);
	stats_.lastSnapshotMs = static_cast<float>(NowMs() - t0);
}

std::vector<DynamicSet::EntInfo> DynamicSet::Info() const
{
	std::vector<EntInfo> out;
	out.reserve(ents_.size());
	for (const auto &e : ents_)
		out.push_back(EntInfo{ e.first, e.second.model, e.second.source, e.second.placed, e.second.fast, e.second.placedMs, e.second.placements,
			static_cast<std::uint32_t>(e.second.world.tris.size()), e.second.placed ? e.second.placedPos : e.second.lastPos, e.second.surfaceprop });
	std::sort(out.begin(), out.end(), [](const EntInfo &a, const EntInfo &b) { return a.ent < b.ent; });
	return out;
}
}  // namespace gc
