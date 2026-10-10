// See blockmesh.hpp.
#include "blockmesh.hpp"
#include "mapcol/bitops.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace gc
{
namespace blk
{
float Curve(float l)
{
	return l / (4.0f - 3.0f * l);
}

float SrgbDecode(float v)
{
	v = v < 0 ? 0 : v > 1 ? 1 : v;
	// double pow: powf would need GLIBC_2.27 (soldier has 2.28, but the modules stay old)
	return v <= 0.04045f ? v / 12.92f : static_cast<float>(std::pow((v + 0.055) / 1.055, 2.4));
}

float FaceShade(std::uint32_t code, const float n[3])
{
	switch (code)
	{
	case 1:  // DOWN
		return 0.5f;
	case 2:  // UP
		return 1.0f;
	case 3:  // NORTH
	case 4:  // SOUTH
		return 0.8f;
	case 5:  // WEST
	case 6:  // EAST
		return 0.6f;
	case kNormalFromTriangle:
		// Minecraft's per-axis brightness, weighted by the squared normal (sums to 1).
		return n[0] * n[0] * 0.6f + n[2] * n[2] * 0.8f + n[1] * n[1] * (n[1] > 0 ? 1.0f : 0.5f);
	default:  // 0: no normal (plants): lit as if facing up
		return 1.0f;
	}
}

void LightRgb(std::uint32_t light, float daylight, float out[3])
{
	const float block = Curve(static_cast<float>(light & 0xF) / 15.0f);
	const float sky = Curve(static_cast<float>((light >> 8) & 0xF) / 15.0f);
	const float s = daylight * (0.3f + 0.7f * sky);
	static const float kWarm[3] = { 1.0f, 0.85f, 0.65f };
	for (int i = 0; i < 3; ++i)
		out[i] = std::max(s, block * kWarm[i]);
}

void BakeColor(std::uint32_t rgba, std::uint32_t light, float shade, const BakeConfig &cfg, std::uint8_t out[4])
{
	float lit[3];
	LightRgb(light, cfg.daylight, lit);
	std::uint8_t c[3];
	for (int i = 0; i < 3; ++i)
	{
		float v = static_cast<float>((rgba >> (8 * i)) & 0xFF) / 255.0f * lit[i] * shade;
		v = v < 0 ? 0 : v > 1 ? 1 : v;
		if (cfg.linearVertexColor)
			v = SrgbDecode(v);
		c[i] = static_cast<std::uint8_t>(v * 255.0f + 0.5f);
	}
	const std::uint8_t a = static_cast<std::uint8_t>(rgba >> 24);
	if (cfg.rgbaOrder)
	{
		out[0] = c[0];
		out[1] = c[1];
		out[2] = c[2];
	}
	else
	{
		out[0] = c[2];
		out[1] = c[1];
		out[2] = c[0];
	}
	out[3] = a;
}

void McToSource(double mx, double my, double mz, std::int32_t ox, std::int32_t oz, std::int32_t oy, float out[3])
{
	out[0] = static_cast<float>((mx - ox) * 40.0);
	out[1] = static_cast<float>(-(mz - oz) * 40.0);
	out[2] = static_cast<float>(my * 40.0 - oy);
}

namespace
{
// MC Direction by ordinal + 1, in MC axes (x east, y up, z south).
const float kDirN[7][3] = { { 0, 1, 0 }, { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };

// The triangle's own normal in MC axes (from MC's CCW order: (b - a) x (c - a)); up if degenerate.
void OwnNormal(const P::RenVertex &a, const P::RenVertex &b, const P::RenVertex &c, float tn[3])
{
	const float ax = b.x - a.x, ay = b.y - a.y, az = b.z - a.z;
	const float cx = c.x - a.x, cy = c.y - a.y, cz = c.z - a.z;
	tn[0] = ay * cz - az * cy;
	tn[1] = az * cx - ax * cz;
	tn[2] = ax * cy - ay * cx;
	const float len = std::sqrt(tn[0] * tn[0] + tn[1] * tn[1] + tn[2] * tn[2]);
	if (len > 1e-12f)
		for (int i = 0; i < 3; ++i)
			tn[i] /= len;
	else
		tn[0] = 0, tn[1] = 1, tn[2] = 0;
}

std::int32_t FloorDiv4(std::int32_t v) { return v >= 0 ? v / 4 : -((-v + 3) / 4); }
}  // namespace

void TriangleNormal(const P::RenVertex &a, const P::RenVertex &b, const P::RenVertex &c, float out[3])
{
	const std::uint32_t code = (a.flags >> kNormalShift) & kNormalMask;
	if (code == kNormalFromTriangle)
		OwnNormal(a, b, c, out);
	else
		for (int i = 0; i < 3; ++i)
			out[i] = kDirN[code][i];
}

void TrianglePoint(const P::RenVertex &a, const P::RenVertex &b, const P::RenVertex &c, float out[3])
{
	float n[3];
	TriangleNormal(a, b, c, n);
	out[0] = (a.x + b.x + c.x) / 3.0f + 0.5f * n[0];
	out[1] = (a.y + b.y + c.y) / 3.0f + 0.5f * n[1];
	out[2] = (a.z + b.z + c.z) / 3.0f + 0.5f * n[2];
}

int CellIndex(const float p[3])
{
	int c[3];
	for (int i = 0; i < 3; ++i)
	{
		const float f = std::floor(p[i] / kCellBlocks);
		c[i] = f < 0 ? 0 : f > 3 ? 3 : static_cast<int>(f);
	}
	return c[0] + 4 * (c[1] + 4 * c[2]);
}

void BuildSection(std::int32_t sx, std::int32_t sy, std::int32_t sz, const P::RenVertex *v, std::uint32_t count, const BakeConfig &cfg,
	std::vector<OutVertex> &opaque, std::vector<OutVertex> &translucent, const float *cellLight)
{
	static thread_local SectionPasses out;
	BuildSectionPasses(sx, sy, sz, v, count, cfg, out, cellLight, nullptr, 0, 0);
	opaque.swap(out.pass[0]);
	translucent.swap(out.pass[1]);
}

// ---- animated sprites -------------------------------------------------------------------------------
bool AnimLayout::Add(const AtlasRect &r)
{
	for (const AnimSprite &s : sprites)
		if (s.ax == r.x && s.ay == r.y && s.w == r.w && s.h == r.h)
			return false;
	sprites.push_back(AnimSprite{ r.x, r.y, r.w, r.h, 0, 0 });
	// Shelf packing, tallest first (ties: atlas order), into a width that keeps it roughly square.
	std::sort(sprites.begin(), sprites.end(), [](const AnimSprite &a, const AnimSprite &b) {
		return a.h != b.h ? a.h > b.h : a.ay != b.ay ? a.ay < b.ay : a.ax < b.ax;
	});
	std::uint64_t area = 0;
	std::uint32_t maxW = 0;
	for (const AnimSprite &s : sprites)
	{
		area += static_cast<std::uint64_t>(s.w) * s.h;
		maxW = std::max(maxW, s.w);
	}
	w = 64;
	while (static_cast<std::uint64_t>(w) * w < area || w < maxW)
		w *= 2;
	std::uint32_t x = 0, y = 0, shelf = 0;
	for (AnimSprite &s : sprites)
	{
		if (x + s.w > w)
		{
			x = 0;
			y += shelf;
			shelf = 0;
		}
		s.dx = x;
		s.dy = y;
		x += s.w;
		shelf = std::max(shelf, s.h);
	}
	// power-of-two height (the width already is): the animated texture has no resample fallback
	h = 1;
	while (h < y + shelf)
		h *= 2;
	++version;
	return true;
}

void AnimLayout::Clear()
{
	if (!sprites.empty() || w != 0)
		++version;
	sprites.clear();
	w = h = 0;
}

int AnimLayout::Find(float px, float py) const
{
	for (std::size_t i = 0; i < sprites.size(); ++i)
	{
		const AnimSprite &s = sprites[i];
		if (px >= s.ax && px < s.ax + s.w && py >= s.ay && py < s.ay + s.h)
			return static_cast<int>(i);
	}
	return -1;
}

void BuildSectionPasses(std::int32_t sx, std::int32_t sy, std::int32_t sz, const P::RenVertex *v, std::uint32_t count, const BakeConfig &cfg,
	SectionPasses &passes, const float *cellLight, const AnimLayout *anim, std::uint32_t atlasW, std::uint32_t atlasH)
{
	for (auto &p : passes.pass)
		p.clear();
	const bool remap = anim != nullptr && anim->w > 0 && anim->h > 0 && atlasW > 0 && atlasH > 0;
	count -= count % 3;
	// The section origin relative to the slot origin, exact in doubles (MC coords reach ~3e7).
	const double bx = static_cast<double>(sx) * 16.0, by = static_cast<double>(sy) * 16.0, bz = static_cast<double>(sz) * 16.0;
	BakeConfig lit = cfg;
	for (std::uint32_t t = 0; t < count; t += 3)
	{
		const P::RenVertex *tri[3] = { &v[t], &v[t + 2], &v[t + 1] };  // MC is CCW: swap v1/v2
		int pass = (v[t].flags & kFlagTranslucent) ? 1 : 0;
		const AnimSprite *sprite = nullptr;
		if (remap)
		{
			const float cu = (v[t].u + v[t + 1].u + v[t + 2].u) / 3.0f * static_cast<float>(atlasW);
			const float cv = (v[t].v + v[t + 1].v + v[t + 2].v) / 3.0f * static_cast<float>(atlasH);
			const int i = anim->Find(cu, cv);
			if (i >= 0)
			{
				sprite = &anim->sprites[static_cast<std::size_t>(i)];
				pass += 2;
			}
		}
		auto &out = passes.pass[pass];
		float tn[3];
		OwnNormal(v[t], v[t + 1], v[t + 2], tn);
		// S: daylight times the GMod light of the triangle's cell (unsampled: 1)
		lit.daylight = cfg.daylight;
		if (cellLight != nullptr)
		{
			float pt[3];
			TrianglePoint(v[t], v[t + 1], v[t + 2], pt);
			const float s = cellLight[CellIndex(pt)];
			if (s >= 0)
				lit.daylight = cfg.daylight * s;
		}
		for (const P::RenVertex *s : tri)
		{
			OutVertex o;
			McToSource(bx + s->x, by + s->y, bz + s->z, cfg.ox, cfg.oz, cfg.oy, o.pos);
			o.pos[2] -= kTerrainDrop;  // Z1: under a coplanar map floor
			const std::uint32_t code = (s->flags >> kNormalShift) & kNormalMask;
			const float *n = code == kNormalFromTriangle ? tn : kDirN[code];
			o.normal[0] = n[0];
			o.normal[1] = -n[2];
			o.normal[2] = n[1];
			o.uv[0] = s->u;
			o.uv[1] = s->v;
			if (sprite != nullptr)
			{
				// atlas pixels -> the same spot of the sprite in the animated texture (clamped to it)
				const float px = std::min(std::max(s->u * atlasW, float(sprite->ax)), float(sprite->ax + sprite->w));
				const float py = std::min(std::max(s->v * atlasH, float(sprite->ay)), float(sprite->ay + sprite->h));
				o.uv[0] = (px - sprite->ax + sprite->dx) / static_cast<float>(anim->w);
				o.uv[1] = (py - sprite->ay + sprite->dy) / static_cast<float>(anim->h);
			}
			BakeColor(s->color, s->light, FaceShade(code, tn), lit, o.color);
			out.push_back(o);
		}
	}
}

std::vector<std::pair<std::uint32_t, std::uint32_t>> Chunks(std::uint32_t vertices)
{
	std::vector<std::pair<std::uint32_t, std::uint32_t>> out;
	vertices -= vertices % 3;
	for (std::uint32_t first = 0; first < vertices; first += kMaxMeshVertices)
		out.emplace_back(first, std::min(kMaxMeshVertices, vertices - first));
	return out;
}

// ---- Store -------------------------------------------------------------------------------------
void Store::OnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n)
{
	if (type < 16)
	{
		++counters.byType[type];
		counters.bytesByType[type] += n;
	}
	else
		++counters.unknown;
	switch (type)
	{
	case P::kRenSection:
		OnSection(p, n);
		break;
	case P::kRenClearAll:
		++counters.clears;
		Clear();
		break;
	case P::kRenAtlas:
		OnAtlas(p, n);
		break;
	case P::kRenAtlasRegion:
		OnAtlasRegion(p, n);
		break;
	case P::kRenLights:
		lights.OnMessage(p, n);
		break;
	default:
		// kRenSolids: not used by GMod. Texture / Avatar / Scene / Ragdoll: P3c (entities.cpp).
		break;
	}
}

void Store::OnSection(const std::uint8_t *p, std::uint32_t n)
{
	if (n < sizeof(P::RenSection))
	{
		++counters.malformed;
		return;
	}
	P::RenSection h;
	std::memcpy(&h, p, sizeof h);
	if (static_cast<std::uint64_t>(n) < sizeof h + static_cast<std::uint64_t>(h.vertexCount) * sizeof(P::RenVertex))
	{
		++counters.malformed;
		return;
	}
	const std::uint64_t key = Key(h.sx, h.sy, h.sz);
	const std::uint32_t count = h.vertexCount - h.vertexCount % 3;
	if (count == 0)
	{
		++counters.sectionRemovals;
		Remove(h.sx, h.sy, h.sz);
		return;
	}
	++counters.sectionUpdates;
	auto found = sections.find(key);
	if (found != sections.end() && (found->second.sx != h.sx || found->second.sy != h.sy || found->second.sz != h.sz))
	{
		// Another section's key: that one goes (released like a removal), this one takes the slot.
		// The key may now sit in dirty_ twice; TakeDirty only takes it once (`queued`).
		++counters.aliased;
		if (onRelease)
			onRelease(found->second);
		totalVertices -= found->second.raw.size();
		found->second = Section{};
	}
	Section &s = sections[key];
	totalVertices -= s.raw.size();
	s.sx = h.sx;
	s.sy = h.sy;
	s.sz = h.sz;
	s.raw.resize(count);
	std::memcpy(s.raw.data(), p + sizeof h, static_cast<std::size_t>(count) * sizeof(P::RenVertex));
	totalVertices += count;
	ComputeCells(s);
	s.dirty = true;
	if (!s.queued)
	{
		s.queued = true;
		dirty_.push_back(key);
	}
}

Section *Store::Find(std::int32_t sx, std::int32_t sy, std::int32_t sz)
{
	auto it = sections.find(Key(sx, sy, sz));
	if (it == sections.end() || it->second.sx != sx || it->second.sy != sy || it->second.sz != sz)
		return nullptr;
	return &it->second;
}

void Store::Remove(std::int32_t sx, std::int32_t sy, std::int32_t sz)
{
	auto it = sections.find(Key(sx, sy, sz));
	if (it == sections.end() || it->second.sx != sx || it->second.sy != sy || it->second.sz != sz)
		return;
	if (onRelease)
		onRelease(it->second);
	totalVertices -= it->second.raw.size();
	sections.erase(it);
}

void Store::Clear()
{
	for (auto &kv : sections)
		if (onRelease)
			onRelease(kv.second);
	sections.clear();
	dirty_.clear();
	totalVertices = 0;
	lights.Clear();
}

void Store::SetConfig(const BakeConfig &cfg)
{
	if (cfg == cfg_)
		return;
	if (cfg.ox != cfg_.ox || cfg.oz != cfg_.oz || cfg.oy != cfg_.oy)
	{
		// a new slot origin: the samples were taken at the old Source positions
		for (auto &kv : sections)
		{
			for (float &l : kv.second.cellLight)
				l = -1.0f;
			kv.second.cellNeed = kv.second.cellMask;
		}
	}
	cfg_ = cfg;
	MarkAllDirty();
}

void Store::MarkAllDirty()
{
	for (auto &kv : sections)
	{
		kv.second.dirty = true;
		if (!kv.second.queued)
		{
			kv.second.queued = true;
			dirty_.push_back(kv.first);
		}
	}
}

std::vector<std::uint64_t> Store::TakeDirty()
{
	std::vector<std::uint64_t> out;
	out.reserve(dirty_.size());
	for (std::uint64_t key : dirty_)
	{
		auto it = sections.find(key);
		if (it == sections.end() || !it->second.queued)
			continue;
		it->second.queued = false;
		if (it->second.dirty)
			out.push_back(key);
	}
	dirty_.clear();
	return out;
}

void Store::BackToFront(double x, double y, double z, std::vector<Section *> &out)
{
	out.clear();
	std::vector<std::pair<double, Section *>> d;
	d.reserve(sections.size());
	for (auto &kv : sections)
	{
		Section &s = kv.second;
		if (s.translucentVerts == 0)
			continue;
		float c[3];
		McToSource(s.sx * 16.0 + 8.0, s.sy * 16.0 + 8.0, s.sz * 16.0 + 8.0, cfg_.ox, cfg_.oz, cfg_.oy, c);
		const double dx = c[0] - x, dy = c[1] - y, dz = c[2] - z;
		d.emplace_back(dx * dx + dy * dy + dz * dz, &s);
	}
	std::sort(d.begin(), d.end(), [](const auto &a, const auto &b) { return a.first > b.first; });
	for (auto &e : d)
		out.push_back(e.second);
}

void Store::OnAtlas(const std::uint8_t *p, std::uint32_t n)
{
	if (n < sizeof(P::RenAtlas))
	{
		++counters.malformed;
		return;
	}
	P::RenAtlas h;
	std::memcpy(&h, p, sizeof h);
	const std::uint64_t bytes = static_cast<std::uint64_t>(h.width) * h.height * 4;
	if (h.width == 0 || h.height == 0 || h.width > 16384 || h.height > 16384 || n < sizeof h + bytes)
	{
		++counters.malformed;
		return;
	}
	++counters.atlases;
	atlas.w = h.width;
	atlas.h = h.height;
	atlas.bgra.resize(bytes);
	const std::uint8_t *src = p + sizeof h;
	if (h.flags & P::kRenPixBGRA)
		std::memcpy(atlas.bgra.data(), src, bytes);
	else
		for (std::uint64_t i = 0; i < bytes; i += 4)
		{
			atlas.bgra[i] = src[i + 2];
			atlas.bgra[i + 1] = src[i + 1];
			atlas.bgra[i + 2] = src[i];
			atlas.bgra[i + 3] = src[i + 3];
		}
	atlasFlags_ = h.flags;
	++atlas.version;
	atlas.fullDirty = true;
	atlas.regionDirty = false;
	atlas.dirtyRects.clear();
	// The new atlas may place the sprites elsewhere: they are learnt again from its regions.
	if (!atlas.anim.sprites.empty())
	{
		atlas.anim.Clear();
		MarkAllDirty();
	}
	atlas.animDirty = false;
}

void Store::OnAtlasRegion(const std::uint8_t *p, std::uint32_t n)
{
	if (n < sizeof(P::RenAtlasRegion))
	{
		++counters.malformed;
		return;
	}
	P::RenAtlasRegion h;
	std::memcpy(&h, p, sizeof h);
	const std::uint64_t bytes = static_cast<std::uint64_t>(h.width) * h.height * 4;
	if (atlas.w == 0 || h.width == 0 || h.height == 0 || static_cast<std::uint64_t>(h.x) + h.width > atlas.w ||
		static_cast<std::uint64_t>(h.y) + h.height > atlas.h || n < sizeof h + bytes)
	{
		++atlas.regionRejects;
		return;
	}
	const std::uint8_t *src = p + sizeof h;
	const bool bgra = (atlasFlags_ & P::kRenPixBGRA) != 0;
	const std::size_t row = static_cast<std::size_t>(h.width) * 4;
	std::vector<std::uint8_t> conv;
	bool changed = false;
	for (std::uint32_t y = 0; y < h.height; ++y)
	{
		std::uint8_t *d = atlas.bgra.data() + (static_cast<std::size_t>(h.y + y) * atlas.w + h.x) * 4;
		const std::uint8_t *s = src + y * row;
		if (!bgra)
		{
			conv.resize(row);
			for (std::size_t i = 0; i < row; i += 4)
			{
				conv[i] = s[i + 2];
				conv[i + 1] = s[i + 1];
				conv[i + 2] = s[i];
				conv[i + 3] = s[i + 3];
			}
			s = conv.data();
		}
		if (std::memcmp(d, s, row) != 0)
		{
			std::memcpy(d, s, row);
			changed = true;
		}
	}
	++atlas.regions;
	atlas.regionBytes += bytes;
	// A sprite seen for the first time: the animated texture's layout changes, so every section is
	// baked again (rare: the first ticks after an atlas).
	if (atlas.anim.Add(AtlasRect{ h.x, h.y, h.width, h.height }))
	{
		atlas.animDirty = true;
		MarkAllDirty();
	}
	if (changed)
		atlas.animDirty = true;
	if (!changed)
	{
		// Minecraft sends every animated sprite each tick; most frames last 2+ ticks.
		++atlas.regionsUnchanged;
		return;
	}
	atlas.regionDirty = true;
	for (const AtlasRect &r : atlas.dirtyRects)
		if (r.x == h.x && r.y == h.y && r.w == h.width && r.h == h.height)
			return;
	atlas.dirtyRects.push_back(AtlasRect{ h.x, h.y, h.width, h.height });
}

// ---- GMod's light per cell --------------------------------------------------------------------------
void Store::MarkDirty(std::uint64_t key, Section &s)
{
	s.dirty = true;
	if (!s.queued)
	{
		s.queued = true;
		dirty_.push_back(key);
	}
}

void Store::ComputeCells(Section &s)
{
	// per cell and face direction (dominant normal axis, 6): the sum of the faces' centres pushed
	// kProbePush out along their normals, and the count
	static thread_local float sum[kCellsPerSection][6][3];
	static thread_local std::uint32_t n[kCellsPerSection][6];
	std::memset(sum, 0, sizeof sum);
	std::memset(n, 0, sizeof n);
	std::uint64_t mask = 0;
	const std::size_t count = s.raw.size() - s.raw.size() % 3;
	for (std::size_t t = 0; t < count; t += 3)
	{
		float pt[3], nn[3];
		TrianglePoint(s.raw[t], s.raw[t + 1], s.raw[t + 2], pt);  // centroid + 0.5 n: picks the cell
		TriangleNormal(s.raw[t], s.raw[t + 1], s.raw[t + 2], nn);
		const int c = CellIndex(pt);
		mask |= std::uint64_t{ 1 } << c;
		int axis = 0;
		for (int i = 1; i < 3; ++i)
			if (std::fabs(nn[i]) > std::fabs(nn[axis]))
				axis = i;
		const int d = axis * 2 + (nn[axis] < 0 ? 1 : 0);
		for (int i = 0; i < 3; ++i)
			sum[c][d][i] += pt[i] + (kProbePush - 0.5f) * nn[i];
		++n[c][d];
	}
	std::uint64_t unsampled = 0;
	for (int c = 0; c < kCellsPerSection; ++c)
	{
		if (mask & (std::uint64_t{ 1 } << c))
		{
			int k = 0;
			for (int d = 0; d < 6; ++d)
				if (n[c][d] > 0)
				{
					for (int i = 0; i < 3; ++i)
						s.cellProbe[c][k][i] = sum[c][d][i] / static_cast<float>(n[c][d]);
					++k;
				}
			// the cell centre last
			s.cellProbe[c][k][0] = (c % 4) * kCellBlocks + kCellBlocks * 0.5f;
			s.cellProbe[c][k][1] = ((c / 4) % 4) * kCellBlocks + kCellBlocks * 0.5f;
			s.cellProbe[c][k][2] = (c / 16) * kCellBlocks + kCellBlocks * 0.5f;
			s.cellProbes[c] = static_cast<std::uint8_t>(k + 1);
			if (s.cellLight[c] < 0)
				unsampled |= std::uint64_t{ 1 } << c;
		}
		else
		{
			s.cellLight[c] = -1.0f;  // no faces there any more: a later one is sampled afresh
			s.cellProbes[c] = 0;
		}
	}
	s.cellMask = mask;
	s.cellNeed = mask & (s.cellNeed | unsampled);
}

void Store::LightQuery(std::size_t max, std::vector<LightProbe> &out)
{
	out.clear();
	const std::size_t n = sections.size();
	if (n == 0 || max == 0)
		return;
	static thread_local std::vector<Section *> order;
	order.clear();
	for (auto &kv : sections)
		order.push_back(&kv.second);
	const std::size_t start = lightCursor_ % n;
	for (int pass = 0; pass < 2; ++pass)
		for (std::size_t i = 0; i < n; ++i)
		{
			const std::size_t at = (start + i) % n;
			Section &s = *order[at];
			for (std::uint64_t need = s.cellNeed; need != 0; need &= need - 1)
			{
				const int c = gmodcraft::mapcol::Ctz64(need);
				if ((s.cellLight[c] < 0) != (pass == 0))
					continue;  // never-sampled cells first
				if (out.size() >= max)
				{
					lightCursor_ = at;  // the next call carries on here
					return;
				}
				LightProbe p;
				p.cx = s.sx * 4 + c % 4;
				p.cy = s.sy * 4 + (c / 4) % 4;
				p.cz = s.sz * 4 + c / 16;
				p.n = s.cellProbes[c];
				for (int k = 0; k < p.n; ++k)
					McToSource(s.sx * 16.0 + s.cellProbe[c][k][0], s.sy * 16.0 + s.cellProbe[c][k][1], s.sz * 16.0 + s.cellProbe[c][k][2], cfg_.ox, cfg_.oz, cfg_.oy,
						p.pos[k]);
				out.push_back(p);
			}
		}
	lightCursor_ = start;
}

bool Store::LightRefresh()
{
	if (LightPending() != 0)
		return false;  // the last sweep isn't done: refreshing now would starve its tail
	++light.refreshes;
	for (auto &kv : sections)
		kv.second.cellNeed = kv.second.cellMask;
	return true;
}

bool Store::LightSet(std::int32_t cx, std::int32_t cy, std::int32_t cz, float sv)
{
	const std::int32_t sx = FloorDiv4(cx), sy = FloorDiv4(cy), sz = FloorDiv4(cz);
	Section *s = Find(sx, sy, sz);
	if (s == nullptr || !(sv >= 0))
		return false;
	const int c = (cx - sx * 4) + 4 * ((cy - sy * 4) + 4 * (cz - sz * 4));
	const std::uint64_t bit = std::uint64_t{ 1 } << c;
	if (!(s->cellMask & bit))
		return false;
	s->cellNeed &= ~bit;
	++light.sets;
	sv = sv > 1 ? 1 : sv;
	const float old = s->cellLight[c];
	if (old >= 0 && std::fabs(sv - old) <= kLightEpsilon)
		return false;
	if (old < 0)
		++light.firstSamples;
	s->cellLight[c] = sv;
	// Re-bake only a section that was already baked without this value (a dirty one picks it up).
	if (!cfg_.cellLight || s->dirty || (old < 0 && !s->built))
		return false;
	MarkDirty(Key(sx, sy, sz), *s);
	++light.rebakes;
	return true;
}

std::size_t Store::LightCells() const
{
	std::size_t n = 0;
	for (auto &kv : sections)
		n += static_cast<std::size_t>(gmodcraft::mapcol::Popcount64(kv.second.cellMask));
	return n;
}

std::size_t Store::LightPending() const
{
	std::size_t n = 0;
	for (auto &kv : sections)
		n += static_cast<std::size_t>(gmodcraft::mapcol::Popcount64(kv.second.cellNeed));
	return n;
}
// ---- BlockLights (P3d) ---------------------------------------------------------------------------------
float LightRadiusBlocks(int level, int count)
{
	const float blocks = static_cast<float>(level + 1) * (1.0f + 0.12f * std::log2(static_cast<float>(std::max(count, 1))));
	return std::min(blocks, kLightMaxBlocks) * 0.8f;
}

float LightFlicker(std::uint8_t kind, float t)
{
	if (kind == P::kLightFlame)
		return 1.0f + 0.07f * std::sin(t * 9.1f) + 0.05f * std::sin(t * 23.7f + 1.3f) + 0.03f * std::sin(t * 4.3f + 0.7f);
	if (kind == P::kLightLava)
		return 1.0f + 0.08f * std::sin(t * 1.3f) + 0.03f * std::sin(t * 3.1f + 2.0f);
	return 1.0f;
}

void BlockLights::OnMessage(const std::uint8_t *p, std::uint32_t n)
{
	if (p == nullptr || n < sizeof(P::RenLights))
	{
		++malformed;
		return;
	}
	++messages;
	P::RenLights h;
	std::memcpy(&h, p, sizeof h);
	const std::uint64_t key = Key(h.sx, h.sy, h.sz);
	const std::uint64_t fit = (n - sizeof h) / sizeof(P::RenLight);
	if (h.count > fit)
		++malformed;  // short: keep what is there
	const std::uint64_t count = std::min<std::uint64_t>(h.count, fit);
	timer_ = 0;  // show a new torch at once
	if (count == 0)
	{
		bySection_.erase(key);
		return;
	}
	SectionLights &sl = bySection_[key];  // an aliased key (another section's) is simply replaced
	sl.sx = h.sx;
	sl.sy = h.sy;
	sl.sz = h.sz;
	sl.list.clear();
	for (std::uint64_t i = 0; i < count; ++i)
	{
		P::RenLight l;
		std::memcpy(&l, p + sizeof h + i * sizeof l, sizeof l);
		if (l.level == 0)
			continue;  // no weight: nothing to show
		const std::uint8_t top = static_cast<std::uint8_t>(l.color >> 24);
		LightEmitter e;
		e.x = h.sx * 16 + l.x;
		e.y = h.sy * 16 + l.y;
		e.z = h.sz * 16 + l.z;
		e.level = std::min<std::uint8_t>(l.level, 15);
		e.kind = static_cast<std::uint8_t>(top & 0x0F);  // bits 4-7: the hazard (not used for light)
		e.rgb = l.color & 0xFFFFFFu;
		sl.list.push_back(e);
	}
}

void BlockLights::Clear()
{
	bySection_.clear();
	for (LightSlot &s : slots)
		s = LightSlot{};
	timer_ = 0;
	lastCells = lastInRange = 0;
}

std::size_t BlockLights::Emitters() const
{
	std::size_t n = 0;
	for (const auto &kv : bySection_)
		n += kv.second.list.size();
	return n;
}

std::size_t BlockLights::CountIn(std::int32_t x0, std::int32_t y0, std::int32_t z0, std::int32_t x1, std::int32_t y1, std::int32_t z1) const
{
	const std::int32_t ax = std::min(x0, x1), bx = std::max(x0, x1), ay = std::min(y0, y1), by = std::max(y0, y1), az = std::min(z0, z1),
					   bz = std::max(z0, z1);
	std::size_t n = 0;
	for (const auto &kv : bySection_)
		for (const LightEmitter &e : kv.second.list)
			n += e.x >= ax && e.x <= bx && e.y >= ay && e.y <= by && e.z >= az && e.z <= bz;
	return n;
}

std::size_t BlockLights::Active() const
{
	std::size_t n = 0;
	for (const LightSlot &s : slots)
		n += s.active ? 1 : 0;
	return n;
}

float BlockLights::Intensity(const LightSlot &s) const
{
	return s.base * LightFlicker(s.kind, static_cast<float>(clock_) + s.phase);
}

bool BlockLights::Update(double dt, double px, double py, double pz, int budget)
{
	const double step = std::isfinite(dt) && dt > 0 ? std::min(dt, 1.0) : 0.0;
	clock_ += step;
	if (!std::isfinite(px) || !std::isfinite(py) || !std::isfinite(pz))
		return false;
	timer_ -= step;
	if (timer_ > 0 && budget == lastBudget_)
		return false;
	timer_ = kLightUpdateSeconds;
	Rebuild(px, py, pz, budget);
	return true;
}

void BlockLights::Rebuild(double px, double py, double pz, int budget)
{
	++rebuilds;
	lastBudget_ = budget;
	budget = std::min(std::max(budget, 0), kMaxLights);
	struct Cluster
	{
		std::uint64_t cell = 0;
		double sx = 0, sy = 0, sz = 0, w = 0;
		double r = 0, g = 0, b = 0;
		int level = 0, count = 0;
		std::uint8_t kind = P::kLightSteady;
		double score = 0;
	};
	static std::unordered_map<std::uint64_t, Cluster> cells;
	cells.clear();
	const double range2 = static_cast<double>(kLightRangeBlocks) * kLightRangeBlocks;
	auto floorDiv = [](std::int32_t v) { return v >= 0 ? v / kLightCellBlocks : (v - kLightCellBlocks + 1) / kLightCellBlocks; };
	std::size_t inRange = 0;
	for (const auto &kv : bySection_)
		for (const LightEmitter &e : kv.second.list)
		{
			const double dx = e.x + 0.5 - px, dy = e.y + 0.5 - py, dz = e.z + 0.5 - pz;
			if (dx * dx + dy * dy + dz * dz > range2)
				continue;
			++inRange;
			const std::uint64_t cell = Key(floorDiv(e.x), floorDiv(e.y), floorDiv(e.z));
			Cluster &c = cells[cell];
			c.cell = cell;
			const double w = static_cast<double>(e.level) * e.level;
			c.sx += (e.x + 0.5) * w;
			c.sy += (e.y + 0.5) * w;
			c.sz += (e.z + 0.5) * w;
			c.w += w;
			c.r += (e.rgb & 0xFF) / 255.0 * w;
			c.g += ((e.rgb >> 8) & 0xFF) / 255.0 * w;
			c.b += ((e.rgb >> 16) & 0xFF) / 255.0 * w;
			c.level = std::max<int>(c.level, e.level);
			c.kind = std::max<std::uint8_t>(c.kind, e.kind);
			++c.count;
		}
	lastCells = cells.size();
	lastInRange = inRange;
	static std::vector<Cluster> chosen;
	chosen.clear();
	for (auto &kv : cells)
	{
		Cluster c = kv.second;
		c.sx /= c.w, c.sy /= c.w, c.sz /= c.w;
		c.sy += 0.3;  // a little above the blocks' centre (lava lights its surface)
		c.r /= c.w, c.g /= c.w, c.b /= c.w;
		const double dx = c.sx - px, dy = c.sy - py, dz = c.sz - pz;
		c.score = std::sqrt(dx * dx + dy * dy + dz * dz) - LightRadiusBlocks(c.level, c.count);
		chosen.push_back(c);
	}
	const std::size_t keep = std::min<std::size_t>(chosen.size(), static_cast<std::size_t>(budget));
	// ties broken by cell, so equal scores don't swap between rebuilds
	std::partial_sort(chosen.begin(), chosen.begin() + static_cast<std::ptrdiff_t>(keep), chosen.end(),
		[](const Cluster &a, const Cluster &b) { return a.score < b.score || (a.score == b.score && a.cell < b.cell); });
	chosen.resize(keep);

	auto fill = [](LightSlot &s, const Cluster &c) {
		s.active = true;
		s.cell = c.cell;
		s.kind = c.kind;
		s.level = c.level;
		s.count = c.count;
		s.mx = c.sx;
		s.my = c.sy;
		s.mz = c.sz;
		s.r = static_cast<float>(c.r);
		s.g = static_cast<float>(c.g);
		s.b = static_cast<float>(c.b);
		s.radiusBlocks = LightRadiusBlocks(c.level, c.count);
		s.base = 0.75f + 0.65f * (static_cast<float>(c.level) / 15.0f);
		s.phase = static_cast<float>(c.cell % 997) * 0.37f;
	};
	// Keep lights on the cells they already show (slots inside the budget); the rest take free slots.
	bool used[kMaxLights] = {};
	static std::vector<const Cluster *> pending;
	pending.clear();
	for (const Cluster &c : chosen)
	{
		bool placed = false;
		for (int i = 0; i < budget; ++i)
			if (!used[i] && slots[i].active && slots[i].cell == c.cell)
			{
				fill(slots[i], c);
				used[i] = placed = true;
				break;
			}
		if (!placed)
			pending.push_back(&c);
	}
	for (const Cluster *c : pending)
		for (int i = 0; i < budget; ++i)
			if (!used[i])
			{
				fill(slots[i], *c);
				used[i] = true;
				break;
			}
	for (int i = 0; i < kMaxLights; ++i)
		if (!used[i])
			slots[i] = LightSlot{};
}
}  // namespace blk
}  // namespace gc
