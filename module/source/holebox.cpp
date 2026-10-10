// Dug-cell boxes and their mask volume (P5b): see holebox.hpp.
#include "holebox.hpp"
#include "mapcol/bitops.hpp"

#include <algorithm>
#include <array>
#include <bitset>
#include <cmath>

namespace gc
{
namespace holes
{
void MergeSection(std::int32_t sx, std::int32_t sy, std::int32_t sz, const std::uint64_t bits[64], std::vector<Box> &out)
{
	std::bitset<4096> left;
	bool any = false;
	for (int w = 0; w < 64; ++w)
	{
		std::uint64_t v = bits[w];
		any |= v != 0;
		while (v)
		{
			const int b = gmodcraft::mapcol::Ctz64(v);
			v &= v - 1;
			left.set(static_cast<std::size_t>(w * 64 + b));
		}
	}
	if (!any)
		return;
	auto at = [](int x, int y, int z) { return static_cast<std::size_t>(x + 16 * z + 256 * y); };
	for (int y = 0; y < 16; ++y)
		for (int z = 0; z < 16; ++z)
			for (int x = 0; x < 16; ++x)
			{
				if (!left.test(at(x, y, z)))
					continue;
				int x1 = x + 1;
				while (x1 < 16 && left.test(at(x1, y, z)))
					++x1;
				auto rowFull = [&](int yy, int zz) {
					for (int xx = x; xx < x1; ++xx)
						if (!left.test(at(xx, yy, zz)))
							return false;
					return true;
				};
				int z1 = z + 1;
				while (z1 < 16 && rowFull(y, z1))
					++z1;
				int y1 = y + 1;
				while (y1 < 16)
				{
					bool full = true;
					for (int zz = z; zz < z1 && full; ++zz)
						full = rowFull(y1, zz);
					if (!full)
						break;
					++y1;
				}
				for (int yy = y; yy < y1; ++yy)
					for (int zz = z; zz < z1; ++zz)
						for (int xx = x; xx < x1; ++xx)
							left.reset(at(xx, yy, zz));
				out.push_back(Box{ sx * 16 + x, sy * 16 + y, sz * 16 + z, sx * 16 + x1, sy * 16 + y1, sz * 16 + z1 });
			}
}

namespace
{
void Put(std::vector<blk::OutVertex> &out, const float p[3], const float n[3])
{
	blk::OutVertex v{};
	for (int i = 0; i < 3; ++i)
	{
		v.pos[i] = p[i];
		v.normal[i] = n[i];
	}
	v.color[0] = v.color[1] = v.color[2] = v.color[3] = 255;
	out.push_back(v);
}

// c0..c3 go counter-clockwise around the outward normal n; the triangles come out clockwise
// (Source's front faces).
void Quad(std::vector<blk::OutVertex> &out, const float *c0, const float *c1, const float *c2, const float *c3, const float n[3])
{
	Put(out, c0, n);
	Put(out, c2, n);
	Put(out, c1, n);
	Put(out, c0, n);
	Put(out, c3, n);
	Put(out, c2, n);
}
}  // namespace

void BoxTriangles(const Box &b, std::int32_t ox, std::int32_t oz, std::int32_t oy, float eps, std::vector<blk::OutVertex> &out)
{
	float a[3], c[3];
	blk::McToSource(b.x0, b.y0, b.z0, ox, oz, oy, a);
	blk::McToSource(b.x1, b.y1, b.z1, ox, oz, oy, c);
	float lo[3], hi[3];
	for (int i = 0; i < 3; ++i)
	{
		lo[i] = std::min(a[i], c[i]) - eps;  // Source y = -(mc z): the corners swap on that axis
		hi[i] = std::max(a[i], c[i]) + eps;
	}
	const float X0 = lo[0], Y0 = lo[1], Z0 = lo[2], X1 = hi[0], Y1 = hi[1], Z1 = hi[2];
	const float p000[3] = { X0, Y0, Z0 }, p100[3] = { X1, Y0, Z0 }, p010[3] = { X0, Y1, Z0 }, p110[3] = { X1, Y1, Z0 };
	const float p001[3] = { X0, Y0, Z1 }, p101[3] = { X1, Y0, Z1 }, p011[3] = { X0, Y1, Z1 }, p111[3] = { X1, Y1, Z1 };
	const float nxp[3] = { 1, 0, 0 }, nxn[3] = { -1, 0, 0 }, nyp[3] = { 0, 1, 0 }, nyn[3] = { 0, -1, 0 }, nzp[3] = { 0, 0, 1 }, nzn[3] = { 0, 0, -1 };
	Quad(out, p100, p110, p111, p101, nxp);
	Quad(out, p000, p001, p011, p010, nxn);
	Quad(out, p010, p011, p111, p110, nyp);
	Quad(out, p000, p100, p101, p001, nyn);
	Quad(out, p001, p101, p111, p011, nzp);
	Quad(out, p000, p010, p110, p100, nzn);
}

namespace
{
namespace mcol = gmodcraft::mapcol;
using V3 = std::array<float, 3>;
using Poly = std::vector<V3>;

float Dot3(const float *a, const float *b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

// Keeps the part of `poly` with n . p + d <= 0.
void ClipPoly(Poly &poly, const float n[3], float d, Poly &tmp)
{
	tmp.clear();
	const std::size_t k = poly.size();
	for (std::size_t v = 0; v < k; ++v)
	{
		const V3 &A = poly[v], &B = poly[(v + 1) % k];
		const float da = Dot3(n, A.data()) + d, db = Dot3(n, B.data()) + d;
		if (da <= 0.0f)
			tmp.push_back(A);
		if ((da <= 0.0f) != (db <= 0.0f))
		{
			const float t = da / (da - db);
			tmp.push_back({ A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t });
		}
	}
	poly.swap(tmp);
}

// Twice the polygon's area along n (> 0: counter-clockwise around n).
float SignedArea2(const Poly &p, const float n[3])
{
	float s[3] = { 0, 0, 0 };
	for (std::size_t i = 1; i + 1 < p.size(); ++i)
	{
		const float e1[3] = { p[i][0] - p[0][0], p[i][1] - p[0][1], p[i][2] - p[0][2] };
		const float e2[3] = { p[i + 1][0] - p[0][0], p[i + 1][1] - p[0][1], p[i + 1][2] - p[0][2] };
		s[0] += e1[1] * e2[2] - e1[2] * e2[1];
		s[1] += e1[2] * e2[0] - e1[0] * e2[2];
		s[2] += e1[0] * e2[1] - e1[1] * e2[0];
	}
	return Dot3(s, n);
}

// A closed prism around the planar convex polygon `p` (unit normal n): caps at +-eps along n,
// outward clockwise fronts like BoxTriangles.
void Prism(Poly p, const float n[3], float eps, std::vector<blk::OutVertex> &out)
{
	if (SignedArea2(p, n) < 0.0f)
		std::reverse(p.begin(), p.end());  // counter-clockwise around n from here on
	const std::size_t k = p.size();
	Poly top(k), bot(k);
	for (std::size_t i = 0; i < k; ++i)
		for (int a = 0; a < 3; ++a)
		{
			top[i][a] = p[i][a] + n[a] * eps;
			bot[i][a] = p[i][a] - n[a] * eps;
		}
	const float nn[3] = { -n[0], -n[1], -n[2] };
	for (std::size_t i = 1; i + 1 < k; ++i)
	{
		Put(out, top[0].data(), n);  // counter-clockwise around n, emitted clockwise
		Put(out, top[i + 1].data(), n);
		Put(out, top[i].data(), n);
		Put(out, bot[0].data(), nn);  // counter-clockwise around n = clockwise around -n
		Put(out, bot[i].data(), nn);
		Put(out, bot[i + 1].data(), nn);
	}
	for (std::size_t i = 0; i < k; ++i)
	{
		const std::size_t j = (i + 1) % k;
		const float e[3] = { p[j][0] - p[i][0], p[j][1] - p[i][1], p[j][2] - p[i][2] };
		float m[3] = { e[1] * n[2] - e[2] * n[1], e[2] * n[0] - e[0] * n[2], e[0] * n[1] - e[1] * n[0] };  // outward
		const float ml = std::sqrt(Dot3(m, m));
		if (ml > 1e-12f)
			for (float &c : m)
				c /= ml;
		Quad(out, bot[i].data(), bot[j].data(), top[j].data(), top[i].data(), m);
	}
}

// Clips `face` (unit normal n) to the Source box [lo, hi] moved by behind * n and grown by eps; a
// piece with area becomes a prism. Returns whether one did.
bool CutFace(const Poly &face, const float n[3], const float lo[3], const float hi[3], float behind, float eps, Poly &work, Poly &tmp,
	std::vector<blk::OutVertex> &out)
{
	work = face;
	for (int a = 0; a < 3 && work.size() >= 3; ++a)
	{
		float ax[3] = { 0, 0, 0 };
		ax[a] = 1;
		ClipPoly(work, ax, -(hi[a] + behind * n[a] + eps), tmp);  // p[a] <= hi + behind n + eps
		if (work.size() < 3)
			break;
		ax[a] = -1;
		ClipPoly(work, ax, lo[a] + behind * n[a] - eps, tmp);  // p[a] >= lo + behind n - eps
	}
	if (work.size() < 3 || std::fabs(SignedArea2(work, n)) < 1e-4f)
		return false;
	Prism(work, n, eps, out);
	return true;
}
}  // namespace

void FaceCutTriangles(const mcol::RegionIndex &index, const std::vector<Box> &boxes, float behind, float eps,
	std::vector<blk::OutVertex> &out, FaceCutStats &stats)
{
	const mcol::Mesh *mesh = index.Source();
	if (mesh == nullptr)
		return;
	const mcol::McFrame &f = index.Frame();
	std::vector<std::uint32_t> ids;
	Poly face, work, tmp;
	const float m = 0.1f;  // blocks: the query reaches past the box by more than behind + eps
	for (const Box &b : boxes)
	{
		if (out.size() >= kMaxCutVertices)
		{
			stats.truncated = true;
			break;
		}
		float a[3], c[3], lo[3], hi[3];
		blk::McToSource(b.x0, b.y0, b.z0, f.originX, f.originZ, f.originYUnits, a);
		blk::McToSource(b.x1, b.y1, b.z1, f.originX, f.originZ, f.originYUnits, c);
		for (int i = 0; i < 3; ++i)
		{
			lo[i] = std::min(a[i], c[i]);
			hi[i] = std::max(a[i], c[i]);
		}
		const float qlo[3] = { b.x0 - m, b.y0 - m, b.z0 - m }, qhi[3] = { b.x1 + m, b.y1 + m, b.z1 + m };
		ids.clear();
		index.ConvexesInBox(qlo, qhi, ids);
		for (std::uint32_t ci : ids)
		{
			const mcol::Convex &cv = mesh->convexes[ci];
			if (cv.kind != mcol::kSrcWorld || cv.planeCount < 4)
				continue;
			++stats.convexes;
			const float ex = cv.hi.x - cv.lo.x, ey = cv.hi.y - cv.lo.y, ez = cv.hi.z - cv.lo.z;
			const float diag = std::sqrt(ex * ex + ey * ey + ez * ez) + 4 * eps + 1.0f;
			const float mid[3] = { (cv.lo.x + cv.hi.x) * 0.5f, (cv.lo.y + cv.hi.y) * 0.5f, (cv.lo.z + cv.hi.z) * 0.5f };
			for (std::uint32_t i = 0; i < cv.planeCount; ++i)
			{
				const mcol::Plane &pl = mesh->planes[cv.firstPlane + i];
				const float *n = pl.n;
				// A big square on the plane around the convex's middle, clipped by the other planes
				// (moved out by eps): the face, grown in-plane.
				const float dist = Dot3(n, mid) + pl.d;
				const float o[3] = { mid[0] - n[0] * dist, mid[1] - n[1] * dist, mid[2] - n[2] * dist };
				const bool steep = std::fabs(n[2]) < 0.9f;
				const float ref[3] = { steep ? 0.0f : 1.0f, 0.0f, steep ? 1.0f : 0.0f };
				float t1[3] = { ref[1] * n[2] - ref[2] * n[1], ref[2] * n[0] - ref[0] * n[2], ref[0] * n[1] - ref[1] * n[0] };
				const float l1 = std::sqrt(Dot3(t1, t1));
				if (!(l1 > 1e-6f))
					continue;
				for (float &x : t1)
					x /= l1;
				const float t2[3] = { n[1] * t1[2] - n[2] * t1[1], n[2] * t1[0] - n[0] * t1[2], n[0] * t1[1] - n[1] * t1[0] };
				face.clear();
				const float s1[4] = { -1, 1, 1, -1 }, s2[4] = { -1, -1, 1, 1 };
				for (int q = 0; q < 4; ++q)
					face.push_back({ o[0] + (t1[0] * s1[q] + t2[0] * s2[q]) * diag, o[1] + (t1[1] * s1[q] + t2[1] * s2[q]) * diag,
						o[2] + (t1[2] * s1[q] + t2[2] * s2[q]) * diag });
				for (std::uint32_t j = 0; j < cv.planeCount && face.size() >= 3; ++j)
					if (j != i)
					{
						const mcol::Plane &cp = mesh->planes[cv.firstPlane + j];
						ClipPoly(face, cp.n, cp.d - eps, tmp);
					}
				if (face.size() < 3)
					continue;
				++stats.faces;
				if (CutFace(face, n, lo, hi, behind, eps, work, tmp, out))
					++stats.pieces;
			}
		}
		ids.clear();
		index.TrianglesInBox(qlo, qhi, ids);
		for (std::uint32_t ti : ids)
		{
			const mcol::Tri &t = mesh->tris[ti];
			if (t.kind != mcol::kSrcDisplacement)
				continue;
			++stats.tris;
			const float *p0 = &t.v[0].x, *p1 = &t.v[1].x, *p2 = &t.v[2].x;
			const float e1[3] = { p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2] }, e2[3] = { p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2] };
			float n[3] = { e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0] };
			const float nl = std::sqrt(Dot3(n, n));  // twice the area
			if (!(nl > 1e-4f))
				continue;
			for (float &x : n)
				x /= nl;
			// Grown about the incentre: every edge moves out by eps (no crack between neighbours).
			const float e3[3] = { p2[0] - p1[0], p2[1] - p1[1], p2[2] - p1[2] };
			const float la = std::sqrt(Dot3(e3, e3)), lb = std::sqrt(Dot3(e2, e2)), lc = std::sqrt(Dot3(e1, e1));
			const float per = la + lb + lc, r = nl / per;  // inradius = area / semi-perimeter
			const float ic[3] = { (la * p0[0] + lb * p1[0] + lc * p2[0]) / per, (la * p0[1] + lb * p1[1] + lc * p2[1]) / per,
				(la * p0[2] + lb * p1[2] + lc * p2[2]) / per };
			const float g = r > eps ? 1.0f + eps / r : 1.0f;  // a sliver isn't stretched (at most 2x)
			face.clear();
			for (const float *p : { p0, p1, p2 })
				face.push_back({ ic[0] + (p[0] - ic[0]) * g, ic[1] + (p[1] - ic[1]) * g, ic[2] + (p[2] - ic[2]) * g });
			++stats.faces;
			if (CutFace(face, n, lo, hi, behind, eps, work, tmp, out))
				++stats.pieces;
		}
	}
}

