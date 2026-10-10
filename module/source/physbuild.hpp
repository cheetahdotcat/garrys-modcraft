// Tier 0 (0.4 Physics): the static physics world for GMod's VPhysics, the engine-free half.
// GMod props, ragdolls and vehicles stop colliding with the BSP world (server/physworld.lua's
// ShouldCollide filter) and collide with per-region static entities instead, built from:
//   * the map's convexes (world brushes, static props) clipped to the region box,
//   * standalone triangles (displacements: a prism kPrismDepth deep behind the face; world
//     polysoup: a thin two-sided slab) clipped to the region box,
//   * minus Minecraft's dug cells (diggable geometry only, as Minecraft cuts it): convex - box is
//     at most 6 convex pieces (piece k: outside box plane k, inside planes 1..k-1),
//   * plus Minecraft's solid blocks as greedy-merged boxes (full cubes, as P5a's solid bitsets).
// Everything is plane clipping of a convex polytope (faces with ordered vertices): each clip is
// linear in the vertex count, and every loop has a hard cap (planes per convex, pieces per region,
// convexes per region); a region past a cap comes back `truncated` and the Lua side treats it as
// not built (props near it keep the BSP).
// (The T0 spike's first carve clipped point clouds by adding every point pair's crossing: the count
// squares per cut and six cuts hung srcds. Never that.)
#pragma once

#include "mapcol.hpp"

#include <array>
#include <cstdint>
#include <functional>
#include <vector>

namespace gc
{
namespace mcol = gmodcraft::mapcol;
}

namespace gc::pw
{
inline constexpr float kPrismDepth = 80.0f;    // units behind a displacement face (2 blocks)
inline constexpr float kSlabHalf = 2.0f;       // a two-sided polysoup triangle's slab, each side
inline constexpr float kMinThick = 0.5f;       // pieces thinner than this along any axis are dropped
inline constexpr std::size_t kMaxPlanes = 64;  // per source convex (more: skipped, region truncated)
inline constexpr std::size_t kMaxConvexes = 1024;  // per region
inline constexpr std::size_t kMaxPieces = 4096;    // carve work per source convex (pieces alive)
inline constexpr std::size_t kMaxDugBoxes = 256;   // per region
inline constexpr std::size_t kMaxFaceVerts = 256;  // per polytope face

struct Box  // Source units, lo < hi
{
	float lo[3], hi[3];
};

struct V3
{
	double x, y, z;
};

// A convex polytope: faces (n . p <= d inside, unit n) with their vertices in cyclic order.
struct Face
{
	double n[3];
	double d;
	std::vector<V3> pts;
};
using Poly = std::vector<Face>;

Poly BoxPoly(const Box &b);
// Keeps n . p <= d. False when nothing (of volume) is left; `p` is then empty.
bool Clip(Poly &p, const double n[3], double d);
// Distinct vertices of a polytope; bounds.
void Vertices(const Poly &p, std::vector<V3> &out);
bool Bounds(const Poly &p, double lo[3], double hi[3]);
// What is left of `in` outside every box (each box: <= 6 pieces). Appends to `out`; false if the
// work went past `maxPieces` (out then holds what was done so far, the caller should give up).
bool Carve(const Poly &in, const std::vector<Box> &boxes, std::vector<Poly> &out, std::size_t maxPieces = kMaxPieces);

// MC block box [x0, x1) x [y0, y1) x [z0, z1) -> Source box (slot frame).
Box McBoxToSource(const mcol::McFrame &f, double x0, double y0, double z0, double x1, double y1, double z1);  // blocks (halves allowed)

// Dug cells (MC block coords) inside the block box [lo, lo + size) -> greedy boxes (runs along x,
// grown along z, then y), MC block coords half-open: { x0, y0, z0, x1, y1, z1 }.
void MergeCells(const std::vector<std::array<int, 3>> &cells, const int lo[3], int size, std::vector<std::array<int, 6>> &out);

// Minecraft's solid octants (half-block cells) around a region: x, z from x0 / z0, y from y0 (half-block
// units, MC axes), nx * ny * nz cells, index x + nx * (z + nz * y). Outside = air.
struct HalfOcc
{
	int x0 = 0, y0 = 0, z0 = 0, nx = 0, ny = 0, nz = 0;
	std::vector<std::uint8_t> v;
	bool At(int x, int y, int z) const
	{
		x -= x0, y -= y0, z -= z0;
		return x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz && v[static_cast<std::size_t>(x + nx * (z + nz * y))] != 0;
	}
};

// T0b: vehicle ramps over half-block steps. Where a Minecraft surface is exactly half a block above the
// neighbouring one (a slab step, a stair), a wedge from the upper edge down to the lower surface one
// block (or half a block, if that's all the lower tread has) away: a staircase of slabs becomes a
// slope wheels roll up (a 20-unit riser stops the jeep, live run). Only for steps whose upper cell lies
// in the half-block box [lo, hi) (no wedge twice). Each wedge: 6 vertices in Source units (absolute).
// `ground` (optional): is the half cell solid map geometry? (a slab on the map's floor: the first step's
// lower tread is the map, not a Minecraft block.)
void StepWedges(const HalfOcc &occ, const int lo[3], const int hi[3], const mcol::McFrame &f, std::vector<std::array<V3, 6>> &out,
	const std::function<bool(int, int, int)> *ground = nullptr);
// The map's solid at a point (Source): inside a physics convex of `mesh` (planes), candidates from the index.
bool MapSolidAt(const mcol::Mesh &mesh, const mcol::RegionIndex &index, const mcol::McFrame &f, const float mc[3]);

struct RegionInput
{
	const mcol::Mesh *mesh = nullptr;            // world mesh (Source space)
	const mcol::MaterialTable *mats = nullptr;   // for "diggable" (null: nothing is diggable)
	std::vector<std::uint32_t> convexIds;        // candidates (mesh.convexes), any order
	std::vector<std::uint32_t> triIds;           // candidates (mesh.tris, standalone only are used)
	Box region;                                  // the region's Source box
	std::vector<Box> dug;                        // dug cells (merged), Source
	std::vector<Box> blocks;                     // Minecraft solid blocks (merged), Source
	std::vector<std::array<V3, 6>> wedges;       // StepWedges (Source, absolute), emitted as they are
	std::size_t maxConvexes = kMaxConvexes;
};

struct RegionOutput
{
	std::vector<float> verts;           // x, y, z relative to region.lo, convex after convex
	std::vector<std::uint32_t> counts;  // vertices per convex
	std::uint32_t brushes = 0, prisms = 0, boxes = 0, carved = 0, dropped = 0, wedges = 0, wedgesDropped = 0;
	bool truncated = false;
	double ms = 0;
};

void BuildRegion(const RegionInput &in, RegionOutput &out);

// The kinds that collide with VPhysics (no clips, no water, no dynamic entities).
bool PhysicsKind(std::uint8_t kind);
}  // namespace gc::pw
