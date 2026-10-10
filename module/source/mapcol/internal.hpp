// mapcol internals: bounds-checked reads and small vector helpers. Not part of the API.
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "mapcol.hpp"

namespace gmodcraft::mapcol::detail
{
	// Reads a T at byte offset `off` of `b` (little-endian host). False if any byte is outside.
	template <class T>
	inline bool Rd(Bytes b, std::int64_t off, T& out)
	{
		if (off < 0 || b.data == nullptr || static_cast<std::uint64_t>(off) > b.size ||
			b.size - static_cast<std::size_t>(off) < sizeof(T)) {
			return false;
		}
		std::memcpy(&out, b.data + off, sizeof(T));
		return true;
	}

	// True if [off, off + len) lies inside b.
	inline bool InRange(Bytes b, std::int64_t off, std::int64_t len)
	{
		return off >= 0 && len >= 0 && static_cast<std::uint64_t>(off) <= b.size &&
		       static_cast<std::uint64_t>(len) <= b.size - static_cast<std::size_t>(off);
	}

	inline Bytes Sub(Bytes b, std::int64_t off, std::int64_t len)
	{
		if (!InRange(b, off, len)) {
			return Bytes{};
		}
		return Bytes{ b.data + off, static_cast<std::size_t>(len) };
	}

	// Lump element count for a fixed record size; false if the size is not a multiple.
	inline bool Count(Bytes b, std::size_t rec, std::size_t& n)
	{
		n = rec ? b.size / rec : 0;
		return rec && b.size % rec == 0;
	}

	inline bool Finite3(const Vec3& v)
	{
		return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z);
	}

	// Finite and within +-1e6 Source units on every axis: anything farther is corrupt (maps span
	// +-16384) and would overflow float -> int casts downstream.
	inline bool Bounded3(const Vec3& v)
	{
		return Finite3(v) && std::fabs(v.x) <= 1e6f && std::fabs(v.y) <= 1e6f && std::fabs(v.z) <= 1e6f;
	}

	inline Vec3 Sub3(const Vec3& a, const Vec3& b) { return Vec3{ a.x - b.x, a.y - b.y, a.z - b.z }; }
	inline Vec3 Cross3(const Vec3& a, const Vec3& b)
	{
		return Vec3{ a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x };
	}
	inline float Dot3(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }

	std::string Lower(std::string s);

	// m * p + o (m from AngleMatrix).
	Vec3 Rotate(const float m[3][3], const Vec3& p, const Vec3& o);
	// Appends the 12 outward triangles of the box [mins, maxs] transformed by (m, o), each a copy
	// of `proto` with new vertices.
	void BoxTris(const Vec3& mins, const Vec3& maxs, const float m[3][3], const Vec3& o, Tri proto, std::vector<Tri>& out);

	// Locale-independent number parsing (the host process may have set a decimal-comma locale).
	// Parses a leading number from s + *pos, skipping spaces, advances *pos. False if none.
	bool ParseFloat(const std::string& s, std::size_t* pos, double& out);
	bool ParseInt(const std::string& s, std::int64_t& out);

	// Appends a convex built from the triangles mesh.tris[firstTri, end): outward planes (deduped),
	// bounds, majority material. Degenerate input (no non-degenerate face) -> false, nothing added
	// to convexes (the caller removes the triangles).
	bool FinishConvex(Mesh& mesh, const MaterialTable* mats, std::uint32_t firstTri, std::uint8_t kind,
		std::uint8_t bits, std::int32_t owner);

	// ---- KeyValues (Valve text format) ------------------------------------------------------
	struct KvNode
	{
		std::string         key, value;
		bool                block = false;
		std::vector<KvNode> children;
		const KvNode*       Find(const char* key) const;  // first child with that key (case-insensitive)
		std::string         Get(const char* key, const char* def = "") const;
	};
	// Parses `text` into root.children. Accepts quoted and bare tokens, // comments, [$cond]
	// suffixes (ignored), #include/#base lines (ignored). Depth and size are capped.
	bool ParseKeyValues(const char* text, std::size_t len, KvNode& root, std::string& err);
}
