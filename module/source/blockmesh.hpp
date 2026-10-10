// Minecraft's block sections and atlas from the client render ring, without the engine (P3a).
// Parses and validates the render ring messages, keeps every section's raw RenVertex list and a
// CPU copy of the atlas, and turns a section into Source-ready vertex lists: positions in Source
// units from the slot origin (D-003), winding swapped (MC is CCW, Source front faces are
// clockwise), the lighting baked into the vertex colour, split into an opaque+cutout pass and a
// translucent pass, chunked for 16-bit indices. Pure C++ (no SDK): tested natively by
// module/test/blockmesh_test.cpp and link_test.py; the IMesh/ITexture side is blocks.cpp.
#pragma once

#include "gmodcraft_protocol.h"

#include <cmath>
#include <cstdint>
#include <functional>
#include <unordered_map>
#include <vector>

namespace gc
{
namespace P = gmodcraft::proto;

namespace blk
{
// One vertex as the host draws it (Source units, Z up). Colour bytes are in the order the engine
// reads them (R, G, B, A with BakeConfig::rgbaOrder, the default measured on GMod's ToGL; else
// B, G, R, A: D3DCOLOR).
struct OutVertex
{
	float pos[3];
	float normal[3];
	float uv[2];
	std::uint8_t color[4];
};

struct BakeConfig
{
	std::int32_t ox = 0, oz = 0;     // slot origin in MC blocks (D-003)
	std::int32_t oy = 0;             // v21: slot vertical offset, SOURCE units (mc.y = (src.z + oy) / 40)
	bool linearVertexColor = false;  // the shader multiplies vertex colour in linear light: sRGB-decode it (measured: no, D-017)
	bool rgbaOrder = true;           // the engine reads vertex colour as R, G, B, A (OPENGL_SWAP_COLORS; measured: yes, D-017)
	float daylight = 1.0f;           // S in the bake (fixed daytime: 1), times the cell's GMod light
	bool cellLight = true;           // use the sections' per-cell GMod light (Section::cellLight)

