// GmodCraft collision streaming (P2, docs/DESIGN.md section 6): the decoded map (mapcol) streamed to
// Minecraft as kColTris + kColRegion around the MC players, SkyCraft's region loop
// (reference/skse/src/Collision.cpp Update) ported. Both realms, one instance each. No Lua and no
// engine code in here, so module/test can drive it host-side.
//
//   Worker      one background thread per module: map decode, region index builds, region jobs
//               (gather + kColTris + kColRegion payloads). Never touches the shared mapping.
//   MapLoader   collects what Lua reads from GMod's filesystem (lumps, .phy, surfaceproperties,
//               VMT surfaceprops), then decodes it on the worker into an immutable MapData.
//   DigStore    Minecraft's dug cells per worldId (kBlkDug / kRenDug), read by region jobs.
//   Streamer    one per link: picks regions (5 around, 3 below, 2 above each player, nearest first;
//               the ones next to a player again every second), posts jobs, writes finished payloads
//               to the collision ring under a per-frame budget (both messages of a region or
//               neither), epochs and kColClear.
//   FillWater   the WaterGrid around a position from the map's water volumes.
#pragma once

#include "coldyn.hpp"
#include "link.hpp"
#include "mapcol.hpp"

#include <array>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace gc
{
namespace mcol = gmodcraft::mapcol;

// ---- worker ------------------------------------------------------------------------------------
// Runs posted tasks in order on one thread, started on the first Post. Stop() drops what is still
// queued and joins: GMod dlcloses the module after gmod13_close, so no thread may outlive it.
class Worker
{
public:
	~Worker() { Stop(); }
	void Post(std::function<void()> fn);
	void Stop();
	std::size_t Pending();
	bool Stopping() const { return stop_.load(); }
	std::uint64_t Errors() const { return errors_.load(); }  // tasks that threw

private:
	std::atomic<std::uint64_t> errors_{ 0 };
	void Loop();
	std::mutex mu_;
	std::condition_variable cv_;
	std::deque<std::function<void()>> q_;
	std::thread th_;
	std::atomic<bool> stop_{ false };
	bool busy_ = false;
};

// ---- map ---------------------------------------------------------------------------------------
struct MapData
{
	std::string name;
	mcol::MapCollision map;
	mcol::PropStats props;
	int sprpVersion = 0;
	std::size_t propCount = 0;
	std::string warnings;  // non-fatal problems, one per line
	double decodeMs = 0, propsMs = 0, totalMs = 0;
	std::uint32_t trisByKind[8] = {}, convexesByKind[8] = {};
	std::vector<std::pair<std::string, std::uint32_t>> materialTris;  // name -> triangles, most first
	std::vector<std::pair<int, std::uint32_t>> digTris;               // DigMaterial (-1: not diggable) -> triangles
	std::size_t inputBytes = 0;
};

// MapData's region index for one slot origin. Immutable once built; holds the data alive.
struct MapView
{
	std::shared_ptr<const MapData> data;
	mcol::RegionIndex index;
	mcol::McFrame frame;
	double indexMs = 0;
	std::uint32_t id = 0;
};

class MapLoader
{
public:
	struct Range
	{
		int lump;
		std::int64_t offset, length;
	};
	// A 1036-byte BSP header: which lumps to read (the ones mapcol decodes).
	bool Begin(const std::string &map, const std::uint8_t *header, std::size_t n, std::string &err);
	const std::vector<Range> &Needed() const { return needed_; }
	bool AddLump(int lump, const std::uint8_t *p, std::size_t n, std::string &err);
	// After the lumps: what else Lua must read (static prop .phy / .mdl, displacement VMTs).
	bool Prepare(std::vector<std::string> &phyModels, std::vector<std::string> &bboxModels, std::vector<std::string> &textures,
		std::string &err);
	void AddPhy(const std::string &model, const std::uint8_t *p, std::size_t n);
	void AddBBox(const std::string &model, const float mins[3], const float maxs[3]);
	void AddSurfaceProps(const std::string &text);
	// A VMT's text: its $surfaceprop pair is recorded for `texture`; returns the include to follow
	// ("" if none). False: unparsable (texture falls back to the name heuristic).
	bool AddVmt(const std::string &texture, const std::string &text, std::string &include);
	void AddTexProps(const std::string &texture, const std::string &prop, const std::string &prop2);
	// Decodes everything collected (any thread). The loader is left empty.
	std::shared_ptr<MapData> Decode(std::string &err);
	const std::string &Name() const { return name_; }
	bool Active() const { return active_; }
	std::size_t Bytes() const;
	std::size_t TexturesKnown() const { return tex_.size(); }
	std::size_t TexturesHeuristic() const { return heuristic_; }

private:
	bool active_ = false;
	std::string name_;
	mcol::BspLumps lumps_;
	std::vector<std::uint8_t> data_[mcol::kLumpCount];
	std::vector<Range> needed_;
	std::vector<mcol::StaticPropEntry> props_;
	int sprpVersion_ = 0;
	std::vector<std::string> surfaceProps_;
	mcol::TexSurfaceProps tex_;
	std::size_t heuristic_ = 0;
	std::unordered_map<std::string, std::vector<std::uint8_t>> phy_;
	mcol::PropModels models_;
};

// A texture name -> surface property guess, for materials whose VMT can't be read.
std::string SurfacePropFromName(const std::string &texture);

// ---- dig state ---------------------------------------------------------------------------------
class DigStore
{
public:
	// One section's dug bits (bit x + 16z + 256y; nullptr = none) for worldId. Appends the cells
	// that changed (MC block coords) to `changed`.
	void Apply(std::uint32_t worldId, int sx, int sy, int sz, const std::uint8_t *bits512, std::vector<std::array<int, 3>> &changed);
	// Dug cells of worldId inside the inclusive block box [lo, hi].
	void Collect(std::uint32_t worldId, const int lo[3], const int hi[3], std::vector<std::array<int, 3>> &out) const;
	std::size_t Cells(std::uint32_t worldId) const;
	std::size_t Sections() const;
	void Clear();
	// Every section of worldId with dug cells (P5b: the client's hole mask).
	struct SectionBits
	{
		int sx, sy, sz;
		std::array<std::uint64_t, 64> bits;
	};
	void Snapshot(std::uint32_t worldId, std::vector<SectionBits> &out) const;
	// Bumped by every Apply that changed a cell and by Clear.
	std::uint64_t Generation() const { return gen_.load(); }

private:
	static std::uint64_t Key(int sx, int sy, int sz);
	std::atomic<std::uint64_t> gen_{ 0 };
	mutable std::mutex mu_;
	std::unordered_map<std::uint32_t, std::unordered_map<std::uint64_t, std::array<std::uint64_t, 64>>> worlds_;
};

// ---- streaming ---------------------------------------------------------------------------------
struct RegionInfo
{
	std::int32_t rx, ry, rz;
	std::uint32_t tris = 0, ghosts = 0, blocks = 0, bytes = 0;
	double sentMs = 0;
	float gatherMs = 0, trisMs = 0, voxelMs = 0;
};

struct StreamStats
{
	std::uint64_t regionsQueued = 0, regionsSent = 0, messages = 0, bytes = 0, clears = 0;
	std::uint64_t stale = 0, ringWaits = 0, refreshes = 0, urgent = 0;
	std::uint64_t digQueued = 0;     // regions around dug cells beyond kRadius queued (P5c)
	std::uint64_t digInflightSkips = 0;  // dig regions passed over while their job was in flight (sent again later)
	std::uint64_t jobErrors = 0;     // region jobs that threw (sent again later)
	std::uint64_t dynMarked = 0;     // regions marked by the dynamic layer
	// Dynamic change -> region written to the ring (both messages), per marked region whose job
	// was queued after the change: count, sum, max (ms).
	std::uint64_t dynSent = 0;
	double dynLatencySumMs = 0;
	float dynLatencyMaxMs = 0, dynLatencyLastMs = 0;
	double gatherMs = 0, trisMs = 0, voxelMs = 0;  // totals over regionsSent
	float maxRegionMs = 0, lastWriteMs = 0, maxWriteMs = 0;
	std::uint32_t inflight = 0, ready = 0, known = 0;
};

class Streamer
{
public:
	Streamer(Worker &worker, DigStore &dig) : worker_(worker), dig_(dig) {}
	~Streamer();

	struct Pos
	{
		float x, y, z;  // MC feet
	};
	// A new epoch: kColClear before anything else, then everything again.
	void Reset(std::uint32_t epoch);
	// Send everything again (no clear).
	void Resend();
	void SetView(std::shared_ptr<const MapView> view, std::uint32_t worldId);
	const std::shared_ptr<const MapView> &View() const { return view_; }
	// Once per frame / tick. Returns regions written to the ring this call.
	int Update(const std::vector<Pos> &players, ByteRingWriter &ring, double budgetMs);
	// Dug cells changed: the regions they touch go out again first.
	void DigChanged(const std::vector<std::array<int, 3>> &cells);
	// The dynamic layer (doors, props) region jobs merge in from now on (nullptr: none). Kept
	// across Reset / SetView: entities outlive epochs.
	void SetDynamic(std::shared_ptr<const DynSnapshot> dyn) { dyn_ = std::move(dyn); }
	const std::shared_ptr<const DynSnapshot> &Dynamic() const { return dyn_; }
	// Regions (region coordinates) whose dynamic collision changed at markMs: they go out again
	// first; the time until each is written is measured (StreamStats::dynLatency*).
	void RegionsChanged(const std::vector<std::array<int, 3>> &regions, double markMs);
	std::uint32_t Epoch() const { return epoch_; }
	const StreamStats &Stats() const { return stats_; }
	const std::unordered_map<std::uint64_t, RegionInfo> &Sent() const { return sent_; }
	void Shutdown();  // drop pending results (the link is going away)

	static constexpr int kRadius = 5, kBelow = 3, kAbove = 2;
	// P5c: regions around dug cells this far from a player (regions horizontally, blocks
	// vertically) go out too, once each, so Minecraft has the geometry it draws a hole's walls from.
	static constexpr int kDigRange = 16, kDigRangeY = 64;
	static constexpr double kDigScanMs = 2000.0, kDigScanMinMs = 250.0;
	static constexpr double kRefreshNearMs = 1000.0;
	static constexpr std::uint32_t kMaxInflight = 32;
	static std::uint64_t Key(int rx, int ry, int rz);

private:
	struct Result
	{
		RegionInfo info;
		std::uint32_t epoch = 0, viewId = 0;
		double queuedMs = 0;
		bool failed = false;  // the job threw: no payloads, the region goes out again later
		std::vector<std::uint8_t> tris, region;
	};
	struct Shared  // between the game thread and the worker's jobs (outlives a closed streamer)
	{
		std::mutex mu;
		std::deque<Result> done;
		std::atomic<std::uint32_t> epoch{ 0 };
	};
	void Queue(int rx, int ry, int rz, double now);
	bool WriteResult(const Result &r, ByteRingWriter &ring);

	Worker &worker_;
	DigStore &dig_;
	std::shared_ptr<Shared> shared_ = std::make_shared<Shared>();
	std::shared_ptr<const MapView> view_;
	std::shared_ptr<const DynSnapshot> dyn_;
	std::unordered_map<std::uint64_t, double> dynMarks_;  // region key -> when the dynamic layer changed it
	std::uint32_t worldId_ = 0;
	std::uint32_t epoch_ = 0;
	bool clearPending_ = false;
	std::unordered_map<std::uint64_t, double> harvested_;  // key -> queued at (ms)
	std::unordered_set<std::uint64_t> inflight_;
	std::deque<Result> ready_;  // finished, waiting for ring room
	std::vector<std::array<int, 3>> urgent_;
	std::unordered_set<std::uint64_t> digSent_;      // dig regions sent this epoch (DigChanged drops its keys)
	std::vector<std::array<int, 3>> digPending_;     // dig regions waiting for a free job slot
	std::vector<std::array<int, 3>> digDirty_;       // regions DigChanged touched, for the dig pass
	double digScanMs_ = -1e18;
	std::uint64_t digGen_ = ~std::uint64_t(0);
	std::vector<std::array<int, 3>> offsets_;
	std::unordered_map<std::uint64_t, RegionInfo> sent_;
	StreamStats stats_;
};

// Kept in sync by Streamer::Reset callers: kColClear payload writer.
bool SendColClear(ByteRingWriter &ring, std::uint32_t epoch);

// v18 kColWeaponIcon (client link): the icon of the weapon class `hash`, rgba = w * h * 4 bytes.
// Refuses (false, nothing written) hash 0, w or h outside 1..kWeaponIconMaxSide, a byte count
// that isn't w * h * 4, or a full ring.
bool SendWeaponIcon(ByteRingWriter &ring, std::uint32_t hash, std::uint32_t w, std::uint32_t h, const std::uint8_t *rgba, std::size_t bytes);

// Builds one region's payloads synchronously (debug overlays, tests): what a job sends. `dyn`
// (may be null) is merged in when its frame is the view's.
void BuildRegionNow(const MapView &view, const DynSnapshot *dyn, const DigStore &dig, std::uint32_t worldId, int rx, int ry, int rz,
	std::uint32_t epoch, std::vector<std::uint8_t> &tris, std::vector<std::uint8_t> &region, RegionInfo *info = nullptr);

// WaterGrid surface around MC feet position (x, y, z): origin = floor(x) - 8, floor(z) - 8; each
// column's surface in MC y, or kNoWater. Volumes whose bottom is more than 2 blocks above the
// feet don't count (a tunnel under a lake). Returns the number of water columns.
int FillWater(const MapView &view, float x, float y, float z, std::int32_t &originX, std::int32_t &originZ,
	float surface[P::kWaterGridSize * P::kWaterGridSize]);
}  // namespace gc
