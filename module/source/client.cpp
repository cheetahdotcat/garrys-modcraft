// gmcl_gmodcraft: the client link (GMod client <-> this player's Minecraft client), the HUD/hand
// overlay texture, and starting / stopping Minecraft. Lua API: see RegisterClient below and
// addon/gmodcraft/README.md.
#ifdef GMODCRAFT_CLIENT

#include "collision.hpp"
#include "colstream.hpp"
#include "jsonfield.hpp"
#include "prismcmd.hpp"
#include "quitmarker.hpp"
#include "link.hpp"
#include "lua_util.hpp"

#include <cdll_int.h>
#include <materialsystem/imaterialsystem.h>
#include <materialsystem/itexture.h>
#include <texture_group_names.h>
#include <vtf/vtf.h>

#include <cctype>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <vector>

#include "os/os.hpp"

#ifndef _WIN32
#include <fcntl.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
#endif

namespace gc
{
using namespace GarrysMod::Lua;

bool IsDevMode();  // main.cpp: GMod started with -gmodcraft_dev

// blocks.cpp: Minecraft's block sections and atlas (P3a).
void BlocksOnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n);
void BlocksDrainTime(double ms);
void BlocksSetOrigin(std::int32_t ox, std::int32_t oz, std::int32_t oy);
void BlocksReset();
void RegisterBlocks(ILua *L);
void CloseBlocks();
void EntitiesReadWorld(const P::WorldEntities *w);  // entities.cpp (P3c)

// What this module reads and writes on the client link, checked against the header's own
// offsets (the header static_asserts its structs; these tie our use of them to it).
static_assert(sizeof(P::HostState) == P::kHostStateBytes && sizeof(P::McState) == P::kMcStateBytes);
static_assert(sizeof(P::McIdentity) == P::kMcIdentityBytes && sizeof(P::JoinInfo) == P::kJoinInfoBytes);
static_assert(sizeof(P::InputEvent) == P::kInputEventBytes && sizeof(P::OverlaySlotHdr) == P::kSlotHdrBytes);
static_assert(P::kClOffOverlaySlotHdr + P::kOverlaySlots * P::kSlotHdrBytes <= P::kClOffWaterGrid);
static_assert(P::kInputRingBytes == P::kIrData + P::kInputEventBytes * P::kInputRingEntries);
static_assert(P::kIrHead == 0x00 && P::kIrTail == 0x40 && P::kIrData == 0x80, "EntryRing* assume this ring header");
static_assert(P::kCrHead == 0x00 && P::kCrTail == 0x40 && P::kCrData == 0x80, "ByteRing* assume this ring header");
static_assert(P::kRrHead == 0x00 && P::kRrTail == 0x40 && P::kRrData == 0x80, "ByteRing* assume this ring header");
static_assert(P::kClOffLinkStats + P::kLinkStatsBytes <= P::kClOffInputRing);
static_assert(sizeof(P::JoinStatus) == P::kJoinStatusBytes && sizeof(P::McEvent) == P::kMcEventBytes);
static_assert(P::kClEventRingBytes == P::kErData + P::kMcEventBytes * P::kClEventRingEntries);
static_assert(P::kErHead == 0x00 && P::kErTail == 0x40 && P::kErData == 0x80, "EntryRing* assume this ring header");

namespace
{
// ---- engine interfaces (resolved from the already-loaded engine libraries) --------------------
CreateInterfaceFn GetFactory(os::EngineLib lib)
{
	return reinterpret_cast<CreateInterfaceFn>(os::EngineFactory(lib));
}

IMaterialSystem *g_matsys = nullptr;
int g_matsysOk = -1;  // -1 unknown, 0 failed, 1 ok
std::string g_matsysProbe;

// Before trusting VMaterialSystem080's vtable, make a call with a checkable result (P0.4).
bool MatSysSane()
{
	if (g_matsysOk >= 0)
		return g_matsysOk == 1;
	g_matsysOk = 0;
	if (CreateInterfaceFn mf = GetFactory(os::EngineLib::kMaterialSystem))
		g_matsys = static_cast<IMaterialSystem *>(mf(MATERIAL_SYSTEM_INTERFACE_VERSION, nullptr));
	if (g_matsys == nullptr)
	{
		g_matsysProbe = "no IMaterialSystem";
		return false;
	}
	ITexture *t = g_matsys->FindTexture("_rt_FullFrameFB", TEXTURE_GROUP_RENDER_TARGET, false);
	if (t == nullptr)
	{
		g_matsysProbe = "FindTexture returned null";
		return false;
	}
	const char *nm = t->GetName();
	char buf[200];
	std::snprintf(buf, sizeof buf, "_rt_FullFrameFB: name=%s error=%d rt=%d", nm ? nm : "(null)", t->IsError() ? 1 : 0, t->IsRenderTarget() ? 1 : 0);
	g_matsysProbe = buf;
	if (nm == nullptr || (std::strcmp(nm, "_rt_fullframefb") != 0 && std::strcmp(nm, "_rt_FullFrameFB") != 0) || t->IsError() || !t->IsRenderTarget())
		return false;
	g_matsysOk = 1;
	return true;
}

// v -> round(255 * srgb_encode(v / 255)): the inverse of GL's sRGB texture decode (D-007).
const unsigned char *SrgbEncodeLut()
{
	static unsigned char lut[256];
	static bool init = false;
	if (!init)
	{
		for (int i = 0; i < 256; ++i)
		{
			double l = i / 255.0;
			double e = l <= 0.0031308 ? l * 12.92 : 1.055 * std::pow(l, 1.0 / 2.4) - 0.055;
			int v = static_cast<int>(e * 255.0 + 0.5);
			lut[i] = static_cast<unsigned char>(v < 0 ? 0 : v > 255 ? 255 : v);
		}
		init = true;
	}
	return lut;
}

// ---- the overlay texture ---------------------------------------------------------------------
// A procedural BGRA8888 texture (ToGL's own storage order, P0.4) whose regenerator copies the
// overlay slot we currently hold straight from shared memory: rows flipped when the frame is
// bottom-up, swizzled when it isn't BGRA, sRGB-encoded with a LUT when Minecraft didn't (D-007
// fallback). The result is always BGRA, top row first, sRGB-encoded, premultiplied: Lua draws it
// with $linearwrite 1 under OverrideBlend(ONE, ONE_MINUS_SRC_ALPHA).
struct OverlayTex final : public ITextureRegenerator
{
	std::string name;
	int w = 0, h = 0;
	ITexture *tex = nullptr;
	const std::uint8_t *src = nullptr;  // the front slot's pixels (we own it until the next xchg)
	std::uint32_t srcW = 0, srcH = 0, srcFlags = 0;
	std::uint64_t regenCalls = 0, mismatches = 0, converted = 0, downloads = 0;
	double lastDownloadMs = 0, avgDownloadMs = 0;
	std::string mismatchInfo;

