// Bit counting for GCC/Clang and MSVC. GCC and Clang keep their builtins (unchanged code on Linux);
// MSVC gets _BitScanForward(64) (BSF, every x86-64 CPU) and a SWAR popcount (no POPCNT instruction
// assumed). Ctz of 0 is undefined, as with the builtins: callers only pass non-zero values.
#pragma once

#include <cstdint>

#if defined(_MSC_VER) && !defined(__clang__)
#include <intrin.h>
#endif

namespace gmodcraft::mapcol
{
inline int Popcount64(std::uint64_t v)
{
#if defined(__GNUC__) || defined(__clang__)
	return __builtin_popcountll(v);
#else
	v = v - ((v >> 1) & 0x5555555555555555ull);
	v = (v & 0x3333333333333333ull) + ((v >> 2) & 0x3333333333333333ull);
	v = (v + (v >> 4)) & 0x0F0F0F0F0F0F0F0Full;
	return static_cast<int>((v * 0x0101010101010101ull) >> 56);
#endif
}

inline int Ctz64(std::uint64_t v)
{
#if defined(__GNUC__) || defined(__clang__)
	return __builtin_ctzll(v);
#else
	unsigned long i = 0;
	_BitScanForward64(&i, v);
	return static_cast<int>(i);
#endif
}

inline int Ctz32(std::uint32_t v)
{
#if defined(__GNUC__) || defined(__clang__)
	return __builtin_ctz(v);
#else
	unsigned long i = 0;
	_BitScanForward(&i, v);
	return static_cast<int>(i);
#endif
}
}  // namespace gmodcraft::mapcol
