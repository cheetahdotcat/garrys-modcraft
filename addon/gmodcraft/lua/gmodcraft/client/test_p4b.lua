-- Scripted P4b run (client): MC players in GMod (server/combat.lua). Dev only (-gmodcraft_dev).
-- Starts when data/gmodcraft/p4btest.txt exists at InitPostEntity, or with gmodcraft_test_p4b.
-- Needs a world with allowCommands (MC chat commands), in survival. The server half is the
-- gmodcraft_test_p4b_* commands in server/test.lua.
--
--   zombie    an npc_zombie next to the player: its slash goes to Minecraft as melee; MC health
--             drops by at most damage / 5 (armour), GMod health mirrors it (x5)
--   combine   an npc_combine_s with an SMG: a bullet goes as projectile, armour reduces it
--   fall      the player lifted ~10 blocks: Minecraft's fall damage, once; nothing forwarded
--   tnt       MC chat /summon tnt high in the air: a floating prop_physics nearby is pushed
--   kill      console kill: hurt 1000 in Minecraft, MC death kills the GMod player, MC's
--             respawn spawns it at a GMod spawn point, no death loop
-- Each attacker is removed after its first forwarded hit. Logs "[gmodcraft-test]" PASS/FAIL lines.

local T = {}
gmodcraft.testP4b = T
local K = gmodcraft.K or {}
local C = gmodcraft.convert

