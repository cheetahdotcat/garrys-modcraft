// gmsv_gmodcraft: the server link (GMod server <-> the Minecraft server, integrated or dedicated).
// Lua API: see RegisterRealm below and addon/gmodcraft/README.md.
#ifdef GMODCRAFT_SERVER

#include "collision.hpp"
#include "hulljob.hpp"
#include "link.hpp"
#include "lua_util.hpp"
#include "sha256.hpp"
#include "solidbox.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <string>

namespace gc
{
using namespace GarrysMod::Lua;

static_assert(sizeof(P::ServerState) == P::kServerStateBytes && sizeof(P::McServerState) == P::kMcServerStateBytes);
static_assert(sizeof(P::McServerSky) == P::kMcServerSkyBytes);  // v43
static_assert(sizeof(P::HostEvent) == P::kHostEventBytes && sizeof(P::McEvent) == P::kMcEventBytes);
static_assert(sizeof(P::HostPlayers) == P::kHostPlayersBytes && sizeof(P::McPlayers) == P::kMcPlayersBytes);
static_assert(P::kHostEventRingBytes == P::kHrData + P::kHostEventBytes * P::kHostEventRingEntries);
static_assert(P::kEventRingBytes == P::kErData + P::kMcEventBytes * P::kEventRingEntries);
static_assert(P::kHrHead == 0x00 && P::kHrTail == 0x40 && P::kHrData == 0x80, "EntryRing* assume this ring header");
static_assert(P::kErHead == 0x00 && P::kErTail == 0x40 && P::kErData == 0x80, "EntryRing* assume this ring header");
static_assert(P::kBrHead == 0x00 && P::kBrTail == 0x40 && P::kBrData == 0x80, "ByteRing* assume this ring header");
static_assert(P::kSvOffLinkStats + P::kLinkStatsBytes <= P::kSvOffHostEventRing);
static_assert(sizeof(P::McServerInfo) == P::kMcServerInfoBytes);
static_assert(P::kWaterGridBytes * P::kMaxPlayers <= P::kSvWaterGridsBytes);
static_assert(P::kSvOffWaterGrids + P::kSvWaterGridsBytes <= P::kSvMappingBytes);
// The join token's hash prefix lives in HostPlayer::reserved (P6b; no layout change).
static_assert(sizeof(P::HostPlayer::reserved) == 8 && P::kHpMcName + P::kMcNameBytes == 0x38);

bool IsDevMode();  // main.cpp
void RegisterPhysWorld(ILua *L);  // physworld.cpp (Tier 0)
void ClosePhysWorld();

namespace
{
Link g_link(P::kLinkServer, P::kSvMappingBytes, P::kSvOffLinkStats);
EntryRingWriter<P::HostEvent, P::kHostEventRingEntries> g_hostEvents;
EntryRingReader<P::McEvent, P::kEventRingEntries> g_events;
ByteRingWriter g_col;
ByteRingReader g_blocks;
P::McServerState g_mss{};
P::McServerSky g_msky{};   // v43: last good McServerSky read
P::McServerInfo g_info{};  // last good McServerInfo read
P::McPlayers *g_players = nullptr;  // last good copy (big: 8 KiB, so on the heap)
P::McWeaponSet *g_sets = nullptr;   // v17: last good copy of each weapon set (kMaxPlayers, heap)
P::McEntities *g_mcEnts = nullptr;  // v27: last good copy of the MC entity table (16 KiB, heap)
std::uint64_t g_lastTickNs = 0;
float g_tickMs = 0;
std::uint64_t g_blockMessages = 0;
std::uint64_t g_blockClears = 0;
sb::Store *g_solids = nullptr;  // P5a: the MC server's solid sections (block collision for GMod entities)
void (*g_solidsHook)(int, int, int, bool) = nullptr;  // Tier 0 (physworld.cpp): a section changed / all gone

sb::Store &Solids()
{
	if (g_solids == nullptr)
		g_solids = new sb::Store();
	return *g_solids;
}

void AttachRings()
{
	g_hostEvents.Attach(g_link.Base() + P::kSvOffHostEventRing, &g_link.Ring(P::kSvRingHostEvents));
	g_events.Attach(g_link.Base() + P::kSvOffEventRing, &g_link.Ring(P::kSvRingEvents));
	g_col.Attach(g_link.Base() + P::kSvOffCollisionRing, P::kSvCollisionRingBytes, &g_link.Ring(P::kSvRingCollision));
	g_blocks.Attach(g_link.Base() + P::kSvOffBlockRing, P::kSvBlockRingBytes, &g_link.Ring(P::kSvRingBlocks));
	g_mss = P::McServerState{};
	g_msky = P::McServerSky{};
	g_info = P::McServerInfo{};
	if (g_players == nullptr)
		g_players = new P::McPlayers();
	*g_players = P::McPlayers{};
	if (g_mcEnts == nullptr)
		g_mcEnts = new P::McEntities();
	*g_mcEnts = P::McEntities{};
	if (g_sets != nullptr)
		std::fill(g_sets, g_sets + P::kMaxPlayers, P::McWeaponSet{});
}

void CloseLink()
{
	g_hostEvents = {};
	g_events = {};
	g_col = {};
	g_blocks = {};
	CollisionLinkClosed();
	g_link.Close();
}

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

// Frame(serverState) -> mcAlive, mcNonce (hex). Once per server tick.
// serverState = { flags, worldId, epoch, map, anchor = { source, floorZ, minZ, maxZ, footMinX, footMinY,
//   footMaxX, footMaxY } | nil, sunSync = SunSyncMode (v43) }. v21: with `anchor` (and kServerAnchorReady in flags) the MC server
// can place a NEW map's slot so its floor sits on a block boundary (oyUnits).
LUA_FUNCTION_STATIC(Frame)
{
	if (!g_link.Open())
	{
		LUA->PushBool(false);
		return 1;
	}
	std::uint64_t now = NowNs();
	if (g_lastTickNs != 0)
		g_tickMs = static_cast<float>(static_cast<double>(now - g_lastTickNs) / 1e6);
	g_lastTickNs = now;
	if (LUA->IsType(1, Type::Table))
	{
		P::ServerState ss{};
		ss.flags = static_cast<std::uint32_t>(FieldInt(LUA, 1, "flags", 0, 0xFFFFFFFF));
		ss.worldId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "worldId", 0, 0xFFFFFFFF));
		ss.collisionEpoch = static_cast<std::uint32_t>(FieldInt(LUA, 1, "epoch", 0, 0xFFFFFFFF));
		ss.tickNs = now;
		FieldStr(LUA, 1, "map", ss.mapName, sizeof ss.mapName);
		ss.sunSync = static_cast<std::uint8_t>(FieldInt(LUA, 1, "sunSync", 0, P::kSunSyncGmodToMc));  // v43
		for (char &c : ss.mapName)
			if (c >= 'A' && c <= 'Z')
				c = static_cast<char>(c - 'A' + 'a');
		LUA->GetField(1, "anchor");
		if (LUA->IsType(-1, Type::Table))
		{
			const int t = LUA->Top();
			auto num = [&](const char *k) { return static_cast<float>(std::fmin(std::fmax(FieldNum(LUA, t, k, 0), -1e6), 1e6)); };
			ss.anchorSource = static_cast<std::uint32_t>(FieldInt(LUA, t, "source", 0, 0xFFFFFFFF));
			ss.floorZ = num("floorZ");
			ss.minZ = num("minZ");
			ss.maxZ = num("maxZ");
			ss.footMinX = num("footMinX");
			ss.footMinY = num("footMinY");
			ss.footMaxX = num("footMaxX");
			ss.footMaxY = num("footMaxY");
		}
		else
			ss.flags &= ~static_cast<std::uint32_t>(P::kServerAnchorReady);  // no anchor, not ready
		LUA->Pop();
		SeqWrite(g_link.At<P::ServerState>(P::kSvOffServerState), [&](P::ServerState &d) {
			std::uint32_t seq = d.seq;
			d = ss;
			d.seq = seq;
		});
	}
	g_link.Heartbeat(g_tickMs, 0.0f);
	// The block ring: solid sections go to the block-collision store (P5a; Lua builds the
	// entities from it), dug cells (kBlkDug) to the collision streamer.
	g_blockMessages += g_blocks.Drain([](std::uint32_t type, const std::uint8_t *p, std::uint32_t n) {
		if (type == P::kBlkClearAll)
		{
			++g_blockClears;
			Solids().ClearAll();
			if (g_solidsHook != nullptr)
				g_solidsHook(0, 0, 0, true);
			return;
		}
		if (type == P::kBlkSolids)
		{
			if (n < sizeof(P::RenSolids))
				return;
			P::RenSolids h;
			std::memcpy(&h, p, sizeof h);
			if (h.count != 0 && n < sizeof h + P::kBlockBitsBytes)
				return;
			const std::uint64_t unchanged = Solids().Stats().unchanged;
			Solids().ApplySolids(h.sx, h.sy, h.sz, h.count, h.count ? p + sizeof h : nullptr);
			if (g_solidsHook != nullptr && Solids().Stats().unchanged == unchanged)
				g_solidsHook(h.sx, h.sy, h.sz, false);
			return;
		}
		if (type == P::kBlkShapes)  // v26: octants of the blocks that aren't full cubes
		{
			if (n < sizeof(P::RenSolids))
				return;
			P::RenSolids h;
			std::memcpy(&h, p, sizeof h);
			if (h.count != 0 && n < sizeof h + P::kBlockShapeBytes)
				return;
			const std::uint64_t unchanged = Solids().Stats().unchanged;
			Solids().ApplyShapes(h.sx, h.sy, h.sz, h.count, h.count ? p + sizeof h : nullptr);
			if (g_solidsHook != nullptr && Solids().Stats().unchanged == unchanged)
				g_solidsHook(h.sx, h.sy, h.sz, false);
			return;
		}
		if (type == P::kBlkMicro)  // v36: microblocks' exact boxes (their cells aren't in kBlkSolids)
		{
			if (n < sizeof(P::RenSolids))
				return;
			P::RenSolids h;
			std::memcpy(&h, p, sizeof h);
			const std::uint64_t unchanged = Solids().Stats().unchanged;
			Solids().ApplyMicro(h.sx, h.sy, h.sz, h.count, p + sizeof h, n - sizeof h);
			if (g_solidsHook != nullptr && Solids().Stats().unchanged == unchanged)
				g_solidsHook(h.sx, h.sy, h.sz, false);
			return;
		}
		if (type != P::kBlkDug || n < sizeof(P::RenDug))
			return;
		P::RenDug h;
		std::memcpy(&h, p, sizeof h);
		if (h.count != 0 && n < sizeof h + P::kBlockBitsBytes)
			return;
		CollisionDug(h, h.count ? p + sizeof h : nullptr);
	});
	std::uint8_t *hb = g_link.Base() + P::kSvOffHostEventRing;
	g_link.Ring(P::kSvRingHostEvents).Fill(LoadAcq64(hb) - LoadAcq64(hb + 0x40));
	std::uint8_t *cb = g_link.Base() + P::kSvOffCollisionRing;
	g_link.Ring(P::kSvRingCollision).Fill(LoadAcq64(cb) - LoadAcq64(cb + 0x40));
	LUA->PushBool(g_link.McAlive());
	LUA->PushString(Hex16(g_link.McNonce()).c_str());  // changes when a (new) Minecraft attaches
	return 2;
}