	void RegenerateTextureBits(ITexture *, IVTFTexture *vtf, Rect_t *) override
	{
		++regenCalls;
		if (vtf == nullptr)
			return;
		unsigned char *dst = vtf->ImageData(0, 0, 0);
		if (dst == nullptr)
			return;
		const int vw = vtf->Width(), vh = vtf->Height();
		if (vw != w || vh != h || vtf->Format() != IMAGE_FORMAT_BGRA8888)
		{
			if (mismatches++ == 0)
			{
				char b[160];
				std::snprintf(b, sizeof b, "vtf %dx%d fmt=%d, expected %dx%d BGRA8888", vw, vh, static_cast<int>(vtf->Format()), w, h);
				mismatchInfo = b;
			}
			return;  // never write blindly into a layout we didn't ask for
		}
		const std::size_t row = static_cast<std::size_t>(w) * 4;
		if (src == nullptr || srcW != static_cast<std::uint32_t>(w) || srcH != static_cast<std::uint32_t>(h))
		{
			std::memset(dst, 0, row * h);  // no frame (yet): fully transparent
			return;
		}
		const bool flip = (srcFlags & P::kOvBottomUp) != 0;
		const bool bgra = (srcFlags & P::kOvBGRA) != 0;
		const bool enc = (srcFlags & P::kOvSrgbEncoded) != 0;
		if (!bgra || !enc)
			++converted;
		const unsigned char *lut = SrgbEncodeLut();
		for (int y = 0; y < h; ++y)
		{
			const std::uint8_t *s = src + static_cast<std::size_t>(flip ? h - 1 - y : y) * row;
			unsigned char *d = dst + static_cast<std::size_t>(y) * row;
			if (bgra && enc)
			{
				std::memcpy(d, s, row);
				continue;
			}
			for (std::size_t i = 0; i < row; i += 4)
			{
				unsigned char b = bgra ? s[i] : s[i + 2], g = s[i + 1], r = bgra ? s[i + 2] : s[i], a = s[i + 3];
				d[i] = enc ? b : lut[b];
				d[i + 1] = enc ? g : lut[g];
				d[i + 2] = enc ? r : lut[r];
				d[i + 3] = a;
			}
		}
	}

	void Release() override
	{
		// Lifetime is ours (g_overlay); the texture only drops its pointer.
	}

	void Destroy()
	{
		if (tex != nullptr)
		{
			tex->SetTextureRegenerator(nullptr);
			tex->DecrementReferenceCount();
			tex = nullptr;
		}
		src = nullptr;
	}
};

OverlayTex *g_overlay = nullptr;
std::string g_overlayError;

// Makes sure g_overlay is a w x h texture. Its name carries the size, so a resolution change
// gets a new texture (and Lua a new material) instead of reusing one of the wrong size.
bool EnsureOverlayTex(int w, int h)
{
	if (g_overlay != nullptr && g_overlay->w == w && g_overlay->h == h && g_overlay->tex != nullptr)
		return true;
	if (!MatSysSane())
	{
		g_overlayError = "material system check failed: " + g_matsysProbe;
		return false;
	}
	if (g_overlay != nullptr)
	{
		g_overlay->Destroy();
		delete g_overlay;
		g_overlay = nullptr;
	}
	char name[64];
	std::snprintf(name, sizeof name, "gmodcraft/overlay_%dx%d", w, h);
	ITexture *tex = nullptr;
	if (g_matsys->IsTextureLoaded(name))
	{
		// Left over from an earlier module load (map change / Lua reload). Reuse only if the
		// actual size and format are what we'd create (ToGL stores BGRA8888, as we request).
		ITexture *old = g_matsys->FindTexture(name, TEXTURE_GROUP_OTHER, false);
		if (old == nullptr || old->IsError() || old->GetActualWidth() != w || old->GetActualHeight() != h ||
			old->GetImageFormat() != IMAGE_FORMAT_BGRA8888)
		{
			g_overlayError = std::string(name) + " exists with a different size/format";
			return false;
		}
		tex = old;
		tex->IncrementReferenceCount();
	}
	else
	{
		int flags = TEXTUREFLAGS_PROCEDURAL | TEXTUREFLAGS_NOMIP | TEXTUREFLAGS_NOLOD | TEXTUREFLAGS_SINGLECOPY | TEXTUREFLAGS_CLAMPS |
			TEXTUREFLAGS_CLAMPT;
		tex = g_matsys->CreateProceduralTexture(name, TEXTURE_GROUP_OTHER, w, h, IMAGE_FORMAT_BGRA8888, flags);
		if (tex == nullptr || tex->IsError())
		{
			g_overlayError = "CreateProceduralTexture failed";
			return false;
		}
	}
	g_overlay = new OverlayTex();
	g_overlay->name = name;
	g_overlay->w = w;
	g_overlay->h = h;
	g_overlay->tex = tex;
	tex->SetTextureRegenerator(g_overlay);
	g_overlayError.clear();
	return true;
}

// ---- the client link -------------------------------------------------------------------------
Link g_link(P::kLinkClient, P::kClMappingBytes, P::kClOffLinkStats);
EntryRingWriter<P::InputEvent, P::kInputRingEntries> g_input;
ByteRingWriter g_col;
ByteRingReader g_render;
EntryRingReader<P::McEvent, P::kClEventRingEntries> g_events;  // v14: join results
P::JoinStatus g_join{};   // last good JoinStatus
P::McState g_mc{};        // last good McState
long long g_mcSeqSeen = -1;
std::uint64_t g_mcTorn = 0;
P::McIdentity g_id{};
std::uint32_t g_front = 2;  // overlay triple buffer: the reader's slot (header: front starts at 2)
std::uint64_t g_overlayTaken = 0;
std::uint64_t g_overlayMcNonce = 0;   // the Minecraft session the triple buffer state belongs to
std::uint32_t g_overlaySkipNext = 0;  // frames still to drop after a new Minecraft attached
std::uint64_t g_overlaySkipped = 0;
std::uint64_t g_overlayResets = 0;
std::uint64_t g_lastFrameNs = 0;
float g_frameMs = 0;
std::uint64_t g_renderMessages = 0;

void AttachRings()
{
	g_input.Attach(g_link.Base() + P::kClOffInputRing, &g_link.Ring(P::kClRingInput));
	g_col.Attach(g_link.Base() + P::kClOffCollisionRing, P::kClCollisionRingBytes, &g_link.Ring(P::kClRingCollision));
	g_render.Attach(g_link.Base() + P::kClOffRenderRing, P::kRenderRingBytes, &g_link.Ring(P::kClRingRender));
	g_events.Attach(g_link.Base() + P::kClOffEventRing, &g_link.Ring(P::kClRingEvents));
	g_join = P::JoinStatus{};
	g_mc = P::McState{};
	g_mcSeqSeen = -1;
	g_id = P::McIdentity{};
	g_front = 2;
	g_overlayTaken = 0;
	g_overlayMcNonce = 0;
	g_overlaySkipNext = 0;
	if (g_overlay != nullptr)
		g_overlay->src = nullptr;
}

// A new Minecraft (new mcNonce) starts its overlay writer from the header's initial state (back
// slot 1), but our front slot and the middle are whatever the previous Minecraft left, so the
// three indices may no longer be a permutation and frames could be written while we read them.
// Go back to the initial state (middle 0, clean; front 2) and drop the first frame taken after
// the change. Minecraft attaches long before it publishes overlays (it loads its world first), so
// this normally runs before its first publish.
void CheckOverlayOwner()
{
	std::uint64_t nonce = g_link.McNonce();
	if (nonce == 0 || nonce == g_overlayMcNonce)
		return;
	bool restart = g_overlayMcNonce != 0;
	g_overlayMcNonce = nonce;
	StoreRel32(&g_link.At<P::OverlayCtl>(P::kClOffOverlayCtl)->state, 0);
	g_front = 2;
	if (g_overlay != nullptr)
		g_overlay->src = nullptr;
	g_overlaySkipNext = 1;
	if (restart)
		++g_overlayResets;
	BlocksReset();  // the new Minecraft sends ClearAll, its atlas and every section again
}

void CloseLink()
{
	if (g_overlay != nullptr)
		g_overlay->src = nullptr;  // the slot memory goes away with the mapping
	g_input = {};
	g_col = {};
	g_render = {};
	g_events = {};
	BlocksReset();
	CollisionLinkClosed();
	g_link.Close();
}

// ---- Minecraft process -----------------------------------------------------------------------
pid_t g_launchPid = -1;
double g_lastLaunchTryMs = -1e18;  // McLaunch: at most one spawn attempt per kLaunchIntervalMs
const double kLaunchIntervalMs = 10000;
int g_launchStatus = -1;  // last exit status of the launch helper (-1: none yet)
double g_launchAtMs = 0;
std::string g_launchHow, g_launchError;

#ifndef _WIN32
const char *kLaunchLog = "/dev/shm/gmodcraft/launch.log";

std::string LockPath()
{
	const char *fixed = std::getenv("GMODCRAFT_LINK");
	if (fixed != nullptr && *fixed != 0 && std::strchr(fixed, '/') == nullptr && std::strlen(fixed) < 200)
		return std::string(kDiscoveryDir) + "/minecraft-client-" + fixed + ".lock";
	return std::string(kDiscoveryDir) + "/minecraft-client.lock";
}

// The Minecraft client holds a POSIX write lock on [0, 1) of the lock file while it runs.
// Returns the holder's pid (> 0), 0 if nobody holds it, -1 if the probe failed.
pid_t ProbeMcLock()
{
	std::string why;
	if (!CheckPrivateDir(kDiscoveryDir, getuid(), &why))
	{
		if (errno == ENOENT)
			return 0;  // no dir yet: nobody holds the lock
		std::fprintf(stderr, "gmodcraft: %s\n", why.c_str());
		return -1;     // can't tell: a planted lock must not stop or fake a launch
	}
	int fd = open(LockPath().c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW);
	if (fd < 0)
		return errno == ENOENT ? 0 : -1;
	struct flock fl{};
	fl.l_type = F_WRLCK;
	fl.l_whence = SEEK_SET;
	fl.l_start = 0;
	fl.l_len = 1;
	int r = fcntl(fd, F_GETLK, &fl);
	close(fd);  // we never held a lock on it, so closing releases nothing
	if (r != 0)
		return -1;
	return fl.l_type == F_UNLCK ? 0 : (fl.l_pid > 0 ? fl.l_pid : 1);
}

void ReapLauncher()
{
	if (g_launchPid <= 0)
		return;
	int st = 0;
	pid_t r = waitpid(g_launchPid, &st, WNOHANG);
	if (r == g_launchPid)
	{
		g_launchStatus = WIFEXITED(st) ? WEXITSTATUS(st) : 128 + (WIFSIGNALED(st) ? WTERMSIG(st) : 0);
		g_launchPid = -1;
	}
	else if (r < 0 && errno == ECHILD)
		g_launchPid = -1;
}

bool SafeArg(const std::string &s)
{
	if (s.empty() || s.size() > 512)
		return false;
	for (char c : s)
		if (static_cast<unsigned char>(c) < 0x20)
			return false;
	return true;
}

// The Prism AppImage to start, and where that came from: prismAppImage in
// $XDG_CONFIG_HOME/garrys-modcraft/config.json (default ~/.config; written by the launcher), else
// the environment variable GMODCRAFT_PRISM, else ~/Documents/Software/PrismLauncher-Linux-x86_64.AppImage.
// Only the user's own files and environment choose it, never Lua, and Lua never sees it (it names
// the user's home): LaunchMc resolves it right before starting Prism, and no Lua function returns it.
// The config file is read only when it is a regular file of at most kMaxConfigBytes, checked on the
// open descriptor itself (O_NONBLOCK: a FIFO swapped in can't block the open; fstat then refuses it).
constexpr off_t kMaxConfigBytes = 1 << 16;

std::string ReadSmallRegularFile(const std::string &path, bool *ok)
{
	*ok = false;
	int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
	if (fd < 0)
		return {};
	std::string text;
	struct stat st{};
	if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size <= kMaxConfigBytes)
	{
		text.resize(static_cast<size_t>(kMaxConfigBytes));
		size_t got = 0;
		while (got < text.size())
		{
			ssize_t n = read(fd, &text[got], text.size() - got);
			if (n < 0 && errno == EINTR)
				continue;
			if (n <= 0)
				break;
			got += static_cast<size_t>(n);
		}
		text.resize(got);
		*ok = true;
	}
	close(fd);
	return text;
}