	bool operator==(const BakeConfig &o) const
	{
		return ox == o.ox && oz == o.oz && oy == o.oy && linearVertexColor == o.linearVertexColor && rgbaOrder == o.rgbaOrder && daylight == o.daylight &&
			cellLight == o.cellLight;
	}
	bool operator!=(const BakeConfig &o) const { return !(*this == o); }
};

// RenVertex::flags
inline constexpr std::uint32_t kFlagCutout = 1;
inline constexpr std::uint32_t kFlagTranslucent = 2;
inline constexpr std::uint32_t kFlagNoMip = 8;
inline constexpr std::uint32_t kNormalShift = 4;
inline constexpr std::uint32_t kNormalMask = 7;
inline constexpr std::uint32_t kNormalFromTriangle = 7;

// Largest vertex count per mesh: 16-bit indices, whole triangles (65535 = 3 * 21845).
inline constexpr std::uint32_t kMaxMeshVertices = 65535;

// Minecraft's light curve: level l in [0, 1] -> brightness.
float Curve(float l);
// sRGB-decode of a [0, 1] value (exact piecewise curve).
float SrgbDecode(float v);
// Minecraft's fixed face shading for a face-normal code (flags bits 4-6: Direction ordinal + 1,
// 0 = none, 7 = the triangle's own normal, given in MC axes and normalised).
float FaceShade(std::uint32_t normalCode, const float mcNormal[3]);
// The bake's brightness per channel for one vertex light word (block light low byte, sky light
// next byte): max(S * lerp(.3, 1, Curve(sky)), Curve(block) * (1, .85, .65)).
void LightRgb(std::uint32_t light, float daylight, float out[3]);
// Bakes one vertex colour: rgb * AO (the RenVertex colour) * LightRgb * shade, in engine byte order.
void BakeColor(std::uint32_t rgba, std::uint32_t light, float shade, const BakeConfig &cfg, std::uint8_t out[4]);
// MC block coordinates (absolute) -> Source units, with the slot origin.
void McToSource(double mx, double my, double mz, std::int32_t ox, std::int32_t oz, std::int32_t oy, float out[3]);

// Section meshes (terrain and placed blocks, not entities, outlines or hitboxes) sit this many units
// below their exact place, so a Minecraft floor flush with a map floor (flat_everywhere: grass top =
// map floor) loses the depth test instead of z-fighting (Z1). 24-bit depth, znear 3: a floor seen
// straight down still separates up to ~3500 units away, at walking eye height far beyond render range.
inline constexpr float kTerrainDrop = 0.25f;

// A double to int32, defined for every input: NaN -> 0, out of range -> the nearest bound
// (a plain static_cast is undefined behaviour there). Truncates toward zero.
inline std::int32_t SafeI32(double v)
{
	if (!(v == v))
		return 0;
	if (v <= -2147483648.0)
		return INT32_MIN;
	if (v >= 2147483647.0)
		return INT32_MAX;
	return static_cast<std::int32_t>(v);
}
// Three cell coordinates from Lua numbers: false unless all are finite and in int32 range.
inline bool CellArg(const double f[3], std::int32_t out[3])
{
	for (int k = 0; k < 3; ++k)
	{
		if (!(f[k] > -2147483648.0 && f[k] < 2147483647.0))
			return false;
		out[k] = static_cast<std::int32_t>(f[k]);
	}
	return true;
}

// ---- GMod's light per cell (P3b) ----------------------------------------------------------------
// A section is split into 4 x 4 x 4 cells of 4 x 4 x 4 blocks (index x + 4 * (y + 4 * z), section-
// local MC axes). A triangle belongs to the cell holding its centroid moved half a block out along
// its normal (the air in front of the face), so a block face never straddles two cells. The host
// samples its own light once per cell that has faces (near them) and the bake uses it as S.
inline constexpr int kCellBlocks = 4;
inline constexpr int kCellsPerSection = 64;
inline constexpr float kLightEpsilon = 0.04f;  // S changes at most this big don't re-bake
inline constexpr int kProbePoints = 7;         // per cell: up to 6 face directions + the cell centre
inline constexpr float kProbePush = 0.3f;      // blocks (12 units) in front of the faces
// The triangle's normal in MC axes for its normal code (code 7 / 0: from the triangle itself).
void TriangleNormal(const P::RenVertex &a, const P::RenVertex &b, const P::RenVertex &c, float out[3]);
// The section-local point a triangle's light is sampled at (MC blocks), and its cell index.
void TrianglePoint(const P::RenVertex &a, const P::RenVertex &b, const P::RenVertex &c, float out[3]);
int CellIndex(const float local[3]);

// Builds a section's two passes from its raw vertices (vertexCount truncated to whole triangles).
// cellLight: the section's 64 cell S values (< 0 = not sampled: 1), or nullptr (all 1).
void BuildSection(std::int32_t sx, std::int32_t sy, std::int32_t sz, const P::RenVertex *v, std::uint32_t count, const BakeConfig &cfg,
	std::vector<OutVertex> &opaque, std::vector<OutVertex> &translucent, const float *cellLight = nullptr);

// [first, first + count) vertex ranges of at most kMaxMeshVertices (multiples of 3).
std::vector<std::pair<std::uint32_t, std::uint32_t>> Chunks(std::uint32_t vertices);

// ---- state fed by the render ring -----------------------------------------------------------
struct Section
{
	std::int32_t sx = 0, sy = 0, sz = 0;
	std::vector<P::RenVertex> raw;  // as Minecraft sent it (whole triangles)
	std::uint32_t opaqueVerts = 0, translucentVerts = 0;  // after the last build (all passes)
	bool dirty = true;    // raw or the bake config changed since the GPU copy was built
	bool queued = false;  // in the dirty list
	bool built = false;   // baked at least once (BuildSection ran since raw arrived)
	void *gpu = nullptr;  // the renderer's meshes (blocks.cpp), released through Store::onRelease

