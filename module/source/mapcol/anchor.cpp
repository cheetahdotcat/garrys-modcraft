// The map's floor for its vertical offset (P8 WP1/WP2, protocol v21): see mapcol.hpp "Map anchor".
#include "mapcol.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>

namespace gmodcraft::mapcol
{
	namespace
	{
		bool FloorKind(const Tri& t)
		{
			return t.kind == kSrcWorld || t.kind == kSrcDisplacement;
		}

		// The upward unit normal's z and the triangle's area (Source units).
		void NormalArea(const Tri& t, float& nz, float& area)
		{
			const float ax = t.v[1].x - t.v[0].x, ay = t.v[1].y - t.v[0].y, az = t.v[1].z - t.v[0].z;
			const float bx = t.v[2].x - t.v[0].x, by = t.v[2].y - t.v[0].y, bz = t.v[2].z - t.v[0].z;
			const float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
			const float len = std::sqrt(cx * cx + cy * cy + cz * cz);
			area = 0.5f * len;
			nz = len > 0 ? cz / len : 0.0f;
		}

		// Height of the triangle's plane at (x, y) if (x, y) lies inside its xy projection.
		bool HeightAt(const Tri& t, float x, float y, float& z)
		{
			const Vec3 &a = t.v[0], &b = t.v[1], &c = t.v[2];
			const float d = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
			if (std::fabs(d) < 1e-6f) {
				return false;
			}
			const float l1 = ((b.y - c.y) * (x - c.x) + (c.x - b.x) * (y - c.y)) / d;
			const float l2 = ((c.y - a.y) * (x - c.x) + (a.x - c.x) * (y - c.y)) / d;
			const float l3 = 1.0f - l1 - l2;
			const float e = -1e-4f;
			if (l1 < e || l2 < e || l3 < e) {
				return false;
			}
			z = l1 * a.z + l2 * b.z + l3 * c.z;
			return true;
		}

		long Bin(float z)
		{
			return std::lround(z);  // 1-unit bins: a floor is a plane, its triangles share one z
		}

		std::string EntityValue(const std::string& block, const char* key)
		{
			const std::string k = std::string("\"") + key + "\"";
			std::size_t i = block.find(k);
			if (i == std::string::npos) {
				return {};
			}
			i = block.find('"', i + k.size());
			const std::size_t j = i == std::string::npos ? i : block.find('"', i + 1);
			return j == std::string::npos ? std::string{} : block.substr(i + 1, j - i - 1);
		}

		// Every entity block of the entity lump, as text.
		template <typename F>
		void ForEachEntity(const BspLumps& lumps, F f)
		{
			const Bytes& b = lumps.lump[kLumpEntities];
			if (b.data == nullptr) {
				return;
			}
			const std::string text(reinterpret_cast<const char*>(b.data), b.size);
			std::size_t p = 0;
			while ((p = text.find('{', p)) != std::string::npos) {
				const std::size_t e = text.find('}', p);
				if (e == std::string::npos) {
					break;
				}
				f(text.substr(p, e - p));
				p = e + 1;
			}
		}
	}

