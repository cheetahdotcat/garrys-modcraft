// GmodCraft binary module. One source tree, built twice: gmsv_gmodcraft (GMODCRAFT_SERVER, the
// server link) and gmcl_gmodcraft (GMODCRAFT_CLIENT, the client link, overlay texture and
// Minecraft launcher). Both expose a Lua table `gmodcraft`; this file has the parts both realms
// share, client.cpp / server.cpp the rest. The API is documented in
// addon/gmodcraft/README.md.
#include "actors.hpp"
#include "collision.hpp"
#include "link.hpp"
#include "lua_util.hpp"

#include <cstdio>
#include <string>

#if defined(GMODCRAFT_CLIENT)
static const char *kRealm = "client";
#elif defined(GMODCRAFT_SERVER)
static const char *kRealm = "server";
#else
#error "GMODCRAFT_CLIENT or GMODCRAFT_SERVER must be defined"
#endif

static const char *kModuleVersion = "0.7.0-r1";

namespace gc
{
using namespace GarrysMod::Lua;

void RegisterRealm(ILua *L);  // client.cpp / server.cpp
void CloseRealm();
P::ActorTable *RealmActorTable();  // this realm's actor table in the mapping; nullptr while the link is closed

void PushSideStats(ILua *L, const P::LinkSideStats &s, const char *const *ringNames, int rings)
{
	L->CreateTable();
	SetNum(L, "protocolVersion", s.protocolVersion);
	SetNum(L, "attachCount", s.attachCount);
	SetStr(L, "sessionNonce", Hex16(s.sessionNonce).c_str());
	SetNum(L, "attachAgeMs", s.attachNs ? static_cast<double>(NowNs() - s.attachNs) / 1e6 : -1);
	SetNum(L, "updateCount", static_cast<double>(s.updateCount));
	SetNum(L, "peerDownCount", s.peerDownCount);
	SetBool(L, "peerAlive", (s.flags & P::kSidePeerAlive) != 0);
	SetNum(L, "tickMs", s.tickMs);
	SetNum(L, "frameMs", s.frameMs);
	SetNum(L, "overlayFrames", static_cast<double>(s.overlayFrames));
	L->CreateTable();
	for (int i = 0; i < rings; ++i)
	{
		const P::RingStats &r = s.rings[i];
		L->CreateTable();
		SetNum(L, "messages", static_cast<double>(r.messages));
		SetNum(L, "bytes", static_cast<double>(r.bytes));
		SetNum(L, "drops", static_cast<double>(r.drops));
		SetNum(L, "fill", static_cast<double>(r.fill));
		SetNum(L, "highWater", static_cast<double>(r.highWater));
		SetNum(L, "bytesPerSec", static_cast<double>(r.bytesPerSec));
		L->SetField(-2, ringNames[i]);
	}
	L->SetField(-2, "rings");
}

namespace
{
// -gmodcraft_dev on GMod's own command line (Steam launch options) turns on the dev tooling: the
// scripted tests and the data/gmodcraft/remote.txt poller. Lua can't read the command line, and a
// convar won't do (a server's clientside Lua can set convars); the process's argv can't be changed
// from Lua. Read once, at module load.
int g_devMode = -1;

bool ReadDevMode()
{
	FILE *f = std::fopen("/proc/self/cmdline", "rb");
	if (f == nullptr)
		return false;
	std::string all;
	char buf[4096];
	std::size_t n;
	while ((n = std::fread(buf, 1, sizeof buf, f)) > 0 && all.size() < (1u << 20))
		all.append(buf, n);
	std::fclose(f);
	std::size_t pos = 0;
	while (pos < all.size())
	{
		std::size_t end = all.find('\0', pos);
		if (end == std::string::npos)
			end = all.size();
		if (all.compare(pos, end - pos, "-gmodcraft_dev") == 0)
			return true;
		pos = end + 1;
	}
	return false;
}

// DevMode() -> true when GMod was started with -gmodcraft_dev.
LUA_FUNCTION_STATIC(DevMode)
{
	if (g_devMode < 0)
		g_devMode = ReadDevMode() ? 1 : 0;
	LUA->PushBool(g_devMode == 1);
	return 1;
}

LUA_FUNCTION_STATIC(Version)
{
	std::string v = std::string(kModuleVersion) + " " + kRealm;
	LUA->PushString(v.c_str());
	return 1;
}

// MonoMs() -> CLOCK_MONOTONIC milliseconds (the protocol's clock; McState.tickNs / 1e6 is on it).
LUA_FUNCTION_STATIC(MonoMs)
{
	LUA->PushNumber(NowMs());
	return 1;
}

// WorldId(mapName) -> FNV-1a 32 of the lowercased name (HostState / ServerState worldId).
LUA_FUNCTION_STATIC(WorldId)
{
	if (!LUA->IsType(1, Type::String))
		return 0;
	char buf[256];
	unsigned int len = 0;
	const char *s = LUA->GetString(1, &len);
	std::size_t n = len < sizeof buf - 1 ? len : sizeof buf - 1;
	for (std::size_t i = 0; i < n; ++i)
		buf[i] = (s[i] >= 'A' && s[i] <= 'Z') ? static_cast<char>(s[i] - 'A' + 'a') : s[i];
	buf[n] = 0;
	LUA->PushNumber(Fnv1a32(buf));
	return 1;
}

// SetActors({ { ent, flags, x, y, z, yaw, width, height, healthFrac, tier, name }, ... }) -> count
// written. The whole actor table, every call (MC coordinates and degrees, hull in blocks; see
// actors.hpp for the sanitising). Entries with ent 0 are skipped, at most kMaxActors are kept. 0 while
// the link is closed.
LUA_FUNCTION_STATIC(SetActors)
{
	P::ActorTable *t = RealmActorTable();
	if (t == nullptr || !LUA->IsType(1, Type::Table))
	{
		LUA->PushNumber(0);
		return 1;
	}
	static P::ActorRecord recs[P::kMaxActors];  // 16 KiB: not on the stack
	std::size_t n = 0;
	for (int i = 1; n < P::kMaxActors; ++i)
	{
		LUA->PushNumber(i);
		LUA->GetTable(1);
		if (!LUA->IsType(-1, Type::Table))
		{
			LUA->Pop();
			break;
		}
		int e = LUA->Top();
		char name[P::kActorNameBytes * 2];
		FieldStr(LUA, e, "name", name, sizeof name);
		recs[n] = MakeActor(static_cast<std::uint32_t>(FieldInt(LUA, e, "ent", 0, 0xFFFFFFFF)),
			static_cast<std::uint32_t>(FieldInt(LUA, e, "flags", 0, 0xFFFFFFFF)), FieldNum(LUA, e, "x"), FieldNum(LUA, e, "y"),
			FieldNum(LUA, e, "z"), FieldNum(LUA, e, "yaw"), FieldNum(LUA, e, "width", 0.6), FieldNum(LUA, e, "height", 1.8),
			FieldNum(LUA, e, "healthFrac", 1), static_cast<std::uint32_t>(FieldInt(LUA, e, "tier", 0, 65535)), name);
		LUA->Pop();
		if (recs[n].entId != 0)
			++n;
	}
	LUA->PushNumber(WriteActors(t, recs, n));
	return 1;
}

void SetK(ILua *L, const char *name, double v)
{
	L->PushNumber(v);
	L->SetField(-2, name);
}

// gmodcraft.K: the protocol constants Lua needs, by their header names without the leading k.
void PushConstants(ILua *L)
{
	L->CreateTable();
	// HostFlags
	SetK(L, "HostInGame", P::kHostInGame);
	SetK(L, "HostMenuOpen", P::kHostMenuOpen);
	SetK(L, "HostLoading", P::kHostLoading);
	SetK(L, "HostSlotKnown", P::kHostSlotKnown);
	SetK(L, "CarryOnTop", P::kCarryOnTop);  // v30 HostCarryFlags
	// McFlags
	SetK(L, "McInWorld", P::kMcInWorld);
	SetK(L, "McScreenOpen", P::kMcScreenOpen);
	SetK(L, "McOnGround", P::kMcOnGround);
	SetK(L, "McSneaking", P::kMcSneaking);
	SetK(L, "McSprinting", P::kMcSprinting);
	SetK(L, "McDead", P::kMcDead);
	SetK(L, "McSwimming", P::kMcSwimming);
	SetK(L, "McFlying", P::kMcFlying);
	SetK(L, "McHeld", P::kMcHeld);
	// McIdentityFlags
	SetK(L, "IdInWorld", P::kIdInWorld);
	SetK(L, "IdOnlineAccount", P::kIdOnlineAccount);
	SetK(L, "IdIntegratedServer", P::kIdIntegratedServer);
	// InputType
	SetK(L, "InKey", P::kInKey);
	SetK(L, "InMouseButton", P::kInMouseButton);
	SetK(L, "InScroll", P::kInScroll);
	SetK(L, "InCursor", P::kInCursor);
	SetK(L, "InText", P::kInText);
	SetK(L, "InReleaseAll", P::kInReleaseAll);
	SetK(L, "InOpenMenu", P::kInOpenMenu);
	// OverlayFlags
	SetK(L, "OvBottomUp", P::kOvBottomUp);
	SetK(L, "OvBGRA", P::kOvBGRA);
	SetK(L, "OvSrgbEncoded", P::kOvSrgbEncoded);
	// ServerFlags, McServerFlags
	SetK(L, "ServerInGame", P::kServerInGame);
	SetK(L, "ServerLoading", P::kServerLoading);
	SetK(L, "ServerWiremod", P::kServerWiremod);  // v16
	SetK(L, "McSrvSlotValid", P::kMcSrvSlotValid);
	SetK(L, "McSrvDedicated", P::kMcSrvDedicated);
	SetK(L, "McSrvNewSlot", P::kMcSrvNewSlot);
	// v21: the per-map vertical offset (P8 WP1)
	SetK(L, "ServerAnchorReady", P::kServerAnchorReady);
	SetK(L, "McSrvAnchorWait", P::kMcSrvAnchorWait);
	SetK(L, "AnchorNone", P::kAnchorNone);
	SetK(L, "AnchorSpawns", P::kAnchorSpawns);
	SetK(L, "AnchorMapMode", P::kAnchorMapMode);
	SetK(L, "AnchorCurated", P::kAnchorCurated);
	SetK(L, "AnchorOperator", P::kAnchorOperator);
	SetK(L, "AnchorForced", P::kAnchorForced);
	SetK(L, "AnchorLegacy", P::kAnchorLegacy);
	SetK(L, "AnchorTimeout", P::kAnchorTimeout);
	SetK(L, "AnchorFloorY", P::kAnchorFloorY);
	SetK(L, "AnchorWaitMs", P::kAnchorWaitMs);
	// Host events
	SetK(L, "HostEvHurt", P::kHostEvHurt);
	SetK(L, "HostEvTeleport", P::kHostEvTeleport);
	SetK(L, "HostEvRespawn", P::kHostEvRespawn);
	SetK(L, "HostEvOpenToLan", P::kHostEvOpenToLan);  // v14
	SetK(L, "OpenAllowOffline", P::kOpenAllowOffline);
	SetK(L, "HostEvDevCommand", P::kHostEvDevCommand);  // v14, dev only
	SetK(L, "HostEvDevCommandText", P::kHostEvDevCommandText);
	// v19: admin commands, demo builds
	SetK(L, "HostEvAdminCommand", P::kHostEvAdminCommand);
	SetK(L, "HostEvAdminText", P::kHostEvAdminText);
	SetK(L, "AdminDemoPlace", P::kAdminDemoPlace);
	SetK(L, "AdminDemoClear", P::kAdminDemoClear);
	SetK(L, "AdminDemoClearAll", P::kAdminDemoClearAll);
	SetK(L, "AdminDemoAnnounce", P::kAdminDemoAnnounce);
	SetK(L, "AdminForce", P::kAdminForce);
	SetK(L, "AdminTextBytes", P::kAdminTextBytes);
	SetK(L, "EvAdminResult", P::kEvAdminResult);
	SetK(L, "EvDemoPlaced", P::kEvDemoPlaced);
	SetK(L, "EvDemoCleared", P::kEvDemoCleared);
	SetK(L, "EvDemoSyncDone", P::kEvDemoSyncDone);
	SetK(L, "AdminOk", P::kAdminOk);
	SetK(L, "AdminOccupied", P::kAdminOccupied);
	SetK(L, "AdminUnknownDemo", P::kAdminUnknownDemo);
	SetK(L, "AdminOutsideSlot", P::kAdminOutsideSlot);
	SetK(L, "AdminNoInstance", P::kAdminNoInstance);
	SetK(L, "AdminMalformed", P::kAdminMalformed);
	SetK(L, "AdminFailed", P::kAdminFailed);
	// v20: STools on the admin channel
	SetK(L, "AdminRepairRadius", P::kAdminRepairRadius);
	SetK(L, "AdminRepairColumn", P::kAdminRepairColumn);
	SetK(L, "AdminRepairUndo", P::kAdminRepairUndo);
	SetK(L, "AdminBlockPlace", P::kAdminBlockPlace);
	SetK(L, "AdminBlockBreak", P::kAdminBlockBreak);
	SetK(L, "AdminBlockUndo", P::kAdminBlockUndo);
	SetK(L, "AdminResync", P::kAdminResync);
	SetK(L, "AdminAllowLiquid", P::kAdminAllowLiquid);
	SetK(L, "AdminByAdmin", P::kAdminByAdmin);
	SetK(L, "AdminEveryone", P::kAdminEveryone);
	SetK(L, "AdminNotAllowed", P::kAdminNotAllowed);
	SetK(L, "AdminNothing", P::kAdminNothing);
	SetK(L, "AdminBadBlock", P::kAdminBadBlock);
	SetK(L, "AdminLiquid", P::kAdminLiquid);
	// v23: slot re-anchor (P8 WP2)
	SetK(L, "AdminReanchor", P::kAdminReanchor);
	SetK(L, "AdminReanchorUndo", P::kAdminReanchorUndo);
	SetK(L, "AdminSlotHash", P::kAdminSlotHash);
	SetK(L, "AdminDryRun", P::kAdminDryRun);
	SetK(L, "AdminBusy", P::kAdminBusy);
	SetK(L, "AdminOutOfRange", P::kAdminOutOfRange);
	SetK(L, "AdminSlotOccupied", P::kAdminSlotOccupied);
	SetK(L, "McSrvSlotBusy", P::kMcSrvSlotBusy);
	// v24: world types, server rules (P8 WP3)
	SetK(L, "AdminSetRules", P::kAdminSetRules);
	SetK(L, "AdminSlotHistory", P::kAdminSlotHistory);
	SetK(L, "AdminBadRule", P::kAdminBadRule);
	SetK(L, "AdminStaged", P::kAdminStaged);
	SetK(L, "RulesStageMs", P::kRulesStageMs);
	SetK(L, "RulesValid", P::kRulesValid);
	SetK(L, "RuleForceGamemode", P::kRuleForceGamemode);
	SetK(L, "RulePvp", P::kRulePvp);
	SetK(L, "RuleKeepInventory", P::kRuleKeepInventory);
	SetK(L, "RuleDigIntoMap", P::kRuleDigIntoMap);
	SetK(L, "RuleNoclipMc", P::kRuleNoclipMc);
	SetK(L, "RuleFireCrossover", P::kRuleFireCrossover);  // v28
	SetK(L, "RulePhysgunMobs", P::kRulePhysgunMobs);      // v29
	// v34: control centre (difficulty / mob rules, slot info, world list / world ops)
	SetK(L, "RuleMobSpawning", P::kRuleMobSpawning);
	SetK(L, "MobCapMax", P::kMobCapMax);
	SetK(L, "AdminSlotInfo", P::kAdminSlotInfo);
	SetK(L, "AdminWorldList", P::kAdminWorldList);
	SetK(L, "AdminWorldOp", P::kAdminWorldOp);
	SetK(L, "AdminRestartNow", P::kAdminRestartNow);
	SetK(L, "AdminUnsupported", P::kAdminUnsupported);
	SetK(L, "WorldEntryRunning", P::kWorldEntryRunning);
	SetK(L, "WorldEntryBackup", P::kWorldEntryBackup);
	SetK(L, "WorldOpNone", P::kWorldOpNone);
	SetK(L, "WorldOpNew", P::kWorldOpNew);
	SetK(L, "WorldOpRestore", P::kWorldOpRestore);
	SetK(L, "WorldOpCancel", P::kWorldOpCancel);
	SetK(L, "GameSurvival", P::kGameSurvival);
	SetK(L, "GameCreative", P::kGameCreative);
	SetK(L, "GameAdventure", P::kGameAdventure);
	SetK(L, "GameSpectator", P::kGameSpectator);
	SetK(L, "WorldVoid", P::kWorldVoid);
	SetK(L, "WorldFlatVoidMaps", P::kWorldFlatVoidMaps);
	SetK(L, "WorldFlatEverywhere", P::kWorldFlatEverywhere);
	SetK(L, "WorldOther", P::kWorldOther);
	SetK(L, "DemoRails", P::kDemoRails);
	SetK(L, "DemoRedstone", P::kDemoRedstone);
	SetK(L, "DemoRange", P::kDemoRange);
	SetK(L, "DemoRamp", P::kDemoRamp);
	SetK(L, "DemoArena", P::kDemoArena);
	SetK(L, "DemoDigWall", P::kDemoDigWall);
	// v16: redstone <-> Wiremod bridge
	SetK(L, "HostEvBridgeOutputs", P::kHostEvBridgeOutputs);
	SetK(L, "EvBridgePlaced", P::kEvBridgePlaced);
	SetK(L, "EvBridgeRemoved", P::kEvBridgeRemoved);
	SetK(L, "EvBridgeInputs", P::kEvBridgeInputs);
	SetK(L, "FaceDown", P::kFaceDown);
	SetK(L, "FaceUp", P::kFaceUp);
	SetK(L, "FaceNorth", P::kFaceNorth);
	SetK(L, "FaceSouth", P::kFaceSouth);
	SetK(L, "FaceWest", P::kFaceWest);
	SetK(L, "FaceEast", P::kFaceEast);
	SetK(L, "BridgeFaces", P::kBridgeFaces);
	SetK(L, "BridgeLevelBits", P::kBridgeLevelBits);
	// v25: map entity links on redstone bridges
	SetK(L, "ServerMapIo", P::kServerMapIo);
	SetK(L, "AdminBridgeLink", P::kAdminBridgeLink);
	SetK(L, "EvBridgeLink", P::kEvBridgeLink);
	SetK(L, "LinkButton", P::kLinkButton);
	SetK(L, "LinkMomentary", P::kLinkMomentary);
	SetK(L, "LinkTrigger", P::kLinkTrigger);
	SetK(L, "LinkDoor", P::kLinkDoor);
	SetK(L, "LinkMoveLinear", P::kLinkMoveLinear);
	SetK(L, "LinkRelay", P::kLinkRelay);
	SetK(L, "LinkLight", P::kLinkLight);
	SetK(L, "LinkSprite", P::kLinkSprite);
	SetK(L, "LinkIn", P::kLinkIn);
	SetK(L, "LinkOut", P::kLinkOut);
	// v17: hybrid mode (GMod weapons as MC items)
	SetK(L, "HostEvWeaponGive", P::kHostEvWeaponGive);
	SetK(L, "HostEvWeaponText", P::kHostEvWeaponText);
	SetK(L, "HostEvWeaponTake", P::kHostEvWeaponTake);
	SetK(L, "HostEvWeaponState", P::kHostEvWeaponState);
	SetK(L, "WeaponTextMaxBytes", P::kWeaponTextMaxBytes);
	SetK(L, "WeaponClassMaxBytes", P::kWeaponClassMaxBytes);
	SetK(L, "MaxWeaponsPerPlayer", P::kMaxWeaponsPerPlayer);
	SetK(L, "WeaponSetDead", P::kWeaponSetDead);
	SetK(L, "WeapCatGeneric", P::kWeapCatGeneric);
	SetK(L, "WeapCatPistol", P::kWeapCatPistol);
	SetK(L, "WeapCatSmg", P::kWeapCatSmg);
	SetK(L, "WeapCatRifle", P::kWeapCatRifle);
	SetK(L, "WeapCatShotgun", P::kWeapCatShotgun);
	SetK(L, "WeapCatHeavy", P::kWeapCatHeavy);
	SetK(L, "WeapCatMelee", P::kWeapCatMelee);
	SetK(L, "WeapCatTool", P::kWeapCatTool);
	SetK(L, "WeapCategories", P::kWeapCategories);
	SetK(L, "HostHybrid", P::kHostHybrid);
	SetK(L, "HostWeaponActive", P::kHostWeaponActive);
	SetK(L, "WeaponIconMaxSide", P::kWeaponIconMaxSide);  // v18
	SetK(L, "DevCommandChunkBytes", P::kDevCommandChunkBytes);
	SetK(L, "DevCommandMaxBytes", P::kDevCommandMaxBytes);
	SetK(L, "EvDevCommandResult", P::kEvDevCommandResult);
	SetK(L, "EvDevCommandText", P::kEvDevCommandText);
	SetK(L, "DevOutputChunkBytes", P::kDevOutputChunkBytes);
	SetK(L, "DevCommandOk", P::kDevCommandOk);
	SetK(L, "DevCommandFailed", P::kDevCommandFailed);
	SetK(L, "DevCommandDisabled", P::kDevCommandDisabled);
	SetK(L, "DevCommandMalformed", P::kDevCommandMalformed);
	SetK(L, "HurtMelee", P::kHurtMelee);
	SetK(L, "HurtProjectile", P::kHurtProjectile);
	SetK(L, "HurtMagic", P::kHurtMagic);
	SetK(L, "HurtOther", P::kHurtOther);
	SetK(L, "HurtBlockedByHost", P::kHurtBlockedByHost);
	SetK(L, "HurtPowerAttack", P::kHurtPowerAttack);
	SetK(L, "HurtFire", P::kHurtFire);  // v31 additive (F2): ignite an MC entity (kHostEvHurtMcEntity)
	SetK(L, "TeleportReasonSetPos", P::kTeleportReasonSetPos);
	SetK(L, "TeleportReasonSpawn", P::kTeleportReasonSpawn);
	SetK(L, "TeleportReasonMapChange", P::kTeleportReasonMapChange);
	SetK(L, "TeleportReasonVehicle", P::kTeleportReasonVehicle);
	SetK(L, "TeleportKeepLook", P::kTeleportKeepLook);
	// Player map
	SetK(L, "HostPlayerAlive", P::kHostPlayerAlive);
	SetK(L, "HostPlayerBot", P::kHostPlayerBot);
	SetK(L, "HostPlayerHasMc", P::kHostPlayerHasMc);
	SetK(L, "HostPlayerNoclip", P::kHostPlayerNoclip);
	SetK(L, "HostPlayerCarried", P::kHostPlayerCarried);  // v30
	SetK(L, "HostEvHurtMcEntity", P::kHostEvHurtMcEntity);  // v27 (B1)
	SetK(L, "McEntHostile", P::kMcEntHostile);
	SetK(L, "McEntPassive", P::kMcEntPassive);
	SetK(L, "McEntVehicle", P::kMcEntVehicle);
	SetK(L, "McEntDead", P::kMcEntDead);
	SetK(L, "McEntYawSteps", P::kMcEntYawSteps);  // v31
	SetK(L, "MaxMcEntities", P::kMaxMcEntities);
	SetK(L, "HostEvPuntMcEntity", P::kHostEvPuntMcEntity);  // v29 (T2)
	SetK(L, "MaxHeldMcEntities", P::kMaxHeldMcEntities);
	SetK(L, "HeldByPhysgun", P::kHeldByPhysgun);
	SetK(L, "HeldFrozen", P::kHeldFrozen);
	SetK(L, "HeldConstrained", P::kHeldConstrained);
	SetK(L, "McPlayerDead", P::kMcPlayerDead);
	SetK(L, "McPlayerMapped", P::kMcPlayerMapped);
	SetK(L, "McPlayerHeld", P::kMcPlayerHeld);
	SetK(L, "McPlayerInWater", P::kMcPlayerInWater);  // v14
	// MC events
	SetK(L, "EvHitActor", P::kEvHitActor);
	SetK(L, "EvPlayerDied", P::kEvPlayerDied);
	SetK(L, "EvExplosion", P::kEvExplosion);
	SetK(L, "EvArrowStuck", P::kEvArrowStuck);
	SetK(L, "EvProjectileHit", P::kEvProjectileHit);  // v15
	SetK(L, "ProjArrow", P::kProjArrow);
	SetK(L, "ProjSpectralArrow", P::kProjSpectralArrow);
	SetK(L, "ProjTippedArrow", P::kProjTippedArrow);
	SetK(L, "ProjTrident", P::kProjTrident);
	SetK(L, "ProjSnowball", P::kProjSnowball);
	SetK(L, "ProjEgg", P::kProjEgg);
	SetK(L, "ProjThrown", P::kProjThrown);
	SetK(L, "ProjSmallFireball", P::kProjSmallFireball);
	// v28 (F1): fire both ways
	SetK(L, "ProjOnFire", P::kProjOnFire);
	SetK(L, "ProjKindMask", P::kProjKindMask);
	SetK(L, "EvFireContact", P::kEvFireContact);
	SetK(L, "HostEvFire", P::kHostEvFire);
	SetK(L, "FireBurning", P::kFireBurning);
	SetK(L, "FireExplosion", P::kFireExplosion);
	SetK(L, "HazardFire", P::kHazardFire);
	SetK(L, "HazardLava", P::kHazardLava);
	// v33 (P1): GMod props as MC items
	SetK(L, "EvPropRequest", P::kEvPropRequest);
	SetK(L, "EvPropText", P::kEvPropText);
	SetK(L, "HostEvPropResult", P::kHostEvPropResult);
	SetK(L, "HostEvPropText", P::kHostEvPropText);
	SetK(L, "PropOpPickup", P::kPropOpPickup);
	SetK(L, "PropOpPlace", P::kPropOpPlace);
	SetK(L, "PropOk", P::kPropOk);
	SetK(L, "PropNotAllowed", P::kPropNotAllowed);
	SetK(L, "PropLimit", P::kPropLimit);
	SetK(L, "PropNotAProp", P::kPropNotAProp);
	SetK(L, "PropConstrained", P::kPropConstrained);
	SetK(L, "PropDisabled", P::kPropDisabled);
	SetK(L, "PropMalformed", P::kPropMalformed);
	SetK(L, "PropFailed", P::kPropFailed);
	SetK(L, "PropModelMaxBytes", P::kPropModelMaxBytes);
	SetK(L, "PropMaterialMaxBytes", P::kPropMaterialMaxBytes);
	SetK(L, "PropBodygroupsMaxBytes", P::kPropBodygroupsMaxBytes);
	SetK(L, "PropDupeMaxBytes", P::kPropDupeMaxBytes);
	SetK(L, "PropSkinShift", P::kPropSkinShift);
	SetK(L, "EvTeleportAck", P::kEvTeleportAck);
	SetK(L, "EvPlayerRespawned", P::kEvPlayerRespawned);
	// v14: opening the host's world, auto-join (JoinInfo / JoinStatus / client event ring)
	SetK(L, "EvWorldOpened", P::kEvWorldOpened);
	SetK(L, "EvJoinResult", P::kEvJoinResult);
	SetK(L, "OpenOk", P::kOpenOk);
	SetK(L, "OpenFailed", P::kOpenFailed);
	SetK(L, "OpenAlreadyOpen", P::kOpenAlreadyOpen);
	SetK(L, "SrvInfoOpen", P::kSrvInfoOpen);
	SetK(L, "SrvInfoDedicated", P::kSrvInfoDedicated);
	SetK(L, "SrvInfoOnlineMode", P::kSrvInfoOnlineMode);
	SetK(L, "SrvInfoE4mcInstalled", P::kSrvInfoE4mcInstalled);
	SetK(L, "SrvInfoE4mcReady", P::kSrvInfoE4mcReady);
	SetK(L, "JoinOk", P::kJoinOk);
	SetK(L, "JoinFailed", P::kJoinFailed);
	SetK(L, "JoinLeft", P::kJoinLeft);
	SetK(L, "JoinLost", P::kJoinLost);
	SetK(L, "JoinBadAddress", P::kJoinBadAddress);
	SetK(L, "JoinStateOwnWorld", P::kJoinStateOwnWorld);
	SetK(L, "JoinStateConnecting", P::kJoinStateConnecting);
	SetK(L, "JoinStateJoined", P::kJoinStateJoined);
	SetK(L, "ServerAddressBytes", P::kServerAddressBytes);
	SetK(L, "JoinTokenBytes", P::kJoinTokenBytes);
	SetK(L, "DevOutputMaxChunks", P::kDevOutputMaxChunks);
	SetK(L, "TeleportOk", P::kTeleportOk);
	SetK(L, "TeleportNoPlayer", P::kTeleportNoPlayer);
	SetK(L, "TeleportTimeout", P::kTeleportTimeout);
	SetK(L, "TeleportWrongWorld", P::kTeleportWrongWorld);
	SetK(L, "TeleportBadPosition", P::kTeleportBadPosition);
	// Actors and hits (P4a)
	SetK(L, "ActorHostile", P::kActorHostile);
	SetK(L, "ActorDead", P::kActorDead);
	SetK(L, "ActorEssential", P::kActorEssential);
	SetK(L, "ActorInCombat", P::kActorInCombat);
	SetK(L, "MaxActors", P::kMaxActors);
	SetK(L, "HitCritical", P::kHitCritical);
	SetK(L, "HitProjectile", P::kHitProjectile);
	SetK(L, "HitSweep", P::kHitSweep);
	SetK(L, "HitFire", P::kHitFire);
	SetK(L, "WeaponUnarmed", P::kWeaponUnarmed);
	SetK(L, "WeaponBlade", P::kWeaponBlade);
	SetK(L, "WeaponAxe", P::kWeaponAxe);
	SetK(L, "WeaponBlunt", P::kWeaponBlunt);
	SetK(L, "WeaponPierce", P::kWeaponPierce);
	SetK(L, "WeaponArrow", P::kWeaponArrow);
	// Sizes and timing
	SetK(L, "Version", P::kVersion);
	SetK(L, "UnitsPerBlock", P::kUnitsPerBlock);
	SetK(L, "SlotBlocks", P::kSlotBlocks);
	SetK(L, "HeartbeatTimeoutMs", P::kHeartbeatTimeoutMs);
	SetK(L, "TeleportAckTimeoutMs", P::kTeleportAckTimeoutMs);
	SetK(L, "ColRegionSize", P::kColRegionSize);
	SetK(L, "MaxPlayers", P::kMaxPlayers);
	SetK(L, "MaxOverlayW", P::kMaxOverlayW);
	SetK(L, "MaxOverlayH", P::kMaxOverlayH);
	// Collision
	SetK(L, "TriStairHelper", P::kTriStairHelper);
	SetK(L, "TriDiggable", P::kTriDiggable);
	SetK(L, "TriGhost", P::kTriGhost);
	SetK(L, "TriTerrain", P::kTriTerrain);
	SetK(L, "TriMaterialShift", P::kTriMaterialShift);
	SetK(L, "WaterGridSize", P::kWaterGridSize);
	L->SetField(-2, "K");
}
}  // namespace

// The same flag DevMode() reports, for C++ functions that are dev-only (BlocksDevInject).
bool IsDevMode()
{
	if (g_devMode < 0)
		g_devMode = ReadDevMode() ? 1 : 0;
	return g_devMode == 1;
}
}  // namespace gc