// The Prism command (prismcmd.hpp): prismCommand / prismAppImage from the launcher's config.json,
// GMODCRAFT_PRISM, or the default AppImage path.
PrismEnv CurrentPrismEnv(bool pv)
{
	PrismEnv env;
	const char *home = std::getenv("HOME");
	const char *xdg = std::getenv("XDG_CONFIG_HOME");
	std::string dir = xdg && *xdg == '/' ? std::string(xdg) : std::string(home ? home : "") + "/.config";
	env.configText = ReadSmallRegularFile(dir + "/garrys-modcraft/config.json", &env.configRead);
	const char *envPrism = std::getenv("GMODCRAFT_PRISM");
	env.envPrism = envPrism ? envPrism : "";
	env.home = home ? home : "";
	env.pressureVessel = pv;
	env.hostRoot = access("/run/host/usr", F_OK) == 0;
	if (!pv)
	{
		const char *path = std::getenv("PATH");
		std::string p = path ? path : "/usr/local/bin:/usr/bin:/bin";
		std::size_t at = 0;
		while (at <= p.size())
		{
			std::size_t end = p.find(':', at);
			std::string dir = p.substr(at, end == std::string::npos ? std::string::npos : end - at);
			if (!dir.empty() && dir[0] == '/' && access((dir + "/flatpak").c_str(), X_OK) == 0)
			{
				env.flatpakOnPath = dir + "/flatpak";
				break;
			}
			if (end == std::string::npos)
				break;
			at = end + 1;
		}
	}
	return env;
}