LUA_FUNCTION_STATIC(McAlive)
{
	LUA->PushBool(g_link.Open() && g_link.McAlive());
	return 1;
}

// McServerState() -> { seq, flags, worldId, slotX, slotZ, originX, originZ, originY, anchorSource, slotCount,
//   tickAtMs, ruleFlags, gameMode, worldType, floorY, damageScale, difficulty, mobCapPercent } | nil. originY (v21): the slot's vertical
//   offset, Source units. v24: the MC server's rules and world type (valid with K.RulesValid in ruleFlags); v34: difficulty, mobCapPercent.
LUA_FUNCTION_STATIC(McServerState)
{
	if (!g_link.Open())
		return 0;
	P::McServerState tmp;
	if (SeqRead(g_link.At<P::McServerState>(P::kSvOffMcServerState), &tmp) > 0)
		g_mss = tmp;
	if (g_mss.seq == 0)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "seq", g_mss.seq);
	SetNum(LUA, "flags", g_mss.flags);
	SetNum(LUA, "worldId", g_mss.worldId);
	SetNum(LUA, "slotX", g_mss.slotX);
	SetNum(LUA, "slotZ", g_mss.slotZ);
	SetNum(LUA, "originX", g_mss.originX);
	SetNum(LUA, "originZ", g_mss.originZ);
	SetNum(LUA, "originY", g_mss.originY);
	SetNum(LUA, "anchorSource", g_mss.anchorSource);
	SetNum(LUA, "slotCount", g_mss.slotCount);
	SetNum(LUA, "tickAtMs", static_cast<double>(g_mss.tickNs) / 1e6);
	SetNum(LUA, "ruleFlags", g_mss.ruleFlags);
	SetNum(LUA, "gameMode", g_mss.gameMode);
	SetNum(LUA, "worldType", g_mss.worldType);
	SetNum(LUA, "floorY", g_mss.floorY);
	SetNum(LUA, "damageScale", g_mss.damageScale);
	SetNum(LUA, "difficulty", g_mss.difficulty);        // v34
	SetNum(LUA, "mobCapPercent", g_mss.mobCapPercent);
	return 1;
}

// McServerSky() -> { seq, flags, valid, advancing, dayTime, timeOfDay, sunAngle, moonAngle, starBrightness, skyLight,
//   moonPhase, rate, sky = {r, g, b}, fog = {r, g, b}, sunrise = {r, g, b, a}, tickAtMs } | nil. v43: the overworld's
//   time of day for the day-night sync; nil until the MC server wrote it once.
LUA_FUNCTION_STATIC(McServerSkyLua)
{
	if (!g_link.Open())
		return 0;
	P::McServerSky tmp;
	if (SeqRead(g_link.At<P::McServerSky>(P::kSvOffMcServerSky), &tmp) > 0)
		g_msky = tmp;
	const P::McServerSky &s = g_msky;
	if (s.seq == 0)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "seq", s.seq);
	SetNum(LUA, "flags", s.flags);
	LUA->PushBool((s.flags & P::kMsvSkyValid) != 0);
	LUA->SetField(-2, "valid");
	LUA->PushBool((s.flags & P::kMsvSkyAdvancing) != 0);
	LUA->SetField(-2, "advancing");
	SetNum(LUA, "dayTime", static_cast<double>(s.dayTime));
	const std::int64_t day = static_cast<std::int64_t>(P::kTicksPerDay);
	SetNum(LUA, "timeOfDay", static_cast<double>(((s.dayTime % day) + day) % day));
	SetNum(LUA, "sunAngle", s.sunAngle);
	SetNum(LUA, "moonAngle", s.moonAngle);
	SetNum(LUA, "starBrightness", s.starBrightness);
	SetNum(LUA, "skyLight", s.skyLight);
	SetNum(LUA, "moonPhase", s.moonPhase);
	SetNum(LUA, "rate", s.rate);
	auto colour = [&](const char *name, float r, float g, float b, float a, bool withA) {
		LUA->CreateTable();
		SetNum(LUA, "r", r);
		SetNum(LUA, "g", g);
		SetNum(LUA, "b", b);
		if (withA)
			SetNum(LUA, "a", a);
		LUA->SetField(-2, name);
	};
	colour("sky", s.skyR, s.skyG, s.skyB, 0, false);
	colour("fog", s.fogR, s.fogG, s.fogB, 0, false);
	colour("sunrise", s.sunriseR, s.sunriseG, s.sunriseB, s.sunriseA, true);
	SetNum(LUA, "tickAtMs", static_cast<double>(s.tickNs) / 1e6);
	return 1;
}

// SetHostPlayers({ { slot = n, steamId = "7656...", ent = n, flags = n, uuid = "32 hex" | nil, name = s | nil,
//   tokenHash = "16 hex" | nil }, ... }) -> count (one past the highest slot written).
// v14: HostPlayers is slot-indexed: each record goes to players[slot] (0-based, e.g. entIndex - 1)
// and keeps it while that player is connected; unlisted slots are empty (steamId 0). A record
// without a free slot of its own takes the first free one. uuid/name are what that player's
// Minecraft reported (set kHostPlayerHasMc in flags with them). tokenHash (P6b): the first 8 bytes
// of SHA-256 of the player's current join token, in HostPlayer::reserved; the Minecraft server
// checks the token that player presents against it.
LUA_FUNCTION_STATIC(SetHostPlayers)
{
	if (!g_link.Open() || !LUA->IsType(1, Type::Table))
	{
		LUA->PushNumber(0);
		return 1;
	}
	static P::HostPlayers next;  // 8 KiB: not on the stack, and no destructor
	std::memset(&next, 0, sizeof next);
	std::uint32_t count = 0;
	for (int i = 1; i <= static_cast<int>(P::kMaxPlayers); ++i)
	{
		LUA->PushNumber(i);
		LUA->GetTable(1);
		if (!LUA->IsType(-1, Type::Table))
		{
			LUA->Pop();
			break;
		}
		int t = LUA->Top();
		char buf[48];
		std::uint64_t sid = 0;
		if (FieldStr(LUA, t, "steamId", buf, sizeof buf) > 0)
			ParseU64(buf, &sid);
		const double want = FieldNum(LUA, t, "slot", -1);
		int slot = -1;
		if (want >= 0 && want < P::kMaxPlayers && next.players[static_cast<int>(want)].steamId == 0)
			slot = static_cast<int>(want);
		for (int k = 0; slot < 0 && k < static_cast<int>(P::kMaxPlayers); ++k)
			if (next.players[k].steamId == 0)
				slot = k;
		if (sid == 0 || slot < 0)
		{
			LUA->Pop();
			continue;
		}
		P::HostPlayer &p = next.players[slot];
		p.steamId = sid;
		p.entIndex = static_cast<std::uint32_t>(FieldInt(LUA, t, "ent", 0, 0xFFFFFFFF));
		p.flags = static_cast<std::uint32_t>(FieldInt(LUA, t, "flags", 0, 0xFFFFFFFF));
		if (FieldStr(LUA, t, "uuid", buf, sizeof buf) > 0 && !HexToUuid(buf, p.mcUuid))
			std::memset(p.mcUuid, 0, sizeof p.mcUuid);
		FieldStr(LUA, t, "name", p.mcName, sizeof p.mcName);
		if (FieldStr(LUA, t, "tokenHash", buf, sizeof buf) == 16 && !HexToBytes(buf, p.reserved, sizeof p.reserved))
			std::memset(p.reserved, 0, sizeof p.reserved);
		LUA->Pop();
		if (static_cast<std::uint32_t>(slot) + 1 > count)
			count = static_cast<std::uint32_t>(slot) + 1;
	}
	next.count = count;
	SeqWrite(g_link.At<P::HostPlayers>(P::kSvOffHostPlayers), [&](P::HostPlayers &d) {
		std::uint32_t seq = d.seq;
		std::memcpy(static_cast<void *>(&d), &next, sizeof next);
		d.seq = seq;
	});
	LUA->PushNumber(count);
	return 1;
}

