// The actor table (protocol ActorTable, host -> MC, seqlock): nearby GMod NPCs, NextBots and
// non-MC players, mirrored in Minecraft as hittable proxies (P4a). Both realms write one: the
// server link's is authoritative (the MC server spawns the proxies from it), the client link's
// carries the positions as the GMod client renders them (Minecraft's ProxySync moves the proxies it
// already has to them). Header-only and Lua-free, so module/test/link_test.cpp writes through the
// same code.
#pragma once

#include "link.hpp"

#include <cmath>
#include <cstring>

namespace gc
{
// One record, sanitised: non-finite numbers become 0 (healthFrac 1), the hull is clamped to
// [0.05, 32] blocks, healthFrac to [0, 1], the name is cut at a UTF-8 character boundary so it
// always ends in a NUL inside its 24 bytes.
inline P::ActorRecord MakeActor(std::uint32_t entId, std::uint32_t flags, double x, double y, double z, double yaw, double width, double height,
	double healthFrac, std::uint32_t tier, const char *name)
{
	auto fin = [](double v, double def) { return std::isfinite(v) ? v : def; };
	auto clamp = [](double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; };
	P::ActorRecord r{};
	r.entId = entId;
	r.flags = flags;
	r.x = static_cast<float>(fin(x, 0));
	r.y = static_cast<float>(fin(y, 0));
	r.z = static_cast<float>(fin(z, 0));
	r.yaw = static_cast<float>(fin(yaw, 0));
	r.width = static_cast<float>(clamp(fin(width, 0.6), 0.05, 32));
	r.height = static_cast<float>(clamp(fin(height, 1.8), 0.05, 32));
	r.healthFrac = static_cast<float>(clamp(fin(healthFrac, 1), 0, 1));
	r.tier = static_cast<std::uint16_t>(tier > 65535 ? 65535 : tier);
	if (name != nullptr)
	{
		std::size_t n = std::strlen(name);
		if (n > sizeof r.name - 1)
		{
			n = sizeof r.name - 1;
			while (n > 0 && (static_cast<unsigned char>(name[n]) & 0xC0) == 0x80)
				--n;  // name[n] continues a character: cut before that character starts
		}
		std::memcpy(r.name, name, n);
	}
	return r;
}

// Writes the whole table (count = n, at most kMaxActors) under the seqlock. Records with entId 0
// are skipped (0 = none in the protocol). Returns the count written.
inline std::uint32_t WriteActors(P::ActorTable *t, const P::ActorRecord *recs, std::size_t n)
{
	std::uint32_t count = 0;
	SeqWrite(t, [&](P::ActorTable &d) {
		for (std::size_t i = 0; i < n && count < P::kMaxActors; ++i)
			if (recs[i].entId != 0)
				d.actors[count++] = recs[i];
		d.count = count;
	});
	return count;
}
}  // namespace gc
