// Region voxelizer and kColTris builder: a port of SkyCraft's Collision.cpp (Triangulate,
// SendTriangles, Voxelize; reference/skse/src/Collision.cpp:702-1223) without Skyrim, dig state
// or globals. Pure functions of the RegionJob.
#include <algorithm>

#include "clip.hpp"
#include "internal.hpp"

namespace gmodcraft::mapcol
{
	namespace
	{
		constexpr int   kRegion = static_cast<int>(proto::kColRegionSize);
		constexpr int   kGrid = kRegion * 8;  // sub-voxels per region edge (64)
		constexpr float kSteepMin = kWallNormalY;      // |n.y| below this is a wall: keep it fine-grained
		constexpr float kSteepMax = kWalkableNormalY;  // |n.y| below this (steeper than ~45.6 deg) gets block-coarsened
		constexpr float kPrimMargin = 0.5f;   // sub-voxels; lets thin convex shapes still register

		inline void  Sub(const float* a, const float* b, float* o) { o[0] = a[0] - b[0], o[1] = a[1] - b[1], o[2] = a[2] - b[2]; }
		inline void  Cross(const float* a, const float* b, float* o)
		{
			o[0] = a[1] * b[2] - a[2] * b[1];
			o[1] = a[2] * b[0] - a[0] * b[2];
			o[2] = a[0] * b[1] - a[1] * b[0];
		}
		inline float Dot(const float* a, const float* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

		bool Finite(const float* v, int n)
		{
			for (int i = 0; i < n; ++i) {
				if (!std::isfinite(v[i]) || std::fabs(v[i]) > 1.0e6f) {
					return false;
				}
			}
			return true;
		}

		bool Overlaps(const float* alo, const float* ahi, const float* blo, const float* bhi)
		{
			return alo[0] <= bhi[0] && ahi[0] >= blo[0] && alo[1] <= bhi[1] && ahi[1] >= blo[1] && alo[2] <= bhi[2] && ahi[2] >= blo[2];
		}

		bool AxisTest(const float* v0, const float* v1, const float* v2, const float* axis, float h)
		{
			const float p0 = Dot(v0, axis), p1 = Dot(v1, axis), p2 = Dot(v2, axis);
			const float mn = std::min({ p0, p1, p2 }), mx = std::max({ p0, p1, p2 });
			const float r = h * (std::fabs(axis[0]) + std::fabs(axis[1]) + std::fabs(axis[2]));
			return !(mn > r || mx < -r);
		}

		// Akenine-Moller triangle / box SAT. Box centred at c, half-size h; face normal n.
		bool TriBoxOverlap(const float* c, float h, const float* ta, const float* tb, const float* tc, const float* n)
		{
			float v0[3], v1[3], v2[3];
			Sub(ta, c, v0);
			Sub(tb, c, v1);
			Sub(tc, c, v2);
			for (int i = 0; i < 3; ++i) {
				const float mn = std::min({ v0[i], v1[i], v2[i] }), mx = std::max({ v0[i], v1[i], v2[i] });
				if (mn > h || mx < -h) {
					return false;
				}
			}
			const float d = Dot(n, v0);
			const float r = h * (std::fabs(n[0]) + std::fabs(n[1]) + std::fabs(n[2]));
			if (std::fabs(d) > r) {
				return false;
			}
			float e[3][3];
			Sub(v1, v0, e[0]);
			Sub(v2, v1, e[1]);
			Sub(v0, v2, e[2]);
			static constexpr float kAxes[3][3] = { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };
			for (auto& edge : e) {
				for (auto& unit : kAxes) {
					float axis[3];
					Cross(edge, unit, axis);
					if (!AxisTest(v0, v1, v2, axis, h)) {
						return false;
					}
				}
			}
			return true;
		}

		void WriteHeader(const RegionJob& job, std::uint32_t count, std::size_t recBytes, const void* recs, std::vector<std::uint8_t>& out)
		{
			proto::ColRegion header{};
			header.minX = job.rx * kRegion;
			header.minY = job.ry * kRegion;
			header.minZ = job.rz * kRegion;
			header.maxX = header.minX + kRegion - 1;
			header.maxY = header.minY + kRegion - 1;
			header.maxZ = header.minZ + kRegion - 1;
			header.epoch = job.epoch;
			header.count = count;
			out.resize(sizeof(header) + std::size_t(count) * recBytes);
			std::memcpy(out.data(), &header, sizeof(header));
			if (count) {
				std::memcpy(out.data() + sizeof(header), recs, std::size_t(count) * recBytes);
			}
		}
	}