// NewJoinToken() -> token (32 hex: 128 random bits), tokenHash (16 hex: the first 8 bytes of
// SHA-256(token)) | nil, err. P6b pairing: the token goes to that player's client only, the hash
// into its HostPlayers slot (SetHostPlayers tokenHash). Never log the token.
LUA_FUNCTION_STATIC(NewJoinTokenLua)
{
	char token[33];
	std::uint8_t hash[8];
	if (!NewJoinToken(token, hash))
		return PushFail(LUA, "no randomness (/dev/urandom unreadable)");
	char hex[17];
	BytesToHex(hash, sizeof hash, hex);
	LUA->PushString(token);
	LUA->PushString(hex);
	std::memset(token, 0, sizeof token);
	return 2;
}

// JoinTokenHash(token) -> 16 hex: the first 8 bytes of SHA-256(token).
LUA_FUNCTION_STATIC(JoinTokenHashLua)
{
	unsigned int n = 0;
	const char *t = LUA->IsType(1, Type::String) ? LUA->GetString(1, &n) : "";
	std::uint8_t hash[8];
	JoinTokenHash(t, n, hash);
	char hex[17];
	BytesToHex(hash, sizeof hash, hex);
	LUA->PushString(hex);
	return 1;
}

// McServerInfo() -> { seq, flags, port, requestId, maxPlayers, lan, e4mc } | nil: how other players
// reach this Minecraft server (v14). nil until Minecraft wrote it in this session.
LUA_FUNCTION_STATIC(McServerInfo)
{
	if (!g_link.Open())
		return 0;
	static P::McServerInfo tmp;
	if (SeqRead(g_link.At<P::McServerInfo>(P::kSvOffMcServerInfo), &tmp) > 0)
		g_info = tmp;
	if (g_info.seq == 0)
		return 0;
	LUA->CreateTable();
	SetNum(LUA, "seq", g_info.seq);
	SetNum(LUA, "flags", g_info.flags);
	SetNum(LUA, "port", g_info.port);
	SetNum(LUA, "requestId", g_info.requestId);
	SetNum(LUA, "maxPlayers", g_info.maxPlayers);
	SetStrN(LUA, "lan", g_info.lanAddress, sizeof g_info.lanAddress);
	SetStrN(LUA, "e4mc", g_info.e4mcAddress, sizeof g_info.e4mcAddress);
	return 1;
}

// PushDevCommand(requestId, command) -> true | nil, err. DEV ONLY (-gmodcraft_dev): runs a
// Minecraft command as the MC server's console (kHostEvDevCommand + its text slots, published at
// once). Answered by a kEvDevCommandResult with that requestId (DrainEvents: result, count,
// output). Minecraft runs it only when started with GMODCRAFT_DEV_COMMANDS=1 (else
// kDevCommandDisabled).
LUA_FUNCTION_STATIC(PushDevCommand)
{
	if (!IsDevMode())
		return PushFail(LUA, "dev commands need -gmodcraft_dev");
	if (!g_link.Open())
		return PushFail(LUA, "link closed");
	const double id = ArgNum(LUA, 1);
	if (!(id >= 1 && id <= 4294967295.0) || !LUA->IsType(2, Type::String))
		return PushFail(LUA, "PushDevCommand(requestId > 0, command)");
	unsigned int n = 0;
	const char *cmd = LUA->GetString(2, &n);
	if (n > 0 && cmd[0] == '/')
	{
		++cmd;
		--n;
	}
	if (n == 0 || n > P::kDevCommandMaxBytes)
		return PushFail(LUA, "command empty or longer than kDevCommandMaxBytes");
	const std::uint32_t chunks = (n + P::kDevCommandChunkBytes - 1) / P::kDevCommandChunkBytes;
	P::HostEvent ev[1 + P::kDevCommandMaxChunks];
	std::memset(static_cast<void *>(ev), 0, sizeof ev);
	ev[0].type = P::kHostEvDevCommand;
	ev[0].code = static_cast<std::uint16_t>(chunks);
	ev[0].requestId = static_cast<std::uint32_t>(id);
	ev[0].a = static_cast<std::int32_t>(n);
	for (std::uint32_t i = 0; i < chunks; ++i)
	{
		P::HostEvent &t = ev[1 + i];
		t.type = P::kHostEvDevCommandText;
		t.code = static_cast<std::uint16_t>(i);
		const std::uint32_t off = i * P::kDevCommandChunkBytes;
		const std::uint32_t len = std::min<std::uint32_t>(P::kDevCommandChunkBytes, n - off);
		std::memcpy(reinterpret_cast<std::uint8_t *>(&t) + P::kHeText, cmd + off, len);
	}
	if (!g_hostEvents.PushMany(ev, 1 + chunks))
		return PushFail(LUA, "host event ring full");
	LUA->PushBool(true);
	return 1;
}

// McPlayers() -> { { uuid, steamId (string, "0" unmapped), entityId, flags, health, maxHealth, name }, ... }
LUA_FUNCTION_STATIC(McPlayers)
{
	LUA->CreateTable();
	if (!g_link.Open() || g_players == nullptr)
		return 1;
	static P::McPlayers tmp;
	if (SeqRead(g_link.At<P::McPlayers>(P::kSvOffMcPlayers), &tmp) > 0)
		std::memcpy(static_cast<void *>(g_players), &tmp, sizeof tmp);
	std::uint32_t n = g_players->count < P::kMaxPlayers ? g_players->count : P::kMaxPlayers;
	for (std::uint32_t i = 0; i < n; ++i)
	{
		const P::McPlayer &p = g_players->players[i];
		LUA->PushNumber(i + 1);
		LUA->CreateTable();
		char hex[33];
		UuidToHex(p.uuid, hex);
		SetStr(LUA, "uuid", hex);
		PushU64(LUA, p.steamId);
		LUA->SetField(-2, "steamId");
		SetNum(LUA, "entityId", p.entityId);
		SetNum(LUA, "flags", p.flags);
		SetNum(LUA, "health", p.health);
		SetNum(LUA, "maxHealth", p.maxHealth);
		SetStrN(LUA, "name", p.name, sizeof p.name);
		LUA->SetTable(-3);
	}
	return 1;
}

// McEntities() -> { { id, category, yaw (v31: MC degrees, body), flags, typeHash, x, y, z, vx, vy, vz, width, height, health, maxHealth }, ... } (v27):
// the Minecraft mobs, animals, carts and boats near the MC players, for the GMod proxies (B1).
LUA_FUNCTION_STATIC(McEntitiesLua)
{
	LUA->CreateTable();
	if (!g_link.Open() || g_mcEnts == nullptr)
		return 1;
	static P::McEntities tmp;
	if (SeqRead(g_link.At<P::McEntities>(P::kSvOffMcEntities), &tmp) > 0)
		std::memcpy(static_cast<void *>(g_mcEnts), &tmp, sizeof tmp);
	std::uint32_t n = g_mcEnts->count < P::kMaxMcEntities ? g_mcEnts->count : P::kMaxMcEntities;
	for (std::uint32_t i = 0; i < n; ++i)
	{
		const P::McEntity &e = g_mcEnts->entities[i];
		LUA->PushNumber(i + 1);
		LUA->CreateTable();
		SetNum(LUA, "id", e.entityId);
		SetNum(LUA, "category", e.category & P::kMcEntCategoryMask);  // v31: the yaw rides above
		SetNum(LUA, "yaw", ((e.category >> P::kMcEntYawShift) & (P::kMcEntYawSteps - 1)) * (360.0 / P::kMcEntYawSteps));
		SetNum(LUA, "flags", e.flags);
		SetNum(LUA, "typeHash", e.typeHash);
		SetNum(LUA, "x", e.x);
		SetNum(LUA, "y", e.y);
		SetNum(LUA, "z", e.z);
		SetNum(LUA, "vx", e.vx);
		SetNum(LUA, "vy", e.vy);
		SetNum(LUA, "vz", e.vz);
		SetNum(LUA, "width", e.width);
		SetNum(LUA, "height", e.height);
		SetNum(LUA, "health", e.health);
		SetNum(LUA, "maxHealth", e.maxHealth);
		LUA->SetTable(-3);
	}
	return 1;
}