namespace
{
// Keeps the part of `poly` with n . p + d <= 0 (into out; fewer than 3 vertices -> empty).
void KeepBelow(const FacePoly &poly, const float n[3], float d, FacePoly &out)
{
	out.clear();
	const std::size_t k = poly.size();
	for (std::size_t v = 0; v < k; ++v)
	{
		const auto &A = poly[v], &B = poly[(v + 1) % k];
		const float da = n[0] * A[0] + n[1] * A[1] + n[2] * A[2] + d, db = n[0] * B[0] + n[1] * B[1] + n[2] * B[2] + d;
		if (da <= 0.0f)
			out.push_back(A);
		if ((da <= 0.0f) != (db <= 0.0f))
		{
			const float t = da / (da - db);
			out.push_back({ A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t });
		}
	}
	if (out.size() < 3)
		out.clear();
}
}  // namespace

float PolyArea(const FacePoly &p)
{
	if (p.size() < 3)
		return 0;
	float s[3] = { 0, 0, 0 };
	for (std::size_t i = 1; i + 1 < p.size(); ++i)
	{
		const float a[3] = { p[i][0] - p[0][0], p[i][1] - p[0][1], p[i][2] - p[0][2] };
		const float b[3] = { p[i + 1][0] - p[0][0], p[i + 1][1] - p[0][1], p[i + 1][2] - p[0][2] };
		s[0] += a[1] * b[2] - a[2] * b[1];
		s[1] += a[2] * b[0] - a[0] * b[2];
		s[2] += a[0] * b[1] - a[1] * b[0];
	}
	return 0.5f * std::sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
}

