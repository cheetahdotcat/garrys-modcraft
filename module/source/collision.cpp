// Lua bindings for P2 collision (see collision.hpp, colstream.hpp). Both realms. The API is
// documented in addon/gmodcraft/README.md.
#include "collision.hpp"

#include "colstream.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

namespace gc
{
using namespace GarrysMod::Lua;

namespace
{
Worker g_worker;
DigStore g_dig;
DynamicSet g_dyn;
mcol::McFrame g_frame;  // the slot origin of the last ColUpdate (the dynamic layer's MC frame)
std::uint64_t g_dynCalls = 0;
Streamer *g_stream = nullptr;  // created on first use, so its destructor never runs after the worker's
MapLoader *g_loader = nullptr;

// Hand-over slots from the worker (decode, index builds).
struct Slots
{
	std::mutex mu;
	bool decodeDone = false;
	std::shared_ptr<MapData> map;
	std::string error;
	std::shared_ptr<MapView> view;
	std::uint32_t generation = 0;  // bumped on MapBegin: results of older loads are ignored
};
std::shared_ptr<Slots> g_slots = std::make_shared<Slots>();

enum class MapState
{
	kNone,
	kLoading,
	kDecoding,
	kReady,
	kError
};
MapState g_state = MapState::kNone;
std::string g_mapName, g_error, g_loadWarnings;
std::shared_ptr<const MapData> g_map;
std::shared_ptr<const MapView> g_view;
bool g_indexing = false;
std::uint32_t g_viewIds = 0;
double g_luaStartMs = 0, g_decodePostedMs = 0, g_luaMs = 0, g_queueMs = 0;
std::uint32_t g_epoch = 0;
bool g_epochSet = false;
std::uint32_t g_worldId = 0;
void (*g_dugHook)(const std::vector<std::array<int, 3>> &) = nullptr;  // Tier 0 (physworld.cpp, server)

// Water grid bookkeeping, per player slot (v14: the server link has one grid per HostPlayers
// slot; the client link has one, slot 0).
struct WaterState
{
	std::int32_t bx = 0x7FFFFFFF, by = 0, bz = 0;
	std::uint32_t viewId = 0;
	int columns = 0;
	std::uint64_t writes = 0;
	std::int32_t originX = 0, originZ = 0;
	float surface[P::kWaterGridSize * P::kWaterGridSize];
};
WaterState *g_waters = nullptr;  // [kMaxPlayers], on the heap (130 KiB)
int g_waterLast = -1;            // the slot written last (ColWater / ColStats)
std::uint64_t g_waterWrites = 0, g_waterClears = 0;

WaterState &Water(int slot)
{
	if (g_waters == nullptr)
		g_waters = new WaterState[P::kMaxPlayers];
	return g_waters[slot];
}

void WaterForgetAll()
{
	if (g_waters == nullptr)
		return;
	for (std::uint32_t i = 0; i < P::kMaxPlayers; ++i)
		g_waters[i].bx = 0x7FFFFFFF;
}
std::uint64_t g_dugMessages = 0, g_dugCells = 0;

Streamer &Stream()
{
	if (g_stream == nullptr)
		g_stream = new Streamer(g_worker, g_dig);
	return *g_stream;
}

MapLoader &Loader()
{
	if (g_loader == nullptr)
		g_loader = new MapLoader();
	return *g_loader;
}

const char *StateName(MapState s)
{
	switch (s)
	{
	case MapState::kLoading: return "loading";
	case MapState::kDecoding: return "decoding";
	case MapState::kReady: return "ready";
	case MapState::kError: return "error";
	default: return "none";
	}
}

std::string ArgStr(ILua *L, int idx)
{
	if (!L->IsType(idx, Type::String))
		return std::string();
	unsigned int len = 0;
	const char *s = L->GetString(idx, &len);
	return std::string(s, len);
}

// Picks up the worker's results (game thread).
void Poll()
{
	std::lock_guard<std::mutex> l(g_slots->mu);
	if (g_slots->decodeDone)
	{
		g_slots->decodeDone = false;
		if (g_slots->map)
		{
			g_map = std::move(g_slots->map);
			g_dyn.SetMap(g_map);  // brush models ("*N") for the dynamic layer
			if (g_stream != nullptr)
				g_stream->SetDynamic(nullptr);
			g_state = MapState::kReady;
			g_queueMs = NowMs() - g_decodePostedMs - g_map->totalMs;
		}
		else
		{
			g_state = MapState::kError;
			g_error = g_slots->error;
		}
	}
	if (g_slots->view)
	{
		g_view = std::move(g_slots->view);
		g_indexing = false;
		Stream().SetView(g_view, g_worldId);
	}
}

// ---- map loading -------------------------------------------------------------------------------
// MapBegin(map, header1036) -> { {lump, offset, length}, ... } | nil, err
LUA_FUNCTION_STATIC(MapBegin)
{
	std::string name = ArgStr(LUA, 1), header = ArgStr(LUA, 2), err;
	{
		std::lock_guard<std::mutex> l(g_slots->mu);
		++g_slots->generation;
		g_slots->decodeDone = false;
		g_slots->map.reset();
		g_slots->view.reset();
	}
	g_map.reset();
	g_view.reset();
	g_indexing = false;
	g_dyn.SetMap(nullptr);
	if (g_stream != nullptr)
	{
		g_stream->SetView(nullptr, g_worldId);
		g_stream->SetDynamic(nullptr);
	}
	g_mapName = name;
	g_error.clear();
	g_loadWarnings.clear();
	g_luaStartMs = NowMs();
	if (!Loader().Begin(name, reinterpret_cast<const std::uint8_t *>(header.data()), header.size(), err))
	{
		g_state = MapState::kError;
		g_error = err;
		return PushFail(LUA, err.c_str());
	}
	g_state = MapState::kLoading;
	LUA->CreateTable();
	int i = 0;
	for (const auto &r : Loader().Needed())
	{
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "lump", r.lump);
		SetNum(LUA, "offset", static_cast<double>(r.offset));
		SetNum(LUA, "length", static_cast<double>(r.length));
		LUA->SetTable(-3);
	}
	return 1;
}

// MapLump(lump, bytes) -> true[, warning] | nil, err
LUA_FUNCTION_STATIC(MapLump)
{
	double lump = ArgNum(LUA, 1, -1);
	if (!LUA->IsType(2, Type::String) || lump < 0 || lump >= mcol::kLumpCount || g_state != MapState::kLoading)
		return PushFail(LUA, "MapLump(lump, bytes) after MapBegin");
	unsigned int len = 0;
	const char *p = LUA->GetString(2, &len);
	std::string err;
	bool ok = Loader().AddLump(static_cast<int>(lump), reinterpret_cast<const std::uint8_t *>(p), len, err);
	if (!ok)
		return PushFail(LUA, err.c_str());
	LUA->PushBool(true);
	if (err.empty())
		return 1;
	g_loadWarnings += err + "\n";
	LUA->PushString(err.c_str());
	return 2;
}

void PushStrings(ILua *L, const std::vector<std::string> &v, const char *field)
{
	L->CreateTable();
	for (std::size_t i = 0; i < v.size(); ++i)
	{
		L->PushNumber(static_cast<double>(i + 1));
		L->PushString(v[i].c_str());
		L->SetTable(-3);
	}
	L->SetField(-2, field);
}

// MapPrepare() -> { phy = {models}, bbox = {models}, textures = {texdata names} }[, warning]
LUA_FUNCTION_STATIC(MapPrepare)
{
	if (g_state != MapState::kLoading)
		return PushFail(LUA, "MapPrepare after MapBegin and the lumps");
	std::vector<std::string> phy, bbox, tex;
	std::string err;
	bool ok = Loader().Prepare(phy, bbox, tex, err);
	if (!ok)
		return PushFail(LUA, err.c_str());
	LUA->CreateTable();
	PushStrings(LUA, phy, "phy");
	PushStrings(LUA, bbox, "bbox");
	PushStrings(LUA, tex, "textures");
	if (err.empty())
		return 1;
	g_loadWarnings += err + "\n";
	LUA->PushString(err.c_str());
	return 2;
}

// MapPhy(model, bytes)
LUA_FUNCTION_STATIC(MapPhy)
{
	if (g_state != MapState::kLoading || !LUA->IsType(1, Type::String) || !LUA->IsType(2, Type::String))
		return 0;
	std::string model = ArgStr(LUA, 1);
	unsigned int len = 0;
	const char *p = LUA->GetString(2, &len);
	Loader().AddPhy(model, reinterpret_cast<const std::uint8_t *>(p), len);
	return 0;
}

// MapBBox(model, minx, miny, minz, maxx, maxy, maxz)
LUA_FUNCTION_STATIC(MapBBox)
{
	if (g_state != MapState::kLoading || !LUA->IsType(1, Type::String))
		return 0;
	float mn[3], mx[3];
	for (int i = 0; i < 3; ++i)
	{
		mn[i] = static_cast<float>(ArgNum(LUA, 2 + i, 0));
		mx[i] = static_cast<float>(ArgNum(LUA, 5 + i, 0));
	}
	Loader().AddBBox(ArgStr(LUA, 1), mn, mx);
	return 0;
}

// MapSurfaceProps(text): one scripts/surfaceproperties*.txt, in manifest order.
LUA_FUNCTION_STATIC(MapSurfaceProps)
{
	if (g_state == MapState::kLoading && LUA->IsType(1, Type::String))
		Loader().AddSurfaceProps(ArgStr(LUA, 1));
	return 0;
}

// MapVmt(texture, vmtText) -> ok, include ("" if none)
LUA_FUNCTION_STATIC(MapVmt)
{
	if (g_state != MapState::kLoading || !LUA->IsType(1, Type::String) || !LUA->IsType(2, Type::String))
	{
		LUA->PushBool(false);
		return 1;
	}
	std::string include;
	bool ok = Loader().AddVmt(ArgStr(LUA, 1), ArgStr(LUA, 2), include);
	LUA->PushBool(ok);
	LUA->PushString(include.c_str());
	return 2;
}

// MapTexProps(texture, prop, prop2)
LUA_FUNCTION_STATIC(MapTexProps)
{
	if (g_state == MapState::kLoading && LUA->IsType(1, Type::String))
		Loader().AddTexProps(ArgStr(LUA, 1), ArgStr(LUA, 2), ArgStr(LUA, 3));
	return 0;
}

// MapDecode() -> ok: decodes on the worker thread; MapStatus() tells when it's done.
LUA_FUNCTION_STATIC(MapDecode)
{
	if (g_state != MapState::kLoading)
	{
		LUA->PushBool(false);
		return 1;
	}
	g_state = MapState::kDecoding;
	g_decodePostedMs = NowMs();
	g_luaMs = g_decodePostedMs - g_luaStartMs;
	std::shared_ptr<MapLoader> job = std::make_shared<MapLoader>(std::move(Loader()));
	*g_loader = MapLoader();
	std::shared_ptr<Slots> slots = g_slots;
	std::uint32_t gen;
	{
		std::lock_guard<std::mutex> l(slots->mu);
		gen = slots->generation;
	}
	g_worker.Post([job, slots, gen] {
		std::string err;
		std::shared_ptr<MapData> d = job->Decode(err);
		std::lock_guard<std::mutex> l(slots->mu);
		if (slots->generation != gen)
			return;
		slots->decodeDone = true;
		slots->map = d;
		slots->error = d ? std::string() : err;
	});
	LUA->PushBool(true);
	return 1;
}

void SetKinds(ILua *L, const std::uint32_t *k, const char *field)
{
	L->CreateTable();
	SetNum(L, "world", k[mcol::kSrcWorld]);
	SetNum(L, "playerClip", k[mcol::kSrcPlayerClip]);
	SetNum(L, "displacement", k[mcol::kSrcDisplacement]);
	SetNum(L, "staticProp", k[mcol::kSrcStaticProp]);
	L->SetField(-2, field);
}

// MapStatus() -> { state, map, error, warnings, timings, counts, materials }
// MapAnchor({ spawns = { {x, y, z}, ... }, nav = { z, ... } | nil, sky = {x, y, z} | nil }) -> { source, floorZ,
//   minZ, maxZ, footMinX, footMinY, footMaxX, footMaxY, modeZ, lowestZ, excludedSky, excludedTop, excludedCovered,
//   ms, spawnFloors = { z, ... }, bins = { {z, area}, ... (8) } }
// | nil while the map isn't decoded. P8 WP1 (v21): the map's main floor for the slot's vertical offset,
// by mapcol::ChooseAnchor (the floor most spawns stand on; navmesh, then walkable area break ties;
// else the map's most common walkable floor). sky: the sky_camera's origin, so the map mode leaves the
// 3D skybox room out (P8 WP2 nit). One pass over the walkable set. Synchronous (tens of ms on a big
// map): once per map.
LUA_FUNCTION_STATIC(MapAnchor)
{
	if (!g_map)
		return 0;
	const double t0 = NowMs();
	std::vector<mcol::Vec3> spawns;
	std::vector<float> nav;
	mcol::AnchorOptions opt;
	if (LUA->IsType(1, Type::Table))
	{
		LUA->GetField(1, "sky");
		if (LUA->IsType(-1, Type::Table))
		{
			const int e = LUA->Top();
			const double x = FieldNum(LUA, e, "x", NAN), y = FieldNum(LUA, e, "y", NAN), z = FieldNum(LUA, e, "z", NAN);
			if (std::isfinite(x) && std::isfinite(y) && std::isfinite(z) && std::fabs(x) < 1e6 && std::fabs(y) < 1e6 && std::fabs(z) < 1e6)
			{
				opt.haveSky = true;
				opt.sky = mcol::Vec3{ static_cast<float>(x), static_cast<float>(y), static_cast<float>(z) };
			}
		}
		LUA->Pop();
		LUA->GetField(1, "spawns");
		if (LUA->IsType(-1, Type::Table))
		{
			const int t = LUA->Top();
			for (int i = 1; i <= 4096; ++i)
			{
				LUA->PushNumber(i);
				LUA->GetTable(t);
				if (!LUA->IsType(-1, Type::Table))
				{
					LUA->Pop();
					break;
				}
				const int e = LUA->Top();
				const double x = FieldNum(LUA, e, "x", NAN), y = FieldNum(LUA, e, "y", NAN), z = FieldNum(LUA, e, "z", NAN);
				if (std::isfinite(x) && std::isfinite(y) && std::isfinite(z) && std::fabs(x) < 1e6 && std::fabs(y) < 1e6 && std::fabs(z) < 1e6)
					spawns.push_back(mcol::Vec3{ static_cast<float>(x), static_cast<float>(y), static_cast<float>(z) });
				LUA->Pop();
			}
		}
		LUA->Pop();
		LUA->GetField(1, "nav");
		if (LUA->IsType(-1, Type::Table))
		{
			const int t = LUA->Top();
			for (int i = 1; i <= 65536; ++i)
			{
				LUA->PushNumber(i);
				LUA->GetTable(t);
				if (!LUA->IsType(-1, Type::Number))
				{
					LUA->Pop();
					break;
				}
				const double z = LUA->GetNumber(-1);
				if (std::isfinite(z) && std::fabs(z) < 1e6)
					nav.push_back(static_cast<float>(z));
				LUA->Pop();
			}
		}
		LUA->Pop();
	}
	const mcol::Mesh &world = g_map->map.world;
	const mcol::AnchorResult r = mcol::ChooseAnchor(world, spawns, nav, opt);
	const mcol::FloorStats &st = r.stats;
	LUA->CreateTable();
	SetNum(LUA, "source", r.source);
	SetNum(LUA, "floorZ", r.floorZ);
	SetNum(LUA, "minZ", r.minZ);
	SetNum(LUA, "maxZ", r.maxZ);
	SetNum(LUA, "footMinX", r.footMinX);
	SetNum(LUA, "footMinY", r.footMinY);
	SetNum(LUA, "footMaxX", r.footMaxX);
	SetNum(LUA, "footMaxY", r.footMaxY);
	SetNum(LUA, "modeZ", st.modeZ);
	SetNum(LUA, "lowestZ", st.lowestZ);
	SetNum(LUA, "excludedSky", st.excludedSky);
	SetNum(LUA, "excludedTop", st.excludedTop);
	SetNum(LUA, "excludedCovered", st.excludedCovered);
	SetNum(LUA, "spawns", static_cast<double>(spawns.size()));
	LUA->CreateTable();
	for (std::size_t i = 0; i < r.spawnFloors.size(); ++i)
	{
		LUA->PushNumber(static_cast<double>(i + 1));
		LUA->PushNumber(r.spawnFloors[i]);
		LUA->SetTable(-3);
	}
	LUA->SetField(-2, "spawnFloors");
	LUA->CreateTable();
	for (std::size_t i = 0; i < st.bins.size() && i < 8; ++i)
	{
		LUA->PushNumber(static_cast<double>(i + 1));
		LUA->CreateTable();
		SetNum(LUA, "z", static_cast<double>(st.bins[i].first));
		SetNum(LUA, "area", st.bins[i].second);
		LUA->SetTable(-3);
	}
	LUA->SetField(-2, "bins");
	SetNum(LUA, "ms", NowMs() - t0);
	return 1;
}

LUA_FUNCTION_STATIC(MapStatus)
{
	Poll();
	LUA->CreateTable();
	SetStr(LUA, "state", StateName(g_state));
	SetStr(LUA, "map", g_mapName.c_str());
	SetStr(LUA, "error", g_error.c_str());
	SetNum(LUA, "luaMs", g_luaMs);
	SetBool(LUA, "indexing", g_indexing);
	if (g_view)
	{
		SetNum(LUA, "indexMs", g_view->indexMs);
		SetNum(LUA, "viewId", g_view->id);
		SetNum(LUA, "originX", g_view->frame.originX);
		SetNum(LUA, "originZ", g_view->frame.originZ);
		SetNum(LUA, "originYUnits", g_view->frame.originYUnits);
	}
	if (g_map)
	{
		const MapData &d = *g_map;
		SetStr(LUA, "warnings", (g_loadWarnings + d.warnings).c_str());
		SetNum(LUA, "decodeMs", d.decodeMs);
		SetNum(LUA, "propsMs", d.propsMs);
		SetNum(LUA, "totalMs", d.totalMs);
		SetNum(LUA, "queueMs", g_queueMs);
		SetNum(LUA, "inputBytes", static_cast<double>(d.inputBytes));
		SetNum(LUA, "tris", static_cast<double>(d.map.world.tris.size()));
		SetNum(LUA, "convexes", static_cast<double>(d.map.world.convexes.size()));
		SetNum(LUA, "water", static_cast<double>(d.map.water.size()));
		SetNum(LUA, "materialCount", static_cast<double>(d.map.materials.Size()));
		SetNum(LUA, "props", static_cast<double>(d.propCount));
		SetNum(LUA, "propsPlaced", d.props.placed);
		SetNum(LUA, "propsMissing", d.props.missingModel);
		SetNum(LUA, "propsBadPhy", d.props.badPhy);
		SetNum(LUA, "propsBBox", d.props.bboxVerify);
		SetNum(LUA, "sprpVersion", d.sprpVersion);
		SetBool(LUA, "dispRebuilt", d.map.stats.dispRebuilt);
		SetNum(LUA, "dispTris", d.map.stats.dispTris + d.map.stats.polysoupTris);
		SetNum(LUA, "dispUnknownTexture", d.map.stats.dispUnknownTexture);
		SetNum(LUA, "clipTris", d.map.stats.clipTris);
		SetKinds(LUA, d.trisByKind, "trisByKind");
		SetKinds(LUA, d.convexesByKind, "convexesByKind");
		LUA->CreateTable();
		for (std::size_t i = 0; i < d.materialTris.size() && i < 24; ++i)
		{
			LUA->PushNumber(static_cast<double>(i + 1));
			LUA->CreateTable();
			SetStr(LUA, "name", d.materialTris[i].first.c_str());
			SetNum(LUA, "tris", d.materialTris[i].second);
			LUA->SetTable(-3);
		}
		LUA->SetField(-2, "materials");
		LUA->CreateTable();
		for (std::size_t i = 0; i < d.digTris.size(); ++i)
		{
			LUA->PushNumber(static_cast<double>(i + 1));
			LUA->CreateTable();
			SetNum(LUA, "dig", d.digTris[i].first);
			SetNum(LUA, "tris", d.digTris[i].second);
			LUA->SetTable(-3);
		}
		LUA->SetField(-2, "dig");
		if (!d.map.water.empty())
		{
			SetNum(LUA, "waterSurfaceZ", d.map.water[0].surfaceZ);
			SetNum(LUA, "waterLoX", d.map.water[0].lo.x);
			SetNum(LUA, "waterLoY", d.map.water[0].lo.y);
			SetNum(LUA, "waterLoZ", d.map.water[0].lo.z);
			SetNum(LUA, "waterHiX", d.map.water[0].hi.x);
			SetNum(LUA, "waterHiY", d.map.water[0].hi.y);
			SetNum(LUA, "waterHiZ", d.map.water[0].hi.z);
		}
	}
	else
		SetStr(LUA, "warnings", g_loadWarnings.c_str());
	return 1;
}

// ---- streaming ---------------------------------------------------------------------------------
void PostIndex(const mcol::McFrame &frame)
{
	g_indexing = true;
	std::shared_ptr<const MapData> data = g_map;
	std::shared_ptr<Slots> slots = g_slots;
	std::uint32_t gen, id = ++g_viewIds;
	{
		std::lock_guard<std::mutex> l(slots->mu);
		gen = slots->generation;
	}
	g_worker.Post([data, slots, gen, id, frame] {
		auto v = std::make_shared<MapView>();
		v->data = data;
		v->frame = frame;
		v->id = id;
		const double t0 = NowMs();
		v->index.Build(v->data->map.world, v->frame);
		v->indexMs = NowMs() - t0;
		std::lock_guard<std::mutex> l(slots->mu);
		if (slots->generation == gen)
			slots->view = std::move(v);
	});
}

bool ReadPos(ILua *L, int t, Streamer::Pos &p)
{
	p.x = static_cast<float>(FieldNum(L, t, "x", NAN));
	p.y = static_cast<float>(FieldNum(L, t, "y", NAN));
	p.z = static_cast<float>(FieldNum(L, t, "z", NAN));
	return std::isfinite(p.x) && std::isfinite(p.y) && std::isfinite(p.z) && std::fabs(p.x) < 3e7f && std::fabs(p.y) < 3e7f &&
		std::fabs(p.z) < 3e7f;
}

void UpdateWater(int slot, const Streamer::Pos &p)
{
	if (slot < 0 || slot >= static_cast<int>(P::kMaxPlayers))
		return;
	P::WaterGrid *wg = RealmWaterGrid(slot);
	if (wg == nullptr || !g_view)
		return;
	WaterState &w = Water(slot);
	const std::int32_t bx = static_cast<std::int32_t>(std::floor(p.x)), by = static_cast<std::int32_t>(std::floor(p.y)),
					   bz = static_cast<std::int32_t>(std::floor(p.z));
	if (bx == w.bx && by == w.by && bz == w.bz && g_view->id == w.viewId)
		return;
	w.bx = bx;
	w.by = by;
	w.bz = bz;
	w.viewId = g_view->id;
	w.columns = FillWater(*g_view, p.x, p.y, p.z, w.originX, w.originZ, w.surface);
	++w.writes;
	++g_waterWrites;
	g_waterLast = slot;
	const std::uint32_t worldId = g_worldId;
	SeqWrite(wg, [&](P::WaterGrid &d) {
		d.originX = w.originX;
		d.originZ = w.originZ;
		d.worldId = worldId;
		std::memcpy(d.surface, w.surface, sizeof d.surface);
	});
}

// Writes slot's grid with no water anywhere (its player left, or has no position any more) and
// forgets what was written, so the next UpdateWater for the slot writes again.
bool ClearWater(int slot)
{
	if (slot < 0 || slot >= static_cast<int>(P::kMaxPlayers))
		return false;
	P::WaterGrid *wg = RealmWaterGrid(slot);
	if (wg == nullptr)
		return false;
	WaterState &w = Water(slot);
	w.bx = 0x7FFFFFFF;
	w.columns = 0;
	for (float &v : w.surface)
		v = P::kNoWater;
	++g_waterClears;
	const std::uint32_t worldId = g_worldId;
	SeqWrite(wg, [&](P::WaterGrid &d) {
		d.worldId = worldId;
		for (float &v : d.surface)
			v = P::kNoWater;
	});
	return true;
}

// ColUpdate({ epoch, worldId, ox, oz, players = { {x, y, z}, ... }, water = {x, y, z} | nil,
//   waters = { {slot, x, y, z}, ... } | nil, budgetMs })
//   -> regions written this call. Positions are MC feet. Once per frame (client) / tick (server).
//   water: the grid of slot 0 (the client link's only grid); waters: per HostPlayers slot (server
//   link, v14: one grid per player).
LUA_FUNCTION_STATIC(ColUpdate)
{
	if (!LUA->IsType(1, Type::Table))
	{
		LUA->PushNumber(0);
		return 1;
	}
	const std::uint32_t epoch = static_cast<std::uint32_t>(FieldInt(LUA, 1, "epoch", 0, 0xFFFFFFFF));
	g_worldId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "worldId", 0, 0xFFFFFFFF));
	const std::int32_t ox = static_cast<std::int32_t>(FieldInt(LUA, 1, "ox", -2147483647.0, 2147483647.0));
	const std::int32_t oz = static_cast<std::int32_t>(FieldInt(LUA, 1, "oz", -2147483647.0, 2147483647.0));
	// v21: the slot's vertical offset in Source units (absent = 0, a pre-v21 caller)
	const std::int32_t oy = static_cast<std::int32_t>(FieldInt(LUA, 1, "oy", -2147483647.0, 2147483647.0));
	const double budget = std::fmin(std::fmax(FieldNum(LUA, 1, "budgetMs", 2.5), 0.1), 50.0);
	std::vector<Streamer::Pos> players;
	Streamer::Pos water{};
	bool haveWater = false;
	std::vector<std::pair<int, Streamer::Pos>> waters;
	LUA->GetField(1, "players");
	if (LUA->IsType(-1, Type::Table))
	{
		int t = LUA->Top();
		for (int i = 1; i <= static_cast<int>(P::kMaxPlayers); ++i)
		{
			LUA->PushNumber(i);
			LUA->GetTable(t);
			if (!LUA->IsType(-1, Type::Table))
			{
				LUA->Pop();
				break;
			}
			Streamer::Pos p;
			if (ReadPos(LUA, LUA->Top(), p))
				players.push_back(p);
			LUA->Pop();
		}
	}
	LUA->Pop();
	LUA->GetField(1, "water");
	if (LUA->IsType(-1, Type::Table))
		haveWater = ReadPos(LUA, LUA->Top(), water);
	LUA->Pop();
	LUA->GetField(1, "waters");
	if (LUA->IsType(-1, Type::Table))
	{
		int t = LUA->Top();
		for (int i = 1; i <= static_cast<int>(P::kMaxPlayers); ++i)
		{
			LUA->PushNumber(i);
			LUA->GetTable(t);
			if (!LUA->IsType(-1, Type::Table))
			{
				LUA->Pop();
				break;
			}
			Streamer::Pos p;
			const double slot = FieldNum(LUA, LUA->Top(), "slot", -1);
			if (ReadPos(LUA, LUA->Top(), p) && slot >= 0 && slot < P::kMaxPlayers)
				waters.emplace_back(static_cast<int>(slot), p);
			LUA->Pop();
		}
	}
	LUA->Pop();

	Poll();
	g_frame.originX = ox;
	g_frame.originZ = oz;
	g_frame.originYUnits = oy;
	Streamer &s = Stream();
	if (!g_epochSet || epoch != g_epoch)
	{
		g_epoch = epoch;
		g_epochSet = true;
		s.Reset(epoch);
	}
	if (g_map && !g_indexing && (!g_view || g_view->data != g_map || g_view->frame != g_frame))
		PostIndex(g_frame);
	int written = 0;
	ByteRingWriter *ring = RealmCollisionRing();
	if (ring != nullptr)
		written = s.Update(players, *ring, budget);
	if (haveWater)
		UpdateWater(0, water);
	for (const auto &w : waters)
		UpdateWater(w.first, w.second);
	LUA->PushNumber(written);
	return 1;
}

