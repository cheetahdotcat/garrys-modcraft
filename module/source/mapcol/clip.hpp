// Cutting dug Minecraft blocks (unit cubes) out of host triangles: what's left of a triangle
// outside every cube, as convex polygons. MC block space. Port of SkyCraft's Clip.h
// (reference/skse/src/Clip.h, MIT) to C++17 / GCC 8. Internal to mapcol.
#pragma once

#include <algorithm>
#include <array>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <tuple>
#include <unordered_set>
#include <vector>

namespace gmodcraft::mapcol::clip
{
	struct Vert
	{
		float p[3];
	};
	using Poly = std::vector<Vert>;       // convex, in winding order
	using Cube = std::array<int, 3>;      // min corner of a dug block

	// Dug blocks merged into boxes (float bounds, slop included).
	struct Box
	{
		float lo[3];
		float hi[3];
	};

	// Faces lying exactly on a dug block's face go with the block: the ground's surface at a whole
	// block height belongs to the block under it.
	inline constexpr float kSlop = 1.0e-4f;

	inline Vert Lerp(const Vert& a, const Vert& b, float t)
	{
		Vert v;
		for (int i = 0; i < 3; ++i) {
			v.p[i] = a.p[i] + (b.p[i] - a.p[i]) * t;
		}
		return v;
	}

	// The parts of `in` below and above the plane p[axis] = value (either may come back empty).
	inline void Split(const Poly& in, int axis, float value, Poly& below, Poly& above)
	{
		below.clear();
		above.clear();
		const std::size_t n = in.size();
		for (std::size_t i = 0; i < n; ++i) {
			const Vert& a = in[i];
			const Vert& b = in[(i + 1) % n];
			const float da = a.p[axis] - value;
			const float db = b.p[axis] - value;
			if (da <= 0.0f) {
				below.push_back(a);
			}
			if (da >= 0.0f) {
				above.push_back(a);
			}
			if ((da < 0.0f && db > 0.0f) || (da > 0.0f && db < 0.0f)) {
				Vert m = Lerp(a, b, da / (da - db));
				m.p[axis] = value;
				below.push_back(m);
				above.push_back(m);
			}
		}
		if (below.size() < 3) {
			below.clear();
		}
		if (above.size() < 3) {
			above.clear();
		}
	}

	inline float Area2(const Poly& poly)
	{
		float n[3] = { 0, 0, 0 };
		for (std::size_t i = 1; i + 1 < poly.size(); ++i) {
			const float* o = poly[0].p;
			const float  u[3] = { poly[i].p[0] - o[0], poly[i].p[1] - o[1], poly[i].p[2] - o[2] };
			const float  w[3] = { poly[i + 1].p[0] - o[0], poly[i + 1].p[1] - o[1], poly[i + 1].p[2] - o[2] };
			n[0] += u[1] * w[2] - u[2] * w[1];
			n[1] += u[2] * w[0] - u[0] * w[2];
			n[2] += u[0] * w[1] - u[1] * w[0];
		}
		return std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
	}

	inline std::uint64_t CubeKey(int x, int y, int z)
	{
		return (std::uint64_t(std::uint32_t(x) & 0x1FFFFF) << 42) | (std::uint64_t(std::uint32_t(y) & 0x1FFFFF) << 21) |
		       (std::uint32_t(z) & 0x1FFFFF);
	}

	// Greedy merge: as far as it goes along x, then z (whole rows), then y (whole layers).
	inline std::vector<Box> Merge(const std::vector<Cube>& cubes)
	{
		std::vector<Box> out;
		if (cubes.empty()) {
			return out;
		}
		std::unordered_set<std::uint64_t> left;
		left.reserve(cubes.size() * 2);
		for (const auto& c : cubes) {
			left.insert(CubeKey(c[0], c[1], c[2]));
		}
		auto has = [&](int x, int y, int z) { return left.count(CubeKey(x, y, z)) != 0; };
		std::vector<Cube> order(cubes);
		std::sort(order.begin(), order.end(),
			[](const Cube& a, const Cube& b) { return std::tie(a[1], a[2], a[0]) < std::tie(b[1], b[2], b[0]); });
		for (const auto& c : order) {
			if (!has(c[0], c[1], c[2])) {
				continue;
			}
			int x1 = c[0], z1 = c[2], y1 = c[1];
			while (has(x1 + 1, c[1], c[2])) {
				++x1;
			}
			for (bool grow = true; grow;) {
				for (int x = c[0]; x <= x1 && grow; ++x) {
					grow = has(x, c[1], z1 + 1);
				}
				z1 += grow ? 1 : 0;
			}
			for (bool grow = true; grow;) {
				for (int z = c[2]; z <= z1 && grow; ++z) {
					for (int x = c[0]; x <= x1 && grow; ++x) {
						grow = has(x, y1 + 1, z);
					}
				}
				y1 += grow ? 1 : 0;
			}
			for (int y = c[1]; y <= y1; ++y) {
				for (int z = c[2]; z <= z1; ++z) {
					for (int x = c[0]; x <= x1; ++x) {
						left.erase(CubeKey(x, y, z));
					}
				}
			}
			out.push_back({ { float(c[0]) - kSlop, float(c[1]) - kSlop, float(c[2]) - kSlop },
				{ float(x1 + 1) + kSlop, float(y1 + 1) + kSlop, float(z1 + 1) + kSlop } });
		}
		return out;
	}

	inline bool Touches(const Poly& poly, const Box& box)
	{
		for (int axis = 0; axis < 3; ++axis) {
			float lo = FLT_MAX, hi = -FLT_MAX;
			for (const auto& v : poly) {
				lo = std::min(lo, v.p[axis]);
				hi = std::max(hi, v.p[axis]);
			}
			if (hi < box.lo[axis] || lo > box.hi[axis]) {
				return false;
			}
		}
		return true;
	}

	// `in` minus every box, as convex pieces appended to `out`. Returns false if nothing was cut
	// (`out` then holds `in` unchanged).
	inline bool Subtract(const Poly& in, const std::vector<Box>& boxes, std::vector<Poly>& out)
	{
		std::vector<Poly> pieces{ in }, next;
		Poly              below, above, rest;
		bool              cut = false;
		for (const auto& box : boxes) {
			next.clear();
			for (auto& piece : pieces) {
				if (!Touches(piece, box)) {
					next.push_back(std::move(piece));
					continue;
				}
				cut = true;
				rest = std::move(piece);
				for (int axis = 0; axis < 3; ++axis) {
					Split(rest, axis, box.lo[axis], below, above);
					if (!below.empty()) {
						next.push_back(below);
					}
					rest.swap(above);
					if (rest.empty()) {
						break;
					}
					Split(rest, axis, box.hi[axis], below, above);
					if (!above.empty()) {
						next.push_back(above);
					}
					rest.swap(below);
					if (rest.empty()) {
						break;
					}
				}
				// Whatever is left is inside the box: gone.
			}
			pieces.swap(next);
			if (pieces.empty()) {
				break;
			}
		}
		for (auto& piece : pieces) {
			if (!cut || Area2(piece) > 1.0e-7f) {
				out.push_back(std::move(piece));
			}
		}
		return cut;
	}

	inline Poly FromTriangle(const float* a, const float* b, const float* c)
	{
		Poly poly(3);
		for (int i = 0; i < 3; ++i) {
			poly[0].p[i] = a[i];
			poly[1].p[i] = b[i];
			poly[2].p[i] = c[i];
		}
		return poly;
	}
}
