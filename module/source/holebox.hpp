// Holes Minecraft dug into GMod's map (P5b, docs/DESIGN.md section 9): the engine-free half.
// Minecraft's dug cells (kRenDug, kept in the client DigStore) become greedy-merged boxes, and the
// boxes become a closed triangle volume in Source units. client/blocks.lua draws that volume twice
// into the stencil buffer (z-fail: back faces increment, front faces decrement), which marks the
// pixels whose world surface lies inside a dug cell; there the depth is cleared and the hole walls
// (DigWalls, already in Minecraft's section meshes) are drawn. holes.cpp owns the engine side.
#pragma once

#include "blockmesh.hpp"
#include "mapcol.hpp"

#include <cstdint>
#include <vector>

namespace gc
{
namespace holes
{
// A box of dug cells in Minecraft block coordinates, half-open: [x0, x1) x [y0, y1) x [z0, z1).
struct Box
{
	std::int32_t x0, y0, z0, x1, y1, z1;
};

// Greedy merge of one section's dug bits (bit x + 16z + 256y, 64 words, as in RenDug / DigStore):
// runs along x, then grown along z, then along y. The boxes are disjoint and cover exactly the set
// bits. Appends to `out`.
void MergeSection(std::int32_t sx, std::int32_t sy, std::int32_t sz, const std::uint64_t bits[64], std::vector<Box> &out);

// A box's 12 triangles in Source units (slot origin ox, oz; McToSource), grown by `eps` units on
// every side (so a world surface lying exactly on a cell face counts as inside). Front faces point
// outwards (clockwise, as the block meshes). Normal = the face's outward normal, uv 0, colour white.
void BoxTriangles(const Box &b, std::int32_t ox, std::int32_t oz, std::int32_t oy, float eps, std::vector<blk::OutVertex> &out);

inline constexpr std::uint32_t kVerticesPerBox = 36;
inline constexpr std::size_t kMaxBoxes = 20000;  // 720k vertices; beyond that the rest isn't drawn
inline constexpr float kEps = 0.5f;              // units the mask volume is grown by

// ---- experimental per-face cut (client convar gmodcraft_dig_cut_brush 1) --------------------------
// Instead of the boxes' volume, the volume is made of thin prisms around the map faces whose solid
// lies in a dug cell: a point P of a face (outward normal n) is cut iff P - behind * n (just inside
// its brush) is in a dug cell. Per box that is the face clipped to the box moved by +behind * n
// (grown by eps on every side, so neighbouring boxes overlap and the hole edge matches the box
// path), exact because the boxes are disjoint and cover exactly the dug cells. A wall standing in
// the cell is cut; the floor top face whose brush lies below the cell bottom is not (its box moves
// up by behind, more than eps). Faces: every face plane of the index's kSrcWorld convexes (the face
// polygon is that plane clipped by the convex's other planes, grown in-plane by eps so faces of
// neighbouring brushes leave no crack) and every kSrcDisplacement triangle (grown about its
// incentre by eps). Each piece becomes a closed prism +-eps along n (outward clockwise fronts), so
// HoleStencilPass works unchanged. Static props, clips and brush entities are never cut.
struct FaceCutStats
{
	std::size_t convexes = 0, tris = 0, faces = 0, pieces = 0;
	bool truncated = false;
};
inline constexpr float kCutBehind = 1.0f;                  // units behind a face that are tested
inline constexpr std::size_t kMaxCutVertices = 2000000;  // beyond that the rest isn't drawn

// Appends the prisms for `boxes` (MC blocks, as from MergeSection) in Source units: the faces come
// from index.Source(), the boxes are placed with index.Frame().
void FaceCutTriangles(const gmodcraft::mapcol::RegionIndex &index, const std::vector<Box> &boxes, float behind, float eps,
	std::vector<blk::OutVertex> &out, FaceCutStats &stats);
}  // namespace holes
}  // namespace gc
