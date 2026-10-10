// GmodCraft shared-memory protocol (Garry's Mod host modules <-> Minecraft Fabric mod).
//
// This header is the single source of truth for the byte layout. The Java side mirrors it in
// fabric/src/main/java/dev/gmodcraft/link/Proto.java, and ProtoLayoutTest parses THIS file and
// fails the build if any constant differs. Rules that keep that check meaningful:
//   * every numeric constant is a plain literal (no arithmetic); derivations are static_asserts;
//   * every struct field the Java side touches has an offset constant checked with offsetof;
//   * fixed-width types only, no wchar_t / long / size_t / bool in shared structs.
// If you change anything here, change Proto.java too and bump kVersion.
//
// All multi-byte values are little-endian (x86-64). Coordinates are Minecraft space (blocks, Y up,
// Z south) unless noted. Seqlocks: the writer makes seq odd, writes, then makes it even again;
// a reader retries while seq is odd or changed across its read.
//
// ---- two links (D-001) -----------------------------------------------------------------------
// Client link: GMod client (gmcl_gmodcraft) <-> the Minecraft client of the same player.
// Server link: GMod server (gmsv_gmodcraft) <-> the Minecraft server (integrated or dedicated).
// The GMod side (host) creates each mapping; Minecraft opens it. A Minecraft client with an
// integrated server opens both; a dedicated Minecraft server opens only the server link; a client
// on a remote Minecraft server opens only the client link. Minecraft server-side code reads only
// the server link.
//
// ---- creating a link (host) ------------------------------------------------------------------
//  1. nonce = 64 random bits (getrandom). shm name = "gmodcraft-client-<nonce as 16 lowercase
//     hex digits>" (or "gmodcraft-server-..."). Never derive names from a PID: the host may run in
//     a Steam Linux Runtime container with its own PID namespace.
//  2. shm_open(name, O_RDWR|O_CREAT|O_EXCL, 0600), ftruncate to kClMappingBytes / kSvMappingBytes,
//     mmap MAP_SHARED. Zero-filled by the kernel. Fill LinkHeader (magic and version LAST, after
//     a release fence, so a reader never sees a half-made header as valid).
//  3. Write the discovery file /dev/shm/gmodcraft/client.json (or server.json). Not
//     $XDG_RUNTIME_DIR and not /tmp: GMod runs in pressure-vessel (Steam Runtime), whose
//     /run/user/<uid> is a container-private tmpfs; /dev/shm is shared with the host. Create the
//     directory with mode 0700 if missing (one user per machine). Write the file atomically
//     (write "<file>.tmp", then rename). Content, one JSON object:
//        {"proto": 14, "kind": "client", "shm": "gmodcraft-client-0123456789abcdef",
//         "bytes": 200327168, "nonce": "0123456789abcdef"}
//     "nonce" is a hex STRING (a u64 does not fit a JSON double). No "pid" (v12 had one; dropped).
//  4. On clean shutdown: delete the discovery file, then shm_unlink.
//
// ---- opening a link (Minecraft) --------------------------------------------------------------
// Name from -Dgmodcraft.link / GMODCRAFT_LINK (client) or -Dgmodcraft.serverLink /
// GMODCRAFT_SERVER_LINK (server): a shm name (opened in /dev/shm) or an absolute path ("none"
// disables that link); else the discovery file. Minecraft opens O_RDWR without O_CREAT, checks the file is at least the mapping
// size, maps it, and accepts it when magic, version, linkKind and (when found through discovery)
// sessionNonce match. A changed sessionNonce means a new host instance: resend everything.
//
// ---- liveness ---------------------------------------------------------------------------------
// Each side stamps its heartbeat (CLOCK_MONOTONIC ns) at least once per frame/tick. The other side
// treats it as alive while the value keeps CHANGING: dead after kHeartbeatTimeoutMs of the
// observer's own clock without a change. Do not compare the two clocks against each other for
// liveness. (Java's System.nanoTime() is clock_gettime(CLOCK_MONOTONIC) on Linux. CLOCK_MONOTONIC
// is per time namespace: McState::tickNs is compared with the host's clock for interpolation and
// assumes both run in the same time namespace.)
//
// ---- "Minecraft is already running" -----------------------------------------------------------
// The Minecraft client holds an exclusive fcntl (POSIX) write lock on
// /dev/shm/gmodcraft/minecraft-client.lock (minecraft-client-<link name>.lock when the
// link name is overridden) for as long as it runs. The host probes it with fcntl(F_GETLK) or a
// non-blocking F_SETLK on a byte range [0, 1); if it is held, do not start another Minecraft.
#pragma once

#include <cstddef>
#include <cstdint>

namespace gmodcraft::proto
{
	inline constexpr std::uint32_t kMagic = 0x52434D47;  // "GMCR" (bytes G M C R)
	inline constexpr std::uint32_t kVersion = 36;        // continues SkyCraft's v11 (v16: Wiremod bridge; v17: hybrid mode; v18: weapon icons; v19: admin commands, demo builds; v20: STools; v21: per-map vertical offset; v22: noclip; v23: slot re-anchor; v24: world types, server rules; v25: map entity links; v26: block shapes; v27: MC entity proxies; v28: fire both ways; v29: physgun on MC entities; v30: moving platforms carry MC players; v31: MC entity body yaw; v32: held MC entity yaw / pitch; v33: props as MC items; v34: control centre: difficulty / mob rules, slot info, world list / world ops; v35: MC screen in the spawnmenu; v36: microblock boxes on the block ring, kBlkMicro)

	// 1 Minecraft block == 40 Source units (72-unit player hull ~ 1.8 blocks).
	inline constexpr double kUnitsPerBlock = 40.0;

	// A side whose heartbeat has not changed for this long (observer's clock) is gone. Generous:
	// a GMod map change can stall the host for seconds.
	inline constexpr std::uint32_t kHeartbeatTimeoutMs = 8000;

	enum LinkKind : std::uint32_t
	{
		kLinkClient = 1,
		kLinkServer = 2,
	};

	// =============================================================================================
	// Common header @0x0 of both links
	// =============================================================================================
	// FROZEN across protocol versions: magic, version, linkKind and the two heartbeats keep these
	// offsets forever, so any version can tell whether another version's segment is alive (the
	// host's stale-segment GC relies on it and never unlinks a live segment of any version).
	struct LinkHeader
	{
		std::uint32_t magic;            // kMagic, written last by the host
		std::uint32_t version;          // kVersion
		std::uint32_t linkKind;         // LinkKind
		std::uint32_t reserved0;
		std::uint64_t mappingBytes;     // total size of this mapping
		std::uint64_t sessionNonce;     // host instance id; same as the discovery file's "nonce"
		std::uint64_t hostHeartbeatNs;  // CLOCK_MONOTONIC ns, host, at its last frame/tick
		std::uint64_t mcHeartbeatNs;    // CLOCK_MONOTONIC ns, Minecraft, at its last frame/tick
		std::uint64_t mcNonce;          // random per Minecraft attach: host sees a Minecraft restart
		std::uint64_t reserved1;
	};
	static_assert(sizeof(LinkHeader) == 0x40);
	inline constexpr std::uint64_t kHMagic = 0x00;
	inline constexpr std::uint64_t kHVersion = 0x04;
	inline constexpr std::uint64_t kHLinkKind = 0x08;
	inline constexpr std::uint64_t kHMappingBytes = 0x10;
	inline constexpr std::uint64_t kHSessionNonce = 0x18;
	inline constexpr std::uint64_t kHHostHeartbeatNs = 0x20;
	inline constexpr std::uint64_t kHMcHeartbeatNs = 0x28;
	inline constexpr std::uint64_t kHMcNonce = 0x30;
	inline constexpr std::uint64_t kLinkHeaderBytes = 0x40;
	static_assert(offsetof(LinkHeader, magic) == kHMagic);
	static_assert(offsetof(LinkHeader, version) == kHVersion);
	static_assert(offsetof(LinkHeader, linkKind) == kHLinkKind);
	static_assert(offsetof(LinkHeader, mappingBytes) == kHMappingBytes);
	static_assert(offsetof(LinkHeader, sessionNonce) == kHSessionNonce);
	static_assert(offsetof(LinkHeader, hostHeartbeatNs) == kHHostHeartbeatNs);
	static_assert(offsetof(LinkHeader, mcHeartbeatNs) == kHMcHeartbeatNs);
	static_assert(offsetof(LinkHeader, mcNonce) == kHMcNonce);
	static_assert(sizeof(LinkHeader) == kLinkHeaderBytes);

	// =============================================================================================
	// Shared structs (used by one or both links)
	// =============================================================================================

	// ---- water grid (host -> MC, seqlock) ---------------------------------------------------
	// The host's water (map water volumes) around the player, for Minecraft to treat as its own
	// water: swimming, floating, drowning. Client link: one grid, around this player. Server link
	// (v14): one grid per player slot (kSvOffWaterGrids, see "player map"); the MC server treats the
	// union of every live grid as water.
	inline constexpr std::uint32_t kWaterGridSize = 16;
	inline constexpr float         kNoWater = -1.0e30f;

	struct WaterGrid
	{
		std::uint32_t seq;
		std::int32_t  originX, originZ;  // Minecraft block column of surface[0]
		std::uint32_t worldId;           // as in HostState / ServerState
		float         surface[kWaterGridSize * kWaterGridSize];  // [z * size + x]: MC y of the water surface; kNoWater: none
	};
	inline constexpr std::uint64_t kWgSeq = 0x00;
	inline constexpr std::uint64_t kWgOriginX = 0x04;
	inline constexpr std::uint64_t kWgOriginZ = 0x08;
	inline constexpr std::uint64_t kWgWorldId = 0x0C;
	inline constexpr std::uint64_t kWgSurface = 0x10;
	inline constexpr std::uint64_t kWaterGridBytes = 0x410;
	static_assert(offsetof(WaterGrid, seq) == kWgSeq);
	static_assert(offsetof(WaterGrid, originX) == kWgOriginX);
	static_assert(offsetof(WaterGrid, originZ) == kWgOriginZ);
	static_assert(offsetof(WaterGrid, worldId) == kWgWorldId);
	static_assert(offsetof(WaterGrid, surface) == kWgSurface);
	static_assert(sizeof(WaterGrid) == kWaterGridBytes);

	// ---- actor table (host -> MC, seqlock) --------------------------------------------------
	// Nearby GMod NPCs (and other hittable non-player entities), mirrored in Minecraft as invisible
	// hittable proxy entities. Server link: authoritative (the proxies live on the MC server).
	// Client link: the same actors as the GMod client renders them this frame (interpolated), so
	// the MC client's crosshair and reach line up with what is on screen.
	inline constexpr std::uint32_t kMaxActors = 256;

	enum ActorFlags : std::uint32_t
	{
		kActorHostile = 0x1,    // hostile to the player right now
		kActorDead = 0x2,
		kActorEssential = 0x4,  // cannot die (god mode, scripted)
		kActorInCombat = 0x8,
	};

	struct ActorRecord
	{
		std::uint32_t entId;       // GMod entity index (Entity:EntIndex()); stable while it exists
		std::uint32_t flags;       // ActorFlags
		float         x, y, z;     // feet, MC coords
		float         yaw;         // MC degrees
		float         width;       // blocks
		float         height;      // blocks
		float         healthFrac;  // 0..1
		std::uint16_t tier;        // generic difficulty tier (0 = normal); was Skyrim's level
		std::uint16_t pad;
		char          name[24];    // display name, UTF-8, NUL-terminated (truncated)
	};
	inline constexpr std::uint64_t kArEntId = 0x00;
	inline constexpr std::uint64_t kArFlags = 0x04;
	inline constexpr std::uint64_t kArX = 0x08;
	inline constexpr std::uint64_t kArY = 0x0C;
	inline constexpr std::uint64_t kArZ = 0x10;
	inline constexpr std::uint64_t kArYaw = 0x14;
	inline constexpr std::uint64_t kArWidth = 0x18;
	inline constexpr std::uint64_t kArHeight = 0x1C;
	inline constexpr std::uint64_t kArHealthFrac = 0x20;
	inline constexpr std::uint64_t kArTier = 0x24;
	inline constexpr std::uint64_t kArName = 0x28;
	inline constexpr std::uint64_t kActorNameBytes = 24;
	inline constexpr std::uint64_t kActorRecordBytes = 64;
	static_assert(offsetof(ActorRecord, entId) == kArEntId);
	static_assert(offsetof(ActorRecord, flags) == kArFlags);
	static_assert(offsetof(ActorRecord, x) == kArX);
	static_assert(offsetof(ActorRecord, y) == kArY);
	static_assert(offsetof(ActorRecord, z) == kArZ);
	static_assert(offsetof(ActorRecord, yaw) == kArYaw);
	static_assert(offsetof(ActorRecord, width) == kArWidth);
	static_assert(offsetof(ActorRecord, height) == kArHeight);
	static_assert(offsetof(ActorRecord, healthFrac) == kArHealthFrac);
	static_assert(offsetof(ActorRecord, tier) == kArTier);
	static_assert(offsetof(ActorRecord, name) == kArName);
	static_assert(sizeof(ActorRecord::name) == kActorNameBytes);
	static_assert(sizeof(ActorRecord) == kActorRecordBytes);

