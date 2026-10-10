// P5a: per-section solid bitsets and their box merge. See solidbox.hpp.
#include "solidbox.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>

namespace gc::sb
{
namespace
{
using Rows = std::uint16_t[16][16];  // [y][z], bit x

void ToRows(const std::uint8_t *bits, Rows rows)
{
	// bit x + 16z + 256y: row (y, z) is the 2 bytes at (16z + 256y) / 8.
	for (int y = 0; y < 16; ++y)
		for (int z = 0; z < 16; ++z)
		{
			int i = (16 * z + 256 * y) >> 3;
			rows[y][z] = static_cast<std::uint16_t>(bits[i] | bits[i + 1] << 8);
		}
}

int Ctz16(std::uint32_t v) { return __builtin_ctz(v); }

// Mask of bits [a, b).
std::uint16_t Span(int a, int b) { return static_cast<std::uint16_t>(((1u << b) - 1u) & ~((1u << a) - 1u)); }
}  // namespace

void MergeGreedy(const std::uint8_t *bits, std::vector<Box> &out)
{
	out.clear();
	Rows left;  // solid and not yet covered
	ToRows(bits, left);
	for (int y = 0; y < 16; ++y)
		for (int z = 0; z < 16; ++z)
			while (left[y][z] != 0)
			{
				std::uint32_t row = left[y][z];
				int x0 = Ctz16(row);
				// Run along x: the first clear bit at or above x0.
				int x1 = x0;
				while (x1 < 16 && (row >> x1 & 1u))
					++x1;
				std::uint16_t m = Span(x0, x1);
				int z1 = z + 1;
				while (z1 < 16 && (left[y][z1] & m) == m)
					++z1;
				int y1 = y + 1;
				for (; y1 < 16; ++y1)
				{
					bool ok = true;
					for (int zz = z; zz < z1 && ok; ++zz)
						ok = (left[y1][zz] & m) == m;
					if (!ok)
						break;
				}
				for (int yy = y; yy < y1; ++yy)
					for (int zz = z; zz < z1; ++zz)
						left[yy][zz] = static_cast<std::uint16_t>(left[yy][zz] & ~m);
				out.push_back(Box{ static_cast<std::uint8_t>(x0), static_cast<std::uint8_t>(y), static_cast<std::uint8_t>(z),
					static_cast<std::uint8_t>(x1), static_cast<std::uint8_t>(y1), static_cast<std::uint8_t>(z1) });
			}
}

void MergeColumns(const std::uint8_t *bits, std::vector<Box> &out)
{
	// Vertical runs per (x, z) column, merged along x where neighbours have the same run.
	out.clear();
	for (int z = 0; z < 16; ++z)
	{
		// Open boxes of the previous x column, keyed by run: extend when x continues.
		std::vector<std::size_t> open;  // indices into out with x1 == x
		for (int x = 0; x < 16; ++x)
		{
			std::vector<std::size_t> next;
			int y = 0;
			while (y < 16)
			{
				if (!BitAt(bits, x, y, z))
				{
					++y;
					continue;
				}
				int y0 = y;
				while (y < 16 && BitAt(bits, x, y, z))
					++y;
				std::size_t hit = SIZE_MAX;
				for (std::size_t i : open)
					if (out[i].y0 == y0 && out[i].y1 == y)
					{
						hit = i;
						break;
					}
				if (hit != SIZE_MAX)
				{
					out[hit].x1 = static_cast<std::uint8_t>(x + 1);
					next.push_back(hit);
				}
				else
				{
					out.push_back(Box{ static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y0), static_cast<std::uint8_t>(z),
						static_cast<std::uint8_t>(x + 1), static_cast<std::uint8_t>(y), static_cast<std::uint8_t>(z + 1) });
					next.push_back(out.size() - 1);
				}
			}
			open.swap(next);
		}
	}
}

int MergeBoxes(const std::uint8_t *bits, std::vector<Box> &out)
{
	MergeGreedy(bits, out);
	if (out.size() <= static_cast<std::size_t>(kFallbackBoxes))
		return kModeGreedy;
	std::vector<Box> cols;
	MergeColumns(bits, cols);
	if (cols.size() < out.size())
	{
		out.swap(cols);
		return kModeColumns;
	}
	return kModeGreedy;
}