local function log(fmt, ...) print("[gmodcraft-test] " .. string.format(fmt, ...)) end
local results = {}
local function check(name, ok, detail)
	results[#results + 1] = { name, ok }
	log("%s %s%s", ok and "PASS" or "FAIL", name, detail and (": " .. detail) or "")
end
local function wait(s)
	local u = RealTime() + s
	while RealTime() < u do coroutine.yield() end
end
local function waitUntil(fn, timeout)
	local deadline = RealTime() + timeout
	while RealTime() < deadline do
		if fn() then return true end
		coroutine.yield()
	end
	return false
end

-- ---- MC chat commands -----------------------------------------------------------------------------------
local SDL_SLASH, SDL_RETURN = 56, 40
local function mcCommand(text)
	gmodcraft.input.Tap(SDL_SLASH)  -- opens chat with "/" typed
	wait(0.5)
	for _, cp in utf8.codes(text) do gmodcraft.PushInput(K.InText, 0, cp) end
	wait(0.3)
	gmodcraft.input.Tap(SDL_RETURN)
	wait(0.5)
	log("mc command: /%s", text)
end

-- ---- the server's view (server/test.lua gmodcraft_test_p4b_watch) ---------------------------------------
local function state()
	return util.JSONToTable(LocalPlayer():GetNW2String("gmodcraft_test_p4b", "")) or { stats = {} }
end
local function fmtState(s)
	return string.format("MC %.2f/%.0f, GMod %d/%d, %s", s.mcHealth or -1, s.mcMax or -1, s.hp or -1, s.maxHp or -1, s.alive and "alive" or "dead")
end
local function mirrorOk(s)
	return s.mcHealth and s.hp == math.max(1, math.ceil(s.mcHealth * 5 - 1e-3))
end

local function healed() local s = state() return s.mcHealth ~= nil and s.mcHealth >= s.mcMax end
local function heal()
	for _ = 1, 2 do  -- a command typed while a Minecraft screen was up gets lost: once more
		mcCommand("effect give @s minecraft:instant_health 1 4")
		if waitUntil(healed, 4) then wait(0.5) return true end
	end
	return false
end

-- One attacker, one forwarded hit: returns before, after, the hurt.
local function oneHit(what, timeout)
	local s0 = state()
	RunConsoleCommand("gmodcraft_test_p4b_spawn", what)
	local hit = waitUntil(function() return (state().stats.forwarded or 0) > (s0.stats.forwarded or 0) end, timeout)
	if not hit then return s0, nil end
	-- Minecraft applies it at the end of its tick; McPlayers carries the new health. Natural
	-- regeneration (full food) starts healing within a second, so take the lowest health seen.
	local low = s0.mcHealth
	local deadline = RealTime() + 1.0
	while RealTime() < deadline do
		local s = state()
		if s.mcHealth and s.mcHealth < low then low = s.mcHealth end
		coroutine.yield()
	end
	local s1 = state()
	s1.mcLow = low
	return s0, s1
end

local function attackerTest(what, label, wantKind, timeout)
	if not heal() then log("heal didn't reach full health: %s", fmtState(state())) end
	local s0, s1 = oneHit(what, timeout)
	if not s1 then
		check(label .. ": a hit reached Minecraft", false, "no forwarded hurt in " .. timeout .. " s")
		RunConsoleCommand("gmodcraft_test_p4b_cleanup")
		RunConsoleCommand("gmodcraft_test_p4b_watch", "1")
		return
	end
	local h = s1.stats.lastHurt or {}
	local drop = s0.mcHealth - s1.mcLow
	local full = (h.amount or 0) / 5
	check(label .. ": sent as HurtKind " .. wantKind .. " with the attacker's EntIndex", h.kind == wantKind and (h.attacker or 0) > 0,
		string.format("kind %s, attacker %s, type 0x%x", tostring(h.kind), tostring(h.attacker), h.type or 0))
	check(label .. ": MC health drops by about damage / 5", drop > 0 and drop <= full + 0.05,
		string.format("GMod damage %.1f -> at most %.2f MC; MC %.2f -> lowest %.2f (drop %.2f), %.2f a second later", h.amount or -1, full, s0.mcHealth,
			s1.mcLow, drop, s1.mcHealth))
	check(label .. ": GMod health mirrors MC health x5", mirrorOk(s1), fmtState(s1))
	log("%s: armour %s (drop %.2f of %.2f; without a P4a proxy the MC source is generic damage)", label, drop < full - 0.01 and "reduced it" or "did not reduce it", drop, full)
	return drop, full
end

local function run()
	local ply = LocalPlayer()
	log("P4b run on %s", game.GetMap())
	local up = waitUntil(function() return gmodcraft.IsPuppet(ply) and gmodcraft.view.valid end, 300)
	check("puppet active", up)
	if not up then log("P4BTEST DONE (aborted)") return end
	RunConsoleCommand("gmodcraft_test_p4b_watch", "1")
	RunConsoleCommand("gmodcraft_test_p4b_spawnpoints")
	local linked = waitUntil(function() return state().linked end, 10)
	check("Minecraft owns the player's health (paired and mapped)", linked, fmtState(state()))
	if not linked then log("P4BTEST DONE (aborted)") return end
	wait(2)

	-- 1. zombie
	attackerTest("zombie", "zombie", K.HurtMelee, 25)
	-- 2. combine soldier with an SMG (the player wears iron armour in the test world)
	attackerTest("combine", "combine soldier", K.HurtProjectile, 40)
	wait(1)

	-- 3. a fall: Minecraft's own fall damage, once; nothing goes through GMod
	heal()
	local s0 = state()
	RunConsoleCommand("gmodcraft_test_p4b_up", "400")
	local drops, last, lift = {}, s0.mcHealth, nil
	local deadline = RealTime() + 8
	while RealTime() < deadline do
		local s = state()
		if s.mcHealth and last and s.mcHealth < last - 0.01 then drops[#drops + 1] = last - s.mcHealth end
		last = s.mcHealth or last
		coroutine.yield()
	end
	lift = ply:GetNW2Float("gmodcraft_test_p4b_lift", 0)
	local s1 = state()
	check("fall: lifted at least 5 blocks", lift >= 200, string.format("%.0f units", lift))
	check("fall: Minecraft hurts once", #drops == 1, string.format("%d drops (%s), MC %.2f -> %.2f", #drops, table.concat(drops, ", "), s0.mcHealth, s1.mcHealth))
	check("fall: nothing forwarded from GMod", (s1.stats.forwarded or 0) == (s0.stats.forwarded or 0),
		string.format("forwarded %d -> %d, dropped %d -> %d", s0.stats.forwarded or 0, s1.stats.forwarded or 0, s0.stats.dropped or 0, s1.stats.dropped or 0))
	check("fall: GMod health mirrors MC health x5", mirrorOk(s1), fmtState(s1))

	-- 4. TNT: a floating crate near it gets pushed; no GMod damage
	heal()
	RunConsoleCommand("gmodcraft_test_p4b_tnt")
	wait(0.5)
	local tnt = ply:GetNW2Vector("gmodcraft_test_p4b_tnt", Vector(0, 0, 0))
	if tnt == Vector(0, 0, 0) then
		check("tnt: an open spot for the TNT", false)
	else
		RunConsoleCommand("gmodcraft_test_p4b_crate")
		wait(0.5)
		local c0 = util.JSONToTable(ply:GetNW2String("gmodcraft_test_p4b_crate", "")) or {}
		local s0 = state()
		local x, y, z = C.ToMc(tnt)
		mcCommand(string.format("summon minecraft:tnt %.2f %.2f %.2f {fuse:20,NoGravity:1b}", x, y, z))
		local boom = waitUntil(function() return (state().stats.explosions or 0) > (s0.stats.explosions or 0) end, 10)
		wait(0.6)
		RunConsoleCommand("gmodcraft_test_p4b_crate")
		wait(0.4)
		local c1 = util.JSONToTable(ply:GetNW2String("gmodcraft_test_p4b_crate", "")) or {}
		local s1 = state()
		check("tnt: the explosion reached GMod", boom, string.format("explosions %d -> %d", s0.stats.explosions or 0, s1.stats.explosions or 0))
		local moved = (c0.pos and c1.pos) and Vector(c1.pos[1], c1.pos[2], c1.pos[3]):Distance(Vector(c0.pos[1], c0.pos[2], c0.pos[3])) or -1
		check("tnt: the floating crate was pushed", moved > 20 and (s1.stats.pushedEnts or 0) > (s0.stats.pushedEnts or 0),
			string.format("moved %.0f units, speed %.0f u/s, objects pushed %d -> %d", moved, c1.vel or -1, s0.stats.pushedEnts or 0, s1.stats.pushedEnts or 0))
		check("tnt: the player (12 blocks away) is unhurt", (s1.mcHealth or -1) >= (s0.mcHealth or 0) - 0.01 and (s1.stats.forwarded or 0) == (s0.stats.forwarded or 0),
			string.format("MC %.2f -> %.2f", s0.mcHealth, s1.mcHealth))
	end

	-- 5. console kill: dies in Minecraft, MC's death kills GMod, MC's respawn spawns GMod
	local s0 = state()
	local sawDead = false
	RunConsoleCommand("kill")  -- hygiene: ok (the test's subject, not a convar)
	local asked = waitUntil(function() return (state().stats.suicides or 0) > (s0.stats.suicides or 0) end, 3)
	if not asked then
		log("client kill didn't reach CanPlayerSuicide: trying the server's ConCommand")
		RunConsoleCommand("gmodcraft_test_p4b_kill")
		asked = waitUntil(function() return (state().stats.suicides or 0) > (s0.stats.suicides or 0) end, 3)
	end
	check("kill: console kill becomes a hurt in Minecraft", asked)
	local died = waitUntil(function()
		local s = state()
		if not s.alive then sawDead = true end
		return (s.stats.kills or 0) > (s0.stats.kills or 0)
	end, 10)
	check("kill: Minecraft's death kills the GMod player", died)
	local back = waitUntil(function()
		local s = state()
		if not s.alive then sawDead = true end
		return (s.stats.spawns or 0) > (s0.stats.spawns or 0) and s.alive
	end, 20)
	check("kill: Minecraft's respawn spawns the GMod player", back and sawDead)
	local acked = waitUntil(function() local s = state() return s.pending == false and (s.lastAck or 0) > (s0.lastAck or 0) end, 15)
	wait(1)
	local s1 = state()
	RunConsoleCommand("gmodcraft_test_p4b_spawnpoints")
	wait(0.5)
	local best = ply:GetNW2Float("gmodcraft_test_p4b_spawndist", math.huge)
	check("kill: Minecraft is teleported to the GMod spawn point (acked)", acked and best < 100,
		string.format("ack %d (result %d), %.0f units from the nearest of %d spawn points", s1.lastAck or 0, s1.lastAckResult or -1, best,
			ply:GetNW2Int("gmodcraft_test_p4b_spawns", 0)))
	check("kill: no death loop (one suicide, one MC death, no GMod-side death sent back)",
		(s1.stats.suicides or 0) - (s0.stats.suicides or 0) == 1 and (s1.stats.kills or 0) - (s0.stats.kills or 0) == 1
		and (s1.stats.gmodDeaths or 0) == (s0.stats.gmodDeaths or 0),
		string.format("suicides +%d, MC deaths +%d, GMod deaths sent +%d", (s1.stats.suicides or 0) - (s0.stats.suicides or 0),
			(s1.stats.kills or 0) - (s0.stats.kills or 0), (s1.stats.gmodDeaths or 0) - (s0.stats.gmodDeaths or 0)))
	check("kill: full health after the respawn, mirrored", s1.alive and s1.mcHealth >= s1.mcMax and mirrorOk(s1), fmtState(s1))
	wait(3)
	check("kill: still alive 3 s later (no loop)", state().alive and (state().stats.kills or 0) == (s1.stats.kills or 0), fmtState(state()))

	RunConsoleCommand("gmodcraft_test_p4b_cleanup")
	wait(0.5)
	local nf = 0
	for _, r in ipairs(results) do if not r[2] then nf = nf + 1 end end
	log("P4BTEST DONE: %d checks, %d failed", #results, nf)
end

local co
function T.Start()
	if co then return end
	results = {}
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then
			log("ERROR %s", tostring(err))
			RunConsoleCommand("gmodcraft_test_p4b_cleanup")
			log("P4BTEST DONE (error)")
		end
		gmodcraft.test.RestoreConVars("end of run")  -- passed, aborted or errored
	end)
	hook.Add("Think", "gmodcraft_test_p4b", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_p4b")
			co = nil
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_p4b", T.Start)
hook.Add("InitPostEntity", "gmodcraft_test_p4b", function()
	if file.Exists("gmodcraft/p4btest.txt", "DATA") then
		log("p4btest.txt present: starting in 5 s")
		timer.Simple(5, T.Start)
	end
end)
