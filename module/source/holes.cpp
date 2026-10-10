// gmcl_gmodcraft: holes Minecraft dug into GMod's map (P5b, docs/DESIGN.md section 9), engine side.
// The client DigStore (kRenDug, collision.cpp) becomes greedy-merged boxes (holebox.*), whose
// closed volume is kept as static IMeshes (blocks.cpp's checked builder) and rebuilt when the dug
// cells, the world or the slot origin change. client/blocks.lua draws it into the stencil buffer in
// PreDrawOpaqueRenderables (or, as the fallback, as a dark translucent cap). Lua API:
//   HolesPrepare(worldId, cutBrush) -> boxes  once per frame (PreRender), after BlocksPrepare;
//                                      cutBrush (experimental, gmodcraft_dig_cut_brush): the volume
//                                      is holes::FaceCutTriangles' face prisms instead of the boxes
//                                      (falls back to the boxes while the map view isn't ready)
//   HolesDraw(material) -> draw calls  the volume with the current render state (IMaterial)
//   HolesInfo() -> table               counters for the Render panel and the tests
//   HolesCells(worldId, x0, y0, z0, x1, y1, z1) -> flat x, y, z list of dug cells in the box
//   HolesDevInject(x0, y0, z0, x1, y1, z1 | nil)  dev only: one extra box (MC blocks, half-open)
//                                      drawn as if dug, without touching the DigStore (so
//                                      Minecraft's collision is left alone); nil removes it
#ifdef GMODCRAFT_CLIENT

#include "blockmesh.hpp"
#include "collision.hpp"
#include "colstream.hpp"
#include "holebox.hpp"
#include "link.hpp"
#include "lua_util.hpp"

#include <materialsystem/imaterial.h>
#include <materialsystem/imaterialsystem.h>
#include <materialsystem/imesh.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

