// The hull trace (world type `hull`): sky marking, per-region block + material trace, the file
// format (protocol/hull_format.md). See mapcol.hpp "Hull trace".
#include <algorithm>
#include <tuple>
#include <unordered_set>

#include "bitops.hpp"
#include "internal.hpp"
#include "raster.hpp"

namespace gmodcraft::mapcol
{
	using namespace detail;

	namespace
	{
		constexpr float kUnits = static_cast<float>(proto::kUnitsPerBlock);
		constexpr int   kRegion = raster::kRegion;
		constexpr int   kGrid = raster::kGrid;
		constexpr std::int32_t kSurfSky = 0x4, kSurfSky2D = 0x2;  // texinfo flags (SURF_SKY, SURF_SKY2D)
		constexpr float kThinMin = 5.0f / kUnits;                 // one sub-voxel: thinner is dropped
		constexpr int   kCodes = proto::kDigMaterialCount + 1;    // 0 air, 1..22 DigMaterial + 1

		// Source AABB -> MC AABB (x, z, -y; the z bounds swap), as region.cpp's BoxToMc.
		void BoxMc(const McFrame& f, const Vec3& lo, const Vec3& hi, float* out)
		{
			out[0] = lo.x / kUnits + f.originX;
			out[1] = (lo.z + float(f.originYUnits)) / kUnits;
			out[2] = -hi.y / kUnits + f.originZ;
			out[3] = hi.x / kUnits + f.originX;
			out[4] = (hi.z + float(f.originYUnits)) / kUnits;
			out[5] = -lo.y / kUnits + f.originZ;
		}

		void TriBox(const Tri& t, Vec3& lo, Vec3& hi)
		{
			lo = hi = t.v[0];
			for (int v = 1; v < 3; ++v) {
				lo.x = std::min(lo.x, t.v[v].x), lo.y = std::min(lo.y, t.v[v].y), lo.z = std::min(lo.z, t.v[v].z);
				hi.x = std::max(hi.x, t.v[v].x), hi.y = std::max(hi.y, t.v[v].y), hi.z = std::max(hi.z, t.v[v].z);
			}
		}

		bool Traced(const Convex& c) { return c.kind == kSrcWorld && !(c.bits & kBitSky); }
		// NOHULL displacements have no player collision (the colstream drops them too): not traced.
		bool Traced(const Tri& t) { return t.kind == kSrcDisplacement && t.convex < 0 && !(t.bits & (kBitSky | kBitNoHull)); }

		std::uint8_t Code(const MapCollision& map, std::uint16_t material)
		{
			if (material >= map.materials.Size() || !map.materials[material].diggable) {
				return std::uint8_t(proto::kDigNone + 1);
			}
			const unsigned d = map.materials[material].dig;
			return std::uint8_t(d < proto::kDigMaterialCount ? d + 1 : proto::kDigNone + 1);
		}

		int FloorDiv8(int v) { return v >= 0 ? v / kRegion : -((-v + kRegion - 1) / kRegion); }

		int RegionOf(float v)
		{
			const float r = std::floor(v / kRegion);
			return static_cast<int>(std::isfinite(r) ? std::clamp(r, -16777216.0f, 16777216.0f) : 0.0f);
		}

		// little-endian writers / readers (the file is LE; so is every host we build for)
		template <class T>
		void Put(std::vector<std::uint8_t>& out, T v)
		{
			const std::size_t at = out.size();
			out.resize(at + sizeof(T));
			std::memcpy(out.data() + at, &v, sizeof(T));
		}
	}