namespace
{
// 32^3 octant grid as rows: grid[y][z] = 32-bit mask over x.
using Rows32 = std::uint32_t[32][32];

void HalfGrid(const std::uint8_t *bits, const std::uint8_t *shapes, Rows32 g)
{
	std::memset(g, 0, sizeof(Rows32));
	for (int y = 0; y < 16; ++y)
		for (int z = 0; z < 16; ++z)
			for (int x = 0; x < 16; ++x)
			{
				if (!BitAt(bits, x, y, z))
					continue;
				const int i = x + 16 * z + 256 * y;
				const int oct = shapes ? shapes[i] : 0;
				for (int o = 0; o < 8; ++o)
				{
					if (oct != 0 && !((oct >> o) & 1))
						continue;
					const int dx = o & 1, dy = (o >> 1) & 1, dz = (o >> 2) & 1;
					g[2 * y + dy][2 * z + dz] |= 1u << (2 * x + dx);
				}
			}
}

std::uint32_t Span32(int a, int b) { return (b >= 32 ? 0xFFFFFFFFu : ((1u << b) - 1u)) & ~((1u << a) - 1u); }

void Greedy32(Rows32 left, std::vector<Box> &out)
{
	out.clear();
	for (int y = 0; y < 32; ++y)
		for (int z = 0; z < 32; ++z)
			while (left[y][z] != 0)
			{
				const std::uint32_t row = left[y][z];
				const int x0 = __builtin_ctz(row);
				int x1 = x0;
				while (x1 < 32 && ((row >> x1) & 1))
					++x1;
				const std::uint32_t m = Span32(x0, x1);
				int z1 = z + 1;
				while (z1 < 32 && (left[y][z1] & m) == m)
					++z1;
				int y1 = y + 1;
				for (;;)
				{
					if (y1 >= 32)
						break;
					bool full = true;
					for (int zz = z; zz < z1 && full; ++zz)
						full = (left[y1][zz] & m) == m;
					if (!full)
						break;
					++y1;
				}
				for (int yy = y; yy < y1; ++yy)
					for (int zz = z; zz < z1; ++zz)
						left[yy][zz] &= ~m;
				out.push_back(Box{ static_cast<std::uint8_t>(x0), static_cast<std::uint8_t>(y), static_cast<std::uint8_t>(z),
					static_cast<std::uint8_t>(x1), static_cast<std::uint8_t>(y1), static_cast<std::uint8_t>(z1) });
			}
}

void Columns32(const Rows32 g, std::vector<Box> &out)
{
	out.clear();
	for (int z = 0; z < 32; ++z)
	{
		std::vector<std::size_t> open;
		for (int x = 0; x < 32; ++x)
		{
			std::vector<std::size_t> next;
			int y = 0;
			while (y < 32)
			{
				if (!((g[y][z] >> x) & 1))
				{
					++y;
					continue;
				}
				const int y0 = y;
				while (y < 32 && ((g[y][z] >> x) & 1))
					++y;
				std::size_t hit = SIZE_MAX;
				for (std::size_t i : open)
					if (out[i].y0 == y0 && out[i].y1 == y)
					{
						hit = i;
						break;
					}
				if (hit != SIZE_MAX)
				{
					out[hit].x1 = static_cast<std::uint8_t>(x + 1);
					next.push_back(hit);
				}
				else
				{
					out.push_back(Box{ static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y0), static_cast<std::uint8_t>(z),
						static_cast<std::uint8_t>(x + 1), static_cast<std::uint8_t>(y), static_cast<std::uint8_t>(z + 1) });
					next.push_back(out.size() - 1);
				}
			}
			open.swap(next);
		}
	}
}
}  // namespace

int MergeHalf(const std::uint8_t *bits, const std::uint8_t *shapes, std::vector<Box> &out)
{
	Rows32 g, work;
	HalfGrid(bits, shapes, g);
	std::memcpy(work, g, sizeof(Rows32));
	Greedy32(work, out);
	if (out.size() <= static_cast<std::size_t>(kFallbackBoxes))
		return kModeGreedy;
	std::vector<Box> cols;
	Columns32(g, cols);
	if (cols.size() < out.size())
	{
		out.swap(cols);
		return kModeColumns;
	}
	return kModeGreedy;
}

