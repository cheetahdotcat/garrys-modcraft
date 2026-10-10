// GmodCraft link layer (see link.hpp). Both realms.
#include "link.hpp"
#include "privatedir.hpp"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <set>

#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace gc
{
const char *const kDiscoveryDir = "/dev/shm/gmodcraft";

std::string DiscoveryDir()
{
	// Tests (module/test) put their discovery files elsewhere, so they never touch a real session's.
	const char *t = std::getenv("GMODCRAFT_TEST_DISCOVERY_DIR");
	if (t != nullptr && t[0] == '/' && std::strstr(t, "..") == nullptr)
		return t;
	return kDiscoveryDir;
}
std::uint32_t Link::s_attachCount = 0;

std::uint64_t NowNs()
{
	timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return static_cast<std::uint64_t>(ts.tv_sec) * 1000000000ull + static_cast<std::uint64_t>(ts.tv_nsec);
}

std::uint32_t Fnv1a32(const char *s)
{
	std::uint32_t h = 0x811C9DC5u;
	for (; *s; ++s)
	{
		h ^= static_cast<std::uint8_t>(*s);
		h *= 0x01000193u;
	}
	return h;
}

std::string Hex16(std::uint64_t v)
{
	char b[17];
	std::snprintf(b, sizeof b, "%016llx", static_cast<unsigned long long>(v));
	return b;
}

bool ParseU64(const char *s, std::uint64_t *out)
{
	if (s == nullptr || *s == 0)
		return false;
	std::uint64_t v = 0;
	for (; *s; ++s)
	{
		if (*s < '0' || *s > '9')
			return false;
		std::uint64_t d = static_cast<std::uint64_t>(*s - '0');
		if (v > (UINT64_MAX - d) / 10)
			return false;
		v = v * 10 + d;
	}
	*out = v;
	return true;
}

bool MkdirP(const std::string &dir, unsigned mode)
{
	std::size_t pos = 0;
	while (pos != std::string::npos)
	{
		pos = dir.find('/', pos + 1);
		std::string cur = dir.substr(0, pos);
		if (!cur.empty() && mkdir(cur.c_str(), mode) != 0 && errno != EEXIST)
			return false;
	}
	return true;
}

bool WriteFileAtomic(const std::string &path, const std::string &data)
{
	std::string tmp = path + ".tmp";
	FILE *f = std::fopen(tmp.c_str(), "wb");
	if (f == nullptr)
		return false;
	bool wrote = data.empty() || std::fwrite(data.data(), 1, data.size(), f) == data.size();
	bool closed = std::fclose(f) == 0;  // always close, even after a short write
	if (!wrote || !closed || std::rename(tmp.c_str(), path.c_str()) != 0)
	{
		std::remove(tmp.c_str());
		return false;
	}
	return true;
}

namespace
{
bool RandomU64(std::uint64_t *out)
{
	int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return false;
	std::uint64_t v = 0;
	ssize_t n = read(fd, &v, sizeof v);
	close(fd);
	if (n != static_cast<ssize_t>(sizeof v))
		return false;
	*out = v;
	return true;
}

// The discovery file currently points at a live mapping that isn't ours: another host (a test
// harness, a second GMod) owns it. We take over, but say so.
std::string DiscoveryOwnerNote(const std::string &path)
{
	std::string shm = DiscoveryShmName(path);
	struct stat st;
	if (shm.empty() || stat(("/dev/shm/" + shm).c_str(), &st) != 0)
		return "";
	return "replaced a discovery file that pointed at a live mapping " + shm;
}

bool IsNonceName(const char *name, const std::string &prefix)
{
	if (std::strncmp(name, prefix.c_str(), prefix.size()) != 0)
		return false;
	const char *hex = name + prefix.size();
	if (std::strlen(hex) != 16)
		return false;
	for (const char *c = hex; *c; ++c)
		if (!((*c >= '0' && *c <= '9') || (*c >= 'a' && *c <= 'f')))
			return false;
	return true;
}
}  // namespace

std::string DiscoveryShmName(const std::string &path)
{
	FILE *f = std::fopen(path.c_str(), "rb");
	if (f == nullptr)
		return "";
	char buf[512];
	std::size_t n = std::fread(buf, 1, sizeof buf - 1, f);
	std::fclose(f);
	buf[n] = 0;
	const char *k = std::strstr(buf, "\"shm\"");
	if (k == nullptr)
		return "";
	const char *q1 = std::strchr(k + 5, '"');
	const char *q2 = q1 ? std::strchr(q1 + 1, '"') : nullptr;
	if (q1 == nullptr || q2 == nullptr || q2 - q1 > 200)
		return "";
	std::string shm(q1 + 1, q2);
	return shm.find('/') == std::string::npos ? shm : "";
}

// Segment names start with "gmodcraft-". Tests (module/test) set GMODCRAFT_TEST_SHM_PREFIX
// ("gmodcraft-test<id>-") so their own segments, and the stale-segment GC, stay in a namespace of
// their own and never see a real session's names (P7: a v16 test once unlinked a live v15 GMod's).
// Fails closed: a set but malformed GMODCRAFT_TEST_SHM_PREFIX returns "" and Link::Create refuses,
// so a broken test setup can never fall back to the real namespace.
std::string ShmPrefix()
{
	const char *t = std::getenv("GMODCRAFT_TEST_SHM_PREFIX");
	if (t == nullptr)
		return "gmodcraft-";
	if (std::strncmp(t, "gmodcraft-test", 14) != 0 || std::strlen(t) > 48 || t[std::strlen(t) - 1] != '-')
		return "";
	for (const char *c = t; *c; ++c)
		if (!((*c >= 'a' && *c <= 'z') || (*c >= '0' && *c <= '9') || *c == '-'))
			return "";
	return t;
}

namespace
{
// Every shm name some discovery file (*.json) names, in the real discovery dir and the test one.
std::set<std::string> DiscoveryNamedSegments()
{
	std::set<std::string> out;
	for (const std::string &d : { DiscoveryDir(), std::string(kDiscoveryDir) })
	{
		std::string why;
		if (!CheckPrivateDir(d, getuid(), &why))
			continue;  // not ours (or missing): its JSON names nothing we trust
		DIR *dir = opendir(d.c_str());
		if (dir == nullptr)
			continue;
		while (dirent *e = readdir(dir))
		{
			std::size_t n = std::strlen(e->d_name);
			if (n > 5 && std::strcmp(e->d_name + n - 5, ".json") == 0)
			{
				std::string shm = DiscoveryShmName(d + "/" + e->d_name);
				if (!shm.empty())
					out.insert(shm);
			}
		}
		closedir(dir);
	}
	return out;
}
}  // namespace

// A segment whose header we can't read as a GmodCraft link of our kind is only removed when its
// mtime is this old (tmpfs doesn't update mtime for writes through a mapping, so a live one can be
// "old" too: this only bounds how long junk lives).
constexpr double kGcForeignAgeMs = 10.0 * 60.0 * 1000.0;

// Removes stale gmodcraft-<kind>-<16 hex> segments that a killed GMod left behind (pkill skips
// gmod13_close; each client mapping is ~235 MB of RAM). A segment is unlinked only when it is
// provably dead; guards, all of which must pass:
//  * a regular file owned by our uid (opened O_NOFOLLOW, read only);
//  * its mtime is older than kHeartbeatTimeoutMs (another instance may have just created it and
//    not written its header yet);
//  * no discovery file (any *.json in the real or the test discovery dir) names it;
//  * our magic and link kind, ANY protocol version (LinkHeader is frozen across versions: the
//    heartbeats are always at kHHostHeartbeatNs / kHMcHeartbeatNs) -> neither the host's nor
//    Minecraft's heartbeat changed within kHeartbeatTimeoutMs;
//  * anything else (foreign magic, other kind, too short): only when its mtime is older than
//    kGcForeignAgeMs.
// It only ever unlinks: mappings still held elsewhere stay valid until unmapped.
void Link::CollectStale()
{
	gcRemoved_ = 0;
	gcNote_.clear();
	const std::uint64_t timeoutNs = static_cast<std::uint64_t>(P::kHeartbeatTimeoutMs) * 1000000ull;
	const std::string base = ShmPrefix();
	if (base.empty())
		return;  // malformed test prefix: never sweep anything
	std::string prefix = base + KindName() + "-";
	const std::set<std::string> named = DiscoveryNamedSegments();
	int dfd = open("/dev/shm", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
	if (dfd < 0)
		return;
	DIR *dir = fdopendir(dfd);
	if (dir == nullptr)
	{
		close(dfd);
		return;
	}
	timespec real;
	clock_gettime(CLOCK_REALTIME, &real);
	std::uint64_t now = NowNs();
	while (dirent *e = readdir(dir))
	{
		if (!IsNonceName(e->d_name, prefix) || named.count(e->d_name) != 0)
			continue;
		int fd = openat(dfd, e->d_name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
		if (fd < 0)
			continue;
		struct stat st;
		if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid())
		{
			close(fd);
			continue;
		}
		double ageMs = static_cast<double>(real.tv_sec - st.st_mtim.tv_sec) * 1e3 + static_cast<double>(real.tv_nsec - st.st_mtim.tv_nsec) / 1e6;
		if (ageMs < static_cast<double>(P::kHeartbeatTimeoutMs))
		{
			close(fd);
			continue;
		}
		bool link = false, sameVersion = false, live = false;
		if (st.st_size >= static_cast<off_t>(sizeof(P::LinkHeader)))
		{
			void *p = mmap(nullptr, sizeof(P::LinkHeader), PROT_READ, MAP_SHARED, fd, 0);
			if (p != MAP_FAILED)
			{
				const auto *h = static_cast<const P::LinkHeader *>(p);
				link = LoadAcq32(&h->magic) == P::kMagic && h->linkKind == kind_;
				sameVersion = link && LoadAcq32(&h->version) == P::kVersion;
				if (link)
				{
					for (std::uint64_t beat : { LoadAcq64(&h->hostHeartbeatNs), LoadAcq64(&h->mcHeartbeatNs) })
						if (beat != 0 && (beat > now || now - beat < timeoutNs))
							live = true;
				}
				munmap(p, sizeof(P::LinkHeader));
			}
		}
		close(fd);
		if (live || (!link && ageMs < kGcForeignAgeMs))
			continue;
		if (unlinkat(dfd, e->d_name, 0) == 0)
		{
			++gcRemoved_;
			if (gcNote_.size() < 400)
				gcNote_ += std::string(gcNote_.empty() ? "" : ", ") + e->d_name
					+ (sameVersion ? "" : link ? " (other version)" : " (other layout)");
		}
	}
	closedir(dir);  // closes dfd too
}

// ---- RingCounter -----------------------------------------------------------------------------
void RingCounter::Count(std::uint64_t messages, std::uint64_t bytes, std::uint64_t drops, std::uint64_t fill)
{
	if (out == nullptr)
		return;
	out->messages += messages;
	out->bytes += bytes;
	out->drops += drops;
	out->fill = fill;
	if (fill > out->highWater)
		out->highWater = fill;
	secondBytes += bytes;
}

void RingCounter::Fill(std::uint64_t fill)
{
	if (out == nullptr)
		return;
	out->fill = fill;
	if (fill > out->highWater)
		out->highWater = fill;
}

void RingCounter::Tick(std::uint64_t now)
{
	if (out == nullptr)
		return;
	if (secondStartNs == 0)
		secondStartNs = now;
	if (now - secondStartNs >= 1000000000ull)
	{
		double secs = static_cast<double>(now - secondStartNs) / 1e9;
		out->bytesPerSec = static_cast<std::uint64_t>(static_cast<double>(secondBytes) / secs);
		secondBytes = 0;
		secondStartNs = now;
	}
}

// ---- Link ------------------------------------------------------------------------------------
Link::Link(P::LinkKind kind, std::uint64_t bytes, std::uint64_t offStats) : kind_(kind), bytes_(bytes), offStats_(offStats)
{
}

Link::~Link()
{
	Close();
}

bool Link::Create(std::string *err)
{
	Close();
	const char *fixed = std::getenv(kind_ == P::kLinkClient ? "GMODCRAFT_LINK" : "GMODCRAFT_SERVER_LINK");
	bool useDiscovery = fixed == nullptr || *fixed == 0;
	if (!useDiscovery && (std::strchr(fixed, '/') != nullptr || std::strlen(fixed) > 200 || std::strcmp(fixed, "none") == 0))
	{
		*err = "GMODCRAFT_(SERVER_)LINK must be a plain shm name";
		return false;
	}
	if (ShmPrefix().empty())
	{
		*err = "GMODCRAFT_TEST_SHM_PREFIX must be gmodcraft-test<a-z0-9->- (at most 48 chars)";
		return false;
	}
	if (!RandomU64(&nonce_))
	{
		*err = "no /dev/urandom";
		return false;
	}
	CollectStale();
	name_ = useDiscovery ? ShmPrefix() + KindName() + "-" + Hex16(nonce_) : std::string(fixed);
	std::string path = "/" + name_;
	if (!useDiscovery)
		shm_unlink(path.c_str());  // a stale one from a killed run with the same fixed name
	int fd = shm_open(path.c_str(), O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
	if (fd < 0)
	{
		*err = std::string("shm_open ") + name_ + ": " + std::strerror(errno);
		name_.clear();
		return false;
	}
	if (ftruncate(fd, static_cast<off_t>(bytes_)) != 0)
	{
		*err = std::string("ftruncate: ") + std::strerror(errno);
		close(fd);
		shm_unlink(path.c_str());
		name_.clear();
		return false;
	}
	void *p = mmap(nullptr, bytes_, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	close(fd);
	if (p == MAP_FAILED)
	{
		*err = std::string("mmap: ") + std::strerror(errno);
		shm_unlink(path.c_str());
		name_.clear();
		return false;
	}
	base_ = static_cast<std::uint8_t *>(p);
	createdNs_ = NowNs();

	// Header: everything but magic/version, a release fence, then version and magic last.
	auto *h = At<P::LinkHeader>(P::kClOffHeader);
	h->linkKind = kind_;
	h->mappingBytes = bytes_;
	h->sessionNonce = nonce_;
	h->hostHeartbeatNs = createdNs_;
	for (std::uint32_t i = 0; i < P::kMaxStatRings; ++i)
		rings_[i].out = &HostStats()->rings[i];
	P::LinkSideStats *hs = HostStats();
	hs->protocolVersion = P::kVersion;
	hs->attachCount = ++s_attachCount;
	hs->sessionNonce = nonce_;
	hs->attachNs = createdNs_;
	__atomic_thread_fence(__ATOMIC_RELEASE);
	StoreRel32(&h->version, P::kVersion);
	StoreRel32(&h->magic, P::kMagic);

	if (useDiscovery)
	{
		std::string dir = DiscoveryDir();
		std::string why;
		if (!MkdirP(dir.substr(0, dir.rfind('/')), 0700) || !EnsurePrivateDir(dir, &why))
		{
			*err = why.empty() ? "mkdir " + dir + ": " + std::strerror(errno) : why;
			std::fprintf(stderr, "gmodcraft: discovery dir refused: %s\n", err->c_str());
			Close();
			return false;
		}
		discovery_ = dir + "/" + KindName() + ".json";
		discoveryNote_ = DiscoveryOwnerNote(discovery_);
		char json[384];
		std::snprintf(json, sizeof json, "{\"proto\": %u, \"kind\": \"%s\", \"shm\": \"%s\", \"bytes\": %llu, \"nonce\": \"%s\"}\n",
			static_cast<unsigned>(P::kVersion), KindName(), name_.c_str(), static_cast<unsigned long long>(bytes_), Hex16(nonce_).c_str());
		if (!WriteFileAtomic(discovery_, json))
		{
			*err = "writing " + discovery_ + " failed";
			Close();
			return false;
		}
	}
	lastMcBeat_ = 0;
	lastMcChangeNs_ = 0;
	mcAlive_ = false;
	updates_ = 0;
	return true;
}

void Link::Close()
{
	// Clean shutdown order (header comment): discovery file first, then the mapping.
	if (!discovery_.empty())
	{
		// Only remove it if it still names our mapping (another host may have taken over).
		FILE *f = std::fopen(discovery_.c_str(), "rb");
		if (f != nullptr)
		{
			char buf[512];
			std::size_t n = std::fread(buf, 1, sizeof buf - 1, f);
			std::fclose(f);
			buf[n] = 0;
			if (std::strstr(buf, name_.c_str()) != nullptr)
				std::remove(discovery_.c_str());
		}
		discovery_.clear();
	}
	if (base_ != nullptr)
	{
		munmap(base_, bytes_);
		base_ = nullptr;
	}
	if (!name_.empty())
	{
		shm_unlink(("/" + name_).c_str());
		name_.clear();
	}
	for (auto &r : rings_)
		r = RingCounter();
	mcAlive_ = false;
}

std::uint64_t Link::McHeartbeatNs() const
{
	return base_ ? LoadAcq64(&At<P::LinkHeader>(0)->mcHeartbeatNs) : 0;
}

std::uint64_t Link::McNonce() const
{
	return base_ ? LoadAcq64(&At<P::LinkHeader>(0)->mcNonce) : 0;
}

double Link::McHeartbeatAgeMs() const
{
	if (lastMcChangeNs_ == 0)
		return -1;
	return static_cast<double>(NowNs() - lastMcChangeNs_) / 1e6;
}

void Link::Heartbeat(float tickMs, float frameMs)
{
	if (base_ == nullptr)
		return;
	std::uint64_t now = NowNs();
	auto *h = At<P::LinkHeader>(0);
	StoreRel64(&h->hostHeartbeatNs, now);
	std::uint64_t beat = LoadAcq64(&h->mcHeartbeatNs);
	if (beat != lastMcBeat_)
	{
		lastMcBeat_ = beat;
		lastMcChangeNs_ = now;
	}
	bool alive = lastMcBeat_ != 0 && now - lastMcChangeNs_ < static_cast<std::uint64_t>(P::kHeartbeatTimeoutMs) * 1000000ull;
	if (mcAlive_ && !alive)
		++peerDown_;
	mcAlive_ = alive;

	P::LinkSideStats *hs = HostStats();
	hs->updateCount = ++updates_;
	hs->peerDownCount = peerDown_;
	hs->flags = alive ? static_cast<std::uint32_t>(P::kSidePeerAlive) : 0u;
	hs->tickMs = tickMs;
	hs->frameMs = frameMs;
	for (auto &r : rings_)
		r.Tick(now);
}

// ---- ByteRingWriter --------------------------------------------------------------------------
std::uint64_t ByteRingWriter::Free() const
{
	if (base == nullptr)
		return 0;
	return dataBytes - (head - LoadAcq64(base + 0x40));
}

std::uint8_t *ByteRingWriter::Begin(std::uint32_t type, std::uint32_t payloadBytes)
{
	if (base == nullptr)
		return nullptr;
	std::uint64_t msg = (8 + static_cast<std::uint64_t>(payloadBytes) + 7) & ~std::uint64_t(7);
	std::uint64_t tail = LoadAcq64(base + 0x40);
	std::uint64_t pos = head % dataBytes;
	std::uint64_t pad = pos + msg > dataBytes ? dataBytes - pos : 0;
	if (msg > dataBytes / 2 || dataBytes - (head - tail) < msg + pad)
	{
		stats->Count(0, 0, 1, head - tail);
		return nullptr;
	}
	pendingHead_ = head;
	if (pad)
	{
		std::uint32_t padHdr[2] = { 0, 0 };
		if (pad >= 8)
			std::memcpy(base + 0x80 + pos, padHdr, 8);
		pendingHead_ += pad;
		pos = 0;
	}
	std::uint32_t hdr[2] = { type, payloadBytes };
	std::memcpy(base + 0x80 + pos, hdr, 8);
	pendingBytes_ = msg + pad;
	return base + 0x80 + pos + 8;
}

void ByteRingWriter::Commit()
{
	if (base == nullptr || pendingBytes_ == 0)
		return;
	head += pendingBytes_;  // message plus any pad before it
	StoreRel64(base + 0x00, head);
	++messages;
	std::uint64_t tail = LoadAcq64(base + 0x40);
	stats->Count(1, pendingBytes_, 0, head - tail);
	pendingBytes_ = 0;
}
}  // namespace gc
