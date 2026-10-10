// The hull trace job (world type `hull`, protocol/hull_format.md): the loaded map traced into
// Minecraft blocks on a thread of its own and written to /dev/shm/gmodcraft/hull/, with a
// persistent cache under garrysmod/data/gmodcraft/hull/. Lua (server realm): HullBuild /
// HullStatus, see hulljob.cpp.
#pragma once

#include "lua_util.hpp"

namespace gc
{
void RegisterHull(ILua *L);  // server.cpp RegisterRealm
void HullCancel();           // a new map is loading (MapBegin): the running trace stops, its result is dropped
void CloseHull();            // gmod13_close: cancels and joins the thread
}  // namespace gc