	WalkableSet BuildWalkable(const Mesh& world)
	{
		WalkableSet ws;
		for (std::uint32_t i = 0; i < world.tris.size(); ++i) {
			const Tri& t = world.tris[i];
			if (!FloorKind(t)) {
				continue;
			}
			for (const Vec3& v : t.v) {
				if (!ws.any) {
					ws.minZ = ws.maxZ = v.z;
					ws.footMinX = ws.footMaxX = v.x;
					ws.footMinY = ws.footMaxY = v.y;
					ws.any = true;
				}
				ws.minZ = std::min(ws.minZ, v.z);
				ws.maxZ = std::max(ws.maxZ, v.z);
				ws.footMinX = std::min(ws.footMinX, v.x);
				ws.footMaxX = std::max(ws.footMaxX, v.x);
				ws.footMinY = std::min(ws.footMinY, v.y);
				ws.footMaxY = std::max(ws.footMaxY, v.y);
			}
			float nz, area;
			NormalArea(t, nz, area);
			if (nz < kWalkableNormalY || area <= 0) {
				continue;
			}
			ws.items.push_back({ i, area, (t.v[0].x + t.v[1].x + t.v[2].x) / 3.0f, (t.v[0].y + t.v[1].y + t.v[2].y) / 3.0f,
				(t.v[0].z + t.v[1].z + t.v[2].z) / 3.0f, false, false });
		}
		// Covered: a downward face of solid geometry (any kind) right on top of it (within kCoveredGap):
		// two brushes back to back, so the face is inside solid and nobody stands on it (the outside of
		// a sealing brush under the map's ground, as on gm_flatgrass). Downward faces are bucketed by
		// xy cell so each walkable triangle tests only the few above it.
		constexpr float kCell = 512.0f;
		std::map<std::pair<long, long>, std::vector<std::uint32_t>> down;
		std::vector<std::uint32_t> huge;  // downward faces too big to bucket (the sealing brushes' bottoms): tested for every one
		for (std::uint32_t i = 0; i < world.tris.size(); ++i) {
			const Tri& t = world.tris[i];
			float nz, area;
			NormalArea(t, nz, area);
			if (nz > -kWalkableNormalY || area <= 0) {
				continue;
			}
			const float x0 = std::min({ t.v[0].x, t.v[1].x, t.v[2].x }), x1 = std::max({ t.v[0].x, t.v[1].x, t.v[2].x });
			const float y0 = std::min({ t.v[0].y, t.v[1].y, t.v[2].y }), y1 = std::max({ t.v[0].y, t.v[1].y, t.v[2].y });
			const long cx0 = std::lround(std::floor(x0 / kCell)), cx1 = std::lround(std::floor(x1 / kCell));
			const long cy0 = std::lround(std::floor(y0 / kCell)), cy1 = std::lround(std::floor(y1 / kCell));
			if ((cx1 - cx0 + 1) * (cy1 - cy0 + 1) > 64) {
				huge.push_back(i);
				continue;
			}
			for (long cx = cx0; cx <= cx1; ++cx) {
				for (long cy = cy0; cy <= cy1; ++cy) {
					down[{ cx, cy }].push_back(i);
				}
			}
		}
		// Open to the void: no downward face anywhere above it (the outside top of the map's hull; a
		// floor inside the map has the sky brush's underside above it).
		for (auto& it : ws.items) {
			bool above = false;
			auto test = [&](std::uint32_t d) {
				float h;
				if (!HeightAt(world.tris[d], it.cx, it.cy, h) || h < it.cz - 0.5f) {
					return;
				}
				above = true;
				if (h <= it.cz + kCoveredGap) {
					it.covered = true;
				}
			};
			auto f = down.find({ std::lround(std::floor(it.cx / kCell)), std::lround(std::floor(it.cy / kCell)) });
			if (f != down.end()) {
				for (std::uint32_t d : f->second) {
					test(d);
				}
			}
			for (std::uint32_t d : huge) {
				test(d);
			}
			it.open = !above;
		}
		return ws;
	}

	void MapFloorStats(const Mesh& world, const float* window, FloorStats& out, const AnchorOptions& opt)
	{
		MapFloorStats(world, BuildWalkable(world), window, out, opt);
	}

