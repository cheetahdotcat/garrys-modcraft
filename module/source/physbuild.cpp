// Tier 0: region convexes for VPhysics. See physbuild.hpp.
#include "physbuild.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace gc::pw
{
namespace
{
constexpr double kEps = 1e-4;  // units: a vertex this close to a plane is on it

double Dot(const double n[3], const V3 &p) { return n[0] * p.x + n[1] * p.y + n[2] * p.z; }

V3 Lerp(const V3 &a, const V3 &b, double t) { return { a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t }; }

bool Near(const V3 &a, const V3 &b)
{
	return std::fabs(a.x - b.x) < 1e-3 && std::fabs(a.y - b.y) < 1e-3 && std::fabs(a.z - b.z) < 1e-3;
}

void AddUnique(std::vector<V3> &v, const V3 &p)
{
	for (const V3 &q : v)
		if (Near(p, q))
			return;
	v.push_back(p);
}

// Orders points lying on a plane (normal n) by angle around their centroid.
void OrderOnPlane(std::vector<V3> &pts, const double n[3])
{
	V3 c{ 0, 0, 0 };
	for (const V3 &p : pts)
	{
		c.x += p.x;
		c.y += p.y;
		c.z += p.z;
	}
	const double k = 1.0 / static_cast<double>(pts.size());
	c = { c.x * k, c.y * k, c.z * k };
	// u: any unit vector perpendicular to n; v = n x u
	double u[3];
	if (std::fabs(n[0]) < 0.9)
	{
		u[0] = 0;
		u[1] = n[2];
		u[2] = -n[1];
	}
	else
	{
		u[0] = -n[2];
		u[1] = 0;
		u[2] = n[0];
	}
	const double ul = std::sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
	for (double &x : u)
		x /= ul;
	const double v[3] = { n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0] };
	std::vector<std::pair<double, V3>> a;
	a.reserve(pts.size());
	for (const V3 &p : pts)
	{
		const V3 d{ p.x - c.x, p.y - c.y, p.z - c.z };
		a.emplace_back(std::atan2(d.x * v[0] + d.y * v[1] + d.z * v[2], d.x * u[0] + d.y * u[1] + d.z * u[2]), p);
	}
	std::sort(a.begin(), a.end(), [](const auto &l, const auto &r) { return l.first < r.first; });
	for (std::size_t i = 0; i < a.size(); ++i)
		pts[i] = a[i].second;
}

bool Thick(const Poly &p)
{
	double lo[3], hi[3];
	if (!Bounds(p, lo, hi))
		return false;
	return hi[0] - lo[0] >= kMinThick && hi[1] - lo[1] >= kMinThick && hi[2] - lo[2] >= kMinThick;
}

bool Overlaps(const double lo[3], const double hi[3], const Box &b)
{
	for (int a = 0; a < 3; ++a)
		if (hi[a] <= b.lo[a] + kEps || lo[a] >= b.hi[a] - kEps)
			return false;
	return true;
}

void Emit(const Poly &p, const Box &region, RegionOutput &out)
{
	std::vector<V3> v;
	Vertices(p, v);
	for (const V3 &q : v)
	{
		out.verts.push_back(static_cast<float>(q.x - region.lo[0]));
		out.verts.push_back(static_cast<float>(q.y - region.lo[1]));
		out.verts.push_back(static_cast<float>(q.z - region.lo[2]));
	}
	out.counts.push_back(static_cast<std::uint32_t>(v.size()));
}
}  // namespace

bool PhysicsKind(std::uint8_t kind)
{
	return kind == mcol::kSrcWorld || kind == mcol::kSrcDisplacement || kind == mcol::kSrcStaticProp;
}

Poly BoxPoly(const Box &b)
{
	const double x0 = b.lo[0], y0 = b.lo[1], z0 = b.lo[2], x1 = b.hi[0], y1 = b.hi[1], z1 = b.hi[2];
	Poly p(6);
	p[0] = { { -1, 0, 0 }, -x0, { { x0, y0, z0 }, { x0, y1, z0 }, { x0, y1, z1 }, { x0, y0, z1 } } };
	p[1] = { { 1, 0, 0 }, x1, { { x1, y0, z0 }, { x1, y0, z1 }, { x1, y1, z1 }, { x1, y1, z0 } } };
	p[2] = { { 0, -1, 0 }, -y0, { { x0, y0, z0 }, { x0, y0, z1 }, { x1, y0, z1 }, { x1, y0, z0 } } };
	p[3] = { { 0, 1, 0 }, y1, { { x0, y1, z0 }, { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } } };
	p[4] = { { 0, 0, -1 }, -z0, { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y1, z0 }, { x0, y1, z0 } } };
	p[5] = { { 0, 0, 1 }, z1, { { x0, y0, z1 }, { x0, y1, z1 }, { x1, y1, z1 }, { x1, y0, z1 } } };
	return p;
}