// Starts the GmodCraft Prism instance. The command is fixed here (only the environment can change
// the AppImage path / instance name): clientside Lua comes from whatever server you join, so Lua
// must never choose what gets executed.
//  * Inside Steam's pressure-vessel container (GMod's normal case) the AppImage can't mount (no
//    FUSE) and would run inside the container, killed with GMod. Steam's launcher service runs it
//    on the host instead: steam-runtime-launch-client --alongside-steam, then systemd-run --user
//    so it is a detached user unit (logs in the journal), not a child of anything of ours.
//  * Outside a container: systemd-run --user directly.
bool LaunchMc(std::string *how, std::string *err)
{
	ReapLauncher();
	if (g_launchPid > 0)
	{
		*err = "a launch is already in progress";
		return false;
	}
	const char *container = std::getenv("container");
	bool pv = (container != nullptr && std::strcmp(container, "pressure-vessel") == 0) || access("/run/pressure-vessel", F_OK) == 0;
	PrismCommand cmd;
	if (!ResolvePrismCommand(CurrentPrismEnv(pv), &cmd, err))
		return false;
	const char *envInst = std::getenv("GMODCRAFT_PRISM_INSTANCE");
	std::string inst = envInst && *envInst ? envInst : "GmodCraft";
	for (char c : inst)
		if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'))
		{
			*err = "bad GMODCRAFT_PRISM_INSTANCE";
			return false;
		}
	for (const std::string &a : cmd.argv)
		if (!SafeArg(a))
		{
			*err = "bad Prism command (from " + cmd.from + ")";
			return false;
		}
	if (!cmd.checkPath.empty() && access(cmd.checkPath.c_str(), X_OK) != 0)
	{
		// The path itself goes to stderr only: this error reaches Lua (McLaunch, McProcess).
		std::fprintf(stderr, "gmodcraft: Prism (%s) not found or not executable: %s (from %s)\n", cmd.kind.c_str(), cmd.checkPath.c_str(),
			cmd.from.c_str());
		*err = "Prism AppImage not found or not executable (" + cmd.kind + ", from " + cmd.from +
			"; choose Prism in the launcher's Setup tab, or set GMODCRAFT_PRISM)";
		return false;
	}
	// -gmodcraft_dev (GMod's own command line, read by the module): Minecraft gets
	// GMODCRAFT_DEV_COMMANDS=1 for the scripted tests' dev commands. Never from Lua.
	const bool dev = IsDevMode();
	std::vector<std::string> args = PrismLaunchArgv(pv, dev, cmd.argv, inst);
	*how = std::string(pv ? "steam-runtime-launch-client --alongside-steam -- systemd-run --user" : "systemd-run --user") +
		(dev ? " (dev: GMODCRAFT_DEV_COMMANDS=1)" : "");

	std::vector<char *> argv;
	for (auto &a : args)
		argv.push_back(const_cast<char *>(a.c_str()));
	argv.push_back(nullptr);
	// The environment minus what would poison the host side (Steam's overlay preload, the
	// container's library path; the launcher service starts from Steam's clean env anyway).
	std::vector<char *> envp;
	for (char **e = environ; e && *e; ++e)
		if (std::strncmp(*e, "LD_PRELOAD=", 11) != 0 && (pv || std::strncmp(*e, "LD_LIBRARY_PATH=", 16) != 0))
			envp.push_back(*e);
	envp.push_back(nullptr);

	std::string dirWhy;
	const bool dirOk = EnsurePrivateDir(kDiscoveryDir, &dirWhy);
	if (!dirOk)
		std::fprintf(stderr, "gmodcraft: %s; the launch log goes nowhere\n", dirWhy.c_str());
	posix_spawn_file_actions_t fa;
	posix_spawn_file_actions_init(&fa);
	posix_spawn_file_actions_addopen(&fa, 0, "/dev/null", O_RDONLY, 0);
	posix_spawn_file_actions_addopen(&fa, 1, dirOk ? kLaunchLog : "/dev/null", O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
	posix_spawn_file_actions_adddup2(&fa, 1, 2);
	posix_spawnattr_t at;
	posix_spawnattr_init(&at);
#ifdef POSIX_SPAWN_SETSID
	posix_spawnattr_setflags(&at, POSIX_SPAWN_SETSID);
#endif
	pid_t pid = -1;
	int rc = posix_spawnp(&pid, argv[0], &fa, &at, argv.data(), envp.data());
	posix_spawn_file_actions_destroy(&fa);
	posix_spawnattr_destroy(&at);
	if (rc != 0)
	{
		*err = std::string("posix_spawn ") + argv[0] + ": " + std::strerror(rc);
		return false;
	}
	g_launchPid = pid;
	g_launchStatus = -1;
	g_launchAtMs = NowMs();
	return true;
}

// SIGTERM to the process holding the Minecraft lock, if it is a Java process.
bool KillMc(std::string *err)
{
	pid_t pid = ProbeMcLock();
	if (pid <= 1)
	{
		*err = pid == 0 ? "Minecraft is not running" : "lock probe failed";
		return false;
	}
	char path[64], comm[64] = { 0 };
	std::snprintf(path, sizeof path, "/proc/%d/comm", static_cast<int>(pid));
	FILE *f = std::fopen(path, "rb");
	if (f == nullptr)
	{
		*err = "lock holder not visible (" + std::string(path) + ")";
		return false;
	}
	std::size_t n = std::fread(comm, 1, sizeof comm - 1, f);
	std::fclose(f);
	comm[n] = 0;
	if (std::strncmp(comm, "java", 4) != 0)
	{
		*err = "lock holder is not java: " + std::string(comm);
		return false;
	}
	if (kill(pid, SIGTERM) != 0)
	{
		*err = std::string("kill: ") + std::strerror(errno);
		return false;
	}
	return true;
}

std::string LaunchLogTail()
{
	FILE *f = std::fopen(kLaunchLog, "rb");
	if (f == nullptr)
		return "";
	char buf[1024];
	std::fseek(f, 0, SEEK_END);
	long sz = std::ftell(f);
	std::fseek(f, sz > 1000 ? sz - 1000 : 0, SEEK_SET);
	std::size_t n = std::fread(buf, 1, sizeof buf - 1, f);
	std::fclose(f);
	buf[n] = 0;
	for (std::size_t i = 0; i < n; ++i)
		if (static_cast<unsigned char>(buf[i]) < 0x20 && buf[i] != '\n')
			buf[i] = ' ';
	return buf;
}
#else
// Windows: starting / stopping Minecraft (CreateProcess, the lock held via LockFileEx with the pid
// written by Java) is not ported yet. Until then McLaunch / McKill fail cleanly and McProcess reports a failed
// probe, so nothing is started or killed on a guess.
std::string LockPath()
{
	return DiscoveryDir() + "/minecraft-client.lock";
}

pid_t ProbeMcLock()
{
	return -1;  // can't tell
}

void ReapLauncher()
{
}

bool LaunchMc(std::string *, std::string *err)
{
	*err = "starting Minecraft from GMod is not supported on Windows yet (start it from the launcher)";
	return false;
}

bool KillMc(std::string *err)
{
	*err = "stopping Minecraft from GMod is not supported on Windows yet";
	return false;
}

std::string LaunchLogTail()
{
	return "";
}
#endif  // !_WIN32

// ---- Lua: client link ------------------------------------------------------------------------
LUA_FUNCTION_STATIC(LinkOpen)
{
	if (g_link.Open())
	{
		LUA->PushBool(true);
		return 1;
	}
	std::string err;
	if (!g_link.Create(&err))
	{
		LUA->PushNil();
		LUA->PushString(err.c_str());
		return 2;
	}
	AttachRings();
	LUA->PushBool(true);
	return 1;
}

LUA_FUNCTION_STATIC(LinkClose)
{
	CloseLink();
	return 0;
}

// Frame(hostState) -> mcAlive, mcNonce (hex). Once per GMod frame (Minecraft paces its frames on HostState.seq).
// hostState = { flags, worldId, epoch, x, y, z, yaw, pitch, originX, originZ, w, h, hour, tickMs }
// L2: the launcher's gentle quit (quitmarker.hpp). Checked about once a second; on a genuine marker GMod
// runs "quit" through the engine, so it shuts down the normal way (config.cfg written). The command is
// fixed here; Lua only drives the frame and can't create the marker.
double g_nextQuitCheckMs = 0;
void CheckQuitMarker()
{
	double now = NowMs();
	if (now < g_nextQuitCheckMs)
		return;
	g_nextQuitCheckMs = now + 1000;
#ifdef _WIN32
	if (!TakeQuitMarker(DiscoveryDir(), static_cast<pid_t>(os::Pid()), 0))
#else
	if (!TakeQuitMarker(DiscoveryDir(), getpid(), getuid()))
#endif
		return;
	CreateInterfaceFn ef = GetFactory(os::EngineLib::kEngine);
	auto *engine = ef ? static_cast<IVEngineClient *>(ef(VENGINE_CLIENT_INTERFACE_VERSION, nullptr)) : nullptr;
	if (engine == nullptr)
	{
		std::fprintf(stderr, "gmodcraft: quit requested by the launcher, but no %s\n", VENGINE_CLIENT_INTERFACE_VERSION);
		return;
	}
	std::fprintf(stderr, "gmodcraft: quit requested by the launcher\n");
	engine->ClientCmd_Unrestricted("quit");
}

LUA_FUNCTION_STATIC(Frame)
{
	CheckQuitMarker();
	if (!g_link.Open())
	{
		LUA->PushBool(false);
		return 1;
	}
	std::uint64_t now = NowNs();
	if (g_lastFrameNs != 0)
		g_frameMs = static_cast<float>(static_cast<double>(now - g_lastFrameNs) / 1e6);
	g_lastFrameNs = now;
	CheckOverlayOwner();
	float tickMs = 15.0f;
	if (LUA->IsType(1, Type::Table))
	{
		tickMs = static_cast<float>(FieldNum(LUA, 1, "tickMs", 15.0));
		P::HostState hs{};
		hs.flags = static_cast<std::uint32_t>(FieldInt(LUA, 1, "flags", 0, 0xFFFFFFFF));
		hs.worldId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "worldId", 0, 0xFFFFFFFF));
		hs.collisionEpoch = static_cast<std::uint32_t>(FieldInt(LUA, 1, "epoch", 0, 0xFFFFFFFF));
		hs.posX = FieldNum(LUA, 1, "x");
		hs.posY = FieldNum(LUA, 1, "y");
		hs.posZ = FieldNum(LUA, 1, "z");
		hs.yaw = static_cast<float>(FieldNum(LUA, 1, "yaw"));
		hs.pitch = static_cast<float>(FieldNum(LUA, 1, "pitch"));
		hs.slotOriginX = static_cast<std::int32_t>(FieldInt(LUA, 1, "originX", -2147483647.0, 2147483647.0));
		hs.slotOriginZ = static_cast<std::int32_t>(FieldInt(LUA, 1, "originZ", -2147483647.0, 2147483647.0));
		hs.slotOriginY = static_cast<std::int32_t>(FieldInt(LUA, 1, "originY", -2147483647.0, 2147483647.0));  // v21, Source units
		hs.viewportW = static_cast<std::uint32_t>(FieldInt(LUA, 1, "w", 0, P::kMaxOverlayW));
		hs.viewportH = static_cast<std::uint32_t>(FieldInt(LUA, 1, "h", 0, P::kMaxOverlayH));
		hs.gameHour = static_cast<float>(FieldNum(LUA, 1, "hour", 12.0));
		// v17 hybrid mode (H2 fills them): HostHybridFlags and the active weapon's ammo, -1 = none.
		hs.hybridFlags = static_cast<std::uint32_t>(FieldInt(LUA, 1, "hybridFlags", 0, 0xFFFFFFFF));
		hs.clip1 = static_cast<std::int32_t>(FieldInt(LUA, 1, "clip1", -1, 2147483647.0, -1));
		hs.maxClip1 = static_cast<std::int32_t>(FieldInt(LUA, 1, "maxClip1", -1, 2147483647.0, -1));
		hs.ammo1 = static_cast<std::int32_t>(FieldInt(LUA, 1, "ammo1", -1, 2147483647.0, -1));
		hs.ammo2 = static_cast<std::int32_t>(FieldInt(LUA, 1, "ammo2", -1, 2147483647.0, -1));
		// v30 (P6i carry, client/carry.lua): the moving entity under the MC player, 0 = none.
		hs.carryEnt = static_cast<std::uint32_t>(FieldInt(LUA, 1, "carryEnt", 0, 65535));
		if (hs.carryEnt != 0)
		{
			hs.carrySeq = static_cast<std::uint32_t>(FieldInt(LUA, 1, "carrySeq", 0, 0xFFFFFFFF));
			hs.carryFlags = static_cast<std::uint32_t>(FieldInt(LUA, 1, "carryFlags", 0, 0xFFFFFFFF));
			// Rates are clamped to sane values (blocks / radians per tick): a bad frame can't fling.
			auto rate = [&](const char *k, double lim) {
				const double v = FieldNum(LUA, 1, k);
				return static_cast<float>(v < -lim ? -lim : v > lim ? lim : v);
			};
			hs.carryVelX = rate("carryVelX", 10.0);
			hs.carryVelY = rate("carryVelY", 10.0);
			hs.carryVelZ = rate("carryVelZ", 10.0);
			hs.carryYawRate = rate("carryYawRate", 1.0);
			hs.carryPivotX = FieldNum(LUA, 1, "carryPivotX");
			hs.carryPivotY = FieldNum(LUA, 1, "carryPivotY");
			hs.carryPivotZ = FieldNum(LUA, 1, "carryPivotZ");
			hs.carryTopY = FieldNum(LUA, 1, "carryTopY");
			hs.carryYaw = static_cast<float>(FieldNum(LUA, 1, "carryYaw"));
		}
		BlocksSetOrigin(hs.slotOriginX, hs.slotOriginZ, hs.slotOriginY);  // a change re-bakes every section
		SeqWrite(g_link.At<P::HostState>(P::kClOffHostState), [&](P::HostState &d) {
			std::uint32_t seq = d.seq;
			d = hs;
			d.seq = seq;
		});
	}
	g_link.Heartbeat(tickMs, g_frameMs);
	g_link.HostStats()->overlayFrames = g_overlayTaken;
	// The render ring, every frame whether blocks are drawn or not (Minecraft's writeRender waits
	// up to 1 s on a full ring). Everything goes to the block pipeline (blocks.cpp, which copies
	// what it keeps); dug cells (kRenDug) also to the collision streamer.
	const double drainStart = NowMs();
	g_renderMessages += g_render.Drain([](std::uint32_t type, const std::uint8_t *p, std::uint32_t n) {
		BlocksOnMessage(type, p, n);
		if (type != P::kRenDug || n < sizeof(P::RenDug))
			return;
		P::RenDug h;
		std::memcpy(&h, p, sizeof h);
		if (h.count != 0 && n < sizeof h + P::kBlockBitsBytes)
			return;
		CollisionDug(h, h.count ? p + sizeof h : nullptr);
	});
	BlocksDrainTime(NowMs() - drainStart);
	EntitiesReadWorld(g_link.At<P::WorldEntities>(P::kClOffWorldEntities));  // P3c: dropped items, arrows, cracks, outline
	// Rings the host produces: keep their fill current even when nothing was written.
	{
		std::uint8_t *ib = g_link.Base() + P::kClOffInputRing;
		g_link.Ring(P::kClRingInput).Fill(LoadAcq64(ib) - LoadAcq64(ib + 0x40));
		std::uint8_t *cb = g_link.Base() + P::kClOffCollisionRing;
		g_link.Ring(P::kClRingCollision).Fill(LoadAcq64(cb) - LoadAcq64(cb + 0x40));
	}
	ReapLauncher();
	LUA->PushBool(g_link.McAlive());
	LUA->PushString(Hex16(g_link.McNonce()).c_str());  // changes when a (new) Minecraft attaches
	return 2;
}