	// ---- sky ---------------------------------------------------------------------------------------
	void MarkSky(const BspLumps& lumps, MapCollision& map)
	{
		Mesh& w = map.world;
		auto  markConvex = [&](Convex& c) {
            c.bits |= kBitSky;
            for (std::uint32_t t = c.firstTri; t < c.firstTri + c.triCount && t < w.tris.size(); ++t) {
                w.tris[t].bits |= kBitSky;
            }
		};

		// Brushes with a sky-textured side: dbrush_t {int firstside, numsides, contents} (12 bytes),
		// dbrushside_t {u16 planenum; i16 texinfo, dispinfo; u8 bevel, thin} (8), texinfo flags at 64 of 72.
		const Bytes br = lumps.lump[kLumpBrushes], bs = lumps.lump[kLumpBrushSides], ti = lumps.lump[kLumpTexinfo];
		std::size_t nb = 0, ns = 0, nt = 0;
		if (Count(br, 12, nb) && Count(bs, 8, ns) && Count(ti, 72, nt) && nb && ns && nt) {
			std::vector<std::uint8_t> sky(nb, 0);
			for (std::size_t b = 0; b < nb; ++b) {
				std::int32_t first = 0, num = 0;
				Rd(br, std::int64_t(b) * 12, first);
				Rd(br, std::int64_t(b) * 12 + 4, num);
				for (std::int64_t s = first; num > 0 && s < std::int64_t(first) + num && s >= 0 && std::size_t(s) < ns; ++s) {
					std::int16_t  tex = -1;
					std::uint8_t  bevel = 0;
					std::int32_t  flags = 0;
					Rd(bs, s * 8 + 2, tex);
					Rd(bs, s * 8 + 6, bevel);
					if (bevel || tex < 0 || std::size_t(tex) >= nt || !Rd(ti, std::int64_t(tex) * 72 + 64, flags)) {
						continue;
					}
					if (flags & (kSurfSky | kSurfSky2D)) {
						sky[b] = 1;
						break;
					}
				}
			}
			for (Convex& c : w.convexes) {
				if (c.kind == kSrcWorld && c.owner >= 0 && std::size_t(c.owner) < nb && sky[std::size_t(c.owner)]) {
					markConvex(c);
					++map.stats.skyBrushConvexes;
				}
			}
		}

		// The 3D skybox room around the sky_camera.
		SkyRoom room;
		if (!ParseSkyCamera(lumps, room.camera)) {
			map.skyRoom = room;
			return;
		}
		const Vec3& cam = room.camera;
		const float camv[3] = { cam.x, cam.y, cam.z };
		float       bound[3][2];
		int         fallback[3][2];  // the convex a direction without a sky brush stopped at, else -1
		bool        closed = true;
		bool        shellAny = false;
		Vec3        shellLo{}, shellHi{};  // the union of the sky brushes that bound the room
		for (int axis = 0; axis < 3 && closed; ++axis) {
			const int o1 = (axis + 1) % 3, o2 = (axis + 2) % 3;
			for (int dir = 0; dir < 2; ++dir) {  // 0: towards -axis (bound = a brush's hi), 1: +axis (its lo)
				int   sky = -1, any = -1;
				float nearSky = 0, farAny = 0;
				for (std::size_t i = 0; i < w.convexes.size(); ++i) {
					const Convex& c = w.convexes[i];
					if (c.kind != kSrcWorld) {
						continue;
					}
					const float lo[3] = { c.lo.x, c.lo.y, c.lo.z }, hi[3] = { c.hi.x, c.hi.y, c.hi.z };
					if (camv[o1] < lo[o1] || camv[o1] > hi[o1] || camv[o2] < lo[o2] || camv[o2] > hi[o2]) {
						continue;
					}
					const float face = dir ? lo[axis] : hi[axis];
					if (dir ? face < camv[axis] : face > camv[axis]) {
						continue;
					}
					const float dist = std::fabs(face - camv[axis]);
					if ((c.bits & kBitSky) && (sky < 0 || dist < std::fabs(nearSky - camv[axis]))) {
						nearSky = face;
						sky = int(i);
					}
					if (any < 0 || dist > std::fabs(farAny - camv[axis])) {
						farAny = face;
						any = int(i);
					}
				}
				if (sky < 0 && any < 0) {
					closed = false;
					break;
				}
				bound[axis][dir] = sky >= 0 ? nearSky : farAny;
				fallback[axis][dir] = sky >= 0 ? -1 : any;
				if (sky >= 0) {
					const Convex& c = w.convexes[std::size_t(sky)];
					shellLo = shellAny ? Vec3{ std::min(shellLo.x, c.lo.x), std::min(shellLo.y, c.lo.y), std::min(shellLo.z, c.lo.z) } : c.lo;
					shellHi = shellAny ? Vec3{ std::max(shellHi.x, c.hi.x), std::max(shellHi.y, c.hi.y), std::max(shellHi.z, c.hi.z) } : c.hi;
					shellAny = true;
				}
			}
		}
		// A direction without a sky brush (gm_flatgrass's room floor isn't sky-textured) only counts
		// when the brush it stopped at lies within the room's sky shell; otherwise it may be the
		// playable map, and the room is given up rather than marking playable space as sky.
		for (int axis = 0; axis < 3 && closed; ++axis) {
			for (int dir = 0; dir < 2 && closed; ++dir) {
				if (fallback[axis][dir] < 0) {
					continue;
				}
				const Convex& c = w.convexes[std::size_t(fallback[axis][dir])];
				constexpr float e = 1.0f;
				closed = shellAny && c.lo.x >= shellLo.x - e && c.lo.y >= shellLo.y - e && c.lo.z >= shellLo.z - e && c.hi.x <= shellHi.x + e &&
				         c.hi.y <= shellHi.y + e && c.hi.z <= shellHi.z + e;
			}
		}
		if (closed) {
			room.lo = Vec3{ bound[0][0], bound[1][0], bound[2][0] };
			room.hi = Vec3{ bound[0][1], bound[1][1], bound[2][1] };
			room.found = true;
			for (const Vec3& s : ParseSpawnPoints(lumps)) {
				if (s.x >= room.lo.x && s.x <= room.hi.x && s.y >= room.lo.y && s.y <= room.hi.y && s.z >= room.lo.z && s.z <= room.hi.z) {
					room.found = false;  // the camera stands in the playable area: no room of its own
				}
			}
		}
		map.skyRoom = room;
		if (!room.found) {
			return;
		}
		constexpr float e = 1.0f;
		auto touches = [&](const Vec3& lo, const Vec3& hi) {
			return lo.x <= room.hi.x + e && hi.x >= room.lo.x - e && lo.y <= room.hi.y + e && hi.y >= room.lo.y - e && lo.z <= room.hi.z + e &&
			       hi.z >= room.lo.z - e;
		};
		for (Convex& c : w.convexes) {
			if (c.kind == kSrcWorld && !(c.bits & kBitSky) && touches(c.lo, c.hi)) {
				markConvex(c);
				++map.stats.skyRoomConvexes;
			}
		}
		for (Tri& t : w.tris) {
			if (t.convex >= 0 || t.kind != kSrcDisplacement || (t.bits & kBitSky)) {
				continue;
			}
			Vec3 lo, hi;
			TriBox(t, lo, hi);
			if (touches(lo, hi)) {
				t.bits |= kBitSky;
				++map.stats.skyRoomTris;
			}
		}
	}

