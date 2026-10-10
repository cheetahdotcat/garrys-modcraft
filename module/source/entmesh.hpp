// Minecraft's per-frame things without the engine (P3c): entity textures (kRenTexture), the F5
// player model (kRenAvatar), every other entity, block entity and particle (kRenScene), the
// ragdoll snapshot (kRenRagdoll, stored for P4), and the geometry of the WorldEntities region
// (dropped items, spinning block items, arrows, tridents, mining cracks; shadows and the block
// outline as data for Lua). Everything is baked like the block sections (blockmesh.*): Source
// units from the slot origin, winding swapped, Minecraft's light word times GMod's light S, face
// shade from the normal code (7 = the triangle's own normal), grouped per texture and pass.
// Pure C++ (no SDK): tested natively by module/test/entmesh_test.cpp (link_test.py); the engine
// side (dynamic meshes, textures, Lua API) is entities.cpp.
#pragma once

#include "blockmesh.hpp"

#include <cstdint>
#include <unordered_map>
#include <vector>

namespace gc
{
namespace ent
{
namespace P = gmodcraft::proto;
using blk::BakeConfig;
using blk::OutVertex;

// ---- budgets --------------------------------------------------------------------------------
inline constexpr std::uint32_t kMaxTextureSide = 4096;
inline constexpr std::uint32_t kMaxAvatarVertices = 32768;
inline constexpr std::uint32_t kMaxSceneVertices = 131072;  // ~4 MB raw; batches past it are dropped whole
inline constexpr std::uint32_t kMaxRagdollVertices = 32768;
inline constexpr std::uint32_t kMaxBatches = 4096;
// WorldEntities geometry is bounded by kMaxWorldEntities (36 vertices each at most).

// RenVertex::light for things lit only by GMod's light: sky 15, block 0 (LightRgb gives S).
inline constexpr std::uint32_t kFullSkyLight = 0x0F00;

struct Texture
{
	std::uint32_t id = 0, w = 0, h = 0;
	std::vector<std::uint8_t> bgra;  // w * h * 4, top row first, B G R A (RGBA input is swizzled)
	std::uint64_t version = 0;       // bumps on every kRenTexture for this id
};

// A kRenAvatar / kRenScene / kRenRagdoll as received: only batches that are in range, with
// vertex counts cut to whole triangles, and their vertices copied compactly (first rebased).
struct MeshMsg
{
	double origin[3] = { 0, 0, 0 };  // kRenScene: the MC block positions are relative to; else 0
	std::vector<P::RenBatch> batches;
	std::vector<P::RenVertex> verts;
	std::uint64_t seq = 0;            // bumps on every message (also empty ones)
	std::uint32_t droppedVerts = 0;   // the last message's vertices past the cap
	std::uint32_t sentVerts = 0;      // the last message's vertexCount
};

struct Counters
{
	std::uint64_t textures = 0, textureRejects = 0;
	std::uint64_t avatars = 0, scenes = 0, ragdolls = 0;
	std::uint64_t malformed = 0;     // too short, too many batches
	std::uint64_t badBatches = 0;    // out of range (dropped)
	std::uint64_t overflowMsgs = 0;  // messages that hit their vertex cap
	std::uint64_t overflowVerts = 0;
};

class Store
{
public:
	// kRenTexture, kRenAvatar, kRenScene, kRenRagdoll (others are ignored). The payload is only
	// valid during the call: everything kept is copied.
	void OnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n);
	void Clear();  // a new Minecraft (texture ids restart at 1): everything goes