	// GMod's light per cell (see kCellBlocks): which cells have faces, which want a sample, the
	// sampled S (-1: none yet) and the candidate sample points (section-local MC blocks): per face
	// direction present, the mean centre of those faces pushed kProbePush out along it, then the
	// cell centre. The host samples the brightest one not inside a brush (P3b run 2: one mean point
	// could land inside a thick GMod wall and read black).
	std::uint64_t cellMask = 0, cellNeed = 0;
	float cellLight[kCellsPerSection];
	float cellProbe[kCellsPerSection][kProbePoints][3];
	std::uint8_t cellProbes[kCellsPerSection];
	Section()
	{
		for (float &l : cellLight)
			l = -1.0f;
		for (auto &c : cellProbe)
			for (auto &p : c)
				p[0] = p[1] = p[2] = 0;
		for (auto &n : cellProbes)
			n = 0;
	}
};

struct AtlasRect
{
	std::uint32_t x, y, w, h;
};

// Animated sprites in their own small texture (P3b): every rect a kRenAtlasRegion ever named is
// packed into a shelf layout; the bake remaps a triangle whose UV centroid lies in one of them to
// that texture (passes 2/3), and the host re-uploads the whole small texture when any changed.
struct AnimSprite
{
	std::uint32_t ax, ay, w, h;  // in the atlas (pixels)
	std::uint32_t dx, dy;        // in the animated texture
};
struct AnimLayout
{
	std::uint32_t w = 0, h = 0;  // the animated texture's size (0: no sprites)
	std::vector<AnimSprite> sprites;
	std::uint64_t version = 0;  // bumps on every relayout (new sprite, new atlas)
	// Adds a rect (no-op if known); relayouts and returns true when it was new.
	bool Add(const AtlasRect &r);
	void Clear();
	// The sprite holding atlas pixel (px, py), or -1.
	int Find(float px, float py) const;
};

struct Atlas
{
	std::uint32_t w = 0, h = 0;
	std::vector<std::uint8_t> bgra;  // w * h * 4, top row first, B G R A
	std::uint64_t version = 0;       // bumps on every full kRenAtlas
	bool fullDirty = false;          // a new atlas the GPU copy hasn't got yet
	bool regionDirty = false;        // animated regions patched since the last upload
	// The patched regions not uploaded yet, one entry per distinct rect (a sprite sent again before
	// its upload is uploaded once); a region whose pixels didn't change isn't listed.
	std::vector<AtlasRect> dirtyRects;
	bool animDirty = false;  // a sprite of `anim` changed since the animated texture's last upload
	AnimLayout anim;
	std::uint64_t regions = 0, regionRejects = 0, regionBytes = 0, regionsUnchanged = 0;
};

// A section's passes: 0 opaque + cutout, 1 translucent on the atlas; 2, 3 the same on the
// animated texture (only with an AnimLayout).
inline constexpr int kPasses = 4;
struct SectionPasses
{
	std::vector<OutVertex> pass[kPasses];
};
// BuildSection with the animated-texture remap: a triangle whose UV centroid falls in a sprite
// of `anim` (atlas atlasW x atlasH) gets UVs in the animated texture and goes to pass 2 / 3.
void BuildSectionPasses(std::int32_t sx, std::int32_t sy, std::int32_t sz, const P::RenVertex *v, std::uint32_t count, const BakeConfig &cfg,
	SectionPasses &out, const float *cellLight, const AnimLayout *anim, std::uint32_t atlasW, std::uint32_t atlasH);

// One cell that wants GMod's light sampled: global cell coordinates (section * 4 + local) and
// its candidate sample points in Source units (Section::cellProbe).
struct LightProbe
{
	std::int32_t cx, cy, cz;
	int n;
	float pos[kProbePoints][3];
};

struct LightCounters
{
	std::uint64_t sets = 0, rebakes = 0, refreshes = 0, firstSamples = 0;
};

struct Counters
{
	std::uint64_t byType[16] = {};
	std::uint64_t bytesByType[16] = {};
	std::uint64_t unknown = 0, malformed = 0;
	std::uint64_t sectionUpdates = 0, sectionRemovals = 0, clears = 0, atlases = 0;
	std::uint64_t aliased = 0;  // a section whose Key() matched another section's (that one was replaced)
};

// The sections map key: sx and sz keep 22 bits, sy 20, so far-apart coordinates can share a key
// (MC's world border is inside that range, but nothing enforces it): every lookup also compares
// the stored sx/sy/sz (Store::Find).
inline std::uint64_t Key(std::int32_t sx, std::int32_t sy, std::int32_t sz)
{
	return (static_cast<std::uint64_t>(static_cast<std::uint32_t>(sx) & 0x3FFFFFu) << 42) |
		(static_cast<std::uint64_t>(static_cast<std::uint32_t>(sz) & 0x3FFFFFu) << 20) | (static_cast<std::uint32_t>(sy) & 0xFFFFFu);
}

// ---- Minecraft's light-emitting blocks as host point lights (P3d) -----------------------------------
// Ported from SkyCraft's BlockLights.cpp: kRenLights lists each section's emitters (torches, lava,
// glowstone, ...). Nearby emitters merge per 3-block cell (weighted by level squared); a cell's
// radius is min((L + 1)(1 + 0.12 log2 n), 20) * 0.8 blocks; the kMaxLights cells nearest the player
// within 72 blocks (by distance minus radius) are kept, then the host's budget of them. Rebuilt every
// 0.25 s (and at once after a message); a cell keeps its slot across rebuilds (sticky), so the
// host's light objects don't jump. Flames flicker, lava glows slowly (the slot's intensity factor).
inline constexpr int kLightCellBlocks = 3;     // not kCellBlocks (the GMod-light cells are 4)
inline constexpr int kMaxLights = 24;          // the hard cap; the host's budget is at most this
inline constexpr float kLightRangeBlocks = 72.0f;
inline constexpr double kLightUpdateSeconds = 0.25;
inline constexpr float kLightMaxBlocks = 20.0f;  // radius cap before the 0.8

// A cell's light radius in MC blocks for its brightest level and emitter count.
float LightRadiusBlocks(int level, int count);
// The intensity factor of a light kind at time t (seconds, phase included): 1 for steady.
float LightFlicker(std::uint8_t kind, float t);

struct LightEmitter
{
	std::int32_t x, y, z;  // MC block coordinates
	std::uint8_t level;    // 1-15
	std::uint8_t kind;     // P::LightKind
	std::uint32_t rgb;     // r low byte
};

struct LightSlot
{
	bool active = false;
	std::uint64_t cell = 0;  // Key() of the 3-block cell it shows
	std::uint8_t kind = P::kLightSteady;
	int level = 0, count = 0;
	double mx = 0, my = 0, mz = 0;  // the cluster's position (MC blocks, weighted, lifted 0.3)
	float r = 0, g = 0, b = 0;      // 0..1
	float radiusBlocks = 0;
	float base = 1;   // 0.75 + 0.65 * level / 15: brighter emitters shine harder as well as further
	float phase = 0;  // flicker phase, from the cell
};

class BlockLights
{
public:
	// One kRenLights message (RenLights + RenLight[count]); count 0 drops the section's emitters.
	void OnMessage(const std::uint8_t *p, std::uint32_t n);
	void Clear();  // every emitter and slot (kRenClearAll, a new Minecraft, a closed link)
	// Advances the clock by dt seconds; rebuilds when due, after a message, or when the budget
	// changed. Player in MC blocks. Returns whether it rebuilt.
	bool Update(double dt, double px, double py, double pz, int budget);
	// The clustering itself (Update calls it); budget clamped to [0, kMaxLights].
	void Rebuild(double px, double py, double pz, int budget);
	// A slot's intensity now: base times the flicker at the current clock.
	float Intensity(const LightSlot &s) const;