bool OrientClockwise(FacePoly &p, const float n[3])
{
	float s[3] = { 0, 0, 0 };
	for (std::size_t i = 1; i + 1 < p.size(); ++i)
	{
		const float a[3] = { p[i][0] - p[0][0], p[i][1] - p[0][1], p[i][2] - p[0][2] };
		const float b[3] = { p[i + 1][0] - p[0][0], p[i + 1][1] - p[0][1], p[i + 1][2] - p[0][2] };
		s[0] += a[1] * b[2] - a[2] * b[1];
		s[1] += a[2] * b[0] - a[0] * b[2];
		s[2] += a[0] * b[1] - a[1] * b[0];
	}
	if (s[0] * n[0] + s[1] * n[1] + s[2] * n[2] <= 0.0f)
		return false;  // already clockwise around n (or degenerate)
	std::reverse(p.begin(), p.end());
	return true;
}

void MapFace(const MapFaceLumps &l, std::size_t face, FacePoly &poly, float n[3], float &d)
{
	const std::size_t fo = face * kMapFaceSize;
	const std::size_t po = std::size_t(l.faces.At<std::uint16_t>(fo)) * kMapPlaneSize;
	for (int k = 0; k < 3; ++k)
		n[k] = l.planes.At<float>(po + std::size_t(k) * 4);
	d = l.planes.At<float>(po + 12);
	const std::int32_t first = l.faces.At<std::int32_t>(fo + 4);
	const std::int16_t count = l.faces.At<std::int16_t>(fo + 8);
	poly.clear();
	for (std::int32_t e = 0; e < count; ++e)
	{
		const std::int32_t se = l.surfedges.At<std::int32_t>(std::size_t(first + e) * 4);
		const std::size_t eo = std::size_t(se < 0 ? -static_cast<std::int64_t>(se) : se) * 4;
		const std::size_t vi = se < 0 ? l.edges.At<std::uint16_t>(eo + 2) : l.edges.At<std::uint16_t>(eo);
		poly.push_back({ l.verts.At<float>(vi * 12), l.verts.At<float>(vi * 12 + 4), l.verts.At<float>(vi * 12 + 8) });
	}
}

