-- Net messages between the GMod server and clients.
--
--   gmodcraft_hello     C->S  the client has loaded (InitPostEntity): send me the slot, ask my identity
--   gmodcraft_slot      S->C  map slot origin and worldId (reliable): the answer
--                             to hello, and pushed to everyone when it changes
--   gmodcraft_identity_req S->C  send your Minecraft identity again (once)
--   gmodcraft_identity  C->S  what this client's Minecraft reported (McIdentity) (reliable): when it
--                             changes (or a new Minecraft attached), and once per identity_req
--   gmodcraft_pos       C->S  the puppet's MC-driven pose, once per tick (UNRELIABLE)
--   gmodcraft_mode      C->S  F6: switch between MC mode and GMod mode
--   gmodcraft_stats_req C->S  an admin's debug tab asks for the server link's stats
--   gmodcraft_stats     S->C  the server link's stats and player table (throttled, admins only)
--   gmodcraft_col_admin C->S  an admin's Collision panel: "resend" or "clear" the server link's collision
--   gmodcraft_arrow     S->C  an arrow stuck in a GMod actor (entity, hit point, flight direction, texture),
--                             or "clear the arrows on this entity" (it died)
--   gmodcraft_join_info S->C  multiplayer pairing (P6b): joinId, Minecraft server address, join token.
--                             To that client only; never to the listen host itself (server/mp.lua)
--   gmodcraft_join_result C->S how that join ended (joinId, JoinResult, JoinState, reason)
--   gmodcraft_join_request C->S the player asks for a fresh JoinInfo (gmodcraft_mp_rejoin)
--   gmodcraft_mc_drop   C->S  S1 (v35): a GMod weapon dropped onto a Minecraft hotbar slot in the
--                             spawnmenu's Minecraft tab (class, slot 0-8)
--
-- Per-player state every client needs is in NW2 vars on the player:
--   gmodcraft_mc      bool  in MC mode and paired with a Minecraft (model hidden, MC drives it)
--   gmodcraft_puppet  bool  MC drives the position right now (no teleport pending)
--   gmodcraft_eye     float MC eye height in units (view offset)

gmodcraft.NET = {
	hello = "gmodcraft_hello",
	slot = "gmodcraft_slot",
	identityReq = "gmodcraft_identity_req",
	identity = "gmodcraft_identity",
	pos = "gmodcraft_pos",
	mode = "gmodcraft_mode",
	statsReq = "gmodcraft_stats_req",
	stats = "gmodcraft_stats",
	colAdmin = "gmodcraft_col_admin",
	arrow = "gmodcraft_arrow",
	joinInfo = "gmodcraft_join_info",
	joinResult = "gmodcraft_join_result",
	joinRequest = "gmodcraft_join_request",
	mcDrop = "gmodcraft_mc_drop",
}

if SERVER then
	for _, name in pairs(gmodcraft.NET) do util.AddNetworkString(name) end
end

-- Is this player driven by Minecraft right now? (Both realms; NW2 vars set by the server.)
function gmodcraft.IsMcPlayer(ply)
	return IsValid(ply) and ply:GetNW2Bool("gmodcraft_mc", false)
end

function gmodcraft.IsPuppet(ply)
	return IsValid(ply) and ply:GetNW2Bool("gmodcraft_puppet", false)
end