	// ---- trace -------------------------------------------------------------------------------------
	void HullRegions(const MapCollision& map, const RegionIndex& index, std::vector<std::array<int, 3>>& out, const HullFill* fill)
	{
		out.clear();
		const Mesh* mesh = index.Source();
		if (mesh == nullptr) {
			return;
		}
		const McFrame& f = index.Frame();
		std::unordered_set<std::uint64_t> seen;
		auto add = [&](const float* b) {
			const int x0 = RegionOf(b[0]), y0 = RegionOf(b[1]), z0 = RegionOf(b[2]);
			const int x1 = RegionOf(b[3]), y1 = RegionOf(b[4]), z1 = RegionOf(b[5]);
			if ((std::int64_t(x1) - x0 + 1) * (std::int64_t(y1) - y0 + 1) * (std::int64_t(z1) - z0 + 1) > (1ll << 24)) {
				return;  // corrupt bounds: a real map spans at most ~100^3 regions
			}
			for (int y = y0; y <= y1; ++y) {
				for (int z = z0; z <= z1; ++z) {
					for (int x = x0; x <= x1; ++x) {
						const std::uint64_t k = (std::uint64_t(std::uint32_t(x) & 0x1FFFFF) << 42) |
						                        (std::uint64_t(std::uint32_t(y) & 0x1FFFFF) << 21) | (std::uint32_t(z) & 0x1FFFFF);
						if (seen.insert(k).second) {
							out.push_back({ x, y, z });
						}
					}
				}
			}
		};
		float b[6];
		for (const Convex& c : mesh->convexes) {
			if (Traced(c) && Bounded3(c.lo) && Bounded3(c.hi)) {
				BoxMc(f, c.lo, c.hi, b);
				add(b);
			}
		}
		for (const Tri& t : mesh->tris) {
			if (!Traced(t)) {
				continue;
			}
			Vec3 lo, hi;
			TriBox(t, lo, hi);
			if (Bounded3(lo) && Bounded3(hi)) {
				BoxMc(f, lo, hi, b);
				add(b);
			}
		}
		if (fill && fill->ok) {
			// per region column, the fill's lowest and highest region
			std::unordered_map<std::uint64_t, std::array<int, 4>> span;  // rx, rz, ry lo, ry hi
			for (std::int32_t cz = 0; cz < fill->nz; ++cz) {
				for (std::int32_t cx = 0; cx < fill->nx; ++cx) {
					const std::size_t i = std::size_t(cx) + std::size_t(fill->nx) * std::size_t(cz);
					if (fill->first[i] == fill->first[i + 1]) {
						continue;
					}
					float lo = 1e30f, hi = -1e30f;
					for (std::uint32_t k = fill->first[i]; k < fill->first[i + 1]; ++k) {
						lo = std::min(lo, fill->runs[k].lo);
						hi = std::max(hi, fill->runs[k].hi);
					}
					const int rx = FloorDiv8(fill->x0 + cx), rz = FloorDiv8(fill->z0 + cz);
					const int ylo = RegionOf(lo - 0.5f), yhi = RegionOf(hi);
					const std::uint64_t key = (std::uint64_t(std::uint32_t(rx)) << 32) | std::uint32_t(rz);
					auto [it, fresh] = span.try_emplace(key, std::array<int, 4>{ rx, rz, ylo, yhi });
					if (!fresh) {
						it->second[2] = std::min(it->second[2], ylo);
						it->second[3] = std::max(it->second[3], yhi);
					}
				}
			}
			for (const auto& kv : span) {
				const std::array<int, 4>& sp = kv.second;
				const float box[6] = { float(sp[0] * kRegion), float(sp[2] * kRegion), float(sp[1] * kRegion), float(sp[0] * kRegion + 1),
					float(sp[3] * kRegion), float(sp[1] * kRegion + 1) };
				add(box);
			}
		}
		(void)map;
		std::sort(out.begin(), out.end(), [](const auto& a, const auto& c) {
			return std::tie(a[1], a[2], a[0]) < std::tie(c[1], c[2], c[0]);
		});
	}

