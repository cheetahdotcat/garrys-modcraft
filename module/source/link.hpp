// GmodCraft link layer: the host side of protocol v13 (protocol/gmodcraft_protocol.h).
// No Lua and no engine code in here, so it can be compiled into a plain host-side test program.
//
//   Link          one shared-memory mapping the host created (O_EXCL, nonce name), its discovery
//                 file, heartbeats, peer liveness and the host half of LinkStats
//   SeqWrite/Read seqlock writer / reader on a struct inside the mapping
//   EntryRing*    fixed-size entry rings (input ring, host event ring, MC event ring)
//   ByteRing*     byte rings (collision ring: host produces; render and block rings: MC produces)
#pragma once

#include "gmodcraft_protocol.h"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace gc
{
namespace P = gmodcraft::proto;

std::uint64_t NowNs();  // CLOCK_MONOTONIC ns (Java's System.nanoTime() on Linux)
inline double NowMs()
{
	return static_cast<double>(NowNs()) / 1.0e6;
}

// Discovery files and the running-Minecraft lock live here (shared with GMod's pressure-vessel
// container; $XDG_RUNTIME_DIR is not).
extern const char *const kDiscoveryDir;
std::string DiscoveryDir();  // kDiscoveryDir, or $GMODCRAFT_TEST_DISCOVERY_DIR (module/test only)
std::string ShmPrefix();     // "gmodcraft-", or $GMODCRAFT_TEST_SHM_PREFIX ("gmodcraft-test...", module/test only)

// ---- atomics on the shared mapping -----------------------------------------------------------
inline std::uint32_t LoadAcq32(const void *p)
{
	return __atomic_load_n(static_cast<const std::uint32_t *>(p), __ATOMIC_ACQUIRE);
}
inline std::uint64_t LoadAcq64(const void *p)
{
	return __atomic_load_n(static_cast<const std::uint64_t *>(p), __ATOMIC_ACQUIRE);
}
inline void StoreRel32(void *p, std::uint32_t v)
{
	__atomic_store_n(static_cast<std::uint32_t *>(p), v, __ATOMIC_RELEASE);
}
inline void StoreRel64(void *p, std::uint64_t v)
{
	__atomic_store_n(static_cast<std::uint64_t *>(p), v, __ATOMIC_RELEASE);
}
inline std::uint32_t Xchg32(void *p, std::uint32_t v)
{
	return __atomic_exchange_n(static_cast<std::uint32_t *>(p), v, __ATOMIC_SEQ_CST);
}

// ---- seqlocks --------------------------------------------------------------------------------
// Writer: seq odd, write, seq even. The host is the only writer of the regions it writes, so the
// counter can live in the mapping itself.
template <class T, class Fill>
void SeqWrite(T *region, Fill fill)
{
	std::uint32_t s = __atomic_load_n(&region->seq, __ATOMIC_RELAXED);
	if (s & 1)
		++s;  // never start from an odd value (a crashed writer)
	__atomic_store_n(&region->seq, s + 1, __ATOMIC_RELAXED);
	__atomic_thread_fence(__ATOMIC_RELEASE);
	fill(*region);
	__atomic_store_n(&region->seq, s + 2, __ATOMIC_RELEASE);
}

// Reader: copies the whole struct while seq is even and unchanged. Gives up after `budgetNs`
// (a JVM pause can hold seq odd for milliseconds): the caller then keeps its last good copy.
// Returns the seq read (even; 0 = never written), or -1 if it gave up.
template <class T>
long long SeqRead(const T *region, T *out, std::uint64_t budgetNs = 200000)
{
	std::uint64_t deadline = 0;
	for (int spin = 0;; ++spin)
	{
		std::uint32_t s1 = LoadAcq32(&region->seq);
		if (!(s1 & 1))
		{
			std::memcpy(static_cast<void *>(out), static_cast<const void *>(region), sizeof(T));
			__atomic_thread_fence(__ATOMIC_ACQUIRE);
			std::uint32_t s2 = __atomic_load_n(&region->seq, __ATOMIC_RELAXED);
			if (s1 == s2)
			{
				out->seq = s1;
				return s1;
			}
		}
		if (spin == 16)
			deadline = NowNs() + budgetNs;
		else if (spin > 16 && (spin & 15) == 0 && NowNs() > deadline)
			return -1;
	}
}

// ---- ring statistics (host half of LinkStats) ------------------------------------------------
struct RingCounter
{
	P::RingStats *out = nullptr;  // in the mapping (LinkStats::host.rings[i])
	std::uint64_t secondStartNs = 0;
	std::uint64_t secondBytes = 0;

	void Count(std::uint64_t messages, std::uint64_t bytes, std::uint64_t drops, std::uint64_t fill);
	void Fill(std::uint64_t fill);
	void Tick(std::uint64_t now);  // refreshes bytesPerSec about once a second
};

// ---- a link ----------------------------------------------------------------------------------
class Link
{
public:
	Link(P::LinkKind kind, std::uint64_t bytes, std::uint64_t offStats);
	~Link();
	Link(const Link &) = delete;
	Link &operator=(const Link &) = delete;

	// Creates the mapping and its discovery file. A fixed name from the environment
	// (GMODCRAFT_LINK / GMODCRAFT_SERVER_LINK) means no discovery file, as in tools/fake_host.py.
	bool Create(std::string *err);
	void Close();
	bool Open() const { return base_ != nullptr; }

	// Once per host frame (client) / tick (server): stamps the heartbeat, watches the peer's,
	// refreshes the host half of LinkStats.
	void Heartbeat(float tickMs, float frameMs);

	bool McAlive() const { return mcAlive_; }
	std::uint64_t McHeartbeatNs() const;
	double McHeartbeatAgeMs() const;  // our clock since Minecraft's heartbeat last changed
	std::uint64_t McNonce() const;

	const char *KindName() const { return kind_ == P::kLinkClient ? "client" : "server"; }
	const std::string &Name() const { return name_; }
	const std::string &DiscoveryPath() const { return discovery_; }
	const std::string &DiscoveryNote() const { return discoveryNote_; }
	// What the last Create() garbage-collected (stale gmodcraft-<kind>-<nonce> segments).
	std::uint32_t GcRemoved() const { return gcRemoved_; }
	const std::string &GcNote() const { return gcNote_; }
	std::uint64_t Nonce() const { return nonce_; }
	std::uint64_t Bytes() const { return bytes_; }
	std::uint8_t *Base() const { return base_; }
	template <class T>
	T *At(std::uint64_t off) const
	{
		return reinterpret_cast<T *>(base_ + off);
	}
	P::LinkSideStats *HostStats() const { return &At<P::LinkStats>(offStats_)->host; }
	const P::LinkSideStats *McStats() const { return &At<P::LinkStats>(offStats_)->mc; }
	RingCounter &Ring(std::uint32_t i) { return rings_[i]; }

private:
	void CollectStale();

	P::LinkKind kind_;
	std::uint64_t bytes_;
	std::uint64_t offStats_;
	std::uint8_t *base_ = nullptr;
	std::string name_;
	std::string discovery_;
	std::string discoveryNote_;
	std::uint32_t gcRemoved_ = 0;
	std::string gcNote_;
	std::uint64_t nonce_ = 0;
	std::uint64_t createdNs_ = 0;
	std::uint64_t lastMcBeat_ = 0;
	std::uint64_t lastMcChangeNs_ = 0;
	bool mcAlive_ = false;
	std::uint32_t peerDown_ = 0;
	std::uint64_t updates_ = 0;
	RingCounter rings_[P::kMaxStatRings];
	static std::uint32_t s_attachCount;
};

// ---- entry rings -----------------------------------------------------------------------------
// head @+0 (producer), tail @+0x40 (consumer), entries @+0x80; Entries is a power of two.
template <class E, std::uint32_t Entries>
struct EntryRingWriter
{
	std::uint8_t *base = nullptr;
	RingCounter *stats = nullptr;
	std::uint64_t head = 0;

	void Attach(std::uint8_t *ringBase, RingCounter *st)
	{
		base = ringBase;
		stats = st;
		head = LoadAcq64(base + 0x00);
	}
	bool Push(const E &e)
	{
		if (base == nullptr)
			return false;
		std::uint64_t tail = LoadAcq64(base + 0x40);
		if (head - tail >= Entries)
		{
			stats->Count(0, 0, 1, head - tail);
			return false;
		}
		std::memcpy(base + 0x80 + (head % Entries) * sizeof(E), &e, sizeof(E));
		++head;
		StoreRel64(base + 0x00, head);
		stats->Count(1, sizeof(E), 0, head - tail);
		return true;
	}
	// n entries published with ONE head store (a sequence the consumer must see whole, e.g. a dev
	// command and its text slots). All or nothing: false when they don't all fit.
	bool PushMany(const E *e, std::uint32_t n)
	{
		if (base == nullptr || n == 0 || n > Entries)
			return false;
		std::uint64_t tail = LoadAcq64(base + 0x40);
		if (head - tail + n > Entries)
		{
			stats->Count(0, 0, n, head - tail);
			return false;
		}
		for (std::uint32_t i = 0; i < n; ++i)
			std::memcpy(base + 0x80 + ((head + i) % Entries) * sizeof(E), &e[i], sizeof(E));
		head += n;
		StoreRel64(base + 0x00, head);
		stats->Count(n, n * sizeof(E), 0, head - tail);
		return true;
	}
};

template <class E, std::uint32_t Entries>
struct EntryRingReader
{
	std::uint8_t *base = nullptr;
	RingCounter *stats = nullptr;

	void Attach(std::uint8_t *ringBase, RingCounter *st)
	{
		base = ringBase;
		stats = st;
	}
	// Calls fn(const E&) for each pending entry; returns how many.
	template <class Fn>
	std::uint32_t Drain(Fn fn, std::uint32_t max = Entries)
	{
		if (base == nullptr)
			return 0;
		std::uint64_t head = LoadAcq64(base + 0x00);
		std::uint64_t tail = LoadAcq64(base + 0x40);
		std::uint64_t lost = 0;
		if (head < tail)
			tail = head;  // garbage (a restarted producer): start over
		else if (head - tail > Entries)
		{
			lost = head - tail - Entries;  // the producer lapped us: those are gone
			tail = head - Entries;
		}
		std::uint32_t n = 0;
		while (tail < head && n < max)
		{
			E e;
			std::memcpy(&e, base + 0x80 + (tail % Entries) * sizeof(E), sizeof(E));
			fn(e);
			++tail;
			++n;
		}
		StoreRel64(base + 0x40, tail);
		stats->Count(n, n * sizeof(E), lost, head - tail);
		return n;
	}
};

// ---- byte rings ------------------------------------------------------------------------------
// head @+0, tail @+0x40, data @+0x80; messages {u32 type, u32 payloadBytes} 8-aligned; type 0 = pad.
struct ByteRingWriter
{
	std::uint8_t *base = nullptr;
	std::uint64_t dataBytes = 0;
	RingCounter *stats = nullptr;
	std::uint64_t head = 0;
	std::uint64_t messages = 0;

	void Attach(std::uint8_t *ringBase, std::uint64_t ringBytes, RingCounter *st)
	{
		base = ringBase;
		dataBytes = ringBytes - 0x80;
		stats = st;
		head = LoadAcq64(base + 0x00);
	}
	std::uint64_t Free() const;
	// Reserves a message of payloadBytes; returns the payload pointer (or nullptr if full).
	// Commit() publishes it.
	std::uint8_t *Begin(std::uint32_t type, std::uint32_t payloadBytes);
	void Commit();

private:
	std::uint64_t pendingHead_ = 0;
	std::uint64_t pendingBytes_ = 0;
};

struct ByteRingReader
{
	std::uint8_t *base = nullptr;
	std::uint64_t dataBytes = 0;
	RingCounter *stats = nullptr;
	std::uint64_t messages = 0;
	std::uint64_t malformed = 0;

	void Attach(std::uint8_t *ringBase, std::uint64_t ringBytes, RingCounter *st)
	{
		base = ringBase;
		dataBytes = ringBytes - 0x80;
		stats = st;
	}
	// Consumes everything pending, calling fn(type, payload, payloadBytes) per message (fn may be
	// a no-op: P1 doesn't use the render / block rings yet, but they must not fill up).
	template <class Fn>
	std::uint32_t Drain(Fn fn);
};

template <class Fn>
std::uint32_t ByteRingReader::Drain(Fn fn)
{
	if (base == nullptr)
		return 0;
	std::uint64_t head = LoadAcq64(base + 0x00);
	std::uint64_t tail = LoadAcq64(base + 0x40);
	if (head < tail || head - tail > dataBytes)
	{
		++malformed;
		StoreRel64(base + 0x40, head);
		stats->Count(0, 0, 1, 0);
		return 0;
	}
	std::uint64_t start = tail;
	std::uint32_t n = 0;
	while (tail < head)
	{
		std::uint64_t pos = tail % dataBytes;
		if (pos + 8 > dataBytes)
		{
			tail += dataBytes - pos;  // can't hold even a header: implicit pad
			continue;
		}
		std::uint32_t type, payload;
		std::memcpy(&type, base + 0x80 + pos, 4);
		std::memcpy(&payload, base + 0x80 + pos + 4, 4);
		if (type == 0)
		{
			tail += dataBytes - pos;
			continue;
		}
		std::uint64_t msg = (8 + static_cast<std::uint64_t>(payload) + 7) & ~std::uint64_t(7);
		if (pos + msg > dataBytes || tail + msg > head)
		{
			++malformed;
			stats->Count(0, 0, 1, 0);
			tail = head;
			break;
		}
		fn(type, base + 0x80 + pos + 8, payload);
		tail += msg;
		++n;
	}
	StoreRel64(base + 0x40, tail);
	messages += n;
	stats->Count(n, tail - start, 0, head - tail);
	return n;
}

// ---- helpers ---------------------------------------------------------------------------------
std::uint32_t Fnv1a32(const char *s);
std::string Hex16(std::uint64_t v);
bool ParseU64(const char *s, std::uint64_t *out);  // decimal, whole string
bool MkdirP(const std::string &dir, unsigned mode);
bool WriteFileAtomic(const std::string &path, const std::string &data);  // tmp + rename
std::string DiscoveryShmName(const std::string &discoveryPath);         // its "shm" field ("" if none)
}  // namespace gc
