// The hull trace job (see hulljob.hpp). Built into both realms; only the server registers the Lua
// functions.
//
//   HullBuild(bspBytes | nil [, { ox, oy, oz, force, hills }]) -> true | false, why, need
//       Starts tracing the loaded map on the hull thread (a running trace is cancelled first and
//       left to finish on its own). bspBytes: the whole maps/<map>.bsp (Lua reads it; the module
//       can't see mounted content): its FNV-1a 64 is the map identity in the file name and header.
//       nil: the hash an earlier build of this map worked out; when there is none yet the call
//       returns false, why, "bsp" (read the file and call again). The slot offsets default
//       to the collision view's (the ones ColUpdate streams with), so the file's blocks are the
//       colstream's. hills (default true): fill the space under terrain (the file's header flag).
//       A cached file with the same hash, offsets and hills is reused unless force.
//   HullPrune(map, hills) -> removed
//       The hills setting changed (or the map is loading): cancels a running trace of the other
//       mode and removes the map's files of the other mode from /dev/shm, so Minecraft (a new hull
//       world takes the first file it finds and keeps it) never picks one up.
//   HullStatus() -> { state, progress (0..1), done, total, map, hash, path, cachePath, cached, error,
//       ms, regions, solidBlocks, fillBlocks, hillBlocks, hills, bytes, thinConvexes, ox, oy, oz }
//       state: "idle", "hashing", "tracing", "writing", "done", "error", "cancelled".
#include "hulljob.hpp"