// ColReset(epoch): kColClear with that epoch, then everything again (debug "clear").
LUA_FUNCTION_STATIC(ColReset)
{
	double e = ArgNum(LUA, 1, -1);
	if (e >= 0 && e <= 4294967295.0)
	{
		g_epoch = static_cast<std::uint32_t>(e);
		g_epochSet = true;
		Stream().Reset(g_epoch);
	}
	return 0;
}

// ColResend(): every region again, no clear (debug "re-send").
LUA_FUNCTION_STATIC(ColResend)
{
	Stream().Resend();
	WaterForgetAll();
	return 0;
}

// ColStats() -> streaming counters, timings, dig and water state.
LUA_FUNCTION_STATIC(ColStats)
{
	Poll();
	const Streamer &s = Stream();
	const StreamStats &st = s.Stats();
	LUA->CreateTable();
	SetNum(LUA, "epoch", s.Epoch());
	SetBool(LUA, "haveView", g_view != nullptr);
	SetNum(LUA, "regionsQueued", static_cast<double>(st.regionsQueued));
	SetNum(LUA, "regionsSent", static_cast<double>(st.regionsSent));
	SetNum(LUA, "messages", static_cast<double>(st.messages));
	SetNum(LUA, "bytes", static_cast<double>(st.bytes));
	SetNum(LUA, "clears", static_cast<double>(st.clears));
	SetNum(LUA, "stale", static_cast<double>(st.stale));
	SetNum(LUA, "ringWaits", static_cast<double>(st.ringWaits));
	SetNum(LUA, "refreshes", static_cast<double>(st.refreshes));
	SetNum(LUA, "urgent", static_cast<double>(st.urgent));
	SetNum(LUA, "digQueued", static_cast<double>(st.digQueued));
	SetNum(LUA, "digInflightSkips", static_cast<double>(st.digInflightSkips));
	SetNum(LUA, "inflight", st.inflight);
	SetNum(LUA, "ready", st.ready);
	SetNum(LUA, "known", st.known);
	const double n = st.regionsSent ? static_cast<double>(st.regionsSent) : 1.0;
	SetNum(LUA, "avgGatherMs", st.gatherMs / n);
	SetNum(LUA, "avgTrisMs", st.trisMs / n);
	SetNum(LUA, "avgVoxelMs", st.voxelMs / n);
	SetNum(LUA, "maxRegionMs", st.maxRegionMs);
	SetNum(LUA, "lastWriteMs", st.lastWriteMs);
	SetNum(LUA, "maxWriteMs", st.maxWriteMs);
	SetNum(LUA, "workerPending", static_cast<double>(g_worker.Pending()));
	SetNum(LUA, "jobErrors", static_cast<double>(st.jobErrors));
	SetNum(LUA, "dynMarked", static_cast<double>(st.dynMarked));
	SetNum(LUA, "dynSent", static_cast<double>(st.dynSent));
	SetNum(LUA, "dynLatencyAvgMs", st.dynSent ? st.dynLatencySumMs / static_cast<double>(st.dynSent) : 0.0);
	SetNum(LUA, "dynLatencyMaxMs", st.dynLatencyMaxMs);
	SetNum(LUA, "dynEntities", g_dyn.Stats().entities);
	SetNum(LUA, "dynPlaced", g_dyn.Stats().placed);
	SetNum(LUA, "dugCells", static_cast<double>(g_dig.Cells(g_worldId)));
	SetNum(LUA, "dugSections", static_cast<double>(g_dig.Sections()));
	SetNum(LUA, "dugMessages", static_cast<double>(g_dugMessages));
	SetNum(LUA, "dugChanged", static_cast<double>(g_dugCells));
	SetNum(LUA, "waterWrites", static_cast<double>(g_waterWrites));
	SetNum(LUA, "waterClears", static_cast<double>(g_waterClears));
	SetNum(LUA, "waterLastSlot", g_waterLast);
	if (g_waterLast >= 0)
	{
		const WaterState &w = Water(g_waterLast);
		SetNum(LUA, "waterColumns", w.columns);
		SetNum(LUA, "waterOriginX", w.originX);
		SetNum(LUA, "waterOriginZ", w.originZ);
	}
	return 1;
}

