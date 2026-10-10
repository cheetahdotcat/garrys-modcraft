// Holes Minecraft dug into GMod's map (P5b, docs/DESIGN.md section 9): the engine-free half.
// Minecraft's dug cells (kRenDug, kept in the client DigStore) become greedy-merged boxes, and the
// boxes become a closed triangle volume in Source units. client/blocks.lua draws that volume twice
// into the stencil buffer (z-fail: back faces increment, front faces decrement), which marks the
// pixels whose world surface lies inside a dug cell; there the depth is cleared and the hole walls
// (DigWalls, already in Minecraft's section meshes) are drawn. holes.cpp owns the engine side.
#pragma once

#include "blockmesh.hpp"
#include "mapcol.hpp"

#include <array>
#include <cstdint>
#include <cstring>
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

// ---- hiding dug map faces in the engine (holehide.*) -------------------------------------------------
// A convex planar map face (vertices in order, outward unit normal n) minus its dug part, by the face
// cut's rule: a point P of the face is dug iff P - behind * n lies in a dug box (boxes as from
// MergeSection, placed with the slot origin as BoxTriangles; not grown). `rest` gets the undug part
// as disjoint convex pieces (slivers under 0.01 square units dropped); returns the dug area.
using FacePoly = std::vector<std::array<float, 3>>;
float FaceRest(const FacePoly &face, const float n[3], const std::vector<Box> &boxes, std::int32_t ox, std::int32_t oz, std::int32_t oy, float behind,
	std::vector<FacePoly> &rest);
float PolyArea(const FacePoly &p);
// Puts a planar polygon's vertices in clockwise order seen from the side n points to (Source's front
// faces). A map file already lists every face clockwise about its stored plane, which is the face's
// front (see MapFace), so for map faces this is a guard that changes nothing. FaceRest keeps the
// order it is given. Returns whether it reversed them.
bool OrientClockwise(FacePoly &p, const float n[3]);

// ---- map file faces (holehide.*, tests) ----------------------------------------------------------------
// One uncompressed lump of a map file (VBSP). At() reads 0 past the end.
struct MapLump
{
	const std::uint8_t *p = nullptr;
	std::size_t n = 0;
	template <class T> T At(std::size_t off) const
	{
		T v{};
		if (off + sizeof(T) <= n)
			std::memcpy(&v, p + off, sizeof(T));
		return v;
	}
};
struct MapFaceLumps
{
	MapLump faces, planes, verts, edges, surfedges;
};
inline constexpr std::size_t kMapFaceSize = 56, kMapPlaneSize = 20;
// Face `face`'s vertices as the file lists them and its front plane (n . p = d, n pointing to the
// side the face is seen from). The plane a face names is its own: faces on a node plane's back side
// (the side flag) name the odd, negated plane of the pair, so the stored normal is the front for
// every face and the vertices go clockwise seen from it. Negating it for the side flag turns those
// faces inside out (their solid side taken for the front, their redrawn rest culled).
void MapFace(const MapFaceLumps &l, std::size_t face, FacePoly &poly, float n[3], float &d);

// ---- the crust (holehide.*) ---------------------------------------------------------------------
// Where a dug hole cuts a map face, the top band of the hole's walls shows the cut face's own material:
// the band is the part of each hole wall (a side of a dug box whose neighbour cell isn't dug, about
// perpendicular to the face) that lies under the face (inside its outline moved along -n) and within the
// solid under it, at most `cap` deep. Its texture is the face's own, draped over the cut edge: a band
// point at depth h shows what the face shows at p + h * n + h * fold (fold: into the dug side, in the
// face's plane), so it continues the face's texture at the cut line with no shift.

// Which MC cells are dug (the boxes as from MergeSection; boxes spanning sections work too).
class DugCells
{
public:
	void Build(const std::vector<Box> &boxes);
	bool IsDug(std::int32_t x, std::int32_t y, std::int32_t z) const;

private:
	std::vector<Box> boxes_;
	std::vector<std::pair<std::uint64_t, std::uint32_t>> index_;  // (section key, box), sorted
};

struct CrustPiece
{
	FacePoly poly;     // on the hole wall, clockwise seen from the hole (wallN)
	float wallN[3];    // the wall's normal, into the hole
	float fold[3];     // unit, in the face's plane, into the dug side (see above)
	float depth = 0;   // the band depth used (units)
};

// The solid's extent under a face: how far from p along `down` (unit) the map stays solid, at most `max`.
using SolidDepthFn = float (*)(const void *user, const float p[3], const float down[3], float max);

// The band pieces of one face (convex, clockwise from its front n, n . p = d) for the dug boxes `boxes`
// (placed with the slot frame, as FaceRest; `behind` as FaceRest). The solid depth is sampled just
// past the wall, under the undug face; where the hole goes through the slab (the face on its far side
// is cut at that wall too) each side's band takes half of it. `probe` (>= cap) is how far the solid is
// followed. Appends to `out`; returns the pieces added.
std::size_t FaceCrust(const FacePoly &face, const float n[3], float d, const std::vector<Box> &boxes, const DugCells &dug, std::int32_t ox, std::int32_t oz,
	std::int32_t oy, float behind, float cap, float probe, SolidDepthFn depth, const void *user, std::vector<CrustPiece> &out);

// The texture point a band point shows (see above): p + h * n + h * fold, h = d - n . p.
void CrustFold(const CrustPiece &piece, const float n[3], float d, const float p[3], float out[3]);

// The distance along `dir` (unit) from p at which the BSP tree's leaves stop being solid (CONTENTS_SOLID),
// at most `max`; 0 when p itself isn't in a solid leaf (or there is no tree).
float BspSolidDepth(const gmodcraft::mapcol::BspTree &tree, const float p[3], const float dir[3], float max);

// The map's solid brushes (BRUSHES 18, BRUSHSIDES 19, PLANES 1; contents & CONTENTS_SOLID), detail
// brushes included (the tree's leaves don't hold those as solid).
class SolidBrushes
{
public:
	void Build(const MapLump &brushes, const MapLump &sides, const MapLump &planes);
	std::size_t Count() const { return brushes_.size(); }
	// How far from p along dir (unit) one stays inside brushes (each brush left into the next), at most max.
	float Depth(const float p[3], const float dir[3], float max) const;

private:
	struct Brush
	{
		std::uint32_t first, count;  // in planes_
		float lo[3], hi[3];
	};
	std::vector<Brush> brushes_;
	std::vector<std::array<float, 4>> planes_;  // inside: n . p - d <= 0
	std::vector<std::pair<std::uint64_t, std::uint32_t>> grid_;  // (256-unit cell, brush), sorted
	const Brush *Inside(const float p[3]) const;
};

// The solid under a face as the crust uses it: brushes and the tree's solid leaves, one after the other.
float MapSolidDepth(const gmodcraft::mapcol::BspTree *tree, const SolidBrushes *brushes, const float p[3], const float dir[3], float max);
}  // namespace holes
}  // namespace gc