// SetHeldMcEntities({ { id, flags, steamId = "7656..." | nil, x, y, z, vx, vy, vz, yaw, pitch }, ... }) -> count (v29, T2):
// the Minecraft entities GMod owns right now (held / frozen / constrained proxies), MC coordinates and
// MC blocks per tick; yaw / pitch (v32, MC degrees) optional: missing or non-finite = NaN (Minecraft keeps its own). The whole table every call (an empty list releases everything); entries with id 0
// or flags 0 are skipped, at most kMaxHeldMcEntities are kept. 0 while the link is closed.
LUA_FUNCTION_STATIC(SetHeldMcEntities)
{
	if (!g_link.Open() || !LUA->IsType(1, Type::Table))
	{
		LUA->PushNumber(0);
		return 1;
	}
	static P::HeldMcEntities next;  // 4 KiB: not on the stack
	std::memset(&next, 0, sizeof next);
	std::uint32_t n = 0;
	for (int i = 1; n < P::kMaxHeldMcEntities; ++i)
	{
		LUA->PushNumber(i);
		LUA->GetTable(1);
		if (!LUA->IsType(-1, Type::Table))
		{
			LUA->Pop();
			break;
		}
		int t = LUA->Top();
		P::HeldMcEntity &h = next.entities[n];
		h.entityId = static_cast<std::uint32_t>(FieldInt(LUA, t, "id", 0, 0xFFFFFFFF));
		h.flags = static_cast<std::uint32_t>(FieldInt(LUA, t, "flags", 0, 0xFFFFFFFF));
		char buf[48];
		std::uint64_t sid = 0;
		if (FieldStr(LUA, t, "steamId", buf, sizeof buf) > 0)
			ParseU64(buf, &sid);
		h.holderSteamId = sid;
		// FieldNum gives the default for a missing or non-finite number: NaN here, so the record is dropped
		// below instead of teleporting the entity to 0, 0, 0 (and NaN / inf never reach Minecraft).
		constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
		const double x = FieldNum(LUA, t, "x", kNaN), y = FieldNum(LUA, t, "y", kNaN), z = FieldNum(LUA, t, "z", kNaN);
		const double vx = FieldNum(LUA, t, "vx", kNaN), vy = FieldNum(LUA, t, "vy", kNaN), vz = FieldNum(LUA, t, "vz", kNaN);
		const bool finite = std::isfinite(x) && std::isfinite(y) && std::isfinite(z) && std::isfinite(vx) && std::isfinite(vy) && std::isfinite(vz);
		h.x = x;
		h.y = y;
		h.z = z;
		h.vx = static_cast<float>(vx);
		h.vy = static_cast<float>(vy);
		h.vz = static_cast<float>(vz);
		const double yaw = FieldNum(LUA, t, "yaw", kNaN), pitch = FieldNum(LUA, t, "pitch", kNaN);
		h.yaw = std::isfinite(yaw) ? static_cast<float>(std::fmod(yaw, 360.0)) : std::numeric_limits<float>::quiet_NaN();
		h.pitch = std::isfinite(pitch) ? static_cast<float>(std::clamp(pitch, -90.0, 90.0)) : std::numeric_limits<float>::quiet_NaN();
		LUA->Pop();
		if (h.entityId != 0 && h.flags != 0 && finite)
			++n;
		else
			h = P::HeldMcEntity{};
	}
	next.count = n;
	SeqWrite(g_link.At<P::HeldMcEntities>(P::kSvOffHeldMcEntities), [&](P::HeldMcEntities &d) {
		std::uint32_t seq = d.seq;
		std::memcpy(static_cast<void *>(&d), &next, sizeof next);
		d.seq = seq;
	});
	LUA->PushNumber(n);
	return 1;
}

// PushHostEvent({ type, code, ent, steamId = "7656...", requestId, flags, a, worldId, x, y, z, yaw, pitch }) -> ok
LUA_FUNCTION_STATIC(PushHostEvent)
{
	if (!g_link.Open() || !LUA->IsType(1, Type::Table))
	{
		LUA->PushBool(false);
		return 1;
	}
	P::HostEvent e{};
	e.type = static_cast<std::uint16_t>(FieldInt(LUA, 1, "type", 0, 65535));
	e.code = static_cast<std::uint16_t>(FieldInt(LUA, 1, "code", 0, 65535));
	e.entId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "ent", 0, 0xFFFFFFFF));
	char buf[48];
	std::uint64_t sid = 0;
	if (FieldStr(LUA, 1, "steamId", buf, sizeof buf) > 0)
		ParseU64(buf, &sid);
	e.steamId = sid;
	e.requestId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "requestId", 0, 0xFFFFFFFF));
	e.flags = static_cast<std::uint32_t>(FieldInt(LUA, 1, "flags", 0, 0xFFFFFFFF));
	e.a = static_cast<std::int32_t>(FieldInt(LUA, 1, "a", -2147483647.0, 2147483647.0));
	e.worldId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "worldId", 0, 0xFFFFFFFF));
	e.x = FieldNum(LUA, 1, "x");
	e.y = FieldNum(LUA, 1, "y");
	e.z = FieldNum(LUA, 1, "z");
	e.yaw = static_cast<float>(FieldNum(LUA, 1, "yaw"));
	e.pitch = static_cast<float>(FieldNum(LUA, 1, "pitch"));
	// v17: a class hash is a u32 (e.g. 0xC4131651); "hash" carries it into a as its int32 bits.
	if (e.type == P::kHostEvWeaponTake || e.type == P::kHostEvWeaponState || e.type == P::kHostEvSelectWeapon)  // v45: select
		e.a = static_cast<std::int32_t>(static_cast<std::uint32_t>(FieldInt(LUA, 1, "hash", 0, 4294967295.0)));
	// Every event targets a player except kHostEvOpenToLan and kHostEvBridgeOutputs (steamId 0), and
	// kHostEvHurtMcEntity (v27), whose steamId is the attacker's, 0 for an NPC or the world.
	// Dev commands go through PushDevCommand and weapon gives through PushWeaponGive (their text
	// slots must be published with them).
	const bool untargeted = e.type == P::kHostEvOpenToLan || e.type == P::kHostEvBridgeOutputs || e.type == P::kHostEvHurtMcEntity
		|| e.type == P::kHostEvFire       // v28: kHostEvFire
		|| e.type == P::kHostEvPuntMcEntity  // v29: steamId is the punting player's (0: none)
		|| e.type == P::kHostEvSetDayTime    // v43
		|| e.type == P::kHostEvPullBlock || e.type == P::kHostEvBlast;  // v44: the player's, 0: none
	const bool textSeq = e.type == P::kHostEvDevCommand || e.type == P::kHostEvDevCommandText || e.type == P::kHostEvWeaponGive
		|| e.type == P::kHostEvWeaponText || e.type == P::kHostEvAdminCommand || e.type == P::kHostEvAdminText
		|| e.type == P::kHostEvPropResult || e.type == P::kHostEvPropText;  // v33: PushPropResult
	LUA->PushBool(e.type != 0 && !textSeq && (sid != 0 || untargeted) && g_hostEvents.Push(e));
	return 1;
}