// ColRegions() -> { {rx, ry, rz, tris, ghosts, blocks, bytes, ageMs, ms}, ... }: what was sent.
LUA_FUNCTION_STATIC(ColRegions)
{
	LUA->CreateTable();
	int i = 0;
	const double now = NowMs();
	for (const auto &e : Stream().Sent())
	{
		const RegionInfo &r = e.second;
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "rx", r.rx);
		SetNum(LUA, "ry", r.ry);
		SetNum(LUA, "rz", r.rz);
		SetNum(LUA, "tris", r.tris);
		SetNum(LUA, "ghosts", r.ghosts);
		SetNum(LUA, "blocks", r.blocks);
		SetNum(LUA, "bytes", r.bytes);
		SetNum(LUA, "ageMs", now - r.sentMs);
		SetNum(LUA, "ms", r.gatherMs + r.trisMs + r.voxelMs);
		LUA->SetTable(-3);
	}
	return 1;
}

// ColDebug(x, y, z, radiusRegions, maxTris, wantBlocks[, haveId]) -> { id, ageMs, buildMs, tris = {x,y,z * 3 ...},
// flags = {...}, blocks = { x, y, z, fill, ... }, dynTris = {...} } | nil (only id/ageMs/buildMs
// when the newest build is haveId: the caller has its arrays already): the payloads the
// streamer sends for the regions around MC position (x, y, z), for the debug overlays. Built on the
// worker (27 regions cost 10-60 ms: never on the game thread): each call returns the newest
// finished build (nil until there is one; `id` changes with each) and asks for a new one when
// none is in flight and the request changed or the last build is older than 1 s.
// dynTris: the dynamic layer's triangles in that area, 9 numbers each, SOURCE units.
struct DebugBuild
{
	std::uint32_t id = 0;
	double builtMs = 0, buildMs = 0;
	std::vector<P::ColTri> tris;
	std::vector<float> blocks, dynTris;
};
struct DebugSlot
{
	std::mutex mu;
	bool busy = false;
	std::uint32_t ids = 0;
	std::shared_ptr<const DebugBuild> done;
};
std::shared_ptr<DebugSlot> g_debug = std::make_shared<DebugSlot>();
std::array<int, 6> g_debugAsked{};  // cx, cy, cz, radius, maxTris, blocks of the last request

