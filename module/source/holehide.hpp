// Holes Minecraft dug into GMod's map, drawn by the engine itself: the map faces whose solid lies
// in a dug cell are hidden from the engine's world rendering, and the undug rest of each such face
// is drawn again here (its own material and the engine's lightmap page). With the face gone the
// engine draws whatever lies behind it, so a hole dug through a wall shows the world beyond.
//
// How a face is hidden: the loaded world keeps one draw-flags word per face. Its "sky" bit makes
// the engine treat the face as sky (nothing drawn, read every frame). Nothing about the engine's
// private structures is assumed: the per-face arrays are found at run time and checked against the
// map file (plane pointer and texinfo of every face, the sky bit on exactly the sky faces, the edge
// count in the top byte, the lightmap mins / size per face), every pointer is checked against
// /proc/self/maps, and the material sort table against the faces' texture names. If any check
// fails nothing is changed and the stencil path (client/blocks.lua) does the holes as before.
#pragma once

#include "holebox.hpp"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

class IMatRenderContext;

namespace gc
{
namespace hide
{
// The map file's bytes (once per map, before the first Update). False + err if they don't parse or
// the engine's world can't be matched to them; Update then does nothing.
bool Init(IMatRenderContext *ctx, const char *bsp, std::size_t n, std::string &err);

// The dug boxes (MC blocks, as holes::MergeSection) with the slot frame. enabled false (or no Init)
// restores every hidden face and drops the meshes. Call from PreRender (before the engine builds the
// frame's world lists), inside a render context.
void Update(IMatRenderContext *ctx, const std::vector<holes::Box> &boxes, std::int32_t ox, std::int32_t oz, std::int32_t oy, bool enabled);

// Draws the undug rest of the hidden faces. Returns draw calls.
int Draw(IMatRenderContext *ctx);

// Bumped by Init (holes.cpp rebuilds when it changes).
std::uint64_t Generation();

// The crust (client convar gmodcraft_hole_crust): 0 off, 1 the top band of each hole wall shows the
// cut face's material as deep as the solid under it, at most one block, 2 as deep as the solid goes.
// A change bumps Generation (the holes are rebuilt with it).
void SetCrust(int mode);

// Module close / map change: faces restored (if the same world is still loaded), state dropped.
void Close(IMatRenderContext *ctx);

struct Stats
{
	bool ready = false;          // Init succeeded
	bool active = false;         // faces are hidden right now
	std::size_t worldFaces = 0;  // faces of the world model that can be hidden
	std::size_t hidden = 0, pieces = 0, vertices = 0, meshes = 0, groups = 0;
	std::uint64_t updates = 0, draws = 0, drawCalls = 0;
	double initMs = 0, lastUpdateMs = 0, maxUpdateMs = 0;
	int crustMode = 0;                  // as SetCrust
	std::size_t crustPieces = 0, crustBrushes = 0;
	bool crustTree = false;             // the BSP tree decoded (solid leaves for the band depth)
	double crustMs = 0;                 // of lastUpdateMs
	std::string error;  // why it isn't ready
	std::string probe;  // what the engine check found
};
const Stats &GetStats();
}  // namespace hide
}  // namespace gc
