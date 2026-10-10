// BSP header, LUMP_PHYSCOLLIDE (29), water, and DecodeMap.
#include <algorithm>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	using namespace detail;

	namespace
	{
		constexpr std::uint32_t kContentsPlayerClip = 0x10000;
		constexpr std::uint32_t kContentsMonsterClip = 0x20000;
		constexpr std::int32_t  kMaxSolids = 1 << 16;

		struct PhysModel
		{
			std::int32_t       index;
			std::vector<Bytes> solids;
			std::string        keydata;
		};

		bool SplitPhysCollide(Bytes lump, std::vector<PhysModel>& out, std::string& err)
		{
			std::int64_t off = 0;
			while (InRange(lump, off, 16)) {
				std::int32_t mi = 0, ds = 0, ks = 0, sc = 0;
				Rd(lump, off, mi);
				Rd(lump, off + 4, ds);
				Rd(lump, off + 8, ks);
				Rd(lump, off + 12, sc);
				off += 16;
				if (mi == -1) {
					return true;
				}
				if (mi < 0 || ds < 0 || ks < 0 || sc < 0 || sc > kMaxSolids || !InRange(lump, off, std::int64_t(ds) + ks)) {
					err = "physcollide: bad model header at " + std::to_string(off - 16);
					return false;
				}
				PhysModel m;
				m.index = mi;
				const Bytes  data = Sub(lump, off, ds);
				std::int64_t p = 0;
				for (std::int32_t j = 0; j < sc; ++j) {
					std::int32_t sz = 0;
					if (!Rd(data, p, sz) || sz < 0 || !InRange(data, p + 4, sz)) {
						err = "physcollide: model " + std::to_string(mi) + " solid " + std::to_string(j) + " out of range";
						return false;
					}
					m.solids.push_back(Sub(data, p + 4, sz));
					p += 4 + std::int64_t(sz);
				}
				const Bytes kd = Sub(lump, off + ds, ks);
				const auto* kdc = reinterpret_cast<const char*>(kd.data);
				m.keydata.assign(kdc, kd.size ? std::find(kdc, kdc + kd.size, '\0') : kdc);
				out.push_back(std::move(m));
				off += std::int64_t(ds) + ks;
			}
			return true;  // no terminator: tolerated
		}

		struct SolidInfo
		{
			bool          fluid = false;
			bool          hasContents = false;
			std::uint32_t contents = 0;
			std::string   surfaceprop;
			bool          hasPlane = false;
			double        plane[4] = {};
		};

		// Reads the keydata blocks; ivpMat gets the materialtable (index -> MaterialTable index).
		bool ParseKeydata(const std::string& kd, std::size_t solidCount, MaterialTable& mats, std::uint16_t (&ivpMat)[128],
			std::vector<SolidInfo>& info, std::string& err)
		{
			const std::uint16_t def = mats.Intern("default");
			std::fill(std::begin(ivpMat), std::end(ivpMat), def);
			info.assign(solidCount, SolidInfo{});
			KvNode root;
			if (!ParseKeyValues(kd.data(), kd.size(), root, err)) {
				return false;
			}
			for (const auto& b : root.children) {
				if (!b.block) {
					continue;
				}
				const std::string key = Lower(b.key);
				if (key == "materialtable") {
					for (const auto& e : b.children) {
						std::int64_t idx = 0;
						if (!e.block && ParseInt(e.value, idx) && idx >= 0 && idx < 128) {
							ivpMat[idx] = mats.Intern(e.key);
						}
					}
					continue;
				}
				if (key != "solid" && key != "staticsolid" && key != "fluid") {
					continue;
				}
				std::int64_t idx = -1;
				if (!ParseInt(b.Get("index", "-1"), idx) || idx < 0 || static_cast<std::size_t>(idx) >= solidCount) {
					continue;
				}
				SolidInfo& s = info[static_cast<std::size_t>(idx)];
				s.fluid = key == "fluid";
				std::int64_t c = 0;
				if (b.Find("contents") && ParseInt(b.Get("contents"), c)) {
					s.hasContents = true;
					s.contents = static_cast<std::uint32_t>(c);
				}
				s.surfaceprop = b.Get("surfaceprop");
				const std::string sp = b.Get("surfaceplane");
				std::size_t       pos = 0;
				s.hasPlane = true;
				for (double& v : s.plane) {
					s.hasPlane = s.hasPlane && ParseFloat(sp, &pos, v);
				}
			}
			return true;
		}

		void AddWater(const Mesh& m, const SolidInfo& info, std::uint16_t material, std::vector<WaterVolume>& out)
		{
			for (const auto& c : m.convexes) {
				WaterVolume w;
				w.planes.assign(m.planes.begin() + c.firstPlane, m.planes.begin() + c.firstPlane + c.planeCount);
				w.lo = c.lo;
				w.hi = c.hi;
				w.surfaceZ = c.hi.z;
				if (info.hasPlane && std::fabs(info.plane[2]) > 0.5) {
					const double z = info.plane[3] / info.plane[2];  // cplane_t: n . p = dist
					if (std::isfinite(z)) {
						w.surfaceZ = static_cast<float>(z);
					}
				}
				w.material = material;
				out.push_back(std::move(w));
			}
		}

		void Append(Mesh& dst, const Mesh& src)
		{
			const auto triBase = static_cast<std::uint32_t>(dst.tris.size());
			const auto planeBase = static_cast<std::uint32_t>(dst.planes.size());
			const auto cvxBase = static_cast<std::int32_t>(dst.convexes.size());
			for (Tri t : src.tris) {
				if (t.convex >= 0) {
					t.convex += cvxBase;
				}
				dst.tris.push_back(t);
			}
			dst.planes.insert(dst.planes.end(), src.planes.begin(), src.planes.end());
			for (Convex c : src.convexes) {
				c.firstTri += triBase;
				c.firstPlane += planeBase;
				dst.convexes.push_back(c);
			}
		}
	}

	bool SplitBsp(Bytes file, BspLumps& out, std::string& err)
	{
		out = BspLumps{};
		char ident[4] = {};
		if (!Rd(file, 0, ident) || std::memcmp(ident, "VBSP", 4) != 0) {
			err = "bsp: not a VBSP file";
			return false;
		}
		Rd(file, 4, out.bspVersion);
		if (out.bspVersion < 19 || out.bspVersion > 20) {
			err = "bsp: unsupported version " + std::to_string(out.bspVersion);
			return false;
		}
		if (!InRange(file, 8, 16 * kLumpCount)) {
			err = "bsp: truncated header";
			return false;
		}
		std::string lzma;
		for (int i = 0; i < kLumpCount; ++i) {
			std::int32_t ofs = 0, len = 0, ver = 0;
			Rd(file, 8 + 16 * i, ofs);
			Rd(file, 12 + 16 * i, len);
			Rd(file, 16 + 16 * i, ver);
			out.version[i] = static_cast<std::uint32_t>(ver);
			out.fileOffset[i] = ofs;
			if (len == 0) {
				continue;
			}
			if (!InRange(file, ofs, len)) {
				err = "bsp: lump " + std::to_string(i) + " outside the file";
				return false;
			}
			Bytes b = Sub(file, ofs, len);
			if (b.size >= 4 && std::memcmp(b.data, "LZMA", 4) == 0) {
				lzma += (lzma.empty() ? "" : ",") + std::to_string(i);
				continue;
			}
			out.lump[i] = b;
		}
		if (!lzma.empty()) {
			err = "bsp: LZMA-compressed lumps unsupported, left empty: " + lzma;
		}
		return true;
	}

	bool DecodeMap(const BspLumps& lumps, const DecodeOptions& opts, MapCollision& out, std::string& err)
	{
		const Bytes pc = lumps.lump[kLumpPhysCollide];
		if (!pc.size) {
			err = "physcollide: lump missing or empty";
			return false;
		}
		std::vector<PhysModel> models;
		if (!SplitPhysCollide(pc, models, err)) {
			return false;
		}
		out.stats.physModels = static_cast<std::uint32_t>(models.size());
		const bool wantPolysoup = opts.disp == DispSource::kAuto || opts.disp == DispSource::kPolysoup;
		bool       sawModel0 = false;
		Mesh       polysoup;
		for (const auto& m : models) {
			if (m.index != 0 && !opts.brushModels) {
				continue;
			}
			std::uint16_t          ivpMat[128];
			std::vector<SolidInfo> info;
			if (!ParseKeydata(m.keydata, m.solids.size(), out.materials, ivpMat, info, err)) {
				err = "physcollide model " + std::to_string(m.index) + ": " + err;
				return false;
			}
			if (m.index != 0) {
				Mesh& mesh = out.brushModels[m.index];
				for (std::size_t j = 0; j < m.solids.size(); ++j) {
					if (info[j].fluid) {
						continue;  // func_water_analog etc.: not collision
					}
					if (!DecodeIvpSolid(m.solids[j], ivpMat, kSrcBrushModel, false, mesh, nullptr, err)) {
						err = "physcollide model " + std::to_string(m.index) + " solid " + std::to_string(j) + ": " + err;
						return false;
					}
				}
				continue;
			}
			if (sawModel0) {
				err = "physcollide: model 0 listed twice";
				return false;
			}
			sawModel0 = true;
			for (std::size_t j = 0; j < m.solids.size(); ++j) {
				const SolidInfo& si = info[j];
				const std::string where = "physcollide solid " + std::to_string(j) + ": ";
				if (si.fluid) {
					std::uint16_t fluidMat[128];
					std::copy(std::begin(ivpMat), std::end(ivpMat), std::begin(fluidMat));
					const std::uint16_t wm = out.materials.Intern(si.surfaceprop.empty() ? "water" : si.surfaceprop);
					fluidMat[0] = wm;
					Mesh tmp;
					if (!DecodeIvpSolid(m.solids[j], fluidMat, kSrcWater, false, tmp, nullptr, err)) {
						err = where + err;
						return false;
					}
					AddWater(tmp, si, wm, out.water);
					++out.stats.fluidSolids;
					continue;
				}
				bool hasContents = si.hasContents;
				if (!hasContents && j == 0) {
					hasContents = true;  // keydata without a "contents" key: vbsp's first solid is MASK_SOLID
				}
				if (hasContents) {
					std::uint8_t kind = kSrcWorld;
					if (si.contents & kContentsPlayerClip) {
						kind = kSrcPlayerClip;
					} else if (si.contents & kContentsMonsterClip) {
						kind = kSrcMonsterClip;
					}
					Mesh tmp;
					if (!DecodeIvpSolid(m.solids[j], ivpMat, kind, false, tmp, &out.stats, err)) {
						err = where + err;
						return false;
					}
					if (kind == kSrcMonsterClip && !opts.keepMonsterClip) {
						out.stats.monsterClipDroppedTris += static_cast<std::uint32_t>(tmp.tris.size());
						continue;
					}
					if (kind == kSrcPlayerClip) {
						out.stats.clipConvexes += static_cast<std::uint32_t>(tmp.convexes.size());
						out.stats.clipTris += static_cast<std::uint32_t>(tmp.tris.size());
					} else if (kind == kSrcWorld) {
						out.stats.worldConvexes += static_cast<std::uint32_t>(tmp.convexes.size());
						out.stats.worldTris += static_cast<std::uint32_t>(tmp.tris.size());
					}
					Append(out.world, tmp);
					continue;
				}
				// staticsolid without contents: a displacement polysoup.
				if (!DecodeIvpSolid(m.solids[j], ivpMat, kSrcDisplacement, true, polysoup, &out.stats, err)) {
					err = where + err;
					return false;
				}
			}
		}
		if (!sawModel0) {
			err = "physcollide: no model 0";
			return false;
		}
		for (auto& t : polysoup.tris) {
			t.kind = kSrcDisplacement;
			t.convex = -1;
		}
		out.stats.polysoupTris = static_cast<std::uint32_t>(polysoup.tris.size());
		const bool rebuild = opts.disp == DispSource::kRebuild || (opts.disp == DispSource::kAuto && polysoup.tris.empty());
		if (wantPolysoup && !rebuild) {
			out.world.tris.insert(out.world.tris.end(), polysoup.tris.begin(), polysoup.tris.end());
			out.stats.dispTris = static_cast<std::uint32_t>(polysoup.tris.size());
		} else if (rebuild) {
			std::vector<Tri> disp;
			if (!RebuildDisplacements(lumps, opts.texProps, out.materials, disp, out.stats, err)) {
				return false;
			}
			out.world.tris.insert(out.world.tris.end(), disp.begin(), disp.end());
			out.stats.dispRebuilt = true;
		}

		// LEAFWATERDATA: float surfaceZ, float minZ, short texinfo, 2 pad.
		const Bytes lw = lumps.lump[kLumpLeafWaterData];
		std::size_t n = 0;
		if (!Count(lw, 12, n)) {
			err = "leafwaterdata: size not a multiple of 12";
			return false;
		}
		for (std::size_t i = 0; i < n; ++i) {
			LeafWater w{};
			Rd(lw, std::int64_t(i) * 12, w.surfaceZ);
			Rd(lw, std::int64_t(i) * 12 + 4, w.minZ);
			Rd(lw, std::int64_t(i) * 12 + 8, w.texinfo);
			out.leafWater.push_back(w);
		}
		return true;
	}

	void ResolveMaterials(MapCollision& map, const SurfaceProps& props)
	{
		map.materials.Resolve(props);
		auto redo = [&](Mesh& mesh) {
			for (auto& c : mesh.convexes) {
				std::uint32_t counts[2] = { 0, 0 };
				std::uint16_t pick[2] = { c.material, c.material };
				// tiny maps per convex: faces are few (<= ~50), so a linear scan is fine
				std::vector<std::pair<std::uint16_t, std::uint32_t>> hist;
				for (std::uint32_t i = c.firstTri; i < c.firstTri + c.triCount && i < mesh.tris.size(); ++i) {
					const std::uint16_t m = mesh.tris[i].material;
					auto it = std::find_if(hist.begin(), hist.end(), [&](const auto& e) { return e.first == m; });
					if (it == hist.end()) {
						hist.emplace_back(m, 1);
					} else {
						++it->second;
					}
				}
				for (const auto& [m, cnt] : hist) {
					const int dig = m < map.materials.Size() && map.materials[m].diggable ? 1 : 0;
					if (cnt > counts[dig]) {
						counts[dig] = cnt;
						pick[dig] = m;
					}
				}
				c.material = counts[1] ? pick[1] : pick[0];
			}
		};
		redo(map.world);
		for (auto& [i, mesh] : map.brushModels) {
			redo(mesh);
		}
	}

	bool WaterSurfaceAt(const MapCollision& map, float x, float y, float nearZ, float& surfaceZ)
	{
		bool  found = false;
		float best = 0;
		for (const auto& w : map.water) {
			if (x < w.lo.x || x > w.hi.x || y < w.lo.y || y > w.hi.y) {
				continue;
			}
			// Inside the footprint: test at a height inside the volume, as near nearZ as possible.
			const float z = std::min(std::max(nearZ, w.lo.z + 0.01f), std::max(w.lo.z, std::min(w.hi.z, w.surfaceZ) - 0.01f));
			bool        inside = true;
			for (const auto& p : w.planes) {
				if (p.n[0] * x + p.n[1] * y + p.n[2] * z + p.d > 0.01f) {
					inside = false;
					break;
				}
			}
			if (!inside) {
				continue;
			}
			if (!found || std::fabs(w.surfaceZ - nearZ) < std::fabs(best - nearZ)) {
				best = w.surfaceZ;
				found = true;
			}
		}
		if (found) {
			surfaceZ = best;
		}
		return found;
	}
}