void BuildDebug(const MapView &view, const DynSnapshot *dyn, const DigStore &dig, std::uint32_t worldId, std::uint32_t epoch, int cx, int cy,
	int cz, int radius, double maxTris, bool wantBlocks, DebugBuild &out)
{
	const double t0 = NowMs();
	const int S = static_cast<int>(P::kColRegionSize);
	std::vector<std::uint8_t> tp, rp;
	for (int ry = cy - 1; ry <= cy + 1; ++ry)
		for (int rz = cz - radius; rz <= cz + radius; ++rz)
			for (int rx = cx - radius; rx <= cx + radius; ++rx)
			{
				BuildRegionNow(view, dyn, dig, worldId, rx, ry, rz, epoch, tp, rp);
				P::ColRegion h;
				std::memcpy(&h, tp.data(), sizeof h);
				for (std::uint32_t i = 0; i < h.count && out.tris.size() < maxTris; ++i)
				{
					P::ColTri t;
					std::memcpy(&t, tp.data() + sizeof h + i * sizeof t, sizeof t);
					out.tris.push_back(t);
				}
				if (!wantBlocks)
					continue;
				std::memcpy(&h, rp.data(), sizeof h);
				for (std::uint32_t i = 0; i < h.count && out.blocks.size() < 4 * 20000; ++i)
				{
					P::ColBlock b;
					std::memcpy(&b, rp.data() + sizeof h + i * sizeof b, sizeof b);
					int fill = 0;
					for (auto v : b.bits)
						fill += __builtin_popcountll(v);
					out.blocks.insert(out.blocks.end(), { static_cast<float>(b.x), static_cast<float>(b.y), static_cast<float>(b.z), static_cast<float>(fill) });
				}
			}
	if (dyn && dyn->frame == view.frame)
	{
		const float lo[3] = { float((cx - radius) * S) - 0.5f, float((cy - 1) * S) - 0.5f, float((cz - radius) * S) - 0.5f };
		const float hi[3] = { float((cx + radius + 1) * S) + 0.5f, float((cy + 2) * S) + 0.5f, float((cz + radius + 1) * S) + 0.5f };
		std::vector<std::uint32_t> ids;
		dyn->index.ConvexesInBox(lo, hi, ids);
		for (std::uint32_t ci : ids)
		{
			const mcol::Convex &c = dyn->mesh.convexes[ci];
			for (std::uint32_t t = c.firstTri; t < c.firstTri + c.triCount && out.dynTris.size() < maxTris * 9; ++t)
				for (const auto &v : dyn->mesh.tris[t].v)
					out.dynTris.insert(out.dynTris.end(), { v.x, v.y, v.z });
		}
	}
	out.builtMs = NowMs();
	out.buildMs = out.builtMs - t0;
}