LUA_FUNCTION_STATIC(McAlive)
{
	LUA->PushBool(g_link.Open() && g_link.McAlive());
	return 1;
}

// McState([t]) -> t | nil. Fills t (or a new table) from the last good McState; nil until
// Minecraft has written one. t.fresh is true when seq changed since the previous call.
LUA_FUNCTION_STATIC(McState)
{
	if (!g_link.Open())
		return 0;
	P::McState tmp;
	long long seq = SeqRead(g_link.At<P::McState>(P::kClOffMcState), &tmp);
	if (seq > 0)
		g_mc = tmp;
	else if (seq < 0)
		++g_mcTorn;
	if (g_mc.seq == 0)
		return 0;
	bool fresh = static_cast<long long>(g_mc.seq) != g_mcSeqSeen;
	g_mcSeqSeen = g_mc.seq;
	if (LUA->IsType(1, Type::Table))
		LUA->Push(1);
	else
		LUA->CreateTable();
	const P::McState &m = g_mc;
	SetNum(LUA, "seq", m.seq);
	SetBool(LUA, "fresh", fresh);
	SetNum(LUA, "flags", m.flags);
	SetNum(LUA, "x", m.x);
	SetNum(LUA, "y", m.y);
	SetNum(LUA, "z", m.z);
	SetNum(LUA, "yaw", m.yaw);
	SetNum(LUA, "pitch", m.pitch);
	SetNum(LUA, "eyeHeight", m.eyeHeight);
	SetNum(LUA, "sensitivity", m.sensitivity);
	SetNum(LUA, "teleportCount", m.teleportCount);
	SetNum(LUA, "guiScale", m.guiScale);
	SetNum(LUA, "frame", static_cast<double>(m.frameCounter));
	SetNum(LUA, "fov", m.fovDeg);
	SetNum(LUA, "bobPhase", m.bobPhase);
	SetNum(LUA, "bobAmount", m.bobAmount);
	SetNum(LUA, "eyeX", m.eyeX);
	SetNum(LUA, "eyeY", m.eyeY);
	SetNum(LUA, "eyeZ", m.eyeZ);
	SetNum(LUA, "tickAtMs", static_cast<double>(m.tickNs) / 1e6);
	SetNum(LUA, "prevX", m.prevX);
	SetNum(LUA, "prevY", m.prevY);
	SetNum(LUA, "prevZ", m.prevZ);
	SetNum(LUA, "curX", m.curX);
	SetNum(LUA, "curY", m.curY);
	SetNum(LUA, "curZ", m.curZ);
	SetNum(LUA, "eyeO", m.tickEyeO);
	SetNum(LUA, "eye", m.tickEye);
	SetNum(LUA, "walkO", m.walkDistO);
	SetNum(LUA, "walk", m.walkDist);
	SetNum(LUA, "bobO", m.bobO);
	SetNum(LUA, "bob", m.bob);
	SetNum(LUA, "tickMs", m.tickMs);
	SetNum(LUA, "cameraMode", m.cameraMode);
	SetNum(LUA, "cameraDistance", m.cameraDistance);
	SetNum(LUA, "heldWeapon", m.heldWeapon);  // v17: class hash of the held gmod_weapon, 0 = none
	SetNum(LUA, "heldSlot", m.heldSlot);
	SetNum(LUA, "carryEnt", m.carryEnt);  // v30: the entity Minecraft carried the player with this tick, 0 = none
	return 1;
}