float FaceRest(const FacePoly &face, const float n[3], const std::vector<Box> &boxes, std::int32_t ox, std::int32_t oz, std::int32_t oy, float behind,
	std::vector<FacePoly> &rest)
{
	constexpr float kSliver = 0.01f;
	float flo[3] = { 1e30f, 1e30f, 1e30f }, fhi[3] = { -1e30f, -1e30f, -1e30f };
	for (const auto &v : face)
		for (int k = 0; k < 3; ++k)
		{
			flo[k] = std::min(flo[k], v[k]);
			fhi[k] = std::max(fhi[k], v[k]);
		}
	rest.clear();
	rest.push_back(face);
	std::vector<FacePoly> next;
	FacePoly cur, part;
	float dug = 0;
	for (const Box &b : boxes)
	{
		float a[3], c[3], lo[3], hi[3];
		blk::McToSource(b.x0, b.y0, b.z0, ox, oz, oy, a);
		blk::McToSource(b.x1, b.y1, b.z1, ox, oz, oy, c);
		bool apart = false;
		for (int k = 0; k < 3; ++k)
		{
			// P - behind * n in the box  <=>  P in the box moved by +behind * n
			lo[k] = std::min(a[k], c[k]) + behind * n[k];
			hi[k] = std::max(a[k], c[k]) + behind * n[k];
			apart = apart || fhi[k] < lo[k] || flo[k] > hi[k];
		}
		if (apart)
			continue;
		// The box's half-spaces, inside: h . p + d <= 0.
		float hn[6][3] = {}, hd[6];
		for (int k = 0; k < 3; ++k)
		{
			hn[2 * k][k] = -1;
			hd[2 * k] = lo[k];
			hn[2 * k + 1][k] = 1;
			hd[2 * k + 1] = -hi[k];
		}
		next.clear();
		for (const FacePoly &piece : rest)
		{
			cur = piece;
			for (int i = 0; i < 6 && !cur.empty(); ++i)
			{
				const float out[3] = { -hn[i][0], -hn[i][1], -hn[i][2] };
				KeepBelow(cur, out, -hd[i], part);  // outside this half-space: stays
				if (PolyArea(part) > kSliver)
					next.push_back(part);
				KeepBelow(cur, hn[i], hd[i], part);
				cur.swap(part);
			}
			dug += PolyArea(cur);  // what is left is inside the box
		}
		rest.swap(next);
	}
	return dug;
}