// PushWeaponGive({ steamId = "7656...", requestId, hash, category, clip1, clip2, class, name[, slot] }) -> true | nil, err
// v17 hybrid mode: kHostEvWeaponGive + its kHostEvWeaponText slots ("class\0name"), published at
// once. class: lowercase, at most kWeaponClassMaxBytes, no spaces; hash must be its FNV-1a 32
// (WorldId(class)). name (the print name) is cut to what fits kWeaponTextMaxBytes.
LUA_FUNCTION_STATIC(PushWeaponGive)
{
	if (!g_link.Open())
		return PushFail(LUA, "link closed");
	if (!LUA->IsType(1, Type::Table))
		return PushFail(LUA, "PushWeaponGive(table)");
	char buf[48];
	std::uint64_t sid = 0;
	if (FieldStr(LUA, 1, "steamId", buf, sizeof buf) > 0)
		ParseU64(buf, &sid);
	const double id = FieldInt(LUA, 1, "requestId", 0, 4294967295.0);
	char cls[P::kWeaponClassMaxBytes + 2] = {};
	const int clsLen = FieldStr(LUA, 1, "class", cls, sizeof cls);
	if (sid == 0 || id < 1 || clsLen <= 0 || clsLen > static_cast<int>(P::kWeaponClassMaxBytes))
		return PushFail(LUA, "PushWeaponGive: steamId, requestId > 0 and a class of 1..kWeaponClassMaxBytes bytes");
	char name[P::kWeaponTextMaxBytes + 1] = {};
	int nameLen = FieldStr(LUA, 1, "name", name, sizeof name);
	if (nameLen < 0)
		nameLen = 0;
	std::uint8_t text[P::kWeaponTextMaxBytes] = {};
	std::memcpy(text, cls, static_cast<std::size_t>(clsLen));
	// "class\0name": the name gets what is left, cut on a UTF-8 character boundary.
	std::size_t room = P::kWeaponTextMaxBytes - static_cast<std::size_t>(clsLen) - 1;
	std::size_t n = std::min<std::size_t>(static_cast<std::size_t>(nameLen), room);
	while (n > 0 && n < static_cast<std::size_t>(nameLen) && (static_cast<std::uint8_t>(name[n]) & 0xC0) == 0x80)
		--n;
	std::memcpy(text + clsLen + 1, name, n);
	const std::size_t used = static_cast<std::size_t>(clsLen) + 1 + n;
	const std::uint32_t chunks = static_cast<std::uint32_t>((used + P::kWeaponTextChunkBytes - 1) / P::kWeaponTextChunkBytes);
	P::HostEvent ev[1 + P::kWeaponTextMaxChunks];
	std::memset(static_cast<void *>(ev), 0, sizeof ev);
	ev[0].type = P::kHostEvWeaponGive;
	ev[0].code = static_cast<std::uint16_t>(chunks);
	ev[0].steamId = sid;
	ev[0].requestId = static_cast<std::uint32_t>(id);
	ev[0].a = static_cast<std::int32_t>(static_cast<std::uint32_t>(FieldInt(LUA, 1, "hash", 0, 4294967295.0)));
	ev[0].flags = static_cast<std::uint32_t>(FieldInt(LUA, 1, "category", 0, P::kWeapCategories - 1));
	ev[0].x = FieldNum(LUA, 1, "clip1", -1);
	ev[0].y = FieldNum(LUA, 1, "clip2", -1);
	// v35: slot = the inventory slot to put it into (0..35; nil / -1: anywhere), sent as entId = slot + 1.
	ev[0].entId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "slot", -1, 35, -1) + 1);
	for (std::uint32_t i = 0; i < chunks; ++i)
	{
		P::HostEvent &t = ev[1 + i];
		t.type = P::kHostEvWeaponText;
		t.code = static_cast<std::uint16_t>(i);
		std::memcpy(reinterpret_cast<std::uint8_t *>(&t) + P::kHeText, text + i * P::kWeaponTextChunkBytes, P::kWeaponTextChunkBytes);
	}
	if (!g_hostEvents.PushMany(ev, 1 + chunks))
		return PushFail(LUA, "host event ring full");
	LUA->PushBool(true);
	return 1;
}

// PushPropResult({ steamId = "7656...", requestId, op, result, ent, skin, color, model, material, bodygroups, dupe })
//   -> true | nil, err
// v33 (P1): kHostEvPropResult answering a kEvPropRequest. A pickup with kPropOk carries the prop text
// ("model\0material\0bodygroups\0dupe") in kHostEvPropText slots, published with it; every other answer
// has none. color: RGBA as a u32 (r in the top byte).
LUA_FUNCTION_STATIC(PushPropResult)
{
	if (!g_link.Open())
		return PushFail(LUA, "link closed");
	if (!LUA->IsType(1, Type::Table))
		return PushFail(LUA, "PushPropResult(table)");
	char buf[48];
	std::uint64_t sid = 0;
	if (FieldStr(LUA, 1, "steamId", buf, sizeof buf) > 0)
		ParseU64(buf, &sid);
	const double id = FieldInt(LUA, 1, "requestId", 0, 4294967295.0);
	const double op = FieldInt(LUA, 1, "op", 0, 255);
	const double result = FieldInt(LUA, 1, "result", 0, 255);
	if (sid == 0 || id < 1 || (op != P::kPropOpPickup && op != P::kPropOpPlace))
		return PushFail(LUA, "PushPropResult: steamId, requestId > 0, op = kPropOpPickup | kPropOpPlace");
	std::uint8_t text[P::kPropTextMaxBytes] = {};
	std::size_t used = 0;
	const bool give = op == P::kPropOpPickup && result == P::kPropOk;
	if (give)
	{
		struct Part { const char *key; std::size_t max; bool required; };
		const Part parts[] = { { "model", P::kPropModelMaxBytes, true }, { "material", P::kPropMaterialMaxBytes, false },
			{ "bodygroups", P::kPropBodygroupsMaxBytes, false }, { "dupe", P::kPropDupeMaxBytes, false } };
		for (const Part &part : parts)
		{
			char field[P::kPropModelMaxBytes + 2] = {};
			int n = FieldStr(LUA, 1, part.key, field, sizeof field);
			if (n < 0)
				n = 0;
			if ((part.required && n == 0) || static_cast<std::size_t>(n) > part.max || std::memchr(field, 0, static_cast<std::size_t>(n)) != nullptr)
				return PushFail(LUA, "PushPropResult: model 1..kPropModelMaxBytes, material / bodygroups / dupe within their kProp*MaxBytes");
			std::memcpy(text + used, field, static_cast<std::size_t>(n));
			used += static_cast<std::size_t>(n) + 1;  // its NUL
		}
	}
	const std::uint32_t chunks = static_cast<std::uint32_t>((used + P::kPropHostChunkBytes - 1) / P::kPropHostChunkBytes);
	P::HostEvent ev[1 + P::kPropHostMaxChunks];
	std::memset(static_cast<void *>(ev), 0, sizeof ev);
	ev[0].type = P::kHostEvPropResult;
	ev[0].code = static_cast<std::uint16_t>(chunks);
	ev[0].steamId = sid;
	ev[0].requestId = static_cast<std::uint32_t>(id);
	ev[0].a = static_cast<std::int32_t>(op);
	ev[0].flags = static_cast<std::uint32_t>(result);
	ev[0].entId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "ent", 0, 0xFFFFFFFF));
	ev[0].x = FieldInt(LUA, 1, "skin", 0, 65535);
	ev[0].y = FieldInt(LUA, 1, "color", 0, 4294967295.0);
	for (std::uint32_t i = 0; i < chunks; ++i)
	{
		P::HostEvent &t = ev[1 + i];
		t.type = P::kHostEvPropText;
		t.code = static_cast<std::uint16_t>(i);
		const std::size_t at = i * P::kPropHostChunkBytes;
		std::memcpy(reinterpret_cast<std::uint8_t *>(&t) + P::kHeText, text + at, std::min<std::size_t>(P::kPropHostChunkBytes, P::kPropTextMaxBytes - at));
	}
	if (!g_hostEvents.PushMany(ev, 1 + chunks))
		return PushFail(LUA, "host event ring full");
	LUA->PushBool(true);
	return 1;
}

// PushAdminCommand({ steamId = "7656...", requestId, code, worldId, x, y, z, yaw, a, flags, name }) -> true | nil, err
// v19: kHostEvAdminCommand + one kHostEvAdminText slot (the name, <= kAdminTextBytes), published with
// one head store. The Lua side sends it only for admins; steamId may be "0" (singleplayer / console).
LUA_FUNCTION_STATIC(PushAdminCommand)
{
	if (!g_link.Open())
		return PushFail(LUA, "link closed");
	if (!LUA->IsType(1, Type::Table))
		return PushFail(LUA, "PushAdminCommand(table)");
	char buf[48];
	std::uint64_t sid = 0;
	if (FieldStr(LUA, 1, "steamId", buf, sizeof buf) > 0)
		ParseU64(buf, &sid);
	const double id = FieldInt(LUA, 1, "requestId", 0, 4294967295.0);
	const double code = FieldInt(LUA, 1, "code", 0, 65535);
	char name[P::kAdminTextBytes + 2] = {};
	int nameLen = FieldStr(LUA, 1, "name", name, sizeof name);
	if (nameLen < 0)
		nameLen = 0;
	if (id < 1 || code < 1 || nameLen > static_cast<int>(P::kAdminTextBytes))
		return PushFail(LUA, "PushAdminCommand: requestId > 0, code > 0, a name of at most kAdminTextBytes bytes");
	P::HostEvent ev[2];
	std::memset(static_cast<void *>(ev), 0, sizeof ev);
	ev[0].type = P::kHostEvAdminCommand;
	ev[0].code = static_cast<std::uint16_t>(code);
	ev[0].steamId = sid;
	ev[0].requestId = static_cast<std::uint32_t>(id);
	ev[0].worldId = static_cast<std::uint32_t>(FieldInt(LUA, 1, "worldId", 0, 0xFFFFFFFF));
	ev[0].a = static_cast<std::int32_t>(FieldInt(LUA, 1, "a", 0, 2147483647.0));
	ev[0].flags = static_cast<std::uint32_t>(FieldInt(LUA, 1, "flags", 0, 0xFFFFFFFF));
	ev[0].x = FieldNum(LUA, 1, "x");
	ev[0].y = FieldNum(LUA, 1, "y");
	ev[0].z = FieldNum(LUA, 1, "z");
	ev[0].yaw = static_cast<float>(FieldNum(LUA, 1, "yaw"));
	ev[1].type = P::kHostEvAdminText;
	std::memcpy(reinterpret_cast<std::uint8_t *>(&ev[1]) + P::kHeText, name, static_cast<std::size_t>(nameLen));
	if (!g_hostEvents.PushMany(ev, 2))
		return PushFail(LUA, "host event ring full");
	LUA->PushBool(true);
	return 1;
}

