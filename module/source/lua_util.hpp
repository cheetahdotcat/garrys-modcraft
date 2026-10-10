// Small helpers for the Lua bindings. None of them raise Lua errors: GMod's LuaJIT unwinds with
// longjmp, which skips C++ destructors, so table fields are read leniently (defaults on a wrong
// type) and argument checks that can throw run before any C++ object with a destructor exists.
#pragma once

#include <GarrysMod/Lua/Interface.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>

namespace gc
{
using ILua = GarrysMod::Lua::ILuaBase;
namespace LT = GarrysMod::Lua::Type;

// t[key] as a number, or def when missing / not a finite number.
inline double FieldNum(ILua *L, int t, const char *key, double def = 0)
{
	L->GetField(t, key);
	double v = def;
	if (L->IsType(-1, LT::Number))
	{
		double x = L->GetNumber(-1);
		if (std::isfinite(x))
			v = x;
	}
	L->Pop();
	return v;
}

// Integer field clamped to [lo, hi] (non-integers are truncated toward zero).
inline double FieldInt(ILua *L, int t, const char *key, double lo, double hi, double def = 0)
{
	double v = std::trunc(FieldNum(L, t, key, def));
	return v < lo ? lo : v > hi ? hi : v;
}

inline bool FieldBool(ILua *L, int t, const char *key, bool def = false)
{
	L->GetField(t, key);
	bool v = def;
	if (L->IsType(-1, LT::Bool))
		v = L->GetBool(-1);
	L->Pop();
	return v;
}

// Copies a string field into buf (NUL-terminated, truncated). Returns its length, -1 if absent.
inline int FieldStr(ILua *L, int t, const char *key, char *buf, std::size_t cap)
{
	L->GetField(t, key);
	int n = -1;
	if (L->IsType(-1, LT::String))
	{
		unsigned int len = 0;
		const char *s = L->GetString(-1, &len);
		std::size_t m = len < cap - 1 ? len : cap - 1;
		std::memcpy(buf, s, m);
		buf[m] = 0;
		n = static_cast<int>(m);
	}
	else if (cap > 0)
		buf[0] = 0;
	L->Pop();
	return n;
}

// Optional numeric argument; def when absent or not a finite number.
inline double ArgNum(ILua *L, int idx, double def = 0)
{
	if (!L->IsType(idx, LT::Number))
		return def;
	double v = L->GetNumber(idx);
	return std::isfinite(v) ? v : def;
}

inline void SetNum(ILua *L, const char *key, double v)
{
	L->PushNumber(v);
	L->SetField(-2, key);
}
inline void SetBool(ILua *L, const char *key, bool v)
{
	L->PushBool(v);
	L->SetField(-2, key);
}
inline void SetStr(ILua *L, const char *key, const char *v)
{
	L->PushString(v != nullptr ? v : "");
	L->SetField(-2, key);
}
// A fixed-size char field from shared memory: never reads past n, even without a NUL.
inline void SetStrN(ILua *L, const char *key, const char *v, std::size_t n)
{
	std::size_t m = 0;
	while (m < n && v[m] != 0)
		++m;
	if (m == 0)
		L->PushString("");  // PushString(p, 0) would mean "NUL-terminated"
	else
		L->PushString(v, static_cast<unsigned int>(m));
	L->SetField(-2, key);
}

inline int PushFail(ILua *L, const char *msg)
{
	L->PushNil();
	L->PushString(msg);
	return 2;
}

// n bytes -> 2n lowercase hex chars + NUL.
inline void BytesToHex(const std::uint8_t *b, std::size_t n, char *out)
{
	static const char *d = "0123456789abcdef";
	for (std::size_t i = 0; i < n; ++i)
	{
		out[i * 2] = d[b[i] >> 4];
		out[i * 2 + 1] = d[b[i] & 15];
	}
	out[n * 2] = 0;
}

// Exactly 2n hex chars -> n bytes; false on anything else.
inline bool HexToBytes(const char *s, std::uint8_t *out, std::size_t n)
{
	auto nib = [](char c) -> int {
		if (c >= '0' && c <= '9')
			return c - '0';
		if (c >= 'a' && c <= 'f')
			return c - 'a' + 10;
		if (c >= 'A' && c <= 'F')
			return c - 'A' + 10;
		return -1;
	};
	for (std::size_t i = 0; i < n; ++i)
	{
		const int hi = nib(s[2 * i]);
		const int lo = hi < 0 ? -1 : nib(s[2 * i + 1]);
		if (hi < 0 || lo < 0)
			return false;
		out[i] = static_cast<std::uint8_t>(hi * 16 + lo);
	}
	return s[2 * n] == '\0';
}

// 16 bytes <-> 32 lowercase hex chars (MC UUIDs, big-endian as in the protocol).
inline void UuidToHex(const std::uint8_t *u, char out[33])
{
	static const char *d = "0123456789abcdef";
	for (int i = 0; i < 16; ++i)
	{
		out[i * 2] = d[u[i] >> 4];
		out[i * 2 + 1] = d[u[i] & 15];
	}
	out[32] = 0;
}

inline bool HexToUuid(const char *s, std::uint8_t out[16])
{
	auto nib = [](char c) -> int {
		if (c >= '0' && c <= '9')
			return c - '0';
		if (c >= 'a' && c <= 'f')
			return c - 'a' + 10;
		if (c >= 'A' && c <= 'F')
			return c - 'A' + 10;
		return -1;
	};
	int k = 0;
	for (const char *p = s; *p && k < 32; ++p)
	{
		if (*p == '-')
			continue;
		int v = nib(*p);
		if (v < 0)
			return false;
		if (k % 2 == 0)
			out[k / 2] = static_cast<std::uint8_t>(v << 4);
		else
			out[k / 2] |= static_cast<std::uint8_t>(v);
		++k;
	}
	return k == 32;
}

// SteamID64s don't fit a Lua number (double): they travel as decimal strings.
inline void PushU64(ILua *L, std::uint64_t v)
{
	char b[24];
	std::snprintf(b, sizeof b, "%llu", static_cast<unsigned long long>(v));
	L->PushString(b);
}
}  // namespace gc
