-- Garry's Modcraft entry point (both realms). Loads the binary module for this realm (gmcl_ / gmsv_gmodcraft)
-- and then the realm's Lua. The module fills the global `gmodcraft` table with its functions; the
-- addon's Lua lives in sub-tables of it (gmodcraft.convert, .player, .view, .input, .mc, .debug).
-- API overview: addon/gmodcraft/README.md.

gmodcraft = gmodcraft or {}

local SHARED_FILES = {
	"gmodcraft/shared/log.lua",
	"gmodcraft/shared/convert.lua",
	"gmodcraft/shared/net.lua",
	"gmodcraft/shared/mapload.lua",
	"gmodcraft/shared/dynamic.lua",
	"gmodcraft/shared/puppet.lua",
	"gmodcraft/shared/joinaddr.lua",
	"gmodcraft/shared/daynight.lua",  -- v43: day-night sync (gmodcraft_sun_sync), the MC sky default
}
local CLIENT_FILES = {
	"gmodcraft/client/link.lua",
	"gmodcraft/client/carry.lua",  -- P6i: moving platforms carry MC players (link.lua calls it)
	"gmodcraft/client/view.lua",
	"gmodcraft/client/lightpick.lua",
	"gmodcraft/client/underground.lua",  -- U1: the cave view with the eye in the map's brushes (before blocks.lua)
	"gmodcraft/client/mcsky.lua",  -- K1: the Minecraft sky instead of the skybox (gmodcraft_mc_sky; before blocks.lua, which owns the sky hooks)
	"gmodcraft/client/blocks.lua",
	"gmodcraft/client/entities.lua",
	"gmodcraft/client/capture.lua",  -- I1: capture / focus state machine (before input.lua)
	"gmodcraft/client/input.lua",
	"gmodcraft/client/launch.lua",
	"gmodcraft/client/debug/init.lua",
	"gmodcraft/client/debug/panel_links.lua",
	"gmodcraft/client/debug/panel_player.lua",
	"gmodcraft/client/debug/panel_render.lua",
	"gmodcraft/client/debug/panel_collision.lua",
	"gmodcraft/client/debug/panel_combat.lua",
	"gmodcraft/client/debug/panel_blocks.lua",
	"gmodcraft/client/debug/panel_settings.lua",  -- the Settings page (Play group): player options from a registry
	"gmodcraft/client/actors.lua",
}
local SERVER_FILES = {
	"gmodcraft/server/link.lua",
	"gmodcraft/server/players.lua",
	"gmodcraft/server/mp.lua",
	"gmodcraft/server/combat.lua",
	"gmodcraft/server/actors.lua",
	"gmodcraft/server/projectiles.lua",
	"gmodcraft/server/fire.lua",  -- F1 (v28): fire both ways
	"gmodcraft/server/pistons.lua",  -- v42: Minecraft pistons push GMod props, NPCs and GMod-mode players
	"gmodcraft/server/blockcol.lua",
	"gmodcraft/server/blocktrace.lua",  -- X1: player view rays (toolgun, eye trace, prop spawn) stop on MC blocks
	"gmodcraft/server/version.lua",
	"gmodcraft/server/hull.lua",  -- world type `hull`: the map traced into blocks (HullBuild)
}
-- Dev tooling (scripted tests, the remote-control poller, console commands that start/stop
-- Minecraft): only loaded when GMod was started with the launch option -gmodcraft_dev, which the
-- module reads from the process's command line. Not a convar: a server's clientside Lua can set
-- those.
local CLIENT_DEV_FILES = { "gmodcraft/client/test_util.lua", "gmodcraft/client/test.lua", "gmodcraft/client/test_p2.lua", "gmodcraft/client/test_p2c.lua", "gmodcraft/client/test_p3a.lua", "gmodcraft/client/test_p4b.lua", "gmodcraft/client/test_p4a.lua", "gmodcraft/client/test_p3c.lua", "gmodcraft/client/test_p3d.lua", "gmodcraft/client/test_p5b.lua", "gmodcraft/client/test_p6.lua" }
local SERVER_DEV_FILES = { "gmodcraft/server/test.lua" }
CLIENT_DEV_FILES[#CLIENT_DEV_FILES + 1] = "gmodcraft/client/test_p5a.lua"
CLIENT_DEV_FILES[#CLIENT_DEV_FILES + 1] = "gmodcraft/client/test_h2.lua"
SERVER_DEV_FILES[#SERVER_DEV_FILES + 1] = "gmodcraft/server/test_p5a.lua"
SERVER_DEV_FILES[#SERVER_DEV_FILES + 1] = "gmodcraft/server/test_t0.lua"  -- 0.4 Tier 0 live check (gmodcraft_t0_live)
SERVER_DEV_FILES[#SERVER_DEV_FILES + 1] = "gmodcraft/server/test_b1.lua"  -- B1 live check (gmodcraft.b1live)
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/blockent.lua"
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/mchitbox.lua"  -- H-approx (v31): model-shaped ray boxes for the proxies
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/mcproxy.lua"  -- B1 (v27): GMod bodies for MC mobs, animals, carts, boats
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/map_anchors.lua"  -- P8 WP1: measured map floors (v21)
SERVER_FILES[#SERVER_FILES + 1] = "gmodcraft/server/anchor.lua"       -- P8 WP1: the map's floor hint for a new slot
SERVER_FILES[#SERVER_FILES + 1] = "gmodcraft/server/reanchor.lua"     -- P8 WP2: gmodcraft_slot (re-anchor, undo, hash)
SERVER_FILES[#SERVER_FILES + 1] = "gmodcraft/server/physworld.lua"  -- 0.4 Tier 0: props/ragdolls/vehicles collide with map + MC blocks (gmodcraft_physics_world)
SERVER_FILES[#SERVER_FILES + 1] = "gmodcraft/server/physblocks.lua"  -- v44: gravgun pulls MC blocks out, GMod explosions break MC blocks
SERVER_FILES[#SERVER_FILES + 1] = "gmodcraft/server/pvs.lua"  -- P5d: entities stay sent while the eye is inside map geometry; P2: entities sunk into dug holes stay sent
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/solid_ents.lua"  -- P5d: nearby entities drawn while the eye is inside map geometry
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/wirebridge.lua"  -- P7: only does something with Wiremod
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/hybrid.lua"      -- v17 hybrid mode: class hash map, categories
SERVER_FILES[#SERVER_FILES + 1] = "gmodcraft/server/hybrid.lua"      -- v17 hybrid mode: weapon reconcile
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/hybrid.lua"      -- H2: input routing, viewmodel, toggles
SERVER_FILES[#SERVER_FILES + 1] = "gmodcraft/server/props.lua"       -- P1 (v33): GMod props as Minecraft items
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/propicons.lua"   -- P1 (v33): their spawn icons (after client/hybrid.lua)
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/debug/panel_hybrid.lua"  -- H2: settings in the spawnmenu tab
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/demos.lua"  -- P7b: demo builds (admins)
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/tools.lua"  -- P7b-2: the STools' shared layer (lua/weapons/gmod_tool/stools/gmodcraft_*.lua)
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/mccube.lua"  -- S2 (v38): gmodcraft_cube, a box of MC blocks as a prop
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/structures.lua"  -- S2 (v38): Structure Export / Blockify (after tools.lua)
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/mapio.lua"  -- R2: map doors / buttons / triggers <-> redstone bridges (after tools.lua)
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/debug/panel_demos.lua"  -- P7b: the Demos page
SHARED_FILES[#SHARED_FILES + 1] = "gmodcraft/shared/serveradmin.lua"  -- P8 WP3: the Server page's requests (rules, slot, re-anchor)
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/debug/panel_server.lua"  -- P8 WP3: the Server page (admins)
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/debug/panel_maps.lua"  -- v34 control centre: the Maps page (admins)
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/debug/panel_world.lua"  -- v34 control centre: the World page (admins)
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/loading.lua"  -- L3: loading screen / corner indicator while Minecraft starts (gmodcraft_loading_screen)
CLIENT_FILES[#CLIENT_FILES + 1] = "gmodcraft/client/mcscreen.lua"  -- S1 (v35): the Minecraft tab in the spawnmenu (MC screen, weapon drops)

if SERVER then
	AddCSLuaFile()
	for _, f in ipairs(SHARED_FILES) do AddCSLuaFile(f) end
	for _, f in ipairs(CLIENT_FILES) do AddCSLuaFile(f) end
	-- the dev files go to clients only from a -gmodcraft_dev server (below); releases don't ship them
end

local ok, err = pcall(require, "gmodcraft")
if not ok or not gmodcraft.LinkOpen then
	print("[gmodcraft] binary module not loaded (" .. tostring(err) .. "): Garry's Modcraft stays off in this realm")
	gmodcraft.missing = true
else
	gmodcraft.missing = nil
	print("[gmodcraft] module " .. gmodcraft.Version() .. ", protocol " .. tostring(gmodcraft.PROTOCOL))
end

-- Asked once, here, before any other Lua runs after the require (a later override of
-- gmodcraft.DevMode can't turn the dev files on).
local devMode = not gmodcraft.missing and gmodcraft.DevMode ~= nil and gmodcraft.DevMode() == true
gmodcraft.devMode = devMode  -- informational only (client/launch.lua asks gmodcraft.DevMode() itself)
if devMode then print("[gmodcraft] -gmodcraft_dev: dev tooling on (tests, remote control)") end

-- Dev files that aren't there (a release bundle leaves them out; a server that didn't send them)
-- are skipped quietly.
local function includeDev(list)
	for _, f in ipairs(list) do
		if file.Exists(f, "LUA") then include(f) end
	end
end
if SERVER and devMode then
	for _, f in ipairs(CLIENT_DEV_FILES) do
		if file.Exists(f, "LUA") then AddCSLuaFile(f) end
	end
end

for _, f in ipairs(SHARED_FILES) do include(f) end
if SERVER then
	for _, f in ipairs(SERVER_FILES) do include(f) end
	if devMode then includeDev(SERVER_DEV_FILES) end
else
	for _, f in ipairs(CLIENT_FILES) do include(f) end
	if devMode then includeDev(CLIENT_DEV_FILES) end
end