// McWeaponSets() -> { { steamId (string), held, lastRequestId, flags, items = { { hash, clip1 }, ... } }, ... }
// v17: every set in use whose steamId is non-zero (match players by steamId, not by index). A set
// that keeps tearing keeps its last good copy.
LUA_FUNCTION_STATIC(McWeaponSets)
{
	LUA->CreateTable();
	if (!g_link.Open())
		return 1;
	if (g_sets == nullptr)
		g_sets = new P::McWeaponSet[P::kMaxPlayers]();
	const auto *table = g_link.At<P::McWeaponSets>(P::kSvOffMcWeaponSets);
	std::uint32_t count = LoadAcq32(&table->count);
	if (count > P::kMaxPlayers)
		count = P::kMaxPlayers;
	int out = 0;
	for (std::uint32_t i = 0; i < count; ++i)
	{
		P::McWeaponSet tmp;
		if (SeqRead(&table->sets[i], &tmp) > 0)
			g_sets[i] = tmp;
		const P::McWeaponSet &s = g_sets[i];
		if (s.seq == 0 || s.steamId == 0)
			continue;
		LUA->PushNumber(++out);
		LUA->CreateTable();
		PushU64(LUA, s.steamId);
		LUA->SetField(-2, "steamId");
		SetNum(LUA, "held", s.heldHash);
		SetNum(LUA, "lastRequestId", s.lastRequestId);
		SetNum(LUA, "flags", s.flags);
		LUA->CreateTable();
		const std::uint32_t n = s.count < P::kMaxWeaponsPerPlayer ? s.count : P::kMaxWeaponsPerPlayer;
		for (std::uint32_t k = 0; k < n; ++k)
		{
			LUA->PushNumber(k + 1);
			LUA->CreateTable();
			SetNum(LUA, "hash", s.entries[k].hash);
			SetNum(LUA, "clip1", s.entries[k].clip1);
			LUA->SetTable(-3);
		}
		LUA->SetField(-2, "items");
		LUA->SetTable(-3);
	}
	return 1;
}

// DrainEvents() -> { { type, ent, steamId (string), a, b, c, d, flags, weapon, requestId, result[, pitch]
//   [, damage, speed][, count, output] }, ... }
// pitch: kEvArrowStuck and kEvProjectileHit, the flight pitch decoded from the float bits in flags
// (degrees, positive up). damage, speed: kEvProjectileHit only (float bits in requestId / result:
// MC damage before the host's scale, blocks per tick); non-finite values come out as 0.
// count, output: kEvDevCommandResult only: the command's result count (int32 bits in flags) and its
// first output line (the kEvDevCommandText slots that follow it, joined; they aren't listed).
// text: kEvStructData only (v38): the kEvStructText slots that follow it, joined (binary-safe, NUL
// padding of the last slot kept; the host cuts it to the text's length). complete = all slots came.
// x, y, z, worldId, yawQ, instance, kind, announce: the v19 demo events (kEvDemoPlaced / Cleared):
// origin block, map, yaw quarter, instance id, DemoKind, re-announced (entId 1).
// x, y, z, worldId, levels: the v16 bridge events (kEvBridgePlaced / Removed / Inputs): the block
// (integers), its map, and levels[1..6] = the input level per BridgeFace (index = face + 1).
LUA_FUNCTION_STATIC(DrainEvents)
{
	LUA->CreateTable();
	if (!g_link.Open())
		return 1;
	int i = 0;
	int devIdx = 0;             // the kEvDevCommandResult whose text is being collected
	std::uint32_t devLeft = 0;  // text slots still expected for it
	std::string devText;
	auto finishDev = [&]() {
		if (devIdx == 0)
			return;
		const std::size_t end = devText.find('\0');
		if (end != std::string::npos)
			devText.resize(end);
		LUA->PushNumber(devIdx);
		LUA->GetTable(-2);
		SetStr(LUA, "output", devText.c_str());
		LUA->Pop();
		devIdx = 0;
		devLeft = 0;
		devText.clear();
	};
	// v33 (P1): the kEvPropRequest whose prop text is being collected (kEvPropText slots)
	int propIdx = 0;
	std::uint32_t propLeft = 0;
	std::string propText;
	auto finishProp = [&]() {
		if (propIdx == 0)
			return;
		// "model NUL material NUL bodygroups NUL dupe": each part cut at its NUL (a missing part is "")
		static const char *const keys[] = { "model", "material", "bodygroups", "dupe" };
		LUA->PushNumber(propIdx);
		LUA->GetTable(-2);
		std::size_t at = 0;
		for (const char *key : keys)
		{
			std::size_t end = at < propText.size() ? propText.find('\0', at) : std::string::npos;
			if (end == std::string::npos)
				end = propText.size();
			SetStrN(LUA, key, propText.data() + std::min(at, propText.size()), at < end ? end - at : 0);
			at = end + 1;
		}
		LUA->PushBool(propLeft == 0);
		LUA->SetField(-2, "complete");
		LUA->Pop();
		propIdx = 0;
		propLeft = 0;
		propText.clear();
	};
	// v38 (S2): the kEvStructData whose kEvStructText slots are being collected
	int structIdx = 0;
	std::uint32_t structLeft = 0;
	std::string structText;
	auto finishStruct = [&]() {
		if (structIdx == 0)
			return;
		LUA->PushNumber(structIdx);
		LUA->GetTable(-2);
		SetStrN(LUA, "text", structText.data(), structText.size());
		LUA->PushBool(structLeft == 0);
		LUA->SetField(-2, "complete");
		LUA->Pop();
		structIdx = 0;
		structLeft = 0;
		structText.clear();
	};
	g_events.Drain([&](const P::McEvent &e) {
		if (e.type == P::kEvStructText)
		{
			if (structIdx != 0 && structLeft > 0)
			{
				structText.append(reinterpret_cast<const char *>(&e) + P::kMeText, P::kStructChunkBytes);
				if (--structLeft == 0)
					finishStruct();
			}
			return;  // a stray text slot is dropped
		}
		finishStruct();  // a batch whose slots didn't all come: complete = false
		if (e.type == P::kEvPropText)
		{
			if (propIdx != 0 && propLeft > 0)
			{
				propText.append(reinterpret_cast<const char *>(&e) + P::kMeText, P::kPropMcChunkBytes);
				if (--propLeft == 0)
					finishProp();
			}
			return;  // a stray prop text slot is dropped
		}
		finishProp();  // a request whose text slots didn't all come: complete = false
		if (e.type == P::kEvDevCommandText)
		{
			if (devIdx != 0 && devLeft > 0)
			{
				devText.append(reinterpret_cast<const char *>(&e) + P::kMeText, P::kDevOutputChunkBytes);
				if (--devLeft == 0)
					finishDev();
			}
			return;  // a stray text slot (no result before it) is dropped
		}
		finishDev();  // a result whose text slots didn't all come: what arrived is its output
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "type", e.type);
		SetNum(LUA, "ent", e.entId);
		PushU64(LUA, e.steamId);
		LUA->SetField(-2, "steamId");
		SetNum(LUA, "a", e.a);
		SetNum(LUA, "b", e.b);
		SetNum(LUA, "c", e.c);
		SetNum(LUA, "d", e.d);
		SetNum(LUA, "flags", e.flags);
		SetNum(LUA, "weapon", e.weapon);
		SetNum(LUA, "requestId", e.requestId);
		SetNum(LUA, "result", e.result);
		if (e.type == P::kEvArrowStuck || e.type == P::kEvProjectileHit)
		{
			float pitch;  // the arrow's flight pitch travels as float bits in flags
			std::memcpy(&pitch, &e.flags, sizeof pitch);
			SetNum(LUA, "pitch", std::isfinite(pitch) ? pitch : 0.0);
		}
		if (e.type == P::kEvProjectileHit)
		{
			float damage, speed;  // float bits in requestId / result (v15)
			std::memcpy(&damage, &e.requestId, sizeof damage);
			std::memcpy(&speed, &e.result, sizeof speed);
			SetNum(LUA, "damage", std::isfinite(damage) ? damage : 0.0);
			SetNum(LUA, "speed", std::isfinite(speed) ? speed : 0.0);
		}
		if (e.type == P::kEvBridgeLink)
		{
			// v25: linked (entId), MapCreationID (weapon, int32 bits), kind / mode (flags)
			LUA->PushBool(e.entId == 1);
			LUA->SetField(-2, "linked");
			SetNum(LUA, "creationId", static_cast<std::int32_t>(e.weapon));
			SetNum(LUA, "kind", e.flags & 0xFF);
			SetNum(LUA, "mode", (e.flags >> 8) & 0xFF);
		}
		if (e.type == P::kEvBridgePlaced || e.type == P::kEvBridgeRemoved || e.type == P::kEvBridgeInputs || e.type == P::kEvBridgeLink)
		{
			// Integer block coords as floats (exact); anything else is clamped to a sane integer.
			auto coord = [](float v) { return std::isfinite(v) ? std::floor(std::max(-3.0e7f, std::min(3.0e7f, v))) : 0.0f; };
			SetNum(LUA, "x", coord(e.a));
			SetNum(LUA, "y", coord(e.b));
			SetNum(LUA, "z", coord(e.c));
			SetNum(LUA, "worldId", e.requestId);
			LUA->CreateTable();
			for (std::uint32_t f = 0; f < P::kBridgeFaces; ++f)
			{
				LUA->PushNumber(f + 1);
				LUA->PushNumber((e.flags >> (f * P::kBridgeLevelBits)) & 0xF);
				LUA->SetTable(-3);
			}
			LUA->SetField(-2, "levels");
		}
		if (e.type == P::kEvDemoPlaced || e.type == P::kEvDemoCleared)
		{
			auto coord = [](float v) { return std::isfinite(v) ? std::floor(std::max(-3.0e7f, std::min(3.0e7f, v))) : 0.0f; };
			SetNum(LUA, "x", coord(e.a));
			SetNum(LUA, "y", coord(e.b));
			SetNum(LUA, "z", coord(e.c));
			SetNum(LUA, "yawQ", std::isfinite(e.d) ? static_cast<double>(static_cast<int>(e.d) & 3) : 0.0);
			SetNum(LUA, "instance", e.requestId);
			SetNum(LUA, "kind", e.weapon);
			SetNum(LUA, "worldId", e.result);
			LUA->PushBool(e.entId == 1);
			LUA->SetField(-2, "announce");
		}
		if (e.type == P::kEvPropRequest)
		{
			// v33: op / skin from flags, colour (RGBA u32) from result, yaw from d
			SetNum(LUA, "op", e.flags & 0xFF);
			SetNum(LUA, "skin", e.flags >> P::kPropSkinShift);
			SetNum(LUA, "color", e.result);
			SetNum(LUA, "yaw", std::isfinite(e.d) ? e.d : 0.0);
		}
		if (e.type == P::kEvDevCommandResult)
		{
			std::int32_t count;
			std::memcpy(&count, &e.flags, sizeof count);
			SetNum(LUA, "count", count);
			SetStr(LUA, "output", "");
		}
		LUA->SetTable(-3);
		if (e.type == P::kEvDevCommandResult && e.weapon > 0)
		{
			devIdx = i;
			devLeft = std::min<std::uint32_t>(e.weapon, P::kDevOutputMaxChunks);
		}
		if (e.type == P::kEvPropRequest)
		{
			propIdx = i;
			propLeft = std::min<std::uint32_t>(e.weapon, P::kPropMcMaxChunks);
			if (propLeft == 0)
				finishProp();  // a pickup: no text (model etc. come out as "")
		}
		if (e.type == P::kEvStructData)
		{
			structIdx = i;
			structLeft = std::min<std::uint32_t>(e.weapon, P::kStructMaxChunks);
			if (structLeft == 0)
				finishStruct();
		}
	});
	finishDev();
	finishProp();
	finishStruct();
	return 1;
}