namespace gc
{
using namespace GarrysMod::Lua;

IMaterialSystem *ClientMatSys();  // client.cpp
bool IsDevMode();                 // main.cpp
// blocks.cpp
const blk::BakeConfig &BlocksBakeConfig();
int BlocksMeshState();
IMesh *BlocksStaticMesh(IMatRenderContext *ctx, const blk::OutVertex *v, std::uint32_t n);
void BlocksDestroyLater(IMesh *m);

namespace
{
struct Holes
{
	std::vector<IMesh *> meshes;
	bool built = false;
	std::uint64_t gen = 0, injGen = 0;
	std::uint32_t world = 0;
	std::int32_t ox = 0, oz = 0, oy = 0;
	std::vector<holes::Box> injected;
	std::uint64_t injectedGen = 0;
	bool cutBrush = false;          // asked for (HolesPrepare's second argument)
	bool cutActive = false;         // the last build used the face cut
	std::uint32_t cutViewId = 0;    // the map view the face cut was built from (0: none)
	holes::FaceCutStats cut;
	std::string cutError;           // why the face cut fell back to the boxes
	// counters
	std::size_t boxes = 0, cells = 0, sections = 0, vertices = 0;
	bool truncated = false;
	std::uint64_t rebuilds = 0, draws = 0, drawCalls = 0, buildFailures = 0;
	double lastBuildMs = 0, maxBuildMs = 0;
	std::string error;
} g_h;

std::vector<holes::Box> g_boxes;
std::vector<blk::OutVertex> g_verts;
std::vector<DigStore::SectionBits> g_snap;

struct Ctx
{
	IMatRenderContext *ctx = nullptr;
	explicit Ctx(IMaterialSystem *ms)
	{
		ctx = ms->GetRenderContext();
		if (ctx)
			ctx->BeginRender();
	}
	~Ctx()
	{
		if (ctx)
			ctx->EndRender();
	}
};

void DropMeshes()
{
	for (IMesh *m : g_h.meshes)
		BlocksDestroyLater(m);
	g_h.meshes.clear();
}

void Rebuild(IMatRenderContext *ctx)
{
	const double t0 = NowMs();
	DropMeshes();
	g_boxes.clear();
	g_snap.clear();
	CollisionDigs().Snapshot(g_h.world, g_snap);
	std::size_t cells = 0;
	for (const auto &s : g_snap)
	{
		for (auto w : s.bits)
			cells += static_cast<std::size_t>(__builtin_popcountll(w));
		holes::MergeSection(s.sx, s.sy, s.sz, s.bits.data(), g_boxes);
	}
	for (const holes::Box &b : g_h.injected)
		g_boxes.push_back(b);
	g_h.truncated = g_boxes.size() > holes::kMaxBoxes;
	if (g_h.truncated)
		g_boxes.resize(holes::kMaxBoxes);
	g_verts.clear();
	g_h.cut = holes::FaceCutStats{};
	g_h.cutActive = false;
	g_h.cutError.clear();
	std::shared_ptr<const MapView> view = g_h.cutBrush ? CollisionView() : nullptr;
	g_h.cutViewId = view ? view->id : 0;  // a new view rebuilds (HolesPrepare)
	if (view && view->frame == mcol::McFrame{ g_h.ox, g_h.oz, g_h.oy })
	{
		holes::FaceCutTriangles(view->index, g_boxes, holes::kCutBehind, holes::kEps, g_verts, g_h.cut);
		g_h.cutActive = true;
	}
	else
	{
		if (g_h.cutBrush)
			g_h.cutError = view ? "face cut: the map view's slot frame differs, boxes drawn" : "face cut: no map view yet, boxes drawn";
		g_verts.reserve(g_boxes.size() * holes::kVerticesPerBox);
		for (const holes::Box &b : g_boxes)
			holes::BoxTriangles(b, g_h.ox, g_h.oz, g_h.oy, holes::kEps, g_verts);
	}
	for (auto &c : blk::Chunks(static_cast<std::uint32_t>(g_verts.size())))
	{
		if (IMesh *m = BlocksStaticMesh(ctx, g_verts.data() + c.first, c.second))
			g_h.meshes.push_back(m);
		else
			++g_h.buildFailures;
	}
	g_h.boxes = g_boxes.size();
	g_h.cells = cells;
	g_h.sections = g_snap.size();
	g_h.vertices = g_verts.size();
	g_h.built = true;
	++g_h.rebuilds;
	g_h.lastBuildMs = NowMs() - t0;
	g_h.maxBuildMs = std::max(g_h.maxBuildMs, g_h.lastBuildMs);
	if (g_verts.capacity() > 200000 && g_verts.size() < 20000)
		std::vector<blk::OutVertex>().swap(g_verts);  // don't keep a huge buffer around
}

// HolesPrepare(worldId, cutBrush) -> boxes (0 while the IMesh path is off or nothing is cut).
LUA_FUNCTION_STATIC(HolesPrepare)
{
	IMaterialSystem *ms = ClientMatSys();
	if (ms == nullptr || BlocksMeshState() == 0)
	{
		if (!g_h.meshes.empty())
			DropMeshes();
		g_h.built = false;
		g_h.error = ms == nullptr ? "no material system" : "the IMesh path failed its check";
		LUA->PushNumber(0);
		return 1;
	}
	g_h.error.clear();
	const bool cutBrush = LUA->IsType(2, Type::Bool) && LUA->GetBool(2);
	// The face cut is rebuilt when the map view changes (or first appears).
	std::uint32_t viewId = 0;
	if (cutBrush)
		if (std::shared_ptr<const MapView> v = CollisionView())
			viewId = v->id;
	const auto world = static_cast<std::uint32_t>(std::fmin(std::fmax(ArgNum(LUA, 1, 0), 0), 4294967295.0));
	const blk::BakeConfig &cfg = BlocksBakeConfig();
	const std::uint64_t gen = CollisionDigs().Generation();
	if (!g_h.built || gen != g_h.gen || world != g_h.world || cfg.ox != g_h.ox || cfg.oz != g_h.oz || cfg.oy != g_h.oy || g_h.injectedGen != g_h.injGen ||
		cutBrush != g_h.cutBrush || (cutBrush && viewId != g_h.cutViewId))
	{
		g_h.cutBrush = cutBrush;
		g_h.gen = gen;
		g_h.world = world;
		g_h.ox = cfg.ox;
		g_h.oz = cfg.oz;
		g_h.oy = cfg.oy;
		g_h.injGen = g_h.injectedGen;
		Ctx c(ms);
		if (c.ctx)
			Rebuild(c.ctx);
	}
	LUA->PushNumber(static_cast<double>(g_h.meshes.empty() ? 0 : g_h.boxes));
	return 1;
}

// HolesDraw(material) -> draw calls. Binds the material and draws the volume with whatever render
// state Lua set (stencil, cull mode, colour / depth write overrides).
LUA_FUNCTION_STATIC(HolesDraw)
{
	IMaterialSystem *ms = ClientMatSys();
	if (ms == nullptr || g_h.meshes.empty() || !LUA->IsType(1, Type::Material))
	{
		LUA->PushNumber(0);
		return 1;
	}
	IMaterial *mat = LUA->GetUserType<IMaterial>(1, Type::Material);
	if (mat == nullptr)
	{
		LUA->PushNumber(0);
		return 1;
	}
	int calls = 0;
	{
		Ctx c(ms);
		if (c.ctx)
		{
			c.ctx->MatrixMode(MATERIAL_MODEL);
			c.ctx->PushMatrix();
			c.ctx->LoadIdentity();
			c.ctx->Bind(mat, nullptr);
			for (IMesh *m : g_h.meshes)
			{
				m->Draw();
				++calls;
			}
			c.ctx->MatrixMode(MATERIAL_MODEL);
			c.ctx->PopMatrix();
		}
	}
	++g_h.draws;
	g_h.drawCalls += static_cast<std::uint64_t>(calls);
	LUA->PushNumber(calls);
	return 1;
}

LUA_FUNCTION_STATIC(HolesInfo)
{
	LUA->CreateTable();
	SetNum(LUA, "boxes", static_cast<double>(g_h.boxes));
	SetNum(LUA, "cells", static_cast<double>(g_h.cells));
	SetNum(LUA, "sections", static_cast<double>(g_h.sections));
	SetNum(LUA, "vertices", static_cast<double>(g_h.vertices));
	SetNum(LUA, "meshes", static_cast<double>(g_h.meshes.size()));
	SetNum(LUA, "injected", static_cast<double>(g_h.injected.size()));
	SetBool(LUA, "truncated", g_h.truncated);
	SetNum(LUA, "rebuilds", static_cast<double>(g_h.rebuilds));
	SetNum(LUA, "buildFailures", static_cast<double>(g_h.buildFailures));
	SetNum(LUA, "draws", static_cast<double>(g_h.draws));
	SetNum(LUA, "drawCalls", static_cast<double>(g_h.drawCalls));
	SetNum(LUA, "lastBuildMs", g_h.lastBuildMs);
	SetNum(LUA, "maxBuildMs", g_h.maxBuildMs);
	SetNum(LUA, "world", static_cast<double>(g_h.world));
	SetNum(LUA, "generation", static_cast<double>(g_h.gen));
	SetBool(LUA, "cutBrush", g_h.cutBrush);
	SetBool(LUA, "cutActive", g_h.cutActive);
	SetNum(LUA, "cutConvexes", static_cast<double>(g_h.cut.convexes));
	SetNum(LUA, "cutTris", static_cast<double>(g_h.cut.tris));
	SetNum(LUA, "cutFaces", static_cast<double>(g_h.cut.faces));
	SetNum(LUA, "cutPieces", static_cast<double>(g_h.cut.pieces));
	SetBool(LUA, "cutTruncated", g_h.cut.truncated);
	SetStr(LUA, "cutError", g_h.cutError.c_str());
	SetStr(LUA, "error", g_h.error.c_str());
	return 1;
}

// HolesCells(worldId, x0, y0, z0, x1, y1, z1) -> { x, y, z, x, y, z, ... }: the dug cells inside the
// inclusive MC block box (at most 64 blocks along each axis).
LUA_FUNCTION_STATIC(HolesCells)
{
	return PushDugCells(LUA);
}

// HolesDevInject(x0, y0, z0, x1, y1, z1 | nil) -> injected boxes (dev only).
LUA_FUNCTION_STATIC(HolesDevInject)
{
	if (!IsDevMode())
		return PushFail(LUA, "dev only (-gmodcraft_dev)");
	if (!LUA->IsType(1, Type::Number))
		g_h.injected.clear();
	else
	{
		auto c = [&](int i) { return static_cast<std::int32_t>(std::fmin(std::fmax(std::floor(ArgNum(LUA, i)), -3e7), 3e7)); };
		holes::Box b{ c(1), c(2), c(3), c(4), c(5), c(6) };
		if (b.x0 > b.x1)
			std::swap(b.x0, b.x1);
		if (b.y0 > b.y1)
			std::swap(b.y0, b.y1);
		if (b.z0 > b.z1)
			std::swap(b.z0, b.z1);
		if (b.x0 < b.x1 && b.y0 < b.y1 && b.z0 < b.z1 && g_h.injected.size() < 64)
			g_h.injected.push_back(b);
	}
	++g_h.injectedGen;
	LUA->PushNumber(static_cast<double>(g_h.injected.size()));
	return 1;
}
}  // namespace

void RegisterHoles(ILua *L)
{
	struct Fn
	{
		const char *name;
		CFunc fn;
	};
	static const Fn fns[] = {
		{ "HolesPrepare", HolesPrepare },
		{ "HolesDraw", HolesDraw },
		{ "HolesInfo", HolesInfo },
		{ "HolesCells", HolesCells },
		{ "HolesDevInject", HolesDevInject },
	};
	for (const Fn &f : fns)
	{
		L->PushCFunction(f.fn);
		L->SetField(-2, f.name);
	}
}

// Module close (from CloseBlocks, before its pending meshes are destroyed).
void CloseHoles()
{
	DropMeshes();
	g_h = Holes{};
	std::vector<holes::Box>().swap(g_boxes);
	std::vector<blk::OutVertex>().swap(g_verts);
	std::vector<DigStore::SectionBits>().swap(g_snap);
}
}  // namespace gc

#endif  // GMODCRAFT_CLIENT