	// Convex hull from planes: clip a big square on each plane by all the other planes.
	void TriangulateConvex(const McConvex& cvx, std::vector<proto::ColTri>& out)
	{
		const float ex = cvx.hi[0] - cvx.lo[0], ey = cvx.hi[1] - cvx.lo[1], ez = cvx.hi[2] - cvx.lo[2];
		const float diag = std::sqrt(ex * ex + ey * ey + ez * ez) + 1.0f;
		const float mid[3] = { (cvx.lo[0] + cvx.hi[0]) * 0.5f, (cvx.lo[1] + cvx.hi[1]) * 0.5f, (cvx.lo[2] + cvx.hi[2]) * 0.5f };
		auto emit = [&](const float* a, const float* b, const float* c, const float* outward) {
			proto::ColTri t{ { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] }, cvx.flags };
			float e1[3], e2[3], n[3];
			Sub(b, a, e1);
			Sub(c, a, e2);
			Cross(e1, e2, n);
			if (Dot(n, outward) < 0.0f) {
				std::swap_ranges(t.v + 3, t.v + 6, t.v + 6);
			}
			out.push_back(t);
		};
		for (std::size_t i = 0; i < cvx.planes.size(); ++i) {
			const auto& pl = cvx.planes[i];
			const float n[3] = { pl[0], pl[1], pl[2] };
			const float nl = std::sqrt(Dot(n, n));
			if (nl < 1e-6f) {
				continue;
			}
			const float dist = (Dot(n, mid) + pl[3]) / (nl * nl);
			const float o[3] = { mid[0] - n[0] * dist, mid[1] - n[1] * dist, mid[2] - n[2] * dist };
			const float ref[3] = { std::fabs(n[1]) < 0.9f * nl ? 0.0f : 1.0f, std::fabs(n[1]) < 0.9f * nl ? 1.0f : 0.0f, 0.0f };
			float       t1[3], t2[3];
			Cross(ref, n, t1);
			const float lt = std::sqrt(Dot(t1, t1));
			for (int k = 0; k < 3; ++k) {
				t1[k] /= lt;
			}
			Cross(n, t1, t2);
			const float l2 = std::sqrt(Dot(t2, t2));
			for (int k = 0; k < 3; ++k) {
				t2[k] /= l2;
			}
			std::vector<std::array<float, 3>> poly;
			const float                       sgn1[4] = { -1, 1, 1, -1 }, sgn2[4] = { -1, -1, 1, 1 };
			for (int q = 0; q < 4; ++q) {
				const float s1 = sgn1[q] * diag, s2 = sgn2[q] * diag;
				poly.push_back({ o[0] + t1[0] * s1 + t2[0] * s2, o[1] + t1[1] * s1 + t2[1] * s2, o[2] + t1[2] * s1 + t2[2] * s2 });
			}
			for (std::size_t j = 0; j < cvx.planes.size() && poly.size() >= 3; ++j) {
				if (j == i) {
					continue;
				}
				const auto&                       cp = cvx.planes[j];
				std::vector<std::array<float, 3>> clipped;
				for (std::size_t v = 0; v < poly.size(); ++v) {
					const auto& A = poly[v];
					const auto& B = poly[(v + 1) % poly.size()];
					const float da = cp[0] * A[0] + cp[1] * A[1] + cp[2] * A[2] + cp[3];
					const float db = cp[0] * B[0] + cp[1] * B[1] + cp[2] * B[2] + cp[3];
					if (da <= 0.0f) {
						clipped.push_back(A);
					}
					if ((da <= 0.0f) != (db <= 0.0f)) {
						const float t = da / (da - db);
						clipped.push_back({ A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t });
					}
				}
				poly.swap(clipped);
			}
			for (std::size_t v = 1; v + 1 < poly.size(); ++v) {
				emit(poly[0].data(), poly[v].data(), poly[v + 1].data(), n);
			}
		}
	}

