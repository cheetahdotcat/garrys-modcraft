// Dynamic entities (P2c): per-model collision in model space and its placement per entity.
#include <algorithm>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	using namespace detail;

	namespace
	{
		constexpr float kUnits = static_cast<float>(proto::kUnitsPerBlock);
		constexpr float kRadToDeg = 180.0f / 3.14159265358979323846f;

		// Every triangle and convex: kSrcDynamic, no material, model-space owner -1.
		void MakeDynamic(Mesh& m)
		{
			for (auto& t : m.tris) {
				t.kind = kSrcDynamic;
				t.material = 0;
				t.owner = -1;
				t.bits = 0;
			}
			for (auto& c : m.convexes) {
				c.kind = kSrcDynamic;
				c.material = 0;
				c.owner = -1;
				c.bits = 0;
			}
		}
	}

	bool DynamicFromPhy(Bytes phy, Mesh& out, std::string& surfaceprop, std::string& err)
	{
		out = Mesh{};
		surfaceprop.clear();
		MaterialTable mats;
		PhyModel      model;
		if (!DecodePhy(phy, mats, model, err)) {
			return false;
		}
		if (model.solids.empty() || model.solids[0].mesh.convexes.empty()) {
			err = "phy: no convex in solid 0";
			return false;
		}
		surfaceprop = mats[model.solids[0].material].name;
		out = std::move(model.solids[0].mesh);
		MakeDynamic(out);
		return true;
	}

	void DynamicFromMesh(const Mesh& model, Mesh& out)
	{
		out = model;
		MakeDynamic(out);
	}

	bool DynamicFromBox(const Vec3& mins, const Vec3& maxs, Mesh& out)
	{
		out = Mesh{};
		if (!Bounded3(mins) || !Bounded3(maxs) || !(maxs.x - mins.x > 0.01f) || !(maxs.y - mins.y > 0.01f) || !(maxs.z - mins.z > 0.01f)) {
			return false;
		}
		const float id[3][3] = { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };
		Tri         proto;
		proto.kind = kSrcDynamic;
		BoxTris(mins, maxs, id, Vec3{}, proto, out.tris);
		if (!FinishConvex(out, nullptr, 0, kSrcDynamic, 0, -1)) {
			out = Mesh{};
			return false;
		}
		return true;
	}

	bool DynamicFromTriangles(const std::vector<std::vector<Vec3>>& convexes, Mesh& out, std::string& err)
	{
		out = Mesh{};
		std::size_t dropped = 0;
		for (const auto& list : convexes) {
			if (list.size() < 12 || list.size() % 3 != 0 ||
				!std::all_of(list.begin(), list.end(), [](const Vec3& v) { return Bounded3(v); })) {
				++dropped;
				continue;
			}
			Vec3 c{};
			for (const auto& v : list) {
				c.x += v.x, c.y += v.y, c.z += v.z;
			}
			const float inv = 1.0f / static_cast<float>(list.size());
			c = Vec3{ c.x * inv, c.y * inv, c.z * inv };
			const auto first = static_cast<std::uint32_t>(out.tris.size());
			for (std::size_t i = 0; i + 2 < list.size(); i += 3) {
				Tri t;
				t.kind = kSrcDynamic;
				t.v[0] = list[i];
				t.v[1] = list[i + 1];
				t.v[2] = list[i + 2];
				const Vec3 n = Cross3(Sub3(t.v[1], t.v[0]), Sub3(t.v[2], t.v[0]));
				if (!(Dot3(n, n) > 1e-10f)) {
					continue;
				}
				const Vec3 mid{ (t.v[0].x + t.v[1].x + t.v[2].x) / 3 - c.x, (t.v[0].y + t.v[1].y + t.v[2].y) / 3 - c.y,
					(t.v[0].z + t.v[1].z + t.v[2].z) / 3 - c.z };
				if (Dot3(n, mid) < 0) {
					std::swap(t.v[1], t.v[2]);
				}
				out.tris.push_back(t);
			}
			if (out.tris.size() == first || !FinishConvex(out, nullptr, first, kSrcDynamic, 0, -1)) {
				out.tris.resize(first);
				++dropped;
			}
		}
		if (out.convexes.empty()) {
			err = "no usable convex (" + std::to_string(dropped) + " dropped)";
			return false;
		}
		return true;
	}

	bool PlaceDynamic(const Mesh& model, const Vec3& origin, const Vec3& angles, std::int32_t owner, Mesh& out)
	{
		if (!Bounded3(origin) || !Finite3(angles) || std::fabs(angles.x) > 1e5f || std::fabs(angles.y) > 1e5f || std::fabs(angles.z) > 1e5f) {
			return false;
		}
		float m[3][3];
		AngleMatrix(angles, m);
		const std::size_t tris0 = out.tris.size(), planes0 = out.planes.size(), convexes0 = out.convexes.size();
		auto rollback = [&] {
			out.tris.resize(tris0);
			out.planes.resize(planes0);
			out.convexes.resize(convexes0);
			return false;
		};
		const Vec3 zero{};
		for (const Tri& t0 : model.tris) {
			if (t0.convex >= 0) {
				continue;  // with its convex below
			}
			Tri t = t0;
			for (auto& v : t.v) {
				v = Rotate(m, v, origin);
				if (!Bounded3(v)) {
					return rollback();
				}
			}
			t.owner = owner;
			out.tris.push_back(t);
		}
		for (const Convex& c0 : model.convexes) {
			if (c0.firstTri + std::uint64_t(c0.triCount) > model.tris.size() || c0.firstPlane + std::uint64_t(c0.planeCount) > model.planes.size()) {
				return rollback();
			}
			Convex c = c0;
			c.firstTri = static_cast<std::uint32_t>(out.tris.size());
			c.firstPlane = static_cast<std::uint32_t>(out.planes.size());
			c.owner = owner;
			c.bits = static_cast<std::uint8_t>(c0.bits & ~kBitThin);
			c.lo = Vec3{ 1e30f, 1e30f, 1e30f };
			c.hi = Vec3{ -1e30f, -1e30f, -1e30f };
			const auto ci = static_cast<std::int32_t>(out.convexes.size());
			for (std::uint32_t i = c0.firstTri; i < c0.firstTri + c0.triCount; ++i) {
				Tri t = model.tris[i];
				for (auto& v : t.v) {
					v = Rotate(m, v, origin);
					if (!Bounded3(v)) {
						return rollback();
					}
					c.lo.x = std::min(c.lo.x, v.x), c.lo.y = std::min(c.lo.y, v.y), c.lo.z = std::min(c.lo.z, v.z);
					c.hi.x = std::max(c.hi.x, v.x), c.hi.y = std::max(c.hi.y, v.y), c.hi.z = std::max(c.hi.z, v.z);
				}
				t.convex = ci;
				t.owner = owner;
				out.tris.push_back(t);
			}
			float thinnest = 1e30f;
			for (std::uint32_t p = c0.firstPlane; p < c0.firstPlane + c0.planeCount; ++p) {
				const Plane& q = model.planes[p];
				const Vec3   n = Rotate(m, Vec3{ q.n[0], q.n[1], q.n[2] }, zero);
				const float  d = q.d - Dot3(n, origin);
				out.planes.push_back(Plane{ { n.x, n.y, n.z }, d });
				// Thickness along this normal: every vertex is on the inner side (n.v + d <= 0),
				// the face's own vertices at 0, the farthest one at -width.
				float deepest = 0.0f;
				for (std::uint32_t i = c.firstTri; i < out.tris.size(); ++i) {
					for (const auto& v : out.tris[i].v) {
						deepest = std::min(deepest, Dot3(n, v) + d);
					}
				}
				thinnest = std::min(thinnest, -deepest);
			}
			if (c.triCount == 0 || c.planeCount < 4) {
				return rollback();
			}
			if (thinnest < kThinBlocks * kUnits) {
				c.bits = static_cast<std::uint8_t>(c.bits | kBitThin);
			}
			out.convexes.push_back(c);
		}
		return true;
	}

	float AngleBetween(const Vec3& a, const Vec3& b)
	{
		float ma[3][3], mb[3][3];
		AngleMatrix(a, ma);
		AngleMatrix(b, mb);
		// trace(ma^T mb) = sum of the dot products of matching columns
		float tr = 0.0f;
		for (int col = 0; col < 3; ++col) {
			for (int row = 0; row < 3; ++row) {
				tr += ma[row][col] * mb[row][col];
			}
		}
		const float cosA = std::clamp((tr - 1.0f) * 0.5f, -1.0f, 1.0f);
		return std::acos(cosA) * kRadToDeg;
	}
}