// ---- the crust ------------------------------------------------------------------------------
namespace
{
std::uint64_t SectionKey(std::int32_t sx, std::int32_t sy, std::int32_t sz)
{
	return (static_cast<std::uint64_t>(static_cast<std::uint32_t>(sx) & 0x1fffff) << 42) |
		(static_cast<std::uint64_t>(static_cast<std::uint32_t>(sy) & 0x1fffff) << 21) | (static_cast<std::uint64_t>(static_cast<std::uint32_t>(sz) & 0x1fffff));
}
std::int32_t SectionOf(std::int32_t v) { return v >> 4; }  // arithmetic shift: floor division by 16

void Cross(const float a[3], const float b[3], float out[3])
{
	out[0] = a[1] * b[2] - a[2] * b[1];
	out[1] = a[2] * b[0] - a[0] * b[2];
	out[2] = a[0] * b[1] - a[1] * b[0];
}
bool Normalize(float v[3])
{
	const float l = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	if (!(l > 1e-6f))
		return false;
	for (int k = 0; k < 3; ++k)
		v[k] /= l;
	return true;
}
void Centroid(const FacePoly &p, float c[3])
{
	c[0] = c[1] = c[2] = 0;
	for (const auto &v : p)
		for (int k = 0; k < 3; ++k)
			c[k] += v[k] / static_cast<float>(p.size());
}
}  // namespace

void DugCells::Build(const std::vector<Box> &boxes)
{
	boxes_ = boxes;
	index_.clear();
	for (std::size_t i = 0; i < boxes_.size(); ++i)
	{
		const Box &b = boxes_[i];
		if (b.x0 >= b.x1 || b.y0 >= b.y1 || b.z0 >= b.z1)
			continue;
		for (std::int32_t sx = SectionOf(b.x0); sx <= SectionOf(b.x1 - 1); ++sx)
			for (std::int32_t sy = SectionOf(b.y0); sy <= SectionOf(b.y1 - 1); ++sy)
				for (std::int32_t sz = SectionOf(b.z0); sz <= SectionOf(b.z1 - 1); ++sz)
					index_.emplace_back(SectionKey(sx, sy, sz), static_cast<std::uint32_t>(i));
	}
	std::sort(index_.begin(), index_.end());
}

bool DugCells::IsDug(std::int32_t x, std::int32_t y, std::int32_t z) const
{
	const std::uint64_t key = SectionKey(SectionOf(x), SectionOf(y), SectionOf(z));
	auto it = std::lower_bound(index_.begin(), index_.end(), std::make_pair(key, std::uint32_t(0)));
	for (; it != index_.end() && it->first == key; ++it)
	{
		const Box &b = boxes_[it->second];
		if (x >= b.x0 && x < b.x1 && y >= b.y0 && y < b.y1 && z >= b.z0 && z < b.z1)
			return true;
	}
	return false;
}

void CrustFold(const CrustPiece &piece, const float n[3], float d, const float p[3], float out[3])
{
	const float h = d - Dot3(n, p);
	for (int k = 0; k < 3; ++k)
		out[k] = p[k] + h * n[k] + h * piece.fold[k];
}

