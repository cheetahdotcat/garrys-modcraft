// The Lua side of P2 collision (both realms): map loading (Map*), streaming (Col*), dig state and
// the water grid. The realm files provide the link's pieces through the hooks below.
#pragma once

#include "link.hpp"
#include "lua_util.hpp"

#include <array>
#include <memory>
#include <vector>

namespace gc
{
void RegisterCollision(ILua *L);
void CloseCollision();      // gmod13_close: stops the worker thread
void CollisionLinkClosed();  // the realm's link closed: pending payloads are dropped, kColClear first next time

// A dug-cell section from Minecraft (kRenDug on the client render ring, kBlkDug on the server
// block ring): RenDug header + bitset (nullptr when count = 0).
void CollisionDug(const P::RenDug &hdr, const std::uint8_t *bits512);

// This realm's dug cells (all worlds), as CollisionDug keeps them (P5b: the client's hole mask).
class DigStore;
const DigStore &CollisionDigs();

// Lua args (worldId, x0, y0, z0, x1, y1, z1) -> pushes { x, y, z, ... }, the dug cells in that
// inclusive MC block box (each axis capped at 64 blocks). ColDugCells (both realms) and the
// client's HolesCells.
int PushDugCells(ILua *L);

// Tier 0 (server physworld.cpp): the current map view (nullptr until indexed; Poll()s first), the
// map's worldId, and a hook called with every batch of changed dug cells of the current world.
struct MapView;
std::shared_ptr<const MapView> CollisionView();
std::uint32_t CollisionWorldId();
void SetCollisionDugHook(void (*hook)(const std::vector<std::array<int, 3>> &cells));

// Realm hooks (client.cpp / server.cpp).
ByteRingWriter *RealmCollisionRing();  // nullptr while the link is closed
P::WaterGrid *RealmWaterGrid(int slot);  // grid of a player slot in the mapping; nullptr while closed / no such slot
}  // namespace gc