std::string PackBoxes(const std::vector<Box> &boxes)
{
	std::string s;
	s.resize(boxes.size() * 6);
	for (std::size_t i = 0; i < boxes.size(); ++i)
	{
		const Box &b = boxes[i];
		char *p = &s[i * 6];
		p[0] = static_cast<char>(b.x0), p[1] = static_cast<char>(b.y0), p[2] = static_cast<char>(b.z0);
		p[3] = static_cast<char>(b.x1), p[4] = static_cast<char>(b.y1), p[5] = static_cast<char>(b.z1);
	}
	return s;
}

void Store::MarkDirty(const SectionKey &k)
{
	if (m_dirtySet.insert(k).second)
		m_dirty.push_back(k);
}

void Store::ApplySolids(std::int32_t sx, std::int32_t sy, std::int32_t sz, std::uint32_t count, const std::uint8_t *bits)
{
	++m_stats.solidsMsgs;
	SectionKey k{ sx, sy, sz };
	if (count == 0 || bits == nullptr)
	{
		if (m_sections.erase(k) != 0)
			MarkDirty(k);
		else
			++m_stats.unchanged;
		return;
	}
	auto it = m_sections.find(k);
	if (it != m_sections.end() && std::memcmp(it->second.bits, bits, kBitsBytes) == 0)
	{
		++m_stats.unchanged;
		return;
	}
	Section &s = m_sections[k];
	std::memcpy(s.bits, bits, kBitsBytes);
	s.count = count;
	++s.version;
	MarkDirty(k);
}

void Store::ApplyShapes(std::int32_t sx, std::int32_t sy, std::int32_t sz, std::uint32_t count, const std::uint8_t *shapes)
{
	auto it = m_sections.find(SectionKey{ sx, sy, sz });
	if (it == m_sections.end())
	{
		++m_stats.unchanged;
		return;
	}
	Section &s = it->second;
	if (count == 0 || shapes == nullptr)
	{
		if (s.shapes.empty())
		{
			++m_stats.unchanged;
			return;
		}
		s.shapes.clear();
	}
	else
	{
		if (s.shapes.size() == static_cast<std::size_t>(kShapeBytes) && std::memcmp(s.shapes.data(), shapes, kShapeBytes) == 0)
		{
			++m_stats.unchanged;
			return;
		}
		s.shapes.assign(shapes, shapes + kShapeBytes);
	}
	++s.version;
	MarkDirty(it->first);
}

bool ParseMicro(const std::uint8_t *recs, std::size_t bytes, std::uint32_t count, std::vector<Box> &out)
{
	out.clear();
	std::size_t at = 0;
	for (std::uint32_t c = 0; c < count; ++c)
	{
		if (recs == nullptr || bytes - at < 3 || at > bytes)
			return false;
		const int index = recs[at] | recs[at + 1] << 8;
		const int n = recs[at + 2];
		at += 3;
		if (index >= 4096 || n == 0 || n > 64 || bytes - at < static_cast<std::size_t>(n) * 6)
			return false;
		const int bx = (index & 15) * kMicroUnits, bz = ((index >> 4) & 15) * kMicroUnits, by = (index >> 8) * kMicroUnits;
		for (int i = 0; i < n; ++i, at += 6)
		{
			const std::uint8_t *b = recs + at;
			for (int a = 0; a < 3; ++a)
				if (b[a] >= b[a + 3] || b[a + 3] > kMicroUnits)
					return false;
			out.push_back(Box{ static_cast<std::uint8_t>(bx + b[0]), static_cast<std::uint8_t>(by + b[1]), static_cast<std::uint8_t>(bz + b[2]),
				static_cast<std::uint8_t>(bx + b[3]), static_cast<std::uint8_t>(by + b[4]), static_cast<std::uint8_t>(bz + b[5]) });
		}
	}
	return at == bytes;
}

