// The world model's BSP tree: point and vertical-line contents queries (the hull trace's fill).
#include <algorithm>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	using namespace detail;

	bool DecodeBspTree(const BspLumps& lumps, BspTree& tree)
	{
		tree = BspTree{};
		// dplane_t {float normal[3], dist; int type} 20 bytes; dnode_t {int planenum, children[2]; ...}
		// 32; dleaf_t {int contents; ...} 32 bytes (lump version 1) or 56 (version 0, with ambient
		// light); dmodel_t {float mins[3], maxs[3], origin[3]; int headnode, ...} 48.
		const Bytes pl = lumps.lump[kLumpPlanes], nd = lumps.lump[kLumpNodes], lf = lumps.lump[kLumpLeafs], md = lumps.lump[kLumpModels];
		const std::size_t leafBytes = lumps.version[kLumpLeafs] == 0 ? 56 : 32;
		std::size_t np = 0, nn = 0, nl = 0, nm = 0;
		if (!Count(pl, 20, np) || !Count(nd, 32, nn) || !Count(lf, leafBytes, nl) || !Count(md, 48, nm) || !np || !nn || !nl || !nm) {
			return false;
		}
		BspTree t;
		t.planes.resize(np);
		for (std::size_t i = 0; i < np; ++i) {
			float v[4];
			Rd(pl, std::int64_t(i) * 20, v);
			if (!std::isfinite(v[0]) || !std::isfinite(v[1]) || !std::isfinite(v[2]) || !std::isfinite(v[3])) {
				return false;
			}
			t.planes[i] = Plane{ { v[0], v[1], v[2] }, -v[3] };
		}
		t.nodes.resize(nn);
		for (std::size_t i = 0; i < nn; ++i) {
			BspTree::Node& n = t.nodes[i];
			Rd(nd, std::int64_t(i) * 32, n.plane);
			Rd(nd, std::int64_t(i) * 32 + 4, n.child[0]);
			Rd(nd, std::int64_t(i) * 32 + 8, n.child[1]);
			if (n.plane < 0 || std::size_t(n.plane) >= np) {
				return false;
			}
			for (std::int32_t c : n.child) {
				if (c >= 0 ? std::size_t(c) >= nn : std::size_t(-(std::int64_t(c) + 1)) >= nl) {
					return false;
				}
			}
		}
		t.contents.resize(nl);
		for (std::size_t i = 0; i < nl; ++i) {
			Rd(lf, std::int64_t(i * leafBytes), t.contents[i]);
		}
		float mm[6];
		Rd(md, 0, mm);
		Rd(md, 36, t.head);
		if (t.head < 0 || std::size_t(t.head) >= nn) {
			return false;
		}
		t.lo = Vec3{ mm[0], mm[1], mm[2] };
		t.hi = Vec3{ mm[3], mm[4], mm[5] };
		if (!Bounded3(t.lo) || !Bounded3(t.hi)) {
			return false;
		}
		t.ok = true;
		tree = std::move(t);
		return true;
	}

	std::int32_t BspContentsAt(const BspTree& tree, const Vec3& p)
	{
		if (!tree.ok) {
			return -1;
		}
		std::int32_t n = tree.head;
		// a well-formed tree reaches a leaf in fewer steps than it has nodes (no cycles)
		for (std::size_t steps = 0; n >= 0 && steps <= tree.nodes.size(); ++steps) {
			const BspTree::Node& node = tree.nodes[std::size_t(n)];
			const Plane&         q = tree.planes[std::size_t(node.plane)];
			n = node.child[q.n[0] * p.x + q.n[1] * p.y + q.n[2] * p.z + q.d >= 0.0f ? 0 : 1];
		}
		return n < 0 ? tree.contents[std::size_t(-(std::int64_t(n) + 1))] : -1;
	}

	namespace
	{
		struct RunWalk
		{
			const BspTree&                         tree;
			float                                  x, y;
			std::vector<std::pair<float, float>>&  out;
			std::size_t                            budget;

			void Leaf(std::int32_t leaf, float lo, float hi)
			{
				if (!(tree.contents[std::size_t(leaf)] & kContentsSolid) || hi <= lo) {
					return;
				}
				if (!out.empty() && out.back().first <= hi) {
					out.back().first = std::min(out.back().first, lo);  // touches the run above: merged
				} else {
					out.emplace_back(lo, hi);
				}
			}
			// The part [lo, hi] of the line inside `n`'s subtree, highest part first.
			void Walk(std::int32_t n, float lo, float hi, int depth)
			{
				if (budget == 0 || depth > 4096) {
					return;  // a cyclic or absurdly deep tree: what was found so far
				}
				--budget;
				if (n < 0) {
					Leaf(-(n + 1), lo, hi);
					return;
				}
				const BspTree::Node& node = tree.nodes[std::size_t(n)];
				const Plane&         q = tree.planes[std::size_t(node.plane)];
				const float          base = q.n[0] * x + q.n[1] * y + q.d;  // side(z) = base + n.z * z
				const float          nz = q.n[2];
				if (std::fabs(nz) < 1e-6f) {
					Walk(node.child[base >= 0.0f ? 0 : 1], lo, hi, depth + 1);
					return;
				}
				const float zc = -base / nz;  // the plane's height on the line
				// above the plane: the front when the normal points up, else the back
				const std::int32_t up = node.child[nz > 0 ? 0 : 1], down = node.child[nz > 0 ? 1 : 0];
				if (zc >= hi) {
					Walk(down, lo, hi, depth + 1);
				} else if (zc <= lo) {
					Walk(up, lo, hi, depth + 1);
				} else {
					Walk(up, zc, hi, depth + 1);
					Walk(down, lo, zc, depth + 1);
				}
			}
		};
	}

	void BspSolidRuns(const BspTree& tree, float x, float y, std::vector<std::pair<float, float>>& out)
	{
		out.clear();
		if (!tree.ok || !(tree.lo.z < tree.hi.z)) {
			return;
		}
		RunWalk w{ tree, x, y, out, tree.nodes.size() * 4 + 64 };
		w.Walk(tree.head, tree.lo.z, tree.hi.z, 0);
	}
}