// McScreen() -> { seq, open, container, hoveredSlot, cursorX, cursorY, frame } | nil (v35, S1): the
// open Minecraft screen's slot under the cursor (the local player's inventory index, -1 none) and
// the cursor position it belongs to. nil until Minecraft wrote it.
LUA_FUNCTION_STATIC(McScreenLua)
{
	if (!g_link.Open())
		return 0;
	static P::McScreen last;
	P::McScreen tmp;
	if (SeqRead(g_link.At<P::McScreen>(P::kClOffMcScreen), &tmp) > 0)
		last = tmp;
	if (last.seq == 0)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "seq", last.seq);
	SetBool(LUA, "open", (last.flags & P::kScrOpen) != 0);
	SetBool(LUA, "container", (last.flags & P::kScrContainer) != 0);
	SetNum(LUA, "hoveredSlot", last.hoveredSlot);
	SetNum(LUA, "cursorX", last.cursorX);
	SetNum(LUA, "cursorY", last.cursorY);
	SetNum(LUA, "frame", static_cast<double>(last.frameCounter));
	return 1;
}

// McSky() -> { seq, valid, overworld, endSky, sunAngle, moonAngle, starAngle, rainBrightness,
// starBrightness, moonPhase, sky = {r,g,b}, fog = {r,g,b}, sunrise = {r,g,b,a}, frame } | nil (v37, K1):
// what Minecraft's sky renderer would draw this frame (gmodcraft_mc_sky). nil until Minecraft wrote it.
static void SetColor(ILua *LUA, const char *name, const float *c, int n)
{
	static const char *const kKeys[4] = { "r", "g", "b", "a" };
	LUA->CreateTable();
	for (int i = 0; i < n; ++i)
	{
		LUA->PushNumber(c[i]);
		LUA->SetField(-2, kKeys[i]);
	}
	LUA->SetField(-2, name);
}

LUA_FUNCTION_STATIC(McSkyLua)
{
	if (!g_link.Open())
		return 0;
	static P::McSky last;
	P::McSky tmp;
	if (SeqRead(g_link.At<P::McSky>(P::kClOffMcSky), &tmp) > 0)
		last = tmp;
	if (last.seq == 0)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "seq", last.seq);
	SetBool(LUA, "valid", (last.flags & P::kMskValid) != 0);
	SetBool(LUA, "overworld", (last.flags & P::kMskOverworld) != 0);
	SetBool(LUA, "endSky", (last.flags & P::kMskEnd) != 0);
	SetNum(LUA, "sunAngle", last.sunAngle);
	SetNum(LUA, "moonAngle", last.moonAngle);
	SetNum(LUA, "starAngle", last.starAngle);
	SetNum(LUA, "rainBrightness", last.rainBrightness);
	SetNum(LUA, "starBrightness", last.starBrightness);
	SetNum(LUA, "moonPhase", last.moonPhase);
	const float sky[3] = { last.skyR, last.skyG, last.skyB };
	const float fog[3] = { last.fogR, last.fogG, last.fogB };
	const float sunrise[4] = { last.sunriseR, last.sunriseG, last.sunriseB, last.sunriseA };
	SetColor(LUA, "sky", sky, 3);
	SetColor(LUA, "fog", fog, 3);
	SetColor(LUA, "sunrise", sunrise, 4);
	SetNum(LUA, "frame", static_cast<double>(last.frameCounter));
	return 1;
}

// SendWeaponIcon(hash, w, h, rgba) -> true | nil, err. v18 hybrid mode: a weapon class's icon to
// Minecraft (kColWeaponIcon on the client collision ring). rgba: w * h * 4 bytes, top row first.
LUA_FUNCTION_STATIC(SendWeaponIconLua)
{
	if (!g_link.Open())
		return PushFail(LUA, "link closed");
	const double hash = ArgNum(LUA, 1), w = ArgNum(LUA, 2), h = ArgNum(LUA, 3);
	if (!(hash >= 1 && hash <= 4294967295.0) || !(w >= 1 && w <= P::kWeaponIconMaxSide) || !(h >= 1 && h <= P::kWeaponIconMaxSide)
		|| !LUA->IsType(4, Type::String))
		return PushFail(LUA, "SendWeaponIcon(hash, w 1..64, h 1..64, rgba string)");
	unsigned int n = 0;
	const char *px = LUA->GetString(4, &n);
	if (!SendWeaponIcon(g_col, static_cast<std::uint32_t>(hash), static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h),
			reinterpret_cast<const std::uint8_t *>(px), n))
		return PushFail(LUA, "refused: the pixel bytes must be w * h * 4, or the ring is full");
	LUA->PushBool(true);
	return 1;
}

// McIdentity() -> { seq, flags, uuid = "32 hex", name } | nil (not written yet)
LUA_FUNCTION_STATIC(McIdentity)
{
	if (!g_link.Open())
		return 0;
	P::McIdentity tmp;
	if (SeqRead(g_link.At<P::McIdentity>(P::kClOffMcIdentity), &tmp) > 0)
		g_id = tmp;
	if (g_id.seq == 0)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "seq", g_id.seq);
	SetNum(LUA, "flags", g_id.flags);
	char hex[33];
	UuidToHex(g_id.uuid, hex);
	SetStr(LUA, "uuid", hex);
	SetStrN(LUA, "name", g_id.name, sizeof g_id.name);
	return 1;
}

