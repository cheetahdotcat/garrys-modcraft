// GmodCraft dynamic collision (P2c, docs/DESIGN.md section 6): brush entities (doors, func_brush,
// func_movelinear, func_breakable) and props (prop_physics, prop_door_rotating, prop_dynamic,
// sandbox props, VPHYSICS SENTs) that Lua lists near the MC players a few times a second.
//
//   per model   collision in model space, cached: the .phy's solid 0 (Lua reads it), brush model
//               N of the decoded map for "*N", GetMeshConvexes lists (server, custom physics), else
//               the entity's OBB
//   per entity  placed in world space when it moved more than kMoveUnits or turned more than
//               kTurnDeg since it was last placed, at most every kMinIntervalMs; physics props
//               moving faster than kFastUnitsPerSec (or turning faster than kFastDegPerSec) and
//               not asleep are taken out until they settle (their old spot would block MC)
//   snapshot    every placed entity in one immutable mesh + region index (MC frame), handed to
//               the Streamer; region jobs gather it on top of the static map (mapcol GatherAppend)
//
// The regions an entity left or entered are reported back so the Streamer re-sends them first.
// No Lua and no engine code in here (module/test drives it host-side). Game thread only.
#pragma once

#include "mapcol.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace gc
{
namespace mcol = gmodcraft::mapcol;
struct MapData;

// What region jobs read: immutable once published. Never moved after Build (the index points at
// the mesh), so it only lives behind a shared_ptr.
struct DynSnapshot
{
	mcol::Mesh mesh;  // world space (Source units), every placed entity
	mcol::RegionIndex index;
	mcol::McFrame frame;
	std::uint32_t id = 0;
	std::uint32_t entities = 0;
	double builtMs = 0;  // NowMs() when built
	DynSnapshot() = default;
	DynSnapshot(const DynSnapshot &) = delete;
	DynSnapshot &operator=(const DynSnapshot &) = delete;
};

enum class DynSource : std::uint8_t
{
	kNone,
	kPhy,    // the model's .phy, solid 0
	kBrush,  // "*N": PHYSCOLLIDE model N of the map
	kMesh,   // GetMeshConvexes (server, custom physics)
	kObb,    // the entity's OBB (no collision model: no .phy, unknown *N, or a bad one)
};
const char *DynSourceName(DynSource s);

struct DynSample
{
	std::int32_t ent = 0;     // entity index (the key)
	std::string model;        // "models/x.mdl", "*N", or a ColDynMesh key
	mcol::Vec3 pos, ang;      // Source units / degrees (pitch, yaw, roll)
	mcol::Vec3 mins, maxs;    // OBB (entity space)
	bool physics = false;     // MOVETYPE_VPHYSICS: the speed rule applies
	bool asleep = false;      // server: the physics object sleeps (counts as settled)
};

struct DynStats
{
	std::uint64_t scans = 0, placements = 0, removals = 0, fastSkips = 0, rateLimited = 0, placeFailures = 0;
	std::uint64_t regionsMarked = 0, snapshots = 0, tooBigToMark = 0;
	std::uint32_t entities = 0, placed = 0, fast = 0, models = 0, tris = 0, convexes = 0;
	float lastUpdateMs = 0, maxUpdateMs = 0, lastSnapshotMs = 0;
};

class DynamicSet
{
public:
	static constexpr float kMoveUnits = 40.0f / 16.0f;  // 1/16 block
	static constexpr float kTurnDeg = 2.0f;
	static constexpr double kMinIntervalMs = 250.0;
	static constexpr float kFastUnitsPerSec = 80.0f;     // 2 blocks/s
	static constexpr float kFastDegPerSec = 90.0f;
	static constexpr std::size_t kMaxMarkRegions = 4096;  // per entity and update; bigger: the 1 s refresh

	// A new map: everything goes (entities, model cache), brush models come from `map`.
	void SetMap(std::shared_ptr<const MapData> map);
	// Model sources. Known(): a source is decided (Lua need not read the .phy again).
	bool Known(const std::string &model) const;
	// A .phy's bytes (nullptr / 0 bytes: the model has none -> OBB). Returns the source used.
	DynSource AddPhy(const std::string &model, const std::uint8_t *p, std::size_t n, std::string &err);
	bool AddMesh(const std::string &key, const std::vector<std::vector<mcol::Vec3>> &convexes, std::string &err);

	// One scan: every listed entity (entities not listed are gone). Appends the regions (MC region
	// coordinates in `frame`) whose collision changed to `regions`. True if the snapshot changed.
	bool Update(const std::vector<DynSample> &samples, double nowMs, const mcol::McFrame &frame, std::vector<std::array<int, 3>> &regions);
	std::shared_ptr<const DynSnapshot> Snapshot() const { return snap_; }
	void Clear();
	const DynStats &Stats() const { return stats_; }

	struct EntInfo
	{
		std::int32_t ent;
		std::string model;
		DynSource source;
		bool placed, fast;
		double placedMs;  // NowMs() of the last placement (0: never)
		std::uint32_t placements, tris;
		mcol::Vec3 pos;
		std::string surfaceprop;
	};
	std::vector<EntInfo> Info() const;

private:
	struct Model
	{
		DynSource source = DynSource::kNone;
		std::shared_ptr<const mcol::Mesh> mesh;  // model space; null for kObb (per entity)
		std::string surfaceprop;
	};
	struct Ent
	{
		std::string model;
		DynSource source = DynSource::kNone;
		std::shared_ptr<const mcol::Mesh> local;
		mcol::Vec3 mins, maxs;
		std::string surfaceprop;
		bool placed = false, fast = false, seen = false;
		mcol::Vec3 placedPos, placedAng;
		double placedMs = 0;
		mcol::Vec3 lastPos, lastAng;
		double lastMs = 0;
		std::uint32_t placements = 0;
		mcol::Mesh world;  // placed (world space)
	};
	bool Resolve(Ent &e, const DynSample &s);
	void Mark(const mcol::Mesh &world, const mcol::McFrame &frame, std::vector<std::array<int, 3>> &regions);
	void Rebuild(const mcol::McFrame &frame, double nowMs);

	std::shared_ptr<const MapData> map_;
	std::unordered_map<std::string, Model> models_;
	std::unordered_map<std::int32_t, Ent> ents_;
	std::shared_ptr<const DynSnapshot> snap_;
	std::uint32_t snapIds_ = 0;
	DynStats stats_;
};
}  // namespace gc