	bool TraceHullRegion(const MapCollision& map, const RegionIndex& index, int rx, int ry, int rz, HullRegion& out, HullStats* stats,
		const HullFill* hullFill)
	{
		out = HullRegion{};
		out.x = rx * kRegion;
		out.y = ry * kRegion;
		out.z = rz * kRegion;
		if (stats) {
			++stats->regions;
		}
		const Mesh* mesh = index.Source();
		if (mesh == nullptr) {
			return false;
		}
		const McFrame& f = index.Frame();
		constexpr int  G = kGrid;
		const float    origin[3] = { float(out.x), float(out.y), float(out.z) };
		std::vector<std::uint64_t> filled(std::size_t(G) * G, 0);  // [y * G + z], bit x
		std::vector<std::uint16_t> votes(512 * kCodes, 0);
		std::array<bool, 512>      surface{};
		auto block = [](int x, int y, int z) { return (x >> 3) + 8 * ((z >> 3) + 8 * (y >> 3)); };
		auto fill = [&](int x, int y, int z, std::uint8_t code) {
			std::uint64_t& row = filled[std::size_t(y) * G + z];
			const std::uint64_t bit = 1ull << x;
			if (!(row & bit)) {
				row |= bit;
				++votes[std::size_t(block(x, y, z)) * kCodes + code];
			}
		};

		// Convexes: sub-voxel-centre containment (no margin: "at least half" means volume).
		const float qlo[3] = { origin[0] + 1e-3f, origin[1] + 1e-3f, origin[2] + 1e-3f };
		const float qhi[3] = { origin[0] + kRegion - 1e-3f, origin[1] + kRegion - 1e-3f, origin[2] + kRegion - 1e-3f };
		std::vector<std::uint32_t> ids;
		index.ConvexesInBox(qlo, qhi, ids);
		std::vector<std::array<float, 4>> planes;
		const bool                        useFill = hullFill != nullptr && hullFill->ok;
		std::vector<std::uint32_t>        skyIds;  // sky brushes here: the fill leaves them out
		for (std::uint32_t ci : ids) {
			const Convex& c = mesh->convexes[ci];
			if (!Traced(c)) {
				if (c.kind == kSrcWorld) {
					if (stats) {
						++stats->skippedSky;
					}
					if (useFill) {
						skyIds.push_back(ci);
					}
				}
				continue;
			}
			if (!Bounded3(c.lo) || !Bounded3(c.hi)) {
				continue;
			}
			float b[6];
			BoxMc(f, c.lo, c.hi, b);
			planes.clear();
			for (std::uint32_t p = c.firstPlane; p < c.firstPlane + c.planeCount && p < mesh->planes.size(); ++p) {
				const Plane q = PlaneToMc(f, mesh->planes[p]);
				planes.push_back({ q.n[0], q.n[1], q.n[2], q.d });
			}
			if (planes.empty()) {
				continue;
			}
			// Sub-voxel ranges per axis; a thin axis covers the block holding the mid-plane, probed there.
			int  lo[3], hi[3];
			bool thin[3] = { false, false, false };
			float mid[3];
			bool skip = false;
			for (int k = 0; k < 3 && !skip; ++k) {
				const float ext = b[3 + k] - b[k];
				mid[k] = 0.5f * (b[k] + b[3 + k]);
				if (ext >= kThinMin - 1e-4f && ext < 1.0f) {
					thin[k] = true;
					const int blk = static_cast<int>(std::floor(mid[k])) - int(origin[k]);
					if (blk < 0 || blk >= kRegion) {
						skip = true;
						break;
					}
					lo[k] = blk * 8;
					hi[k] = blk * 8 + 7;
				} else {
					lo[k] = raster::ClampLo((b[k] - origin[k]) * 8.0f - 1.0f);
					hi[k] = raster::ClampHi((b[3 + k] - origin[k]) * 8.0f + 1.0f);
					if ((b[3 + k] - origin[k]) * 8.0f < 0 || (b[k] - origin[k]) * 8.0f > G) {
						skip = true;
					}
				}
			}
			if (skip) {
				continue;
			}
			if (stats && (thin[0] || thin[1] || thin[2])) {
				++stats->thinConvexes;  // counted once per region it is traced in
			}
			const std::uint8_t code = Code(map, c.material);
			for (int y = lo[1]; y <= hi[1]; ++y) {
				for (int z = lo[2]; z <= hi[2]; ++z) {
					for (int x = lo[0]; x <= hi[0]; ++x) {
						float p[3] = { origin[0] + (x + 0.5f) / 8.0f, origin[1] + (y + 0.5f) / 8.0f, origin[2] + (z + 0.5f) / 8.0f };
						for (int k = 0; k < 3; ++k) {
							if (thin[k]) {
								p[k] = mid[k];
							}
						}
						bool inside = true;
						for (const auto& pl : planes) {
							if (pl[0] * p[0] + pl[1] * p[1] + pl[2] * p[2] + pl[3] > 0.0f) {
								inside = false;
								break;
							}
						}
						if (inside) {
							fill(x, y, z, code);
						}
					}
				}
			}
		}

		// Displacement triangles: surfaces; every block they touch is solid (raster::Triangle, the
		// same sub-voxels the colstream's ColBlock occupancy gets for them).
		const float tlo[3] = { origin[0] - 0.5f, origin[1] - 0.5f, origin[2] - 0.5f };
		const float thi[3] = { origin[0] + kRegion + 0.5f, origin[1] + kRegion + 0.5f, origin[2] + kRegion + 0.5f };
		index.TrianglesInBox(tlo, thi, ids);
		for (std::uint32_t ti : ids) {
			const Tri& t = mesh->tris[ti];
			if (!Traced(t)) {
				if (stats && t.kind == kSrcDisplacement && (t.bits & kBitSky)) {
					++stats->skippedSky;
				}
				continue;
			}
			float v[9];
			for (int k = 0; k < 3; ++k) {
				ToMc(f, t.v[k], v + 3 * k);
			}
			const std::uint8_t code = Code(map, t.material);
			auto sink = [&](int x, int y, int z) {
				surface[std::size_t(block(x, y, z))] = true;
				fill(x, y, z, code);
			};
			raster::Triangle(v, origin, [&](const float*) { return &sink; });
		}

		// The fill: sky brushes in the region (MC planes), never filled.
		std::vector<std::vector<std::array<float, 4>>> sky;
		for (std::uint32_t ci : skyIds) {
			const Convex& c = mesh->convexes[ci];
			sky.emplace_back();
			for (std::uint32_t p = c.firstPlane; p < c.firstPlane + c.planeCount && p < mesh->planes.size(); ++p) {
				const Plane q = PlaneToMc(f, mesh->planes[p]);
				sky.back().push_back({ q.n[0], q.n[1], q.n[2], q.d });
			}
			if (sky.back().empty()) {
				sky.pop_back();
			}
		}
		// The fill's code for block (bx, by, bz), 0 for none; `hill`: from a run under a displacement.
		auto fillCode = [&](int bx, int by, int bz, bool& hill) -> std::uint8_t {
			const int cx = out.x + bx - hullFill->x0, cz = out.z + bz - hullFill->z0;
			if (cx < 0 || cz < 0 || cx >= hullFill->nx || cz >= hullFill->nz) {
				return 0;
			}
			const std::size_t i = std::size_t(cx) + std::size_t(hullFill->nx) * std::size_t(cz);
			const float       p[3] = { float(out.x + bx) + 0.5f, float(out.y + by) + 0.5f, float(out.z + bz) + 0.5f };
			std::uint8_t      code = 0;
			for (std::uint32_t k = hullFill->first[i]; k < hullFill->first[i + 1] && !code; ++k) {
				if (p[1] >= hullFill->runs[k].lo && p[1] < hullFill->runs[k].hi) {
					code = hullFill->runs[k].code;
					hill = hullFill->runs[k].hill;
				}
			}
			if (!code) {
				return 0;
			}
			if (hullFill->room && p[0] >= hullFill->roomLo[0] && p[0] <= hullFill->roomHi[0] && p[1] >= hullFill->roomLo[1] && p[1] <= hullFill->roomHi[1] &&
				p[2] >= hullFill->roomLo[2] && p[2] <= hullFill->roomHi[2]) {
				return 0;
			}
			for (const auto& pl : sky) {
				bool inside = true;
				for (const auto& q : pl) {
					if (q[0] * p[0] + q[1] * p[1] + q[2] * p[2] + q[3] > 0.0f) {
						inside = false;
						break;
					}
				}
				if (inside) {
					return 0;
				}
			}
			return code;
		};

		// Blocks: solid when half full or touched by a surface; material = majority. Else the fill.
		bool any = false;
		for (int by = 0; by < kRegion; ++by) {
			for (int bz = 0; bz < kRegion; ++bz) {
				for (int bx = 0; bx < kRegion; ++bx) {
					const int idx = bx + 8 * (bz + 8 * by);
					int       count = 0;
					for (int y = by * 8; y < by * 8 + 8; ++y) {
						for (int z = bz * 8; z < bz * 8 + 8; ++z) {
							count += Popcount64((filled[std::size_t(y) * G + z] >> (bx * 8)) & 0xFF);
						}
					}
					if (count < 256 && !surface[std::size_t(idx)]) {
						bool               hill = false;
						const std::uint8_t code = useFill ? fillCode(bx, by, bz, hill) : 0;
						if (code) {
							out.code[std::size_t(idx)] = code;
							out.filled.set(std::size_t(idx));
							any = true;
							if (stats) {
								++stats->solidBlocks;
								++stats->fillBlocks;
								stats->hillBlocks += hill;
								++stats->materialBlocks[code - 1];
							}
						}
						continue;
					}
					const std::uint16_t* v = &votes[std::size_t(idx) * kCodes];
					int best = 1;
					for (int c = 1; c < kCodes; ++c) {
						if (v[c] > v[best]) {
							best = c;
						}
					}
					out.code[std::size_t(idx)] = std::uint8_t(best);
					any = true;
					if (stats) {
						++stats->solidBlocks;
						++stats->materialBlocks[best - 1];
					}
				}
			}
		}
		if (stats && any) {
			++stats->solidRegions;
		}
		return any;
	}