std::size_t FaceCrust(const FacePoly &face, const float n[3], float d, const std::vector<Box> &boxes, const DugCells &dug, std::int32_t ox, std::int32_t oz,
	std::int32_t oy, float behind, float cap, float probe, SolidDepthFn depth, const void *user, std::vector<CrustPiece> &out)
{
	if (face.size() < 3 || !(cap > 0.0f) || !depth)
		return 0;
	probe = std::max(probe, cap);
	// The region the band can reach (Source units): the face's bounds and the same moved down by cap.
	float rlo[3] = { 1e30f, 1e30f, 1e30f }, rhi[3] = { -1e30f, -1e30f, -1e30f };
	for (const auto &v : face)
		for (int k = 0; k < 3; ++k)
		{
			rlo[k] = std::min({ rlo[k], v[k], v[k] - cap * n[k] });
			rhi[k] = std::max({ rhi[k], v[k], v[k] - cap * n[k] });
		}
	for (int k = 0; k < 3; ++k)
	{
		rlo[k] -= 1.0f;
		rhi[k] += 1.0f;
	}
	// The face's outline as planes: keep s . p + s[3] <= 0 (inside, grown by 0.05).
	float fc[3];
	Centroid(face, fc);
	std::vector<std::array<float, 4>> sides;
	for (std::size_t i = 0; i < face.size(); ++i)
	{
		const auto &a = face[i], &b = face[(i + 1) % face.size()];
		const float e[3] = { b[0] - a[0], b[1] - a[1], b[2] - a[2] };
		float w[3];
		Cross(n, e, w);
		if (!Normalize(w))
			continue;
		const float rel[3] = { fc[0] - a[0], fc[1] - a[1], fc[2] - a[2] };
		if (Dot3(w, rel) < 0)
			for (float &c : w)
				c = -c;
		sides.push_back({ -w[0], -w[1], -w[2], Dot3(w, a.data()) - 0.05f });  // w . (p - a) >= -0.05
	}
	// The region's MC cells (Source x = 40 (mx - ox), y = -40 (mz - oz), z = 40 my - oy).
	auto cellOf = [](double v) { return static_cast<std::int32_t>(std::floor(v / 40.0)); };
	const std::int32_t mlo[3] = { cellOf(rlo[0]) + ox, cellOf(double(rlo[2]) + oy), cellOf(-double(rhi[1])) + oz };
	const std::int32_t mhi[3] = { cellOf(rhi[0]) + ox, cellOf(double(rhi[2]) + oy), cellOf(-double(rlo[1])) + oz };
	const std::size_t before = out.size();
	FacePoly sq, a, b;
	const float down[3] = { -n[0], -n[1], -n[2] };
	// MC axis k (0 x, 1 y, 2 z) as a Source unit vector.
	const float axis[3][3] = { { 1, 0, 0 }, { 0, 0, 1 }, { 0, -1, 0 } };
	for (const Box &box : boxes)
	{
		const std::int32_t blo[3] = { box.x0, box.y0, box.z0 }, bhi[3] = { box.x1, box.y1, box.z1 };
		std::int32_t lo[3], hi[3];  // the box's cells in the region, half-open
		bool apart = false;
		for (int k = 0; k < 3; ++k)
		{
			lo[k] = std::max(blo[k], mlo[k]);
			hi[k] = std::min(bhi[k], mhi[k] + 1);
			apart = apart || lo[k] >= hi[k];
		}
		if (apart)
			continue;
		for (int k = 0; k < 3; ++k)
			for (int s = -1; s <= 1; s += 2)
			{
				float o[3];  // out of the hole, into the neighbour
				for (int c = 0; c < 3; ++c)
					o[c] = axis[k][c] * static_cast<float>(s);
				if (std::fabs(Dot3(o, n)) > 0.7f)
					continue;  // about parallel to the face: not a wall of its cut
				const std::int32_t layer = s > 0 ? bhi[k] - 1 : blo[k];  // the box's cells on this side
				if (layer < lo[k] || layer >= hi[k])
					continue;
				float e[3];  // along the cut line
				Cross(n, o, e);
				if (!Normalize(e))
					continue;
				const int u = (k + 1) % 3, v = (k + 2) % 3;
				for (std::int32_t cu = lo[u]; cu < hi[u]; ++cu)
					for (std::int32_t cv = lo[v]; cv < hi[v]; ++cv)
					{
						std::int32_t nb[3];
						nb[k] = layer + s;
						nb[u] = cu;
						nb[v] = cv;
						if (dug.IsDug(nb[0], nb[1], nb[2]))
							continue;  // dug next door: no wall here
						// The square between the cell and its neighbour.
						sq.clear();
						const int corners[4][2] = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };
						for (const auto &cr : corners)
						{
							double m[3];
							m[k] = static_cast<double>(layer) + (s > 0 ? 1.0 : 0.0);
							m[u] = static_cast<double>(cu + cr[0]);
							m[v] = static_cast<double>(cv + cr[1]);
							float p[3];
							blk::McToSource(m[0], m[1], m[2], ox, oz, oy, p);
							sq.push_back({ p[0], p[1], p[2] });
						}
						// Under the face (n . p <= d), at most cap deep, inside its outline.
						KeepBelow(sq, n, -d - 0.01f, a);
						if (a.empty())
							continue;
						KeepBelow(a, down, d - cap, b);
						for (const auto &sd : sides)
						{
							if (b.empty())
								break;
							KeepBelow(b, sd.data(), sd[3], a);
							b.swap(a);
						}
						if (b.empty() || PolyArea(b) < 0.25f)
							continue;
						// The solid's depth just past the wall, under the face beside the cut: the least of
						// the band's two ends and middle along the cut line.
						float c[3];
						Centroid(b, c);
						float emin = 1e30f, emax = -1e30f;
						for (const auto &pv : b)
						{
							emin = std::min(emin, Dot3(e, pv.data()));
							emax = std::max(emax, Dot3(e, pv.data()));
						}
						const float ec = Dot3(e, c), hc = d - Dot3(n, c);
						float lip[3];  // the band's middle on the cut line
						for (int q = 0; q < 3; ++q)
							lip[q] = c[q] + hc * n[q];
						const float along[3] = { emin + 1.0f - ec, 0.0f, emax - 1.0f - ec };
						constexpr float kStart = 0.5f;  // the probe starts this far under the face
						float raw = probe;
						for (int i = 0; i < 3; ++i)
						{
							if (i != 1 && emax - emin < 2.0f)
								continue;
							float ps[3];
							for (int q = 0; q < 3; ++q)
								ps[q] = lip[q] + along[i] * e[q] + o[q] * 1.0f - n[q] * kStart;
							raw = std::min(raw, depth(user, ps, down, probe - kStart) + kStart);
						}
						if (raw <= kStart + 0.01f)
							continue;  // no solid under the face there
						float t = raw;
						if (raw < probe - 0.01f)
						{
							// Through the slab: is the face on its far side cut at this wall too? Then each
							// side's band takes half.
							float q[3];
							for (int i = 0; i < 3; ++i)
								q[i] = lip[i] - (raw - behind) * n[i] - o[i] * 0.5f;
							const double mx = q[0] / 40.0 + ox, my = (q[2] + static_cast<double>(oy)) / 40.0, mz = -q[1] / 40.0 + oz;
							if (dug.IsDug(static_cast<std::int32_t>(std::floor(mx)), static_cast<std::int32_t>(std::floor(my)), static_cast<std::int32_t>(std::floor(mz))))
								t = raw * 0.5f;
						}
						t = std::min(t, cap);
						if (t < cap)
						{
							KeepBelow(b, down, d - t, a);
							b.swap(a);
							if (b.empty() || PolyArea(b) < 0.25f)
								continue;
						}
						CrustPiece piece;
						piece.poly = b;
						piece.depth = t;
						float f[3];
						const float on = -Dot3(o, n);
						for (int i = 0; i < 3; ++i)
						{
							piece.wallN[i] = -o[i];
							f[i] = -o[i] - on * n[i];
						}
						if (!Normalize(f))
							continue;
						for (int i = 0; i < 3; ++i)
							piece.fold[i] = f[i];
						OrientClockwise(piece.poly, piece.wallN);
						out.push_back(std::move(piece));
					}
			}
	}
	return out.size() - before;
}