#include "colstream.hpp"
#include "collision.hpp"
#include "link.hpp"
#include "os/os.hpp"
#include "privatedir.hpp"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <system_error>
#include <utility>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace gc
{
using namespace GarrysMod::Lua;

namespace
{
const char *const kCacheDir = "garrysmod/data/gmodcraft/hull";  // relative to the game's working directory

struct HullJob
{
	std::atomic<bool> cancel{ false };
	std::atomic<std::uint32_t> done{ 0 }, total{ 0 };
	std::mutex mu;  // the fields below
	std::string state = "hashing", error, path, cachePath, map, hash;
	bool cached = false;
	double startMs = 0, endMs = 0;
	std::uint32_t regions = 0, thin = 0;
	std::uint64_t solid = 0, fill = 0, hill = 0, bytes = 0;
	mcol::McFrame frame;
	bool hills = true;  // the space under terrain is filled (set before the thread starts)
	std::atomic<bool> finished{ false };  // the thread has returned (joinable without waiting)

	void Set(const char *s)
	{
		std::lock_guard<std::mutex> l(mu);
		state = s;
	}
	void Fail(const std::string &why)
	{
		std::lock_guard<std::mutex> l(mu);
		state = "error";
		error = why;
		endMs = NowMs();
	}
};

std::shared_ptr<HullJob> g_job;
std::thread g_thread;
// Cancelled jobs whose thread still runs: joined once finished (or at module close), never waited
// for on the game thread.
std::vector<std::pair<std::shared_ptr<HullJob>, std::thread>> g_retired;

// Held around every write into /dev/shm (with its cancel check) and around HullPrune's cancel +
// removal: a trace of the other mode can't write its file after the prune.
std::mutex g_shmMu;

// The map hash an earlier build worked out (hashing 30+ MB once per map is enough).
std::mutex g_hashMu;
std::string g_hashMap;
std::uint64_t g_hash = 0;

void Reap()
{
	for (auto it = g_retired.begin(); it != g_retired.end();)
	{
		if (it->first->finished.load())
		{
			if (it->second.joinable())
				it->second.join();
			it = g_retired.erase(it);
		}
		else
			++it;
	}
}

bool SafeName(const std::string &s)
{
	if (s.empty() || s.size() > 128 || s[0] == '.')
		return false;
	for (char c : s)
		if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'))
			return false;
	return true;
}

bool ReadAll(const std::string &path, std::vector<std::uint8_t> &out)
{
	FILE *f = std::fopen(path.c_str(), "rb");
	if (f == nullptr)
		return false;
	out.clear();
	std::uint8_t buf[65536];
	std::size_t n;
	while ((n = std::fread(buf, 1, sizeof buf, f)) > 0)
		out.insert(out.end(), buf, buf + n);
	const bool ok = !std::ferror(f);
	std::fclose(f);
	return ok;
}

// tmp + rename: readers never see a partial file.
bool WriteAtomic(const std::string &path, const std::vector<std::uint8_t> &data, std::string &why)
{
	const std::string tmp = path + ".tmp";
	FILE *f = std::fopen(tmp.c_str(), "wb");
	if (f == nullptr)
	{
		why = tmp + ": " + std::strerror(errno);
		return false;
	}
	const bool wrote = data.empty() || std::fwrite(data.data(), 1, data.size(), f) == data.size();
	const bool closed = std::fclose(f) == 0;
	if (!wrote || !closed || !os::RenameReplace(tmp, path))
	{
		why = path + ": write failed (" + std::strerror(errno) + ")";
		std::remove(tmp.c_str());
		return false;
	}
	return true;
}

// The persistent cache dir, created when the game's data dir is there ("" when not).
std::string CacheDir()
{
	if (!os::IsDir("garrysmod/data"))
		return "";
	if (!MkdirP(kCacheDir, 0755))
		return "";
	return kCacheDir;
}

// Removes `map`'s files of the other hills mode from dir (mcol::HullFileStale); the count.
int PruneShm(const std::string &dir, const std::string &map, bool hills)
{
	std::vector<std::string> names;
	if (!os::ListDir(dir, &names))
		return 0;
	int removed = 0;
	for (const std::string &name : names)
	{
		if (name.size() <= map.size() || name.compare(0, map.size(), map) != 0)
			continue;
		const std::string path = dir + "/" + name;
		std::uint8_t head[mcol::kHullHeaderBytes];
		FILE *f = std::fopen(path.c_str(), "rb");
		if (f == nullptr)
			continue;
		const std::size_t n = std::fread(head, 1, sizeof head, f);
		std::fclose(f);
		if (mcol::HullFileStale(name, map, mcol::Bytes{ head, n }, hills) && std::remove(path.c_str()) == 0)
			++removed;
	}
	return removed;
}

// Writes the job's file into /dev/shm unless it was cancelled meanwhile ("cancelled": false, why empty).
bool WriteShm(HullJob &job, const std::string &path, const std::vector<std::uint8_t> &bytes, std::string &why)
{
	std::lock_guard<std::mutex> l(g_shmMu);
	if (job.cancel.load())
		return false;
	return WriteAtomic(path, bytes, why);
}

void Run(std::shared_ptr<HullJob> job, std::shared_ptr<const MapView> view, std::vector<std::uint8_t> bsp, bool haveHash,
	std::uint64_t hash, bool force)
{
	// Below the game's threads: the trace is background work.
	os::LowerThreadPriority();
	const std::shared_ptr<const MapData> data = view->data;
	const mcol::McFrame frame = job->frame;
	const bool hills = job->hills;
	const std::uint32_t flags = hills ? mcol::kHullFlagHills : 0;

	auto cancelled = [&]() {
		if (!job->cancel.load())
			return false;
		job->Set("cancelled");
		return true;
	};
	if (!haveHash)
	{
		// In 4 MiB steps, so a cancel doesn't wait for the whole file.
		hash = mcol::kFnvBasis;
		constexpr std::size_t kStep = 4u << 20;
		for (std::size_t at = 0; at < bsp.size(); at += kStep)
		{
			if (cancelled())
				return;
			hash = mcol::Fnv1a64(mcol::Bytes{ bsp.data() + at, std::min(kStep, bsp.size() - at) }, hash);
		}
		bsp.clear();
		bsp.shrink_to_fit();
		std::lock_guard<std::mutex> l(g_hashMu);
		g_hashMap = data->name;
		g_hash = hash;
	}
	char hex[17];
	std::snprintf(hex, sizeof hex, "%016llx", static_cast<unsigned long long>(hash));
	const std::string fileName = data->name + "." + hex + ".bin";
	std::string why;
	const std::string shmDir = DiscoveryDir() + "/hull";
	if (!EnsurePrivateDir(DiscoveryDir(), &why) || !EnsurePrivateDir(shmDir, &why))
	{
		job->Fail(why);
		return;
	}
	const std::string shmPath = shmDir + "/" + fileName;
	{
		// a file of the other mode (same name) must not stay while this one is traced
		std::lock_guard<std::mutex> l(g_shmMu);
		PruneShm(shmDir, data->name, hills);
	}
	const std::string cacheDir = CacheDir();
	const std::string cachePath = cacheDir.empty() ? std::string() : cacheDir + "/" + fileName;
	{
		std::lock_guard<std::mutex> l(job->mu);
		job->hash = hex;
		job->path = shmPath;
		job->cachePath = cachePath;
	}

	// The cache: same map bytes, offsets and hills -> no trace.
	std::vector<std::uint8_t> bytes;
	if (!force && !cachePath.empty() && ReadAll(cachePath, bytes))
	{
		mcol::HullHeader h;
		std::vector<mcol::HullRegion> regions;
		std::string err;
		if (mcol::ParseHull(mcol::Bytes{ bytes.data(), bytes.size() }, h, regions, err) && h.mapHash == hash && h.frame == frame &&
			h.traceVersion == mcol::kHullTraceVersion && (h.flags & mcol::kHullFlagHills) == flags)
		{
			job->Set("writing");
			if (!WriteShm(*job, shmPath, bytes, why))
			{
				if (why.empty())
					job->Set("cancelled");
				else
					job->Fail(why);
				return;
			}
			std::lock_guard<std::mutex> l(job->mu);
			job->cached = true;
			job->regions = h.regionCount;
			job->solid = h.solidCount;
			job->bytes = bytes.size();
			job->state = "done";
			job->endMs = NowMs();
			return;
		}
	}

	job->Set("tracing");
	mcol::RegionIndex ownIndex;
	const mcol::RegionIndex *index = &view->index;
	if (view->frame != frame)
	{
		if (cancelled())
			return;
		ownIndex.Build(data->map.world, frame);
		index = &ownIndex;
	}
	if (cancelled())
		return;
	// The map's solid space per block column (one BSP walk per column; cancellable per row).
	mcol::HullFill fill;
	if (!data->map.tree.ok)
	{
		std::lock_guard<std::mutex> l(job->mu);
		job->error = "the map's BSP tree is missing: no solid fill";
	}
	mcol::BuildHullFill(data->map, *index, fill, hills, &job->cancel);
	if (cancelled())
		return;
	std::vector<std::array<int, 3>> todo;
	mcol::HullRegions(data->map, *index, todo, &fill);
	job->total.store(static_cast<std::uint32_t>(todo.size()));
	std::vector<mcol::HullRegion> regions;
	mcol::HullStats stats;
	for (const auto &r : todo)
	{
		if (job->cancel.load())
		{
			job->Set("cancelled");
			return;
		}
		mcol::HullRegion out;
		if (mcol::TraceHullRegion(data->map, *index, r[0], r[1], r[2], out, &stats, &fill))
			regions.push_back(out);
		job->done.fetch_add(1);
	}

	job->Set("writing");
	mcol::SerializeHull(regions, hash, frame, flags, bytes);
	if (!WriteShm(*job, shmPath, bytes, why))
	{
		if (why.empty())
			job->Set("cancelled");
		else
			job->Fail(why);
		return;
	}
	std::string cacheWhy;
	const bool cachedOk = cachePath.empty() || WriteAtomic(cachePath, bytes, cacheWhy);
	std::lock_guard<std::mutex> l(job->mu);
	job->regions = static_cast<std::uint32_t>(regions.size());
	job->solid = stats.solidBlocks;
	job->fill = stats.fillBlocks;
	job->hill = stats.hillBlocks;
	job->thin = stats.thinConvexes;
	job->bytes = bytes.size();
	job->state = "done";
	if (!cachedOk)
		job->error = "cache not written: " + cacheWhy;
	job->endMs = NowMs();
}

// Cancels the current job; its thread finishes on its own (Reap joins it).
void RetireCurrent()
{
	if (g_job)
		g_job->cancel.store(true);
	if (g_thread.joinable())
		g_retired.emplace_back(g_job, std::move(g_thread));
	g_thread = std::thread();
	Reap();
}

LUA_FUNCTION_STATIC(HullBuild)
{
	std::shared_ptr<const MapView> view = CollisionView();
	if (!view || !view->data)
	{
		LUA->PushBool(false);
		LUA->PushString("the map isn't decoded and indexed yet (it needs the slot offsets from Minecraft)");
		return 2;
	}
	mcol::McFrame frame = view->frame;
	bool force = false, hills = true;
	if (LUA->IsType(2, Type::Table))
	{
		frame.originX = static_cast<std::int32_t>(FieldInt(LUA, 2, "ox", -2147483647.0, 2147483647.0, frame.originX));
		frame.originYUnits = static_cast<std::int32_t>(FieldInt(LUA, 2, "oy", -2147483647.0, 2147483647.0, frame.originYUnits));
		frame.originZ = static_cast<std::int32_t>(FieldInt(LUA, 2, "oz", -2147483647.0, 2147483647.0, frame.originZ));
		force = FieldBool(LUA, 2, "force", false);
		hills = FieldBool(LUA, 2, "hills", true);
	}
	if (!SafeName(view->data->name))
	{
		LUA->PushBool(false);
		LUA->PushString("map name not usable in a file name");
		return 2;
	}
	bool haveHash = false;
	std::uint64_t hash = 0;
	{
		std::lock_guard<std::mutex> l(g_hashMu);
		if (g_hashMap == view->data->name)
		{
			haveHash = true;
			hash = g_hash;
		}
	}
	std::vector<std::uint8_t> bsp;
	if (!haveHash)
	{
		if (!LUA->IsType(1, Type::String))
		{
			LUA->PushBool(false);
			LUA->PushString("HullBuild: the map's hash isn't known yet: pass the .bsp's bytes");
			LUA->PushString("bsp");
			return 3;
		}
		unsigned int len = 0;
		const char *p = LUA->GetString(1, &len);
		if (len < 4 || std::memcmp(p, "VBSP", 4) != 0)
		{
			LUA->PushBool(false);
			LUA->PushString("HullBuild: not a .bsp");
			return 2;
		}
		bsp.assign(reinterpret_cast<const std::uint8_t *>(p), reinterpret_cast<const std::uint8_t *>(p) + len);
	}
	RetireCurrent();
	auto job = std::make_shared<HullJob>();
	job->map = view->data->name;
	job->frame = frame;
	job->hills = hills;
	job->startMs = NowMs();
	std::string why;
	try
	{
		g_thread = std::thread([job, view, b = std::move(bsp), haveHash, hash, force]() mutable {
			try
			{
				Run(job, view, std::move(b), haveHash, hash, force);
			}
			catch (const std::exception &e)
			{
				job->Fail(std::string("hull trace failed: ") + e.what());
			}
			catch (...)
			{
				job->Fail("hull trace failed");
			}
			job->finished.store(true);
		});
	}
	catch (const std::system_error &e)
	{
		why = std::string("HullBuild: no thread: ") + e.what();
	}
	if (!why.empty())
	{
		g_job.reset();
		LUA->PushBool(false);
		LUA->PushString(why.c_str());
		return 2;
	}
	g_job = job;
	LUA->PushBool(true);
	return 1;
}

LUA_FUNCTION_STATIC(HullPrune)
{
	const char *map = LUA->CheckString(1);
	const bool hills = LUA->GetBool(2);
	if (!SafeName(map))
	{
		LUA->PushNumber(0);
		return 1;
	}
	std::lock_guard<std::mutex> l(g_shmMu);
	if (g_job && !g_job->finished.load() && g_job->hills != hills)
		RetireCurrent();
	LUA->PushNumber(PruneShm(DiscoveryDir() + "/hull", map, hills));
	return 1;
}

LUA_FUNCTION_STATIC(HullStatus)
{
	Reap();
	LUA->CreateTable();
	std::shared_ptr<HullJob> job = g_job;
	if (!job)
	{
		SetStr(LUA, "state", "idle");
		return 1;
	}
	std::lock_guard<std::mutex> l(job->mu);
	const std::uint32_t done = job->done.load(), total = job->total.load();
	SetStr(LUA, "state", job->state.c_str());
	SetNum(LUA, "done", done);
	SetNum(LUA, "total", total);
	SetNum(LUA, "progress", job->state == "done" ? 1.0 : total ? static_cast<double>(done) / total : 0.0);
	SetStr(LUA, "map", job->map.c_str());
	SetStr(LUA, "hash", job->hash.c_str());
	SetStr(LUA, "path", job->path.c_str());
	SetStr(LUA, "cachePath", job->cachePath.c_str());
	SetBool(LUA, "cached", job->cached);
	SetStr(LUA, "error", job->error.c_str());
	SetNum(LUA, "ms", (job->endMs > 0 ? job->endMs : NowMs()) - job->startMs);
	SetNum(LUA, "regions", job->regions);
	SetNum(LUA, "solidBlocks", static_cast<double>(job->solid));
	SetNum(LUA, "fillBlocks", static_cast<double>(job->fill));
	SetNum(LUA, "hillBlocks", static_cast<double>(job->hill));
	SetBool(LUA, "hills", job->hills);
	SetNum(LUA, "bytes", static_cast<double>(job->bytes));
	SetNum(LUA, "thinConvexes", job->thin);
	SetNum(LUA, "ox", job->frame.originX);
	SetNum(LUA, "oy", job->frame.originYUnits);
	SetNum(LUA, "oz", job->frame.originZ);
	return 1;
}
}  // namespace

void RegisterHull(ILua *L)
{
	L->PushCFunction(HullBuild);
	L->SetField(-2, "HullBuild");
	L->PushCFunction(HullStatus);
	L->SetField(-2, "HullStatus");
	L->PushCFunction(HullPrune);
	L->SetField(-2, "HullPrune");
}

void HullCancel()
{
	if (g_job)
		g_job->cancel.store(true);
}

void CloseHull()
{
	// The module is unloaded after this: every hull thread must be gone.
	RetireCurrent();
	for (auto &r : g_retired)
	{
		r.first->cancel.store(true);
		if (r.second.joinable())
			r.second.join();
	}
	g_retired.clear();
	g_job.reset();
	std::lock_guard<std::mutex> l(g_hashMu);
	g_hashMap.clear();
}
}  // namespace gc