// ---- P5a block collision (docs/DESIGN.md section 9) ------------------------------------------
// Sections are absolute MC section coords. Source box of section (sx, sy, sz) with slot origin
// (ox, oz) in blocks and vertical offset oy in Source units (v21): x [(16sx - ox)*40, +640],
// y [-(16sz + 16 - oz)*40, +640], z [640sy - oy, +640].

// BlockDirty(max) -> { { sx, sy, sz, solid = bool }, ... }: sections changed since the last call
// (oldest first, each once). solid = false: the section has no solid blocks any more.
LUA_FUNCTION_STATIC(BlockDirty)
{
	double maxArg = LUA->IsType(1, Type::Number) ? LUA->GetNumber(1) : 64.0;
	std::vector<sb::SectionKey> keys;
	Solids().TakeDirty(static_cast<std::size_t>(std::max(0.0, std::min(maxArg, 1e6))), keys);
	LUA->CreateTable();
	int i = 0;
	for (const sb::SectionKey &k : keys)
	{
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "sx", k.sx);
		SetNum(LUA, "sy", k.sy);
		SetNum(LUA, "sz", k.sz);
		SetBool(LUA, "solid", Solids().Has(k));  // solid blocks or (v36) microblocks
		LUA->SetTable(-3);
	}
	return 1;
}

// BlockBoxes(sx, sy, sz) -> packed, boxCount, mode, mergeMs, microPacked, microCount | nothing. packed: 6 bytes per box
// (x0 y0 z0 x1 y1 z1: MC section-local in HALF BLOCKS (v26), half-open, 0..32); mode 0 greedy, 1 column runs;
// mergeMs: this call's merge time (0 when it was cached). v36: microPacked, the same 6 bytes per box
// for the section's microblocks, in EIGHTHS (0..128); packed is "" (boxCount 0) when it has only those.
LUA_FUNCTION_STATIC(BlockBoxes)
{
	sb::SectionKey k{ static_cast<std::int32_t>(ArgNum(LUA, 1)), static_cast<std::int32_t>(ArgNum(LUA, 2)),
		static_cast<std::int32_t>(ArgNum(LUA, 3)) };
	std::uint64_t merges = Solids().Stats().merges;
	const sb::Section *s = Solids().Merged(k);
	const std::vector<sb::Box> *micro = Solids().FindMicro(k);
	const bool half = s != nullptr && !s->boxes.empty();
	if (!half && micro == nullptr)
		return 0;
	std::string packed = half ? sb::PackBoxes(s->boxes) : std::string();
	LUA->PushString(packed.data(), static_cast<unsigned int>(packed.size()));
	LUA->PushNumber(half ? static_cast<double>(s->boxes.size()) : 0.0);
	LUA->PushNumber(half ? s->mode : 0);
	LUA->PushNumber(Solids().Stats().merges != merges ? Solids().Stats().lastMergeMs : 0.0);
	std::string mp = micro != nullptr ? sb::PackBoxes(*micro) : std::string();
	LUA->PushString(mp.data(), static_cast<unsigned int>(mp.size()));
	LUA->PushNumber(micro != nullptr ? static_cast<double>(micro->size()) : 0.0);
	return 6;
}

// BlockNear({ x, y, z, x, y, z, ... } (Source points), radius, ox, oz[, max = 4096[, oy = 0]])
// (oy, v21: the slot's vertical offset in Source units)
// -> { { sx, sy, sz }, ... }: sections with solid blocks whose box lies within radius of a point.
LUA_FUNCTION_STATIC(BlockNear)
{
	LUA->CreateTable();
	if (!LUA->IsType(1, Type::Table))
		return 1;
	std::vector<double> pts;
	for (int i = 1; i <= 3 * 1024; ++i)
	{
		LUA->PushNumber(i);
		LUA->GetTable(1);
		bool num = LUA->IsType(-1, Type::Number);
		double v = num ? LUA->GetNumber(-1) : 0;
		LUA->Pop();
		if (!num)
			break;
		pts.push_back(v);
	}
	double r = ArgNum(LUA, 2, 2048), ox = ArgNum(LUA, 3), oz = ArgNum(LUA, 4);
	double max = std::max(0.0, std::min(ArgNum(LUA, 5, 4096), 65536.0));
	const double oy = ArgNum(LUA, 6, 0);
	double r2 = r * r;
	int out = 0;
	std::vector<sb::SectionKey> keys;
	keys.reserve(Solids().SectionCount() + Solids().Micro().size());
	for (const auto &kv : Solids().Sections())
		keys.push_back(kv.first);
	for (const auto &kv : Solids().Micro())  // v36: sections with only microblocks too
		if (Solids().Find(kv.first) == nullptr)
			keys.push_back(kv.first);
	for (const sb::SectionKey &k : keys)
	{
		if (out >= max)
			break;
		double x0 = (16.0 * k.sx - ox) * 40, y0 = -(16.0 * k.sz + 16 - oz) * 40, z0 = 640.0 * k.sy - oy;
		for (std::size_t i = 0; i + 2 < pts.size(); i += 3)
		{
			double dx = std::max({ x0 - pts[i], 0.0, pts[i] - (x0 + 640) });
			double dy = std::max({ y0 - pts[i + 1], 0.0, pts[i + 1] - (y0 + 640) });
			double dz = std::max({ z0 - pts[i + 2], 0.0, pts[i + 2] - (z0 + 640) });
			if (dx * dx + dy * dy + dz * dz <= r2)
			{
				LUA->PushNumber(++out);
				LUA->CreateTable();
				SetNum(LUA, "sx", k.sx);
				SetNum(LUA, "sy", k.sy);
				SetNum(LUA, "sz", k.sz);
				LUA->SetTable(-3);
				break;
			}
		}
	}
	return 1;
}

