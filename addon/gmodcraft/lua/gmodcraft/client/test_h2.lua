-- Scripted H2 run (hybrid input + view), client. Dev only (-gmodcraft_dev; listen server host).
-- Starts with gmodcraft_test_h2, or 10 s after InitPostEntity when data/gmodcraft/h2test.txt exists.
-- Needs MC mode, a paired Minecraft, gmodcraft_hybrid 1. Mouse/keys are injected (gmodcraft.input.inject),
-- look is set through gmodcraft.input.look (the camera and Minecraft follow it).
--   give     a GMod-side weapon_pistol reaches the Minecraft hotbar (McState.heldWeapon when selected)
--   fire     LMB fires it: clip down, the test NPC hurt; Minecraft got no mouse button events
--   reload   R reloads (clip back to full)
--   item     a normal Minecraft item: the hands, no viewmodel, LMB goes to Minecraft again
--   toolgun  weld two crates (LMB, LMB)
--   physgun  grab (LMB hold), freeze (RMB while holding), unfreeze (R)
--   use      context E (gmodcraft_use_mode 1): a door opens; on nothing E opens Minecraft's inventory
-- At most 2 screenshots (pistol held, physgun held). Cleans up: test entities, the pistol (stripped:
-- Minecraft's stack goes too), the hotbar slot it started on, every convar it touched.

local T = {}
gmodcraft.testH2 = T
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band = bit.band

local function log(fmt, ...) print("[gmodcraft-test] " .. string.format(fmt, ...)) end
local results = {}
local function check(name, ok, detail)
	results[#results + 1] = { name, ok }
	log("%s %s%s", ok and "PASS" or "FAIL", name, detail and (": " .. tostring(detail)) or "")
	return ok
end
local function wait(s)
	local t = RealTime() + s
	while RealTime() < t do coroutine.yield() end
end
local function waitUntil(fn, timeout)
	local deadline = RealTime() + timeout
	while RealTime() < deadline do
		if fn() then return true end
		coroutine.yield()
	end
	return fn() and true or false
end
local shotN = 0
local function shot(label)
	shotN = shotN + 1
	log("SHOT %d %s", shotN, label)
	RunConsoleCommand("jpeg")
	wait(0.5)
end
local I = function() return gmodcraft.input end
local function key(code, on) I().inject[code] = on or nil end
local function tap(code, holdS)
	key(code, true)
	wait(holdS or 0.15)
	key(code, false)
	wait(0.15)
end
local function M() return gmodcraft.clientLink.M end
local SLOTKEYS = { KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9 }
local HS = function() return gmodcraft.hybridShared end
local HC = function() return gmodcraft.hybridClient end

local function aimAt(pos)
	local ply = LocalPlayer()
	local a = (pos - ply:EyePos()):Angle()
	local look = I().look
	look.yaw = C.YawToMc(a.y)
	look.pitch = math.Clamp(math.NormalizeAngle(a.p), -89, 89)
	wait(0.6)
end
local function tagged(tag)
	for _, e in ipairs(ents.GetAll()) do
		if e:GetNW2String("gmodcraft_test", "") == tag then return e end
	end
end
-- Hotbar slots 1-9 (keys go to Minecraft): the first whose McState says pred.
local function findSlot(pred)
	for i = 1, 9 do
		tap(SLOTKEYS[i])
		wait(0.35)
		if pred() then return i end
	end
	return nil
end
local function heldIs(class) return function() return M().heldWeapon == HS().Hash(class) end end
-- Mouse events pushed to Minecraft since `since` (gmodcraft.input's log: "<t> mouse <b> <down>").
local function mcMouseEvents(since)
	local n = 0
	for _, l in ipairs(I().log) do
		local t, kind = l:match("^(%S+) (%S+)")
		if kind == "mouse" and tonumber(t) and tonumber(t) >= since then n = n + 1 end
	end
	return n
end
local function active() local w = LocalPlayer():GetActiveWeapon() return IsValid(w) and w or nil end

local function run()
	local ply = LocalPlayer()
	local CL = gmodcraft.clientLink
	local TU = gmodcraft.test
	log("H2 run: %s on %s", ply:Nick(), game.GetMap())
	local up = waitUntil(function() return CL.mcAlive and CL.McInWorld() and gmodcraft.IsPuppet(ply) end, 180)
	check("Minecraft attached and driving (MC mode)", up)
	if not up then log("H2TEST DONE (aborted)") return end
	local hy = waitUntil(function() return HC().Hybrid(ply) end, 20)
	check("hybrid mode active for this player (NW2 gmodcraft_hybrid)", hy)
	if not hy then log("H2TEST DONE (aborted)") return end
	T.startSlot = (M().heldSlot or 0) + 1
	TU.SetConVarTemp("gmodcraft_use_mode", "0")

	-- One free hotbar slot does all the work (nothing in the player's hotbar is overwritten): a weapon
	-- is brought there by losing it in GMod (Take) and getting it back (Give -> Minecraft's first free
	-- slot, the hotbar's first). No Minecraft dev commands needed (Prism's Minecraft runs without them).
	local free
	T.owned = {}
	for _, c in ipairs({ "weapon_pistol", "gmod_tool", "weapon_physgun" }) do T.owned[c] = ply:HasWeapon(c) end
	local function bring(class)
		if ply:HasWeapon(class) then
			RunConsoleCommand("gmodcraft_test_h2_strip", class)
			waitUntil(function() return not ply:HasWeapon(class) end, 3)
			wait(1.5)  -- Minecraft takes its stack out
		end
		RunConsoleCommand("gmodcraft_test_h2_give", class)
		wait(1.5)
		if free then
			tap(SLOTKEYS[free])
			return waitUntil(heldIs(class), 2)
		end
		free = findSlot(heldIs(class))
		T.free = free
		return free ~= nil
	end
	T.bring = bring
	local function vacate(class)
		RunConsoleCommand("gmodcraft_test_h2_strip", class)
		waitUntil(function() return not ply:HasWeapon(class) end, 3)
		wait(1.5)
	end

	-- give: GMod side -> the Minecraft hotbar
	local slot = bring("weapon_pistol") and free or nil
	check("give weapon_pistol (GMod side): its item is in the Minecraft hotbar", slot ~= nil, slot and ("slot " .. slot) or "not in slots 1-9 (hotbar full?)")
	if not slot then log("H2TEST DONE (aborted)") return end
	local sel = waitUntil(function() local w = active() return w and w:GetClass() == "weapon_pistol" end, 3)
	check("holding it selects it in GMod (usercmd)", sel, active() and active():GetClass())
	check("the viewmodel is drawn for it (first person) and the ammo HUD shows", HC().DrawViewModel(ply, active()) and HC().ActiveHeld(ply) ~= nil)

	-- fire at the test NPC
	RunConsoleCommand("gmodcraft_test_h2_spawn")
	local npc
	waitUntil(function() npc = tagged("h2npc") return IsValid(npc) end, 5)
	wait(1)
	if not check("test NPC and crates spawned", IsValid(npc)) then log("H2TEST DONE (aborted)") return end
	aimAt(npc:WorldSpaceCenter() + Vector(0, 0, 8))
	shot("pistol held, aimed at the test NPC (viewmodel alignment, no Minecraft hand)")
	local w = active()
	local clip0, hp0 = w:Clip1(), npc:GetNW2Int("gmodcraft_test_hp", 100)
	local since = RealTime()
	for _ = 1, 3 do
		tap(MOUSE_LEFT, 0.1)
		wait(0.35)
	end
	wait(0.5)
	local clip1, hp1 = w:Clip1(), IsValid(npc) and npc:GetNW2Int("gmodcraft_test_hp", 100) or 0
	check("LMB fires the pistol (clip went down)", clip1 < clip0, string.format("clip %d -> %d", clip0, clip1))
	check("the NPC was hurt", hp1 < hp0, string.format("hp %d -> %d", hp0, hp1))
	tap(MOUSE_RIGHT, 0.1)
	check("no mouse events went to Minecraft while the weapon was held (no block break / place)", mcMouseEvents(since) == 0,
		mcMouseEvents(since) .. " events")
	local reserve0 = ply:GetAmmoCount(w:GetPrimaryAmmoType())
	tap(KEY_R, 0.15)
	local full = waitUntil(function() return w:Clip1() == w:GetMaxClip1() end, 4)
	check("R reloads (clip full again)", full, string.format("clip %d / %d, reserve %d -> %d", w:Clip1(), w:GetMaxClip1(), reserve0,
		ply:GetAmmoCount(w:GetPrimaryAmmoType())))
	local fields = { HC().HostFields(ply) }
	check("HostState fields: hybrid + weapon active, clip and reserve", band(fields[1], 3) == 3 and fields[2] == w:Clip1(),
		table.concat(fields, " "))

	-- a normal Minecraft item: hands, no viewmodel, LMB to Minecraft
	local itemSlot = (T.startSlot ~= free) and T.startSlot or nil
	if itemSlot then
		tap(SLOTKEYS[itemSlot])
		if M().heldWeapon ~= 0 then itemSlot = nil end
	end
	itemSlot = itemSlot or findSlot(function() return M().heldWeapon == 0 end)
	check("a slot with a normal Minecraft item / nothing", itemSlot ~= nil, itemSlot and ("slot " .. itemSlot))
	if itemSlot then
		local hands = waitUntil(function() local a = active() return a and a:GetClass() == "gmodcraft_hands" end, 3)
		check("normal item: GMod holds the hands, no viewmodel, no ammo HUD", hands and HC().ActiveHeld(ply) == nil,
			active() and active():GetClass())
		local s2 = RealTime()
		tap(MOUSE_LEFT, 0.1)
		check("normal item: LMB goes to Minecraft again (dig / attack)", mcMouseEvents(s2) >= 2, mcMouseEvents(s2) .. " events")
	end

	-- toolgun: weld the two crates
	local a, b = tagged("h2a"), tagged("h2b")
	vacate("weapon_pistol")
	local toolSlot = bring("gmod_tool") and free or nil
	if check("toolgun item in the hotbar", toolSlot ~= nil and IsValid(a) and IsValid(b)) then
		TU.SetConVarTemp("gmod_toolmode", "weld")
		waitUntil(function() local x = active() return x and x:GetClass() == "gmod_tool" end, 3)
		wait(0.5)
		aimAt(a:WorldSpaceCenter())
		tap(MOUSE_LEFT, 0.1)
		wait(0.4)
		aimAt(b:WorldSpaceCenter())
		tap(MOUSE_LEFT, 0.1)
		wait(0.8)
		RunConsoleCommand("gmodcraft_test_h2_welds")
		local welded = waitUntil(function() return ply:GetNW2Int("gmodcraft_test_welds", 0) > 0 end, 3)
		check("toolgun weld: LMB on crate A, LMB on crate B -> welded", welded, ply:GetNW2Int("gmodcraft_test_welds", 0) .. " welds")
	end

	-- physgun: grab, freeze, unfreeze
	vacate("gmod_tool")
	local physSlot = bring("weapon_physgun") and free or nil
	if check("physgun item in the hotbar", physSlot ~= nil and IsValid(a)) then
		waitUntil(function() local x = active() return x and x:GetClass() == "weapon_physgun" end, 3)
		wait(0.5)
		local p0, f0, r0 = ply:GetNW2Int("gmodcraft_test_pickups", 0), ply:GetNW2Int("gmodcraft_test_freezes", 0), ply:GetNW2Int("gmodcraft_test_reloads", 0)
		aimAt(a:WorldSpaceCenter())
		key(MOUSE_LEFT, true)
		wait(0.8)
		check("physgun: LMB grabs the crate", ply:GetNW2Int("gmodcraft_test_pickups", 0) > p0)
		shot("physgun holding a crate (beam, viewmodel)")
		tap(MOUSE_RIGHT, 0.1)
		key(MOUSE_LEFT, false)
		wait(0.5)
		check("physgun: RMB while holding freezes it", ply:GetNW2Int("gmodcraft_test_freezes", 0) > f0)
		aimAt(a:WorldSpaceCenter())
		tap(KEY_R, 0.15)
		wait(0.5)
		check("physgun: R unfreezes", ply:GetNW2Int("gmodcraft_test_reloads", 0) > r0)
	end

	-- context E: a door, then nothing (Minecraft's inventory)
	vacate("weapon_physgun")
	if itemSlot then tap(SLOTKEYS[itemSlot]) end
	TU.SetConVarTemp("gmodcraft_use_mode", "1")
	RunConsoleCommand("gmodcraft_test_close_doors")
	RunConsoleCommand("gmodcraft_test_goto_use")
	wait(3)
	local door, best
	for _, e in ipairs(ents.FindByClass("prop_door_rotating")) do
		local d = e:GetPos():Distance(ply:GetPos())
		if not best or d < best then door, best = e, d end
	end
	if check("a door within reach", IsValid(door) and best < 140, best and string.format("%.0f units", best)) then
		waitUntil(function() return gmodcraft.IsPuppet(ply) end, 6)
		aimAt(door:WorldSpaceCenter())
		local u0 = ply:GetNW2Int("gmodcraft_test_uses", 0)
		local s3 = RealTime()
		tap(KEY_E, 0.3)
		wait(0.5)
		check("context E on a door: GMod +use (the door was used)", ply:GetNW2Int("gmodcraft_test_uses", 0) > u0
			and ply:GetNW2String("gmodcraft_test_lastuse", "") == "prop_door_rotating", ply:GetNW2String("gmodcraft_test_lastuse", ""))
		check("context E on a door: Minecraft's inventory stayed closed", not CL.McScreenOpen())
	end
	local look = I().look
	look.pitch = -85
	wait(0.6)
	tap(KEY_E, 0.15)
	local inv = waitUntil(function() return CL.McScreenOpen() end, 2)
	check("context E on nothing: Minecraft's inventory opens", inv)
	if inv then
		I().Tap(41)  -- Esc to Minecraft: closes its screen
		check("inventory closed again", waitUntil(function() return not CL.McScreenOpen() end, 2))
	end
	look.pitch = 0
	if T.startSlot then tap(SLOTKEYS[T.startSlot]) end  -- the hotbar slot it started on
end

-- After the run: the weapons the player had are theirs again (in GMod and as Minecraft items; they
-- land in Minecraft's first free slots, so possibly the hotbar slot the test used), a weapon the
-- test gave is gone.
function T.RestoreWeapons()
	local ply = LocalPlayer()
	if not T.owned then return end
	for class, had in pairs(T.owned) do
		if had and not ply:HasWeapon(class) then
			RunConsoleCommand("gmodcraft_test_h2_give", class)
			wait(1.5)
			log("restored %s (%s)", class, ply:HasWeapon(class) and "back" or "NOT back")
		elseif not had and ply:HasWeapon(class) then
			RunConsoleCommand("gmodcraft_test_h2_strip", class)
			wait(1.5)
			log("removed the test's %s", class)
		end
	end
	if T.startSlot then tap(SLOTKEYS[T.startSlot]) end
end

local co
function T.Start()
	if co then return end
	results = {}
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then log("ERROR %s", tostring(err)) end
		-- cleanup, whatever happened
		for c in pairs(gmodcraft.input.inject) do gmodcraft.input.inject[c] = nil end
		local okR, errR = pcall(T.RestoreWeapons)
		if not okR then log("ERROR restoring weapons: %s", tostring(errR)) end
		RunConsoleCommand("gmodcraft_test_h2_cleanup")
		gmodcraft.test.RestoreConVars("end of run")
		local nf = 0
		for _, x in ipairs(results) do if not x[2] then nf = nf + 1 end end
		log("H2TEST DONE: %d checks, %d failed%s", #results, nf, ok and "" or " (error)")
	end)
	hook.Add("Think", "gmodcraft_test_h2", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_h2")
			co = nil
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_h2", T.Start)
hook.Add("InitPostEntity", "gmodcraft_test_h2", function()
	if file.Exists("gmodcraft/h2test.txt", "DATA") then
		log("h2test.txt present: starting in 10 s")
		timer.Simple(10, T.Start)
	end
end)