void PushFloats(ILua *L, const float *v, std::size_t n, const char *field)
{
	L->CreateTable();
	for (std::size_t i = 0; i < n; ++i)
	{
		L->PushNumber(static_cast<double>(i + 1));
		L->PushNumber(v[i]);
		L->SetTable(-3);
	}
	L->SetField(-2, field);
}

LUA_FUNCTION_STATIC(ColDebug)
{
	Poll();
	if (!g_view)
		return 0;
	const double x = ArgNum(LUA, 1, NAN), y = ArgNum(LUA, 2, NAN), z = ArgNum(LUA, 3, NAN);
	if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) || std::fabs(x) > 3e7 || std::fabs(y) > 3e7 || std::fabs(z) > 3e7)
		return 0;
	const int radius = static_cast<int>(std::fmin(std::fmax(ArgNum(LUA, 4, 1), 0), 3));
	const double maxTris = std::fmin(std::fmax(ArgNum(LUA, 5, 4000), 0), 50000);
	const bool wantBlocks = LUA->IsType(6, Type::Bool) && LUA->GetBool(6);
	const int S = static_cast<int>(P::kColRegionSize);
	const int cx = static_cast<int>(std::floor(x / S)), cy = static_cast<int>(std::floor(y / S)), cz = static_cast<int>(std::floor(z / S));
	const std::array<int, 6> asked = { cx, cy, cz, radius, static_cast<int>(maxTris), wantBlocks ? 1 : 0 };
	std::shared_ptr<DebugSlot> slot = g_debug;
	std::shared_ptr<const DebugBuild> done;
	bool post = false;
	{
		std::lock_guard<std::mutex> l(slot->mu);
		done = slot->done;
		if (!slot->busy && (!done || asked != g_debugAsked || NowMs() - done->builtMs > 1000.0))
		{
			slot->busy = true;
			post = true;
		}
	}
	if (post)
	{
		g_debugAsked = asked;
		std::shared_ptr<const MapView> view = g_view;
		std::shared_ptr<const DynSnapshot> dyn = Stream().Dynamic();
		const DigStore *dig = &g_dig;
		const std::uint32_t worldId = g_worldId, epoch = Stream().Epoch();
		g_worker.Post([slot, view, dyn, dig, worldId, epoch, cx, cy, cz, radius, maxTris, wantBlocks] {
			auto b = std::make_shared<DebugBuild>();
			try
			{
				BuildDebug(*view, dyn.get(), *dig, worldId, epoch, cx, cy, cz, radius, maxTris, wantBlocks, *b);
			}
			catch (...)
			{
			}
			std::lock_guard<std::mutex> l(slot->mu);
			b->id = ++slot->ids;
			slot->done = std::move(b);
			slot->busy = false;
		});
	}
	if (!done)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "id", done->id);
	SetNum(LUA, "ageMs", NowMs() - done->builtMs);
	SetNum(LUA, "buildMs", done->buildMs);
	if (ArgNum(LUA, 7, -1) == static_cast<double>(done->id))
		return 1;  // the caller has this build already: no arrays
	LUA->CreateTable();
	for (std::size_t i = 0; i < done->tris.size(); ++i)
		for (int k = 0; k < 9; ++k)
		{
			LUA->PushNumber(static_cast<double>(i * 9 + k + 1));
			LUA->PushNumber(done->tris[i].v[k]);
			LUA->SetTable(-3);
		}
	LUA->SetField(-2, "tris");
	LUA->CreateTable();
	for (std::size_t i = 0; i < done->tris.size(); ++i)
	{
		LUA->PushNumber(static_cast<double>(i + 1));
		LUA->PushNumber(done->tris[i].flags);
		LUA->SetTable(-3);
	}
	LUA->SetField(-2, "flags");
	PushFloats(LUA, done->blocks.data(), done->blocks.size(), "blocks");
	PushFloats(LUA, done->dynTris.data(), done->dynTris.size(), "dynTris");
	return 1;
}