// PushInput(type, code, a, b, c) -> ok
LUA_FUNCTION_STATIC(PushInput)
{
	P::InputEvent e{};
	e.type = static_cast<std::uint16_t>(std::fmin(std::fmax(ArgNum(LUA, 1), 0), 65535));
	e.code = static_cast<std::uint16_t>(std::fmin(std::fmax(ArgNum(LUA, 2), 0), 65535));
	e.a = static_cast<std::int32_t>(std::fmin(std::fmax(ArgNum(LUA, 3), -2147483647.0), 2147483647.0));
	e.b = static_cast<std::int32_t>(std::fmin(std::fmax(ArgNum(LUA, 4), -2147483647.0), 2147483647.0));
	e.c = static_cast<std::int32_t>(std::fmin(std::fmax(ArgNum(LUA, 5), -2147483647.0), 2147483647.0));
	LUA->PushBool(g_link.Open() && e.type != 0 && g_input.Push(e));
	return 1;
}

// SetJoinInfo(address, token, joinId) -> ok. Multiplayer pairing (v14, P6b): tells this player's
// Minecraft which server to play on. joinId must be non-zero and new for every instruction
// (Minecraft acts only when it changes); address "" = back to its own world. The token is what the
// GMod server handed this client (never logged).
LUA_FUNCTION_STATIC(SetJoinInfo)
{
	const double id = ArgNum(LUA, 3);
	if (!g_link.Open() || !LUA->IsType(1, Type::String) || !LUA->IsType(2, Type::String) || !(id >= 1 && id <= 4294967295.0))
	{
		LUA->PushBool(false);
		return 1;
	}
	const char *addr = LUA->GetString(1);
	const char *tok = LUA->GetString(2);
	SeqWrite(g_link.At<P::JoinInfo>(P::kClOffJoinInfo), [&](P::JoinInfo &j) {
		j.flags = 0;
		std::memset(j.serverAddress, 0, sizeof j.serverAddress);
		std::memset(j.joinToken, 0, sizeof j.joinToken);
		std::strncpy(j.serverAddress, addr, sizeof j.serverAddress - 1);
		std::strncpy(j.joinToken, tok, sizeof j.joinToken - 1);
		j.joinId = static_cast<std::uint32_t>(id);
		j.pad = 0;
	});
	LUA->PushBool(true);
	return 1;
}

// JoinStatus() -> { seq, state, joinId, result, address, reason } | nil: where this Minecraft plays
// and how its last join ended (v14). nil until Minecraft wrote it in this session.
LUA_FUNCTION_STATIC(JoinStatus)
{
	if (!g_link.Open())
		return 0;
	static P::JoinStatus tmp;
	if (SeqRead(g_link.At<P::JoinStatus>(P::kClOffJoinStatus), &tmp) > 0)
		g_join = tmp;
	if (g_join.seq == 0)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "seq", g_join.seq);
	SetNum(LUA, "state", g_join.state);
	SetNum(LUA, "joinId", g_join.joinId);
	SetNum(LUA, "result", g_join.result);
	SetStrN(LUA, "address", g_join.serverAddress, sizeof g_join.serverAddress);
	SetStrN(LUA, "reason", g_join.reason, sizeof g_join.reason);
	return 1;
}

// DrainJoinEvents() -> { { type, joinId, result }, ... }: the client link's event ring (v14; only
// kEvJoinResult so far: requestId = the JoinInfo joinId it answers, result = JoinResult).
LUA_FUNCTION_STATIC(DrainJoinEvents)
{
	LUA->CreateTable();
	if (!g_link.Open())
		return 1;
	int i = 0;
	g_events.Drain([&](const P::McEvent &e) {
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "type", e.type);
		SetNum(LUA, "joinId", e.requestId);
		SetNum(LUA, "result", e.result);
		LUA->SetTable(-3);
	});
	return 1;
}

// OverlayTake() -> textureName, w, h, fresh, flags | nil, err
// Takes the newest overlay frame (if Minecraft published one since the last call) and downloads
// it into the overlay texture. Returns the current texture either way.
LUA_FUNCTION_STATIC(OverlayTake)
{
	if (!g_link.Open())
		return PushFail(LUA, "link closed");
	bool fresh = false;
	auto *ctl = g_link.At<P::OverlayCtl>(P::kClOffOverlayCtl);
	std::uint32_t state = LoadAcq32(&ctl->state);
	if (state & P::kOverlayDirty)
	{
		std::uint32_t old = Xchg32(&ctl->state, g_front);
		std::uint32_t slot = old & 3;
		if (slot <= 2 && g_overlaySkipNext > 0)
		{
			g_front = slot;  // ours now, but it may have been written while the indices were reset
			--g_overlaySkipNext;
			++g_overlaySkipped;
		}
		else if (slot <= 2)
		{
			g_front = slot;
			++g_overlayTaken;
			const auto *hdr = g_link.At<P::OverlaySlotHdr>(P::kClOffOverlaySlotHdr + slot * P::kSlotHdrBytes);
			std::uint32_t w = hdr->width, h = hdr->height, flags = hdr->flags;
			if (w > 0 && h > 0 && w <= P::kMaxOverlayW && h <= P::kMaxOverlayH)
			{
				if (EnsureOverlayTex(static_cast<int>(w), static_cast<int>(h)))
				{
					g_overlay->src = g_link.Base() + P::kClOffOverlayPixels + slot * P::kOverlaySlotBytes;
					g_overlay->srcW = w;
					g_overlay->srcH = h;
					g_overlay->srcFlags = flags;
					double t0 = NowMs();
					g_overlay->tex->Download();
					g_overlay->lastDownloadMs = NowMs() - t0;
					g_overlay->avgDownloadMs =
						g_overlay->downloads == 0 ? g_overlay->lastDownloadMs : g_overlay->avgDownloadMs * 0.95 + g_overlay->lastDownloadMs * 0.05;
					++g_overlay->downloads;
					fresh = true;
				}
			}
		}
	}
	if (g_overlay == nullptr || g_overlay->tex == nullptr)
		return PushFail(LUA, g_overlayError.empty() ? "no overlay frame yet" : g_overlayError.c_str());
	LUA->PushString(g_overlay->name.c_str());
	LUA->PushNumber(g_overlay->w);
	LUA->PushNumber(g_overlay->h);
	LUA->PushBool(fresh);
	LUA->PushNumber(g_overlay->srcFlags);
	return 5;
}

// McProcess() -> { running, pid, lock, launching, launchStatus, launchAgeMs, how, error, log }
LUA_FUNCTION_STATIC(McProcess)
{
	ReapLauncher();
	pid_t pid = ProbeMcLock();
	LUA->CreateTable();
	SetBool(LUA, "running", pid > 0);
	SetNum(LUA, "pid", pid > 0 ? pid : 0);
	SetBool(LUA, "probeFailed", pid < 0);
	SetStr(LUA, "lock", LockPath().c_str());
	SetBool(LUA, "launching", g_launchPid > 0);
	SetNum(LUA, "launchStatus", g_launchStatus);
	SetNum(LUA, "launchAgeMs", g_launchAtMs > 0 ? NowMs() - g_launchAtMs : -1);
	SetStr(LUA, "how", g_launchHow.c_str());
	SetStr(LUA, "error", g_launchError.c_str());
	SetStr(LUA, "log", LaunchLogTail().c_str());
	return 1;
}

