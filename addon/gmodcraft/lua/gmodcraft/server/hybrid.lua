-- Hybrid mode, part 1 (D-016, protocol v17): every paired player's GMod weapons mirror the
-- gmodcraft:gmod_weapon stacks in their Minecraft inventory (McWeaponSets). The MC server's
-- inventory owns possession (it persists, multiplayer included); GMod owns acquisition, ammo and
-- firing. Replaces the MC-mode weapon strip of server/players.lua while gmodcraft_hybrid is 1.
--
-- Every tick, per player with a weapon set (paired and mapped), HY.Plan (pure, unit-tested in
-- module/test/hybrid_test.py) compares the owned classes with the set:
--  * in the set, not owned: Give it here (clip from the stack), unless GMod just dropped it (then
--    a Take goes to Minecraft) or HY.Allow refuses it. Every entry from Minecraft is gated (creative
--    can forge any stack): spawn list + Spawnable, not AdminOnly (admins / singleplayer excepted),
--    then PlayerGiveSWEP == true (fail closed); a refusal lasts while the stack stays in the set;
--  * owned, not in the set: newly acquired here (pickup, spawn menu) -> a Give event to Minecraft;
--    held for 0.5 s since Minecraft removed it (Q drop, chest, death drop) -> a State event (its
--    clips, onto the dropped item) and StripWeapon;
--  * a Give / Take in flight (until the set's lastRequestId reaches it, at most 3 s) suspends both
--    rules for that class; nothing happens while the player is dead.
-- After a (re)spawn or the first set: loadout weapons Minecraft doesn't have are given to it
-- (gmodcraft_hybrid_loadout_union 1, the union) or stripped (0, strict Minecraft inventory).
-- The hands SWEP (gmodcraft_hands) is never mirrored; it is what a player in MC mode holds.

local HY = gmodcraft.hybrid or {}
gmodcraft.hybrid = HY
local HS = gmodcraft.hybridShared
local K = gmodcraft.K or {}

local cvHybrid = CreateConVar("gmodcraft_hybrid", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: 1 = paired players' GMod weapons mirror the GMod weapon items in their Minecraft inventory (hybrid mode); 0 = MC mode strips GMod weapons (before v17)")
local cvUnion = CreateConVar("gmodcraft_hybrid_loadout_union", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft hybrid mode: 1 = after a respawn the GMod loadout is added to the Minecraft inventory; 0 = the Minecraft inventory alone decides")

HY.DEBOUNCE = 0.5        -- s a weapon must stay missing from the set before it is stripped
HY.PENDING_TIMEOUT = 3   -- s a Give / Take may stay unacknowledged
HY.states = HY.states or {}
HY.nextRequest = HY.nextRequest or 1

function HY.Enabled()
	return cvHybrid:GetBool()
end

-- ---- the planner (no GMod API: module/test/hybrid_test.py drives it) ---------------------------
-- hs:    per-player state { known, refused, pending, missing, lastOwned, policy } (HY.NewState)
-- owned: class -> clip1 of the weapons the player has now (hands excluded)
-- set:   { lastRequestId, items = { { hash, clip1 }, ... } } from McWeaponSets
-- opts:  { now, union, resolve(hash) -> class|nil, allow(class) -> ok, why, debounce, timeout }
-- Returns { give = { {class, clip1} }, strip = { {class, clip1} }, events = { {kind, class, clip1} },
--           unknown = { hash, ... }, refused = { {class, why} } } and updates hs.
function HY.NewState()
	return { known = {}, refused = {}, pending = {}, missing = {}, lastOwned = nil, policy = "first" }
end

function HY.Plan(hs, owned, set, opts)
	local now = opts.now
	local out = { give = {}, strip = {}, events = {}, unknown = {}, refused = {} }
	-- acknowledged or stale requests end
	for class, p in pairs(hs.pending) do
		if (p.req and (set.lastRequestId or 0) >= p.req) or now - p.at > (opts.timeout or HY.PENDING_TIMEOUT) then hs.pending[class] = nil end
	end
	local inSet = {}
	for _, it in ipairs(set.items or {}) do
		local class = opts.resolve(it.hash)
		if class == HS.HANDS then
			-- a forged hands stack: never mirrored (the player always has the hands anyway)
		elseif class then
			if inSet[class] == nil then inSet[class] = it.clip1 end
		else
			out.unknown[#out.unknown + 1] = it.hash
		end
	end
	-- A refusal lasts while the stack stays in the set; when it comes back it is asked again.
	for class in pairs(hs.refused) do
		if inSet[class] == nil then hs.refused[class] = nil end
	end
	local last = hs.lastOwned or {}
	local spawnPolicy = hs.policy
	hs.policy = nil
	local after = {}
	for class in pairs(owned) do after[class] = true end

	-- in Minecraft, not here
	for class, clip in pairs(inSet) do
		local p = hs.pending[class]
		if owned[class] == nil and p then
			if p.kind == "give" then
				-- GMod got it and lost it again before Minecraft answered: take it back out.
				out.events[#out.events + 1] = { kind = "take", class = class }
				hs.pending[class] = { kind = "take", at = now }
			end
		elseif owned[class] == nil then
			if last[class] and not spawnPolicy then
				-- GMod took it away (dropped, stripped by something else): Minecraft follows.
				out.events[#out.events + 1] = { kind = "take", class = class }
				hs.pending[class] = { kind = "take", at = now }
			elseif hs.refused[class] then
				-- refused before: stays inert
			else
				-- Every entry from Minecraft passes the gate, known class or not: a stack can be
				-- forged in creative for any class this player once had (or that was dropped by an admin).
				local ok, why = opts.allow(class)
				if ok then
					hs.known[class] = true
					out.give[#out.give + 1] = { class = class, clip1 = clip }
					after[class] = true
				else
					hs.refused[class] = why or "refused"
					out.refused[#out.refused + 1] = { class = class, why = why or "refused" }
				end
			end
		end
	end
	-- here, not in Minecraft
	for class, clip in pairs(owned) do
		if inSet[class] == nil then
			local p = hs.pending[class]
			if p then
				hs.missing[class] = nil
				if p.kind == "take" and not last[class] then
					-- dropped here and picked up again before Minecraft answered: give it back
					out.events[#out.events + 1] = { kind = "give", class = class, clip1 = clip }
					hs.pending[class] = { kind = "give", at = now }
				end
			elseif spawnPolicy and not opts.union then
				-- strict: after a spawn only Minecraft's inventory counts
				out.strip[#out.strip + 1] = { class = class, clip1 = clip, quiet = true }
				after[class] = nil
				hs.missing[class] = nil
			elseif spawnPolicy or not last[class] then
				-- picked up here (or the loadout, with the union): Minecraft gets a stack
				hs.known[class] = true
				hs.refused[class] = nil
				out.events[#out.events + 1] = { kind = "give", class = class, clip1 = clip }
				hs.pending[class] = { kind = "give", at = now }
				hs.missing[class] = nil
			else
				-- Minecraft removed it: strip after the debounce, its clips go onto the dropped item
				hs.missing[class] = hs.missing[class] or now
				if now - hs.missing[class] >= (opts.debounce or HY.DEBOUNCE) then
					out.events[#out.events + 1] = { kind = "state", class = class, clip1 = clip }
					out.strip[#out.strip + 1] = { class = class, clip1 = clip }
					after[class] = nil
					hs.missing[class] = nil
				end
			end
		else
			hs.missing[class] = nil
			hs.known[class] = true
		end
	end
	for class in pairs(hs.missing) do
		if owned[class] == nil then hs.missing[class] = nil end
	end
	hs.lastOwned = after
	return out
end

-- A Give / Take that never reached the ring (full, no link): forget the request so the next plan
-- sends it again, instead of the pending guard timing out and the weapon being stripped.
--  * give: GMod still has it and Minecraft doesn't -> not "owned last tick", so it is re-sent;
--  * take: GMod lost it and Minecraft still lists it -> "owned last tick", so the Take is re-sent
--    (clearing it would make the planner give the weapon back from the Minecraft set).
function HY.SendFailed(hs, ev)
	hs.pending[ev.class] = nil
	hs.lastOwned = hs.lastOwned or {}
	if ev.kind == "give" then
		hs.lastOwned[ev.class] = nil
	elseif ev.kind == "take" then
		hs.lastOwned[ev.class] = true
	end
end

-- A change of admin status re-opens the gate for every stack it refused.
function HY.NoteAdmin(hs, admin)
	if hs.wasAdmin ~= nil and hs.wasAdmin ~= admin then hs.refused = {} end
	hs.wasAdmin = admin
end

-- This tick's set for a player: the one read, or (once in a row, while active) the last one.
function HY.PickSet(hs, set)
	if set then
		hs.lastSet, hs.missed = set, 0
		return set
	end
	if hs.active and hs.lastSet and (hs.missed or 0) < 1 then
		hs.missed = (hs.missed or 0) + 1
		return hs.lastSet
	end
	hs.lastSet = nil
	return nil
end

-- ---- GMod glue -------------------------------------------------------------------------------------
local function stateOf(ply)
	local hs = HY.states[ply]
	if not hs then
		hs = HY.NewState()
		HY.states[ply] = hs
	end
	return hs
end

-- Security: a class first seen from Minecraft must pass the gamemode's PlayerGiveSWEP (sandbox:
-- Spawnable, AdminOnly). Fail closed: anything but true refuses.
function HY.Allow(ply, class)
	-- Sandbox's GM:PlayerGiveSWEP returns true unconditionally; Spawnable / AdminOnly are enforced
	-- only by its gm_giveswep command (CCGiveSWEP). Do the same checks here first.
	local e
	if list and list.GetEntry then e = list.GetEntry("Weapon", class)
	elseif list and list.Get then e = list.Get("Weapon")[class] end
	local swep = e or (weapons and weapons.Get(class))
	if not swep and not HS.BUILTIN[class] then return false, "not installed on this server" end
	local isAdmin = (ply.IsAdmin ~= nil and ply:IsAdmin() == true) or (game ~= nil and game.SinglePlayer ~= nil and game.SinglePlayer() == true)
	if (not e or not e.Spawnable) and not isAdmin then return false, "not spawnable (admins only)" end
	if e and e.AdminOnly and not isAdmin then return false, "admin only" end
	local ok = hook.Run("PlayerGiveSWEP", ply, class, swep or { ClassName = class })
	if ok ~= true then return false, "PlayerGiveSWEP refused" end
	return true
end

local function owned(ply)
	local t = {}
	for _, w in ipairs(ply:GetWeapons()) do
		local c = w:GetClass()
		if c ~= HS.HANDS then t[c] = w:Clip1() end
	end
	return t
end

local function nextRequest()
	local id = HY.nextRequest
	HY.nextRequest = id % 2147483647 + 1
	return id
end

-- Sends one planned event; sets the pending request id. Returns true when it went out.
local function sendEvent(ply, hs, ev)
	local sid = ply:SteamID64()
	if not sid then return false end
	local h = HS.Hash(ev.class)
	if ev.kind == "give" then
		local wep = ply:GetWeapon(ev.class)
		local cat, name = HS.Describe(ev.class, IsValid(wep) and wep or nil)
		local id = nextRequest()
		local ok, err = gmodcraft.PushWeaponGive({ steamId = sid, requestId = id, hash = h, category = cat, class = ev.class, name = name,
			clip1 = ev.clip1 or -1, clip2 = IsValid(wep) and wep:Clip2() or -1 })
		if hs.pending[ev.class] then hs.pending[ev.class].req = id end
		if not ok then gmodcraft.Info("hybrid: give %s to %s's Minecraft failed: %s", ev.class, ply:Nick(), tostring(err)) end
		gmodcraft.Log("hybrid", "%s: %s -> Minecraft (give %d)", ply:Nick(), ev.class, id)
		return ok == true
	elseif ev.kind == "take" then
		local id = nextRequest()
		if hs.pending[ev.class] then hs.pending[ev.class].req = id end
		gmodcraft.Log("hybrid", "%s: %s gone in GMod -> Minecraft (take %d)", ply:Nick(), ev.class, id)
		return gmodcraft.PushHostEvent({ type = K.HostEvWeaponTake, steamId = sid, requestId = id, hash = h })
	elseif ev.kind == "state" then
		local wep = ply:GetWeapon(ev.class)
		return gmodcraft.PushHostEvent({ type = K.HostEvWeaponState, steamId = sid, hash = h, x = ev.clip1 or -1,
			y = IsValid(wep) and wep:Clip2() or -1 })
	end
	return false
end

local function ensureHands(ply, mcMode)
	if not ply:HasWeapon(HS.HANDS) and weapons.GetStored and weapons.GetStored(HS.HANDS) then
		ply:Give(HS.HANDS)
	end
	-- H2: the client's usercmd selects what Minecraft's hand holds (client/hybrid.lua HC.Select);
	-- here only a player left without any weapon gets the hands.
	if mcMode and ply:HasWeapon(HS.HANDS) and not IsValid(ply:GetActiveWeapon()) then HY.SelectQuiet(ply, HS.HANDS) end
end

local function apply(ply, hs, plan)
	for _, g in ipairs(plan.give) do
		local w = ply:Give(g.class, true)
		if IsValid(w) then
			if g.clip1 and g.clip1 >= 0 and w.SetClip1 then w:SetClip1(g.clip1) end
			HS.Learn(g.class)
			gmodcraft.Log("hybrid", "%s: %s from Minecraft (clip %s)", ply:Nick(), g.class, tostring(g.clip1))
		else
			hs.refused[g.class] = "Give failed"
			if hs.lastOwned then hs.lastOwned[g.class] = nil end
			gmodcraft.Info("hybrid: %s's Minecraft has %s, which GMod can't give (not installed?); the item stays inert", ply:Nick(), g.class)
		end
	end
	for _, e in ipairs(plan.events) do
		if e.kind == "state" then sendEvent(ply, hs, e) end  -- before the strip: the clips onto the dropped item
	end
	for _, s in ipairs(plan.strip) do
		ply:StripWeapon(s.class)
		gmodcraft.Log("hybrid", "%s: %s %s", ply:Nick(), s.class, s.quiet and "not in the Minecraft inventory after the spawn (strict loadout)" or "left the Minecraft inventory: stripped")
	end
	for _, e in ipairs(plan.events) do
		if e.kind ~= "state" and not sendEvent(ply, hs, e) then HY.SendFailed(hs, e) end
	end
	for _, r in ipairs(plan.refused) do
		gmodcraft.Info("hybrid: refused %s from %s's Minecraft inventory (%s); the item stays inert", r.class, ply:Nick(), r.why)
	end
	for _, h in ipairs(plan.unknown) do
		hs.unknownLogged = hs.unknownLogged or {}
		if not hs.unknownLogged[h] then
			hs.unknownLogged[h] = true
			gmodcraft.Log("hybrid", "%s: Minecraft weapon hash %08x is no class this server knows (or a collision); inert", ply:Nick(), h)
		end
	end
end

-- After PL.Tick: one plan per player with a weapon set.
function HY.Tick()
	if not HY.Enabled() or not gmodcraft.McWeaponSets then return end
	local now = CurTime()
	if not HS.refreshedAt or now - HS.refreshedAt > 10 then HS.Refresh() end
	local sets = {}
	for _, s in ipairs(gmodcraft.McWeaponSets()) do sets[s.steamId] = s end
	local PL = gmodcraft.player
	local union = cvUnion:GetBool()
	for _, ply in ipairs(player.GetHumans()) do
		local st = PL.Get(ply)
		local sid = ply:SteamID64()
		local set = sid and sets[sid]
		local hs = stateOf(ply)
		-- One tick without this player's set (a torn read, a shift in McPlayers order) keeps the last
		-- one: no strip-and-give-back flicker. Two in a row end hybrid mode for the player.
		set = HY.PickSet(hs, set)
		HY.NoteHeld(hs, set)
		local active = set ~= nil and st ~= nil and st.paired and st.mapped
		-- A promotion (or demotion) re-opens the gate for stacks it refused before.
		HY.NoteAdmin(hs, ply:IsAdmin() == true)
		ply:SetNW2Bool("gmodcraft_hybrid", active == true)
		if not active then
			if hs.active then
				hs.active = false
				hs.policy = "first"  -- the next set is a fresh start (loadout policy)
				if ply:HasWeapon(HS.HANDS) then ply:StripWeapon(HS.HANDS) end
			end
		elseif ply:Alive() then
			hs.active = true
			ensureHands(ply, st.mcMode)
			if hs.mcMode ~= st.mcMode then
				-- F6 to GMod mode: a real weapon in hand again (the hands draw nothing).
				if hs.mcMode and not st.mcMode and ply:HasWeapon("weapon_physgun") then HY.SelectQuiet(ply, "weapon_physgun") end
				hs.mcMode = st.mcMode
			end
			local plan = HY.Plan(hs, owned(ply), set, {
				now = now, union = union, resolve = HS.Resolve, allow = function(class) return HY.Allow(ply, class) end,
			})
			apply(ply, hs, plan)
		end
	end
end

-- The loadout policy applies to the first plan after every spawn.
hook.Add("PlayerSpawn", "gmodcraft_hybrid", function(ply)
	local hs = HY.states[ply]
	if hs then hs.policy = "spawn" end
end)

-- A weapon a player gets (pickup, spawn menu, Give from other code) teaches the hash map its class.
hook.Add("WeaponEquip", "gmodcraft_hybrid", function(wep)
	if IsValid(wep) then HS.Learn(wep:GetClass()) end
end)

-- H3: whether the physgun holds something (the client routes the wheel and freezes the look for
-- rotating only then).
hook.Add("OnPhysgunPickup", "gmodcraft_hybrid", function(ply, ent)
	if IsValid(ply) then ply:SetNW2Bool("gmodcraft_physgun_holding", true) end
end)
hook.Add("PhysgunDrop", "gmodcraft_hybrid", function(ply, ent)
	if IsValid(ply) then ply:SetNW2Bool("gmodcraft_physgun_holding", false) end
end)

hook.Add("PlayerDisconnected", "gmodcraft_hybrid", function(ply)
	HY.states[ply] = nil
end)

-- The hands are never dropped (Q in GMod mode would otherwise throw them).
hook.Add("PlayerDroppedWeapon", "gmodcraft_hybrid", function(ply, wep)
	if IsValid(wep) and wep:GetClass() == HS.HANDS then wep:Remove() end
end)

-- ---- v45: GMod's own weapon switches select the Minecraft stack ------------------------------------
-- In MC mode Minecraft's hand decides the weapon (the client's usercmd selects McState.heldWeapon
-- every tick), so a switch GMod makes on its own (a spawnmenu tool click: gmod_tool <mode>; the
-- weapon tab: gm_giveswep; any Lua ply:SelectWeapon) would be undone at once. Instead, when the
-- weapon's stack is in the Minecraft inventory, Minecraft is asked to put it into the hand
-- (kHostEvSelectWeapon: hotbar slot selected, main inventory swapped into the hotbar) and the switch
-- is held off here; the usercmd then switches once Minecraft holds it (no flicker).
-- Origin: only a Lua SelectWeapon marks a request (the Player:SelectWeapon wrapper below). Usercmd
-- switches (Minecraft's hand, which includes the follow-up after a request) carry no mark and never
-- send, whatever the published held hash says (it lags the client's by up to a Minecraft tick).
-- The engine's own `use <class>` command isn't Lua and isn't seen: Minecraft's hand wins there.
HY.SELECT_MARK = 0.25    -- s a SelectWeapon request waits for its PlayerSwitchWeapon
HY.SELECT_PENDING = 1    -- s a sent request holds off repeats of its class (until Minecraft holds it)

-- A Lua SelectWeapon(class) for a player in hybrid mode: remembered for the switch it causes.
-- Not when that weapon is in hand already (a stool click with the toolgun out changes the mode only),
-- and not while a (re)spawn's loadout policy is pending (hs.policy, until the first plan after it):
-- the gamemode's own spawn select (PlayerLoadout -> SwitchToDefaultWeapon, cl_defaultweapon) is
-- not the player's choice. Only the latest request counts: one SelectWeapon causes one switch, so
-- a mark for another class replaces an older one that never switched.
function HY.MarkSelect(hs, class, activeClass, now)
	if not hs or not hs.active or hs.policy ~= nil or type(class) ~= "string" then return end
	class = string.lower(class)
	if class == activeClass then
		hs.selMark = nil
		return
	end
	hs.selMark = { class = class, at = now }
end

-- Our own selects (hands, F6 back to GMod mode, weapons restored on activation): never a request.
function HY.SelectQuiet(ply, class)
	local hs = HY.states[ply]
	if hs then hs.selMark = nil end
	return (HY.rawSelectWeapon or ply.SelectWeapon)(ply, class)
end

-- Minecraft's hand holds what was asked for: repeats may be asked again.
function HY.NoteHeld(hs, set)
	if hs.selPending and set and set.held == hs.selPending.hash then hs.selPending = nil end
end

-- One PlayerSwitchWeapon (pure; module/test/hybrid_test.py). ctx = { now, class, hash, held (the
-- set's held hash), inSet (a stack of it in the Minecraft inventory), mcMode (paired, MC mode, alive) }.
-- Returns "allow" (no event), "send" (send kHostEvSelectWeapon, hold the switch off) or "hold" (a
-- request for it is in flight: hold off, no second event).
function HY.SwitchPlan(hs, ctx)
	local m = hs.selMark
	if not m or m.class ~= ctx.class then return "allow" end
	hs.selMark = nil
	if ctx.now - m.at > HY.SELECT_MARK then return "allow" end
	if not ctx.mcMode or ctx.class == HS.HANDS or not ctx.inSet or ctx.hash == ctx.held then return "allow" end
	local p = hs.selPending
	if p and p.hash == ctx.hash and ctx.now - p.at < HY.SELECT_PENDING then return "hold" end
	hs.selPending = { hash = ctx.hash, at = ctx.now }
	return "send"
end

local PLAYER = FindMetaTable and FindMetaTable("Player")
if PLAYER and PLAYER.SelectWeapon then
	HY.rawSelectWeapon = HY.rawSelectWeapon or PLAYER.SelectWeapon  -- once (a Lua refresh re-runs this file)
	PLAYER.SelectWeapon = function(ply, class, ...)
		local hs = HY.states[ply]
		if hs and hs.active then
			local w = ply:GetActiveWeapon()
			HY.MarkSelect(hs, class, IsValid(w) and w:GetClass() or nil, CurTime())
		end
		return HY.rawSelectWeapon(ply, class, ...)
	end
end

hook.Add("PlayerSwitchWeapon", "gmodcraft_hybrid_select", function(ply, old, new)
	local hs = HY.states[ply]
	if not (hs and hs.selMark and IsValid(new)) then return end
	local class = new:GetClass()
	local h = HS.Hash(class)
	local set = hs.lastSet
	local inSet = false
	for _, it in ipairs(set and set.items or {}) do
		if it.hash == h then inSet = true break end
	end
	local st = gmodcraft.player and gmodcraft.player.Get(ply)
	local plan = HY.SwitchPlan(hs, { now = CurTime(), class = class, hash = h, held = set and set.held or 0, inSet = inSet,
		mcMode = hs.active == true and st ~= nil and st.mcMode == true and ply:Alive() })
	if plan == "send" then
		local sid = ply:SteamID64()
		if not (sid and K.HostEvSelectWeapon and gmodcraft.PushHostEvent({ type = K.HostEvSelectWeapon, steamId = sid, hash = h })) then
			hs.selPending = nil
			return  -- not sent: GMod switches and Minecraft's hand takes it back, as before v45
		end
		gmodcraft.Log("hybrid", "%s: GMod switched to %s -> Minecraft selects it", ply:Nick(), class)
		return true
	elseif plan == "hold" then
		return true
	end
end)

-- ---- S1 (v35): a weapon dropped onto a Minecraft hotbar slot (the spawnmenu's Minecraft tab) -----
-- The same gate as every weapon from Minecraft (HY.Allow: spawn list, Spawnable, AdminOnly,
-- PlayerGiveSWEP). GMod gives it first (normal ammo; the planner then mirrors it as for any
-- spawnmenu weapon), and a Give with the target slot puts the stack there (or moves the one the
-- player has). Whichever of the two Gives Minecraft sees second finds the class there already.
HY.DROP_INTERVAL = 0.25  -- s between two drops of one player
function HY.GiveToSlot(ply, class, slot)
	if not HY.Enabled() then return false, "hybrid mode is off on this server" end
	local hs = HY.states[ply]
	if not hs or not hs.active then return false, "your Minecraft isn't paired with this server" end
	if not ply:Alive() then return false, "dead" end
	if not gmodcraft.PushWeaponGive then return false, "no server link" end
	if class == HS.HANDS then return false, "not a weapon" end
	local ok, why = HY.Allow(ply, class)
	if not ok then return false, why end
	if not ply:HasWeapon(class) then ply:Give(class) end
	local wep = ply:GetWeapon(class)
	if not IsValid(wep) then return false, "GMod couldn't give it" end
	HS.Learn(class)
	local cat, name = HS.Describe(class, wep)
	local id = nextRequest()
	local sent, err = gmodcraft.PushWeaponGive({ steamId = ply:SteamID64(), requestId = id, hash = HS.Hash(class), category = cat, class = class,
		name = name, clip1 = wep:Clip1(), clip2 = wep:Clip2(), slot = slot })
	if not sent then return false, tostring(err) end
	gmodcraft.Log("hybrid", "%s: %s dropped onto Minecraft slot %d (give %d)", ply:Nick(), class, slot, id)
	return true
end

function HY.OnDropMessage(ply, class, slot)
	if not IsValid(ply) then return end
	class = string.lower(class or "")
	local now = RealTime()
	if now - (ply.gmodcraftDropAt or 0) < HY.DROP_INTERVAL then return end
	ply.gmodcraftDropAt = now
	if not slot or slot > 8 or class == "" or #class > 64 or class:find("[^%w_%-%.]") then return end
	local ok, why = HY.GiveToSlot(ply, class, slot)
	if not ok then ply:ChatPrint("Garry's Modcraft: can't put " .. class .. " into the hotbar: " .. tostring(why)) end
	return ok, why
end
if net and net.Receive and gmodcraft.NET and gmodcraft.NET.mcDrop then
	net.Receive(gmodcraft.NET.mcDrop, function(_, ply)
		HY.OnDropMessage(ply, net.ReadString(), net.ReadUInt(8))
	end)
end

function HY.DebugTable()
	local out = {}
	for ply, hs in pairs(HY.states) do
		if IsValid(ply) then
			local pending, refused = {}, {}
			for c, p in pairs(hs.pending) do pending[#pending + 1] = string.format("%s:%s#%s", c, p.kind, tostring(p.req)) end
			for c, why in pairs(hs.refused) do refused[#refused + 1] = c .. " (" .. tostring(why) .. ")" end
			out[#out + 1] = { nick = ply:Nick(), active = hs.active, pending = pending, refused = refused }
		end
	end
	return out
end