GMOD_MODULE_OPEN()
{
	using namespace gc;
	// Fill the global `gmodcraft` table (create it if the addon hasn't yet): the module's functions
	// sit at its top level, the addon's Lua lives in sub-tables (gmodcraft.debug, ...).
	LUA->PushSpecial(GarrysMod::Lua::SPECIAL_GLOB);
	LUA->GetField(-1, "gmodcraft");
	if (!LUA->IsType(-1, GarrysMod::Lua::Type::Table))
	{
		LUA->Pop();
		LUA->CreateTable();
		LUA->Push(-1);
		LUA->SetField(-3, "gmodcraft");
	}
	LUA->PushCFunction(Version);
	LUA->SetField(-2, "Version");
	LUA->PushCFunction(DevMode);
	LUA->SetField(-2, "DevMode");
	LUA->PushCFunction(MonoMs);
	LUA->SetField(-2, "MonoMs");
	LUA->PushCFunction(WorldId);
	LUA->SetField(-2, "WorldId");
	LUA->PushCFunction(SetActors);
	LUA->SetField(-2, "SetActors");
	LUA->PushString(kRealm);
	LUA->SetField(-2, "realm");
	LUA->PushNumber(P::kVersion);
	LUA->SetField(-2, "PROTOCOL");
	PushConstants(LUA);
	RegisterRealm(LUA);
	RegisterCollision(LUA);
	LUA->Pop(2);
	return 0;
}

GMOD_MODULE_CLOSE()
{
	gc::CloseRealm();
	gc::CloseCollision();
	return 0;
}