void MergeMicro(std::vector<Box> &boxes)
{
	// One sweep per axis: sort by the other two axes' ranges, then by the start along the axis,
	// and join runs whose end meets the next one's start. Two rounds catch most rectangles.
	auto lo = [](const Box &b, int a) { return a == 0 ? b.x0 : a == 1 ? b.y0 : b.z0; };
	auto hi = [](const Box &b, int a) { return a == 0 ? b.x1 : a == 1 ? b.y1 : b.z1; };
	for (int round = 0; round < 2; ++round)
		for (int a : { 0, 2, 1 })
		{
			const int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
			auto key = [&](const Box &b) { return std::array<int, 5>{ lo(b, a1), hi(b, a1), lo(b, a2), hi(b, a2), lo(b, a) }; };
			std::sort(boxes.begin(), boxes.end(), [&](const Box &l, const Box &r) { return key(l) < key(r); });
			std::vector<Box> out;
			out.reserve(boxes.size());
			for (const Box &b : boxes)
			{
				if (!out.empty())
				{
					Box &p = out.back();
					if (lo(p, a1) == lo(b, a1) && hi(p, a1) == hi(b, a1) && lo(p, a2) == lo(b, a2) && hi(p, a2) == hi(b, a2) && hi(p, a) == lo(b, a))
					{
						(a == 0 ? p.x1 : a == 1 ? p.y1 : p.z1) = hi(b, a);
						continue;
					}
				}
				out.push_back(b);
			}
			boxes.swap(out);
		}
}

void Store::ApplyMicro(std::int32_t sx, std::int32_t sy, std::int32_t sz, std::uint32_t count, const std::uint8_t *recs, std::size_t bytes)
{
	++m_stats.microMsgs;
	SectionKey k{ sx, sy, sz };
	std::vector<Box> boxes;
	if (count != 0 && !ParseMicro(recs, bytes, count, boxes))
	{
		++m_stats.microBad;
		boxes.clear();
	}
	MergeMicro(boxes);
	auto it = m_micro.find(k);
	if (boxes.empty())
	{
		if (it == m_micro.end())
		{
			++m_stats.unchanged;
			return;
		}
		m_micro.erase(it);
		MarkDirty(k);
		return;
	}
	if (it != m_micro.end() && it->second.size() == boxes.size() && std::memcmp(it->second.data(), boxes.data(), boxes.size() * sizeof(Box)) == 0)
	{
		++m_stats.unchanged;
		return;
	}
	m_micro[k] = std::move(boxes);
	MarkDirty(k);
}

const std::vector<Box> *Store::FindMicro(const SectionKey &k) const
{
	auto it = m_micro.find(k);
	return it == m_micro.end() ? nullptr : &it->second;
}

void Store::ClearAll()
{
	++m_stats.clears;
	for (const auto &kv : m_sections)
		MarkDirty(kv.first);
	m_sections.clear();
	for (const auto &kv : m_micro)
		MarkDirty(kv.first);
	m_micro.clear();
}

std::size_t Store::TakeDirty(std::size_t max, std::vector<SectionKey> &out)
{
	std::size_t n = 0;
	while (n < max && !m_dirty.empty())
	{
		SectionKey k = m_dirty.front();
		m_dirty.pop_front();
		m_dirtySet.erase(k);
		out.push_back(k);
		++n;
	}
	return n;
}

const Section *Store::Find(const SectionKey &k) const
{
	auto it = m_sections.find(k);
	return it == m_sections.end() ? nullptr : &it->second;
}

const Section *Store::Merged(const SectionKey &k)
{
	auto it = m_sections.find(k);
	if (it == m_sections.end())
		return nullptr;
	Section &s = it->second;
	if (s.mergedVersion != s.version)
	{
		auto t0 = std::chrono::steady_clock::now();
		s.mode = MergeHalf(s.bits, s.shapes.empty() ? nullptr : s.shapes.data(), s.boxes);
		s.mergedVersion = s.version;
		double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
		++m_stats.merges;
		if (s.mode == kModeColumns)
			++m_stats.fallbacks;
		m_stats.lastMergeMs = ms;
		m_stats.lastMergeBoxes = static_cast<std::uint32_t>(s.boxes.size());
		if (ms > m_stats.maxMergeMs)
			m_stats.maxMergeMs = ms;
	}
	return &s;
}
}  // namespace gc::sb