	// ---- fill ----------------------------------------------------------------------------------------
	namespace
	{
		// What the solid run whose top is Source z `top` at (x, y) ends with: a traced brush's face
		// (1, its code), a sky brush (-1) or nothing traced (0).
		int TopFace(const MapCollision& map, const RegionIndex& index, float x, float y, float top, std::vector<std::uint32_t>& ids,
			std::uint8_t& code)
		{
			const Mesh* mesh = index.Source();
			const Vec3  p{ x, y, top - 1.0f };  // just inside the solid
			float       mc[3];
			ToMc(index.Frame(), p, mc);
			index.ConvexesInBox(mc, mc, ids);
			float best = 1e30f;
			int   found = 0;
			for (std::uint32_t ci : ids) {
				const Convex& c = mesh->convexes[ci];
				if (c.kind != kSrcWorld) {
					continue;
				}
				bool inside = true;
				for (std::uint32_t k = c.firstPlane; k < c.firstPlane + c.planeCount && k < mesh->planes.size() && inside; ++k) {
					const Plane& q = mesh->planes[k];
					inside = q.n[0] * p.x + q.n[1] * p.y + q.n[2] * p.z + q.d <= 0.05f;
				}
				if (!inside) {
					continue;
				}
				if (c.bits & kBitSky) {
					return -1;
				}
				// its upward face over (x, y) nearest the run's top: that face's material
				bool face = false;
				for (std::uint32_t t = c.firstTri; t < c.firstTri + c.triCount && t < mesh->tris.size(); ++t) {
					const Tri& tri = mesh->tris[t];
					const Vec3 n = Cross3(Sub3(tri.v[1], tri.v[0]), Sub3(tri.v[2], tri.v[0]));
					if (!(n.z > 1e-6f)) {
						continue;
					}
					const float h = tri.v[0].z - (n.x * (x - tri.v[0].x) + n.y * (y - tri.v[0].y)) / n.z;
					face = true;
					if (std::fabs(h - top) < best) {
						best = std::fabs(h - top);
						code = Code(map, tri.material);
					}
				}
				if (!face && found == 0) {
					code = Code(map, c.material);
				}
				found = 1;
			}
			return found;
		}
	}

	namespace
	{
		// A traced displacement lying on the run's top (within one block of it, above or dipping
		// into the brush) is the ground there: its material, not the hidden brush's under it (grass layers into dirt, then stone).
		void DispOnTop(const MapCollision& map, const RegionIndex& index, float x, float y, float top, std::vector<std::uint32_t>& ids,
			std::uint8_t& code)
		{
			const Mesh* mesh = index.Source();
			float       a[3], b[3];
			ToMc(index.Frame(), Vec3{ x, y, top - kUnits }, a);
			ToMc(index.Frame(), Vec3{ x, y, top + kUnits }, b);
			index.TrianglesInBox(a, b, ids);
			float best = 1e30f;
			for (std::uint32_t ti : ids) {
				const Tri& t = mesh->tris[ti];
				if (!Traced(t)) {
					continue;
				}
				// (x, y) inside the triangle's footprint
				auto side = [&](int i, int j) {
					return (t.v[j].x - t.v[i].x) * (y - t.v[i].y) - (t.v[j].y - t.v[i].y) * (x - t.v[i].x);
				};
				const float s0 = side(0, 1), s1 = side(1, 2), s2 = side(2, 0);
				if (!((s0 >= 0 && s1 >= 0 && s2 >= 0) || (s0 <= 0 && s1 <= 0 && s2 <= 0))) {
					continue;
				}
				const Vec3 n = Cross3(Sub3(t.v[1], t.v[0]), Sub3(t.v[2], t.v[0]));
				if (std::fabs(n.z) < 1e-6f) {
					continue;
				}
				const float h = t.v[0].z - (n.x * (x - t.v[0].x) + n.y * (y - t.v[0].y)) / n.z;
				if (std::fabs(h - top) <= kUnits && std::fabs(h - top) < best) {
					best = std::fabs(h - top);
					code = Code(map, t.material);
				}
			}
		}
	}