	void MapFloorStats(const Mesh& world, const WalkableSet& ws, const float* window, FloorStats& out, const AnchorOptions& opt)
	{
		out = FloorStats{};
		out.minZ = ws.minZ;
		out.maxZ = ws.maxZ;
		out.footMinX = ws.footMinX;
		out.footMinY = ws.footMinY;
		out.footMaxX = ws.footMaxX;
		out.footMaxY = ws.footMaxY;
		// A sky_camera in a room of its own: no walkable face right under it (a camera standing on the
		// playable floor, as on gm_flatgrass, means there is no separate room to leave out).
		bool skyRoom = false;
		if (opt.haveSky) {
			skyRoom = true;
			for (const auto& it : ws.items) {
				float h;
				if (!it.covered && HeightAt(world.tris[it.tri], opt.sky.x, opt.sky.y, h) && opt.sky.z - h >= -1.0f && opt.sky.z - h <= kSkyRoomFloor) {
					skyRoom = false;  // the camera stands on a floor: it is in the playable area, no room of its own
					break;
				}
			}
		}
		std::map<long, double> bins;
		for (const auto& it : ws.items) {
			if (it.covered) {
				++out.excludedCovered;
				continue;
			}
			if (it.open || it.cz >= ws.maxZ - 1.0f) {
				++out.excludedTop;  // the outside of the brush sealing the map's top
				continue;
			}
			if (skyRoom && std::fabs(it.cz - opt.sky.z) < kSkyRoomRadius) {
				// the triangle's xy box against the room's: a skybox room's floor can be one huge face
				// whose centroid is far from the camera (gm_construct: a 31k-wide plane at z 10367)
				const Tri& t = world.tris[it.tri];
				const float x0 = std::min({ t.v[0].x, t.v[1].x, t.v[2].x }), x1 = std::max({ t.v[0].x, t.v[1].x, t.v[2].x });
				const float y0 = std::min({ t.v[0].y, t.v[1].y, t.v[2].y }), y1 = std::max({ t.v[0].y, t.v[1].y, t.v[2].y });
				if (x1 >= opt.sky.x - kSkyRoomRadius && x0 <= opt.sky.x + kSkyRoomRadius && y1 >= opt.sky.y - kSkyRoomRadius && y0 <= opt.sky.y + kSkyRoomRadius) {
					++out.excludedSky;
					continue;
				}
			}
			if (window != nullptr && (it.cx < window[0] || it.cy < window[1] || it.cx > window[2] || it.cy > window[3])) {
				continue;
			}
			bins[Bin(it.cz)] += it.area;
			out.walkableArea += it.area;
		}
		out.bins.assign(bins.begin(), bins.end());
		std::stable_sort(out.bins.begin(), out.bins.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
		if (!out.bins.empty()) {
			out.modeZ = static_cast<float>(out.bins.front().first);
			float lowest = out.modeZ;
			for (const auto& b : out.bins) {
				if (b.second > 0.05 * out.walkableArea) {
					lowest = std::min(lowest, static_cast<float>(b.first));
				}
			}
			out.lowestZ = lowest;
		}
	}

	bool FloorUnder(const Mesh& world, float x, float y, float z, float& floorZ)
	{
		return FloorUnder(world, BuildWalkable(world), x, y, z, floorZ);
	}

	bool FloorUnder(const Mesh& world, const WalkableSet& ws, float x, float y, float z, float& floorZ)
	{
		bool found = false;
		for (const auto& it : ws.items) {
			if (it.cz > z + kSpawnAbove + 4096.0f || it.cz < z - kSpawnBelow - 4096.0f) {
				continue;  // cheap reject (a triangle's centroid can't be farther than its size away)
			}
			float h;
			if (HeightAt(world.tris[it.tri], x, y, h) && h <= z + kSpawnAbove && h >= z - kSpawnBelow && (!found || h > floorZ)) {
				floorZ = h;
				found = true;
			}
		}
		return found;
	}

	AnchorResult ChooseAnchor(const Mesh& world, const std::vector<Vec3>& spawns, const std::vector<float>& navZ, const AnchorOptions& opt)
	{
		AnchorResult r;
		const WalkableSet ws = BuildWalkable(world);
		MapFloorStats(world, ws, nullptr, r.stats, opt);
		const FloorStats& all = r.stats;
		r.minZ = all.minZ;
		r.maxZ = all.maxZ;
		r.footMinX = all.footMinX;
		r.footMinY = all.footMinY;
		r.footMaxX = all.footMaxX;
		r.footMaxY = all.footMaxY;
		// Spawn floors: the walkable surface under each spawn point; the most common one wins.
		std::map<long, int> votes;
		for (const Vec3& s : spawns) {
			float f;
			if (FloorUnder(world, ws, s.x, s.y, s.z, f)) {
				r.spawnFloors.push_back(f);
				++votes[Bin(f)];
			}
		}
		auto areaNear = [&](long z) {
			double a = 0;
			for (const auto& b : all.bins) {
				if (std::labs(b.first - z) <= 1) {
					a += b.second;
				}
			}
			return a;
		};
		auto navNear = [&](long z) {
			int n = 0;
			for (float v : navZ) {
				if (std::fabs(v - static_cast<float>(z)) <= 8.0f) {
					++n;
				}
			}
			return n;
		};
		if (!votes.empty()) {
			long best = 0;
			int bestVotes = -1, bestNav = -1;
			double bestArea = -1;
			for (const auto& [z, n] : votes) {
				const int nav = navNear(z);
				const double area = areaNear(z);
				// More spawns; then more navmesh areas there; then more walkable area; then the lower.
				if (n > bestVotes || (n == bestVotes && (nav > bestNav || (nav == bestNav && area > bestArea)))) {
					best = z;
					bestVotes = n;
					bestNav = nav;
					bestArea = area;
				}
			}
			double sum = 0;
			int k = 0;
			for (float f : r.spawnFloors) {
				if (Bin(f) == best) {
					sum += f;
					++k;
				}
			}
			r.floorZ = static_cast<float>(sum / k);
			r.source = proto::kAnchorSpawns;
			return r;
		}
		if (!all.bins.empty()) {
			r.floorZ = all.modeZ;
			r.source = proto::kAnchorMapMode;
			return r;
		}
		r.source = proto::kAnchorNone;
		return r;
	}

	std::vector<Vec3> ParseSpawnPoints(const BspLumps& lumps)
	{
		std::vector<Vec3> out;
		ForEachEntity(lumps, [&](const std::string& block) {
			bool spawn = false;
			const std::string cls = EntityValue(block, "classname");
			for (const char* c : kSpawnClasses) {
				spawn |= cls == c;
			}
			Vec3 v;
			if (spawn && std::sscanf(EntityValue(block, "origin").c_str(), "%f %f %f", &v.x, &v.y, &v.z) == 3) {
				out.push_back(v);
			}
		});
		return out;
	}

	bool ParseSkyCamera(const BspLumps& lumps, Vec3& out)
	{
		bool found = false;
		ForEachEntity(lumps, [&](const std::string& block) {
			if (!found && EntityValue(block, "classname") == "sky_camera"
				&& std::sscanf(EntityValue(block, "origin").c_str(), "%f %f %f", &out.x, &out.y, &out.z) == 3) {
				found = true;
			}
		});
		return found;
	}
}