bool Clip(Poly &p, const double n[3], double d)
{
	// all inside: nothing to do; all outside: empty
	bool anyOut = false, anyIn = false;
	for (const Face &f : p)
		for (const V3 &q : f.pts)
		{
			const double s = Dot(n, q) - d;
			if (s > kEps)
				anyOut = true;
			if (s < -kEps)
				anyIn = true;
		}
	if (!anyOut)
		return !p.empty();
	if (!anyIn)
	{
		p.clear();
		return false;
	}
	std::vector<V3> cap;
	Poly next;
	next.reserve(p.size() + 1);
	for (Face &f : p)
	{
		std::vector<V3> kept;
		const std::size_t m = f.pts.size();
		for (std::size_t i = 0; i < m; ++i)
		{
			const V3 &a = f.pts[i], &b = f.pts[(i + 1) % m];
			const double sa = Dot(n, a) - d, sb = Dot(n, b) - d;
			if (sa <= kEps)
				kept.push_back(a);
			if (std::fabs(sa) <= kEps)
				AddUnique(cap, a);
			if ((sa < -kEps && sb > kEps) || (sa > kEps && sb < -kEps))
			{
				const V3 x = Lerp(a, b, sa / (sa - sb));
				kept.push_back(x);
				AddUnique(cap, x);
			}
		}
		if (kept.size() >= 3 && kept.size() <= kMaxFaceVerts)
		{
			f.pts = std::move(kept);
			next.push_back(std::move(f));
		}
	}
	if (cap.size() >= 3 && cap.size() <= kMaxFaceVerts)
	{
		OrderOnPlane(cap, n);
		next.push_back({ { n[0], n[1], n[2] }, d, std::move(cap) });
	}
	p = std::move(next);
	if (p.size() < 4)
	{
		p.clear();
		return false;
	}
	return true;
}

void Vertices(const Poly &p, std::vector<V3> &out)
{
	out.clear();
	for (const Face &f : p)
		for (const V3 &q : f.pts)
			AddUnique(out, q);
}

bool Bounds(const Poly &p, double lo[3], double hi[3])
{
	bool any = false;
	for (const Face &f : p)
		for (const V3 &q : f.pts)
		{
			const double c[3] = { q.x, q.y, q.z };
			for (int a = 0; a < 3; ++a)
			{
				lo[a] = any ? std::min(lo[a], c[a]) : c[a];
				hi[a] = any ? std::max(hi[a], c[a]) : c[a];
			}
			any = true;
		}
	return any;
}

bool Carve(const Poly &in, const std::vector<Box> &boxes, std::vector<Poly> &out, std::size_t maxPieces)
{
	std::vector<Poly> work{ in }, next;
	for (const Box &b : boxes)
	{
		next.clear();
		for (Poly &piece : work)
		{
			double lo[3], hi[3];
			if (!Bounds(piece, lo, hi) || !Overlaps(lo, hi, b))
			{
				next.push_back(std::move(piece));
				continue;
			}
			// the box's 6 planes, inside = n . p <= d
			const double planes[6][4] = { { -1, 0, 0, -b.lo[0] }, { 1, 0, 0, b.hi[0] }, { 0, -1, 0, -b.lo[1] }, { 0, 1, 0, b.hi[1] },
				{ 0, 0, -1, -b.lo[2] }, { 0, 0, 1, b.hi[2] } };
			Poly rest = std::move(piece);
			for (const auto &pl : planes)
			{
				Poly outside = rest;
				const double on[3] = { -pl[0], -pl[1], -pl[2] };
				if (Clip(outside, on, -pl[3]) && Thick(outside))
					next.push_back(std::move(outside));
				if (!Clip(rest, pl, pl[3]))
					break;
			}
			if (next.size() > maxPieces)
			{
				out.insert(out.end(), next.begin(), next.end());
				return false;
			}
		}
		work.swap(next);
		if (work.size() > maxPieces)
		{
			out.insert(out.end(), work.begin(), work.end());
			return false;
		}
	}
	for (Poly &p : work)
		out.push_back(std::move(p));
	return true;
}

