// SHA-256 (FIPS 180-4), header-only. Used for the multiplayer join tokens (P6b): the GMod server
// publishes the first 8 bytes of SHA-256(token) per player in HostPlayer::reserved, and the
// Minecraft server checks the token a player presents against it. Checked against the FIPS test
// vectors in module/test/token_test.cpp.
#pragma once

#include "os/os.hpp"

#include <cstddef>
#include <cstdio>
#include <cstdint>
#include <cstring>

namespace gc
{
class Sha256
{
public:
	Sha256() { Reset(); }

	void Reset()
	{
		static const std::uint32_t init[8] = { 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19 };
		std::memcpy(h_, init, sizeof h_);
		len_ = 0;
		fill_ = 0;
	}

	void Update(const void *data, std::size_t n)
	{
		const std::uint8_t *p = static_cast<const std::uint8_t *>(data);
		len_ += n;
		while (n > 0)
		{
			std::size_t take = 64 - fill_;
			if (take > n)
				take = n;
			std::memcpy(buf_ + fill_, p, take);
			fill_ += take;
			p += take;
			n -= take;
			if (fill_ == 64)
			{
				Block(buf_);
				fill_ = 0;
			}
		}
	}

	void Final(std::uint8_t out[32])
	{
		const std::uint64_t bits = len_ * 8;
		const std::uint8_t one = 0x80, zero = 0;
		Update(&one, 1);
		while (fill_ != 56)
			Update(&zero, 1);
		std::uint8_t lenBytes[8];
		for (int i = 0; i < 8; ++i)
			lenBytes[i] = static_cast<std::uint8_t>(bits >> (56 - 8 * i));
		Update(lenBytes, 8);
		for (int i = 0; i < 8; ++i)
			for (int j = 0; j < 4; ++j)
				out[4 * i + j] = static_cast<std::uint8_t>(h_[i] >> (24 - 8 * j));
	}

	static void Hash(const void *data, std::size_t n, std::uint8_t out[32])
	{
		Sha256 s;
		s.Update(data, n);
		s.Final(out);
	}

private:
	static std::uint32_t Rotr(std::uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

	void Block(const std::uint8_t *p)
	{
		static const std::uint32_t k[64] = {
			0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be,
			0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa,
			0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85,
			0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
			0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
			0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
		};
		std::uint32_t w[64];
		for (int i = 0; i < 16; ++i)
			w[i] = (std::uint32_t(p[4 * i]) << 24) | (std::uint32_t(p[4 * i + 1]) << 16) | (std::uint32_t(p[4 * i + 2]) << 8) | std::uint32_t(p[4 * i + 3]);
		for (int i = 16; i < 64; ++i)
		{
			const std::uint32_t s0 = Rotr(w[i - 15], 7) ^ Rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
			const std::uint32_t s1 = Rotr(w[i - 2], 17) ^ Rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
			w[i] = w[i - 16] + s0 + w[i - 7] + s1;
		}
		std::uint32_t a = h_[0], b = h_[1], c = h_[2], d = h_[3], e = h_[4], f = h_[5], g = h_[6], h = h_[7];
		for (int i = 0; i < 64; ++i)
		{
			const std::uint32_t S1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
			const std::uint32_t ch = (e & f) ^ (~e & g);
			const std::uint32_t t1 = h + S1 + ch + k[i] + w[i];
			const std::uint32_t S0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
			const std::uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
			const std::uint32_t t2 = S0 + maj;
			h = g;
			g = f;
			f = e;
			e = d + t1;
			d = c;
			c = b;
			b = a;
			a = t1 + t2;
		}
		h_[0] += a;
		h_[1] += b;
		h_[2] += c;
		h_[3] += d;
		h_[4] += e;
		h_[5] += f;
		h_[6] += g;
		h_[7] += h;
	}

	std::uint32_t h_[8];
	std::uint64_t len_;
	std::uint8_t buf_[64];
	std::size_t fill_;
};

// The first 8 bytes of SHA-256(token) (token = the exact string handed to Minecraft).
inline void JoinTokenHash(const char *token, std::size_t n, std::uint8_t hashPrefix[8])
{
	std::uint8_t d[32];
	Sha256::Hash(token, n, d);
	std::memcpy(hashPrefix, d, 8);
}

// A new join token: 16 random bytes from the OS CSPRNG (os::RandomBytes) as 32 lowercase hex chars, and the first 8
// bytes of SHA-256 of that hex string (what HostPlayer::reserved carries). False when no
// randomness could be read (no token then: never a predictable one).
inline bool NewJoinToken(char tokenHex[33], std::uint8_t hashPrefix[8])
{
	std::uint8_t raw[16];
	if (!os::RandomBytes(raw, sizeof raw))
		return false;
	static const char hex[] = "0123456789abcdef";
	for (int i = 0; i < 16; ++i)
	{
		tokenHex[2 * i] = hex[raw[i] >> 4];
		tokenHex[2 * i + 1] = hex[raw[i] & 15];
	}
	tokenHex[32] = '\0';
	JoinTokenHash(tokenHex, 32, hashPrefix);
	std::memset(raw, 0, sizeof raw);
	return true;
}
}  // namespace gc
