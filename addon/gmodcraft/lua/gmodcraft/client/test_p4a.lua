-- Scripted P4a run (client): GMod NPCs in Minecraft (server/actors.lua). Dev only (-gmodcraft_dev).
-- Starts when data/gmodcraft/p4atest.txt exists at InitPostEntity, or with gmodcraft_test_p4a. If the
-- file contains "chain p4b", the P4b run (client/test_p4b.lua) follows. Needs a world with
-- allowCommands, in survival. The server half is gmodcraft_test_p4a_* in server/test.lua.
--
--   lava      MC chat /fill: lava left near the MC spawn (P3b-B's box) becomes air; the count is in
--             Minecraft's log
--   pixel     facing the gm_construct wall as P3a/P3b did, the P3b-B wall reference pixel (754, 236)
--             must not be near-black (the light-in-walls fix)
--   zombie    an npc_zombie: its first hit is forwarded with its EntIndex and MC armour reduces it;
--             then a diamond sword (MC hotbar slot 9) kills it in about 2 hits, the killfeed
--             attacker is the player
--   combine   an npc_combine_s: a bow kills it, the arrows stick (clientside models), and go
--             when it dies
--   fall      in GMod mode: lifted 10 blocks, the landing is one MC hurt of 7 (MC's rule)
-- Logs "[gmodcraft-test]" PASS/FAIL lines. Leaves: hotbar slot 9 and the arrows cleared, slot 1 selected.

local T = {}
gmodcraft.testP4a = T
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

-- ---- Minecraft input ------------------------------------------------------------------------------------
local SDL_SLASH, SDL_RETURN, SDL_1, SDL_9 = 56, 40, 30, 38
local function mcCommand(text)
	gmodcraft.input.Tap(SDL_SLASH)  -- opens chat with "/" typed
	wait(0.5)
	for _, cp in utf8.codes(text) do gmodcraft.PushInput(K.InText, 0, cp) end
	wait(0.3)
	gmodcraft.input.Tap(SDL_RETURN)
	wait(0.5)
	log("mc command: /%s", text)
end
local function press(button) gmodcraft.PushInput(K.InMouseButton, button, 1) end
local function release(button) gmodcraft.PushInput(K.InMouseButton, button, 0) end

-- Points Minecraft's look (the authoritative look, gmodcraft.input.look) at an entity's centre.
local function aimAt(e)
	local eye = (gmodcraft.view.lastView or {}).origin or LocalPlayer():EyePos()
	local ang = (e:WorldSpaceCenter() - eye):Angle()
	local look = gmodcraft.input.look
	look.yaw, look.pitch = C.YawToMc(ang.y), math.Clamp(math.NormalizeAngle(ang.p), -89, 89)
end
local function aimFor(e, s)
	local u = RealTime() + s
	while RealTime() < u and IsValid(e) do aimAt(e) coroutine.yield() end
end

-- ---- the server's view ------------------------------------------------------------------------------------
local function st4a() return util.JSONToTable(LocalPlayer():GetNW2String("gmodcraft_test_p4a", "")) or { hit = {}, tg = {} } end
local function st4b() return util.JSONToTable(LocalPlayer():GetNW2String("gmodcraft_test_p4b", "")) or { stats = {} } end
local function fmt4b(s)
	return string.format("MC %.2f/%.0f, GMod %d/%d", s.mcHealth or -1, s.mcMax or -1, s.hp or -1, s.maxHp or -1)
end
local function healed() local s = st4b() return s.mcHealth ~= nil and s.mcHealth >= s.mcMax end
local function heal()
	for _ = 1, 2 do
		mcCommand("effect give @s minecraft:instant_health 1 4")
		if waitUntil(healed, 4) then wait(0.5) return true end
	end
	return false
end
-- The newest target the server spawned (tagged p4a_target), as a client entity.
local function target()
	local tg = st4a().tg or {}
	local t = tg[#tg]
	local e = t and Entity(t[1])
	return IsValid(e) and e or nil, t
end

-- Alive on the server (its health as the server publishes it; an NPC's health isn't networked).
local function serverAlive(idx)
	for _, t in ipairs(st4a().tg or {}) do
		if t[1] == idx then return t[2] > 0 end
	end
	return false
end

-- ---- the P3b-B wall pixel ------------------------------------------------------------------------------------
local function findWall(eye, minDist)
	for i = 0, 23 do
		local yaw = i * 15
		local dir = Angle(0, yaw, 0):Forward()
		local tr = util.TraceLine({ start = eye, endpos = eye + dir * 1500, mask = MASK_SOLID_BRUSHONLY })
		if tr.Hit and not tr.StartSolid and tr.HitPos:Distance(eye) > minDist and math.abs(tr.HitNormal.z) < 0.3 then return yaw end
	end
end
local pixWant, pixOut
hook.Add("HUDPaint", "gmodcraft_test_p4a_pixel", function()
	if not pixWant then return end
	pixWant = false
	render.CapturePixels()
	local r0, g0, b0 = render.ReadPixel(754, 236)
	local sr, sg, sb, n = 0, 0, 0, 0
	for dx = -3, 3 do
		for dy = -3, 3 do
			local r, g, b = render.ReadPixel(754 + dx, 236 + dy)
			sr, sg, sb, n = sr + r, sg + g, sb + b, n + 1
		end
	end
	pixOut = { r0 = r0, g0 = g0, b0 = b0, r = sr / n, g = sg / n, b = sb / n, w = ScrW(), h = ScrH() }
end)

local function run()
	local ply = LocalPlayer()
	log("P4a run on %s", game.GetMap())
	local up = waitUntil(function() return gmodcraft.IsPuppet(ply) and gmodcraft.view.valid end, 300)
	check("puppet active", up)
	if not up then log("P4ATEST DONE (aborted)") return end
	RunConsoleCommand("gmodcraft_test_p4b_watch", "1")
	RunConsoleCommand("gmodcraft_test_p4a_watch", "1")
	local linked = waitUntil(function() return st4b().linked and st4a().writes ~= nil end, 10)
	check("Minecraft owns the player's health and the actor writer runs", linked, fmt4b(st4b()))
	if not linked then log("P4ATEST DONE (aborted)") return end
	wait(2)

	-- 0a. P3b-B's leftover lava near the MC spawn (19, -2, 2): every lava block in a box around it -> air.
	-- Minecraft logs "Successfully filled N block(s)" or "No blocks were filled".
	mcCommand("fill 9 -7 -8 29 3 12 minecraft:air replace minecraft:lava")
	wait(1)

	-- 0b. the P3b-B wall pixel
	gmodcraft.test.SetConVarTemp("gmodcraft_overlay", "0")  -- the hand would cover it
	local eye = (gmodcraft.view.lastView or {}).origin or ply:EyePos()
	local yaw = findWall(eye, 300) or findWall(eye, 120)
	if yaw then
		local look = gmodcraft.input.look
		local hold = RealTime() + 4  -- the light sampling settles
		while RealTime() < hold do look.yaw, look.pitch = C.YawToMc(yaw), 0 coroutine.yield() end
		pixOut, pixWant = nil, true
		waitUntil(function() return pixOut ~= nil end, 3)
		local p = pixOut or {}
		local lum = p.r and (0.2126 * p.r + 0.7152 * p.g + 0.0722 * p.b) or -1
		check("P3b-B wall pixel (754, 236): not near-black", lum > 30,
			string.format("eye %s, yaw %d; pixel (%s,%s,%s), 7x7 mean (%.0f,%.0f,%.0f) lum %.0f at %sx%s (run 2: (104,84,48) -> (17,13,6))",
				tostring(eye), yaw, tostring(p.r0), tostring(p.g0), tostring(p.b0), p.r or -1, p.g or -1, p.b or -1, lum, tostring(p.w), tostring(p.h)))
	else
		check("P3b-B wall pixel: a wall near the spawn", false)
	end
	gmodcraft.test.RestoreConVars("after the pixel")

	-- 1. zombie: its hit through armour, then the diamond sword
	mcCommand("item replace entity @s hotbar.8 with minecraft:diamond_sword")
	gmodcraft.input.Tap(SDL_9)
	heal()
	local a0, b0 = st4a(), st4b()
	RunConsoleCommand("gmodcraft_test_p4a_spawn", "zombie", "100")
	local zombie
	waitUntil(function() zombie = target() return zombie ~= nil end, 5)
	if not zombie then
		check("zombie spawned", false)
	else
		local zid = zombie:EntIndex()
		local listed = waitUntil(function() return (st4a().listed or 0) >= 1 end, 3)
		check("zombie listed in the actor table", listed, string.format("listed %s", tostring(st4a().listed)))
		-- its first hit on us (armour)
		local low, hurt = b0.mcHealth, false
		local deadline = RealTime() + 15
		while RealTime() < deadline do
			if IsValid(zombie) then aimAt(zombie) end
			local s = st4b()
			if s.mcHealth and s.mcHealth < low then low = s.mcHealth end
			if (s.stats.forwarded or 0) > (b0.stats.forwarded or 0) and not hurt then
				hurt = true
				deadline = math.min(deadline, RealTime() + 1)  -- the lowest health within a second
			end
			coroutine.yield()
		end
		local h = st4b().stats.lastHurt or {}
		local full = (h.amount or 0) / 5
		local drop = (b0.mcHealth or 0) - low
		check("zombie hit forwarded with its EntIndex (the proxy is the MC attacker)", hurt and h.attacker == zid,
			string.format("attacker %s, zombie #%d, %.1f GMod", tostring(h.attacker), zid, h.amount or -1))
		check("MC armour reduces the zombie's hit", hurt and drop > 0 and drop < full - 0.01,
			string.format("GMod %.1f -> %.2f MC unarmoured; MC %.2f -> %.2f (drop %.2f)", h.amount or -1, full, b0.mcHealth or -1, low, drop))
		-- the sword
		local swings = 0
		while IsValid(zombie) and serverAlive(zid) and swings < 8 do
			aimFor(zombie, 0.3)
			press(1)
			wait(0.06)
			release(1)
			swings = swings + 1
			aimFor(zombie, 0.6)  -- the sword's attack cooldown (0.625 s)
		end
		local dead = waitUntil(function() local k = st4a().kill return k ~= nil and k.cls == "npc_zombie" end, 3)
		local a1 = st4a()
		local hits = (a1.hits or 0) - (a0.hits or 0)
		check("diamond sword kills the zombie in about 2 hits", dead and hits >= 1 and hits <= 3,
			string.format("%d swings, %d hits reached GMod; last hit %.2f MC -> %.1f GMod, health %s -> %s", swings, hits, a1.hit.mc or -1, a1.hit.gmod or -1,
				tostring(a1.hit.hp0), tostring(a1.hit.hp1)))
		check("killfeed credits the player (OnNPCKilled attacker)", dead and a1.kill.me == true, a1.kill and ("attacker " .. tostring(a1.kill.by)) or "no kill")
	end
	RunConsoleCommand("gmodcraft_test_p4a_cleanup")
	RunConsoleCommand("gmodcraft_test_p4a_watch", "1")
	wait(1)

	-- 2. combine soldier: the bow, stuck arrows
	mcCommand("item replace entity @s hotbar.8 with minecraft:bow")
	mcCommand("give @s minecraft:arrow 16")
	gmodcraft.input.Tap(SDL_9)
	heal()
	local CA = gmodcraft.clientActors
	local arrows0 = CA.arrowsAdded or 0
	a0 = st4a()
	RunConsoleCommand("gmodcraft_test_p4a_spawn", "combine", "240")
	local combine
	waitUntil(function() combine = target() return combine ~= nil end, 5)
	if not combine then
		check("combine soldier spawned", false)
	else
		wait(1)
		local shots, stuckSeen = 0, 0
		local cid = combine:EntIndex()
		while IsValid(combine) and serverAlive(cid) and shots < 7 do
			aimFor(combine, 0.2)
			press(3)
			aimFor(combine, 1.3)  -- full draw
			release(3)
			shots = shots + 1
			wait(0.3)
			stuckSeen = math.max(stuckSeen, #CA.arrows)
			aimFor(combine, 0.5)
			if st4a().kill then break end
		end
		local dead = waitUntil(function() local k = st4a().kill return k ~= nil and k.cls == "npc_combine_s" end, 3)
		local a1 = st4a()
		local added = (CA.arrowsAdded or 0) - arrows0
		check("bow kills the combine soldier", dead, string.format("%d shots, %d hits reached GMod, last %.2f MC -> %.1f GMod", shots,
			(a1.hits or 0) - (a0.hits or 0), a1.hit.mc or -1, a1.hit.gmod or -1))
		check("killfeed credits the player for the combine", dead and a1.kill.me == true, a1.kill and ("attacker " .. tostring(a1.kill.by)) or "no kill")
		check("arrows stick in the combine (clientside models)", added >= 1 and (a1.arrows or 0) - (a0.arrows or 0) >= 1,
			string.format("%d arrow events, %d models added, up to %d on screen", (a1.arrows or 0) - (a0.arrows or 0), added, stuckSeen))
		local gone = waitUntil(function() return #CA.arrows == 0 end, 3)
		check("stuck arrows go when it dies", gone, string.format("%d left", #CA.arrows))
	end
	RunConsoleCommand("gmodcraft_test_p4a_cleanup")
	mcCommand("item replace entity @s hotbar.8 with minecraft:air")
	mcCommand("clear @s minecraft:arrow")
	gmodcraft.input.Tap(SDL_1)
	RunConsoleCommand("gmodcraft_test_p4a_watch", "1")
	wait(1)

	-- 3. a fall in GMod mode: one MC hurt by MC's rule
	heal()
	net.Start(gmodcraft.NET.mode)
	net.SendToServer()
	local gm = waitUntil(function() return not gmodcraft.IsMcPlayer(ply) end, 5)
	check("switched to GMod mode", gm)
	wait(1.5)
	local s0, f0 = st4b(), st4a().falls or 0
	RunConsoleCommand("gmodcraft_test_p4b_up", "400")
	local drops, last = {}, s0.mcHealth
	local deadline = RealTime() + 7
	while RealTime() < deadline do
		local s = st4b()
		if s.mcHealth and last and s.mcHealth < last - 0.01 then drops[#drops + 1] = string.format("%.2f", last - s.mcHealth) end
		last = s.mcHealth or last
		coroutine.yield()
	end
	local lift = ply:GetNW2Float("gmodcraft_test_p4b_lift", 0)
	local falls = (st4a().falls or 0) - f0
	check("GMod-mode fall: one hurt sent, Minecraft hurts once", lift >= 200 and falls == 1 and #drops == 1,
		string.format("lifted %.0f units, falls sent %d, MC drops [%s], MC %.2f -> %.2f", lift, falls, table.concat(drops, ", "), s0.mcHealth or -1, last or -1))
	net.Start(gmodcraft.NET.mode)
	net.SendToServer()
	local back = waitUntil(function() return gmodcraft.IsPuppet(ply) end, 15)
	check("back in MC mode", back)

	RunConsoleCommand("gmodcraft_test_p4a_cleanup")
	RunConsoleCommand("gmodcraft_test_p4b_cleanup")
	wait(0.5)
	local nf = 0
	for _, r in ipairs(results) do if not r[2] then nf = nf + 1 end end
	log("P4ATEST DONE: %d checks, %d failed", #results, nf)
end

local co
function T.Start(chain)
	if co then return end
	results = {}
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then
			log("ERROR %s", tostring(err))
			RunConsoleCommand("gmodcraft_test_p4a_cleanup")
			log("P4ATEST DONE (error)")
		end
		gmodcraft.test.RestoreConVars("end of run")
	end)
	hook.Add("Think", "gmodcraft_test_p4a", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_p4a")
			co = nil
			if chain and gmodcraft.testP4b then
				log("chaining the P4b run")
				gmodcraft.testP4b.Start()
			end
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_p4a", function() T.Start(false) end)
hook.Add("InitPostEntity", "gmodcraft_test_p4a", function()
	if file.Exists("gmodcraft/p4atest.txt", "DATA") then
		local chain = (file.Read("gmodcraft/p4atest.txt", "DATA") or ""):find("chain p4b", 1, true) ~= nil
		log("p4atest.txt present: starting in 5 s%s", chain and " (then P4b)" or "")
		timer.Simple(5, function() T.Start(chain) end)
	end
end)
