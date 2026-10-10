// P5a: block collision for GMod entities, the engine-free half (docs/DESIGN.md section 9).
// The MC server's solid-block bitsets (block ring kBlkSolids, one 16^3 section each) are kept per
// section and merged into axis-aligned boxes: one frozen entity per box, or one multi-convex entity
// per section past the entity cap (the Lua side: shared/blockent.lua, server/blockcol.lua).
//
// Merge: greedy 3D (runs along x, then grown along z, then along y) on 16-bit row masks. Above
// kFallbackBoxes boxes (checkerboard-like sections) the vertical column-run decomposition is
// computed too, and the smaller of the two is kept.
#pragma once

#include <cstdint>
#include <string>
#include <deque>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace gc::sb
{
inline constexpr int kBitsBytes = 512;      // 4096 bits: bit x + 16z + 256y (as the protocol)
inline constexpr int kFallbackBoxes = 512;  // greedy result above this: try column runs too

// A merged box in section-local MC block coordinates, half-open: [x0, x1) etc., 0..16.
struct Box
{
	std::uint8_t x0, y0, z0, x1, y1, z1;
};

enum MergeMode : int
{
	kModeGreedy = 0,
	kModeColumns = 1,
};

// bits: kBitsBytes, little-endian bit order (bit i = byte i/8, bit i%8). Clears `out` first.
// Returns the mode used.
int MergeBoxes(const std::uint8_t *bits, std::vector<Box> &out);
// The two decompositions on their own (tests).
void MergeGreedy(const std::uint8_t *bits, std::vector<Box> &out);
void MergeColumns(const std::uint8_t *bits, std::vector<Box> &out);

// v26 (T0b): half-block resolution. A section as a 32^3 grid of octants: a solid block fills its 8
// octants unless `shapes` (kShapeBytes, one octant byte per block: bit dx + 2 dy + 4 dz; 0 = a full
// cube) says which. Boxes in HALF-BLOCK units, 0..32. Same greedy / column-run choice as MergeBoxes.
inline constexpr int kShapeBytes = 4096;  // = proto kBlockShapeBytes
int MergeHalf(const std::uint8_t *bits, const std::uint8_t *shapes, std::vector<Box> &out);

inline bool BitAt(const std::uint8_t *bits, int x, int y, int z)
{
	int i = x + 16 * z + 256 * y;
	return (bits[i >> 3] >> (i & 7)) & 1;
}

struct SectionKey
{
	std::int32_t sx, sy, sz;
	bool operator==(const SectionKey &o) const { return sx == o.sx && sy == o.sy && sz == o.sz; }
};
struct SectionKeyHash
{
	std::size_t operator()(const SectionKey &k) const
	{
		std::uint64_t h = static_cast<std::uint32_t>(k.sx) * 0x9E3779B97F4A7C15ull;
		h ^= static_cast<std::uint32_t>(k.sy) * 0xC2B2AE3D27D4EB4Full + (h << 6) + (h >> 2);
		h ^= static_cast<std::uint32_t>(k.sz) * 0x165667B19E3779F9ull + (h << 6) + (h >> 2);
		return static_cast<std::size_t>(h);
	}
};

// Packed form of a box list, as sent to Lua and on to GMod clients: 6 bytes per box
// (x0 y0 z0 x1 y1 z1, MC section-local; Section::boxes are in half blocks, 0..32, since v26).
std::string PackBoxes(const std::vector<Box> &boxes);

// v36 kBlkMicro: microblock boxes, kept in a list of their own per section (not mixed into the
// half-block boxes) in EIGHTHS of a block, section-local: 0..128, half-open. ParseMicro reads a
// message's records (count cells of {u16 index, u8 n, n x 6 u8 block-local eighth box}); false when
// malformed (out is then unspecified). MergeMicro joins boxes that share a face exactly (sweeps
// along x, z, y), so a floor of covers becomes one box.
inline constexpr int kMicroUnits = 8;  // = proto kMicroEighths
bool ParseMicro(const std::uint8_t *recs, std::size_t bytes, std::uint32_t count, std::vector<Box> &out);
void MergeMicro(std::vector<Box> &boxes);

struct Section
{
	std::uint8_t bits[kBitsBytes];
	std::uint32_t count = 0;    // solid blocks
	std::uint32_t version = 0;  // bumps on every change of bits or shapes
	std::vector<std::uint8_t> shapes;  // v26: kShapeBytes octant bytes, empty = every solid block is a full cube
	// Merge cache (valid when mergedVersion == version).
	std::uint32_t mergedVersion = 0xFFFFFFFFu;
	int mode = kModeGreedy;
	std::vector<Box> boxes;  // HALF-BLOCK units (0..32), MergeHalf
};

struct StoreStats
{
	std::uint64_t solidsMsgs = 0, unchanged = 0, clears = 0, merges = 0, fallbacks = 0, microMsgs = 0, microBad = 0;
	double lastMergeMs = 0, maxMergeMs = 0;
	std::uint32_t lastMergeBoxes = 0;
};

// The host's copy of the MC server's solid sections (server realm).
class Store
{
public:
	// A kBlkSolids message: count 0 (bits may be null) drops the section. Unchanged bits don't
	// mark it dirty.
	void ApplySolids(std::int32_t sx, std::int32_t sy, std::int32_t sz, std::uint32_t count, const std::uint8_t *bits);
	// v26 kBlkShapes (after the section's kBlkSolids): count 0 drops the shapes. A section without
	// solid blocks ignores it. Unchanged shapes don't mark it dirty.
	void ApplyShapes(std::int32_t sx, std::int32_t sy, std::int32_t sz, std::uint32_t count, const std::uint8_t *shapes);
	// v36 kBlkMicro: the section's microblock boxes (count 0 or a malformed message: none). Kept
	// apart from the solid sections, so a section with only microblocks exists here alone.
	// Unchanged boxes don't mark it dirty (counted in Stats().unchanged).
	void ApplyMicro(std::int32_t sx, std::int32_t sy, std::int32_t sz, std::uint32_t count, const std::uint8_t *recs, std::size_t bytes);
	// The section's merged microblock boxes (eighths, 0..128), nullptr when it has none.
	const std::vector<Box> *FindMicro(const SectionKey &k) const;
	const std::unordered_map<SectionKey, std::vector<Box>, SectionKeyHash> &Micro() const { return m_micro; }
	// Solid blocks or microblocks.
	bool Has(const SectionKey &k) const { return Find(k) != nullptr || FindMicro(k) != nullptr; }
	// kBlkClearAll: every section is gone (each one reported dirty, as removed).
	void ClearAll();
	// Takes up to `max` dirty sections (oldest first, each once).
	std::size_t TakeDirty(std::size_t max, std::vector<SectionKey> &out);
	std::size_t DirtyCount() const { return m_dirty.size(); }
	// nullptr when the section has no solid blocks.
	const Section *Find(const SectionKey &k) const;
	// Merged boxes of a section (cached per version); nullptr when it has none.
	const Section *Merged(const SectionKey &k);
	std::size_t SectionCount() const { return m_sections.size(); }
	const std::unordered_map<SectionKey, Section, SectionKeyHash> &Sections() const { return m_sections; }
	const StoreStats &Stats() const { return m_stats; }

	// X1: the nearest solid / microblock box the segment p0 -> p1 (MC block coordinates) enters.
	// Section-level DDA (cells of 16 blocks); in each section with blocks, a slab test against its
	// merged half-block boxes and microblock boxes; the first section with a hit ends the walk (a
	// box's entry point lies in its own section). Boxes the segment starts inside (entry <= 0) are
	// skipped, so an eye inside a block sees out. Returns true with t in (0, 1] and the entered face's
	// outward normal (one axis, +-1). Merges sections on demand (as Merged).
	bool RayCast(const double p0[3], const double p1[3], double &t, int normal[3]);

private:
	void MarkDirty(const SectionKey &k);
	std::unordered_map<SectionKey, Section, SectionKeyHash> m_sections;
	std::unordered_map<SectionKey, std::vector<Box>, SectionKeyHash> m_micro;  // v36
	std::deque<SectionKey> m_dirty;
	std::unordered_set<SectionKey, SectionKeyHash> m_dirtySet;
	StoreStats m_stats;
};
}  // namespace gc::sb