namespace
{
struct DepthWalk
{
	const gmodcraft::mapcol::BspTree &tree;
	float p[3], dir[3];
	std::size_t budget;
	// The first t in [t0, t1] in a leaf that isn't solid, or +inf.
	float First(std::int32_t node, float t0, float t1, int level)
	{
		if (budget == 0 || level > 4096)
			return t0;  // a broken tree: stop here
		--budget;
		if (node < 0)
			return (tree.contents[std::size_t(-(std::int64_t(node) + 1))] & gmodcraft::mapcol::kContentsSolid) ? INFINITY : t0;
		const auto &nd = tree.nodes[std::size_t(node)];
		const auto &q = tree.planes[std::size_t(nd.plane)];
		const float base = Dot3(q.n, p) + q.d, slope = Dot3(q.n, dir);
		const float d0 = base + slope * t0, d1 = base + slope * t1;
		if (d0 >= 0.0f && d1 >= 0.0f)
			return First(nd.child[0], t0, t1, level + 1);
		if (d0 < 0.0f && d1 < 0.0f)
			return First(nd.child[1], t0, t1, level + 1);
		const float tm = t0 + (t1 - t0) * d0 / (d0 - d1);
		const int nearSide = d0 >= 0.0f ? 0 : 1;
		const float r = First(nd.child[nearSide], t0, tm, level + 1);
		if (r < INFINITY)
			return r;
		return First(nd.child[1 - nearSide], tm, t1, level + 1);
	}
};
}  // namespace

float BspSolidDepth(const gmodcraft::mapcol::BspTree &tree, const float p[3], const float dir[3], float max)
{
	if (!tree.ok || !(max > 0.0f))
		return 0.0f;
	DepthWalk w{ tree, { p[0], p[1], p[2] }, { dir[0], dir[1], dir[2] }, tree.nodes.size() * 4 + 64 };
	return std::min(w.First(tree.head, 0.0f, max, 0), max);
}

namespace
{
constexpr float kBrushCell = 256.0f;
std::int32_t BrushCell(float v) { return static_cast<std::int32_t>(std::floor(v / kBrushCell)); }
}  // namespace