	LightSlot slots[kMaxLights];
	std::size_t Emitters() const;
	// Emitters inside the MC block box [x0, x1] x [y0, y1] x [z0, z1] (inclusive, any corner order).
	std::size_t CountIn(std::int32_t x0, std::int32_t y0, std::int32_t z0, std::int32_t x1, std::int32_t y1, std::int32_t z1) const;
	std::size_t Sections() const { return bySection_.size(); }
	std::size_t Active() const;
	std::size_t lastCells = 0, lastInRange = 0;  // cells and emitters in range at the last rebuild
	std::uint64_t messages = 0, malformed = 0, rebuilds = 0;
	double Clock() const { return clock_; }

private:
	struct SectionLights
	{
		std::int32_t sx, sy, sz;
		std::vector<LightEmitter> list;
	};
	std::unordered_map<std::uint64_t, SectionLights> bySection_;
	double clock_ = 0, timer_ = 0;
	int lastBudget_ = -1;
};

class Store
{
public:
	// Called before a section is dropped: removal (a 0-vertex kRenSection), Clear / kRenClearAll,
	// and a section pushed out by another one with the same Key() (alias). A section that is simply
	// sent again keeps its `gpu` and is only marked dirty: the renderer rebuilds it in place.
	std::function<void(Section &)> onRelease;