// X1: BlockRay(x0, y0, z0, x1, y1, z1, ox, oz[, oy = 0]) (Source points; ox, oz: the slot origin in MC
// blocks, oy: its vertical offset in Source units, as BlockNear) -> fraction, nx, ny, nz (Source normal)
// of the nearest Minecraft block box the segment enters, or nothing. server/blocktrace.lua calls it for
// player view rays only.
LUA_FUNCTION_STATIC(BlockRay)
{
	double a[9];
	for (int i = 0; i < 9; ++i)
		a[i] = ArgNum(LUA, i + 1, i == 8 ? 0 : NAN);
	for (double v : a)
		if (!std::isfinite(v))
			return 0;
	const double ox = a[6], oz = a[7], oy = a[8];
	// Source (x, y, z) -> MC (x / 40 + ox, (z + oy) / 40, oz - y / 40)
	const double p0[3] = { a[0] / 40 + ox, (a[2] + oy) / 40, oz - a[1] / 40 };
	const double p1[3] = { a[3] / 40 + ox, (a[5] + oy) / 40, oz - a[4] / 40 };
	double t = 0;
	int n[3];
	if (!Solids().RayCast(p0, p1, t, n))
		return 0;
	LUA->PushNumber(t);
	LUA->PushNumber(n[0]);
	LUA->PushNumber(-n[2]);
	LUA->PushNumber(n[1]);
	return 4;
}

// BlockStats() -> { sections, dirty, solidsMsgs, unchanged, clears, merges, fallbacks, lastMergeMs,
// maxMergeMs, lastMergeBoxes }
LUA_FUNCTION_STATIC(BlockStats)
{
	const sb::StoreStats &st = Solids().Stats();
	LUA->CreateTable();
	SetNum(LUA, "sections", static_cast<double>(Solids().SectionCount()));
	SetNum(LUA, "dirty", static_cast<double>(Solids().DirtyCount()));
	SetNum(LUA, "solidsMsgs", static_cast<double>(st.solidsMsgs));
	SetNum(LUA, "unchanged", static_cast<double>(st.unchanged));
	SetNum(LUA, "clears", static_cast<double>(st.clears));
	SetNum(LUA, "merges", static_cast<double>(st.merges));
	SetNum(LUA, "fallbacks", static_cast<double>(st.fallbacks));
	SetNum(LUA, "lastMergeMs", st.lastMergeMs);
	SetNum(LUA, "maxMergeMs", st.maxMergeMs);
	SetNum(LUA, "lastMergeBoxes", st.lastMergeBoxes);
	SetNum(LUA, "microSections", static_cast<double>(Solids().Micro().size()));
	SetNum(LUA, "microMsgs", static_cast<double>(st.microMsgs));
	SetNum(LUA, "microBad", static_cast<double>(st.microBad));
	return 1;
}

const char *const kSvRingNames[] ={ "hostEvents", "collision", "events", "blocks" };
}  // namespace

void PushSideStats(ILua *L, const P::LinkSideStats &s, const char *const *ringNames, int rings);

namespace
{
LUA_FUNCTION_STATIC(Stats)
{
	LUA->CreateTable();
	SetStr(LUA, "realm", "server");
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
		SetNum(LUA, "serverStateSeq", LoadAcq32(&g_link.At<P::ServerState>(P::kSvOffServerState)->seq));
		SetNum(LUA, "mcServerStateSeq", LoadAcq32(&g_link.At<P::McServerState>(P::kSvOffMcServerState)->seq));
		SetNum(LUA, "mcServerInfoSeq", LoadAcq32(&g_link.At<P::McServerInfo>(P::kSvOffMcServerInfo)->seq));
		SetNum(LUA, "hostPlayersSeq", LoadAcq32(&g_link.At<P::HostPlayers>(P::kSvOffHostPlayers)->seq));
		SetNum(LUA, "mcPlayersSeq", LoadAcq32(&g_link.At<P::McPlayers>(P::kSvOffMcPlayers)->seq));
		SetNum(LUA, "actorSeq", LoadAcq32(&g_link.At<P::ActorTable>(P::kSvOffActorTable)->seq));
		SetNum(LUA, "actorCount", LoadAcq32(&g_link.At<P::ActorTable>(P::kSvOffActorTable)->count));
		SetNum(LUA, "blockMessages", static_cast<double>(g_blockMessages));
		SetNum(LUA, "blockClears", static_cast<double>(g_blockClears));
		SetNum(LUA, "blockMalformed", static_cast<double>(g_blocks.malformed));
		SetNum(LUA, "collisionFree", static_cast<double>(g_col.Free()));
		LUA->CreateTable();
		PushSideStats(LUA, *g_link.HostStats(), kSvRingNames, 4);
		LUA->SetField(-2, "host");
		PushSideStats(LUA, *g_link.McStats(), kSvRingNames, 4);
		LUA->SetField(-2, "mc");
		LUA->SetField(-2, "linkStats");
	}
	return 1;
}
}  // namespace

ByteRingWriter *RealmCollisionRing()
{
	return g_link.Open() ? &g_col : nullptr;
}

// v14: one grid per HostPlayers slot (the v13 single grid @kSvOffWaterGrid is no longer written).
P::WaterGrid *RealmWaterGrid(int slot)
{
	if (!g_link.Open() || slot < 0 || slot >= static_cast<int>(P::kMaxPlayers))
		return nullptr;
	return g_link.At<P::WaterGrid>(P::kSvOffWaterGrids + static_cast<std::uint64_t>(slot) * P::kWaterGridBytes);
}

P::ActorTable *RealmActorTable()
{
	return g_link.Open() ? g_link.At<P::ActorTable>(P::kSvOffActorTable) : nullptr;
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
		{ "McServerState", McServerState },
		{ "McServerSky", McServerSkyLua },  // v43
		{ "SetHostPlayers", SetHostPlayers },
		{ "NewJoinToken", NewJoinTokenLua },
		{ "JoinTokenHash", JoinTokenHashLua },
		{ "McServerInfo", McServerInfo },
		{ "PushDevCommand", PushDevCommand },
		{ "McPlayers", McPlayers },
		{ "McEntities", McEntitiesLua },        // v27
		{ "SetHeldMcEntities", SetHeldMcEntities },  // v29
		{ "PushHostEvent", PushHostEvent },
		{ "PushWeaponGive", PushWeaponGive },  // v17
		{ "PushPropResult", PushPropResult },  // v33
		{ "PushAdminCommand", PushAdminCommand },  // v19
		{ "McWeaponSets", McWeaponSets },      // v17
		{ "DrainEvents", DrainEvents },
		{ "Stats", Stats },
		{ "BlockDirty", BlockDirty },
		{ "BlockBoxes", BlockBoxes },
		{ "BlockNear", BlockNear },
		{ "BlockStats", BlockStats },
		{ "BlockRay", BlockRay },  // X1
	};
	for (const Fn &f : fns)
	{
		L->PushCFunction(f.fn);
		L->SetField(-2, f.name);
	}
	RegisterPhysWorld(L);
	RegisterHull(L);  // world type `hull`: HullBuild / HullStatus
}

const sb::Section *ServerSolidMerged(const sb::SectionKey &k)
{
	return Solids().Merged(k);
}

const sb::Section *ServerSolidFind(const sb::SectionKey &k)
{
	return Solids().Find(k);
}

const std::vector<sb::Box> *ServerSolidMicro(const sb::SectionKey &k)
{
	return Solids().FindMicro(k);
}

void SetServerSolidsHook(void (*hook)(int sx, int sy, int sz, bool all))
{
	g_solidsHook = hook;
}

void CloseRealm()
{
	ClosePhysWorld();
	CloseLink();
	delete g_players;
	g_players = nullptr;
	delete[] g_sets;
	g_sets = nullptr;
	delete g_solids;
	g_solids = nullptr;
}
}  // namespace gc

#endif  // GMODCRAFT_SERVER
