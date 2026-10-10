// IVP compact surface decoding (PHYSCOLLIDE solids and .phy solids).
//
// Layout (verified on gm_construct / gm_flatgrass):
//   [VPHY header 28 B: 'VPHY', short 0x100, short modelType, int surfaceSize, float drag[3], int axisMapSize]
//   compact surface 48 B: massCenter[3], rotInertia[3], upperLimitRadius, u32 maxDev:8|byteSize:24,
//                          int offsetLedgetreeRoot, int dummy[3] ('IVPS' at +44)
//   ledge tree node 28 B: int offsetRightNode (0 = leaf), int offsetCompactLedge, center[3], radius, box[4]
//                          (left child at node + 28; offsets relative to the node)
//   compact ledge 16 B:   int pointOffset (relative to the ledge), int clientData, u32 flags, short nTris, short
//   triangle 16 B:        u32 triIndex:12|pierce:12|material:7|virtual:1, 3 x u32 edge startPoint:16|opp:15|virt:1
//   points: float4 in IVP metres at ledge + pointOffset + 16 * index.
#include <algorithm>
#include <map>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	namespace detail
	{
		bool FinishConvex(Mesh& mesh, const MaterialTable* mats, std::uint32_t firstTri, std::uint8_t kind,
			std::uint8_t bits, std::int32_t owner)
		{
			Convex c;
			c.firstTri = firstTri;
			c.triCount = static_cast<std::uint32_t>(mesh.tris.size()) - firstTri;
			c.firstPlane = static_cast<std::uint32_t>(mesh.planes.size());
			c.kind = kind;
			c.bits = bits;
			c.owner = owner;
			c.lo = Vec3{ 1e30f, 1e30f, 1e30f };
			c.hi = Vec3{ -1e30f, -1e30f, -1e30f };
			std::map<std::uint16_t, std::uint32_t> matCount;
			for (std::uint32_t i = firstTri; i < mesh.tris.size(); ++i) {
				const Tri& t = mesh.tris[i];
				for (const auto& v : t.v) {
					c.lo.x = std::min(c.lo.x, v.x), c.lo.y = std::min(c.lo.y, v.y), c.lo.z = std::min(c.lo.z, v.z);
					c.hi.x = std::max(c.hi.x, v.x), c.hi.y = std::max(c.hi.y, v.y), c.hi.z = std::max(c.hi.z, v.z);
				}
				++matCount[t.material];
				Vec3        n = Cross3(Sub3(t.v[1], t.v[0]), Sub3(t.v[2], t.v[0]));
				const float len = std::sqrt(Dot3(n, n));
				if (!(len > 1e-6f)) {
					continue;  // degenerate face: no plane
				}
				n = Vec3{ n.x / len, n.y / len, n.z / len };
				// Plane through the face centroid (less rounding than v0 alone).
				const Vec3  cen{ (t.v[0].x + t.v[1].x + t.v[2].x) / 3, (t.v[0].y + t.v[1].y + t.v[2].y) / 3,
                    (t.v[0].z + t.v[1].z + t.v[2].z) / 3 };
				const float d = -Dot3(n, cen);
				bool        dup = false;
				for (std::uint32_t p = c.firstPlane; p < mesh.planes.size(); ++p) {
					const Plane& q = mesh.planes[p];
					if (q.n[0] * n.x + q.n[1] * n.y + q.n[2] * n.z > 0.9999f && std::fabs(q.d - d) < 0.05f) {
						dup = true;
						break;
					}
				}
				if (!dup) {
					mesh.planes.push_back(Plane{ { n.x, n.y, n.z }, d });
				}
			}
			c.planeCount = static_cast<std::uint32_t>(mesh.planes.size()) - c.firstPlane;
			if (c.planeCount < 4 || c.triCount == 0) {
				mesh.planes.resize(c.firstPlane);
				return false;
			}
			// Material: the most common diggable face material, else the most common one.
			std::uint32_t best = 0, bestDig = 0;
			bool          haveDig = false;
			for (const auto& [m, n] : matCount) {
				if (n > best) {
					best = n;
					c.material = m;
				}
			}
			if (mats) {
				std::uint16_t digMat = 0;
				for (const auto& [m, n] : matCount) {
					if (m < mats->Size() && (*mats)[m].diggable && n > bestDig) {
						bestDig = n;
						digMat = m;
						haveDig = true;
					}
				}
				if (haveDig) {
					c.material = digMat;
				}
			}
			const auto ci = static_cast<std::int32_t>(mesh.convexes.size());
			for (std::uint32_t i = firstTri; i < mesh.tris.size(); ++i) {
				mesh.tris[i].convex = ci;
			}
			mesh.convexes.push_back(c);
			return true;
		}
	}

	using namespace detail;

	namespace
	{
		constexpr float kMetresToUnits = 1.0f / 0.0254f;

		struct Ledge
		{
			std::int64_t off;
			std::int32_t pointOffset, clientData;
			std::int16_t nTris;
		};
	}

	bool DecodeIvpSolid(Bytes blob, const std::uint16_t (&ivpMaterial)[128], std::uint8_t kind, bool polysoup, Mesh& out,
		MapStats* stats, std::string& err)
	{
		std::int64_t cs = 0;  // compact surface offset
		char         magic[4] = {};
		if (Rd(blob, 0, magic) && std::memcmp(magic, "VPHY", 4) == 0) {
			std::int16_t ver = 0, modelType = 0;
			if (!Rd(blob, 4, ver) || !Rd(blob, 6, modelType)) {
				err = "ivp: truncated VPHY header";
				return false;
			}
			if (modelType != 0) {
				err = "ivp: unsupported model type " + std::to_string(modelType) + " (not a compact surface)";
				return false;
			}
			cs = 28;
		}
		char ivps[4] = {};
		if (!Rd(blob, cs + 44, ivps) || std::memcmp(ivps, "IVPS", 4) != 0) {
			err = "ivp: no IVPS compact surface";
			return false;
		}
		std::int32_t root = 0;
		if (!Rd(blob, cs + 32, root)) {
			err = "ivp: truncated compact surface";
			return false;
		}

		// Walk the ledge tree. Left child = node + 28, right = node + offsetRightNode (> 0), so every
		// step moves forward: no cycles; the visit cap bounds the work anyway.
		std::vector<std::int64_t> stack{ cs + static_cast<std::int64_t>(root) };
		std::vector<Ledge>        ledges;
		const std::size_t         maxNodes = blob.size / 28 + 1;
		std::size_t               visited = 0;
		while (!stack.empty()) {
			const std::int64_t node = stack.back();
			stack.pop_back();
			if (++visited > maxNodes) {
				err = "ivp: ledge tree too large";
				return false;
			}
			std::int32_t right = 0, ledgeOff = 0;
			if (!InRange(blob, node, 28) || !Rd(blob, node, right) || !Rd(blob, node + 4, ledgeOff)) {
				err = "ivp: ledge tree node out of range";
				return false;
			}
			if (right == 0) {
				Ledge l{};
				l.off = node + ledgeOff;
				std::int16_t n = 0;
				if (!InRange(blob, l.off, 16) || !Rd(blob, l.off, l.pointOffset) || !Rd(blob, l.off + 4, l.clientData) ||
					!Rd(blob, l.off + 12, n) || n < 0 || !InRange(blob, l.off + 16, std::int64_t(n) * 16)) {
					err = "ivp: compact ledge out of range";
					return false;
				}
				l.nTris = n;
				ledges.push_back(l);
				continue;
			}
			if (right < 0) {
				err = "ivp: negative right-node offset";
				return false;
			}
			stack.push_back(node + right);
			stack.push_back(node + 28);
		}
		// Tree order is right-before-left after the pops above; keep the file's ledge order instead
		// so output is stable and matches the oracle's walk.
		std::sort(ledges.begin(), ledges.end(), [](const Ledge& a, const Ledge& b) { return a.off < b.off; });
		ledges.erase(std::unique(ledges.begin(), ledges.end(), [](const Ledge& a, const Ledge& b) { return a.off == b.off; }),
			ledges.end());

		for (const Ledge& l : ledges) {
			const auto first = static_cast<std::uint32_t>(out.tris.size());
			Tri        tris[2];
			for (int t = 0; t < l.nTris; ++t) {
				const std::int64_t tb = l.off + 16 + 16 * std::int64_t(t);
				std::uint32_t      w = 0;
				Rd(blob, tb, w);  // in range: checked with the ledge
				Tri tri;
				tri.material = ivpMaterial[(w >> 24) & 0x7F];
				tri.kind = kind;
				tri.owner = polysoup ? -1 : l.clientData;
				for (int e = 0; e < 3; ++e) {
					std::uint32_t ew = 0;
					Rd(blob, tb + 4 + 4 * e, ew);
					const std::int64_t pt = l.off + l.pointOffset + 16 * std::int64_t(ew & 0xFFFF);
					float              p[3] = {};
					if (!Rd(blob, pt, p)) {
						err = "ivp: triangle point out of range";
						out.tris.resize(first);
						return false;
					}
					tri.v[e] = Vec3{ p[0] * kMetresToUnits, p[2] * kMetresToUnits, -p[1] * kMetresToUnits };
					if (!Finite3(tri.v[e]) || std::fabs(tri.v[e].x) > 1e6f || std::fabs(tri.v[e].y) > 1e6f ||
						std::fabs(tri.v[e].z) > 1e6f) {
						err = "ivp: non-finite point";
						out.tris.resize(first);
						return false;
					}
				}
				if (polysoup && l.nTris == 2) {
					tris[t] = tri;
				} else {
					out.tris.push_back(tri);
				}
			}
			if (polysoup) {
				if (l.nTris == 2) {
					// One triangle stored front and back: same three points, opposite winding. The
					// second copy faces out (checked against the displacement rebuild).
					auto same = [](const Vec3& a, const Vec3& b) { return a.x == b.x && a.y == b.y && a.z == b.z; };
					const Tri& a = tris[0];
					const Tri& b = tris[1];
					bool       mirrored = false;
					for (int r = 0; r < 3 && !mirrored; ++r) {
						mirrored = same(a.v[0], b.v[r]) && same(a.v[1], b.v[(r + 2) % 3]) && same(a.v[2], b.v[(r + 1) % 3]);
					}
					if (mirrored) {
						out.tris.push_back(b);
						if (stats) {
							++stats->polysoupLedges;
						}
					} else {
						out.tris.push_back(a);
						out.tris.push_back(b);
						if (stats) {
							++stats->polysoupOddLedges;
						}
					}
				} else if (stats) {
					++stats->polysoupOddLedges;
				}
				continue;
			}
			if (out.tris.size() > first && !FinishConvex(out, nullptr, first, kind, 0, l.clientData)) {
				out.tris.resize(first);  // flat / degenerate ledge: nothing solid
				if (stats) {
					++stats->flatLedgesDropped;
				}
			}
		}
		return true;
	}
}
