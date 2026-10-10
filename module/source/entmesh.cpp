// See entmesh.hpp.
#include "entmesh.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace gc
{
namespace ent
{
using blk::kFlagCutout;
using blk::kFlagNoMip;
using blk::kFlagTranslucent;
using blk::kNormalFromTriangle;
using blk::kNormalMask;
using blk::kNormalShift;

// ---- Store ----------------------------------------------------------------------------------------
void Store::OnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n)
{
	switch (type)
	{
	case P::kRenTexture:
		OnTexture(p, n);
		break;
	case P::kRenAvatar:
		++counters.avatars;
		ParseMesh(p, n, false, kMaxAvatarVertices, avatar);
		break;
	case P::kRenScene:
		++counters.scenes;
		ParseMesh(p, n, true, kMaxSceneVertices, scene);
		break;
	case P::kRenRagdoll:
		++counters.ragdolls;
		ParseMesh(p, n, false, kMaxRagdollVertices, ragdoll);
		break;
	default:
		break;
	}
}

void Store::Clear()
{
	textures.clear();
	changedTextures.clear();
	for (MeshMsg *m : { &avatar, &scene, &ragdoll })
	{
		m->batches.clear();
		m->verts.clear();
		++m->seq;
	}
}

void Store::OnTexture(const std::uint8_t *p, std::uint32_t n)
{
	if (n < sizeof(P::RenTexture))
	{
		++counters.textureRejects;
		return;
	}
	P::RenTexture h;
	std::memcpy(&h, p, sizeof h);
	if (h.id == 0 || h.width == 0 || h.height == 0 || h.width > kMaxTextureSide || h.height > kMaxTextureSide ||
		static_cast<std::uint64_t>(n) < sizeof h + static_cast<std::uint64_t>(h.width) * h.height * 4)
	{
		++counters.textureRejects;
		return;
	}
	Texture &t = textures[h.id];
	t.id = h.id;
	t.w = h.width;
	t.h = h.height;
	const std::size_t bytes = static_cast<std::size_t>(h.width) * h.height * 4;
	t.bgra.resize(bytes);
	const std::uint8_t *src = p + sizeof h;
	if (h.flags & P::kRenPixBGRA)
		std::memcpy(t.bgra.data(), src, bytes);
	else
		for (std::size_t i = 0; i < bytes; i += 4)
		{
			t.bgra[i] = src[i + 2];
			t.bgra[i + 1] = src[i + 1];
			t.bgra[i + 2] = src[i];
			t.bgra[i + 3] = src[i + 3];
		}
	++t.version;
	++counters.textures;
	if (std::find(changedTextures.begin(), changedTextures.end(), h.id) == changedTextures.end())
		changedTextures.push_back(h.id);
}

bool Store::ParseMesh(const std::uint8_t *p, std::uint32_t n, bool hasOrigin, std::uint32_t cap, MeshMsg &out)
{
	++out.seq;
	out.batches.clear();
	out.verts.clear();
	out.droppedVerts = 0;
	out.sentVerts = 0;
	const std::size_t head = hasOrigin ? sizeof(P::RenScene) : sizeof(P::RenAvatar);
	if (n < head)
	{
		++counters.malformed;
		return false;
	}
	std::uint32_t batchCount, vertexCount;
	if (hasOrigin)
	{
		P::RenScene h;
		std::memcpy(&h, p, sizeof h);
		out.origin[0] = h.originX;
		out.origin[1] = h.originY;
		out.origin[2] = h.originZ;
		batchCount = h.batchCount;
		vertexCount = h.vertexCount;
	}
	else
	{
		P::RenAvatar h;
		std::memcpy(&h, p, sizeof h);
		out.origin[0] = out.origin[1] = out.origin[2] = 0;
		batchCount = h.batchCount;
		vertexCount = h.vertexCount;
	}
	out.sentVerts = vertexCount;
	if (batchCount == 0 || vertexCount == 0)
		return true;  // nothing shown (first person, nothing around)
	if (batchCount > kMaxBatches ||
		static_cast<std::uint64_t>(n) < head + static_cast<std::uint64_t>(batchCount) * sizeof(P::RenBatch) +
				static_cast<std::uint64_t>(vertexCount) * sizeof(P::RenVertex))
	{
		++counters.malformed;
		return false;
	}
	const std::uint8_t *bp = p + head;
	const std::uint8_t *vp = bp + static_cast<std::size_t>(batchCount) * sizeof(P::RenBatch);
	std::uint32_t dropped = 0;
	for (std::uint32_t i = 0; i < batchCount; ++i)
	{
		P::RenBatch b;
		std::memcpy(&b, bp + static_cast<std::size_t>(i) * sizeof b, sizeof b);
		if (static_cast<std::uint64_t>(b.first) + b.count > vertexCount || b.count < 3)
		{
			++counters.badBatches;
			continue;
		}
		b.count -= b.count % 3;
		if (out.verts.size() + b.count > cap)
		{
			dropped += b.count;
			continue;
		}
		const std::size_t at = out.verts.size();
		out.verts.resize(at + b.count);
		std::memcpy(out.verts.data() + at, vp + static_cast<std::size_t>(b.first) * sizeof(P::RenVertex), static_cast<std::size_t>(b.count) * sizeof(P::RenVertex));
		b.first = static_cast<std::uint32_t>(at);
		out.batches.push_back(b);
	}
	out.droppedVerts = dropped;
	if (dropped)
	{
		++counters.overflowMsgs;
		counters.overflowVerts += dropped;
	}
	return true;
}

// ---- LightCache --------------------------------------------------------------------------------------
namespace
{
std::int32_t FloorCell(double m)
{
	return blk::SafeI32(std::floor(m / blk::kCellBlocks));  // NaN / huge vertex positions stay defined
}
}  // namespace

float LightCache::At(double mx, double my, double mz, double nowMs)
{
	const std::int32_t cx = FloorCell(mx), cy = FloorCell(my), cz = FloorCell(mz);
	const std::uint64_t key = blk::Key(cx, cy, cz);
	Cell *c = nullptr;
	if (key == lastKey_ && last_ != nullptr)
		c = last_;
	else
	{
		auto it = cells_.find(key);
		if (it == cells_.end() || it->second.cx != cx || it->second.cy != cy || it->second.cz != cz)
		{
			++misses;
			Cell fresh;
			fresh.cx = cx;
			fresh.cy = cy;
			fresh.cz = cz;
			fresh.probe[0] = mx;
			fresh.probe[1] = my;
			fresh.probe[2] = mz;
			c = &(cells_[key] = fresh);  // pointers into an unordered_map stay valid on insert
		}
		else
			c = &it->second;
		lastKey_ = key;
		last_ = c;
	}
	c->usedAt = nowMs;
	return c->s >= 0 ? c->s : defaultS;
}

void LightCache::Query(std::size_t max, double nowMs, std::int32_t ox, std::int32_t oz, std::int32_t oy, std::vector<LightProbe> &out)
{
	out.clear();
	auto add = [&](const Cell &c) {
		LightProbe lp;
		lp.cx = c.cx;
		lp.cy = c.cy;
		lp.cz = c.cz;
		blk::McToSource(c.probe[0], c.probe[1], c.probe[2], ox, oz, oy, lp.pos);
		out.push_back(lp);
	};
	for (const auto &kv : cells_)
		if (out.size() < max && kv.second.s < 0)
			add(kv.second);
	for (const auto &kv : cells_)
		if (out.size() < max && kv.second.s >= 0 && nowMs - kv.second.sampledAt >= refreshMs && nowMs - kv.second.usedAt < refreshMs)
			add(kv.second);
}

std::size_t LightCache::Pending(double nowMs) const
{
	std::size_t n = 0;
	for (const auto &kv : cells_)
		if (kv.second.s < 0 || (nowMs - kv.second.sampledAt >= refreshMs && nowMs - kv.second.usedAt < refreshMs))
			++n;
	return n;
}

void LightCache::Set(std::int32_t cx, std::int32_t cy, std::int32_t cz, float s, double nowMs)
{
	auto it = cells_.find(blk::Key(cx, cy, cz));
	if (it == cells_.end() || it->second.cx != cx || it->second.cy != cy || it->second.cz != cz)
		return;
	it->second.s = std::min(std::max(s, 0.0f), 1.0f);
	it->second.sampledAt = nowMs;
	++sets;
}

void LightCache::Prune(double nowMs)
{
	for (auto it = cells_.begin(); it != cells_.end();)
		it = nowMs - it->second.usedAt > dropMs ? cells_.erase(it) : std::next(it);
	lastKey_ = ~std::uint64_t{ 0 };
	last_ = nullptr;
}

void LightCache::Clear()
{
	cells_.clear();
	lastKey_ = ~std::uint64_t{ 0 };
	last_ = nullptr;
}

// ---- the bake -----------------------------------------------------------------------------------------
void Baked::Clear()
{
	for (int p = 0; p < 2; ++p)
	{
		for (std::size_t i = 0; i < used[p]; ++i)
			pass[p][i].v.clear();
		used[p] = 0;
	}
}

std::vector<OutVertex> &Baked::Out(int p, std::uint32_t texture)
{
	auto &groups = pass[p];
	for (std::size_t i = 0; i < used[p]; ++i)
		if (groups[i].texture == texture)
			return groups[i].v;
	if (used[p] == groups.size())
		groups.emplace_back();
	Group &g = groups[used[p]++];
	g.texture = texture;
	g.v.clear();
	return g.v;
}

std::size_t Baked::Vertices() const
{
	std::size_t n = 0;
	for (int p = 0; p < 2; ++p)
		for (std::size_t i = 0; i < used[p]; ++i)
			n += pass[p][i].v.size();
	return n;
}

void BakeMesh(const P::RenBatch *batches, std::size_t nb, const P::RenVertex *v, std::size_t nv, const double origin[3], const BakeConfig &cfg,
	LightCache *light, double nowMs, Baked &out)
{
	BakeConfig lit = cfg;
	std::uint32_t lastLight = ~0u;
	float lastDay = -1.0f, litRgb[3] = { 0, 0, 0 };
	for (std::size_t b = 0; b < nb; ++b)
	{
		const P::RenBatch &batch = batches[b];
		if (static_cast<std::uint64_t>(batch.first) + batch.count > nv)
			continue;
		std::vector<OutVertex> &o = out.Out((batch.flags & 1) ? 1 : 0, batch.texture);
		const std::uint32_t end = batch.first + (batch.count - batch.count % 3);
		for (std::uint32_t t = batch.first; t < end; t += 3)
		{
			const P::RenVertex &a = v[t], &b1 = v[t + 1], &c = v[t + 2];
			float tn[3];
			blk::TriangleNormal(a, b1, c, tn);
			const std::uint32_t code = (a.flags >> kNormalShift) & kNormalMask;
			const float shade = blk::FaceShade(code, tn);
			lit.daylight = cfg.daylight;
			if (light != nullptr)
				lit.daylight = cfg.daylight * light->At(origin[0] + (a.x + b1.x + c.x) / 3.0, origin[1] + (a.y + b1.y + c.y) / 3.0,
												  origin[2] + (a.z + b1.z + c.z) / 3.0, nowMs);
			const float n[3] = { tn[0], -tn[2], tn[1] };
			for (const P::RenVertex *s : { &a, &c, &b1 })  // MC is CCW: swap v1/v2
			{
				OutVertex ov;
				blk::McToSource(origin[0] + s->x, origin[1] + s->y, origin[2] + s->z, cfg.ox, cfg.oz, cfg.oy, ov.pos);
				ov.normal[0] = n[0];
				ov.normal[1] = n[1];
				ov.normal[2] = n[2];
				ov.uv[0] = s->u;
				ov.uv[1] = s->v;
				if (cfg.linearVertexColor)
					blk::BakeColor(s->color, s->light, shade, lit, ov.color);
				else
				{
					// BakeColor's arithmetic, with LightRgb cached per light word and S (most
					// vertices of an entity share both)
					if (s->light != lastLight || lit.daylight != lastDay)
					{
						blk::LightRgb(s->light, lit.daylight, litRgb);
						lastLight = s->light;
						lastDay = lit.daylight;
					}
					std::uint8_t c3[3];
					for (int i = 0; i < 3; ++i)
					{
						float x = static_cast<float>((s->color >> (8 * i)) & 0xFF) / 255.0f * litRgb[i] * shade;
						x = x < 0 ? 0 : x > 1 ? 1 : x;
						c3[i] = static_cast<std::uint8_t>(x * 255.0f + 0.5f);
					}
					ov.color[0] = cfg.rgbaOrder ? c3[0] : c3[2];
					ov.color[1] = c3[1];
					ov.color[2] = cfg.rgbaOrder ? c3[2] : c3[0];
					ov.color[3] = static_cast<std::uint8_t>(s->color >> 24);
				}
				o.push_back(ov);
			}
		}
	}
}

// ---- WorldEntities geometry ---------------------------------------------------------------------------
void Quad(std::vector<P::RenVertex> &out, const float p[4][3], const float uv[4], std::uint32_t color, std::uint32_t flags)
{
	// TL, BL, BR / TL, BR, TR: counter-clockwise seen from the front
	const float tuv[4][2] = { { uv[0], uv[1] }, { uv[2], uv[1] }, { uv[2], uv[3] }, { uv[0], uv[3] } };
	for (int k : { 0, 3, 2, 0, 2, 1 })
	{
		P::RenVertex r;
		r.x = p[k][0];
		r.y = p[k][1];
		r.z = p[k][2];
		r.u = tuv[k][0];
		r.v = tuv[k][1];
		r.color = color;
		r.light = kFullSkyLight;
		r.flags = flags;
		out.push_back(r);
	}
}

void Box(std::vector<P::RenVertex> &out, const float mn[3], const float size[3], float yawRad, const float side[4], const float top[4],
	const float bottom[4], std::uint32_t topTint, std::uint32_t flags, bool shaded)
{
	const float cx = mn[0] + size[0] * 0.5f, cz = mn[2] + size[2] * 0.5f;
	const float c = std::cos(yawRad), s = std::sin(yawRad);
	auto corner = [&](int i, float o[3]) {
		const float lx = ((i & 1) ? 0.5f : -0.5f) * size[0], lz = ((i & 4) ? 0.5f : -0.5f) * size[2];
		o[0] = cx + lx * c - lz * s;
		o[1] = mn[1] + ((i & 2) ? size[1] : 0.0f);
		o[2] = cz + lx * s + lz * c;
	};
	// corner bits: 1 = +x, 2 = +y, 4 = +z; each face TL, TR, BR, BL seen from outside
	static const int kFaces[6][4] = {
		{ 6, 7, 5, 4 },  // south (+z)
		{ 3, 2, 0, 1 },  // north (-z)
		{ 7, 3, 1, 5 },  // east (+x)
		{ 2, 6, 4, 0 },  // west (-x)
		{ 2, 3, 7, 6 },  // top
		{ 4, 5, 1, 0 },  // bottom
	};
	const std::uint32_t f = flags | (shaded ? (kNormalFromTriangle << kNormalShift) : 0);
	for (int face = 0; face < 6; ++face)
	{
		float p[4][3];
		for (int k = 0; k < 4; ++k)
			corner(kFaces[face][k], p[k]);
		const float *uv = face == 4 ? top : face == 5 ? bottom : side;
		const std::uint32_t color = (shaded && face == 4 && topTint != 0) ? (topTint | 0xFF000000u) : 0xFFFFFFFFu;
		Quad(out, p, uv, color, f);
	}
}

void ArrowDir(float yawDeg, float pitchDeg, float d[3])
{
	const float y = yawDeg * 0.017453292519943295f, p = pitchDeg * 0.017453292519943295f;
	d[0] = std::sin(y) * std::cos(p);
	d[1] = std::sin(p);
	d[2] = std::cos(y) * std::cos(p);
}

void Arrow(std::vector<P::RenVertex> &out, const float p[3], const float d[3], const float uvSide[4], const float uvBack[4], bool trident)
{
	// s: the horizontal side, u: "up" around the flight axis; the fins sit at 45 degrees between.
	float s[3] = { d[2], 0.0f, -d[0] };
	float sl = std::sqrt(s[0] * s[0] + s[2] * s[2]);
	if (sl < 1e-3f)
	{
		s[0] = 1.0f;
		s[2] = 0.0f;
		sl = 1.0f;
	}
	s[0] /= sl;
	s[2] /= sl;
	const float u[3] = { s[1] * d[2] - s[2] * d[1], s[2] * d[0] - s[0] * d[2], s[0] * d[1] - s[1] * d[0] };
	const float r = 0.70710678f;
	const float fins[2][3] = { { (u[0] + s[0]) * r, (u[1] + s[1]) * r, (u[2] + s[2]) * r }, { (u[0] - s[0]) * r, (u[1] - s[1]) * r, (u[2] - s[2]) * r } };
	auto at = [&](float along, const float *q, float side, const float *q2, float side2, float o[3]) {
		for (int k = 0; k < 3; ++k)
			o[k] = p[k] + d[k] * along + q[k] * side + (q2 ? q2[k] * side2 : 0.0f);
	};
	const std::uint32_t flags = kFlagCutout | kFlagNoMip;
	if (!trident)
	{
		// Minecraft's ArrowModel (1/16 block units, scaled 0.9): two fins 16 long and 4 wide from
		// -12 (fletching) to +4 (head), and a 4 x 4 back plate at -11. GMod's world is Minecraft's
		// scale (1 block = 40 units), so unlike SkyCraft no extra shrink.
		const float k = 0.9f / 16.0f;
		for (const auto &q : fins)
		{
			float c[4][3];
			at(-12 * k, q, -2 * k, nullptr, 0, c[0]);
			at(4 * k, q, -2 * k, nullptr, 0, c[1]);
			at(4 * k, q, 2 * k, nullptr, 0, c[2]);
			at(-12 * k, q, 2 * k, nullptr, 0, c[3]);
			Quad(out, c, uvSide, 0xFFFFFFFFu, flags);
		}
		float c[4][3];
		at(-11 * k, fins[0], -2 * k, fins[1], -2 * k, c[0]);
		at(-11 * k, fins[0], 2 * k, fins[1], -2 * k, c[1]);
		at(-11 * k, fins[0], 2 * k, fins[1], 2 * k, c[2]);
		at(-11 * k, fins[0], -2 * k, fins[1], 2 * k, c[3]);
		Quad(out, c, uvBack, 0xFFFFFFFFu, flags);
	}
	else
	{
		// the item icon, whose diagonal runs handle (bottom-left) to tip (top-right)
		const float h = 0.9f;
		for (const auto &q : fins)
		{
			float c[4][3];
			at(0, q, h, nullptr, 0, c[0]);
			at(h, q, 0, nullptr, 0, c[1]);  // tip
			at(0, q, -h, nullptr, 0, c[2]);
			at(-h, q, 0, nullptr, 0, c[3]);  // handle
			Quad(out, c, uvSide, 0xFFFFFFFFu, flags);
		}
	}
}

void WorldGeometry::Clear()
{
	opaque.clear();
	translucent.clear();
	shadows.clear();
	hasSelection = false;
	items = blocks = arrows = cracks = 0;
}

void BuildWorld(const P::WorldEntities &w, const double origin[3], const BakeConfig &cfg, WorldGeometry &out)
{
	out.Clear();
	const std::uint32_t count = std::min(w.count, P::kMaxWorldEntities);
	for (std::uint32_t i = 0; i < count; ++i)
	{
		const P::WorldEntity &e = w.entities[i];
		const float px = static_cast<float>(e.x - origin[0]), py = static_cast<float>(e.y - origin[1]), pz = static_cast<float>(e.z - origin[2]);
		if (!std::isfinite(px) || !std::isfinite(py) || !std::isfinite(pz) || !std::isfinite(e.scale) || !std::isfinite(e.yaw) || !std::isfinite(e.pitch) ||
			!std::isfinite(e.ext[0]) || !std::isfinite(e.ext[1]) || !std::isfinite(e.ext[2]))
			continue;  // std::max / std::min pass NaN through
		switch (e.kind)
		{
		case P::kWeShadow:
		{
			Shadow s;
			blk::McToSource(e.x, e.y, e.z, cfg.ox, cfg.oz, cfg.oy, s.pos);
			s.radius = std::max(e.scale, 0.5f) * kShadowRadiusPerWidth * 40.0f;
			out.shadows.push_back(s);
			break;
		}
		case P::kWeBlock:
		{
			// a dropped block: a small cube spinning about its centre
			const float sc = std::min(std::max(e.scale, 0.01f), 2.0f);
			const float mn[3] = { px - sc * 0.5f, py - sc * 0.5f, pz - sc * 0.5f };
			const float sz[3] = { sc, sc, sc };
			Box(out.opaque, mn, sz, e.yaw * 0.017453292519943295f, e.uv[0], e.uv[1], e.uv[2], e.tint, kFlagCutout | kFlagNoMip, true);
			++out.blocks;
			break;
		}
		case P::kWeCrack:
		{
			const float mn[3] = { px, py, pz };
			float ext[3];
			for (int k = 0; k < 3; ++k)
				ext[k] = std::min(std::max(e.ext[k], 0.0f), 4.0f);
			Box(out.translucent, mn, ext, 0.0f, e.uv[0], e.uv[0], e.uv[0], 0, kFlagTranslucent | kFlagNoMip, true);
			++out.cracks;
			break;
		}
		case P::kWeArrow:
		case P::kWeTrident:
		{
			float d[3];
			ArrowDir(e.yaw, e.pitch, d);
			const float p[3] = { px, py, pz };
			Arrow(out.opaque, p, d, e.uv[0], e.uv[1], e.kind == P::kWeTrident);
			++out.arrows;
			break;
		}
		case P::kWeItem:
		{
			// a flat sprite turning about the vertical
			const float spin = e.yaw * 0.017453292519943295f, half = std::min(std::max(e.scale, 0.01f), 2.0f) * 0.5f;
			const float rx = std::cos(spin) * half, rz = std::sin(spin) * half;
			const float p[4][3] = {
				{ px - rx, py + half, pz - rz },
				{ px + rx, py + half, pz + rz },
				{ px + rx, py - half, pz + rz },
				{ px - rx, py - half, pz - rz },
			};
			Quad(out.opaque, p, e.uv[0], 0xFFFFFFFFu, kFlagCutout | kFlagNoMip);
			++out.items;
			break;
		}
		default:
			break;
		}
	}
	bool selFinite = true;
	for (int k = 0; k < 3; ++k)
		selFinite = selFinite && std::isfinite(w.selMin[k]) && std::isfinite(w.selMax[k]);
	if (w.hasSelection && selFinite)
	{
		float a[3], b[3];
		blk::McToSource(w.selMin[0], w.selMin[1], w.selMin[2], cfg.ox, cfg.oz, cfg.oy, a);
		blk::McToSource(w.selMax[0], w.selMax[1], w.selMax[2], cfg.ox, cfg.oz, cfg.oy, b);
		const float g = 0.002f * 40.0f;
		for (int k = 0; k < 3; ++k)
		{
			out.selMin[k] = std::min(a[k], b[k]) - g;
			out.selMax[k] = std::max(a[k], b[k]) + g;
		}
		out.hasSelection = true;
	}
}
}  // namespace ent
}  // namespace gc