	void BuildColTrisPayload(const RegionJob& job, std::vector<std::uint8_t>& out)
	{
		const float lo[3] = { float(job.rx * kRegion) - 0.5f, float(job.ry * kRegion) - 0.5f, float(job.rz * kRegion) - 0.5f };
		const float hi[3] = { lo[0] + kRegion + 1.0f, lo[1] + kRegion + 1.0f, lo[2] + kRegion + 1.0f };
		std::vector<proto::ColTri> tris;
		tris.reserve(job.tris.size() + job.convexFaces.size() + job.helperTris.size());
		auto add = [&](const proto::ColTri& t, std::uint32_t flags) {
			float tlo[3], thi[3];
			for (int k = 0; k < 3; ++k) {
				tlo[k] = std::min({ t.v[k], t.v[3 + k], t.v[6 + k] });
				thi[k] = std::max({ t.v[k], t.v[3 + k], t.v[6 + k] });
			}
			if (Overlaps(tlo, thi, lo, hi) && Finite(t.v, 9)) {
				proto::ColTri c = t;
				c.flags = flags;
				tris.push_back(c);
			}
		};
		// Dug blocks merged into boxes once; diggable triangles touching one are cut.
		const std::vector<clip::Box> boxes = clip::Merge(job.dug);
		std::vector<clip::Box>       nearBoxes;
		std::vector<clip::Poly>      pieces;
		auto addSolid = [&](const proto::ColTri& t) {
			if ((t.flags & proto::kTriDiggable) && !(t.flags & proto::kTriGhost) && !boxes.empty() && Finite(t.v, 9)) {
				float tlo[3], thi[3];
				for (int k = 0; k < 3; ++k) {
					tlo[k] = std::min({ t.v[k], t.v[3 + k], t.v[6 + k] });
					thi[k] = std::max({ t.v[k], t.v[3 + k], t.v[6 + k] });
				}
				nearBoxes.clear();
				for (const auto& b : boxes) {
					if (thi[0] >= b.lo[0] && tlo[0] <= b.hi[0] && thi[1] >= b.lo[1] && tlo[1] <= b.hi[1] && thi[2] >= b.lo[2] && tlo[2] <= b.hi[2]) {
						nearBoxes.push_back(b);
					}
				}
				if (!nearBoxes.empty()) {
					add(t, t.flags | proto::kTriGhost);  // the surface as it was: what's behind it is solid
					pieces.clear();
					clip::Subtract(clip::FromTriangle(t.v, t.v + 3, t.v + 6), nearBoxes, pieces);
					for (const auto& piece : pieces) {
						for (std::size_t v = 1; v + 1 < piece.size(); ++v) {
							proto::ColTri part{ { piece[0].p[0], piece[0].p[1], piece[0].p[2], piece[v].p[0], piece[v].p[1], piece[v].p[2],
													piece[v + 1].p[0], piece[v + 1].p[1], piece[v + 1].p[2] },
								t.flags };
							add(part, part.flags);
						}
					}
					return;
				}
			}
			add(t, t.flags);
		};
		for (const auto& t : job.tris) {
			addSolid(t);
		}
		if (job.convexFaces.empty()) {
			std::vector<proto::ColTri> faces;
			for (const auto& c : job.convexes) {
				TriangulateConvex(c, faces);
			}
			for (const auto& t : faces) {
				addSolid(t);
			}
		} else {
			for (const auto& t : job.convexFaces) {
				addSolid(t);
			}
		}
		for (const auto& t : job.helperTris) {
			add(t, proto::kTriStairHelper);
		}
		WriteHeader(job, static_cast<std::uint32_t>(tris.size()), sizeof(proto::ColTri), tris.data(), out);
	}