Box McBoxToSource(const mcol::McFrame &f, double x0, double y0, double z0, double x1, double y1, double z1)
{
	Box b;
	b.lo[0] = static_cast<float>((x0 - f.originX) * 40.0);
	b.hi[0] = static_cast<float>((x1 - f.originX) * 40.0);
	b.lo[1] = static_cast<float>(-(z1 - f.originZ) * 40.0);
	b.hi[1] = static_cast<float>(-(z0 - f.originZ) * 40.0);
	b.lo[2] = static_cast<float>(y0 * 40.0 - f.originYUnits);
	b.hi[2] = static_cast<float>(y1 * 40.0 - f.originYUnits);
	return b;
}

void MergeCells(const std::vector<std::array<int, 3>> &cells, const int lo[3], int size, std::vector<std::array<int, 6>> &out)
{
	out.clear();
	if (size <= 0 || size > 64)
		return;
	const int S = size;
	std::vector<std::uint8_t> grid(static_cast<std::size_t>(S * S * S), 0);
	auto at = [&](int x, int y, int z) -> std::uint8_t & { return grid[static_cast<std::size_t>(x + S * (z + S * y))]; };
	for (const auto &c : cells)
	{
		const int x = c[0] - lo[0], y = c[1] - lo[1], z = c[2] - lo[2];
		if (x >= 0 && y >= 0 && z >= 0 && x < S && y < S && z < S)
			at(x, y, z) = 1;
	}
	for (int y = 0; y < S; ++y)
		for (int z = 0; z < S; ++z)
			for (int x = 0; x < S; ++x)
			{
				if (!at(x, y, z))
					continue;
				int x1 = x + 1;
				while (x1 < S && at(x1, y, z))
					++x1;
				auto rowFull = [&](int yy, int zz) {
					for (int i = x; i < x1; ++i)
						if (!at(i, yy, zz))
							return false;
					return true;
				};
				int z1 = z + 1;
				while (z1 < S && rowFull(y, z1))
					++z1;
				int y1 = y + 1;
				for (;;)
				{
					if (y1 >= S)
						break;
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
							at(xx, yy, zz) = 0;
				out.push_back({ lo[0] + x, lo[1] + y, lo[2] + z, lo[0] + x1, lo[1] + y1, lo[2] + z1 });
			}
}

bool MapSolidAt(const mcol::Mesh &mesh, const mcol::RegionIndex &index, const mcol::McFrame &f, const float mc[3])
{
	const float lo[3] = { mc[0] - 0.01f, mc[1] - 0.01f, mc[2] - 0.01f }, hi[3] = { mc[0] + 0.01f, mc[1] + 0.01f, mc[2] + 0.01f };
	std::vector<std::uint32_t> ids;
	index.ConvexesInBox(lo, hi, ids);
	const mcol::Vec3 p = mcol::FromMc(f, mc);
	for (std::uint32_t id : ids)
	{
		if (id >= mesh.convexes.size())
			continue;
		const mcol::Convex &c = mesh.convexes[id];
		if (!PhysicsKind(c.kind) || c.firstPlane + c.planeCount > mesh.planes.size())
			continue;
		bool in = true;
		for (std::uint32_t i = c.firstPlane; i < c.firstPlane + c.planeCount && in; ++i)
		{
			const mcol::Plane &q = mesh.planes[i];
			in = q.n[0] * p.x + q.n[1] * p.y + q.n[2] * p.z + q.d <= 0.01f;
		}
		if (in)
			return true;
	}
	return false;
}

void StepWedges(const HalfOcc &o, const int lo[3], const int hi[3], const mcol::McFrame &f, std::vector<std::array<V3, 6>> &out,
	const std::function<bool(int, int, int)> *ground)
{
	// a lower tread cell: a Minecraft block, or (with `ground`) the map's solid
	auto low = [&](int x, int y, int z) { return o.At(x, y, z) || (ground != nullptr && (*ground)(x, y, z)); };
	auto src = [&](double hx, double hy, double hz) {
		const float mc[3] = { static_cast<float>(hx * 0.5), static_cast<float>(hy * 0.5), static_cast<float>(hz * 0.5) };
		const mcol::Vec3 v = mcol::FromMc(f, mc);
		return V3{ v.x, v.y, v.z };
	};
	// a riser at the upper cell (x, y, z) facing (dx, dz): the upper top is y + 1, the lower one y. Returns
	// the tread length usable (2, 1) or 0 when it isn't a half-block step down.
	auto len = [&](int x, int y, int z, int dx, int dz) {
		if (!o.At(x, y, z) || o.At(x, y + 1, z))
			return 0;
		const int ax = x + dx, az = z + dz;
		if (o.At(ax, y, az) || !low(ax, y - 1, az) || o.At(ax, y + 1, az))
			return 0;
		const int bx = ax + dx, bz = az + dz;
		return (!o.At(bx, y, bz) && low(bx, y - 1, bz) && !o.At(bx, y + 1, bz)) ? 2 : 1;
	};
	const int dirs[4][2] = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
	for (const auto &d : dirs)
	{
		const int dx = d[0], dz = d[1];
		const bool alongZ = dx != 0;  // risers run along z when the step faces x
		for (int y = lo[1]; y < hi[1]; ++y)
			for (int u = (alongZ ? lo[0] : lo[2]); u < (alongZ ? hi[0] : hi[2]); ++u)
			{
				int w = alongZ ? lo[2] : lo[0];
				const int wEnd = alongZ ? hi[2] : hi[0];
				while (w < wEnd)
				{
					const int x = alongZ ? u : w, z = alongZ ? w : u;
					const int L = len(x, y, z, dx, dz);
					if (L == 0)
					{
						++w;
						continue;
					}
					int w1 = w + 1;  // merge the run of cells with the same tread
					while (w1 < wEnd && len(alongZ ? u : w1, y, alongZ ? w1 : u, dx, dz) == L)
						++w1;
					// riser plane: the upper cell's face toward d; the wedge goes L cells out, y + 1 -> y
					std::array<V3, 6> p;
					for (int k = 0; k < 2; ++k)
					{
						const double ww = k == 0 ? w : w1;
						if (alongZ)
						{
							const double xr = dx > 0 ? u + 1 : u, xe = xr + dx * L;
							p[k * 3 + 0] = src(xr, y, ww);
							p[k * 3 + 1] = src(xr, y + 1, ww);
							p[k * 3 + 2] = src(xe, y, ww);
						}
						else
						{
							const double zr = dz > 0 ? u + 1 : u, ze = zr + dz * L;
							p[k * 3 + 0] = src(ww, y, zr);
							p[k * 3 + 1] = src(ww, y + 1, zr);
							p[k * 3 + 2] = src(ww, y, ze);
						}
					}
					out.push_back(p);
					w = w1;
				}
			}
	}
}

void BuildRegion(const RegionInput &in, RegionOutput &out)
{
	const auto t0 = std::chrono::steady_clock::now();
	out = RegionOutput{};
	const Poly region = BoxPoly(in.region);
	std::vector<Poly> pieces;
	auto diggable = [&](std::uint16_t material, std::uint8_t kind) {
		return in.mats != nullptr && !in.dug.empty() &&
			(mcol::ColFlags(*in.mats, material, kind) & gmodcraft::proto::kTriDiggable) != 0;
	};
	// Emits p (carved when diggable); false when a cap was hit.
	auto finish = [&](Poly &p, bool dig, std::uint32_t &counter) {
		if (!Thick(p))
			return true;
		pieces.clear();
		if (dig)
		{
			if (!Carve(p, in.dug, pieces))
			{
				out.truncated = true;
				return false;
			}
			if (pieces.size() != 1)
				++out.carved;
		}
		else
			pieces.push_back(std::move(p));
		for (const Poly &q : pieces)
		{
			if (!Thick(q))
			{
				++out.dropped;
				continue;
			}
			if (out.counts.size() >= in.maxConvexes)
			{
				out.truncated = true;
				return false;
			}
			Emit(q, in.region, out);
			++counter;
		}
		return true;
	};
	const mcol::Mesh *mesh = in.mesh;
	if (mesh != nullptr)
	{
		for (std::uint32_t id : in.convexIds)
		{
			if (id >= mesh->convexes.size())
				continue;
			const mcol::Convex &c = mesh->convexes[id];
			if (!PhysicsKind(c.kind) || c.planeCount < 4)
				continue;
			if (c.planeCount > kMaxPlanes || c.firstPlane + c.planeCount > mesh->planes.size())
			{
				out.truncated = true;
				continue;
			}
			Poly p = region;
			bool ok = true;
			for (std::uint32_t i = c.firstPlane; i < c.firstPlane + c.planeCount && ok; ++i)
			{
				const mcol::Plane &pl = mesh->planes[i];  // n . p + d <= 0 inside
				const double n[3] = { pl.n[0], pl.n[1], pl.n[2] };
				ok = Clip(p, n, -static_cast<double>(pl.d));
			}
			if (ok && !finish(p, diggable(c.material, c.kind), out.brushes))
				break;
		}
		for (std::uint32_t id : in.triIds)
		{
			if (out.truncated && out.counts.size() >= in.maxConvexes)
				break;
			if (id >= mesh->tris.size())
				continue;
			const mcol::Tri &t = mesh->tris[id];
			if (t.convex >= 0 || !PhysicsKind(t.kind))
				continue;
			const V3 v[3] = { { t.v[0].x, t.v[0].y, t.v[0].z }, { t.v[1].x, t.v[1].y, t.v[1].z }, { t.v[2].x, t.v[2].y, t.v[2].z } };
			const V3 e1{ v[1].x - v[0].x, v[1].y - v[0].y, v[1].z - v[0].z }, e2{ v[2].x - v[0].x, v[2].y - v[0].y, v[2].z - v[0].z };
			double n[3] = { e1.y * e2.z - e1.z * e2.y, e1.z * e2.x - e1.x * e2.z, e1.x * e2.y - e1.y * e2.x };
			const double len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			if (len < 1e-6)
				continue;
			for (double &x : n)
				x /= len;
			// displacements: outward winding, the solid is behind; polysoup: two-sided, a thin slab
			const bool disp = t.kind == mcol::kSrcDisplacement;
			const double top = Dot(n, v[0]) + (disp ? 0.0 : kSlabHalf);
			const double bottom = Dot(n, v[0]) - (disp ? kPrismDepth : kSlabHalf);
			Poly p = region;
			bool ok = Clip(p, n, top);
			const double nn[3] = { -n[0], -n[1], -n[2] };
			ok = ok && Clip(p, nn, -bottom);
			for (int e = 0; e < 3 && ok; ++e)
			{
				const V3 &a = v[e], &b = v[(e + 1) % 3], &o = v[(e + 2) % 3];
				const V3 ab{ b.x - a.x, b.y - a.y, b.z - a.z };
				double s[3] = { ab.y * n[2] - ab.z * n[1], ab.z * n[0] - ab.x * n[2], ab.x * n[1] - ab.y * n[0] };
				const double sl = std::sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
				if (sl < 1e-9)
				{
					ok = false;
					break;
				}
				for (double &x : s)
					x /= sl;
				if (Dot(s, o) - Dot(s, a) > 0)
					for (double &x : s)
						x = -x;
				ok = Clip(p, s, Dot(s, a));
			}
			if (ok && !finish(p, diggable(t.material, t.kind), out.prisms))
				break;
		}
	}
	for (const Box &b : in.blocks)
	{
		Box c = b;
		bool empty = false;
		for (int a = 0; a < 3; ++a)
		{
			c.lo[a] = std::max(c.lo[a], in.region.lo[a]);
			c.hi[a] = std::min(c.hi[a], in.region.hi[a]);
			empty = empty || c.hi[a] - c.lo[a] < kMinThick;
		}
		if (empty)
			continue;
		if (out.counts.size() >= in.maxConvexes)
		{
			out.truncated = true;
			break;
		}
		Emit(BoxPoly(c), in.region, out);
		++out.boxes;
	}
	for (const auto &w : in.wedges)
	{
		if (out.counts.size() >= in.maxConvexes)
		{
			// wedges are a nicety: past the cap they're dropped, the region stays usable
			out.wedgesDropped = static_cast<std::uint32_t>(in.wedges.size()) - out.wedges;
			break;
		}
		for (const V3 &q : w)
		{
			out.verts.push_back(static_cast<float>(q.x - in.region.lo[0]));
			out.verts.push_back(static_cast<float>(q.y - in.region.lo[1]));
			out.verts.push_back(static_cast<float>(q.z - in.region.lo[2]));
		}
		out.counts.push_back(6);
		++out.wedges;
	}
	out.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}
}  // namespace gc::pw