	namespace
	{
		// Terrain hanging over the map's sky floor (gm_construct's lake valley: 60..900 units of
		// void under it) is ground too: a sky brush's top counts as the floor under a displacement.
		constexpr bool kHillFloorOnSky = true;

		struct Span
		{
			float lo, hi;
			bool  sky;
		};

		// The hills rule (mapcol.hpp) for the vertical line through (x, y): under each upward
		// traced displacement triangle, the air down to the next solid thing below, as Source z
		// (floor, surface, code). `solid`: the line's BSP solid runs.
		void HillRuns(const MapCollision& map, const RegionIndex& index, float x, float y, const std::vector<std::pair<float, float>>& solid,
			std::vector<std::uint32_t>& ids, std::vector<std::tuple<float, float, std::uint8_t>>& out)
		{
			out.clear();
			const Mesh* mesh = index.Source();
			float       a[3], b[3];
			ToMc(index.Frame(), Vec3{ x, y, map.tree.lo.z }, a);
			ToMc(index.Frame(), Vec3{ x, y, map.tree.hi.z }, b);
			const float qlo[3] = { a[0] - 0.01f, std::min(a[1], b[1]), a[2] - 0.01f };
			const float qhi[3] = { a[0] + 0.01f, std::max(a[1], b[1]), a[2] + 0.01f };
			struct Hit
			{
				float        z;
				bool         start;  // upward and traced: a fill starts under it
				bool         down;   // facing down: open space under it
				std::uint8_t code;
			};
			std::vector<Hit> hits;
			bool             anyStart = false;
			index.TrianglesInBox(qlo, qhi, ids);
			for (std::uint32_t ti : ids) {
				const Tri& t = mesh->tris[ti];
				if (t.convex >= 0 || t.kind != kSrcDisplacement) {
					continue;
				}
				auto side = [&](int i, int j) {
					return (t.v[j].x - t.v[i].x) * (y - t.v[i].y) - (t.v[j].y - t.v[i].y) * (x - t.v[i].x);
				};
				const float s0 = side(0, 1), s1 = side(1, 2), s2 = side(2, 0);
				if (!((s0 >= 0 && s1 >= 0 && s2 >= 0) || (s0 <= 0 && s1 <= 0 && s2 <= 0))) {
					continue;
				}
				const Vec3 n = Cross3(Sub3(t.v[1], t.v[0]), Sub3(t.v[2], t.v[0]));
				if (std::fabs(n.z) < 1e-6f) {
					continue;
				}
				const float h = t.v[0].z - (n.x * (x - t.v[0].x) + n.y * (y - t.v[0].y)) / n.z;
				const bool  start = n.z > 0 && Traced(t);
				hits.push_back(Hit{ h, start, n.z < 0, start ? Code(map, t.material) : std::uint8_t(0) });
				anyStart |= start;
			}
			if (!anyStart) {
				return;
			}
			// What is solid on the line: world brushes (sky ones too) and the BSP's solid runs.
			std::vector<Span> spans;
			for (const auto& [lo, hi] : solid) {
				spans.push_back(Span{ lo, hi, false });
			}
			index.ConvexesInBox(qlo, qhi, ids);
			for (std::uint32_t ci : ids) {
				const Convex& c = mesh->convexes[ci];
				if (c.kind != kSrcWorld) {
					continue;
				}
				float zl = -1e30f, zh = 1e30f;
				bool  on = true;
				for (std::uint32_t k = c.firstPlane; k < c.firstPlane + c.planeCount && k < mesh->planes.size() && on; ++k) {
					const Plane& q = mesh->planes[k];
					const float  base = q.n[0] * x + q.n[1] * y + q.d;
					if (std::fabs(q.n[2]) < 1e-6f) {
						on = base <= 0.0f;
					} else if (q.n[2] > 0) {
						zh = std::min(zh, -base / q.n[2]);
					} else {
						zl = std::max(zl, -base / q.n[2]);
					}
				}
				if (on && zl < zh) {
					spans.push_back(Span{ zl, zh, (c.bits & kBitSky) != 0 });
				}
			}
			std::sort(hits.begin(), hits.end(), [](const Hit& p, const Hit& q) { return p.z > q.z; });
			constexpr float e = 0.5f;
			float lastStart = 1e30f;
			for (std::size_t i = 0; i < hits.size(); ++i) {
				if (!hits[i].start || lastStart - hits[i].z < e) {
					continue;  // not a start, or a shared edge's second triangle
				}
				const float s = hits[i].z;
				lastStart = s;
				// A downward triangle at the same height (a double-sided sheet): its underside faces
				// open space, nothing to fill.
				bool sheet = false;
				for (const Hit& h : hits) {
					sheet |= h.down && std::fabs(h.z - s) < e;
				}
				if (sheet) {
					continue;
				}
				bool        inside = false;
				float       floor = -1e30f;
				bool        sky = false;
				for (const Span& sp : spans) {
					if (sp.lo < s - e && sp.hi > s - e) {
						inside = true;  // the surface lies on or in something solid: nothing open under it
						break;
					}
					if (sp.hi <= s - e && sp.hi >= floor - e) {
						sky = sp.hi > floor + e ? sp.sky : sky || sp.sky;
						floor = std::max(floor, sp.hi);
					}
				}
				if (inside) {
					continue;
				}
				for (std::size_t j = i + 1; j < hits.size(); ++j) {
					if (hits[j].z < s - e) {
						if (hits[j].z > floor + e) {
							floor = hits[j].z;
							sky = false;
						}
						break;
					}
				}
				if (floor > -1e29f && (kHillFloorOnSky || !sky)) {
					out.emplace_back(floor, s, hits[i].code);
				}
			}
		}
	}