// McLaunch() -> true, how | nil, err. Starts the GmodCraft Prism instance unless it's running.
LUA_FUNCTION_STATIC(McLaunch)
{
	if (ProbeMcLock() > 0)
		return PushFail(LUA, "Minecraft is already running");
	// Clientside Lua comes from the server you joined and could call this in a loop: whoever
	// calls it, at most one attempt per 10 s (the command itself is fixed, see LaunchMc).
	double now = NowMs();
	if (now - g_lastLaunchTryMs < kLaunchIntervalMs)
	{
		char msg[96];
		std::snprintf(msg, sizeof msg, "rate limited: one launch per %.0f s (next in %.1f s)", kLaunchIntervalMs / 1000,
			(kLaunchIntervalMs - (now - g_lastLaunchTryMs)) / 1000);
		return PushFail(LUA, msg);
	}
	g_lastLaunchTryMs = now;
	std::string how, err;
	if (!LaunchMc(&how, &err))
	{
		g_launchError = err;
		return PushFail(LUA, err.c_str());
	}
	g_launchHow = how;
	g_launchError.clear();
	LUA->PushBool(true);
	LUA->PushString(how.c_str());
	return 2;
}

// McKill() -> true | nil, err. SIGTERM to the Java process holding the Minecraft lock.
LUA_FUNCTION_STATIC(McKill)
{
	std::string err;
	if (!KillMc(&err))
		return PushFail(LUA, err.c_str());
	LUA->PushBool(true);
	return 1;
}

}  // namespace

// Shared by both realms' Stats(): one side of LinkStats as a Lua table.
void PushSideStats(ILua *L, const P::LinkSideStats &s, const char *const *ringNames, int rings);

namespace
{
const char *const kClRingNames[] = { "input", "collision", "render", "events" };

// Stats() -> everything the debug tab's Links / Player / Render panels show for the client link.
LUA_FUNCTION_STATIC(Stats)
{
	LUA->CreateTable();
	SetStr(LUA, "realm", "client");
	SetBool(LUA, "open", g_link.Open());
	if (g_link.Open())
	{
		SetStr(LUA, "shm", g_link.Name().c_str());
		SetStr(LUA, "nonce", Hex16(g_link.Nonce()).c_str());
		SetStr(LUA, "discovery", g_link.DiscoveryPath().c_str());
		SetStr(LUA, "discoveryNote", g_link.DiscoveryNote().c_str());
		SetNum(LUA, "gcRemoved", g_link.GcRemoved());
		SetStr(LUA, "gcNote", g_link.GcNote().c_str());
		SetNum(LUA, "bytes", static_cast<double>(g_link.Bytes()));
		const auto *h = g_link.At<P::LinkHeader>(0);
		SetNum(LUA, "headerVersion", h->version);
		SetBool(LUA, "mcAlive", g_link.McAlive());
		SetNum(LUA, "mcBeatAgeMs", g_link.McHeartbeatAgeMs());
		SetNum(LUA, "hostBeatAgeMs", static_cast<double>(NowNs() - LoadAcq64(&h->hostHeartbeatNs)) / 1e6);
		SetStr(LUA, "mcNonce", Hex16(g_link.McNonce()).c_str());
		SetNum(LUA, "hostStateSeq", LoadAcq32(&g_link.At<P::HostState>(P::kClOffHostState)->seq));
		SetNum(LUA, "mcStateSeq", LoadAcq32(&g_link.At<P::McState>(P::kClOffMcState)->seq));
		SetNum(LUA, "mcIdentitySeq", LoadAcq32(&g_link.At<P::McIdentity>(P::kClOffMcIdentity)->seq));
		SetNum(LUA, "joinInfoSeq", LoadAcq32(&g_link.At<P::JoinInfo>(P::kClOffJoinInfo)->seq));
		SetNum(LUA, "joinStatusSeq", LoadAcq32(&g_link.At<P::JoinStatus>(P::kClOffJoinStatus)->seq));
		SetNum(LUA, "mcStateTorn", static_cast<double>(g_mcTorn));
		SetNum(LUA, "overlayTaken", static_cast<double>(g_overlayTaken));
		SetNum(LUA, "overlaySkipped", static_cast<double>(g_overlaySkipped));
		SetNum(LUA, "overlayResets", static_cast<double>(g_overlayResets));
		SetNum(LUA, "renderMessages", static_cast<double>(g_renderMessages));
		SetNum(LUA, "renderMalformed", static_cast<double>(g_render.malformed));
		SetNum(LUA, "collisionFree", static_cast<double>(g_col.Free()));
		LUA->CreateTable();
		PushSideStats(LUA, *g_link.HostStats(), kClRingNames, 4);
		LUA->SetField(-2, "host");
		PushSideStats(LUA, *g_link.McStats(), kClRingNames, 4);
		LUA->SetField(-2, "mc");
		LUA->SetField(-2, "linkStats");
	}
	LUA->CreateTable();
	SetBool(LUA, "matsysOk", g_matsysOk == 1);
	SetStr(LUA, "matsysProbe", g_matsysProbe.c_str());
	SetStr(LUA, "error", g_overlayError.c_str());
	if (g_overlay != nullptr)
	{
		SetStr(LUA, "name", g_overlay->name.c_str());
		SetNum(LUA, "w", g_overlay->w);
		SetNum(LUA, "h", g_overlay->h);
		SetNum(LUA, "flags", g_overlay->srcFlags);
		SetNum(LUA, "downloads", static_cast<double>(g_overlay->downloads));
		SetNum(LUA, "regen", static_cast<double>(g_overlay->regenCalls));
		SetNum(LUA, "converted", static_cast<double>(g_overlay->converted));
		SetNum(LUA, "mismatch", static_cast<double>(g_overlay->mismatches));
		SetStr(LUA, "mismatchInfo", g_overlay->mismatchInfo.c_str());
		SetNum(LUA, "lastMs", g_overlay->lastDownloadMs);
		SetNum(LUA, "avgMs", g_overlay->avgDownloadMs);
	}
	LUA->SetField(-2, "overlay");
	return 1;
}
}  // namespace

IMaterialSystem *ClientMatSys()
{
	return MatSysSane() ? g_matsys : nullptr;
}

const std::string &ClientMatSysProbe()
{
	MatSysSane();
	return g_matsysProbe;
}

ByteRingWriter *RealmCollisionRing()
{
	return g_link.Open() ? &g_col : nullptr;
}

// The client link has one grid: this player's (slot 0).
P::WaterGrid *RealmWaterGrid(int slot)
{
	return g_link.Open() && slot == 0 ? g_link.At<P::WaterGrid>(P::kClOffWaterGrid) : nullptr;
}

P::ActorTable *RealmActorTable()
{
	return g_link.Open() ? g_link.At<P::ActorTable>(P::kClOffActorTable) : nullptr;
}

void RegisterRealm(ILua *L)
{
	struct Fn
	{
		const char *name;
		CFunc fn;
	};
	static const Fn fns[] = {
		{ "LinkOpen", LinkOpen },
		{ "LinkClose", LinkClose },
		{ "Frame", Frame },
		{ "McAlive", McAlive },
		{ "McState", McState },
		{ "McScreen", McScreenLua },  // v35
		{ "McSky", McSkyLua },  // v37
		{ "SendWeaponIcon", SendWeaponIconLua },  // v18
		{ "McIdentity", McIdentity },
		{ "PushInput", PushInput },
		{ "SetJoinInfo", SetJoinInfo },
		{ "JoinStatus", JoinStatus },
		{ "DrainJoinEvents", DrainJoinEvents },
		{ "OverlayTake", OverlayTake },
		{ "McProcess", McProcess },
		{ "McLaunch", McLaunch },
		{ "McKill", McKill },
		{ "Stats", Stats },
	};
	for (const Fn &f : fns)
	{
		L->PushCFunction(f.fn);
		L->SetField(-2, f.name);
	}
	RegisterBlocks(L);
}

void CloseRealm()
{
	CloseLink();
	CloseBlocks();
	if (g_overlay != nullptr)
	{
		g_overlay->Destroy();
		delete g_overlay;
		g_overlay = nullptr;
	}
	ReapLauncher();
}
}  // namespace gc

#endif  // GMODCRAFT_CLIENT
