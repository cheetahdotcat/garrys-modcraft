// Tier 0 (0.4 Physics), server: region convexes for the static physics world (physbuild.hpp), built
// on a worker thread of their own; server/physworld.lua turns them into entities. Lua API:
//   PhysRegionRequest(rx, ry, rz, size, gen) -> bool   queue a build of the MC block box
//        [rx*size, (rx+1)*size)^3 (size 2..32); false without a map view, a bad size or 256 queued
//   PhysRegionTake(max) -> { { rx, ry, rz, size, gen, ok, truncated, ox, oy, oz (the region's Source
//        mins: the entity origin), verts = { x, y, z, ... } (relative), counts = { n, ... },
//        brushes, prisms, boxes, carved, buildMs, waitMs }, ... }   finished builds, oldest first
//   PhysDirty() -> { cells = { x, y, z, ... }, sections = { sx, sy, sz, ... }, all = bool }
//        dug cells (current map) and Minecraft block sections changed since the last call; all = the
//        block store was cleared or too much changed (rebuild everything)
//   PhysInfo() -> { queued, done, builds, maxMs, lastMs, truncated, viewId }
#ifdef GMODCRAFT_SERVER

#include "collision.hpp"
#include "colstream.hpp"
#include "lua_util.hpp"
#include "physbuild.hpp"
#include "solidbox.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <deque>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace gc
{
using namespace GarrysMod::Lua;

const sb::Section *ServerSolidMerged(const sb::SectionKey &k);  // server.cpp
const sb::Section *ServerSolidFind(const sb::SectionKey &k);
const std::vector<sb::Box> *ServerSolidMicro(const sb::SectionKey &k);  // v36: eighths, 0..128
void SetServerSolidsHook(void (*hook)(int sx, int sy, int sz, bool all));

namespace
{
constexpr std::size_t kMaxQueued = 256;
constexpr std::size_t kMaxDirty = 65536;  // cells / sections kept; more: "all"

struct Result
{
	int rx, ry, rz, size;
	double gen;
	bool ok = false;
	pw::Box region{};
	pw::RegionOutput out;
	double postedMs = 0, doneMs = 0;
};

struct Shared
{
	std::mutex mu;
	std::deque<std::unique_ptr<Result>> done;
	std::size_t queued = 0;
	std::uint64_t builds = 0, truncated = 0;
	double maxMs = 0, lastMs = 0;
};

Worker *g_worker = nullptr;
std::shared_ptr<Shared> g_shared = std::make_shared<Shared>();
std::vector<int> g_dirtyCells, g_dirtySections;
bool g_dirtyAll = false;
bool g_hooked = false;

double NowMs()
{
	return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void OnDug(const std::vector<std::array<int, 3>> &cells)
{
	if (g_dirtyCells.size() + cells.size() * 3 > kMaxDirty * 3)
	{
		g_dirtyAll = true;
		return;
	}
	for (const auto &c : cells)
		g_dirtyCells.insert(g_dirtyCells.end(), { c[0], c[1], c[2] });
}

void OnSolids(int sx, int sy, int sz, bool all)
{
	if (all || g_dirtySections.size() >= kMaxDirty * 3)
	{
		g_dirtyAll = true;
		return;
	}
	g_dirtySections.insert(g_dirtySections.end(), { sx, sy, sz });
}

void Hook()
{
	if (g_hooked)
		return;
	g_hooked = true;
	SetCollisionDugHook(OnDug);
	SetServerSolidsHook(OnSolids);
}

int FloorDiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

LUA_FUNCTION_STATIC(PhysRegionRequest)
{
	Hook();
	const double rxd = ArgNum(LUA, 1, NAN), ryd = ArgNum(LUA, 2, NAN), rzd = ArgNum(LUA, 3, NAN);
	const double sized = ArgNum(LUA, 4, 8), gen = ArgNum(LUA, 5, 0);
	if (!std::isfinite(rxd) || !std::isfinite(ryd) || !std::isfinite(rzd) || std::fabs(rxd) > 1e6 || std::fabs(ryd) > 1e6 || std::fabs(rzd) > 1e6 ||
		!(sized >= 2 && sized <= 32))
	{
		LUA->PushBool(false);
		return 1;
	}
	std::shared_ptr<const MapView> view = CollisionView();
	std::shared_ptr<Shared> sh = g_shared;
	{
		std::lock_guard<std::mutex> l(sh->mu);
		if (!view || sh->queued >= kMaxQueued)
		{
			LUA->PushBool(false);
			return 1;
		}
		++sh->queued;
	}
	const int size = static_cast<int>(sized);
	const int rx = static_cast<int>(rxd), ry = static_cast<int>(ryd), rz = static_cast<int>(rzd);
	const int lo[3] = { rx * size, ry * size, rz * size };
	// Minecraft blocks: the sections overlapping the region (game thread: the store isn't shared)
	std::vector<pw::Box> blocks;
	for (int sy = FloorDiv(lo[1], 16); sy <= FloorDiv(lo[1] + size - 1, 16); ++sy)
		for (int sz = FloorDiv(lo[2], 16); sz <= FloorDiv(lo[2] + size - 1, 16); ++sz)
			for (int sx = FloorDiv(lo[0], 16); sx <= FloorDiv(lo[0] + size - 1, 16); ++sx)
			{
				// each list at its own scale: half blocks (v26 shapes: slabs, stairs) and (v36) eighths
				// (microblocks: covers, panels, posts)
				const auto add = [&](const std::vector<sb::Box> &boxes, double u) {
					for (const sb::Box &b : boxes)
					{
						const double x0 = 16 * sx + b.x0 * u, y0 = 16 * sy + b.y0 * u, z0 = 16 * sz + b.z0 * u;
						const double x1 = 16 * sx + b.x1 * u, y1 = 16 * sy + b.y1 * u, z1 = 16 * sz + b.z1 * u;
						if (x1 <= lo[0] || x0 >= lo[0] + size || y1 <= lo[1] || y0 >= lo[1] + size || z1 <= lo[2] || z0 >= lo[2] + size)
							continue;
						blocks.push_back(pw::McBoxToSource(view->frame, x0, y0, z0, x1, y1, z1));
					}
				};
				if (const sb::Section *s = ServerSolidMerged({ sx, sy, sz }))
					add(s->boxes, 0.5);
				if (const std::vector<sb::Box> *m = ServerSolidMicro({ sx, sy, sz }))
					add(*m, 1.0 / sb::kMicroUnits);
			}
	// T0b: the octants around the region (step wedges for vehicles), half-block units
	pw::HalfOcc occ;
	occ.x0 = 2 * lo[0] - 4, occ.y0 = 2 * lo[1] - 2, occ.z0 = 2 * lo[2] - 4;
	occ.nx = 2 * size + 8, occ.ny = 2 * size + 4, occ.nz = 2 * size + 8;
	bool anySection = false;  // most regions have no Minecraft blocks at all: skip the cell loop
	for (int sy = FloorDiv(FloorDiv(occ.y0, 2), 16); sy <= FloorDiv(FloorDiv(occ.y0 + occ.ny - 1, 2), 16) && !anySection; ++sy)
		for (int sz = FloorDiv(FloorDiv(occ.z0, 2), 16); sz <= FloorDiv(FloorDiv(occ.z0 + occ.nz - 1, 2), 16) && !anySection; ++sz)
			for (int sx = FloorDiv(FloorDiv(occ.x0, 2), 16); sx <= FloorDiv(FloorDiv(occ.x0 + occ.nx - 1, 2), 16) && !anySection; ++sx)
				anySection = ServerSolidFind({ sx, sy, sz }) != nullptr;
	occ.v.assign(anySection ? static_cast<std::size_t>(occ.nx * occ.ny * occ.nz) : 0, 0);
	bool anySolid = false;
	for (int y = 0; y < (anySection ? occ.ny : 0); ++y)
		for (int z = 0; z < occ.nz; ++z)
			for (int x = 0; x < occ.nx; ++x)
			{
				const int hx = occ.x0 + x, hy = occ.y0 + y, hz = occ.z0 + z;
				const int bx = FloorDiv(hx, 2), by = FloorDiv(hy, 2), bz = FloorDiv(hz, 2);
				const sb::Section *sec = ServerSolidFind({ FloorDiv(bx, 16), FloorDiv(by, 16), FloorDiv(bz, 16) });
				if (sec == nullptr)
					continue;
				const int lx = bx - 16 * FloorDiv(bx, 16), ly = by - 16 * FloorDiv(by, 16), lz = bz - 16 * FloorDiv(bz, 16);
				if (!sb::BitAt(sec->bits, lx, ly, lz))
					continue;
				const int oct = sec->shapes.empty() ? 0 : sec->shapes[static_cast<std::size_t>(lx + 16 * lz + 256 * ly)];
				const int o = (hx - 2 * bx) + 2 * (hy - 2 * by) + 4 * (hz - 2 * bz);
				if (oct == 0 || ((oct >> o) & 1))
				{
					occ.v[static_cast<std::size_t>(x + occ.nx * (z + occ.nz * y))] = 1;
					anySolid = true;
				}
			}
	if (!anySolid)
		occ.v.clear(), occ.nx = occ.ny = occ.nz = 0;
	const std::uint32_t worldId = CollisionWorldId();
	const DigStore *dig = &CollisionDigs();
	const double posted = NowMs();
	if (g_worker == nullptr)
		g_worker = new Worker();
	g_worker->Post([sh, view, dig, worldId, rx, ry, rz, size, gen, lo, blocks = std::move(blocks), occ = std::move(occ), posted]() mutable {
		auto r = std::make_unique<Result>();
		r->rx = rx, r->ry = ry, r->rz = rz, r->size = size, r->gen = gen, r->postedMs = posted;
		try
		{
			const mcol::McFrame &f = view->frame;
			pw::RegionInput in;
			in.mesh = &view->data->map.world;
			in.mats = &view->data->map.materials;
			in.region = pw::McBoxToSource(f, lo[0], lo[1], lo[2], lo[0] + size, lo[1] + size, lo[2] + size);
			in.blocks = std::move(blocks);
			if (occ.nx > 0)
			{
				const int hlo[3] = { 2 * lo[0], 2 * lo[1], 2 * lo[2] }, hhi[3] = { 2 * (lo[0] + size), 2 * (lo[1] + size), 2 * (lo[2] + size) };
				// the map's floor under a first step (cell centres; one point test each, cached per cell)
				std::unordered_map<std::int64_t, bool> seen;
				// dug cells around the region (the occupancy's margin): their map solid is gone
				std::vector<std::array<int, 3>> dugNear;
				const int dlo[3] = { FloorDiv(occ.x0, 2), FloorDiv(occ.y0, 2), FloorDiv(occ.z0, 2) };
				const int dhi[3] = { FloorDiv(occ.x0 + occ.nx - 1, 2), FloorDiv(occ.y0 + occ.ny - 1, 2), FloorDiv(occ.z0 + occ.nz - 1, 2) };
				dig->Collect(worldId, dlo, dhi, dugNear);
				std::unordered_map<std::int64_t, bool> dugSet;
				for (const auto &c : dugNear)
					dugSet.emplace((static_cast<std::int64_t>(c[0]) * 1048576 + c[1]) * 1048576 + c[2], true);
				const std::function<bool(int, int, int)> ground = [&](int hx, int hy, int hz) {
					const std::int64_t k = (static_cast<std::int64_t>(hx) * 1048576 + hy) * 1048576 + hz;
					auto it = seen.find(k);
					if (it != seen.end())
						return it->second;
					const std::int64_t bk = (static_cast<std::int64_t>(FloorDiv(hx, 2)) * 1048576 + FloorDiv(hy, 2)) * 1048576 + FloorDiv(hz, 2);
					const float mc[3] = { hx * 0.5f + 0.25f, hy * 0.5f + 0.25f, hz * 0.5f + 0.25f };
					const bool v = dugSet.count(bk) == 0 && pw::MapSolidAt(view->data->map.world, view->index, f, mc);
					seen.emplace(k, v);
					return v;
				};
				pw::StepWedges(occ, hlo, hhi, f, in.wedges, &ground);
			}
			// candidates, MC space: convexes overlapping the region; triangles whose prism can reach it
			const float m = pw::kPrismDepth / 40.0f + 0.05f;
			const float clo[3] = { lo[0] - 0.01f, lo[1] - 0.01f, lo[2] - 0.01f };
			const float chi[3] = { lo[0] + size + 0.01f, lo[1] + size + 0.01f, lo[2] + size + 0.01f };
			const float tlo[3] = { lo[0] - m, lo[1] - m, lo[2] - m };
			const float thi[3] = { lo[0] + size + m, lo[1] + size + m, lo[2] + size + m };
			view->index.ConvexesInBox(clo, chi, in.convexIds);
			view->index.TrianglesInBox(tlo, thi, in.triIds);
			// dug cells in the region, merged
			std::vector<std::array<int, 3>> cells;
			const int hi[3] = { lo[0] + size - 1, lo[1] + size - 1, lo[2] + size - 1 };
			dig->Collect(worldId, lo, hi, cells);
			std::vector<std::array<int, 6>> boxes;
			pw::MergeCells(cells, lo, size, boxes);
			bool tooMany = boxes.size() > pw::kMaxDugBoxes;
			for (std::size_t i = 0; i < boxes.size() && i < pw::kMaxDugBoxes; ++i)
			{
				const auto &b = boxes[i];
				in.dug.push_back(pw::McBoxToSource(f, b[0], b[1], b[2], b[3], b[4], b[5]));
			}
			r->region = in.region;
			pw::BuildRegion(in, r->out);
			r->out.truncated = r->out.truncated || tooMany;
			r->ok = true;
		}
		catch (...)
		{
			r->ok = false;
		}
		r->doneMs = NowMs();
		std::lock_guard<std::mutex> l(sh->mu);
		if (sh->queued > 0)
			--sh->queued;
		++sh->builds;
		if (r->out.truncated)
			++sh->truncated;
		sh->lastMs = r->out.ms;
		sh->maxMs = std::max(sh->maxMs, r->out.ms);
		sh->done.push_back(std::move(r));
	});
	LUA->PushBool(true);
	return 1;
}

void PushInts(ILua *L, const std::vector<int> &v, const char *field)
{
	L->CreateTable();
	for (std::size_t i = 0; i < v.size(); ++i)
	{
		L->PushNumber(static_cast<double>(i + 1));
		L->PushNumber(v[i]);
		L->SetTable(-3);
	}
	L->SetField(-2, field);
}

LUA_FUNCTION_STATIC(PhysRegionTake)
{
	Hook();
	const double maxArg = ArgNum(LUA, 1, 4);
	const std::size_t max = static_cast<std::size_t>(std::max(0.0, std::min(maxArg, 64.0)));
	std::vector<std::unique_ptr<Result>> got;
	{
		std::shared_ptr<Shared> sh = g_shared;
		std::lock_guard<std::mutex> l(sh->mu);
		while (!sh->done.empty() && got.size() < max)
		{
			got.push_back(std::move(sh->done.front()));
			sh->done.pop_front();
		}
	}
	LUA->CreateTable();
	int i = 0;
	for (const auto &r : got)
	{
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "rx", r->rx);
		SetNum(LUA, "ry", r->ry);
		SetNum(LUA, "rz", r->rz);
		SetNum(LUA, "size", r->size);
		SetNum(LUA, "gen", r->gen);
		SetBool(LUA, "ok", r->ok);
		SetBool(LUA, "truncated", r->out.truncated);
		SetNum(LUA, "ox", r->region.lo[0]);
		SetNum(LUA, "oy", r->region.lo[1]);
		SetNum(LUA, "oz", r->region.lo[2]);
		SetNum(LUA, "brushes", r->out.brushes);
		SetNum(LUA, "prisms", r->out.prisms);
		SetNum(LUA, "boxes", r->out.boxes);
		SetNum(LUA, "carved", r->out.carved);
		SetNum(LUA, "wedges", r->out.wedges);
		SetNum(LUA, "wedgesDropped", r->out.wedgesDropped);
		SetNum(LUA, "buildMs", r->out.ms);
		SetNum(LUA, "waitMs", r->doneMs - r->postedMs);
		LUA->CreateTable();
		for (std::size_t k = 0; k < r->out.verts.size(); ++k)
		{
			LUA->PushNumber(static_cast<double>(k + 1));
			LUA->PushNumber(r->out.verts[k]);
			LUA->SetTable(-3);
		}
		LUA->SetField(-2, "verts");
		LUA->CreateTable();
		for (std::size_t k = 0; k < r->out.counts.size(); ++k)
		{
			LUA->PushNumber(static_cast<double>(k + 1));
			LUA->PushNumber(r->out.counts[k]);
			LUA->SetTable(-3);
		}
		LUA->SetField(-2, "counts");
		LUA->SetTable(-3);
	}
	return 1;
}

LUA_FUNCTION_STATIC(PhysDirty)
{
	Hook();
	LUA->CreateTable();
	PushInts(LUA, g_dirtyCells, "cells");
	PushInts(LUA, g_dirtySections, "sections");
	SetBool(LUA, "all", g_dirtyAll);
	g_dirtyCells.clear();
	g_dirtySections.clear();
	g_dirtyAll = false;
	return 1;
}

LUA_FUNCTION_STATIC(PhysInfo)
{
	std::shared_ptr<Shared> sh = g_shared;
	std::shared_ptr<const MapView> view = CollisionView();
	std::lock_guard<std::mutex> l(sh->mu);
	LUA->CreateTable();
	SetNum(LUA, "queued", static_cast<double>(sh->queued));
	SetNum(LUA, "done", static_cast<double>(sh->done.size()));
	SetNum(LUA, "builds", static_cast<double>(sh->builds));
	SetNum(LUA, "truncated", static_cast<double>(sh->truncated));
	SetNum(LUA, "maxMs", sh->maxMs);
	SetNum(LUA, "lastMs", sh->lastMs);
	SetNum(LUA, "viewId", view ? view->id : 0);
	return 1;
}
}  // namespace

void RegisterPhysWorld(ILua *L)
{
	struct Fn
	{
		const char *name;
		CFunc fn;
	};
	static const Fn fns[] = {
		{ "PhysRegionRequest", PhysRegionRequest },
		{ "PhysRegionTake", PhysRegionTake },
		{ "PhysDirty", PhysDirty },
		{ "PhysInfo", PhysInfo },
	};
	for (const Fn &f : fns)
	{
		L->PushCFunction(f.fn);
		L->SetField(-2, f.name);
	}
}

void ClosePhysWorld()
{
	if (g_worker != nullptr)
		g_worker->Stop();
	delete g_worker;
	g_worker = nullptr;
	SetCollisionDugHook(nullptr);
	SetServerSolidsHook(nullptr);
	g_hooked = false;
	g_shared = std::make_shared<Shared>();
	g_dirtyCells.clear();
	g_dirtySections.clear();
	g_dirtyAll = false;
}
}  // namespace gc

#endif  // GMODCRAFT_SERVER