void SolidBrushes::Build(const MapLump &brushes, const MapLump &sides, const MapLump &planes)
{
	brushes_.clear();
	planes_.clear();
	grid_.clear();
	constexpr std::int32_t kSolid = 0x1;
	const std::size_t nb = brushes.n / 12, ns = sides.n / 8, np = planes.n / kMapPlaneSize;
	for (std::size_t i = 0; i < nb; ++i)
	{
		const std::int32_t first = brushes.At<std::int32_t>(i * 12), count = brushes.At<std::int32_t>(i * 12 + 4),
						   contents = brushes.At<std::int32_t>(i * 12 + 8);
		if (!(contents & kSolid) || first < 0 || count < 4 || std::size_t(first) + std::size_t(count) > ns)
			continue;
		Brush b{ static_cast<std::uint32_t>(planes_.size()), 0, { -1e30f, -1e30f, -1e30f }, { 1e30f, 1e30f, 1e30f } };
		bool ok = true;
		for (std::int32_t s = first; s < first + count && ok; ++s)
		{
			const std::size_t pn = sides.At<std::uint16_t>(std::size_t(s) * 8);
			if (pn >= np)
			{
				ok = false;
				break;
			}
			std::array<float, 4> q;
			for (int k = 0; k < 4; ++k)
				q[std::size_t(k)] = planes.At<float>(pn * kMapPlaneSize + std::size_t(k) * 4);
			ok = std::isfinite(q[0]) && std::isfinite(q[1]) && std::isfinite(q[2]) && std::isfinite(q[3]);
			planes_.push_back(q);
			for (int k = 0; k < 3; ++k)  // an axial side bounds the brush on that axis
			{
				if (q[std::size_t(k)] > 0.999f)
					b.hi[k] = std::min(b.hi[k], q[3]);
				else if (q[std::size_t(k)] < -0.999f)
					b.lo[k] = std::max(b.lo[k], -q[3]);
			}
		}
		b.count = static_cast<std::uint32_t>(planes_.size()) - b.first;
		for (int k = 0; k < 3 && ok; ++k)
			ok = b.lo[k] > -1e29f && b.hi[k] < 1e29f && b.lo[k] <= b.hi[k];
		if (!ok)
		{
			planes_.resize(b.first);
			continue;
		}
		const std::int32_t c0[3] = { BrushCell(b.lo[0]), BrushCell(b.lo[1]), BrushCell(b.lo[2]) }, c1[3] = { BrushCell(b.hi[0]), BrushCell(b.hi[1]), BrushCell(b.hi[2]) };
		const double cells = double(c1[0] - c0[0] + 1) * double(c1[1] - c0[1] + 1) * double(c1[2] - c0[2] + 1);
		if (cells > 65536)
		{
			planes_.resize(b.first);  // a sky shell or the like: never under a dug face
			continue;
		}
		const auto id = static_cast<std::uint32_t>(brushes_.size());
		brushes_.push_back(b);
		for (std::int32_t x = c0[0]; x <= c1[0]; ++x)
			for (std::int32_t y = c0[1]; y <= c1[1]; ++y)
				for (std::int32_t z = c0[2]; z <= c1[2]; ++z)
					grid_.emplace_back(SectionKey(x, y, z), id);
	}
	std::sort(grid_.begin(), grid_.end());
}

const SolidBrushes::Brush *SolidBrushes::Inside(const float p[3]) const
{
	const std::uint64_t key = SectionKey(BrushCell(p[0]), BrushCell(p[1]), BrushCell(p[2]));
	for (auto it = std::lower_bound(grid_.begin(), grid_.end(), std::make_pair(key, std::uint32_t(0))); it != grid_.end() && it->first == key; ++it)
	{
		const Brush &b = brushes_[it->second];
		bool in = true;
		for (std::uint32_t i = b.first; i < b.first + b.count && in; ++i)
		{
			const auto &q = planes_[i];
			in = q[0] * p[0] + q[1] * p[1] + q[2] * p[2] - q[3] < -0.001f;
		}
		if (in)
			return &b;
	}
	return nullptr;
}

float SolidBrushes::Depth(const float p[3], const float dir[3], float max) const
{
	float t = 0, end = 0;
	for (int step = 0; step < 64 && t < max; ++step)
	{
		const float q[3] = { p[0] + t * dir[0], p[1] + t * dir[1], p[2] + t * dir[2] };
		const Brush *b = Inside(q);
		if (!b)
			return std::min(end, max);
		float exit = max;
		for (std::uint32_t i = b->first; i < b->first + b->count; ++i)
		{
			const auto &pl = planes_[i];
			const float s = pl[0] * dir[0] + pl[1] * dir[1] + pl[2] * dir[2];
			if (s > 1e-6f)
				exit = std::min(exit, (pl[3] - (pl[0] * q[0] + pl[1] * q[1] + pl[2] * q[2])) / s);
		}
		end = t + std::max(exit, 0.0f);
		t = end + 0.02f;  // just past this brush: inside the next one?
	}
	return std::min(end, max);
}

float MapSolidDepth(const gmodcraft::mapcol::BspTree *tree, const SolidBrushes *brushes, const float p[3], const float dir[3], float max)
{
	float t = 0;
	for (int step = 0; step < 64 && t < max; ++step)
	{
		const float q[3] = { p[0] + t * dir[0], p[1] + t * dir[1], p[2] + t * dir[2] };
		const float a = brushes ? brushes->Depth(q, dir, max - t) : 0.0f;
		const float b = tree ? BspSolidDepth(*tree, q, dir, max - t) : 0.0f;
		const float run = std::max(a, b);
		if (run < 0.01f)
			return t;
		t += run;
		if (t >= max)
			return max;
		// one step on: still solid (the next brush, or the tree's solid past a brush)?
		const float r[3] = { q[0] + (run + 0.05f) * dir[0], q[1] + (run + 0.05f) * dir[1], q[2] + (run + 0.05f) * dir[2] };
		const bool more = (brushes && brushes->Depth(r, dir, 0.1f) > 0.0f) || (tree && BspSolidDepth(*tree, r, dir, 0.1f) > 0.0f);
		if (!more)
			return t;
		t += 0.05f;
	}
	return std::min(t, max);
}
}  // namespace holes
}  // namespace gc