	bool BuildHullFill(const MapCollision& map, const RegionIndex& index, HullFill& fill, bool hills, const std::atomic<bool>* cancel)
	{
		fill = HullFill{};
		const Mesh* mesh = index.Source();
		if (mesh == nullptr || !map.tree.ok) {
			return false;
		}
		const McFrame& f = index.Frame();
		// The traced geometry's bounds: the columns looked at, and the fill's bottom.
		bool any = false;
		Vec3 lo{}, hi{};
		auto grow = [&](const Vec3& a, const Vec3& b) {
			if (!Bounded3(a) || !Bounded3(b)) {
				return;
			}
			lo = any ? Vec3{ std::min(lo.x, a.x), std::min(lo.y, a.y), std::min(lo.z, a.z) } : a;
			hi = any ? Vec3{ std::max(hi.x, b.x), std::max(hi.y, b.y), std::max(hi.z, b.z) } : b;
			any = true;
		};
		for (const Convex& c : mesh->convexes) {
			if (Traced(c)) {
				grow(c.lo, c.hi);
			}
		}
		bool terrain = false;  // any traced displacement (else no column needs the hills rule)
		for (const Tri& t : mesh->tris) {
			if (Traced(t)) {
				Vec3 a, b;
				TriBox(t, a, b);
				grow(a, b);
				terrain = true;
			}
		}
		if (!any) {
			return false;
		}
		float b[6];
		BoxMc(f, lo, hi, b);
		const std::int64_t x0 = std::int64_t(std::floor(b[0])), x1 = std::int64_t(std::floor(b[3]));
		const std::int64_t z0 = std::int64_t(std::floor(b[2])), z1 = std::int64_t(std::floor(b[5]));
		if (x1 - x0 + 1 > 8192 || z1 - z0 + 1 > 8192) {
			return false;  // beyond any real map (+-16384 units is 820 blocks)
		}
		fill.x0 = std::int32_t(x0);
		fill.z0 = std::int32_t(z0);
		fill.nx = std::int32_t(x1 - x0 + 1);
		fill.nz = std::int32_t(z1 - z0 + 1);
		fill.first.reserve(std::size_t(fill.nx) * std::size_t(fill.nz) + 1);
		const float bottom = std::max(lo.z, map.tree.lo.z);
		std::vector<std::pair<float, float>>                runs;
		std::vector<std::uint32_t>                          ids;
		std::vector<std::tuple<float, float, std::uint8_t>> hill;
		fill.hills = hills;
		for (std::int32_t cz = 0; cz < fill.nz; ++cz) {
			if (cancel != nullptr && cancel->load()) {
				fill = HullFill{};
				return false;
			}
			for (std::int32_t cx = 0; cx < fill.nx; ++cx) {
				fill.first.push_back(std::uint32_t(fill.runs.size()));
				const float mc[3] = { float(fill.x0 + cx) + 0.5f, 0.0f, float(fill.z0 + cz) + 0.5f };
				const Vec3  src = FromMc(f, mc);
				BspSolidRuns(map.tree, src.x, src.y, runs);
				for (const auto& [rlo, rhi] : runs) {
					if (rhi >= map.tree.hi.z - 0.5f) {
						continue;  // reaches the model's top: the void around the map
					}
					const float from = std::max(rlo, bottom);
					if (!(from < rhi)) {
						continue;
					}
					std::uint8_t code = 0;
					const int    top = TopFace(map, index, src.x, src.y, rhi, ids, code);
					if (top <= 0) {
						++(top < 0 ? fill.skyRuns : fill.unknownRuns);
						continue;
					}
					DispOnTop(map, index, src.x, src.y, rhi, ids, code);
					fill.runs.push_back(HullFill::Run{ (from + float(f.originYUnits)) / kUnits, (rhi + float(f.originYUnits)) / kUnits, code, false });
				}
				if (!hills || !terrain) {
					continue;
				}
				// Under terrain: these runs lie in open leaves, so they never overlap the ones above.
				HillRuns(map, index, src.x, src.y, runs, ids, hill);
				for (const auto& [floor, surface, code] : hill) {
					const float from = std::max(floor, bottom);
					if (from < surface) {
						fill.runs.push_back(HullFill::Run{ (from + float(f.originYUnits)) / kUnits, (surface + float(f.originYUnits)) / kUnits, code, true });
						++fill.hillRuns;
					}
				}
			}
		}
		fill.first.push_back(std::uint32_t(fill.runs.size()));
		if (map.skyRoom.found) {
			float r[6];
			BoxMc(f, map.skyRoom.lo, map.skyRoom.hi, r);
			fill.room = true;
			std::memcpy(fill.roomLo, r, sizeof fill.roomLo);
			std::memcpy(fill.roomHi, r + 3, sizeof fill.roomHi);
		}
		fill.ok = true;
		return true;
	}

	// ---- file ----------------------------------------------------------------------------------------
	std::uint64_t Fnv1a64(Bytes b, std::uint64_t h)
	{
		for (std::size_t i = 0; i < b.size; ++i) {
			h ^= b.data[i];
			h *= 0x100000001b3ull;
		}
		return h;
	}