	void BuildColRegionPayload(const RegionJob& job, std::vector<std::uint8_t>& out)
	{
		constexpr int              G = kGrid;
		std::vector<std::uint64_t> solid(G * G, 0), steep(G * G, 0);
		std::vector<std::uint64_t> digSolid(G * G, 0), digSteep(G * G, 0);  // diggable geometry
		auto set = [&](std::vector<std::uint64_t>& grid, int x, int y, int z) { grid[std::size_t(y) * G + z] |= 1ull << x; };

		const float ox = float(job.rx * kRegion), oy = float(job.ry * kRegion), oz = float(job.rz * kRegion);
		auto        toVoxel = [&](const float* mc, float* o) {
            o[0] = (mc[0] - ox) * 8.0f;
            o[1] = (mc[1] - oy) * 8.0f;
            o[2] = (mc[2] - oz) * 8.0f;
		};
		auto clampLo = [](float v) { return std::clamp(static_cast<int>(std::floor(std::clamp(v, -1e6f, 1e6f))), 0, G - 1); };
		auto clampHi = [](float v) { return std::clamp(static_cast<int>(std::ceil(std::clamp(v, -1e6f, 1e6f))) - 1, 0, G - 1); };

		// Triangles: plane-guided SAT test so big triangles cost O(area) instead of O(volume).
		// steepOnly: only steep surfaces count (convex faces: containment already fills the solid).
		auto raster = [&](const proto::ColTri& tri, bool steepOnly) {
			if (!Finite(tri.v, 9)) {
				return;
			}
			float a[3], b[3], c[3];
			toVoxel(tri.v, a);
			toVoxel(tri.v + 3, b);
			toVoxel(tri.v + 6, c);
			float lo[3], hi[3];
			for (int i = 0; i < 3; ++i) {
				lo[i] = std::min({ a[i], b[i], c[i] });
				hi[i] = std::max({ a[i], b[i], c[i] });
			}
			if (hi[0] < 0 || hi[1] < 0 || hi[2] < 0 || lo[0] > G || lo[1] > G || lo[2] > G) {
				return;
			}
			float e1[3], e2[3], n[3];
			Sub(b, a, e1);
			Sub(c, a, e2);
			Cross(e1, e2, n);
			const float len = std::sqrt(Dot(n, n));
			if (len < 1e-9f) {
				return;
			}
			n[0] /= len, n[1] /= len, n[2] /= len;
			const float ny = std::fabs(n[1]);
			const bool  flat = ny >= kSteepMax || ny < kSteepMin;
			if (flat && steepOnly) {
				return;
			}
			auto&       grid = (tri.flags & proto::kTriDiggable) ? (flat ? digSolid : digSteep) : (flat ? solid : steep);

			int dom = 0;
			if (std::fabs(n[1]) > std::fabs(n[dom])) dom = 1;
			if (std::fabs(n[2]) > std::fabs(n[dom])) dom = 2;
			const int   u = (dom + 1) % 3, v = (dom + 2) % 3;
			const float d = Dot(n, a);
			const float r = 0.5f * (std::fabs(n[0]) + std::fabs(n[1]) + std::fabs(n[2]));
			const int   iu0 = clampLo(lo[u]), iu1 = clampHi(hi[u]), iv0 = clampLo(lo[v]), iv1 = clampHi(hi[v]);
			// Half a sub-voxel of slack along the normal: a flat triangle lying exactly on a sub-voxel
			// boundary (a Source floor at a multiple of 5 units) has lo == hi there, and the SAT test
			// below includes the cubes on both sides; without the slack the range was empty
			// (SkyCraft's voxelizer dropped such triangles).
			const int   id0 = clampLo(lo[dom] - 0.5f), id1 = clampHi(hi[dom] + 0.5f);
			for (int iu = iu0; iu <= iu1; ++iu) {
				for (int iv = iv0; iv <= iv1; ++iv) {
					const float cu = iu + 0.5f, cv = iv + 0.5f;
					const float s0 = (d - r - n[u] * cu - n[v] * cv) / n[dom];
					const float s1 = (d + r - n[u] * cu - n[v] * cv) / n[dom];
					// Clamped before the casts: a near-degenerate n[dom] can't make them overflow.
					const float f0 = std::clamp(std::min(s0, s1) - 0.5f, -1.0f, float(G));
					const float f1 = std::clamp(std::max(s0, s1) - 0.5f, -1.0f, float(G));
					const int   a0 = std::max(id0, static_cast<int>(std::floor(f0)));
					const int   a1 = std::min(id1, static_cast<int>(std::ceil(f1)));
					for (int id = a0; id <= a1; ++id) {
						float cen[3];
						cen[dom] = id + 0.5f;
						cen[u] = cu;
						cen[v] = cv;
						if (TriBoxOverlap(cen, 0.5f, a, b, c, n)) {
							int p[3];
							p[dom] = id, p[u] = iu, p[v] = iv;
							set(grid, p[0], p[1], p[2]);
						}
					}
				}
			}
		};
		for (const auto& tri : job.tris) {
			raster(tri, false);
		}
		// Convex faces: the steep ones (a ramp brush's top, a rock's flank) go into the steep grid
		// too, so they are coarsened like triangles; the solid under them comes from containment.
		if (job.convexFaces.empty()) {
			std::vector<proto::ColTri> faces;
			for (const auto& c : job.convexes) {
				TriangulateConvex(c, faces);
			}
			for (const auto& f : faces) {
				raster(f, true);
			}
		} else {
			for (const auto& f : job.convexFaces) {
				raster(f, true);
			}
		}

		// Convex primitives: sub-voxel-centre containment with a small margin.
		constexpr float m = kPrimMargin / 8.0f;
		for (const auto& cvx : job.convexes) {
			auto& grid = (cvx.flags & proto::kTriDiggable) ? digSolid : solid;
			if (!Finite(cvx.lo, 3) || !Finite(cvx.hi, 3)) {
				continue;
			}
			float lo[3], hi[3];
			toVoxel(cvx.lo, lo);
			toVoxel(cvx.hi, hi);
			if (hi[0] < 0 || hi[1] < 0 || hi[2] < 0 || lo[0] > G || lo[1] > G || lo[2] > G) {
				continue;
			}
			for (int y = clampLo(lo[1] - 1); y <= clampHi(hi[1] + 1); ++y) {
				for (int z = clampLo(lo[2] - 1); z <= clampHi(hi[2] + 1); ++z) {
					for (int x = clampLo(lo[0] - 1); x <= clampHi(hi[0] + 1); ++x) {
						const float p[3] = { ox + (x + 0.5f) / 8.0f, oy + (y + 0.5f) / 8.0f, oz + (z + 0.5f) / 8.0f };
						bool        inside = true;
						for (const auto& pl : cvx.planes) {
							if (pl[0] * p[0] + pl[1] * p[1] + pl[2] * p[2] + pl[3] > m) {
								inside = false;
								break;
							}
						}
						if (inside) {
							set(grid, x, y, z);
						}
					}
				}
			}
		}

		// Steep surfaces (|n.y| in [kWallNormalY, kWalkableNormalY), about 45.6-84.3 degrees from
		// horizontal): snap to whole-block footprints so the risers between
		// neighbouring columns exceed MC's 0.6 step height.
		auto coarsen = [&](const std::vector<std::uint64_t>& st, std::vector<std::uint64_t>& so) {
			for (int by = 0; by < kRegion; ++by) {
				for (int bz = 0; bz < kRegion; ++bz) {
					for (int bx = 0; bx < kRegion; ++bx) {
						const std::uint64_t xmask = 0xFFull << (bx * 8);
						int                 minY = 99, maxY = -1;
						for (int y = by * 8; y < by * 8 + 8; ++y) {
							for (int z = bz * 8; z < bz * 8 + 8; ++z) {
								if (st[std::size_t(y) * G + z] & xmask) {
									minY = std::min(minY, y);
									maxY = std::max(maxY, y);
								}
							}
						}
						if (maxY < 0) {
							continue;
						}
						for (int y = minY; y <= maxY; ++y) {
							for (int z = bz * 8; z < bz * 8 + 8; ++z) {
								so[std::size_t(y) * G + z] |= xmask;
							}
						}
					}
				}
			}
		};
		coarsen(steep, solid);
		coarsen(digSteep, digSolid);

		// Dug blocks: the diggable geometry in them is gone.
		for (const auto& cube : job.dug) {
			const int bx = cube[0] - job.rx * kRegion, by = cube[1] - job.ry * kRegion, bz = cube[2] - job.rz * kRegion;
			if (bx < 0 || by < 0 || bz < 0 || bx >= kRegion || by >= kRegion || bz >= kRegion) {
				continue;
			}
			const std::uint64_t keep = ~(0xFFull << (bx * 8));
			for (int y = by * 8; y < by * 8 + 8; ++y) {
				for (int z = bz * 8; z < bz * 8 + 8; ++z) {
					digSolid[std::size_t(y) * G + z] &= keep;
				}
			}
		}
		for (std::size_t i = 0; i < solid.size(); ++i) {
			solid[i] |= digSolid[i];
		}

		std::vector<proto::ColBlock> blocks;
		for (int by = 0; by < kRegion; ++by) {
			for (int bz = 0; bz < kRegion; ++bz) {
				for (int bx = 0; bx < kRegion; ++bx) {
					proto::ColBlock blk{};
					bool            any = false;
					for (int sy = 0; sy < 8; ++sy) {
						std::uint64_t layer = 0;
						for (int sz = 0; sz < 8; ++sz) {
							const auto row = (solid[std::size_t(by * 8 + sy) * G + (bz * 8 + sz)] >> (bx * 8)) & 0xFF;
							layer |= row << (sz * 8);
						}
						blk.bits[sy] = layer;
						any |= layer != 0;
					}
					if (any) {
						blk.x = job.rx * kRegion + bx;
						blk.y = job.ry * kRegion + by;
						blk.z = job.rz * kRegion + bz;
						blocks.push_back(blk);
					}
				}
			}
		}
		WriteHeader(job, static_cast<std::uint32_t>(blocks.size()), sizeof(proto::ColBlock), blocks.data(), out);
	}
}
