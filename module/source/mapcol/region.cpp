// Minecraft-space conversion, the region grid index and region gathering.
#include <algorithm>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	using namespace detail;

	namespace
	{
		constexpr float        kUnits = static_cast<float>(proto::kUnitsPerBlock);
		constexpr int          kRegion = static_cast<int>(proto::kColRegionSize);
		constexpr std::int64_t kMaxCellsPerItem = 4096;  // larger items go to the always-checked list

		// Clamped before the cast (float -> int overflow is UB): +-2^24 regions is far beyond any map.
		int RegionOf(float v)
		{
			const float r = std::floor(v / kRegion);
			return static_cast<int>(std::isfinite(r) ? std::clamp(r, -16777216.0f, 16777216.0f) : 0.0f);
		}

		bool Overlap(const float* a, const float* lo, const float* hi)
		{
			return a[0] <= hi[0] && a[3] >= lo[0] && a[1] <= hi[1] && a[4] >= lo[1] && a[2] <= hi[2] && a[5] >= lo[2];
		}

		// Source AABB -> MC AABB (x, z, -y axis map, so z bounds swap).
		void BoxToMc(const McFrame& f, const Vec3& lo, const Vec3& hi, float* out)
		{
			out[0] = lo.x / kUnits + f.originX;
			out[1] = (lo.z + float(f.originYUnits)) / kUnits;
			out[2] = -hi.y / kUnits + f.originZ;
			out[3] = hi.x / kUnits + f.originX;
			out[4] = (hi.z + float(f.originYUnits)) / kUnits;
			out[5] = -lo.y / kUnits + f.originZ;
		}

		constexpr std::uint64_t kBigKey = ~0ull;
	}

	void ToMc(const McFrame& f, const Vec3& s, float out[3])
	{
		out[0] = s.x / kUnits + f.originX;
		out[1] = (s.z + float(f.originYUnits)) / kUnits;
		out[2] = -s.y / kUnits + f.originZ;
	}

	Vec3 FromMc(const McFrame& f, const float mc[3])
	{
		return Vec3{ (mc[0] - f.originX) * kUnits, -(mc[2] - f.originZ) * kUnits, mc[1] * kUnits - float(f.originYUnits) };
	}

	Plane PlaneToMc(const McFrame& f, const Plane& p)
	{
		Plane q;
		q.n[0] = p.n[0];
		q.n[1] = p.n[2];
		q.n[2] = -p.n[1];
		// n.p + d = 0 with x = (x' - ox) * U, y = -(z' - oz) * U, z = y' * U - oy (each over U):
		q.d = p.d / kUnits - p.n[0] * float(f.originX) + p.n[1] * float(f.originZ) - p.n[2] * float(f.originYUnits) / kUnits;
		return q;
	}

	std::uint64_t RegionIndex::Key(int rx, int ry, int rz)
	{
		return (std::uint64_t(std::uint32_t(rx) & 0x1FFFFF) << 42) | (std::uint64_t(std::uint32_t(ry) & 0x1FFFFF) << 21) |
		       (std::uint32_t(rz) & 0x1FFFFF);
	}

	void RegionIndex::Build(const Mesh& mesh, const McFrame& frame)
	{
		mesh_ = &mesh;
		frame_ = frame;
		cells_.clear();
		triBox_.assign(mesh.tris.size() * 6, 0.0f);
		convexBox_.assign(mesh.convexes.size() * 6, 0.0f);
		auto insert = [&](const float* b, std::uint32_t idx, bool convex) {
			const int x0 = RegionOf(b[0]), y0 = RegionOf(b[1]), z0 = RegionOf(b[2]);
			const int x1 = RegionOf(b[3]), y1 = RegionOf(b[4]), z1 = RegionOf(b[5]);
			const std::int64_t cells = (std::int64_t(x1) - x0 + 1) * (std::int64_t(y1) - y0 + 1) * (std::int64_t(z1) - z0 + 1);
			if (cells > kMaxCellsPerItem || cells <= 0) {
				auto& c = cells_[kBigKey];
				(convex ? c.convexes : c.tris).push_back(idx);
				return;
			}
			for (int x = x0; x <= x1; ++x) {
				for (int y = y0; y <= y1; ++y) {
					for (int z = z0; z <= z1; ++z) {
						auto& c = cells_[Key(x, y, z)];
						(convex ? c.convexes : c.tris).push_back(idx);
					}
				}
			}
		};
		for (std::uint32_t i = 0; i < mesh.tris.size(); ++i) {
			const Tri& t = mesh.tris[i];
			float*     b = &triBox_[std::size_t(i) * 6];
			float      p[3];
			ToMc(frame, t.v[0], p);
			for (int k = 0; k < 3; ++k) {
				b[k] = b[3 + k] = p[k];
			}
			for (int v = 1; v < 3; ++v) {
				ToMc(frame, t.v[v], p);
				for (int k = 0; k < 3; ++k) {
					b[k] = std::min(b[k], p[k]);
					b[3 + k] = std::max(b[3 + k], p[k]);
				}
			}
			if (t.convex < 0) {
				insert(b, i, false);
			}
		}
		for (std::uint32_t i = 0; i < mesh.convexes.size(); ++i) {
			float* b = &convexBox_[std::size_t(i) * 6];
			BoxToMc(frame, mesh.convexes[i].lo, mesh.convexes[i].hi, b);
			insert(b, i, true);
		}
	}

	void RegionIndex::Query(const float lo[3], const float hi[3], bool convexes, std::vector<std::uint32_t>& out) const
	{
		out.clear();
		const std::vector<float>& boxes = convexes ? convexBox_ : triBox_;
		auto scan = [&](const Cell& c) {
			for (std::uint32_t i : convexes ? c.convexes : c.tris) {
				if (Overlap(&boxes[std::size_t(i) * 6], lo, hi)) {
					out.push_back(i);
				}
			}
		};
		auto big = cells_.find(kBigKey);
		if (big != cells_.end()) {
			scan(big->second);
		}
		const int x0 = RegionOf(lo[0]), y0 = RegionOf(lo[1]), z0 = RegionOf(lo[2]);
		const int x1 = RegionOf(hi[0]), y1 = RegionOf(hi[1]), z1 = RegionOf(hi[2]);
		if ((std::int64_t(x1) - x0 + 1) * (std::int64_t(y1) - y0 + 1) * (std::int64_t(z1) - z0 + 1) > std::int64_t(cells_.size())) {
			for (const auto& [k, c] : cells_) {  // huge query box: scanning every cell is cheaper
				if (k != kBigKey) {
					scan(c);
				}
			}
		} else {
			for (int x = x0; x <= x1; ++x) {
				for (int y = y0; y <= y1; ++y) {
					for (int z = z0; z <= z1; ++z) {
						auto it = cells_.find(Key(x, y, z));
						if (it != cells_.end()) {
							scan(it->second);
						}
					}
				}
			}
		}
		std::sort(out.begin(), out.end());
		out.erase(std::unique(out.begin(), out.end()), out.end());
	}

	void RegionIndex::TrianglesInBox(const float lo[3], const float hi[3], std::vector<std::uint32_t>& out) const
	{
		Query(lo, hi, false, out);
	}

	void RegionIndex::ConvexesInBox(const float lo[3], const float hi[3], std::vector<std::uint32_t>& out) const
	{
		Query(lo, hi, true, out);
	}

	std::uint32_t ColFlags(const MaterialTable& mats, std::uint16_t material, std::uint8_t kind)
	{
		std::uint32_t f = 0;
		if (kind == kSrcDynamic) {
			return proto::kTriDynamic;  // moving entities: solid, never diggable, tagged (v15)
		}
		const bool    clip = kind == kSrcPlayerClip || kind == kSrcMonsterClip;
		if (!clip && material < mats.Size() && mats[material].diggable) {
			f |= proto::kTriDiggable | (std::uint32_t(mats[material].dig) << proto::kTriMaterialShift);
		}
		if (kind == kSrcDisplacement) {
			f |= proto::kTriTerrain;
		}
		return f;
	}

	void GatherRegion(const MapCollision& map, const RegionIndex& index, int rx, int ry, int rz, std::uint32_t epoch,
		const GatherOptions& opts, RegionJob& job)
	{
		job = RegionJob{};
		job.rx = rx;
		job.ry = ry;
		job.rz = rz;
		job.epoch = epoch;
		GatherAppend(map.materials, index, opts, job);
	}

	// v30: a dynamic triangle's flags carry its GMod entity index (Tri::owner, PlaceDynamic) in bits
	// 16-31, so Minecraft can tell the platform a carried player stands on from everything else.
	static std::uint32_t TriFlags(const MaterialTable& materials, const Tri& t)
	{
		std::uint32_t f = ColFlags(materials, t.material, t.kind);
		if (t.kind == kSrcDynamic && t.owner > 0) {
			f |= (std::uint32_t(t.owner) & 0xFFFFu) << proto::kTriEntityShift;
		}
		return f;
	}

	void GatherAppend(const MaterialTable& materials, const RegionIndex& index, const GatherOptions& opts, RegionJob& job)
	{
		const int   rx = job.rx, ry = job.ry, rz = job.rz;
		const Mesh* mesh = index.Source();
		if (!mesh) {
			return;
		}
		const McFrame& f = index.Frame();
		const float    lo[3] = { float(rx * kRegion) - 0.5f, float(ry * kRegion) - 0.5f, float(rz * kRegion) - 0.5f };
		const float    hi[3] = { lo[0] + kRegion + 1.0f, lo[1] + kRegion + 1.0f, lo[2] + kRegion + 1.0f };
		auto           toCol = [&](const Tri& t, std::uint32_t flags) {
            proto::ColTri c{};
            for (int v = 0; v < 3; ++v) {
                ToMc(f, t.v[v], c.v + 3 * v);
            }
            c.flags = flags;
            return c;
		};
		std::vector<std::uint32_t> ids;
		index.TrianglesInBox(lo, hi, ids);
		for (std::uint32_t i : ids) {
			const Tri& t = mesh->tris[i];
			if (opts.skipNoHull && (t.bits & kBitNoHull)) {
				continue;
			}
			job.tris.push_back(toCol(t, TriFlags(materials, t)));
		}
		index.ConvexesInBox(lo, hi, ids);
		for (std::uint32_t i : ids) {
			const Convex& c = mesh->convexes[i];
			if (c.kind == kSrcDynamic && (c.bits & kBitThin)) {
				// Too thin to hold a sub-voxel centre: its faces are voxelized as surfaces instead.
				for (std::uint32_t t = c.firstTri; t < c.firstTri + c.triCount; ++t) {
					job.tris.push_back(toCol(mesh->tris[t], TriFlags(materials, mesh->tris[t])));
				}
				continue;
			}
			const bool    clip = c.kind == kSrcPlayerClip || c.kind == kSrcMonsterClip;
			if (clip && opts.clip == ClipMode::kSkip) {
				continue;
			}
			if (clip && opts.clip == ClipMode::kHelperTris) {
				for (std::uint32_t t = c.firstTri; t < c.firstTri + c.triCount; ++t) {
					job.helperTris.push_back(toCol(mesh->tris[t], proto::kTriStairHelper));
				}
				continue;
			}
			McConvex m;
			for (std::uint32_t p = c.firstPlane; p < c.firstPlane + c.planeCount; ++p) {
				const Plane q = PlaneToMc(f, mesh->planes[p]);
				m.planes.push_back({ q.n[0], q.n[1], q.n[2], q.d });
			}
			float box[6];
			BoxToMc(f, c.lo, c.hi, box);
			std::copy(box, box + 3, m.lo);
			std::copy(box + 3, box + 6, m.hi);
			m.flags = ColFlags(materials, c.material, c.kind);
			job.convexes.push_back(std::move(m));
			for (std::uint32_t t = c.firstTri; t < c.firstTri + c.triCount; ++t) {
				const Tri& tri = mesh->tris[t];
				job.convexFaces.push_back(toCol(tri, TriFlags(materials, tri)));
			}
		}
	}

	// D6: does any of the index's map geometry (brushes, player clips, static props, displacement
	// triangles; not monster clips) reach into the Source-unit box [lo, hi]? Bounds first, then a
	// separating-plane test against the convex's planes / the triangle's plane: conservative, a
	// box near a slanted brush's edge may still count as touching it.
	bool SolidInBox(const RegionIndex& index, const float lo[3], const float hi[3])
	{
		const Mesh* mesh = index.Source();
		if (mesh == nullptr) {
			return false;
		}
		const McFrame& f = index.Frame();
		float mc[6];
		BoxToMc(f, Vec3{ lo[0], lo[1], lo[2] }, Vec3{ hi[0], hi[1], hi[2] }, mc);
		float corners[8][3];
		for (int c = 0; c < 8; ++c) {
			corners[c][0] = (c & 1) ? hi[0] : lo[0];
			corners[c][1] = (c & 2) ? hi[1] : lo[1];
			corners[c][2] = (c & 4) ? hi[2] : lo[2];
		}
		// All eight corners strictly in front of the plane n . p + d = 0.
		auto outside = [&](const float n[3], float d) {
			for (const auto& p : corners) {
				if (n[0] * p[0] + n[1] * p[1] + n[2] * p[2] + d <= 0.0f) {
					return false;
				}
			}
			return true;
		};
		auto overlaps = [&](const Vec3& a, const Vec3& b) {
			return a.x < hi[0] && b.x > lo[0] && a.y < hi[1] && b.y > lo[1] && a.z < hi[2] && b.z > lo[2];
		};
		std::vector<std::uint32_t> ids;
		index.ConvexesInBox(mc, mc + 3, ids);
		for (std::uint32_t ci : ids) {
			const Convex& c = mesh->convexes[ci];
			if (c.kind == kSrcMonsterClip || c.kind == kSrcWater || !overlaps(c.lo, c.hi)) {
				continue;
			}
			bool separated = false;
			for (std::uint32_t p = c.firstPlane; p < c.firstPlane + c.planeCount && !separated; ++p) {
				separated = outside(mesh->planes[p].n, mesh->planes[p].d);
			}
			if (!separated) {
				return true;
			}
		}
		index.TrianglesInBox(mc, mc + 3, ids);
		for (std::uint32_t ti : ids) {
			const Tri& t = mesh->tris[ti];
			if (t.kind == kSrcMonsterClip || t.kind == kSrcWater) {
				continue;
			}
			const Vec3 a{ std::min({ t.v[0].x, t.v[1].x, t.v[2].x }), std::min({ t.v[0].y, t.v[1].y, t.v[2].y }), std::min({ t.v[0].z, t.v[1].z, t.v[2].z }) };
			const Vec3 b{ std::max({ t.v[0].x, t.v[1].x, t.v[2].x }), std::max({ t.v[0].y, t.v[1].y, t.v[2].y }), std::max({ t.v[0].z, t.v[1].z, t.v[2].z }) };
			if (!overlaps(a, b)) {
				continue;
			}
			const float e1[3] = { t.v[1].x - t.v[0].x, t.v[1].y - t.v[0].y, t.v[1].z - t.v[0].z };
			const float e2[3] = { t.v[2].x - t.v[0].x, t.v[2].y - t.v[0].y, t.v[2].z - t.v[0].z };
			float n[3] = { e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0] };
			const float len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			if (len < 1e-9f) {
				continue;
			}
			n[0] /= len, n[1] /= len, n[2] /= len;
			const float d = -(n[0] * t.v[0].x + n[1] * t.v[0].y + n[2] * t.v[0].z);
			const float m[3] = { -n[0], -n[1], -n[2] };
			if (!outside(n, d) && !outside(m, -d)) {
				return true;  // the box has corners on both sides of (or on) the triangle's plane
			}
		}
		return false;
	}
}
