// The region voxelizer's triangle rasterizer (sub-voxel SAT, plane-guided), shared by the
// colstream's kColRegion builder (voxelize.cpp) and the hull trace (hull.cpp) so both mark the
// same sub-voxels for the same triangle. Internal to mapcol.
#pragma once

#include <algorithm>
#include <cmath>

#include "gmodcraft_protocol.h"

namespace gmodcraft::mapcol::raster
{
	constexpr int kRegion = static_cast<int>(proto::kColRegionSize);
	constexpr int kGrid = kRegion * 8;  // sub-voxels per region edge (64)

	inline void  Sub(const float* a, const float* b, float* o) { o[0] = a[0] - b[0], o[1] = a[1] - b[1], o[2] = a[2] - b[2]; }
	inline void  Cross(const float* a, const float* b, float* o)
	{
		o[0] = a[1] * b[2] - a[2] * b[1];
		o[1] = a[2] * b[0] - a[0] * b[2];
		o[2] = a[0] * b[1] - a[1] * b[0];
	}
	inline float Dot(const float* a, const float* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

	inline bool Finite(const float* v, int n)
	{
		for (int i = 0; i < n; ++i) {
			if (!std::isfinite(v[i]) || std::fabs(v[i]) > 1.0e6f) {
				return false;
			}
		}
		return true;
	}

	inline bool AxisTest(const float* v0, const float* v1, const float* v2, const float* axis, float h)
	{
		const float p0 = Dot(v0, axis), p1 = Dot(v1, axis), p2 = Dot(v2, axis);
		const float mn = std::min({ p0, p1, p2 }), mx = std::max({ p0, p1, p2 });
		const float r = h * (std::fabs(axis[0]) + std::fabs(axis[1]) + std::fabs(axis[2]));
		return !(mn > r || mx < -r);
	}

	// Akenine-Moller triangle / box SAT. Box centred at c, half-size h; face normal n.
	inline bool TriBoxOverlap(const float* c, float h, const float* ta, const float* tb, const float* tc, const float* n)
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

	inline int ClampLo(float v) { return std::clamp(static_cast<int>(std::floor(std::clamp(v, -1e6f, 1e6f))), 0, kGrid - 1); }
	inline int ClampHi(float v) { return std::clamp(static_cast<int>(std::ceil(std::clamp(v, -1e6f, 1e6f))) - 1, 0, kGrid - 1); }

	// Rasterizes the MC-space triangle tri (9 floats) into the region whose minimum block is
	// `origin`: plane-guided SAT so big triangles cost O(area) instead of O(volume). pick(n) gets
	// the unit normal (sub-voxel space = MC axes) and returns the sink to fill, or nullptr to skip;
	// the sink is called as sink(x, y, z) for every sub-voxel (0..63) the triangle touches.
	template <class Pick>
	void Triangle(const float* tri, const float origin[3], Pick pick)
	{
		if (!Finite(tri, 9)) {
			return;
		}
		constexpr int G = kGrid;
		float a[3], b[3], c[3];
		for (int i = 0; i < 3; ++i) {
			a[i] = (tri[i] - origin[i]) * 8.0f;
			b[i] = (tri[3 + i] - origin[i]) * 8.0f;
			c[i] = (tri[6 + i] - origin[i]) * 8.0f;
		}
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
		auto* sink = pick(n);
		if (sink == nullptr) {
			return;
		}

		int dom = 0;
		if (std::fabs(n[1]) > std::fabs(n[dom])) dom = 1;
		if (std::fabs(n[2]) > std::fabs(n[dom])) dom = 2;
		const int   u = (dom + 1) % 3, v = (dom + 2) % 3;
		const float d = Dot(n, a);
		const float r = 0.5f * (std::fabs(n[0]) + std::fabs(n[1]) + std::fabs(n[2]));
		const int   iu0 = ClampLo(lo[u]), iu1 = ClampHi(hi[u]), iv0 = ClampLo(lo[v]), iv1 = ClampHi(hi[v]);
		// Half a sub-voxel of slack along the normal: a flat triangle lying exactly on a sub-voxel
		// boundary (a Source floor at a multiple of 5 units) has lo == hi there, and the SAT test
		// below includes the cubes on both sides; without the slack the range was empty
		// (SkyCraft's voxelizer dropped such triangles).
		const int   id0 = ClampLo(lo[dom] - 0.5f), id1 = ClampHi(hi[dom] + 0.5f);
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
						(*sink)(p[0], p[1], p[2]);
					}
				}
			}
		}
	}
}