// ---- dynamic entities (P2c) --------------------------------------------------------------------
// ColDynKnows(model) -> bool: the dynamic layer has decided this model's source ("*N" always);
// otherwise read its .phy and call ColDynModel.
LUA_FUNCTION_STATIC(ColDynKnows)
{
	Poll();
	LUA->PushBool(g_dyn.Known(ArgStr(LUA, 1)));
	return 1;
}

// ColDynModel(model, phyBytes | nil) -> source name ("phy" | "obb"), err
LUA_FUNCTION_STATIC(ColDynModel)
{
	if (!LUA->IsType(1, Type::String))
		return 0;
	std::string err;
	DynSource src;
	if (LUA->IsType(2, Type::String))
	{
		unsigned int len = 0;
		const char *p = LUA->GetString(2, &len);
		src = g_dyn.AddPhy(ArgStr(LUA, 1), reinterpret_cast<const std::uint8_t *>(p), len, err);
	}
	else
		src = g_dyn.AddPhy(ArgStr(LUA, 1), nullptr, 0, err);
	LUA->PushString(DynSourceName(src));
	LUA->PushString(err.c_str());
	return 2;
}

// ColDynMesh(key, { {x, y, z, x, y, z, ...}, ... }) -> ok, err: GetMeshConvexes, flattened (3
// vertices per triangle, one list per convex). Server only in practice (custom physics).
LUA_FUNCTION_STATIC(ColDynMesh)
{
	if (!LUA->IsType(1, Type::String) || !LUA->IsType(2, Type::Table))
		return PushFail(LUA, "ColDynMesh(key, convexes)");
	std::vector<std::vector<mcol::Vec3>> convexes;
	for (int i = 1; i <= 4096; ++i)
	{
		LUA->PushNumber(i);
		LUA->GetTable(2);
		if (!LUA->IsType(-1, Type::Table))
		{
			LUA->Pop();
			break;
		}
		const int t = LUA->Top();
		std::vector<mcol::Vec3> list;
		for (int j = 1; j <= 3 * 65536; j += 3)
		{
			double v[3];
			bool ok = true;
			for (int k = 0; k < 3; ++k)
			{
				LUA->PushNumber(j + k);
				LUA->GetTable(t);
				ok = ok && LUA->IsType(-1, Type::Number);
				v[k] = ok ? LUA->GetNumber(-1) : 0;
				LUA->Pop();
			}
			if (!ok)
				break;
			list.push_back(mcol::Vec3{ static_cast<float>(v[0]), static_cast<float>(v[1]), static_cast<float>(v[2]) });
		}
		LUA->Pop();
		convexes.push_back(std::move(list));
	}
	std::string err;
	const bool ok = g_dyn.AddMesh(ArgStr(LUA, 1), convexes, err);
	LUA->PushBool(ok);
	LUA->PushString(err.c_str());
	return 2;
}

// ColDynUpdate({ {ent, model, x, y, z, p, yaw, r, minX, minY, minZ, maxX, maxY, maxZ, physics, asleep}, ... })
//   -> regions marked: every solid entity near the MC players, a few times a second (entities
//   not listed are gone). Source units and degrees.
LUA_FUNCTION_STATIC(ColDynUpdate)
{
	Poll();
	if (!LUA->IsType(1, Type::Table))
	{
		LUA->PushNumber(0);
		return 1;
	}
	std::vector<DynSample> samples;
	for (int i = 1; i <= 8192; ++i)
	{
		LUA->PushNumber(i);
		LUA->GetTable(1);
		if (!LUA->IsType(-1, Type::Table))
		{
			LUA->Pop();
			break;
		}
		const int t = LUA->Top();
		DynSample s;
		s.ent = static_cast<std::int32_t>(FieldInt(LUA, t, "ent", -1, 1 << 24, -1));
		LUA->GetField(t, "model");
		s.model = ArgStr(LUA, -1);
		LUA->Pop();
		auto num = [&](const char *k) { return static_cast<float>(FieldNum(LUA, t, k, NAN)); };
		s.pos = mcol::Vec3{ num("x"), num("y"), num("z") };
		s.ang = mcol::Vec3{ num("p"), num("yaw"), num("r") };
		s.mins = mcol::Vec3{ num("minX"), num("minY"), num("minZ") };
		s.maxs = mcol::Vec3{ num("maxX"), num("maxY"), num("maxZ") };
		s.physics = FieldBool(LUA, t, "physics");
		s.asleep = FieldBool(LUA, t, "asleep");
		LUA->Pop();
		if (s.ent >= 0 && !s.model.empty() && std::isfinite(s.pos.x) && std::isfinite(s.pos.y) && std::isfinite(s.pos.z) && std::isfinite(s.ang.x) &&
			std::isfinite(s.ang.y) && std::isfinite(s.ang.z))
			samples.push_back(std::move(s));
	}
	++g_dynCalls;
	std::vector<std::array<int, 3>> regions;
	const double now = NowMs();
	if (g_dyn.Update(samples, now, g_frame, regions))
		Stream().SetDynamic(g_dyn.Snapshot());
	if (!regions.empty())
		Stream().RegionsChanged(regions, now);
	LUA->PushNumber(static_cast<double>(regions.size()));
	return 1;
}