	void SerializeHull(const std::vector<HullRegion>& regions, std::uint64_t mapHash, const McFrame& frame, std::uint32_t flags,
		std::vector<std::uint8_t>& out)
	{
		out.clear();
		std::uint32_t count = 0, solid = 0;
		std::vector<std::uint8_t> body;
		for (const HullRegion& r : regions) {
			std::uint32_t n = 0;
			for (std::uint8_t c : r.code) {
				n += c != 0;
			}
			if (n == 0) {
				continue;
			}
			++count;
			solid += n;
			Put<std::int32_t>(body, r.x);
			Put<std::int32_t>(body, r.y);
			Put<std::int32_t>(body, r.z);
			const std::size_t runsAt = body.size();
			Put<std::uint16_t>(body, 0);
			std::uint16_t runs = 0;
			for (std::size_t i = 0; i < r.code.size();) {
				std::size_t j = i;
				while (j < r.code.size() && r.code[j] == r.code[i]) {
					++j;
				}
				Put<std::uint8_t>(body, r.code[i]);
				Put<std::uint16_t>(body, std::uint16_t(j - i));
				++runs;
				i = j;
			}
			std::memcpy(body.data() + runsAt, &runs, 2);
		}
		static const char kMagic[8] = { 'G', 'M', 'C', 'H', 'U', 'L', 'L', '\0' };
		out.insert(out.end(), kMagic, kMagic + 8);
		Put<std::uint32_t>(out, kHullVersion);
		Put<std::uint32_t>(out, kHullHeaderBytes);
		Put<std::uint64_t>(out, mapHash);
		Put<std::int32_t>(out, frame.originX);
		Put<std::int32_t>(out, frame.originYUnits);
		Put<std::int32_t>(out, frame.originZ);
		Put<std::uint32_t>(out, count);
		Put<std::uint32_t>(out, solid);
		Put<std::uint32_t>(out, flags & ~kHullFlagStaticProps);  // static props are never traced
		Put<std::uint32_t>(out, kHullTraceVersion);
		out.resize(kHullHeaderBytes, 0);
		out.insert(out.end(), body.begin(), body.end());
	}

	bool ParseHullHeader(Bytes file, HullHeader& h, std::string& err)
	{
		h = HullHeader{};
		std::uint32_t version = 0, headerBytes = 0;
		if (file.size < kHullHeaderBytes || std::memcmp(file.data, "GMCHULL\0", 8) != 0) {
			err = "hull: bad magic or short header";
			return false;
		}
		Rd(file, 8, version);
		Rd(file, 12, headerBytes);
		if (version != kHullVersion || headerBytes != kHullHeaderBytes) {
			err = "hull: version " + std::to_string(version) + ", header " + std::to_string(headerBytes);
			return false;
		}
		Rd(file, 16, h.mapHash);
		Rd(file, 24, h.frame.originX);
		Rd(file, 28, h.frame.originYUnits);
		Rd(file, 32, h.frame.originZ);
		Rd(file, 36, h.regionCount);
		Rd(file, 40, h.solidCount);
		Rd(file, 44, h.flags);
		Rd(file, 48, h.traceVersion);
		return true;
	}

	bool HullFileStale(const std::string& fileName, const std::string& map, Bytes head, bool hills)
	{
		constexpr std::size_t kHex = 16;
		const std::string     ext = ".bin";
		if (map.empty() || fileName.size() != map.size() + 1 + kHex + ext.size() || fileName.compare(0, map.size(), map) != 0 ||
			fileName[map.size()] != '.' || fileName.compare(fileName.size() - ext.size(), ext.size(), ext) != 0) {
			return false;
		}
		for (std::size_t i = map.size() + 1; i < map.size() + 1 + kHex; ++i) {
			const char c = fileName[i];
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
				return false;
			}
		}
		HullHeader  h;
		std::string err;
		if (!ParseHullHeader(head, h, err)) {
			return false;
		}
		return ((h.flags & kHullFlagHills) != 0) != hills;
	}

	bool ParseHull(Bytes file, HullHeader& h, std::vector<HullRegion>& regions, std::string& err)
	{
		regions.clear();
		if (!ParseHullHeader(file, h, err)) {
			return false;
		}
		std::int64_t  at = kHullHeaderBytes;
		std::uint64_t solid = 0;
		for (std::uint32_t r = 0; r < h.regionCount; ++r) {
			HullRegion    reg;
			std::uint16_t runs = 0;
			if (!Rd(file, at, reg.x) || !Rd(file, at + 4, reg.y) || !Rd(file, at + 8, reg.z) || !Rd(file, at + 12, runs)) {
				err = "hull: region " + std::to_string(r) + " truncated";
				return false;
			}
			if (FloorDiv8(reg.x) * kRegion != reg.x || FloorDiv8(reg.y) * kRegion != reg.y || FloorDiv8(reg.z) * kRegion != reg.z) {
				err = "hull: region " + std::to_string(r) + " not aligned";
				return false;
			}
			at += 14;
			std::size_t i = 0;
			for (std::uint16_t k = 0; k < runs; ++k) {
				std::uint8_t  code = 0;
				std::uint16_t len = 0;
				if (!Rd(file, at, code) || !Rd(file, at + 1, len)) {
					err = "hull: region " + std::to_string(r) + " runs truncated";
					return false;
				}
				at += 3;
				if (code > proto::kDigMaterialCount || len == 0 || i + len > 512) {
					err = "hull: region " + std::to_string(r) + " bad run (code " + std::to_string(code) + ", length " + std::to_string(len) + ")";
					return false;
				}
				std::fill(reg.code.begin() + std::ptrdiff_t(i), reg.code.begin() + std::ptrdiff_t(i + len), code);
				if (code) {
					solid += len;
				}
				i += len;
			}
			if (i != 512) {
				err = "hull: region " + std::to_string(r) + " runs cover " + std::to_string(i) + " blocks";
				return false;
			}
			regions.push_back(reg);
		}
		if (std::uint64_t(at) != file.size) {
			err = "hull: " + std::to_string(file.size - std::uint64_t(at)) + " trailing bytes";
			return false;
		}
		if (solid != h.solidCount) {
			err = "hull: solid count " + std::to_string(solid) + " != header " + std::to_string(h.solidCount);
			return false;
		}
		return true;
	}
}