	std::unordered_map<std::uint32_t, Texture> textures;
	std::vector<std::uint32_t> changedTextures;  // ids received since the caller last cleared it
	MeshMsg avatar, scene, ragdoll;
	Counters counters;

private:
	void OnTexture(const std::uint8_t *p, std::uint32_t n);
	bool ParseMesh(const std::uint8_t *p, std::uint32_t n, bool hasOrigin, std::uint32_t cap, MeshMsg &out);
};

// ---- GMod's light for dynamic things ------------------------------------------------------------
// S per 4x4x4-block cell (global cell coordinates, as blockmesh's cells), sampled by the host at
// the first point a frame's geometry needed it, again every refreshMs while still used, forgotten
// dropMs after the last use. Unknown cells give defaultS (the host passes its value at the eye).
struct LightProbe
{
	std::int32_t cx, cy, cz;
	float pos[3];  // Source units
};

class LightCache
{
public:
	float defaultS = 1.0f;
	double refreshMs = 1000.0, dropMs = 5000.0;
	// S at an MC point (absolute blocks), at time now; marks the cell used (asks for it if new).
	float At(double mx, double my, double mz, double nowMs);
	// Up to `max` cells that want a sample (never sampled first), probe points in Source units.
	void Query(std::size_t max, double nowMs, std::int32_t ox, std::int32_t oz, std::int32_t oy, std::vector<LightProbe> &out);
	void Set(std::int32_t cx, std::int32_t cy, std::int32_t cz, float s, double nowMs);
	void Prune(double nowMs);  // cells unused for dropMs go
	void Clear();
	std::size_t Cells() const { return cells_.size(); }
	std::size_t Pending(double nowMs) const;
	std::uint64_t sets = 0, misses = 0;

private:
	struct Cell
	{
		float s = -1.0f;
		double sampledAt = -1e18, usedAt = 0;
		double probe[3] = { 0, 0, 0 };  // MC blocks
		std::int32_t cx = 0, cy = 0, cz = 0;
	};
	std::unordered_map<std::uint64_t, Cell> cells_;
	std::uint64_t lastKey_ = ~std::uint64_t{ 0 };
	Cell *last_ = nullptr;
};

// ---- the bake ---------------------------------------------------------------------------------------
// Baked vertices per pass (0 opaque + cutout, 1 translucent) and texture (0 = the block/item atlas).
struct Group
{
	std::uint32_t texture = 0;
	std::vector<OutVertex> v;
};
struct Baked
{
	std::vector<Group> pass[2];  // the first used[p] are this frame's
	std::size_t used[2] = { 0, 0 };
	void Clear();  // keeps the groups' storage (no allocation per frame once warm)
	std::vector<OutVertex> &Out(int pass, std::uint32_t texture);
	std::size_t Vertices() const;
	std::size_t Groups() const { return used[0] + used[1]; }
};

// Bakes batches whose vertex positions are MC blocks relative to `origin` (absolute MC blocks):
// Source position with cfg's slot origin, winding swapped, colour = rgba * LightRgb(light word,
// S * cfg.daylight) * FaceShade, S from `light` at the triangle's centroid (nullptr: 1). Batch
// flags bit0 picks the pass.
void BakeMesh(const P::RenBatch *batches, std::size_t nb, const P::RenVertex *v, std::size_t nv, const double origin[3], const BakeConfig &cfg,
	LightCache *light, double nowMs, Baked &out);

// ---- WorldEntities geometry --------------------------------------------------------------------------
// A quad with corners TL, TR, BR, BL as seen from its front (uv rect {u0, v0, u1, v1} mapped TL =
// (u0, v0)), emitted as two triangles in Minecraft's counter-clockwise order (so a triangle's own
// normal points out of the front).
void Quad(std::vector<P::RenVertex> &out, const float p[4][3], const float uv[4], std::uint32_t color, std::uint32_t flags);
// An axis-aligned box rotated yawRad about its vertical centre line; side / top / bottom uv rects.
// shaded: lit by its faces' own normals (normal code 7) and the top face tinted by topTint (RGBA8,
// 0 = none).
void Box(std::vector<P::RenVertex> &out, const float mn[3], const float size[3], float yawRad, const float side[4], const float top[4],
	const float bottom[4], std::uint32_t topTint, std::uint32_t flags, bool shaded);
// The direction an arrow with Minecraft yaw / pitch (degrees) flies: (sin y cos p, sin p, cos y cos p).
void ArrowDir(float yawDeg, float pitchDeg, float d[3]);
// Minecraft's arrow (two crossed fins and the back plate) at p flying along unit d, or a trident
// (its item icon on the two fins, handle to tip along d).
void Arrow(std::vector<P::RenVertex> &out, const float p[3], const float d[3], const float uvSide[4], const float uvBack[4], bool trident);

// A soft contact shadow under a player or mob (Lua draws it): Source position of the feet and radius.
struct Shadow
{
	float pos[3];
	float radius;  // Source units
};

struct WorldGeometry
{
	std::vector<P::RenVertex> opaque, translucent;  // positions relative to `origin`
	std::vector<Shadow> shadows;
	bool hasSelection = false;
	float selMin[3] = {}, selMax[3] = {};  // Source units (min/max per Source axis)
	std::uint32_t items = 0, blocks = 0, arrows = 0, cracks = 0;
	void Clear();
};
inline constexpr float kShadowRadiusPerWidth = 0.75f;  // blocks of radius per block of entity width

// The region's entities as geometry relative to origin (MC blocks, absolute; the host uses the
// slot origin), shadows and the outline converted to Source with cfg's slot origin.
void BuildWorld(const P::WorldEntities &w, const double origin[3], const BakeConfig &cfg, WorldGeometry &out);
}  // namespace ent
}  // namespace gc