// ColDynInfo() -> { stats..., entities = { {ent, model, source, placed, fast, ageMs, placements, tris, x, y, z, surfaceprop}, ... } }
LUA_FUNCTION_STATIC(ColDynInfo)
{
	const DynStats &d = g_dyn.Stats();
	const StreamStats &st = Stream().Stats();
	const double now = NowMs();
	LUA->CreateTable();
	SetNum(LUA, "calls", static_cast<double>(g_dynCalls));
	SetNum(LUA, "scans", static_cast<double>(d.scans));
	SetNum(LUA, "entityCount", d.entities);
	SetNum(LUA, "placed", d.placed);
	SetNum(LUA, "fast", d.fast);
	SetNum(LUA, "models", d.models);
	SetNum(LUA, "tris", d.tris);
	SetNum(LUA, "convexes", d.convexes);
	SetNum(LUA, "placements", static_cast<double>(d.placements));
	SetNum(LUA, "removals", static_cast<double>(d.removals));
	SetNum(LUA, "fastSkips", static_cast<double>(d.fastSkips));
	SetNum(LUA, "rateLimited", static_cast<double>(d.rateLimited));
	SetNum(LUA, "placeFailures", static_cast<double>(d.placeFailures));
	SetNum(LUA, "regionsMarked", static_cast<double>(d.regionsMarked));
	SetNum(LUA, "tooBigToMark", static_cast<double>(d.tooBigToMark));
	SetNum(LUA, "snapshots", static_cast<double>(d.snapshots));
	SetNum(LUA, "lastUpdateMs", d.lastUpdateMs);
	SetNum(LUA, "maxUpdateMs", d.maxUpdateMs);
	SetNum(LUA, "lastSnapshotMs", d.lastSnapshotMs);
	SetNum(LUA, "dynMarked", static_cast<double>(st.dynMarked));
	SetNum(LUA, "dynSent", static_cast<double>(st.dynSent));
	SetNum(LUA, "latencyAvgMs", st.dynSent ? st.dynLatencySumMs / static_cast<double>(st.dynSent) : 0.0);
	SetNum(LUA, "latencyMaxMs", st.dynLatencyMaxMs);
	SetNum(LUA, "latencyLastMs", st.dynLatencyLastMs);
	SetNum(LUA, "jobErrors", static_cast<double>(st.jobErrors));
	SetNum(LUA, "workerErrors", static_cast<double>(g_worker.Errors()));
	LUA->CreateTable();
	int i = 0;
	for (const auto &e : g_dyn.Info())
	{
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "ent", e.ent);
		SetStr(LUA, "model", e.model.c_str());
		SetStr(LUA, "source", DynSourceName(e.source));
		SetBool(LUA, "placed", e.placed);
		SetBool(LUA, "fast", e.fast);
		SetNum(LUA, "ageMs", e.placedMs > 0 ? now - e.placedMs : -1);
		SetNum(LUA, "placements", e.placements);
		SetNum(LUA, "tris", e.tris);
		SetNum(LUA, "x", e.pos.x);
		SetNum(LUA, "y", e.pos.y);
		SetNum(LUA, "z", e.pos.z);
		SetStr(LUA, "surfaceprop", e.surfaceprop.c_str());
		LUA->SetTable(-3);
		if (i >= 256)
			break;
	}
	LUA->SetField(-2, "entities");
	return 1;
}

// ColWater([slot]) -> { slot, originX, originZ, columns, surface = { 256 MC y or false } } | nil: the
// grid last written for slot (default: the slot written last).
LUA_FUNCTION_STATIC(ColWater)
{
	const int slot = LUA->IsType(1, Type::Number) ? static_cast<int>(LUA->GetNumber(1)) : g_waterLast;
	if (slot < 0 || slot >= static_cast<int>(P::kMaxPlayers) || Water(slot).writes == 0)
		return 0;
	const WaterState &w = Water(slot);
	LUA->CreateTable();
	SetNum(LUA, "slot", slot);
	SetNum(LUA, "originX", w.originX);
	SetNum(LUA, "originZ", w.originZ);
	SetNum(LUA, "columns", w.columns);
	LUA->CreateTable();
	for (int i = 0; i < static_cast<int>(P::kWaterGridSize * P::kWaterGridSize); ++i)
	{
		LUA->PushNumber(i + 1);
		if (w.surface[i] < -1e20f)
			LUA->PushBool(false);
		else
			LUA->PushNumber(w.surface[i]);
		LUA->SetTable(-3);
	}
	LUA->SetField(-2, "surface");
	return 1;
}

// ColWaterClear(slot) -> ok: that slot's grid written with no water (its player left).
LUA_FUNCTION_STATIC(ColWaterClear)
{
	LUA->PushBool(LUA->IsType(1, Type::Number) && ClearWater(static_cast<int>(LUA->GetNumber(1))));
	return 1;
}

// DigApply(worldId, sx, sy, sz, bits512 | nil): a dug-cell section as Minecraft reports it (tests,
// and a GMod server -> client relay).
LUA_FUNCTION_STATIC(DigApply)
{
	P::RenDug h{};
	h.worldId = static_cast<std::uint32_t>(std::fmin(std::fmax(ArgNum(LUA, 1, 0), 0), 4294967295.0));
	h.sx = static_cast<std::int32_t>(std::fmin(std::fmax(ArgNum(LUA, 2, 0), -1e6), 1e6));
	h.sy = static_cast<std::int32_t>(std::fmin(std::fmax(ArgNum(LUA, 3, 0), -1e6), 1e6));
	h.sz = static_cast<std::int32_t>(std::fmin(std::fmax(ArgNum(LUA, 4, 0), -1e6), 1e6));
	const std::uint8_t *bits = nullptr;
	if (LUA->IsType(5, Type::String))
	{
		unsigned int len = 0;
		const char *p = LUA->GetString(5, &len);
		if (len == P::kBlockBitsBytes)
			bits = reinterpret_cast<const std::uint8_t *>(p);
	}
	h.count = bits ? 1 : 0;
	CollisionDug(h, bits);
	return 0;
}

// ColDugCells(worldId, x0, y0, z0, x1, y1, z1) -> { x, y, z, x, y, z, ... }: this realm's dug cells
// inside the inclusive MC block box (at most 64 blocks along each axis). The server uses it for
// its "inside solid" check (a body in a dug hole is inside GMod's brushes but free for Minecraft).
LUA_FUNCTION_STATIC(ColDugCells)
{
	return PushDugCells(LUA);
}