	struct ActorTable
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint8_t  pad[0x40 - 8];
		ActorRecord   actors[kMaxActors];
	};
	inline constexpr std::uint64_t kAtSeq = 0x00;
	inline constexpr std::uint64_t kAtCount = 0x04;
	inline constexpr std::uint64_t kAtRecords = 0x40;
	inline constexpr std::uint64_t kActorTableBytes = 0x4040;
	static_assert(offsetof(ActorTable, seq) == kAtSeq);
	static_assert(offsetof(ActorTable, count) == kAtCount);
	static_assert(offsetof(ActorTable, actors) == kAtRecords);
	static_assert(sizeof(ActorTable) == kActorTableBytes);
	static_assert(sizeof(ActorTable) == kAtRecords + kActorRecordBytes * kMaxActors);

	// ---- collision ring (host -> MC) --------------------------------------------------------
	// Byte ring. Every message starts 8-byte aligned with {u32 type, u32 payloadBytes}; the next
	// message starts at align8(8 + payloadBytes). A kColPad message means "skip to the start of the
	// ring". head/tail count bytes ever written/consumed (u64, never wrap); offset = count % data.
	// Same message set on both links: the client link feeds the MC client's world (the local
	// player's smooth triangle collider and client-side voxel shapes), the server link feeds the
	// MC server's world (voxel shapes for every entity, and the triangles that digging and
	// projectiles test against). Send kColTris before the kColRegion of the same region.
	inline constexpr std::uint64_t kCrHead = 0x00;  // u64 total bytes written (host)
	inline constexpr std::uint64_t kCrTail = 0x40;  // u64 total bytes consumed (MC)
	inline constexpr std::uint64_t kCrData = 0x80;

	enum ColType : std::uint32_t
	{
		kColPad = 0,
		kColClear = 1,   // payload: u32 epoch
		kColRegion = 2,  // payload: ColRegion + ColBlock[count]
		kColTris = 3,    // payload: ColRegion (count = triangles) + ColTri[count]; sent before kColRegion
		kColWeaponIcon = 4,  // v18, CLIENT link only (hybrid mode): payload WeaponIcon + w * h * 4 bytes
		                     // RGBA8 (straight alpha, top row first). The icon of the GMod weapon class
		                     // whose hash it carries, for gmodcraft:gmod_weapon items in Minecraft's
		                     // GUI. Sent once per class per link session (a later one replaces it).
		                     // Bounds: 1 <= w, h <= kWeaponIconMaxSide, format kIconRgba8, payloadBytes
		                     // exactly kWeaponIconHeaderBytes + w * h * 4; anything else is dropped.
	};

	// kColWeaponIcon payload header (v18).
	enum WeaponIconFormat : std::uint32_t
	{
		kIconRgba8 = 1,
	};
	struct WeaponIcon
	{
		std::uint32_t hash;    // FNV-1a 32 of the lowercased class (as McWeaponSet entries)
		std::uint16_t w, h;    // pixels, 1..kWeaponIconMaxSide
		std::uint32_t format;  // WeaponIconFormat
		std::uint32_t reserved;
	};
	inline constexpr std::uint64_t kWiHash = 0x00;
	inline constexpr std::uint64_t kWiW = 0x04;
	inline constexpr std::uint64_t kWiH = 0x06;
	inline constexpr std::uint64_t kWiFormat = 0x08;
	inline constexpr std::uint64_t kWeaponIconHeaderBytes = 16;
	inline constexpr std::uint32_t kWeaponIconMaxSide = 64;
	inline constexpr std::uint64_t kWeaponIconMaxBytes = 16400;  // header + 64 * 64 * 4
	static_assert(offsetof(WeaponIcon, hash) == kWiHash);
	static_assert(offsetof(WeaponIcon, w) == kWiW);
	static_assert(offsetof(WeaponIcon, h) == kWiH);
	static_assert(offsetof(WeaponIcon, format) == kWiFormat);
	static_assert(sizeof(WeaponIcon) == kWeaponIconHeaderBytes);
	static_assert(kWeaponIconMaxBytes == kWeaponIconHeaderBytes + 4ull * kWeaponIconMaxSide * kWeaponIconMaxSide);

	// Exact host collision triangle (MC space) for the smooth collider.
	enum ColTriFlags : std::uint32_t
	{
		kTriStairHelper = 0x1,  // an invisible stair ramp (playerclip): walkable, never a wall
		kTriDiggable = 0x2,     // ground, rock, trees...: can be dug into (bits 8-15: DigMaterial).
		                        // Its normal faces out of the solid side (winding is outward).
		kTriGhost = 0x4,        // a diggable triangle as it was before blocks were dug out of it:
		                        // not collision, only for telling what's inside the host's geometry
		kTriTerrain = 0x8,      // the land (displacements / a height field)
		kTriDynamic = 0x10,     // v15: part of a GMod entity's collision (props, doors, breakables,
		                        // glass: the dynamic layer), not the map's static world. v30: bits
		                        // 16-31 hold that entity's index (kTriEntityShift)
	};

	inline constexpr std::uint32_t kTriMaterialShift = 8;
	inline constexpr std::uint32_t kTriEntityShift = 16;  // v30: kTriDynamic triangles, the GMod entity index

	struct ColTri
	{
		float         v[9];
		std::uint32_t flags;
	};
	inline constexpr std::uint64_t kColTriBytes = 40;
	static_assert(sizeof(ColTri) == kColTriBytes);

	struct ColMsgHeader
	{
		std::uint32_t type;
		std::uint32_t payloadBytes;
	};
	inline constexpr std::uint64_t kColMsgHeaderBytes = 8;
	static_assert(sizeof(ColMsgHeader) == kColMsgHeaderBytes);

	// Replaces all host collision inside the inclusive block box [min, max]. Regions are 8-block
	// cubes aligned to multiples of 8 (kColRegionSize).
	inline constexpr std::uint32_t kColRegionSize = 8;

	struct ColRegion
	{
		std::int32_t  minX, minY, minZ;
		std::int32_t  maxX, maxY, maxZ;
		std::uint32_t epoch;
		std::uint32_t count;
	};
	inline constexpr std::uint64_t kColRegionHeaderBytes = 32;
	static_assert(sizeof(ColRegion) == kColRegionHeaderBytes);

	// One block's worth of host collision as an 8x8x8 occupancy mask.
	// bits[y] bit (z * 8 + x) is sub-voxel (x, y, z), each 1/8 block, in MC axes.
	struct ColBlock
	{
		std::int32_t  x, y, z;
		std::uint32_t pad;
		std::uint64_t bits[8];
	};
	inline constexpr std::uint64_t kColBlockBytes = 80;
	static_assert(sizeof(ColBlock) == kColBlockBytes);

	// What a piece of diggable host geometry is made of, as the Minecraft block it digs into
	// (ColTri flags bits 8-15). Chosen on the host from Source surface properties.
	enum DigMaterial : std::uint8_t
	{
		kDigNone = 0,  // not known: stone
		kDigGrass = 1,
		kDigDirt = 2,
		kDigStone = 3,
		kDigCobble = 4,
		kDigSnow = 5,
		kDigIce = 6,
		kDigSand = 7,
		kDigGravel = 8,
		kDigMud = 9,
		kDigOakLog = 10,
		kDigSpruceLog = 11,
		kDigBirchLog = 12,
		kDigPlanks = 13,
		kDigMetal = 14,
		kDigGlass = 15,
		kDigOrganic = 16,
		kDigCloth = 17,
		kDigBone = 18,
		kDigWeb = 19,
		kDigAsh = 20,
		kDigBedrock = 21,  // Minecraft only: a few blocks under the land
		kDigMaterialCount = 22,
	};

	// ---- map slots (D-003, docs/DESIGN.md section 3) ----------------------------------------
	// Every GMod map lives in the one mirror dimension, in its own slot on a grid of kSlotBlocks.
	// The MC server owns the persisted table (worldId/map name -> slot) and answers on the server
	// link (McServerState). Slot origin (ox, oz) = (slotX * kSlotBlocks, slotZ * kSlotBlocks), in
	// blocks. Every MC-side value in this protocol is plain MC coordinates (blocks) and already
	// includes the origin: the HOST adds (ox, oz) when it converts GMod -> MC and subtracts it for
	// MC -> GMod. Minecraft never adds or removes it.
	//
	// v21: a per-map VERTICAL offset too, oyUnits (int32, Source units, persisted with the slot):
	//     mc.y = (src.z + oyUnits) / 40          src.z = mc.y * 40 - oyUnits
	// (x/z as before: mc.x = src.x / 40 + ox, mc.z = -src.y / 40 + oz). Source-unit precise, so a map's
	// floor can sit exactly on a block boundary: a NEW slot gets oyUnits = kAnchorFloorY * 40 - floorZ
	// from the host's floor hint (ServerState anchor fields, kServerAnchorReady), clamped so the map's
	// z range fits the mirror dimension. Slots from before v21 keep oyUnits = 0. Like ox/oz, only the
	// HOST applies it; every MC-side value stays plain MC coordinates.
	inline constexpr std::int32_t kSlotBlocks = 2048;
	inline constexpr std::int32_t kAnchorFloorY = 64;      // the MC y a new map's floor lands on (T)
	inline constexpr std::uint32_t kAnchorWaitMs = 30000;  // a NEW map's slot waits this long for the hint

	// ---- link stats (both links, debug only) ------------------------------------------------
	// For the debug tab's Links / Player panels (docs/DESIGN.md section 12): each side writes its
	// own half (LinkStats::host or LinkStats::mc), so every field has exactly one writer and either
	// side's debug tab can show both. Plain aligned stores, NO seqlock: a reader may see fields from
	// two different updates. Never use these for control decisions.
	//
	// RingStats are indexed by ring (kClRing* on the client link, kSvRing* on the server link). A
	// side fills only the rings it touches, from its own role: the producer counts what it wrote,
	// the consumer what it read. Units: `fill`/`highWater` are in the ring's own unit (entries for
	// the input and event rings, bytes for the byte rings: collision, render, block);
	// `bytes`/`bytesPerSec` are always bytes (entry rings: entries * entry size).
	inline constexpr std::uint32_t kMaxStatRings = 4;

	enum ClientStatRing : std::uint32_t
	{
		kClRingInput = 0,      // host produces, MC client consumes
		kClRingCollision = 1,  // host produces, MC client consumes
		kClRingRender = 2,     // MC client produces, host consumes
		kClRingEvents = 3,     // MC client produces, host consumes (v14: join results)
	};

	enum ServerStatRing : std::uint32_t
	{
		kSvRingHostEvents = 0,  // host produces, MC server consumes
		kSvRingCollision = 1,   // host produces, MC server consumes
		kSvRingEvents = 2,      // MC server produces, host consumes
		kSvRingBlocks = 3,      // MC server produces, host consumes
	};
	// The collision ring has the same index on both links (shared consumer code relies on it).
	static_assert(std::uint32_t(kClRingCollision) == std::uint32_t(kSvRingCollision));

	struct RingStats
	{
		std::uint64_t messages;     // messages written (producer) / read (consumer) by this side
		std::uint64_t bytes;        // bytes of those messages, ring framing included
		std::uint64_t drops;        // producer: messages dropped because the ring was full;
		                            // consumer: messages lost (the producer lapped it) or malformed
		std::uint64_t fill;         // head - tail at this side's last access (ring units, see above)
		std::uint64_t highWater;    // the largest fill this side has seen
		std::uint64_t bytesPerSec;  // bytes over the last whole second (refreshed about once a second)
	};
	inline constexpr std::uint64_t kRsMessages = 0x00;
	inline constexpr std::uint64_t kRsBytes = 0x08;
	inline constexpr std::uint64_t kRsDrops = 0x10;
	inline constexpr std::uint64_t kRsFill = 0x18;
	inline constexpr std::uint64_t kRsHighWater = 0x20;
	inline constexpr std::uint64_t kRsBytesPerSec = 0x28;
	inline constexpr std::uint64_t kRingStatsBytes = 0x30;
	static_assert(offsetof(RingStats, messages) == kRsMessages);
	static_assert(offsetof(RingStats, bytes) == kRsBytes);
	static_assert(offsetof(RingStats, drops) == kRsDrops);
	static_assert(offsetof(RingStats, fill) == kRsFill);
	static_assert(offsetof(RingStats, highWater) == kRsHighWater);
	static_assert(offsetof(RingStats, bytesPerSec) == kRsBytesPerSec);
	static_assert(sizeof(RingStats) == kRingStatsBytes);

	enum LinkSideFlags : std::uint32_t
	{
		kSidePeerAlive = 0x1,  // this side currently sees the other side's heartbeat advancing
	};

	struct LinkSideStats
	{
		std::uint32_t protocolVersion;  // the kVersion this side was built with
		std::uint32_t attachCount;      // host: links created; MC: host sessions mapped (1 + remaps)
		std::uint64_t sessionNonce;     // the session this side is on (LinkHeader::sessionNonce)
		std::uint64_t attachNs;         // CLOCK_MONOTONIC ns of the last create (host) / (re)map (MC)
		std::uint64_t updateCount;      // bumps every time this side refreshes its half
		std::uint32_t peerDownCount;    // times this side declared the peer dead (kHeartbeatTimeoutMs)
		std::uint32_t flags;            // LinkSideFlags
		float         tickMs;           // MC: last client tick (client link) / average server tick
		                                // (server link), ms. Host: its own tick, ms
		float         frameMs;          // MC client: last frame time, ms (0 on the server link).
		                                // Host: its frame time, ms
		std::uint64_t overlayFrames;    // client link: MC = frames published, host = frames taken
		std::uint64_t reserved;
		RingStats     rings[kMaxStatRings];
	};
	inline constexpr std::uint64_t kSdProtocolVersion = 0x00;
	inline constexpr std::uint64_t kSdAttachCount = 0x04;
	inline constexpr std::uint64_t kSdSessionNonce = 0x08;
	inline constexpr std::uint64_t kSdAttachNs = 0x10;
	inline constexpr std::uint64_t kSdUpdateCount = 0x18;
	inline constexpr std::uint64_t kSdPeerDownCount = 0x20;
	inline constexpr std::uint64_t kSdFlags = 0x24;
	inline constexpr std::uint64_t kSdTickMs = 0x28;
	inline constexpr std::uint64_t kSdFrameMs = 0x2C;
	inline constexpr std::uint64_t kSdOverlayFrames = 0x30;
	inline constexpr std::uint64_t kSdRings = 0x40;
	inline constexpr std::uint64_t kLinkSideStatsBytes = 0x100;
	static_assert(offsetof(LinkSideStats, protocolVersion) == kSdProtocolVersion);
	static_assert(offsetof(LinkSideStats, attachCount) == kSdAttachCount);
	static_assert(offsetof(LinkSideStats, sessionNonce) == kSdSessionNonce);
	static_assert(offsetof(LinkSideStats, attachNs) == kSdAttachNs);
	static_assert(offsetof(LinkSideStats, updateCount) == kSdUpdateCount);
	static_assert(offsetof(LinkSideStats, peerDownCount) == kSdPeerDownCount);
	static_assert(offsetof(LinkSideStats, flags) == kSdFlags);
	static_assert(offsetof(LinkSideStats, tickMs) == kSdTickMs);
	static_assert(offsetof(LinkSideStats, frameMs) == kSdFrameMs);
	static_assert(offsetof(LinkSideStats, overlayFrames) == kSdOverlayFrames);
	static_assert(offsetof(LinkSideStats, rings) == kSdRings);
	static_assert(sizeof(LinkSideStats) == kLinkSideStatsBytes);

	struct LinkStats
	{
		LinkSideStats host;  // written only by the host
		LinkSideStats mc;    // written only by Minecraft
	};
	inline constexpr std::uint64_t kLsHost = 0x000;
	inline constexpr std::uint64_t kLsMc = 0x100;
	inline constexpr std::uint64_t kLinkStatsBytes = 0x200;
	static_assert(offsetof(LinkStats, host) == kLsHost);
	static_assert(offsetof(LinkStats, mc) == kLsMc);
	static_assert(sizeof(LinkStats) == kLinkStatsBytes);

	// Minecraft names are <= 16 ASCII chars; NUL-terminated, NUL-padded (HostPlayer, McPlayer,
	// McIdentity).
	inline constexpr std::uint64_t kMcNameBytes = 24;

	// =============================================================================================
	// CLIENT LINK (GMod client <-> MC client)
	// =============================================================================================
	inline constexpr std::uint64_t kClOffHeader = 0x0;
	inline constexpr std::uint64_t kClOffHostState = 0x100;
	inline constexpr std::uint64_t kClOffMcState = 0x200;
	inline constexpr std::uint64_t kClOffOverlayCtl = 0x300;
	inline constexpr std::uint64_t kClOffOverlaySlotHdr = 0x340;  // 3 x 0x40
	inline constexpr std::uint64_t kClOffWaterGrid = 0x400;
	inline constexpr std::uint64_t kClOffMcIdentity = 0x880;
	inline constexpr std::uint64_t kClOffLinkStats = 0xC00;
	inline constexpr std::uint64_t kClOffInputRing = 0x1000;
	inline constexpr std::uint64_t kClOffActorTable = 0x12000;
	inline constexpr std::uint64_t kClOffWorldEntities = 0x17000;
	inline constexpr std::uint64_t kClOffJoinInfo = 0x1B000;
	inline constexpr std::uint64_t kClOffJoinStatus = 0x1B200;
	inline constexpr std::uint64_t kClOffEventRing = 0x1C000;
	inline constexpr std::uint64_t kClOffMcScreen = 0x1F080;  // v35
	inline constexpr std::uint64_t kClOffCollisionRing = 0x20000;
	inline constexpr std::uint64_t kClCollisionRingBytes = 0x2000000;  // 32 MiB
	inline constexpr std::uint64_t kClOffOverlayPixels = 0x2020000;
	inline constexpr std::uint32_t kMaxOverlayW = 3840;
	inline constexpr std::uint32_t kMaxOverlayH = 2160;
	inline constexpr std::uint64_t kOverlaySlotBytes = 0x1FA4000;     // kMaxOverlayW * kMaxOverlayH * 4
	inline constexpr std::uint32_t kOverlaySlots = 3;
	inline constexpr std::uint64_t kClOffRenderRing = 0x7F0C000;
	inline constexpr std::uint64_t kRenderRingBytes = 0x4000000;      // 64 MiB
	inline constexpr std::uint64_t kClMappingBytes = 0xBF0C000;       // ~191 MiB (sparse until touched)

	// ---- host -> MC state @kClOffHostState (seqlock) ---------------------------------------
	enum HostFlags : std::uint32_t
	{
		kHostInGame = 0x1,    // a map is loaded and the local player exists
		kHostMenuOpen = 0x2,  // a GMod menu / VGUI owns input; MC should drop held keys
		kHostLoading = 0x4,   // loading screen / map change in progress
		kHostSlotKnown = 0x8, // slotOriginX/Y/Z hold the current map's slot origin (from the GMod
		                      // server, which got it from the MC server's McServerState)
	};

	// v17 hybrid mode (GMod weapons as Minecraft items): HostState::hybridFlags.
	enum HostHybridFlags : std::uint32_t
	{
		kHostHybrid = 0x1,        // the GMod server runs hybrid mode (gmodcraft_hybrid 1) for this player
		kHostWeaponActive = 0x2,  // the GMod weapon McState::heldWeapon names is the active one: clip1,
		                          // maxClip1, ammo1, ammo2 are its ammo
	};

	// v30 (P6i carry): HostState::carryFlags.
	enum HostCarryFlags : std::uint32_t
	{
		kCarryOnTop = 0x1,  // the feet are on the platform this frame (carryTopY is valid): Minecraft pins
		                    // them there; without it (the hysteresis after the feet left it, or a jump
		                    // over it) the platform's motion is still applied, nothing is pinned
	};

	// v13: no teleportSeq any more. Teleports are server-side (server link kHostEvTeleport, acked
	// with kEvTeleportAck); the MC client follows the MC server's position packet like any client.
	struct HostState
	{
		std::uint32_t seq;
		std::uint32_t flags;           // HostFlags
		std::uint32_t worldId;         // hash of the map name (FNV-1a 32 of game.GetMap())
		std::uint32_t collisionEpoch;  // bumps on world change; MC drops all collision data
		double        posX, posY, posZ;  // the GMod puppet's feet, MC coords (informational: the
		                                 // debug tab's position error; MC never moves to it)
		float         yaw, pitch;        // authoritative look (MC degrees)
		std::int32_t  slotOriginX;       // the map's slot origin (blocks); valid with kHostSlotKnown
		std::int32_t  slotOriginZ;
		std::uint32_t viewportW, viewportH;
		float         gameHour;          // 0..24, for MC's sky/daylight
		std::int32_t  slotOriginY;       // v21: the map's vertical offset in SOURCE UNITS (oyUnits, see "map
		                                 // slots"); valid with kHostSlotKnown
		// v17 hybrid mode (appended; H2 fills them): the active GMod weapon's ammo, -1 = none.
		std::uint32_t hybridFlags;       // HostHybridFlags
		std::int32_t  clip1;             // rounds in the primary clip
		std::int32_t  maxClip1;          // primary clip size
		std::int32_t  ammo1;             // primary reserve ammo
		std::int32_t  ammo2;             // secondary reserve ammo
		// v30 (P6i carry, appended; was pad5C): the moving GMod entity (door, train, prop) the local MC
		// player stands on, from the GMod client's interpolated view of it; 0 = none. Minecraft moves
		// the player with it every tick: by the change of (carryPivot, carryYaw) between two samples
		// (carrySeq changed), else by the rates; and pins the feet to carryTopY with kCarryOnTop.
		std::uint32_t carryEnt;          // GMod entity index (ColTri flags bits 16-31 of its triangles)
		std::uint32_t carrySeq;          // bumps with every new sample (every GMod frame while carried)
		std::uint32_t carryFlags;        // HostCarryFlags
		float         carryVelX, carryVelY, carryVelZ;  // the pivot's velocity, MC blocks per MC tick
		float         carryYawRate;      // MC yaw (degrees grow clockwise seen from above), radians per MC tick
		double        carryPivotX, carryPivotY, carryPivotZ;  // the entity's origin, MC coords
		double        carryTopY;         // MC y of the platform's top under the feet (kCarryOnTop)
		float         carryYaw;          // the entity's yaw as an MC yaw, radians (only differences count)
		std::uint32_t pad9C;
	};
	inline constexpr std::uint64_t kHsSeq = 0x00;
	inline constexpr std::uint64_t kHsFlags = 0x04;
	inline constexpr std::uint64_t kHsWorldId = 0x08;
	inline constexpr std::uint64_t kHsCollisionEpoch = 0x0C;
	inline constexpr std::uint64_t kHsPosX = 0x10;
	inline constexpr std::uint64_t kHsPosY = 0x18;
	inline constexpr std::uint64_t kHsPosZ = 0x20;
	inline constexpr std::uint64_t kHsYaw = 0x28;
	inline constexpr std::uint64_t kHsPitch = 0x2C;
	inline constexpr std::uint64_t kHsSlotOriginX = 0x30;
	inline constexpr std::uint64_t kHsSlotOriginZ = 0x34;
	inline constexpr std::uint64_t kHsViewportW = 0x38;
	inline constexpr std::uint64_t kHsViewportH = 0x3C;
	inline constexpr std::uint64_t kHsGameHour = 0x40;
	inline constexpr std::uint64_t kHsSlotOriginY = 0x44;
	inline constexpr std::uint64_t kHsHybridFlags = 0x48;
	inline constexpr std::uint64_t kHsClip1 = 0x4C;
	inline constexpr std::uint64_t kHsMaxClip1 = 0x50;
	inline constexpr std::uint64_t kHsAmmo1 = 0x54;
	inline constexpr std::uint64_t kHsAmmo2 = 0x58;
	inline constexpr std::uint64_t kHsCarryEnt = 0x5C;
	inline constexpr std::uint64_t kHsCarrySeq = 0x60;
	inline constexpr std::uint64_t kHsCarryFlags = 0x64;
	inline constexpr std::uint64_t kHsCarryVelX = 0x68;
	inline constexpr std::uint64_t kHsCarryVelY = 0x6C;
	inline constexpr std::uint64_t kHsCarryVelZ = 0x70;
	inline constexpr std::uint64_t kHsCarryYawRate = 0x74;
	inline constexpr std::uint64_t kHsCarryPivotX = 0x78;
	inline constexpr std::uint64_t kHsCarryPivotY = 0x80;
	inline constexpr std::uint64_t kHsCarryPivotZ = 0x88;
	inline constexpr std::uint64_t kHsCarryTopY = 0x90;
	inline constexpr std::uint64_t kHsCarryYaw = 0x98;
	inline constexpr std::uint64_t kHostStateBytes = 0xA0;
	static_assert(offsetof(HostState, seq) == kHsSeq);
	static_assert(offsetof(HostState, flags) == kHsFlags);
	static_assert(offsetof(HostState, worldId) == kHsWorldId);
	static_assert(offsetof(HostState, collisionEpoch) == kHsCollisionEpoch);
	static_assert(offsetof(HostState, posX) == kHsPosX);
	static_assert(offsetof(HostState, posY) == kHsPosY);
	static_assert(offsetof(HostState, posZ) == kHsPosZ);
	static_assert(offsetof(HostState, yaw) == kHsYaw);
	static_assert(offsetof(HostState, pitch) == kHsPitch);
	static_assert(offsetof(HostState, slotOriginX) == kHsSlotOriginX);
	static_assert(offsetof(HostState, slotOriginZ) == kHsSlotOriginZ);
	static_assert(offsetof(HostState, viewportW) == kHsViewportW);
	static_assert(offsetof(HostState, viewportH) == kHsViewportH);
	static_assert(offsetof(HostState, gameHour) == kHsGameHour);
	static_assert(offsetof(HostState, slotOriginY) == kHsSlotOriginY);
	static_assert(offsetof(HostState, hybridFlags) == kHsHybridFlags);
	static_assert(offsetof(HostState, clip1) == kHsClip1);
	static_assert(offsetof(HostState, maxClip1) == kHsMaxClip1);
	static_assert(offsetof(HostState, ammo1) == kHsAmmo1);
	static_assert(offsetof(HostState, ammo2) == kHsAmmo2);
	static_assert(offsetof(HostState, carryEnt) == kHsCarryEnt);
	static_assert(offsetof(HostState, carrySeq) == kHsCarrySeq);
	static_assert(offsetof(HostState, carryFlags) == kHsCarryFlags);
	static_assert(offsetof(HostState, carryVelX) == kHsCarryVelX);
	static_assert(offsetof(HostState, carryVelY) == kHsCarryVelY);
	static_assert(offsetof(HostState, carryVelZ) == kHsCarryVelZ);
	static_assert(offsetof(HostState, carryYawRate) == kHsCarryYawRate);
	static_assert(offsetof(HostState, carryPivotX) == kHsCarryPivotX);
	static_assert(offsetof(HostState, carryPivotY) == kHsCarryPivotY);
	static_assert(offsetof(HostState, carryPivotZ) == kHsCarryPivotZ);
	static_assert(offsetof(HostState, carryTopY) == kHsCarryTopY);
	static_assert(offsetof(HostState, carryYaw) == kHsCarryYaw);
	static_assert(sizeof(HostState) == kHostStateBytes);

	// ---- MC -> host state @kClOffMcState (seqlock) -----------------------------------------
	enum McFlags : std::uint32_t
	{
		kMcInWorld = 0x1,
		kMcScreenOpen = 0x2,  // an MC GUI screen (inventory, chat, ...) is open
		kMcOnGround = 0x4,
		kMcSneaking = 0x8,
		kMcSprinting = 0x10,
		kMcDead = 0x20,
		kMcSwimming = 0x40,
		kMcFlying = 0x80,
		kMcHeld = 0x100,  // held in place until the host's collision around it has arrived (after
		                  // joining, a teleport or a respawn)
	};

	struct McState
	{
		std::uint32_t seq;
		std::uint32_t flags;          // McFlags
		double        x, y, z;        // interpolated feet position (MC coords)
		float         yaw, pitch;     // MC rotation (degrees)
		float         eyeHeight;      // blocks above feet
		float         sensitivity;    // MC mouse sensitivity option (0..1)
		std::uint32_t teleportCount;  // bumps each time the MC server moved this player (teleport,
		                              // respawn): don't interpolate or speed-check across a change
		std::uint32_t guiScale;
		std::uint64_t frameCounter;
		float         fovDeg;         // effective vertical FOV (includes sprint / fluid modifiers)
		float         bobPhase;       // MC walk-bob phase (interpolated walk distance); 0 if bobbing is off
		float         bobAmount;      // MC walk-bob amplitude
		std::uint32_t heldWeapon;     // v17 (was pad4C): FNV-1a 32 of the lowercased class of the
		                              // gmodcraft:gmod_weapon in the main hand; 0 = any other item / empty
		double        eyeX, eyeY, eyeZ;  // MC camera position (interpolated, includes sneak eye lerp)

		// Raw 20 Hz physics ticks, so the host can interpolate on its own frame clock exactly like
		// Minecraft's renderer does with partial ticks.
		std::uint64_t tickNs;              // CLOCK_MONOTONIC ns at the (remainder-corrected) tick
		double       prevX, prevY, prevZ;  // feet at the previous tick
		double       curX, curY, curZ;     // feet at the latest tick
		float        tickEyeO, tickEye;      // Camera's smoothed eye height, previous/latest tick
		float        walkDistO, walkDist;    // walk-bob phase inputs
		float        bobO, bob;              // walk-bob amplitude inputs
		float        tickMs;                 // milliseconds per tick (50 unless /tick rate changed)
		std::uint32_t tickPad;

		// Minecraft's camera (F5): 0 first person, 1 third person behind, 2 third person in front.
		// cameraDistance is how far Minecraft's camera sits from the eye, after its zoom collision.
		std::uint32_t cameraMode;
		float         cameraDistance;

		// v17 (appended): the selected hotbar slot (0-8), i.e. the main hand's inventory slot.
		std::uint32_t heldSlot;
		// v30 (P6i carry; was padCC): the GMod entity Minecraft moved the player with this tick
		// (HostState::carryEnt as applied), 0 = none. The GMod client turns the look with it and tells
		// the GMod server, whose inside-solid check then leaves that entity out.
		std::uint32_t carryEnt;
	};
	inline constexpr std::uint64_t kMsSeq = 0x00;
	inline constexpr std::uint64_t kMsFlags = 0x04;
	inline constexpr std::uint64_t kMsX = 0x08;
	inline constexpr std::uint64_t kMsY = 0x10;
	inline constexpr std::uint64_t kMsZ = 0x18;
	inline constexpr std::uint64_t kMsYaw = 0x20;
	inline constexpr std::uint64_t kMsPitch = 0x24;
	inline constexpr std::uint64_t kMsEyeHeight = 0x28;
	inline constexpr std::uint64_t kMsSensitivity = 0x2C;
	inline constexpr std::uint64_t kMsTeleportCount = 0x30;
	inline constexpr std::uint64_t kMsGuiScale = 0x34;
	inline constexpr std::uint64_t kMsFrameCounter = 0x38;
	inline constexpr std::uint64_t kMsFov = 0x40;
	inline constexpr std::uint64_t kMsBobPhase = 0x44;
	inline constexpr std::uint64_t kMsBobAmount = 0x48;
	inline constexpr std::uint64_t kMsHeldWeapon = 0x4C;
	inline constexpr std::uint64_t kMsEyeX = 0x50;
	inline constexpr std::uint64_t kMsEyeY = 0x58;
	inline constexpr std::uint64_t kMsEyeZ = 0x60;
	inline constexpr std::uint64_t kMsTickNs = 0x68;
	inline constexpr std::uint64_t kMsPrevX = 0x70;
	inline constexpr std::uint64_t kMsPrevY = 0x78;
	inline constexpr std::uint64_t kMsPrevZ = 0x80;
	inline constexpr std::uint64_t kMsCurX = 0x88;
	inline constexpr std::uint64_t kMsCurY = 0x90;
	inline constexpr std::uint64_t kMsCurZ = 0x98;
	inline constexpr std::uint64_t kMsEyeHeightO = 0xA0;
	inline constexpr std::uint64_t kMsEyeHeightT = 0xA4;
	inline constexpr std::uint64_t kMsWalkO = 0xA8;
	inline constexpr std::uint64_t kMsWalk = 0xAC;
	inline constexpr std::uint64_t kMsBobO = 0xB0;
	inline constexpr std::uint64_t kMsBob = 0xB4;
	inline constexpr std::uint64_t kMsTickMs = 0xB8;
	inline constexpr std::uint64_t kMsCameraMode = 0xC0;
	inline constexpr std::uint64_t kMsCameraDistance = 0xC4;
	inline constexpr std::uint64_t kMsHeldSlot = 0xC8;
	inline constexpr std::uint64_t kMsCarryEnt = 0xCC;
	inline constexpr std::uint64_t kMcStateBytes = 0xD0;
	static_assert(offsetof(McState, seq) == kMsSeq);
	static_assert(offsetof(McState, flags) == kMsFlags);
	static_assert(offsetof(McState, x) == kMsX);
	static_assert(offsetof(McState, y) == kMsY);
	static_assert(offsetof(McState, z) == kMsZ);
	static_assert(offsetof(McState, yaw) == kMsYaw);
	static_assert(offsetof(McState, pitch) == kMsPitch);
	static_assert(offsetof(McState, eyeHeight) == kMsEyeHeight);
	static_assert(offsetof(McState, sensitivity) == kMsSensitivity);
	static_assert(offsetof(McState, teleportCount) == kMsTeleportCount);
	static_assert(offsetof(McState, guiScale) == kMsGuiScale);
	static_assert(offsetof(McState, frameCounter) == kMsFrameCounter);
	static_assert(offsetof(McState, fovDeg) == kMsFov);
	static_assert(offsetof(McState, bobPhase) == kMsBobPhase);
	static_assert(offsetof(McState, bobAmount) == kMsBobAmount);
	static_assert(offsetof(McState, heldWeapon) == kMsHeldWeapon);
	static_assert(offsetof(McState, eyeX) == kMsEyeX);
	static_assert(offsetof(McState, eyeY) == kMsEyeY);
	static_assert(offsetof(McState, eyeZ) == kMsEyeZ);
	static_assert(offsetof(McState, tickNs) == kMsTickNs);
	static_assert(offsetof(McState, prevX) == kMsPrevX);
	static_assert(offsetof(McState, prevY) == kMsPrevY);
	static_assert(offsetof(McState, prevZ) == kMsPrevZ);
	static_assert(offsetof(McState, curX) == kMsCurX);
	static_assert(offsetof(McState, curY) == kMsCurY);
	static_assert(offsetof(McState, curZ) == kMsCurZ);
	static_assert(offsetof(McState, tickEyeO) == kMsEyeHeightO);
	static_assert(offsetof(McState, tickEye) == kMsEyeHeightT);
	static_assert(offsetof(McState, walkDistO) == kMsWalkO);
	static_assert(offsetof(McState, walkDist) == kMsWalk);
	static_assert(offsetof(McState, bobO) == kMsBobO);
	static_assert(offsetof(McState, bob) == kMsBob);
	static_assert(offsetof(McState, tickMs) == kMsTickMs);
	static_assert(offsetof(McState, cameraMode) == kMsCameraMode);
	static_assert(offsetof(McState, cameraDistance) == kMsCameraDistance);
	static_assert(offsetof(McState, heldSlot) == kMsHeldSlot);
	static_assert(offsetof(McState, carryEnt) == kMsCarryEnt);
	static_assert(sizeof(McState) == kMcStateBytes);

	// ---- overlay triple buffer @kClOffOverlayCtl -------------------------------------------
	// state: bits 0-1 = index of the "middle" slot, bit 2 = middle holds an unread frame.
	// Writer (MC) renders into its private back slot (starts at 1), then xchg(state, back | kDirty)
	// and keeps the returned index as its new back slot. Reader (host; front starts at 2) does
	// xchg(state, front) only when the dirty bit is set and keeps the returned index as its front.
	// Pixels: 8 bits per channel, premultiplied alpha; OverlaySlotHdr::flags says how they're laid
	// out and encoded. A host must honour every bit (and do its own swizzle / encode when a bit is
	// absent); Minecraft sets kOvBGRA | kOvSrgbEncoded whenever it can.
	inline constexpr std::uint32_t kOverlayDirty = 0x4;

	enum OverlayFlags : std::uint32_t
	{
		kOvBottomUp = 0x1,     // rows are bottom-up (else top row first)
		kOvBGRA = 0x2,         // bytes B, G, R, A in memory (else R, G, B, A). ToGL stores BGRA8888
		kOvSrgbEncoded = 0x4,  // every colour channel c (0..255, premultiplied, as Minecraft rendered
		                       // it) went through the sRGB encode curve: out = round(255 * f(c / 255)),
		                       // f(x) = x <= 0.0031308 ? 12.92 x : 1.055 x^(1/2.4) - 0.055. Alpha is
		                       // untouched. Draw it with $linearwrite 1: ToGL's sRGB decode on read
		                       // then gives back Minecraft's own gamma-space values to blend.
	};

	struct OverlayCtl
	{
		std::uint32_t state;
		std::uint32_t pad;
		std::uint64_t framesPublished;
	};
	inline constexpr std::uint64_t kOcState = 0x00;
	inline constexpr std::uint64_t kOcFramesPublished = 0x08;
	static_assert(offsetof(OverlayCtl, state) == kOcState);
	static_assert(offsetof(OverlayCtl, framesPublished) == kOcFramesPublished);
	static_assert(sizeof(OverlayCtl) == 0x10);

	struct OverlaySlotHdr
	{
		std::uint32_t width;
		std::uint32_t height;
		std::uint32_t flags;  // OverlayFlags
		std::uint32_t pad;
		std::uint64_t frameId;
		std::uint8_t  reserved[0x40 - 0x18];
	};
	inline constexpr std::uint64_t kShWidth = 0x00;
	inline constexpr std::uint64_t kShHeight = 0x04;
	inline constexpr std::uint64_t kShFlags = 0x08;
	inline constexpr std::uint64_t kShFrameId = 0x10;
	inline constexpr std::uint64_t kSlotHdrBytes = 0x40;
	static_assert(offsetof(OverlaySlotHdr, width) == kShWidth);
	static_assert(offsetof(OverlaySlotHdr, height) == kShHeight);
	static_assert(offsetof(OverlaySlotHdr, flags) == kShFlags);
	static_assert(offsetof(OverlaySlotHdr, frameId) == kShFrameId);
	static_assert(sizeof(OverlaySlotHdr) == kSlotHdrBytes);

	// ---- MC identity @kClOffMcIdentity (MC client -> host, seqlock) ------------------------
	// Who this Minecraft is. Written by the MC client whenever it changes (at least when it starts
	// and when it joins or leaves a world). The GMod client forwards uuid + name to the GMod
	// server (GMod net), which lists them in HostPlayers on the server link; that is how the MC
	// server ties a Minecraft player to a GMod player (see "player map" below). seq 0 = not written.
	enum McIdentityFlags : std::uint32_t
	{
		kIdInWorld = 0x1,           // in a world (its own integrated server or a remote server)
		kIdOnlineAccount = 0x2,     // a signed-in (Microsoft) account: uuid is the real profile id.
		                            // Without it this is an offline / dev profile. Minecraft decides it
		                            // by the UUID's version: Mojang profile ids are random (version 4),
		                            // offline / dev ones name-based (version 3); launchers don't
		                            // reliably pass an xuid or client id.
		kIdIntegratedServer = 0x4,  // the world is its own integrated server (singleplayer / host)
	};

	struct McIdentity
	{
		std::uint32_t seq;
		std::uint32_t flags;     // McIdentityFlags
		std::uint8_t  uuid[16];  // the Minecraft profile UUID, big-endian (most significant byte first)
		char          name[24];  // the Minecraft profile name, UTF-8, NUL-terminated
	};
	inline constexpr std::uint64_t kIdSeq = 0x00;
	inline constexpr std::uint64_t kIdFlags = 0x04;
	inline constexpr std::uint64_t kIdUuid = 0x08;
	inline constexpr std::uint64_t kIdName = 0x18;
	inline constexpr std::uint64_t kMcIdentityBytes = 0x30;
	static_assert(offsetof(McIdentity, seq) == kIdSeq);
	static_assert(offsetof(McIdentity, flags) == kIdFlags);
	static_assert(offsetof(McIdentity, uuid) == kIdUuid);
	static_assert(offsetof(McIdentity, name) == kIdName);
	static_assert(sizeof(McIdentity::name) == kMcNameBytes);
	static_assert(sizeof(McIdentity) == kMcIdentityBytes);

	// ---- link stats @kClOffLinkStats: LinkStats (see "link stats" above), rings kClRing* ----

	// ---- input ring @kClOffInputRing (host produces, MC consumes) --------------------------
	inline constexpr std::uint32_t kInputRingEntries = 4096;  // power of two
	inline constexpr std::uint64_t kIrHead = 0x00;  // u64 events ever written (host)
	inline constexpr std::uint64_t kIrTail = 0x40;  // u64 events ever consumed (MC)
	inline constexpr std::uint64_t kIrData = 0x80;

	enum InputType : std::uint16_t
	{
		kInKey = 1,          // code = SDL scancode, a = 1 press / 0 release
		kInMouseButton = 2,  // code = SDL button (1 L, 2 M, 3 R, 4 X1, 5 X2), a = 1 press / 0 release
		kInScroll = 3,       // a = wheel notches * 120 (positive = up)
		kInCursor = 4,       // a, b = absolute cursor position in overlay pixels
		kInText = 5,         // a = unicode code point
		kInReleaseAll = 6,   // release every held key/button (input focus left MC)
		                     // 7 was SkyCraft's kInHurt: hurt now arrives on the server link
		kInOpenMenu = 8,     // open Minecraft's pause/options menu
	};

	struct InputEvent
	{
		std::uint16_t type;
		std::uint16_t code;
		std::int32_t  a;
		std::int32_t  b;
		std::int32_t  c;
	};
	inline constexpr std::uint64_t kInputEventBytes = 16;
	static_assert(sizeof(InputEvent) == kInputEventBytes);
	inline constexpr std::uint64_t kInputRingBytes = 0x10080;
	static_assert(kInputRingBytes == kIrData + kInputEventBytes * kInputRingEntries);

	// ---- world entities @kClOffWorldEntities (MC -> host, seqlock) -------------------------
	// Minecraft things the host draws itself each frame (arrows, dropped items, block cracks) +
	// the block outline.
	inline constexpr std::uint32_t kMaxWorldEntities = 160;

	enum WorldEntityKind : std::uint32_t
	{
		kWeArrow = 1,    // uv[0]: the arrow's item icon
		kWeItem = 2,     // dropped/thrown item: a flat sprite (uv[0]) turning about the vertical
		kWeTrident = 3,  // uv[0]: the trident's item icon
		kWeBlock = 4,    // dropped block item: a spinning cube of side `scale`, uv[0..2] = side, top, bottom
		kWeCrack = 5,    // block-breaking cracks over the box at (x, y, z) of size ext, uv[0] = crack stage
		kWeShadow = 6,   // a player's or mob's feet at (x, y, z), `scale` wide: its soft contact shadow
	};

	struct WorldEntity
	{
		std::uint32_t kind;        // WorldEntityKind
		std::uint32_t id;          // MC entity id (stable while it exists)
		float         x, y, z;     // MC coords (interpolated at MC's render time)
		float         yaw, pitch;  // MC degrees
		float         scale;
		float         ext[3];      // kWeCrack: box size
		float         uv[3][4];    // atlas rects {u0, v0, u1, v1}
		std::uint32_t tint;        // RGBA8 multiplier for the top face (grass, leaves); 0 = none
	};
	inline constexpr std::uint64_t kWorldEntityBytes = 96;
	static_assert(offsetof(WorldEntity, ext) == 32);
	static_assert(offsetof(WorldEntity, uv) == 44);
	static_assert(offsetof(WorldEntity, tint) == 92);
	static_assert(sizeof(WorldEntity) == kWorldEntityBytes);

	struct WorldEntities
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint32_t hasSelection;           // draw an outline around the targeted block
		float         selMin[3], selMax[3];   // MC coords
		std::uint8_t  pad[0x40 - 36];
		WorldEntity   entities[kMaxWorldEntities];
	};
	inline constexpr std::uint64_t kWtSeq = 0x00;
	inline constexpr std::uint64_t kWtCount = 0x04;
	inline constexpr std::uint64_t kWtHasSelection = 0x08;
	inline constexpr std::uint64_t kWtSelMin = 0x0C;
	inline constexpr std::uint64_t kWtSelMax = 0x18;
	inline constexpr std::uint64_t kWtRecords = 0x40;
	inline constexpr std::uint64_t kWorldEntitiesBytes = 0x3C40;
	static_assert(offsetof(WorldEntities, seq) == kWtSeq);
	static_assert(offsetof(WorldEntities, count) == kWtCount);
	static_assert(offsetof(WorldEntities, hasSelection) == kWtHasSelection);
	static_assert(offsetof(WorldEntities, selMin) == kWtSelMin);
	static_assert(offsetof(WorldEntities, selMax) == kWtSelMax);
	static_assert(offsetof(WorldEntities, entities) == kWtRecords);
	static_assert(sizeof(WorldEntities) == kWorldEntitiesBytes);
	static_assert(sizeof(WorldEntities) == kWtRecords + kWorldEntityBytes * kMaxWorldEntities);

	// ---- join info @kClOffJoinInfo (host -> MC, seqlock) -----------------------------------
	// Multiplayer pairing (v14): which Minecraft server this player's Minecraft should play on, as
	// the GMod server announced it, and a token proving the pairing. Minecraft follows joinId:
	//  * joinId changes and serverAddress is set: leave the current world and connect there;
	//  * joinId changes and serverAddress is empty: go back to its own world (like /leave);
	//  * the same joinId again (a rewrite, a new link session): nothing happens. A join that failed
	//    is not retried until the host writes a new joinId.
	// seq 0 / joinId 0 = nothing announced (play in the integrated server). Every outcome comes back
	// as kEvJoinResult on this link's event ring (requestId = joinId) and in JoinStatus.
	// After connecting, Minecraft hands joinToken to the MC server (a mod packet), which keeps it per
	// player; it is never logged in full (logs show a short hash).
	inline constexpr std::uint64_t kServerAddressBytes = 256;
	inline constexpr std::uint64_t kJoinTokenBytes = 128;

	struct JoinInfo
	{
		std::uint32_t seq;
		std::uint32_t flags;               // reserved, 0
		char          serverAddress[256];  // "host:port" (or "host": port 25565), UTF-8, NUL-terminated
		char          joinToken[128];      // opaque, UTF-8, NUL-terminated
		std::uint32_t joinId;              // v14: bump (non-zero) for every new instruction
		std::uint32_t pad;
	};
	inline constexpr std::uint64_t kJiSeq = 0x00;
	inline constexpr std::uint64_t kJiFlags = 0x04;
	inline constexpr std::uint64_t kJiServerAddress = 0x08;
	inline constexpr std::uint64_t kJiJoinToken = 0x108;
	inline constexpr std::uint64_t kJiJoinId = 0x188;
	inline constexpr std::uint64_t kJoinInfoBytes = 0x190;
	static_assert(offsetof(JoinInfo, seq) == kJiSeq);
	static_assert(offsetof(JoinInfo, flags) == kJiFlags);
	static_assert(offsetof(JoinInfo, serverAddress) == kJiServerAddress);
	static_assert(offsetof(JoinInfo, joinToken) == kJiJoinToken);
	static_assert(offsetof(JoinInfo, joinId) == kJiJoinId);
	static_assert(sizeof(JoinInfo::serverAddress) == kServerAddressBytes);
	static_assert(sizeof(JoinInfo::joinToken) == kJoinTokenBytes);
	static_assert(sizeof(JoinInfo) == kJoinInfoBytes);

	// ---- join status @kClOffJoinStatus (MC -> host, seqlock) -------------------------------
	// Where this Minecraft plays now, and why the last join ended. Rewritten on every change (and on
	// a new link session). The same outcomes also arrive as kEvJoinResult events.
	enum JoinState : std::uint32_t
	{
		kJoinStateOwnWorld = 0,    // in (or opening) its own world
		kJoinStateConnecting = 1,  // connecting to serverAddress
		kJoinStateJoined = 2,      // playing on serverAddress
	};

	// kEvJoinResult::result and JoinStatus::result.
	enum JoinResult : std::uint32_t
	{
		kJoinOk = 0,          // connected: playing on the server
		kJoinFailed = 1,      // couldn't connect / refused while logging in (reason says why); back home
		kJoinLeft = 2,        // back in its own world because the host (empty address) or the player
		                      // (/leave) asked
		kJoinLost = 3,        // was playing there; the connection dropped or it was kicked; back home
		kJoinBadAddress = 4,  // serverAddress doesn't parse; nothing changed
	};

	inline constexpr std::uint64_t kJoinReasonBytes = 256;

	struct JoinStatus
	{
		std::uint32_t seq;
		std::uint32_t state;               // JoinState
		std::uint32_t joinId;              // the JoinInfo::joinId this follows (0: none / the player's /join)
		std::uint32_t result;              // JoinResult of the last finished attempt
		char          serverAddress[256];  // the server it plays on / connects to; empty in its own world
		char          reason[256];         // the last failure or disconnect message (UTF-8), else empty
	};
	inline constexpr std::uint64_t kJsSeq = 0x00;
	inline constexpr std::uint64_t kJsState = 0x04;
	inline constexpr std::uint64_t kJsJoinId = 0x08;
	inline constexpr std::uint64_t kJsResult = 0x0C;
	inline constexpr std::uint64_t kJsServerAddress = 0x10;
	inline constexpr std::uint64_t kJsReason = 0x110;
	inline constexpr std::uint64_t kJoinStatusBytes = 0x210;
	static_assert(offsetof(JoinStatus, seq) == kJsSeq);
	static_assert(offsetof(JoinStatus, state) == kJsState);
	static_assert(offsetof(JoinStatus, joinId) == kJsJoinId);
	static_assert(offsetof(JoinStatus, result) == kJsResult);
	static_assert(offsetof(JoinStatus, serverAddress) == kJsServerAddress);
	static_assert(offsetof(JoinStatus, reason) == kJsReason);
	static_assert(sizeof(JoinStatus::serverAddress) == kServerAddressBytes);
	static_assert(sizeof(JoinStatus::reason) == kJoinReasonBytes);
	static_assert(sizeof(JoinStatus) == kJoinStatusBytes);

	// ---- MC -> host: the open screen @kClOffMcScreen (seqlock; v35, S1) -------------------
	// What the open Minecraft screen has under the cursor, so the host can drop things onto its
	// slots (the spawnmenu's "Minecraft" tab drops GMod weapons onto the hotbar). Written every
	// frame by the MC client together with McState.
	enum McScreenFlags : std::uint32_t
	{
		kScrOpen = 0x1,       // a screen is open
		kScrContainer = 0x2,  // it is a container screen (inventory, creative, chest...): hoveredSlot is valid
	};

	struct McScreen
	{
		std::uint32_t seq;
		std::uint32_t flags;        // McScreenFlags
		std::int32_t  hoveredSlot;  // the local player's inventory slot under the cursor (0-8 hotbar,
		                            // 9-35 main, 36-39 armour, 40 offhand), -1 = none / another container's
		std::int32_t  cursorX;      // the last kInCursor position Minecraft applied (overlay pixels):
		std::int32_t  cursorY;      // hoveredSlot belongs to this position
		std::uint32_t pad14;
		std::uint64_t frameCounter; // McState::frameCounter of the frame this was written in
		std::uint8_t  reserved[0x20];
	};
	inline constexpr std::uint64_t kScrSeq = 0x00;
	inline constexpr std::uint64_t kScrFlags = 0x04;
	inline constexpr std::uint64_t kScrHoveredSlot = 0x08;
	inline constexpr std::uint64_t kScrCursorX = 0x0C;
	inline constexpr std::uint64_t kScrCursorY = 0x10;
	inline constexpr std::uint64_t kScrFrameCounter = 0x18;
	inline constexpr std::uint64_t kMcScreenBytes = 0x40;
	static_assert(offsetof(McScreen, seq) == kScrSeq);
	static_assert(offsetof(McScreen, flags) == kScrFlags);
	static_assert(offsetof(McScreen, hoveredSlot) == kScrHoveredSlot);
	static_assert(offsetof(McScreen, cursorX) == kScrCursorX);
	static_assert(offsetof(McScreen, cursorY) == kScrCursorY);
	static_assert(offsetof(McScreen, frameCounter) == kScrFrameCounter);
	static_assert(sizeof(McScreen) == kMcScreenBytes);

	// ---- client event ring @kClOffEventRing (MC client produces, host consumes; v14) -------
	// The server link's McEvent records and head/tail layout (kEr*: head 0x00, tail 0x40, data
	// 0x80), with fewer entries. Only kEvJoinResult is sent here so far.
	inline constexpr std::uint32_t kClEventRingEntries = 256;  // power of two
	inline constexpr std::uint64_t kClEventRingBytes = 0x3080;  // kErData + kMcEventBytes * entries

	// ---- render ring @kClOffRenderRing (MC -> host) ----------------------------------------
	// Byte ring like the collision ring. Minecraft ships its own block meshes (models, tint, AO,
	// lighting) and its block atlas; the host draws them in its own frame (IMesh) so blocks stay
	// locked to the world and are occluded by the host's geometry.
	inline constexpr std::uint64_t kRrHead = 0x00;
	inline constexpr std::uint64_t kRrTail = 0x40;
	inline constexpr std::uint64_t kRrData = 0x80;

	enum RenType : std::uint32_t
	{
		kRenPad = 0,
		kRenAtlas = 1,     // RenAtlas + pixels (w * h * 4, layout per RenAtlas::flags), top row first
		kRenSection = 2,   // RenSection + RenVertex[vertexCount] (triangle list); 0 vertices = remove
		kRenClearAll = 3,  // drop every section (world change)
		kRenTexture = 4,   // RenTexture + pixels (RenTexture::flags): an entity texture (skin, armour, ...)
		kRenAvatar = 5,    // RenAvatar + RenBatch[batchCount] + RenVertex[vertexCount]: the player's
		                   // model this frame; 0 batches = not shown (first person)
		kRenScene = 6,     // RenScene + RenBatch[batchCount] + RenVertex[vertexCount]: every other
		                   // entity and all particles this frame, relative to RenScene's origin
		kRenAtlasRegion = 7,  // RenAtlasRegion + pixels in the last kRenAtlas's layout: an animated
		                      // sprite's current frame
		kRenLights = 8,       // RenLights + RenLight[count]: a section's light-emitting blocks (sent
		                      // after its kRenSection; 0 = none)
		kRenRagdoll = 9,      // RenAvatar + RenBatch[] + RenVertex[]: the player's body standing still,
		                      // relative to the feet and facing +Z, split into its parts (RenBatch
		                      // flags bits 8-11: RagdollPart). Sent about once a second while alive.
		kRenSolids = 10,      // RenSolids + 512-byte bitset (bit x + 16z + 256y): which blocks of a
		                      // section NPCs collide with (sent after its kRenSection; 0 = none)
		kRenDug = 11,         // RenDug + 512-byte bitset (bit x + 16z + 256y): which blocks of a section
		                      // were dug out of the host's world (its geometry there is gone); 0 = none
	};

	struct RenSolids
	{
		std::int32_t  sx, sy, sz;  // section coords, as in RenSection
		std::uint32_t count;       // solid blocks (0: none, and no bitset follows)
	};
	static_assert(sizeof(RenSolids) == 16);

	struct RenDug
	{
		std::int32_t  sx, sy, sz;  // section coords, as in RenSection
		std::uint32_t count;       // dug blocks (0: none, and no bitset follows)
		std::uint32_t worldId;     // the host world (HostState::worldId) the bits belong to
		std::uint32_t pad;
	};
	static_assert(sizeof(RenDug) == 24);

	enum RagdollPart : std::uint32_t
	{
		kPartNone = 0,
		kPartHead = 1,
		kPartBody = 2,
		kPartRightArm = 3,
		kPartLeftArm = 4,
		kPartRightLeg = 5,
		kPartLeftLeg = 6,
		kPartCount = 7,
	};

	struct RenLights
	{
		std::int32_t  sx, sy, sz;  // section coords, as in RenSection
		std::uint32_t count;
	};
	static_assert(sizeof(RenLights) == 16);

	enum LightKind : std::uint8_t
	{
		kLightSteady = 0,
		kLightFlame = 1,  // torches, fire, campfires, candles: flicker
		kLightLava = 2,   // lava, magma: a slow glow
	};

	enum BlockHazard : std::uint8_t
	{
		kHazardNone = 0,
		kHazardFire = 1,   // fire, soul fire, campfires: burns what stands in it
		kHazardLava = 2,   // lava: burns hard
		kHazardMagma = 3,  // magma block: hurts what stands on top of it
	};

	struct RenLight
	{
		std::uint8_t  x, y, z;  // block within the section
		std::uint8_t  level;    // Minecraft light emission, 1-15
		std::uint32_t color;    // RGB8 (r low byte); top byte: LightKind in bits 0-3, BlockHazard in 4-7
	};
	static_assert(sizeof(RenLight) == 8);

	struct RenAtlasRegion
	{
		std::uint32_t x, y, width, height;  // pixels in the combined atlas (kRenAtlas)
	};
	static_assert(sizeof(RenAtlasRegion) == 16);

	struct RenScene
	{
		double        originX, originY, originZ;  // MC block the positions are relative to
		std::uint32_t batchCount;
		std::uint32_t vertexCount;
	};
	static_assert(sizeof(RenScene) == 32);

	// Pixel layout of kRenAtlas / kRenTexture (kRenAtlasRegion follows its atlas). Straight (not
	// premultiplied) alpha and NOT sRGB-pre-encoded: ordinary texture decode is right for 3D.
	enum RenPixelFlags : std::uint32_t
	{
		kRenPixBGRA = 0x1,  // bytes B, G, R, A (else R, G, B, A)
	};

	struct RenTexture
	{
		std::uint32_t id;  // 1+, referenced by RenBatch::texture
		std::uint32_t width, height;
		std::uint32_t flags;  // RenPixelFlags (MC sends kRenPixBGRA)
	};
	static_assert(sizeof(RenTexture) == 16);

	// The player as Minecraft's own entity renderer draws it (skin, armour, held items, cape),
	// posed and animated, with positions in blocks relative to the player's feet.
	struct RenAvatar
	{
		std::uint32_t batchCount;
		std::uint32_t vertexCount;
	};
	static_assert(sizeof(RenAvatar) == 8);

	struct RenBatch
	{
		std::uint32_t texture;  // 0: the block/item atlas, else a RenTexture id
		std::uint32_t first;    // first vertex
		std::uint32_t count;    // vertices (multiple of 3)
		std::uint32_t flags;    // bit0: translucent (blended, after the solid pass; casts no shadow)
	};
	static_assert(sizeof(RenBatch) == 16);

	struct RenAtlas
	{
		std::uint32_t width, height;
		std::uint32_t flags;  // RenPixelFlags (v13; v12's RenAtlas was 8 bytes)
		std::uint32_t pad;
	};
	static_assert(sizeof(RenAtlas) == 16);

	struct RenSection
	{
		std::int32_t  sx, sy, sz;   // section coords (16-block cubes)
		std::uint32_t vertexCount;  // multiple of 3
	};
	static_assert(sizeof(RenSection) == 16);

	struct RenVertex
	{
		float         x, y, z;  // MC coords relative to the section origin (sx*16, sy*16, sz*16)
		float         u, v;     // atlas UV
		std::uint32_t color;    // RGBA8 (tint * ambient occlusion; Minecraft's fixed face shading is left out)
		std::uint32_t light;    // low byte: block light 0-15, next byte: sky light 0-15
		std::uint32_t flags;    // bit0: cutout (alpha test), bit1: translucent, bit3: no mip (entities/particles),
		                        // bits 4-6: face normal as MC Direction ordinal + 1 (0 = none: lit without a normal;
		                        // 7 = lit by the triangle's own face normal)
	};
	inline constexpr std::uint32_t kRenVertexBytes = 32;
	static_assert(sizeof(RenVertex) == kRenVertexBytes);

	// ---- client link region map ----------------------------------------------------------------
	static_assert(kClOffHeader + sizeof(LinkHeader) <= kClOffHostState);
	static_assert(kClOffHostState + sizeof(HostState) <= kClOffMcState);
	static_assert(kClOffMcState + sizeof(McState) <= kClOffOverlayCtl);
	static_assert(kClOffOverlayCtl + sizeof(OverlayCtl) <= kClOffOverlaySlotHdr);
	static_assert(kClOffOverlaySlotHdr + sizeof(OverlaySlotHdr) * kOverlaySlots <= kClOffWaterGrid);
	static_assert(kClOffWaterGrid + sizeof(WaterGrid) <= kClOffMcIdentity);
	static_assert(kClOffMcIdentity + sizeof(McIdentity) <= kClOffLinkStats);
	static_assert(kClOffLinkStats + sizeof(LinkStats) <= kClOffInputRing);
	static_assert(kClOffMcIdentity % 64 == 0 && kClOffLinkStats % 64 == 0);
	static_assert(kClOffInputRing + kInputRingBytes <= kClOffActorTable);
	static_assert(kClOffActorTable + sizeof(ActorTable) <= kClOffWorldEntities);
	static_assert(kClOffWorldEntities + sizeof(WorldEntities) <= kClOffJoinInfo);
	static_assert(kClOffJoinInfo + sizeof(JoinInfo) <= kClOffJoinStatus);
	static_assert(kClOffJoinStatus + sizeof(JoinStatus) <= kClOffEventRing);
	static_assert(kClOffEventRing + kClEventRingBytes <= kClOffMcScreen);
	static_assert(kClOffMcScreen + sizeof(McScreen) <= kClOffCollisionRing && kClOffMcScreen % 64 == 0);  // v35
	static_assert(kClOffJoinStatus % 64 == 0 && kClOffEventRing % 64 == 0);
	static_assert(kClOffOverlayPixels == kClOffCollisionRing + kClCollisionRingBytes);
	static_assert(kOverlaySlotBytes == std::uint64_t(kMaxOverlayW) * kMaxOverlayH * 4);
	static_assert(kClOffRenderRing == kClOffOverlayPixels + kOverlaySlotBytes * kOverlaySlots);
	static_assert(kClMappingBytes == kClOffRenderRing + kRenderRingBytes);
	static_assert(kClOffCollisionRing % 4096 == 0 && kClOffOverlayPixels % 4096 == 0 && kClOffRenderRing % 4096 == 0);
	static_assert(kClOffInputRing % 64 == 0 && kClOffActorTable % 64 == 0 && kClOffWorldEntities % 64 == 0);

	// =============================================================================================
	// SERVER LINK (GMod server <-> MC server)
	// =============================================================================================
	// v13 re-laid this whole link (bigger host events, UUIDs in the player map, McServerState,
	// LinkStats, the block ring).
	inline constexpr std::uint64_t kSvOffHeader = 0x0;
	inline constexpr std::uint64_t kSvOffServerState = 0x100;
	inline constexpr std::uint64_t kSvOffMcServerState = 0x200;
	// v14 appended McServerInfo (in a gap) and the per-player water grids (after the collision ring);
	// every v13 offset is unchanged.
	inline constexpr std::uint64_t kSvOffWaterGrid = 0x400;            // DEPRECATED (v14): one grid, read
	                                                                   // as player slot 0's (see below)
	inline constexpr std::uint64_t kSvOffLinkStats = 0xC00;
	inline constexpr std::uint64_t kSvOffHostEventRing = 0x1000;
	inline constexpr std::uint64_t kSvOffHostPlayers = 0x12000;
	inline constexpr std::uint64_t kSvOffMcPlayers = 0x15000;
	inline constexpr std::uint64_t kSvOffActorTable = 0x18000;
	inline constexpr std::uint64_t kSvOffEventRing = 0x1D000;
	inline constexpr std::uint64_t kSvOffMcServerInfo = 0x2A000;
	inline constexpr std::uint64_t kSvOffHeldMcEntities = 0x2B000;  // v29, in the gap after McServerInfo
	inline constexpr std::uint64_t kSvOffMcWeaponSets = 0x30000;   // v17, in the gap before the block ring
	inline constexpr std::uint64_t kSvOffMcEntities = 0x3B800;     // v27, after the weapon sets
	inline constexpr std::uint64_t kSvOffBlockRing = 0x40000;
	inline constexpr std::uint64_t kSvBlockRingBytes = 0x100000;       // 1 MiB
	inline constexpr std::uint64_t kSvOffCollisionRing = 0x140000;
	inline constexpr std::uint64_t kSvCollisionRingBytes = 0x2000000;  // 32 MiB
	inline constexpr std::uint64_t kSvOffWaterGrids = 0x2140000;       // WaterGrid[kMaxPlayers]
	inline constexpr std::uint64_t kSvWaterGridsBytes = 0x21000;       // kWaterGridBytes * kMaxPlayers, page-padded
	inline constexpr std::uint64_t kSvMappingBytes = 0x2161000;        // ~33 MiB

	// ---- host -> MC server state @kSvOffServerState (seqlock) -------------------------------
	enum ServerFlags : std::uint32_t
	{
		kServerInGame = 0x1,   // a map is running
		kServerLoading = 0x2,  // map change in progress
		kServerWiremod = 0x4,  // v16: Wiremod is installed on the GMod server: redstone bridges work
		                       // (without it the MC server sends no bridge events and drives nothing)
		kServerMapIo = 0x10,   // v25: the GMod server links map entities (doors, buttons, triggers, ...)
		                       // to redstone bridges: bridges work without Wiremod too
		kServerAnchorReady = 0x8,  // v21: the anchor fields describe this map (worldId/mapName); the MC
		                           // server waits up to kAnchorWaitMs for it before it allocates a new slot
	};

	// v21: where ServerState::floorZ came from (ServerState::anchorSource, McServerState::anchorSource).
	enum AnchorSource : std::uint32_t
	{
		kAnchorNone = 0,      // no floor known (the MC server then uses oyUnits = 0)
		kAnchorSpawns = 1,    // the most common walkable floor near the map's spawn points
		kAnchorMapMode = 2,   // the most common walkable floor of the whole map
		kAnchorCurated = 3,   // the addon's measured table (shared/map_anchors.lua)
		kAnchorOperator = 4,  // the server operator's data/gmodcraft/map_anchors.json
		kAnchorForced = 5,    // dev: the MC server's -Dgmodcraft.forceOy (wins over any hint and the table)
		kAnchorLegacy = 6,    // McServerState only: a slot from before v21 (oyUnits 0)
		kAnchorTimeout = 7,   // McServerState only: no hint within kAnchorWaitMs (oyUnits 0)
	};

	inline constexpr std::uint64_t kMapNameBytes = 40;

	struct ServerState
	{
		std::uint32_t seq;
		std::uint32_t flags;           // ServerFlags
		std::uint32_t worldId;         // hash of the map name (FNV-1a 32 of game.GetMap()), as HostState
		std::uint32_t collisionEpoch;  // bumps on world change; MC drops all server collision data
		std::uint64_t tickNs;          // CLOCK_MONOTONIC ns of the host's last server tick
		char          mapName[40];     // game.GetMap(), lowercased, UTF-8, NUL-terminated. The MC
		                               // server keys its slot table by it (worldId when empty)
		// v21 anchor: the floor hint for a new slot (Source units; valid with kServerAnchorReady).
		std::uint32_t anchorSource;    // AnchorSource
		float         floorZ;          // the map's main floor height
		float         minZ, maxZ;      // the map's z range (world geometry)
		float         footMinX, footMinY, footMaxX, footMaxY;  // the map's xy footprint (world geometry)
		std::uint8_t  reserved[0x80 - 0x60];
	};
	inline constexpr std::uint64_t kSsSeq = 0x00;
	inline constexpr std::uint64_t kSsFlags = 0x04;
	inline constexpr std::uint64_t kSsWorldId = 0x08;
	inline constexpr std::uint64_t kSsCollisionEpoch = 0x0C;
	inline constexpr std::uint64_t kSsTickNs = 0x10;
	inline constexpr std::uint64_t kSsMapName = 0x18;
	inline constexpr std::uint64_t kSsAnchorSource = 0x40;
	inline constexpr std::uint64_t kSsFloorZ = 0x44;
	inline constexpr std::uint64_t kSsMinZ = 0x48;
	inline constexpr std::uint64_t kSsMaxZ = 0x4C;
	inline constexpr std::uint64_t kSsFootMinX = 0x50;
	inline constexpr std::uint64_t kSsFootMinY = 0x54;
	inline constexpr std::uint64_t kSsFootMaxX = 0x58;
	inline constexpr std::uint64_t kSsFootMaxY = 0x5C;
	inline constexpr std::uint64_t kServerStateBytes = 0x80;
	static_assert(offsetof(ServerState, seq) == kSsSeq);
	static_assert(offsetof(ServerState, flags) == kSsFlags);
	static_assert(offsetof(ServerState, worldId) == kSsWorldId);
	static_assert(offsetof(ServerState, collisionEpoch) == kSsCollisionEpoch);
	static_assert(offsetof(ServerState, tickNs) == kSsTickNs);
	static_assert(offsetof(ServerState, mapName) == kSsMapName);
	static_assert(sizeof(ServerState::mapName) == kMapNameBytes);
	static_assert(offsetof(ServerState, anchorSource) == kSsAnchorSource);
	static_assert(offsetof(ServerState, floorZ) == kSsFloorZ);
	static_assert(offsetof(ServerState, minZ) == kSsMinZ);
	static_assert(offsetof(ServerState, maxZ) == kSsMaxZ);
	static_assert(offsetof(ServerState, footMinX) == kSsFootMinX);
	static_assert(offsetof(ServerState, footMinY) == kSsFootMinY);
	static_assert(offsetof(ServerState, footMaxX) == kSsFootMaxX);
	static_assert(offsetof(ServerState, footMaxY) == kSsFootMaxY);
	static_assert(sizeof(ServerState) == kServerStateBytes);

	// ---- MC server -> host state @kSvOffMcServerState (seqlock) ----------------------------
	// The MC server's answer to ServerState: the slot of the map the host runs (see "map slots").
	// The MC server looks the map up in its persisted table (in the world save), allocating the
	// next free slot the first time it sees a map, and publishes it here within a server tick of
	// reading a new worldId / mapName with kServerInGame. The host must not convert coordinates
	// for a map until kMcSrvSlotValid is set AND worldId equals its own ServerState::worldId;
	// then the GMod server tells its clients (GMod net), whose gmcl echoes it in HostState.
	enum McServerFlags : std::uint32_t
	{
		kMcSrvSlotValid = 0x1,   // slotX/Z and originX/Z answer worldId
		kMcSrvDedicated = 0x2,   // a dedicated MC server (else an integrated one)
		kMcSrvNewSlot = 0x4,     // the slot was allocated for this map just now (first visit)
		kMcSrvSlotBusy = 0x8,    // v23: a slot job (re-anchor) owns the slot; originY changes when it's done
		kMcSrvAnchorWait = 0x10, // v21: a new map waits for the host's floor hint (kServerAnchorReady, at
		                         // most kAnchorWaitMs); kMcSrvSlotValid stays clear meanwhile
	};

	struct McServerState
	{
		std::uint32_t seq;
		std::uint32_t flags;      // McServerFlags
		std::uint32_t worldId;    // the ServerState::worldId this answers (0: none yet)
		std::int32_t  slotX;      // slot index on the kSlotBlocks grid
		std::int32_t  slotZ;
		std::int32_t  originX;    // ox = slotX * kSlotBlocks (blocks)
		std::int32_t  originZ;    // oz = slotZ * kSlotBlocks (blocks)
		std::uint32_t slotCount;  // maps in the table (informational)
		std::uint64_t tickNs;     // CLOCK_MONOTONIC ns of the last answer (rewritten only when the answer
		                          // changes: use the header heartbeat for liveness, compare worldId+flags;
		                          // "answered, no slot" = seq != 0 with kMcSrvSlotValid clear)
		std::int32_t  originY;       // v21: oyUnits, the map's vertical offset in SOURCE UNITS ("map slots")
		std::uint32_t anchorSource;  // v21: AnchorSource of the slot's offset
		// v24 (P8 WP3): the MC server's rules (config/gmodcraft.properties) and world. Written with every
		// answer, also before a slot is known; valid when ruleFlags has kRulesValid.
		std::uint32_t ruleFlags;     // ServerRuleFlags
		std::uint8_t  gameMode;      // GameModeId: the default game mode (forced on join with kRuleForceGamemode)
		std::uint8_t  worldType;     // WorldType of the running world (its generator, read at start)
		std::int16_t  floorY;        // the y new maps' floors land on (T; the flat worlds' surface is T - 1)
		float         damageScale;   // hostDamagePerMcDamage: GMod damage per Minecraft damage point
		// v34 (control centre): the running world's difficulty and the mob cap rule.
		std::uint8_t  difficulty;    // DifficultyId ([difficulty]; the world's own when the rule isn't set)
		std::uint8_t  reserved;
		std::uint16_t mobCapPercent; // [mobCapPercent] natural spawning caps in percent of vanilla (0 .. kMobCapMax)
	};
	inline constexpr std::uint64_t kMssSeq = 0x00;
	inline constexpr std::uint64_t kMssFlags = 0x04;
	inline constexpr std::uint64_t kMssWorldId = 0x08;
	inline constexpr std::uint64_t kMssSlotX = 0x0C;
	inline constexpr std::uint64_t kMssSlotZ = 0x10;
	inline constexpr std::uint64_t kMssOriginX = 0x14;
	inline constexpr std::uint64_t kMssOriginZ = 0x18;
	inline constexpr std::uint64_t kMssSlotCount = 0x1C;
	inline constexpr std::uint64_t kMssTickNs = 0x20;
	inline constexpr std::uint64_t kMssOriginY = 0x28;
	inline constexpr std::uint64_t kMssAnchorSource = 0x2C;
	inline constexpr std::uint64_t kMssRuleFlags = 0x30;
	inline constexpr std::uint64_t kMssGameMode = 0x34;
	inline constexpr std::uint64_t kMssWorldType = 0x35;
	inline constexpr std::uint64_t kMssFloorY = 0x36;
	inline constexpr std::uint64_t kMssDamageScale = 0x38;
	inline constexpr std::uint64_t kMssDifficulty = 0x3C;
	inline constexpr std::uint64_t kMssMobCapPercent = 0x3E;
	inline constexpr std::uint64_t kMcServerStateBytes = 0x40;
	static_assert(offsetof(McServerState, seq) == kMssSeq);
	static_assert(offsetof(McServerState, flags) == kMssFlags);
	static_assert(offsetof(McServerState, worldId) == kMssWorldId);
	static_assert(offsetof(McServerState, slotX) == kMssSlotX);
	static_assert(offsetof(McServerState, slotZ) == kMssSlotZ);
	static_assert(offsetof(McServerState, originX) == kMssOriginX);
	static_assert(offsetof(McServerState, originZ) == kMssOriginZ);
	static_assert(offsetof(McServerState, slotCount) == kMssSlotCount);
	static_assert(offsetof(McServerState, tickNs) == kMssTickNs);
	static_assert(offsetof(McServerState, originY) == kMssOriginY);
	static_assert(offsetof(McServerState, anchorSource) == kMssAnchorSource);
	static_assert(offsetof(McServerState, ruleFlags) == kMssRuleFlags);
	static_assert(offsetof(McServerState, gameMode) == kMssGameMode);
	static_assert(offsetof(McServerState, worldType) == kMssWorldType);
	static_assert(offsetof(McServerState, floorY) == kMssFloorY);
	static_assert(offsetof(McServerState, damageScale) == kMssDamageScale);
	static_assert(offsetof(McServerState, difficulty) == kMssDifficulty);
	static_assert(offsetof(McServerState, mobCapPercent) == kMssMobCapPercent);
	static_assert(sizeof(McServerState) == kMcServerStateBytes);

	// v24 (P8 WP3): McServerState::ruleFlags. The rules live in the MC server's
	// config/gmodcraft.properties (keys in brackets); kAdminSetRules changes them live.
	enum ServerRuleFlags : std::uint32_t
	{
		kRulesValid = 0x1,          // the fields below are filled in (an MC server of v24 or later)
		kRuleForceGamemode = 0x2,   // [forceGamemode] every player gets gameMode on join
		kRulePvp = 0x4,             // [pvp] players can hurt each other (GameRules.PVP)
		kRuleKeepInventory = 0x8,   // [keepInventory] (GameRules.KEEP_INVENTORY)
		kRuleDigIntoMap = 0x10,     // [digIntoMap] Minecraft digs into GMod's map (mining, explosions)
		kRuleNoclipMc = 0x20,       // [noclipMc] GMod's noclip reaches Minecraft players (N1)
		kRuleFireCrossover = 0x40,  // v28 [fireCrossover] fire crosses between GMod and Minecraft (F1)
		kRulePhysgunMobs = 0x80,    // v29 [physgunMobs] GMod's physgun / gravgun / tools work on Minecraft entities (T2)
		kRuleMobSpawning = 0x100,   // v34 [mobSpawning] natural mob spawning (gamerules spawn_mobs / spawn_monsters; default off)
	};

	// v34 McServerState::difficulty ([difficulty]: peaceful, easy, normal, hard): Minecraft's ids.
	enum DifficultyId : std::uint8_t
	{
		kDifficultyPeaceful = 0,
		kDifficultyEasy = 1,
		kDifficultyNormal = 2,
		kDifficultyHard = 3,
	};
	inline constexpr std::uint32_t kMobCapMax = 1000;  // v34 [mobCapPercent]: 100 = vanilla caps, 0 = no natural spawns

	// McServerState::gameMode ([gamemode]: survival, creative, adventure, spectator): Minecraft's ids.
	enum GameModeId : std::uint8_t
	{
		kGameSurvival = 0,
		kGameCreative = 1,
		kGameAdventure = 2,
		kGameSpectator = 3,
	};

	// McServerState::worldType: what generates the running world ([worldType] in the properties only
	// picks it for a NEW world: the level-type of a dedicated server, the single-player world's preset).
	enum WorldType : std::uint8_t
	{
		kWorldVoid = 0,           // gmodcraft:mirror, nothing but the maps' blocks (the default)
		kWorldFlatVoidMaps = 1,   // gmodcraft:flat_void_maps: a superflat surface at floorY - 1, void inside
		                          // the maps' footprints (+16 blocks) and around every unused slot origin
		kWorldFlatEverywhere = 2, // gmodcraft:flat_everywhere: the superflat layers everywhere, under the maps too
		kWorldOther = 3,          // another generator (a vanilla world)
		// 4: reserved ("underground", 0.5: terrain to mine below the map)
	};
	inline constexpr std::int32_t kFlatLayers = 4;  // bedrock, dirt, dirt, grass block: y floorY - 4 .. floorY - 1

	// ---- MC server info @kSvOffMcServerInfo (MC server -> host, seqlock; v14) ----------------
	// How other players reach this Minecraft server: what the GMod server hands its clients as
	// JoinInfo::serverAddress. Rewritten whenever it changes and on a new link session; each
	// rewrite is announced by a kEvWorldOpened event.
	//  * Dedicated server: open from startup (kSrvInfoOpen | kSrvInfoDedicated, port = its port).
	//  * Integrated server (listen-server host): closed until it is opened to LAN, by the host's
	//    kHostEvOpenToLan or by the player's own "Open to LAN" button.
	//  * e4mc (an optional mod) also publishes an opened world through an internet relay; once its
	//    address arrives (seconds later), kSrvInfoE4mcReady is set and e4mcAddress holds it.
	// lanAddress is Minecraft's best guess at its LAN address ("ip:port"); the GMod server may
	// prefer its own idea of the machine's address with `port`.
	enum McServerInfoFlags : std::uint32_t
	{
		kSrvInfoOpen = 0x1,           // other players can join (port is valid)
		kSrvInfoDedicated = 0x2,      // a dedicated server
		kSrvInfoOnlineMode = 0x4,     // it checks Microsoft accounts (offline / dev profiles are refused)
		kSrvInfoE4mcInstalled = 0x8,  // the e4mc mod is present
		kSrvInfoE4mcReady = 0x10,     // e4mcAddress is valid
	};

	inline constexpr std::uint64_t kServerInfoAddressBytes = 128;

	struct McServerInfo
	{
		std::uint32_t seq;
		std::uint32_t flags;             // McServerInfoFlags
		std::uint32_t port;              // TCP port it accepts players on (0 while closed)
		std::uint32_t requestId;         // the last kHostEvOpenToLan this answered (0: none)
		std::uint32_t maxPlayers;
		std::uint32_t pad;
		char          lanAddress[128];   // "ip:port", UTF-8, NUL-terminated; empty while closed
		char          e4mcAddress[128];  // e4mc's relay domain (players join it on port 25565); empty: none
	};
	inline constexpr std::uint64_t kSiSeq = 0x00;
	inline constexpr std::uint64_t kSiFlags = 0x04;
	inline constexpr std::uint64_t kSiPort = 0x08;
	inline constexpr std::uint64_t kSiRequestId = 0x0C;
	inline constexpr std::uint64_t kSiMaxPlayers = 0x10;
	inline constexpr std::uint64_t kSiLanAddress = 0x18;
	inline constexpr std::uint64_t kSiE4mcAddress = 0x98;
	inline constexpr std::uint64_t kMcServerInfoBytes = 0x118;
	static_assert(offsetof(McServerInfo, seq) == kSiSeq);
	static_assert(offsetof(McServerInfo, flags) == kSiFlags);
	static_assert(offsetof(McServerInfo, port) == kSiPort);
	static_assert(offsetof(McServerInfo, requestId) == kSiRequestId);
	static_assert(offsetof(McServerInfo, maxPlayers) == kSiMaxPlayers);
	static_assert(offsetof(McServerInfo, lanAddress) == kSiLanAddress);
	static_assert(offsetof(McServerInfo, e4mcAddress) == kSiE4mcAddress);
	static_assert(sizeof(McServerInfo::lanAddress) == kServerInfoAddressBytes);
	static_assert(sizeof(McServerInfo::e4mcAddress) == kServerInfoAddressBytes);
	static_assert(sizeof(McServerInfo) == kMcServerInfoBytes);

	// ---- water grids @kSvOffWaterGrids (host -> MC server, seqlock each; v14) ---------------
	// WaterGrid[kMaxPlayers]: grid i is the water around HostPlayers::players[i] (see "player map":
	// HostPlayers is slot-indexed). The MC server uses grid i while i < HostPlayers::count, that
	// record's steamId is non-zero, the grid's seq is non-zero and its worldId is ServerState's;
	// the union of those is its water. Clear a grid by writing it with every surface kNoWater.
	// The single v13 grid @kSvOffWaterGrid still works as slot 0's while grid 0 has never been
	// written (seq 0); it goes away once the GMod side writes per-player grids.

	// ---- link stats @kSvOffLinkStats: LinkStats (see "link stats" above), rings kSvRing* ----

	// ---- host event ring @kSvOffHostEventRing (host produces, MC server consumes) ----------
	// Things the GMod server does to Minecraft players. The target is always steamId; the MC server
	// resolves it through HostPlayers (UUID, else name; see "player map").
	//
	// Teleport (kHostEvTeleport) and respawn (kHostEvRespawn) are requests with an id: the host
	// picks requestId (non-zero, unique per session, e.g. a counter) and the MC server always
	// answers with exactly one kEvTeleportAck carrying it. The ack means the MC CLIENT has
	// confirmed the move (Minecraft's teleport handshake: its next position report is from the new
	// place), so from then on the client link's McState is at the new position; the puppet holds
	// (docs/DESIGN.md 4.2) from sending the request until the ack. If the client hasn't confirmed
	// within kTeleportAckTimeoutMs the ack comes anyway with kTeleportTimeout (the server-side
	// position was still set).
	// Keep at most ONE teleport / respawn request outstanding per player: Minecraft has a single
	// teleport handshake per connection. If a second request arrives anyway, MC acks the older one at
	// once with kTeleportTimeout and keeps the newer; the host must not retry a request it superseded
	// itself, and its puppet hold follows the newest request.
	inline constexpr std::uint32_t kHostEventRingEntries = 1024;  // power of two
	inline constexpr std::uint32_t kTeleportAckTimeoutMs = 5000;
	inline constexpr std::uint64_t kHrHead = 0x00;  // u64 events ever written (host)
	inline constexpr std::uint64_t kHrTail = 0x40;  // u64 events ever consumed (MC)
	inline constexpr std::uint64_t kHrData = 0x80;

	enum HostEventType : std::uint16_t
	{
		kHostEvHurt = 1,      // a GMod entity hurt a player: steamId = target player, code = HurtKind,
		                      // a = GMod damage * 100, entId = attacker (0: world), flags = HurtFlags.
		                      // v14: when entId is the entIndex of a HostPlayers record whose
		                      // Minecraft player is online (not the target), that Minecraft player is
		                      // the attacker (PvP: armour, knockback, kill credit, Minecraft's pvp
		                      // setting); else the actor table's stand-in for entId, if any.
		kHostEvTeleport = 2,  // move the player: steamId, requestId, worldId (0: don't check), x/y/z =
		                      // feet (MC coords, slot origin included), yaw/pitch (MC degrees),
		                      // code = TeleportReason, flags = TeleportFlags. Velocity and fall
		                      // distance are reset.
		kHostEvRespawn = 3,   // the GMod player (re)spawned: if the MC player is dead it respawns
		                      // first (full health, Minecraft's own respawn), then it is teleported as
		                      // kHostEvTeleport (same fields). If alive: just the teleport. Acked the
		                      // same way.
		kHostEvOpenToLan = 4, // v14, no target (steamId 0): open the integrated server's world to other
		                      // players (a listen-server host). requestId (non-zero), a = TCP port (0:
		                      // Minecraft picks a free one), flags = OpenFlags. Always answered by one
		                      // kEvWorldOpened with that requestId; McServerInfo then says how to
		                      // reach it. A dedicated server answers kOpenAlreadyOpen.
		kHostEvDevCommand = 5,     // v14, DEV ONLY, no target: run a Minecraft command as the server
		                           // console (permission level 4). requestId (non-zero), a = command
		                           // length in bytes (<= kDevCommandMaxBytes, no leading '/' needed),
		                           // code = how many kHostEvDevCommandText slots follow (<=
		                           // kDevCommandMaxChunks). The host writes the whole sequence before it
		                           // publishes head. MC runs it only when started with
		                           // -Dgmodcraft.devCommands=true or GMODCRAFT_DEV_COMMANDS=1; always
		                           // answered by kEvDevCommandResult (+ text slots).
		kHostEvDevCommandText = 6, // v14: kDevCommandChunkBytes of the command's UTF-8 at kHeText
		                           // (code = chunk index); only directly after kHostEvDevCommand.
		kHostEvBridgeOutputs = 7,  // v16, no target (steamId 0): what the wire entity of a redstone
		                           // bridge drives. worldId = the map (dropped unless it is the
		                           // current one), x/y/z = the bridge block (integer MC coords, slot
		                           // origin included), code = driven mask (bit i = BridgeFace i),
		                           // flags = levels (bits 4i..4i+3 = BridgeFace i, 0-15). A driven
		                           // face gives weak redstone power of its level on that face; an
		                           // undriven one is read as an input (kEvBridgeInputs). The full
		                           // state each time, sent only on change, at most one per bridge
		                           // per host tick (latest wins).
		// v17 hybrid mode: GMod weapons as gmodcraft:gmod_weapon items (see "weapon sets" below).
		// The MC inventory owns possession; these report what GMod did to a player's weapons.
		kHostEvWeaponGive = 8,     // the GMod player steamId got a weapon on the GMod side (pickup,
		                           // spawn menu, loadout): put a stack of it into their inventory
		                           // (none if one of that class is there already: one per class; a
		                           // full inventory drops it at their feet). requestId (non-zero),
		                           // a = class hash (int32 bits), flags = WeaponCategory, x / y =
		                           // clip1 / clip2 (-1: none), code = how many kHostEvWeaponText
		                           // slots follow (1..kWeaponTextMaxChunks), published with it. Acked
		                           // through McWeaponSet::lastRequestId. v35: entId = the inventory
		                           // slot to put it into + 1 (0: anywhere; the spawnmenu's Minecraft
		                           // tab drops weapons onto hotbar slots): an empty slot takes it,
		                           // an occupied one moves its item elsewhere; one of that class the
		                           // player has already moves there (swapping).
		kHostEvWeaponText = 9,     // v17: kWeaponTextChunkBytes of the weapon's UTF-8 at kHeText
		                           // (code = chunk index); only directly after kHostEvWeaponGive.
		                           // The text is "<class>\0<print name>", NUL-padded.
		kHostEvWeaponTake = 10,    // the GMod player steamId lost a weapon on the GMod side (dropped,
		                           // stripped): remove one stack of class hash a (the main hand's
		                           // first, then the inventory's). requestId (non-zero), acked
		                           // through McWeaponSet::lastRequestId like a Give.
		kHostEvWeaponState = 11,   // the clips of the GMod weapon a (class hash) of steamId: x / y =
		                           // clip1 / clip2 (-1: none). Written into that stack's swep
		                           // component; when the inventory has none (MC dropped it), into
		                           // the newest such item entity the player threw nearby.
		kHostEvAdminCommand = 12,  // v19: an admin's command (the GMod side sends it only for admins:
		                           // IsAdmin / IsSuperAdmin / singleplayer; Minecraft takes it from the
		                           // server link only). steamId = the admin, requestId (non-zero),
		                           // code = AdminCommand, worldId = the map, x/y/z = target block (MC
		                           // coords), yaw = the admin's MC yaw (Minecraft rounds it to 90 deg),
		                           // a = instance id (kAdminDemoClear), flags = AdminFlags. Every
		                           // command is followed by exactly one kHostEvAdminText slot (the
		                           // name; empty for commands without one), published together; one
		                           // without it is answered kAdminMalformed. Always answered by one
		                           // kEvAdminResult with the requestId.
		kHostEvAdminText = 13,     // v19: kAdminTextBytes of UTF-8 at kHeText (the demo name, NUL-
		                           // padded); only directly after kHostEvAdminCommand.
		kHostEvHurtMcEntity = 14,  // v27: GMod hurt the Minecraft entity behind a proxy (McEntities):
		                           // requestId = its McEntity::entityId, code = HurtKind, a = GMod damage
		                           // * 100 (Minecraft divides by its hostDamagePerMcDamage), flags =
		                           // HurtFlags (kHurtFire: ignite, no damage), entId = the GMod attacker's entity index (0: world),
		                           // steamId = the attacker's SteamID64 when it is a GMod player (0:
		                           // an NPC or the world; not a target, the event has none). Credited
		                           // like kHostEvHurt: that player's Minecraft player (PvP-style kill
		                           // credit and loot), else the host actor stand-in for entId.
		kHostEvFire = 15,          // v28 (F1), no target (steamId 0): something of the host's burns at x/y/z
		                           // (MC coords; worldId = the map): MC may set fire to flammable MC blocks
		                           // within a blocks (1-3) of it. code = FireCause. Minecraft decides
		                           // (rule fireCrossover, gamerule fire spread, its own caps: at most
		                           // kFireBlocksPerEvent per event and kFireBlocksPerTick per tick; only air
		                           // cells inside the map's slot with no host geometry, not dug, not locked).
		kHostEvPuntMcEntity = 16,  // v29 (T2), no target (steamId = the punting player's, 0: none): a gravgun
		                           // punt on the proxy of Minecraft entity requestId (McEntity::entityId) while
		                           // Minecraft owns it (not in HeldMcEntities): x/y/z = the impulse, MC blocks per
		                           // tick (MC axes), added to its motion once. Minecraft clamps it.
		// v33 (P1): GMod props as gmodcraft:gmod_prop items (see "prop items" below). 17-18 are S1's.
		kHostEvPropResult = 19,    // answers kEvPropRequest: steamId (the requester), requestId (echoed), a =
		                           // PropOp, flags = PropResult, entId = the GMod entity removed (pickup) or
		                           // spawned (place), 0: none. A pickup with kPropOk gives the player a
		                           // stack of the prop (a full inventory drops it at their feet): x = skin,
		                           // y = colour (RGBA, r in the top byte, as a u32 in the double), code = how
		                           // many kHostEvPropText slots follow (1..kPropHostMaxChunks), published
		                           // with it. A place with kPropOk consumes one matching stack. Every other
		                           // answer has code 0 and changes no stack (the player is told why).
		kHostEvPropText = 20,      // v33: kPropHostChunkBytes of the prop text at kHeText (code = chunk
		                           // index); only directly after kHostEvPropResult.
	};

	// v19 admin commands (kHostEvAdminCommand::code) and their flags.
	enum AdminCommand : std::uint16_t
	{
		kAdminDemoPlace = 1,     // place demo <name> with its origin at x/y/z, rotated by yaw
		kAdminDemoClear = 2,     // clear demo instance a (restore the world exactly)
		kAdminDemoClearAll = 3,  // clear every demo instance of this map
		kAdminDemoAnnounce = 4,  // announce every live instance of this map again (+ kEvDemoSyncDone)
		// v20, the Garry's Modcraft STools. Each needs kAdminByAdmin or kAdminEveryone in flags (the GMod
		// side's permission decision; Minecraft refuses a command with neither: kAdminNotAllowed).
		// Undo stacks are per steamId, in memory (lost when Minecraft restarts).
		kAdminRepairRadius = 5,  // Terrain Repair: the dug cells (this map) whose centres lie within a
		                         // blocks (1-8) of x/y/z become solid GMod geometry again. Result: a =
		                         // cells restored, flags = cells skipped (an MC block other than the
		                         // terrain digging revealed is in them: they stay dug)
		kAdminRepairColumn = 6,  // Terrain Repair: every dug cell (this map) of the chunk column at x/z
		kAdminRepairUndo = 7,    // undo steamId's last repair (a stack of 5): a = cells dug again
		kAdminBlockPlace = 8,    // Block Tool: set the block at x/y/z (air or replaceable only) to the
		                         // block state in the text slot; liquids only with kAdminAllowLiquid,
		                         // never operator blocks (command / structure / jigsaw / barrier)
		kAdminBlockBreak = 9,    // Block Tool: break the MC block at x/y/z (no drops)
		kAdminBlockUndo = 10,    // undo steamId's last block change (a stack of 10)
		kAdminResync = 11,       // Resync: the MC server re-sends the block sections within a blocks of
		                         // x/y/z (a = 0: every loaded section) on the block ring. Result a = sections
		// v23 (P8 WP2): slot re-anchor, admins only (kAdminByAdmin). Answered at once (kAdminOk + the job
		// id in kEvAdminResult::a, or a refusal); the job then runs over ticks: McServerState carries
		// kMcSrvSlotBusy meanwhile and the new originY when it is done (the MC log has the outcome).
		kAdminReanchor = 12,      // a = dyUnits (Source units, signed): the slot's offset changes by exactly dy, its
		                          // MC content moves by round(dy / 40) blocks. kAdminDryRun: only check it fits.
		kAdminReanchorUndo = 13,  // a = the re-anchor's job id: moved back (the exact inverse)
		// v24 (P8 WP3), admins only (kAdminByAdmin):
		kAdminSetRules = 14,      // the text slot: "key=value;key=value..." (ServerRuleFlags keys, gamemode,
		                          // hostDamagePerMcDamage, worldType, floorY). All pairs are checked first: an
		                          // unknown key or a bad value is kAdminBadRule (a = the 1-based pair) and
		                          // nothing changes. Else the properties file is rewritten (atomically, other
		                          // keys kept; kAdminFailed if that fails, nothing changed), the rules apply at
		                          // once (worldType / floorY: for the next new world only) and McServerState is
		                          // answered again. a = pairs applied.
		kAdminSlotHash = 15,      // the slot's content hash (absolute + relative), in the MC log
		kAdminSlotHistory = 16,   // v24: the slot's re-anchor history entry a (0: the newest). kAdminOk with
		                          // a = its job id, b = dyUnits, c = the job it undid (0: none), d = the job
		                          // that undid it (0: still in effect), flags = how many entries there are,
		                          // weapon = the index; kAdminNothing past the end (flags = the count).
		kAdminBridgeLink = 17,   // v25: link the redstone bridge at x/y/z to a GMod map entity: a = its
		                         // MapCreationID (-1: unlink), the text slot = "<kind> <mode> <targetname>"
		                         // (kind: button momentary trigger door movelinear relay light sprite;
		                         // mode: in = the entity drives redstone, out = redstone drives the
		                         // entity). Needs kAdminByAdmin or kAdminEveryone. Stored with the block
		                         // (block entity NBT, with the map) and announced as kEvBridgeLink.
		// v34 (control centre), admins only (kAdminByAdmin):
		kAdminSlotInfo = 18,     // the slot of the map named in the text slot (lower case; never allocates one):
		                         // kAdminOk with a = slotX, b = slotZ, c = oyUnits (Source units), d = the
		                         // anchor source, flags = 1 when it is the current map's; kAdminNothing when
		                         // the map has no slot yet (it gets one on its first visit).
		kAdminWorldList = 19,    // entry a of the world list (0: the running world, 1..: its backups, newest
		                         // first). kAdminOk: flags = the entry count, weapon = a, c = its size in MiB,
		                         // d = WorldListKind. A backup: a = its name's stamp's day (days since
		                         // 1970-01-01), b = the second of that day. Entry 0: a = the pending WorldOp,
		                         // b = what it uses (kWorldOpNew: the WorldType; kWorldOpRestore: the backup's
		                         // entry index; else -1). kAdminNothing past the end. Dedicated servers only
		                         // (else kAdminUnsupported).
		kAdminWorldOp = 20,      // a = WorldOp, scheduled for the next start of the dedicated server: the
		                         // text slot = the world type (kWorldOpNew: mirror | flat_void_maps |
		                         // flat_everywhere) or the backup's stamp "YYYYMMDD-HHMMSS" (kWorldOpRestore,
		                         // checked against the backups that exist). tools/run_mc_server.sh applies it
		                         // before Java starts: the running world is moved aside as a new backup first
		                         // (never deleted). With kAdminRestartNow the server also saves and stops at
		                         // once when a supervisor (run_mc_server.sh) restarts it, else kAdminOk only
		                         // schedules it (result a = 1: it restarts now, 0: on the next start).
	};

	// v34 kAdminWorldList entries (kEvAdminResult::d) and kAdminWorldOp operations (kHostEvAdminCommand::a).
	enum WorldListKind : std::uint32_t
	{
		kWorldEntryRunning = 0,  // the running world (<level-name>)
		kWorldEntryBackup = 1,   // a backup: <level-name>.bak-YYYYMMDD-HHMMSS (run_mc_server.sh --reset-world)
	};
	enum WorldOp : std::uint32_t
	{
		kWorldOpNone = 0,      // (kAdminWorldList: nothing pending)
		kWorldOpNew = 1,       // a fresh world of the given type; the running one becomes a backup
		kWorldOpRestore = 2,   // the backup back in place; the running one becomes a backup first
		kWorldOpCancel = 3,    // forget the pending operation
	};

	enum AdminFlags : std::uint32_t
	{
		kAdminForce = 0x1,  // kAdminDemoPlace: place even where the area isn't empty
		kAdminAllowLiquid = 0x2,  // v20 kAdminBlockPlace: liquids allowed (the admin ticked it)
		kAdminByAdmin = 0x4,      // v20: the requester is a GMod admin (or the server console)
		kAdminEveryone = 0x8,     // v20: the GMod server lets everyone use this tool (its convar)
		kAdminDryRun = 0x10,      // v23 kAdminReanchor: only check (scan the slot), change nothing
		kAdminStaged = 0x20,      // v24 kAdminSetRules: more texts of this change follow. Every text of a change
		                          // carries a = the change's id (one the host picks, e.g. a fresh request id; 0: a one-text
		                          // change). MC buffers staged texts per steamId (answered kAdminOk, a = pairs so
		                          // far), checks the whole set, and applies it only with the final text (no
		                          // kAdminStaged). The buffer is dropped after kRulesStageMs, on a refusal, or
		                          // when a text of another change arrives; a final text whose change isn't
		                          // buffered (expired) is kAdminMalformed and changes nothing.
		kAdminRestartNow = 0x40,  // v34 kAdminWorldOp: also stop the server now (when supervised) so it applies at once
	};
	inline constexpr std::uint32_t kAdminTextBytes = 60;
	inline constexpr std::uint32_t kRulesStageMs = 5000;  // v24: a staged kAdminSetRules change lives this long

	// kHostEvWeaponGive text: "<class>\0<print name>" in kHostEvWeaponText slots. The class (lower-
	// case, ASCII) must fit kWeaponClassMaxBytes; the print name is cut to what fits.
	inline constexpr std::uint32_t kWeaponTextChunkBytes = 60;
	inline constexpr std::uint32_t kWeaponTextMaxChunks = 3;
	inline constexpr std::uint32_t kWeaponTextMaxBytes = 180;
	inline constexpr std::uint32_t kWeaponClassMaxBytes = 64;

	// What a GMod weapon looks like in Minecraft (its item sprite), from the SWEP's HoldType.
	enum WeaponCategory : std::uint32_t
	{
		kWeapCatGeneric = 0,  // normal, grenade, slam, passive, anything else
		kWeapCatPistol = 1,   // pistol, revolver, duel
		kWeapCatSmg = 2,      // smg
		kWeapCatRifle = 3,    // ar2, crossbow
		kWeapCatShotgun = 4,  // shotgun
		kWeapCatHeavy = 5,    // rpg
		kWeapCatMelee = 6,    // melee, melee2, knife, fist
		kWeapCatTool = 7,     // physgun, toolgun, gravity gun, camera, magic
	};
	inline constexpr std::uint32_t kWeapCategories = 8;

	// v33 (P1) prop items. The prop text (both ways): "<model>\0<material>\0<bodygroups>\0<dupe>", NUL-
	// padded. model: lowercase GMod path ("models/props_junk/wood_crate001a.mdl"), 1..kPropModelMaxBytes;
	// material: override material ("" none), <= kPropMaterialMaxBytes; bodygroups: one base-36 digit per
	// group (Entity:SetBodyGroups), <= kPropBodygroupsMaxBytes; dupe: optional hash of a stored dupe, hex,
	// <= kPropDupeMaxBytes ("" none). The model hash (icons) is FNV-1a 32 of the model, as a class hash.
	enum PropOp : std::uint32_t
	{
		kPropOpPickup = 1,  // sneak + use with an empty main hand on a GMod prop's collision (kTriDynamic)
		kPropOpPlace = 2,   // use with a gmod_prop stack on any surface
	};
	enum PropResult : std::uint32_t
	{
		kPropOk = 0,
		kPropNotAllowed = 1,   // prop protection (CPPI), PhysgunPickup / PlayerSpawnProp said no, not the owner
		kPropLimit = 2,        // place: sbox_maxprops reached
		kPropNotAProp = 3,     // pickup: gone, not a prop_physics, or ours; place: not a valid model
		kPropConstrained = 4,  // pickup: welded / roped / ... (v1 takes loose props only)
		kPropDisabled = 5,     // prop items are off (convar), the player isn't paired / in hybrid mode
		kPropMalformed = 6,    // bad sequence (missing text slots, bad text)
		kPropFailed = 7,       // GMod couldn't do it (spawn failed, ring full)
	};
	inline constexpr std::uint32_t kPropModelMaxBytes = 260;
	inline constexpr std::uint32_t kPropMaterialMaxBytes = 96;
	inline constexpr std::uint32_t kPropBodygroupsMaxBytes = 32;
	inline constexpr std::uint32_t kPropDupeMaxBytes = 16;
	inline constexpr std::uint32_t kPropTextMaxBytes = 440;   // >= 260 + 96 + 32 + 16 + 3 NULs
	inline constexpr std::uint32_t kPropHostChunkBytes = 60;  // kHostEvPropText (from kHeText)
	inline constexpr std::uint32_t kPropHostMaxChunks = 8;
	inline constexpr std::uint32_t kPropMcChunkBytes = 44;    // kEvPropText (from kMeText)
	inline constexpr std::uint32_t kPropMcMaxChunks = 10;
	inline constexpr std::uint32_t kPropMaxStack = 16;        // identical prop stacks hold this many
	inline constexpr std::uint32_t kPropSkinShift = 16;       // kEvPropRequest::flags = PropOp | skin << 16
	static_assert(kPropModelMaxBytes + kPropMaterialMaxBytes + kPropBodygroupsMaxBytes + kPropDupeMaxBytes + 3 <= kPropTextMaxBytes);
	static_assert(kPropHostChunkBytes * kPropHostMaxChunks >= kPropTextMaxBytes);
	static_assert(kPropMcChunkBytes * kPropMcMaxChunks == kPropTextMaxBytes);

	// kHostEvDevCommand text: chunks of the UTF-8 command in kHostEvDevCommandText slots.
	inline constexpr std::uint64_t kHeText = 0x04;
	inline constexpr std::uint32_t kDevCommandChunkBytes = 60;
	inline constexpr std::uint32_t kDevCommandMaxChunks = 4;
	inline constexpr std::uint32_t kDevCommandMaxBytes = 240;

	enum OpenFlags : std::uint32_t
	{
		kOpenAllowOffline = 0x1,  // let offline / dev profiles in (no Microsoft account check)
	};

	enum HurtKind : std::uint16_t
	{
		kHurtMelee = 0,
		kHurtProjectile = 1,  // bullets, crossbow bolts, thrown things
		kHurtMagic = 2,       // energy / dissolve / shock
		kHurtOther = 3,       // falls, explosions, world
	};

	enum HurtFlags : std::uint32_t
	{
		kHurtBlockedByHost = 0x1,  // the host already counted it as blocked
		kHurtPowerAttack = 0x2,    // shove harder (knockback)
		kHurtFire = 0x4,           // v31 additive (F2), kHostEvHurtMcEntity only: GMod fire (DMG_BURN) on the
		                           // proxy; Minecraft sets the entity on fire (its own burning does the damage)
		                           // instead of applying a. Older Minecraft ignores the bit (plain damage).
	};

	enum TeleportReason : std::uint16_t
	{
		kTeleportReasonSetPos = 0,     // Entity:SetPos from Lua (admin tools, triggers, ...)
		kTeleportReasonSpawn = 1,      // PlayerSpawn / first placement after joining
		kTeleportReasonMapChange = 2,  // the map changed (new slot)
		kTeleportReasonVehicle = 3,    // seated: entered a seat or moved with it (no MC fall damage until a teleport for another reason; leaving sends SetPos)
	};

	enum TeleportFlags : std::uint32_t
	{
		kTeleportKeepLook = 0x1,  // keep the player's yaw/pitch (ignore the event's)
	};

	struct HostEvent
	{
		std::uint16_t type;       // HostEventType
		std::uint16_t code;       // HurtKind / TeleportReason
		std::uint32_t entId;      // GMod entity index involved (attacker), 0 = none
		std::uint64_t steamId;    // SteamID64 of the target player
		std::uint32_t requestId;  // teleport / respawn: echoed in kEvTeleportAck (non-zero)
		std::uint32_t flags;      // HurtFlags / TeleportFlags
		std::int32_t  a;          // hurt: GMod damage * 100
		std::uint32_t worldId;    // teleport / respawn: the map x/y/z belong to (0: don't check)
		double        x, y, z;    // teleport / respawn: feet, MC coords
		float         yaw, pitch; // teleport / respawn: MC degrees
	};
	inline constexpr std::uint64_t kHeType = 0x00;
	inline constexpr std::uint64_t kHeCode = 0x02;
	inline constexpr std::uint64_t kHeEntId = 0x04;
	inline constexpr std::uint64_t kHeSteamId = 0x08;
	inline constexpr std::uint64_t kHeRequestId = 0x10;
	inline constexpr std::uint64_t kHeFlags = 0x14;
	inline constexpr std::uint64_t kHeA = 0x18;
	inline constexpr std::uint64_t kHeWorldId = 0x1C;
	inline constexpr std::uint64_t kHeX = 0x20;
	inline constexpr std::uint64_t kHeY = 0x28;
	inline constexpr std::uint64_t kHeZ = 0x30;
	inline constexpr std::uint64_t kHeYaw = 0x38;
	inline constexpr std::uint64_t kHePitch = 0x3C;
	inline constexpr std::uint64_t kHostEventBytes = 64;
	static_assert(offsetof(HostEvent, type) == kHeType);
	static_assert(offsetof(HostEvent, code) == kHeCode);
	static_assert(offsetof(HostEvent, entId) == kHeEntId);
	static_assert(offsetof(HostEvent, steamId) == kHeSteamId);
	static_assert(offsetof(HostEvent, requestId) == kHeRequestId);
	static_assert(offsetof(HostEvent, flags) == kHeFlags);
	static_assert(offsetof(HostEvent, a) == kHeA);
	static_assert(offsetof(HostEvent, worldId) == kHeWorldId);
	static_assert(offsetof(HostEvent, x) == kHeX);
	static_assert(offsetof(HostEvent, y) == kHeY);
	static_assert(offsetof(HostEvent, z) == kHeZ);
	static_assert(offsetof(HostEvent, yaw) == kHeYaw);
	static_assert(offsetof(HostEvent, pitch) == kHePitch);
	static_assert(sizeof(HostEvent) == kHostEventBytes);
	inline constexpr std::uint64_t kHostEventRingBytes = 0x10080;
	static_assert(kHostEventRingBytes == kHrData + kHostEventBytes * kHostEventRingEntries);

	// ---- player map ---------------------------------------------------------------------------
	// GMod players <-> Minecraft players. Both are seqlocked tables, one writer each.
	//  * Each MC client reports its own identity (uuid, name) on its CLIENT link (McIdentity).
	//    The GMod client sends it to the GMod server, which lists every GMod player in HostPlayers
	//    with the identity its MC reported (kHostPlayerHasMc). The host does NOT choose names and
	//    does not launch Minecraft with --username (v12 did; that only worked offline).
	//  * The MC server lists who is online (McPlayers) and maps each to a SteamID64: first the
	//    HostPlayer whose mcUuid equals the player's UUID; failing that, the one whose mcName equals
	//    the player's name, case-insensitively (offline / dev servers, where the server's offline
	//    UUID differs from the one the client made up). kMcPlayerMapped says a match was found.
	//  * v14: HostPlayers is slot-indexed. players[i] keeps its index i for as long as that GMod
	//    player is connected (e.g. i = entIndex - 1); a record with steamId 0 is an empty slot, and
	//    count is one past the highest slot in use. Water grid i (kSvOffWaterGrids) belongs to
	//    players[i].
	inline constexpr std::uint32_t kMaxPlayers = 128;

	enum HostPlayerFlags : std::uint32_t
	{
		kHostPlayerAlive = 0x1,
		kHostPlayerBot = 0x2,
		kHostPlayerHasMc = 0x4,  // mcUuid / mcName are what this player's Minecraft reported
		kHostPlayerNoclip = 0x8,  // v22: GMod has this player in noclip (MOVETYPE_NOCLIP; GMod's own
		                          // PlayerNoClip / sbox_noclip / admin rules granted it). Its Minecraft
		                          // player flies through everything (no physics), takes no fall damage
		                          // and is never kicked for flying; survival HUD and building stay.
		kHostPlayerCarried = 0x10,  // v30 (P6i): its Minecraft rides a moving GMod entity (validated by
		                            // the GMod server). The MC server then keeps moves next to GMod
		                            // entities that its stale copy of them would reject.
	};

	struct HostPlayer
	{
		std::uint64_t steamId;     // SteamID64 (bots: 90071996842377216 + index, as GMod reports)
		std::uint32_t entIndex;    // GMod player entity index
		std::uint32_t flags;       // HostPlayerFlags
		std::uint8_t  mcUuid[16];  // McIdentity::uuid of this player's Minecraft (big-endian); 0s: none
		char          mcName[24];  // McIdentity::name of this player's Minecraft; empty: none
		std::uint8_t  reserved[8];
	};
	inline constexpr std::uint64_t kHpSteamId = 0x00;
	inline constexpr std::uint64_t kHpEntIndex = 0x08;
	inline constexpr std::uint64_t kHpFlags = 0x0C;
	inline constexpr std::uint64_t kHpMcUuid = 0x10;
	inline constexpr std::uint64_t kHpMcName = 0x20;
	inline constexpr std::uint64_t kHostPlayerBytes = 64;
	static_assert(offsetof(HostPlayer, steamId) == kHpSteamId);
	static_assert(offsetof(HostPlayer, entIndex) == kHpEntIndex);
	static_assert(offsetof(HostPlayer, flags) == kHpFlags);
	static_assert(offsetof(HostPlayer, mcUuid) == kHpMcUuid);
	static_assert(offsetof(HostPlayer, mcName) == kHpMcName);
	static_assert(sizeof(HostPlayer::mcName) == kMcNameBytes);
	static_assert(sizeof(HostPlayer) == kHostPlayerBytes);

	struct HostPlayers
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint8_t  pad[0x40 - 8];
		HostPlayer    players[kMaxPlayers];
	};
	inline constexpr std::uint64_t kPtSeq = 0x00;
	inline constexpr std::uint64_t kPtCount = 0x04;
	inline constexpr std::uint64_t kPtRecords = 0x40;
	inline constexpr std::uint64_t kHostPlayersBytes = 0x2040;
	static_assert(offsetof(HostPlayers, seq) == kPtSeq);
	static_assert(offsetof(HostPlayers, count) == kPtCount);
	static_assert(offsetof(HostPlayers, players) == kPtRecords);
	static_assert(sizeof(HostPlayers) == kHostPlayersBytes);

	enum McPlayerFlags : std::uint32_t
	{
		kMcPlayerDead = 0x1,
		kMcPlayerMapped = 0x2,  // steamId was resolved through HostPlayers
		kMcPlayerHeld = 0x4,    // a teleport / respawn request for this player is still unacked
		kMcPlayerInWater = 0x8, // v14: in water as the MC server sees it (its own or the host's grids)
	};

	struct McPlayer
	{
		std::uint8_t  uuid[16];   // Minecraft UUID, big-endian (most significant byte first)
		std::uint64_t steamId;    // from HostPlayers (see above); 0 if unmapped
		std::uint32_t entityId;   // Minecraft entity id
		std::uint32_t flags;      // McPlayerFlags
		float         health;
		float         maxHealth;
		char          name[24];   // Minecraft name, NUL-terminated
	};
	inline constexpr std::uint64_t kMpUuid = 0x00;
	inline constexpr std::uint64_t kMpSteamId = 0x10;
	inline constexpr std::uint64_t kMpEntityId = 0x18;
	inline constexpr std::uint64_t kMpFlags = 0x1C;
	inline constexpr std::uint64_t kMpHealth = 0x20;
	inline constexpr std::uint64_t kMpMaxHealth = 0x24;
	inline constexpr std::uint64_t kMpName = 0x28;
	inline constexpr std::uint64_t kMcPlayerBytes = 64;
	static_assert(offsetof(McPlayer, uuid) == kMpUuid);
	static_assert(offsetof(McPlayer, steamId) == kMpSteamId);
	static_assert(offsetof(McPlayer, entityId) == kMpEntityId);
	static_assert(offsetof(McPlayer, flags) == kMpFlags);
	static_assert(offsetof(McPlayer, health) == kMpHealth);
	static_assert(offsetof(McPlayer, maxHealth) == kMpMaxHealth);
	static_assert(offsetof(McPlayer, name) == kMpName);
	static_assert(sizeof(McPlayer::name) == kMcNameBytes);
	static_assert(sizeof(McPlayer) == kMcPlayerBytes);

	struct McPlayers
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint8_t  pad[0x40 - 8];
		McPlayer      players[kMaxPlayers];
	};
	inline constexpr std::uint64_t kMcPlayersBytes = 0x2040;
	static_assert(offsetof(McPlayers, seq) == kPtSeq);
	static_assert(offsetof(McPlayers, count) == kPtCount);
	static_assert(offsetof(McPlayers, players) == kPtRecords);
	static_assert(sizeof(McPlayers) == kMcPlayersBytes);

	// ---- weapon sets @kSvOffMcWeaponSets (MC server -> host; v17 hybrid mode) ----------------
	// Each online Minecraft player's gmodcraft:gmod_weapon stacks (main inventory, hotbar, offhand
	// and the stack on the cursor). Set i belongs to McPlayers::players[i] (same order, written in
	// the same tick), and carries its steamId so a reader never pairs it with the wrong player when
	// the two tables were read a tick apart: match by steamId (0 = unmapped player, ignore). Each set
	// is seqlocked on its own; count (sets in use) is written with release after them. A set is
	// rewritten when it changes and every 2 s. The GMod server makes the player's GMod weapons equal
	// the set (the MC inventory owns possession): it gives what's missing and strips what's gone.
	// One entry per stack, in inventory order; several stacks of one class may be listed.
	inline constexpr std::uint32_t kMaxWeaponsPerPlayer = 40;

	struct WeaponEntry
	{
		std::uint32_t hash;   // FNV-1a 32 of the lowercased class
		std::int32_t  clip1;  // the clip saved in the stack (-1: none / unknown)
	};
	inline constexpr std::uint64_t kWeHash = 0x00;
	inline constexpr std::uint64_t kWeClip1 = 0x04;
	inline constexpr std::uint64_t kWeaponEntryBytes = 8;
	static_assert(offsetof(WeaponEntry, hash) == kWeHash);
	static_assert(offsetof(WeaponEntry, clip1) == kWeClip1);
	static_assert(sizeof(WeaponEntry) == kWeaponEntryBytes);

	enum McWeaponSetFlags : std::uint32_t
	{
		kWeaponSetDead = 0x1,  // the player is dead (the set still holds what keepInventory keeps)
	};

	struct McWeaponSet
	{
		std::uint32_t seq;
		std::uint32_t count;          // entries in use (<= kMaxWeaponsPerPlayer; more stacks are cut)
		std::uint32_t heldHash;       // class hash of the gmod_weapon in the main hand, 0 = none
		std::uint32_t lastRequestId;  // the last kHostEvWeaponGive / Take for this player the MC server
		                              // has carried out (0 after a new link session)
		std::uint64_t steamId;        // McPlayers::players[i].steamId (0: unmapped)
		std::uint32_t flags;          // McWeaponSetFlags
		std::uint32_t reserved;
		WeaponEntry   entries[kMaxWeaponsPerPlayer];
	};
	inline constexpr std::uint64_t kWsSeq = 0x00;
	inline constexpr std::uint64_t kWsCount = 0x04;
	inline constexpr std::uint64_t kWsHeldHash = 0x08;
	inline constexpr std::uint64_t kWsLastRequestId = 0x0C;
	inline constexpr std::uint64_t kWsSteamId = 0x10;
	inline constexpr std::uint64_t kWsFlags = 0x18;
	inline constexpr std::uint64_t kWsEntries = 0x20;
	inline constexpr std::uint64_t kMcWeaponSetBytes = 0x160;
	static_assert(offsetof(McWeaponSet, seq) == kWsSeq);
	static_assert(offsetof(McWeaponSet, count) == kWsCount);
	static_assert(offsetof(McWeaponSet, heldHash) == kWsHeldHash);
	static_assert(offsetof(McWeaponSet, lastRequestId) == kWsLastRequestId);
	static_assert(offsetof(McWeaponSet, steamId) == kWsSteamId);
	static_assert(offsetof(McWeaponSet, flags) == kWsFlags);
	static_assert(offsetof(McWeaponSet, entries) == kWsEntries);
	static_assert(sizeof(McWeaponSet) == kMcWeaponSetBytes);
	static_assert(kMcWeaponSetBytes == kWsEntries + kWeaponEntryBytes * kMaxWeaponsPerPlayer);

	struct McWeaponSets
	{
		std::uint32_t reserved0;
		std::uint32_t count;          // sets in use; the first McPlayers::count are its players, in order
		std::uint8_t  pad[0x40 - 8];
		McWeaponSet   sets[kMaxPlayers];
	};
	inline constexpr std::uint64_t kWtsCount = 0x04;
	inline constexpr std::uint64_t kWtsSets = 0x40;
	inline constexpr std::uint64_t kMcWeaponSetsBytes = 0xB040;
	static_assert(offsetof(McWeaponSets, count) == kWtsCount);
	static_assert(offsetof(McWeaponSets, sets) == kWtsSets);
	static_assert(sizeof(McWeaponSets) == kMcWeaponSetsBytes);

	// ---- MC entities @kSvOffMcEntities (MC server -> host, seqlock; v27) --------------------
	// Minecraft's mobs, animals, minecarts and boats within kMcEntityRange blocks of the Minecraft
	// players that GMod players play as (mapped), nearest first, at most kMaxMcEntities. With nobody
	// mapped online the table is empty (test runs with dev commands on list every loaded one, by id).
	// Never players and never the host actor stand-ins (GMod's own NPCs). The host gives each an invisible GMod body (a
	// proxy) that follows it: bullets, melee and explosions on it come back as kHostEvHurtMcEntity.
	// Written every MC server tick; header as McPlayers (kPtSeq / kPtCount / kPtRecords).
	inline constexpr std::uint32_t kMaxMcEntities = 256;
	inline constexpr std::uint32_t kMcEntityRange = 64;  // blocks

	enum McEntityCategory : std::uint16_t
	{
		kMcEntOther = 0,
		kMcEntHostile = 1,  // a monster (Enemy): zombies, skeletons, creepers, ...
		kMcEntPassive = 2,  // other living entities: animals, villagers, golems, ...
		kMcEntVehicle = 3,  // minecarts and boats
	};

	// v31: McEntity::category also carries the body yaw (yBodyRot for living entities, else getYRot),
	// quantized to kMcEntYawSteps steps in bits kMcEntYawShift..15: q = round(wrap360(yaw) / 360 *
	// kMcEntYawSteps) mod kMcEntYawSteps; yaw = q * 360 / kMcEntYawSteps (MC degrees). Bits 4-5 spare.
	// Every reader masks: category = raw & kMcEntCategoryMask; q = (raw >> kMcEntYawShift) & (steps - 1).
	inline constexpr std::uint32_t kMcEntCategoryMask = 0xF;
	inline constexpr std::uint32_t kMcEntYawShift = 6;
	inline constexpr std::uint32_t kMcEntYawSteps = 1024;

	enum McEntityFlags : std::uint16_t
	{
		kMcEntDead = 0x1,   // dying (death animation): no proxy hits any more
	};

	struct McEntity
	{
		std::uint32_t entityId;   // Minecraft entity id (kHostEvHurtMcEntity::requestId)
		std::uint16_t category;   // McEntityCategory in the low bits, body yaw above (v31, kMcEntYawShift)
		std::uint16_t flags;      // McEntityFlags
		std::uint32_t typeHash;   // FNV-1a 32 of the entity type id ("minecraft:zombie")
		float         width;      // hitbox, blocks
		double        x, y, z;    // feet (bottom centre of the hitbox), MC coords
		float         vx, vy, vz; // blocks per tick
		float         health;     // 0 for non-living (carts, boats)
		float         maxHealth;
		float         height;     // hitbox, blocks
	};
	inline constexpr std::uint64_t kMenEntityId = 0x00;
	inline constexpr std::uint64_t kMenCategory = 0x04;
	inline constexpr std::uint64_t kMenFlags = 0x06;
	inline constexpr std::uint64_t kMenTypeHash = 0x08;
	inline constexpr std::uint64_t kMenWidth = 0x0C;
	inline constexpr std::uint64_t kMenX = 0x10;
	inline constexpr std::uint64_t kMenY = 0x18;
	inline constexpr std::uint64_t kMenZ = 0x20;
	inline constexpr std::uint64_t kMenVx = 0x28;
	inline constexpr std::uint64_t kMenVy = 0x2C;
	inline constexpr std::uint64_t kMenVz = 0x30;
	inline constexpr std::uint64_t kMenHealth = 0x34;
	inline constexpr std::uint64_t kMenMaxHealth = 0x38;
	inline constexpr std::uint64_t kMenHeight = 0x3C;
	inline constexpr std::uint64_t kMcEntityBytes = 0x40;
	static_assert(offsetof(McEntity, entityId) == kMenEntityId);
	static_assert(offsetof(McEntity, category) == kMenCategory);
	static_assert(offsetof(McEntity, flags) == kMenFlags);
	static_assert(offsetof(McEntity, typeHash) == kMenTypeHash);
	static_assert(offsetof(McEntity, width) == kMenWidth);
	static_assert(offsetof(McEntity, x) == kMenX);
	static_assert(offsetof(McEntity, y) == kMenY);
	static_assert(offsetof(McEntity, z) == kMenZ);
	static_assert(offsetof(McEntity, vx) == kMenVx);
	static_assert(offsetof(McEntity, vy) == kMenVy);
	static_assert(offsetof(McEntity, vz) == kMenVz);
	static_assert(offsetof(McEntity, health) == kMenHealth);
	static_assert(offsetof(McEntity, maxHealth) == kMenMaxHealth);
	static_assert(offsetof(McEntity, height) == kMenHeight);
	static_assert(sizeof(McEntity) == kMcEntityBytes);

	struct McEntities
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint8_t  pad[0x40 - 8];
		McEntity      entities[kMaxMcEntities];
	};
	inline constexpr std::uint64_t kMcEntitiesBytes = 0x4040;
	static_assert(offsetof(McEntities, seq) == kPtSeq);
	static_assert(offsetof(McEntities, count) == kPtCount);
	static_assert(offsetof(McEntities, entities) == kPtRecords);
	static_assert(sizeof(McEntities) == kMcEntitiesBytes);

	// ---- held MC entities @kSvOffHeldMcEntities (host -> MC server, seqlock; v29) ----------
	// T2 (authority handoff): the Minecraft entities whose GMod proxy GMod owns right now: held by a
	// physgun / gravgun, frozen, or constrained (rope, balloon, weld). GMod simulates them (collision
	// stays in GMod) and Minecraft only mirrors: each listed entity (overworld, by McEntity::entityId)
	// is pinned to x/y/z with motion vx/vy/vz and (v32) turned to yaw / pitch, its AI and gravity paused (never saved: a transient set,
	// not NoAI / NoGravity). The tick an entity drops out of the table Minecraft releases it: its last
	// vx/vy/vz once (the throw), fall distance reset, AI and gravity back. No link, no fresh write
	// for kHeldStaleMs (the host stopped writing): everything is released.
	// Written by the host at 20 Hz (also when empty); header as McPlayers (kPtSeq / kPtCount / kPtRecords).
	inline constexpr std::uint32_t kMaxHeldMcEntities = 64;
	inline constexpr std::uint32_t kHeldStaleMs = 1000;

	enum HeldMcEntityFlags : std::uint32_t
	{
		kHeldByPhysgun = 0x1,     // a physgun or gravgun holds it (holderSteamId)
		kHeldFrozen = 0x2,        // frozen in place (physgun freeze)
		kHeldConstrained = 0x4,   // constrained (rope, balloon, weld, ...)
	};

	struct HeldMcEntity
	{
		std::uint32_t entityId;       // Minecraft entity id (McEntity::entityId)
		std::uint32_t flags;          // HeldMcEntityFlags
		std::uint64_t holderSteamId;  // SteamID64 of the holding GMod player (0: none / not held)
		double        x, y, z;        // feet (bottom centre of the hitbox), MC coords (the map's slot, oy included)
		float         vx, vy, vz;     // MC blocks per tick
		float         yaw;            // v32: the GMod body's facing, MC degrees (yRot / yHeadRot / yBodyRot); NaN: keep MC's
		float         pitch;          // v32: its pitch, MC degrees (xRot, positive down, clamped to +-90 by MC); NaN: keep MC's
		std::uint8_t  pad[0x04];
	};
	inline constexpr std::uint64_t kHmeEntityId = 0x00;
	inline constexpr std::uint64_t kHmeFlags = 0x04;
	inline constexpr std::uint64_t kHmeHolderSteamId = 0x08;
	inline constexpr std::uint64_t kHmeX = 0x10;
	inline constexpr std::uint64_t kHmeY = 0x18;
	inline constexpr std::uint64_t kHmeZ = 0x20;
	inline constexpr std::uint64_t kHmeVx = 0x28;
	inline constexpr std::uint64_t kHmeVy = 0x2C;
	inline constexpr std::uint64_t kHmeVz = 0x30;
	inline constexpr std::uint64_t kHmeYaw = 0x34;
	inline constexpr std::uint64_t kHmePitch = 0x38;
	inline constexpr std::uint64_t kHeldMcEntityBytes = 0x40;
	static_assert(offsetof(HeldMcEntity, entityId) == kHmeEntityId);
	static_assert(offsetof(HeldMcEntity, flags) == kHmeFlags);
	static_assert(offsetof(HeldMcEntity, holderSteamId) == kHmeHolderSteamId);
	static_assert(offsetof(HeldMcEntity, x) == kHmeX);
	static_assert(offsetof(HeldMcEntity, y) == kHmeY);
	static_assert(offsetof(HeldMcEntity, z) == kHmeZ);
	static_assert(offsetof(HeldMcEntity, vx) == kHmeVx);
	static_assert(offsetof(HeldMcEntity, vy) == kHmeVy);
	static_assert(offsetof(HeldMcEntity, vz) == kHmeVz);
	static_assert(offsetof(HeldMcEntity, yaw) == kHmeYaw);
	static_assert(offsetof(HeldMcEntity, pitch) == kHmePitch);
	static_assert(sizeof(HeldMcEntity) == kHeldMcEntityBytes);

	struct HeldMcEntities
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint8_t  pad[0x40 - 8];
		HeldMcEntity  entities[kMaxHeldMcEntities];
	};
	inline constexpr std::uint64_t kHeldMcEntitiesBytes = 0x1040;
	static_assert(offsetof(HeldMcEntities, seq) == kPtSeq);
	static_assert(offsetof(HeldMcEntities, count) == kPtCount);
	static_assert(offsetof(HeldMcEntities, entities) == kPtRecords);
	static_assert(sizeof(HeldMcEntities) == kHeldMcEntitiesBytes);

	// ---- event ring @kSvOffEventRing (MC server -> host) -----------------------------------
	inline constexpr std::uint32_t kEventRingEntries = 1024;  // power of two
	inline constexpr std::uint64_t kErHead = 0x00;  // u64 events ever written (MC)
	inline constexpr std::uint64_t kErTail = 0x40;  // u64 events ever consumed (host)
	inline constexpr std::uint64_t kErData = 0x80;

	enum McEventType : std::uint32_t
	{
		kEvHitActor = 1,    // entId, steamId = the hitting player (0: not a player), a = MC damage (after
		                    // MC's own modifiers), b/c = knockback dir x/z (MC), d = knockback strength,
		                    // flags = HitFlags, weapon = HitWeapon
		kEvPlayerDied = 2,  // the Minecraft player steamId died: kill the GMod player. entId = attacker (0:
		                    // none): a host actor's entId, or (v14) the HostPlayers entIndex of the
		                    // Minecraft player who gets the kill
		kEvExplosion = 3,   // a Minecraft explosion (TNT, creeper, ...): a/b/c = centre (MC coords), d = radius (blocks)
		kEvArrowStuck = 4,  // an arrow stuck in a host actor: entId, steamId = shooter, a/b/c = where it hit
		                    // (MC coords), d = flight yaw, flags = flight pitch (float bits),
		                    // weapon = arrow texture (0 plain, 1 tipped, 2 spectral)
		                    // 5 was SkyCraft's kEvSkillUse (Skyrim skills): removed
		kEvTeleportAck = 6,       // answers kHostEvTeleport / kHostEvRespawn: steamId, requestId,
		                          // result = TeleportResult, a/b/c = the player's feet now (MC coords)
		kEvPlayerRespawned = 7,   // Minecraft respawned the player steamId on its own (its death
		                          // screen / immediate respawn), a/b/c = where (MC coords). The host
		                          // decides where the GMod player goes and answers with
		                          // kHostEvTeleport / kHostEvRespawn.
		kEvWorldOpened = 8,       // v14: McServerInfo was (re)written: answers kHostEvOpenToLan
		                          // (requestId, result = OpenResult) or reports a change on its own
		                          // (requestId 0: a dedicated server starting, the player's own "Open to
		                          // LAN", e4mc's address arriving). a = port, flags = McServerInfoFlags.
		kEvJoinResult = 9,        // v14, CLIENT link event ring only: how a JoinInfo instruction (or the
		                          // player's /join, /leave) ended. requestId = JoinInfo::joinId (0: the
		                          // player's own), result = JoinResult; JoinStatus has the reason text.
		kEvDevCommandResult = 10, // v14, dev only: answers kHostEvDevCommand. requestId, result =
		                          // DevCommandResult, flags = the command's result count (the int
		                          // Minecraft's command returns, as int32 bits), weapon = how many
		                          // kEvDevCommandText slots follow (<= kDevOutputMaxChunks) with its
		                          // first output line, truncated to kDevOutputMaxBytes.
		kEvDevCommandText = 11,   // v14: the next kDevOutputChunkBytes of output UTF-8 at kMeText (NUL-
		                          // padded); only directly after kEvDevCommandResult, published together
		                          // with it.
		kEvProjectileHit = 12,    // v15: a Minecraft projectile hit host collision flagged kTriDynamic (a
		                          // GMod entity's; never sent for the static world or MC blocks). entId 0
		                          // (the host finds the entity: a short trace through the hit point),
		                          // steamId = the shooter (0: not a mapped player), a/b/c = where it hit
		                          // (MC coords), d = flight yaw and flags = flight pitch (float bits),
		                          // both as kEvArrowStuck, weapon = ProjectileKind, requestId = MC damage
		                          // (float bits, after MC's speed/crit rules, before the host's scale),
		                          // result = speed (float bits, blocks per tick)
		// v16, redstone <-> Wiremod bridge (only while ServerState has kServerWiremod; only bridges
		// inside the current map's slot). a/b/c = the bridge block (integer MC coords as floats,
		// exact: slot coords stay far below 2^24), requestId = worldId, flags = input levels
		// (bits 4i..4i+3 = BridgeFace i, 0-15; a driven face reads 0).
		kEvBridgePlaced = 13,     // a bridge exists: placed, its chunk loaded, or announced again
		                          // (new link session, map change, kServerWiremod turned on). The host
		                          // makes its wire entity (or keeps the one it has: idempotent).
		kEvBridgeRemoved = 14,    // the bridge was broken: flags 0. Not sent when its chunk unloads (the
		                          // host keeps the entity and its wires; kEvBridgePlaced comes again on load).
		kEvBridgeInputs = 15,     // the redstone levels on its undriven faces changed (at most one
		                          // per bridge per MC tick, latest wins).
		// v19, admin commands and demo builds. Demo events: a/b/c = the origin block (integer MC
		// coords as floats, exact), d = yaw quarter (0-3, clockwise from south), requestId = the
		// instance id (persisted, never reused), weapon = DemoKind, result = worldId, steamId = who
		// placed it (0: the Minecraft console / an unmapped player).
		kEvAdminResult = 16,      // answers kHostEvAdminCommand: requestId, result = AdminResult,
		                          // flags = the instance id it made / cleared (0: none), a = how many
		                          // instances it touched.
		kEvDemoPlaced = 17,       // a demo instance exists: just placed (by GMod or /gmodcraft demo),
		                          // or announced again (new link session, map, kAdminDemoAnnounce;
		                          // entId = 1 then). The host spawns its GMod parts (or keeps them).
		kEvDemoCleared = 18,      // the instance was cleared and the world restored: remove its parts.
		kEvDemoSyncDone = 19,     // the end of an announce batch: result = worldId, a = how many were
		                          // announced. The host removes parts of instances not announced.
		kEvBridgeLink = 20,       // v25: a bridge's map entity link (after kEvBridgePlaced, and when it
		                          // changes): a/b/c = the bridge block, requestId = the link's worldId (only
		                          // sent for the current map), entId = 1 linked / 0 unlinked, weapon = the
		                          // MapCreationID, flags = BridgeLinkKind | BridgeLinkMode << 8.
		kEvFireContact = 21,      // v28 (F1): Minecraft fire / lava touches a host entity's collision (the
		                          // dynamic layer: a GMod prop): a/b/c = the burning block's centre (MC
		                          // coords), requestId = worldId, weapon = BlockHazard (kHazardFire /
		                          // kHazardLava). The host finds the entity there and sets it on fire.
		                          // At most kFireContactsPerScan per scan, each cell at most every 2 s.
		// 22-23 are S1's.
		kEvPropRequest = 24,      // v33 (P1): a prop item request, answered by one kHostEvPropResult with the
		                          // requestId (non-zero, MC's counter). steamId = the player, flags = PropOp |
		                          // skin << kPropSkinShift, a/b/c = the hit point (MC coords). Pickup: entId =
		                          // the GMod entity (the hit triangle's kTriEntityShift index), no text.
		                          // Place: d = the player's yaw (MC degrees; the prop faces them), result =
		                          // colour (RGBA, r in the top byte), weapon = how many kEvPropText slots
		                          // follow (1..kPropMcMaxChunks), published together.
		kEvPropText = 25,         // v33: the next kPropMcChunkBytes of the prop text at kMeText; only directly
		                          // after kEvPropRequest.
	};
	inline constexpr std::uint32_t kFireContactsPerScan = 8;

	enum AdminResult : std::uint32_t
	{
		kAdminOk = 0,
		kAdminOccupied = 1,       // the demo's box isn't empty (air) and kAdminForce wasn't given
		kAdminUnknownDemo = 2,
		kAdminOutsideSlot = 3,    // not inside the current map's slot, or no slot yet
		kAdminNoInstance = 4,     // kAdminDemoClear: no such live instance
		kAdminMalformed = 5,      // bad code / missing text slot / bad map
		kAdminFailed = 6,         // Minecraft couldn't do it (logged), nothing changed
		kAdminNotAllowed = 7,     // v20: neither kAdminByAdmin nor kAdminEveryone
		kAdminNothing = 8,        // v20: nothing to do (no dug cell there, no MC block to break, nothing to undo,
		                          // or the block changed since: undo refused)
		kAdminBadBlock = 9,       // v20: not a block state Minecraft knows, or an operator block
		kAdminLiquid = 10,        // v20: a liquid (or waterlogged) without kAdminAllowLiquid
		kAdminBusy = 11,          // v23: another slot job is running
		kAdminOutOfRange = 12,    // v23: the offset would leave the int range (the dimension check runs in the job)
		kAdminSlotOccupied = 13,  // v23: a Minecraft player is in the slot
		kAdminBadRule = 14,       // v24 kAdminSetRules: unknown key or bad value (a = the pair); nothing changed
		kAdminUnsupported = 15,   // v34: not on this server (world ops on an integrated / single-player server)
	};

	// The demo builds (kEvDemoPlaced::weapon). Names (kHostEvAdminText): rails, redstone, range,
	// ramp, arena, digwall. Each fits kDemoMaxX x kDemoMaxY x kDemoMaxZ blocks.
	enum DemoKind : std::uint32_t
	{
		kDemoRails = 1,      // rail loop: powered + detector rails, two minecarts
		kDemoRedstone = 2,   // redstone <-> Wiremod: lever, lamp and a clock around a bridge block
		kDemoRange = 3,      // projectile range: a dispenser with arrows on a clock
		kDemoRamp = 4,       // vehicle ramp and jump (GMod: a jeep)
		kDemoArena = 5,      // a wall ring (GMod: NPCs inside)
		kDemoDigWall = 6,    // marker frame on a GMod wall: where to dig
	};
	inline constexpr std::uint32_t kDemoMaxX = 32;
	inline constexpr std::uint32_t kDemoMaxY = 16;
	inline constexpr std::uint32_t kDemoMaxZ = 32;

	// Faces of a redstone bridge (Minecraft's Direction order). Wire port names: Down Up North
	// South West East (MC directions: North = -Z, East = +X).
	enum BridgeFace : std::uint32_t
	{
		kFaceDown = 0,
		kFaceUp = 1,
		kFaceNorth = 2,
		kFaceSouth = 3,
		kFaceWest = 4,
		kFaceEast = 5,
	};
	inline constexpr std::uint32_t kBridgeFaces = 6;

	// v25 kEvBridgeLink: what the linked map entity is, and which way the signal goes.
	enum BridgeLinkKind : std::uint32_t
	{
		kLinkButton = 1,      // func_button, func_rot_button: in = pressed, out = Press
		kLinkMomentary = 2,   // momentary_rot_button: in = held
		kLinkTrigger = 3,     // trigger_once, trigger_multiple: in = occupied
		kLinkDoor = 4,        // func_door(_rotating), prop_door_rotating: in = open, out = Open / Close
		kLinkMoveLinear = 5,  // func_movelinear: out = Open / Close
		kLinkRelay = 6,       // logic_relay: in = a pulse when it triggers
		kLinkLight = 7,       // light, light_spot, light_dynamic: out = TurnOn / TurnOff
		kLinkSprite = 8,      // env_sprite: out = ShowSprite / HideSprite
	};
	enum BridgeLinkMode : std::uint32_t
	{
		kLinkIn = 1,   // the entity drives redstone: every face at 15 while pressed / open / occupied
		kLinkOut = 2,  // redstone drives the entity: any powered face = on, none = off
	};
	inline constexpr std::uint32_t kBridgeLevelBits = 4;

	// What a kEvProjectileHit was (the host picks damage type and push from it).
	enum ProjectileKind : std::uint32_t
	{
		kProjArrow = 1,
		kProjSpectralArrow = 2,
		kProjTippedArrow = 3,
		kProjTrident = 4,
		kProjSnowball = 5,
		kProjEgg = 6,
		kProjThrown = 7,         // other thrown items: potions, ender pearls, experience bottles
		kProjSmallFireball = 8,  // blaze / dispenser fire charge (big fireballs explode: kEvExplosion)
	};
	// v28 (F1): kEvProjectileHit::weapon = ProjectileKind | kProjOnFire when the projectile burns (a flame
	// arrow, a fire charge): the host sets what it hit on fire. Mask the kind with kProjKindMask.
	inline constexpr std::uint32_t kProjOnFire = 0x100;
	inline constexpr std::uint32_t kProjKindMask = 0xFF;

	// v28 (F1) kHostEvFire::code
	enum FireCause : std::uint16_t
	{
		kFireBurning = 1,    // a burning entity (IsOnFire, env_fire, a burning ragdoll): a = 1-2
		kFireExplosion = 2,  // an explosion with fire (gas can, explosive barrel): a = up to 3
	};
	inline constexpr std::uint32_t kFireBlocksPerEvent = 3;
	inline constexpr std::uint32_t kFireBlocksPerTick = 6;

	enum DevCommandResult : std::uint32_t
	{
		kDevCommandOk = 0,         // ran and succeeded
		kDevCommandFailed = 1,     // ran (or failed to parse) and Minecraft reported a failure
		kDevCommandDisabled = 2,   // dev commands are off on this server: not run
		kDevCommandMalformed = 3,  // bad sequence (missing text slots, too long, bad UTF-8): not run
	};

	// kEvDevCommandResult output text: chunks in kEvDevCommandText slots.
	inline constexpr std::uint64_t kMeText = 0x04;
	inline constexpr std::uint32_t kDevOutputChunkBytes = 44;
	inline constexpr std::uint32_t kDevOutputMaxChunks = 3;
	inline constexpr std::uint32_t kDevOutputMaxBytes = 132;

	enum OpenResult : std::uint32_t
	{
		kOpenOk = 0,           // opened on port a
		kOpenFailed = 1,       // couldn't open (port taken, ...); still closed
		kOpenAlreadyOpen = 2,  // it already accepts players (a dedicated server, or opened before) on
		                       // port a; the requested port is ignored
	};

	enum TeleportResult : std::uint32_t
	{
		kTeleportOk = 0,           // done and confirmed by the player's client
		kTeleportNoPlayer = 1,     // no online Minecraft player maps to steamId
		kTeleportTimeout = 2,      // moved on the server, but the client didn't confirm in time
		kTeleportWrongWorld = 3,   // worldId isn't the map the MC server is serving
		kTeleportBadPosition = 4,  // NaN / infinite / outside the world
	};

	enum HitFlags : std::uint32_t
	{
		kHitCritical = 0x1,
		kHitProjectile = 0x2,
		kHitSweep = 0x4,
		kHitFire = 0x8,
	};

	// What landed a kEvHitActor (the host plays that weapon class's impact effect and sounds).
	enum HitWeapon : std::uint32_t
	{
		kWeaponUnarmed = 0,
		kWeaponBlade = 1,   // swords
		kWeaponAxe = 2,
		kWeaponBlunt = 3,   // maces, pickaxes, shovels, hoes, anything else held
		kWeaponPierce = 4,  // tridents, spears
		kWeaponArrow = 5,   // arrows and other projectiles
	};

	struct McEvent
	{
		std::uint32_t type;       // McEventType
		std::uint32_t entId;      // GMod entity index involved, 0 = none
		std::uint64_t steamId;    // SteamID64 of the Minecraft player involved, 0 = none / unmapped
		float         a, b, c, d;
		std::uint32_t flags;
		std::uint32_t weapon;     // HitWeapon for kEvHitActor
		std::uint32_t requestId;  // kEvTeleportAck: the request it answers
		std::uint32_t result;     // kEvTeleportAck: TeleportResult
	};
	inline constexpr std::uint64_t kMeType = 0x00;
	inline constexpr std::uint64_t kMeEntId = 0x04;
	inline constexpr std::uint64_t kMeSteamId = 0x08;
	inline constexpr std::uint64_t kMeA = 0x10;
	inline constexpr std::uint64_t kMeB = 0x14;
	inline constexpr std::uint64_t kMeC = 0x18;
	inline constexpr std::uint64_t kMeD = 0x1C;
	inline constexpr std::uint64_t kMeFlags = 0x20;
	inline constexpr std::uint64_t kMeWeapon = 0x24;
	inline constexpr std::uint64_t kMeRequestId = 0x28;
	inline constexpr std::uint64_t kMeResult = 0x2C;
	inline constexpr std::uint64_t kMcEventBytes = 48;
	static_assert(offsetof(McEvent, type) == kMeType);
	static_assert(offsetof(McEvent, entId) == kMeEntId);
	static_assert(offsetof(McEvent, steamId) == kMeSteamId);
	static_assert(offsetof(McEvent, a) == kMeA);
	static_assert(offsetof(McEvent, b) == kMeB);
	static_assert(offsetof(McEvent, c) == kMeC);
	static_assert(offsetof(McEvent, d) == kMeD);
	static_assert(offsetof(McEvent, flags) == kMeFlags);
	static_assert(offsetof(McEvent, weapon) == kMeWeapon);
	static_assert(offsetof(McEvent, requestId) == kMeRequestId);
	static_assert(offsetof(McEvent, result) == kMeResult);
	static_assert(sizeof(McEvent) == kMcEventBytes);
	inline constexpr std::uint64_t kEventRingBytes = 0xC080;
	static_assert(kEventRingBytes == kErData + kMcEventBytes * kEventRingEntries);
	static_assert(kMeText + kDevOutputChunkBytes == kMcEventBytes);
	static_assert(kDevOutputMaxBytes == kDevOutputChunkBytes * kDevOutputMaxChunks);
	static_assert(kHeText + kDevCommandChunkBytes == kHostEventBytes);
	static_assert(kDevCommandMaxBytes == kDevCommandChunkBytes * kDevCommandMaxChunks);
	// The client link's event ring (v14) is the same layout with kClEventRingEntries.
	static_assert(kClEventRingBytes == kErData + kMcEventBytes * kClEventRingEntries);
	static_assert((kClEventRingEntries & (kClEventRingEntries - 1)) == 0);

	// ---- block ring @kSvOffBlockRing (MC server -> host) -----------------------------------
	// Byte ring like the collision ring (8-aligned {u32 type, u32 payloadBytes} messages, kBlkPad =
	// skip to the start). The MC SERVER's own blocks, for host-side collision of GMod entities
	// (NPCs, props; P5) on a dedicated server too. Per 16^3 section, absolute MC section coords
	// (slot origin included), the full state of that section each time (not a diff): a later
	// message for a section replaces the earlier one. On a new session (or after kBlkClearAll) the
	// MC server re-sends every non-empty section it has loaded.
	inline constexpr std::uint64_t kBrHead = 0x00;  // u64 bytes ever written (MC)
	inline constexpr std::uint64_t kBrTail = 0x40;  // u64 bytes ever consumed (host)
	inline constexpr std::uint64_t kBrData = 0x80;
	inline constexpr std::uint64_t kBlockBitsBytes = 512;  // 4096 bits: bit x + 16z + 256y
	inline constexpr std::uint64_t kBlockShapeBytes = 4096;  // v26: one byte per block (index x + 16z + 256y)
	// v26 kBlkShapes byte: which octants (half-block cubes) of a block its collision shape fills
	// (at least half of the octant's volume). Bit dx + 2 dy + 4 dz (d = 0 the low half, 1 the high
	// half, MC axes). 0 = a full cube (or a shape that fills no octant: kept a full cube, as before).
	// A bottom slab = 0x33 (dy = 0: bits 0, 1, 4, 5), a top slab = 0xCC, a bottom stair facing north
	// (-z) = 0x33 | 0x0C (dy = 1, dz = 0: bits 2, 3).
	inline constexpr std::uint32_t kShapeBottomSlab = 0x33;
	inline constexpr std::uint32_t kShapeTopSlab = 0xCC;

	enum BlockMsgType : std::uint32_t
	{
		kBlkPad = 0,
		kBlkClearAll = 1,  // no payload: forget every section from this MC server (a full re-send follows)
		kBlkSolids = 2,    // RenSolids + kBlockBitsBytes bitset (none when count = 0): which blocks of the
		                   // section have a collision shape (what GMod entities should bump into)
		kBlkDug = 3,       // RenDug + kBlockBitsBytes bitset (none when count = 0): which cells of the
		                   // section were dug out of the host's world (RenDug::worldId's map)
		kBlkShapes = 4,    // v26: RenSolids (count = blocks that aren't full cubes) + kBlockShapeBytes
		                   // octant bytes (none when count = 0): the shapes of the section's solid blocks
		                   // that aren't full cubes (slabs, stairs, ...). Sent after the section's
		                   // kBlkSolids when it has such blocks or had them; refines those solid bits only
		kBlkMicro = 5,     // v36: RenSolids (count = microblock cells) + per cell {u16 index (x + 16z + 256y),
		                   // u8 n, n x 6 u8 box (x0 y0 z0 x1 y1 z1: eighths of the block, 0..8, half-open)}
		                   // (none when count = 0): the exact collision boxes of the section's microblocks.
		                   // Those cells are left out of kBlkSolids / kBlkShapes. Sent when the section
		                   // has microblocks or had them; at most kMicroMaxBytes of records (cells past
		                   // that go out as solid bits + octants, as before v36)
	};
	inline constexpr std::uint64_t kMicroCellHeaderBytes = 3;   // v36: u16 index + u8 n
	inline constexpr std::uint64_t kMicroBoxBytes = 6;          // v36
	inline constexpr std::uint32_t kMicroMaxParts = 64;         // v36: = MicroblockEntity.MAX_PARTS
	inline constexpr std::uint32_t kMicroEighths = 8;           // v36: box units per block
	inline constexpr std::uint64_t kMicroMaxBytes = 0x20000;    // v36: 128 KiB of records per message
	static_assert(kMicroMaxBytes + sizeof(RenSolids) + 8 < kSvBlockRingBytes / 4);
	static_assert(kMicroCellHeaderBytes + kMicroBoxBytes * kMicroMaxParts <= 0xFFFF);
	static_assert(16 * kMicroEighths <= 0xFF);  // section-local eighths fit a u8 (module sb::Box)

	// ---- server link region map ----------------------------------------------------------------
	static_assert(kSvOffHeader + sizeof(LinkHeader) <= kSvOffServerState);
	static_assert(kSvOffServerState + sizeof(ServerState) <= kSvOffMcServerState);
	static_assert(kSvOffMcServerState + sizeof(McServerState) <= kSvOffWaterGrid);
	static_assert(kSvOffWaterGrid + sizeof(WaterGrid) <= kSvOffLinkStats);
	static_assert(kSvOffLinkStats + sizeof(LinkStats) <= kSvOffHostEventRing);
	static_assert(kSvOffHostEventRing + kHostEventRingBytes <= kSvOffHostPlayers);
	static_assert(kSvOffHostPlayers + sizeof(HostPlayers) <= kSvOffMcPlayers);
	static_assert(kSvOffMcPlayers + sizeof(McPlayers) <= kSvOffActorTable);
	static_assert(kSvOffActorTable + sizeof(ActorTable) <= kSvOffEventRing);
	static_assert(kSvOffEventRing + kEventRingBytes <= kSvOffMcServerInfo);
	static_assert(kSvOffMcServerInfo + sizeof(McServerInfo) <= kSvOffHeldMcEntities);
	static_assert(kSvOffHeldMcEntities + sizeof(HeldMcEntities) <= kSvOffMcWeaponSets);
	static_assert(kSvOffHeldMcEntities % 64 == 0);
	static_assert(kSvOffMcWeaponSets + sizeof(McWeaponSets) <= kSvOffMcEntities);
	static_assert(kSvOffMcEntities + sizeof(McEntities) <= kSvOffBlockRing);
	static_assert(kSvOffMcEntities % 64 == 0);
	static_assert(kSvOffMcWeaponSets % 64 == 0 && kMcWeaponSetBytes % 8 == 0);
	static_assert(kHeText + kWeaponTextChunkBytes == kHostEventBytes);
	static_assert(kHeText + kAdminTextBytes == kHostEventBytes);
	static_assert(kHeText + kPropHostChunkBytes == kHostEventBytes);  // v33
	static_assert(kMeText + kPropMcChunkBytes == kMcEventBytes);      // v33
	static_assert(kHostEvPropResult == 19 && kHostEvPropText == 20 && kEvPropRequest == 24 && kEvPropText == 25);
	static_assert(kWeaponTextMaxBytes == kWeaponTextChunkBytes * kWeaponTextMaxChunks);
	static_assert(kWeaponClassMaxBytes < kWeaponTextMaxBytes);
	static_assert(kSvOffCollisionRing == kSvOffBlockRing + kSvBlockRingBytes);
	static_assert(kSvOffWaterGrids == kSvOffCollisionRing + kSvCollisionRingBytes);
	static_assert(kSvWaterGridsBytes >= kWaterGridBytes * kMaxPlayers && kSvWaterGridsBytes - kWaterGridBytes * kMaxPlayers < 4096);
	static_assert(kSvMappingBytes == kSvOffWaterGrids + kSvWaterGridsBytes);
	static_assert(kSvMappingBytes % 4096 == 0 && kSvOffWaterGrids % 4096 == 0);
	static_assert(kSvOffCollisionRing % 4096 == 0 && kSvOffBlockRing % 4096 == 0);
	static_assert(kSvOffMcServerInfo % 64 == 0 && kWaterGridBytes % 16 == 0);
	static_assert(kSvOffMcServerState % 64 == 0 && kSvOffLinkStats % 64 == 0);
	static_assert(kSvOffHostEventRing % 64 == 0 && kSvOffHostPlayers % 64 == 0 && kSvOffMcPlayers % 64 == 0);
	static_assert(kSvOffActorTable % 64 == 0 && kSvOffEventRing % 64 == 0);
}
