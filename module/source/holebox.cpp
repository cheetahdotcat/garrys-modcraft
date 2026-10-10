// Dug-cell boxes and their mask volume (P5b): see holebox.hpp.
#include "holebox.hpp"

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
			const int b = __builtin_ctzll(v);
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
}  // namespace holes
}  // namespace gc