// ColMapSolid(worldId, x0, y0, z0, x1, y1, z1[, margin]) -> bool | nil: does the map's geometry (what
// Minecraft collides with: brushes, clips, static props, displacements) reach into the SOURCE-unit
// box, outside the cells Minecraft dug? Each undug MC cell's part of the box (shrunk by margin
// units, default 2) is tested on its own. nil while no map is indexed. D6: the server's "inside
// solid" check counts Source's solid only where this is true, so Minecraft terrain under a map (the
// BSP's void, solid to Source) is free. A few index lookups: cheap enough for every report.
LUA_FUNCTION_STATIC(ColMapSolid)
{
	Poll();
	std::shared_ptr<const MapView> view = g_view;
	if (!view)
		return 0;
	const auto world = static_cast<std::uint32_t>(std::fmin(std::fmax(ArgNum(LUA, 1, 0), 0), 4294967295.0));
	float lo[3], hi[3];
	for (int a = 0; a < 3; ++a)
	{
		const double p = ArgNum(LUA, 2 + a, NAN), q = ArgNum(LUA, 5 + a, NAN);
		if (!std::isfinite(p) || !std::isfinite(q) || std::fabs(p) > 1e6 || std::fabs(q) > 1e6)
			return 0;
		lo[a] = static_cast<float>(std::fmin(p, q));
		hi[a] = static_cast<float>(std::fmax(p, q));
	}
	const float margin = static_cast<float>(std::fmin(std::fmax(ArgNum(LUA, 8, 2), 0), 20));
	const mcol::McFrame &f = view->frame;
	// MC cells of the box: x = sx / 40 + ox, y = (sz + oy) / 40, z = -sy / 40 + oz.
	int c0[3] = { static_cast<int>(std::floor(lo[0] / 40.0 + f.originX)), static_cast<int>(std::floor((lo[2] + f.originYUnits) / 40.0)),
		static_cast<int>(std::floor(-hi[1] / 40.0 + f.originZ)) };
	int c1[3] = { static_cast<int>(std::floor(hi[0] / 40.0 + f.originX)), static_cast<int>(std::floor((hi[2] + f.originYUnits) / 40.0)),
		static_cast<int>(std::floor(-lo[1] / 40.0 + f.originZ)) };
	for (int a = 0; a < 3; ++a)
		c1[a] = std::min(c1[a], c0[a] + 7);
	std::vector<std::array<int, 3>> dug;
	g_dig.Collect(world, c0, c1, dug);
	for (int x = c0[0]; x <= c1[0]; ++x)
		for (int y = c0[1]; y <= c1[1]; ++y)
			for (int z = c0[2]; z <= c1[2]; ++z)
			{
				if (std::find(dug.begin(), dug.end(), std::array<int, 3>{ x, y, z }) != dug.end())
					continue;
				// The cell in Source units (MC z + 1 is the low Source y edge), clipped to the box.
				const float plo[3] = { std::max(lo[0], float((x - f.originX) * 40)) + margin, std::max(lo[1], float(-(z + 1 - f.originZ) * 40)) + margin,
					std::max(lo[2], float(y * 40 - f.originYUnits)) + margin };
				const float phi[3] = { std::min(hi[0], float((x + 1 - f.originX) * 40)) - margin, std::min(hi[1], float(-(z - f.originZ) * 40)) - margin,
					std::min(hi[2], float((y + 1) * 40 - f.originYUnits)) - margin };
				if (plo[0] < phi[0] && plo[1] < phi[1] && plo[2] < phi[2] && mcol::SolidInBox(view->index, plo, phi))
				{
					LUA->PushBool(true);
					return 1;
				}
			}
	LUA->PushBool(false);
	return 1;
}
}  // namespace

int PushDugCells(ILua *LUA)
{
	const auto world = static_cast<std::uint32_t>(std::fmin(std::fmax(ArgNum(LUA, 1, 0), 0), 4294967295.0));
	auto c = [&](int i) { return static_cast<int>(std::fmin(std::fmax(std::floor(ArgNum(LUA, i)), -3e7), 3e7)); };
	int lo[3] = { c(2), c(3), c(4) }, hi[3] = { c(5), c(6), c(7) };
	for (int a = 0; a < 3; ++a)
	{
		if (lo[a] > hi[a])
			std::swap(lo[a], hi[a]);
		hi[a] = std::min(hi[a], lo[a] + 63);
	}
	std::vector<std::array<int, 3>> cells;
	g_dig.Collect(world, lo, hi, cells);
	LUA->CreateTable();
	int i = 0;
	for (const auto &p : cells)
		for (int a = 0; a < 3; ++a)
		{
			LUA->PushNumber(++i);
			LUA->PushNumber(p[a]);
			LUA->SetTable(-3);
		}
	return 1;
}

void CollisionDug(const P::RenDug &hdr, const std::uint8_t *bits512)
{
	std::vector<std::array<int, 3>> changed;
	g_dig.Apply(hdr.worldId, hdr.sx, hdr.sy, hdr.sz, hdr.count ? bits512 : nullptr, changed);
	++g_dugMessages;
	g_dugCells += changed.size();
	if (!changed.empty() && hdr.worldId == g_worldId)
	{
		Stream().DigChanged(changed);
		if (g_dugHook != nullptr)
			g_dugHook(changed);
	}
}

void SetCollisionDugHook(void (*hook)(const std::vector<std::array<int, 3>> &cells))
{
	g_dugHook = hook;
}

std::shared_ptr<const MapView> CollisionView()
{
	Poll();
	return g_view;
}

std::uint32_t CollisionWorldId()
{
	return g_worldId;
}

const DigStore &CollisionDigs()
{
	return g_dig;
}

void CollisionLinkClosed()
{
	if (g_stream != nullptr)
	{
		g_stream->Shutdown();
		g_stream->Reset(g_stream->Epoch());
	}
	WaterForgetAll();
}

void CloseCollision()
{
	g_worker.Stop();
	delete g_stream;
	g_stream = nullptr;
	delete g_loader;
	g_loader = nullptr;
	g_slots = std::make_shared<Slots>();
	g_map.reset();
	g_view.reset();
	g_state = MapState::kNone;
	g_indexing = false;
	g_epochSet = false;
	g_dig.Clear();
	g_dyn.Clear();
	g_dynCalls = 0;
	g_debug = std::make_shared<DebugSlot>();  // a build dropped by Stop() would leave it busy
	g_frame = mcol::McFrame{};
	delete[] g_waters;
	g_waters = nullptr;
	g_waterLast = -1;
	g_waterWrites = g_waterClears = 0;
	g_dugMessages = g_dugCells = 0;
}

void RegisterCollision(ILua *L)
{
	struct Fn
	{
		const char *name;
		CFunc fn;
	};
	static const Fn fns[] = {
		{ "MapBegin", MapBegin },
		{ "MapLump", MapLump },
		{ "MapPrepare", MapPrepare },
		{ "MapPhy", MapPhy },
		{ "MapBBox", MapBBox },
		{ "MapSurfaceProps", MapSurfaceProps },
		{ "MapVmt", MapVmt },
		{ "MapTexProps", MapTexProps },
		{ "MapDecode", MapDecode },
		{ "MapStatus", MapStatus },
		{ "MapAnchor", MapAnchor },
		{ "ColUpdate", ColUpdate },
		{ "ColReset", ColReset },
		{ "ColResend", ColResend },
		{ "ColStats", ColStats },
		{ "ColRegions", ColRegions },
		{ "ColDebug", ColDebug },
		{ "ColWater", ColWater },
		{ "ColWaterClear", ColWaterClear },
		{ "DigApply", DigApply },
		{ "ColDugCells", ColDugCells },
		{ "ColMapSolid", ColMapSolid },
		{ "ColDynKnows", ColDynKnows },
		{ "ColDynModel", ColDynModel },
		{ "ColDynMesh", ColDynMesh },
		{ "ColDynUpdate", ColDynUpdate },
		{ "ColDynInfo", ColDynInfo },
	};
	for (const Fn &f : fns)
	{
		L->PushCFunction(f.fn);
		L->SetField(-2, f.name);
	}
}
}  // namespace gc
