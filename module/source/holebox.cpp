// Dug-cell boxes and their mask volume (P5b): see holebox.hpp.
#include "holebox.hpp"

#include <algorithm>
#include <bitset>

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
}  // namespace holes
}  // namespace gc