	// The section at these coordinates, or nullptr (also when another section holds its Key()).
	Section *Find(std::int32_t sx, std::int32_t sy, std::int32_t sz);

	// One render ring message (except kRenDug, which the caller routes to the collision layer).
	// The payload is only valid during the call: everything kept is copied.
	void OnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n);

	void Clear();  // every section and light (kRenClearAll, a new Minecraft); the atlas stays
	void SetConfig(const BakeConfig &cfg);  // marks every section dirty when it changes
	const BakeConfig &Config() const { return cfg_; }
	void MarkAllDirty();

	// Sections whose GPU copy needs (re)building, oldest first; the caller clears `dirty`.
	std::vector<std::uint64_t> TakeDirty();
	std::unordered_map<std::uint64_t, Section> sections;
	Atlas atlas;
	BlockLights lights;  // kRenLights (P3d); cleared with the sections
	Counters counters;
	std::uint64_t totalVertices = 0;  // raw vertices held

	// Section centres in Source units, farthest first from (x, y, z): the translucent draw order.
	void BackToFront(double x, double y, double z, std::vector<Section *> &out);

	// GMod's light: up to `max` cells that want a sample (never sampled first), into `out`.
	// Round robin over the sections: a call starts where the last one stopped.
	void LightQuery(std::size_t max, std::vector<LightProbe> &out);
	// Every cell with faces wants a sample again, once the previous sweep finished (nothing
	// pending); returns whether it started a new sweep.
	bool LightRefresh();
	// A cell's sampled S. Re-bakes its section when it was baked without it or S moved by more
	// than kLightEpsilon; returns whether it did.
	bool LightSet(std::int32_t cx, std::int32_t cy, std::int32_t cz, float s);
	std::size_t LightCells() const;    // cells with faces
	std::size_t LightPending() const;  // cells wanting a sample
	LightCounters light;
	// The renderer built a section (clears dirty, sets built).
	static void MarkBuilt(Section &s)
	{
		s.dirty = false;
		s.built = true;
	}

private:
	void MarkDirty(std::uint64_t key, Section &s);
	void ComputeCells(Section &s);
	void OnSection(const std::uint8_t *p, std::uint32_t n);
	void OnAtlas(const std::uint8_t *p, std::uint32_t n);
	void OnAtlasRegion(const std::uint8_t *p, std::uint32_t n);
	void Remove(std::int32_t sx, std::int32_t sy, std::int32_t sz);
	BakeConfig cfg_;
	std::uint32_t atlasFlags_ = P::kRenPixBGRA;
	std::vector<std::uint64_t> dirty_;
	std::size_t lightCursor_ = 0;  // LightQuery's round-robin start (a position in `sections`)
};
}  // namespace blk
}  // namespace gc
