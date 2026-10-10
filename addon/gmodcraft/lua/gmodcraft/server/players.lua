-- Server side of each player (docs/DESIGN.md sections 4 and 5):
--  * identity: the client forwards its Minecraft's McIdentity (net gmodcraft_identity); every
--    GMod player goes into HostPlayers on the server link with it, and McPlayers tells us when
--    the MC server has mapped the SteamID64;
--  * teleports: GMod decides where the player is (spawn, SetPos, mode switch); kHostEvTeleport /
--    kHostEvRespawn with one request outstanding per player; the puppet holds until the ack;
--  * the puppet: the client's reports (net gmodcraft_pos, unreliable, once per tick) after the
--    sanity limits of section 4.3 drive SetupMove (shared/puppet.lua);
--  * F6 modes: MC mode strips GMod weapons (remembered), GMod mode gives them back and Minecraft
--    follows the GMod player by teleport.
--  * vehicles and seats (section 4.2, P6i): while seated GMod drives, in either mode. The puppet is
--    off, Minecraft follows the seat like GMod mode does (kTeleportReasonVehicle: the MC server
--    keeps the player free of fall damage until the next other teleport), and on leaving the seat
--    Minecraft is put where GMod set the player down (a plain SetPos teleport), then drives again.
--  * HostPlayers is slot-indexed (v14): a player keeps slot entIndex - 1 while connected; its
--    record carries the hash of its current join token (P6b, server/mp.lua).

local PL = gmodcraft.player or {}
gmodcraft.player = PL
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band, bor = bit.band, bit.bor
local Log = gmodcraft.Log

local cvMaxSpeed = CreateConVar("gmodcraft_max_speed", "4000", FCVAR_ARCHIVE,
	"Garry's Modcraft: largest speed (units/s) a client may report for its MC player (default 100 blocks/s: elytra, riptide)")
local cvMaxRate = CreateConVar("gmodcraft_max_report_rate", "100", FCVAR_ARCHIVE, "Garry's Modcraft: most position reports per second accepted")

local states = PL.states or {}
PL.states = states
PL.nextRequest = PL.nextRequest or 1
PL.hostPlayersKey = nil
PL.hostPlayersAt = 0

local ACK_TIMEOUT = (K.TeleportAckTimeoutMs or 5000) / 1000 + 1.5
local REPORT_FRESH = 0.5      -- s: a puppet whose client went quiet longer holds still
local VIOLATIONS_HOLD = 5     -- sanity rejections within 2 s that force a teleport back
local FOLLOW_STEP = 40        -- units: GMod mode re-teleports Minecraft after this much movement
local FOLLOW_STEP_SEATED = 80 -- units: the same while seated (a vehicle covers that many times a second)
local WORLD_LIMIT = 16384     -- units: Source's coordinate range (outside = garbage)
local INSIDE_RECOVER = 1.5    -- s: reports inside world brushes this long move the player back to a free spot
local HOLD_WINDOW, HOLD_CAP = 30, 3  -- at most this many forced moves back per window, then stop trying
local SAFE_KEEP, SAFE_STEP, SAFE_EVERY = 8, 16, 0.25  -- hull-verified positions kept / spacing (units) / rate (s)
-- The free-space check: the MC hull (0.6 blocks) a little shrunk, up to sneaking height, lifted
-- off the floor it stands on. World and brush entities only by default (props move: Minecraft's
-- smooth collider pushes the player out of a face cutting into it, and the MC client moves a player
-- shut inside a prop to the nearest free spot, PlayerPushOut, P6i).
local SAFE_MIN, SAFE_MAX = Vector(-11, -11, 2), Vector(11, 11, 56)

function PL.Get(ply)
	return states[ply]
end

local function state(ply)
	local st = states[ply]
	if not st then
		-- Bots have no Minecraft: they are listed in HostPlayers (kHostPlayerBot) and stay in GMod mode.
		st = { mcWanted = not ply:IsBot(), puppet = false, rejected = 0, accepted = 0, corrections = 0, violations = {}, rateT = 0, rateN = 0,
			needTeleport = "spawn", needAfter = 0, safe = {}, holds = {}, recoveries = 0 }
		states[ply] = st
	end
	return st
end

local function bad(v)
	return v ~= v or v == math.huge or v == -math.huge
end

local function contentsAt(pos)
	return string.format("contents 0x%x", util.PointContents(pos + Vector(0, 0, 8)))
end

-- Trace filter for the sanity checks: the player itself and the block collision entities (P5a,
-- server/blockcol.lua) are skipped. Minecraft moves the player against its own blocks, and slabs,
-- carpets and fences are full cubes on the GMod side, so those entities would read as "inside".
-- (Even *_BRUSHONLY masks hit them: they are CONTENTS_SOLID.)
local BLOCK_CLASS = "gmodcraft_blocks"
-- One filter function; the player it skips is set just before each (synchronous) trace, so no
-- per-player closure keeps the player alive.
local sanityPly
-- P6i (v30): the moving entity the player's Minecraft is carried by (and its parent) doesn't count
-- either while a report is checked: Minecraft follows the platform as the player's GMod client draws
-- it, which lags the server's, so a carried player overlaps it there (a rising elevator).
local sanityCarry
local function sanityFilterFn(e)
	if sanityCarry and (e == sanityCarry or e == sanityCarry:GetParent()) then return false end
	return e ~= sanityPly and e:GetClass() ~= BLOCK_CLASS and string.sub(e:GetClass(), 1, 17) ~= "gmodcraft_mcproxy"  -- B1: MC mobs' bodies (both parts) don't count
end
local function sanityFilter(ply)
	sanityPly = ply
	return sanityFilterFn
end

-- Room for the MC hull at pos? (mask: MASK_PLAYERSOLID_BRUSHONLY unless given)
local function hullFree(ply, pos, mask)
	local tr = util.TraceHull({ start = pos, endpos = pos, mins = SAFE_MIN, maxs = SAFE_MAX, mask = mask or MASK_PLAYERSOLID_BRUSHONLY, filter = sanityFilter(ply) })
	return not tr.StartSolid and not tr.AllSolid
end
PL.HullFree = hullFree

-- Is the player's body inside world brushes? A core column (12 wide, 20..52 up) rather than the
-- hull: the hull's corners clip a steep slope the feet stand on (a 0.62 displacement bank on
-- gm_construct read as "inside" and forced moves back), the core can't (at radius 6 even a 60
-- degree slope rises only ~10 units).
local CORE_MIN, CORE_MAX = Vector(-6, -6, 20), Vector(6, 6, 52)
-- Holes Minecraft dug into the map (P5b) are free for Minecraft but still brushes for GMod: solid
-- in a dug cell doesn't count. The core is cut along the MC block grid; only the pieces in cells
-- that aren't dug are traced, each shrunk by DUG_MARGIN (Minecraft's hull meets the hole walls at
-- the cell edge, its collision there isn't the brushes' to the unit).
local DUG_MARGIN = 2
local function solidBox(ply, lo, hi)
	local tr = util.TraceHull({ start = lo, endpos = lo, mins = Vector(0, 0, 0), maxs = hi - lo, mask = MASK_PLAYERSOLID_BRUSHONLY, filter = sanityFilter(ply) })
	return tr.StartSolid or tr.AllSolid
end

-- Is every solid bit of the core box lo..hi (Source) in a dug cell? Needs the slot origin and the
-- module's dug-cell store; false when either is missing.
local function solidOnlyInDugCells(ply, lo, hi)
	if not gmodcraft.ColDugCells or not C.slot.known then return false end
	local x0, y0, z1 = C.ToMc(lo)  -- MC z runs along -Source y: lo.y is the far MC z edge
	local x1, y1, z0 = C.ToMc(hi)
	x0, y0, z0, x1, y1, z1 = math.floor(x0), math.floor(y0), math.floor(z0), math.floor(x1), math.floor(y1), math.floor(z1)
	local flat = gmodcraft.ColDugCells(C.WorldId(), x0, y0, z0, x1, y1, z1)
	if not flat or #flat == 0 then return false end
	local dug = {}
	for i = 1, #flat - 2, 3 do dug[flat[i] .. "," .. flat[i + 1] .. "," .. flat[i + 2]] = true end
	for x = x0, x1 do
		for y = y0, y1 do
			for z = z0, z1 do
				if not dug[x .. "," .. y .. "," .. z] then
					-- The cell in Source coordinates (MC z + 1 is the low Source y edge), clipped to the core.
					local a, b = C.FromMc(x, y, z + 1), C.FromMc(x + 1, y + 1, z)
					local plo = Vector(math.max(a.x, lo.x) + DUG_MARGIN, math.max(a.y, lo.y) + DUG_MARGIN, math.max(a.z, lo.z) + DUG_MARGIN)
					local phi = Vector(math.min(b.x, hi.x) - DUG_MARGIN, math.min(b.y, hi.y) - DUG_MARGIN, math.min(b.z, hi.z) - DUG_MARGIN)
					if plo.x < phi.x and plo.y < phi.y and plo.z < phi.z and solidBox(ply, plo, phi) then return false end
				end
			end
		end
	end
	return true
end

-- True when the core is in solid; second value true when that solid is all in dug cells (then the
-- caller treats the position as free).
local function embedded(ply, pos)
	local tr = util.TraceHull({ start = pos, endpos = pos, mins = CORE_MIN, maxs = CORE_MAX, mask = MASK_PLAYERSOLID_BRUSHONLY, filter = sanityFilter(ply) })
	if not (tr.StartSolid or tr.AllSolid) then return false end
	if solidOnlyInDugCells(ply, pos + CORE_MIN, pos + CORE_MAX) then return false, true end
	-- D6: Source's solid with no map geometry there (outside the BSP: Minecraft terrain the player
	-- mined under a map's floor in a flat / underground world) is free for Minecraft too. The
	-- module answers from the map collision Minecraft gets (nil: no map indexed, keep the trace).
	if gmodcraft.ColMapSolid and C.slot.known then
		local lo, hi = pos + CORE_MIN, pos + CORE_MAX
		if gmodcraft.ColMapSolid(C.WorldId(), lo.x, lo.y, lo.z, hi.x, hi.y, hi.z, DUG_MARGIN) == false then return false end
	end
	return true
end
PL.Embedded = embedded

-- P6i (v30): the entity a report says Minecraft carries the player with, if it is one that can be
-- (a dynamic-layer entity: a door, a train, a prop; not the world, a player or a vehicle) and it is
-- what's under the reported feet here too: client/carry.lua's trace (the MC hull, 24 units over the
-- feet down to 80 under them, players left out) must hit it (or a child of it) first. Else nil: a
-- client can't wave the inside-solid check (and the MC server's move checks) away for an entity
-- elsewhere.
local CARRY_UP, CARRY_DOWN = Vector(0, 0, 24), Vector(0, 0, 80)
local CARRY_MIN, CARRY_MAX = Vector(-12, -12, 0), Vector(12, 12, 2)
local function carryTraceFilter(e) return not e:IsPlayer() end
function PL.CarryEntity(idx, pos)
	if not idx or idx <= 0 then return nil end
	local e = Entity(idx)
	if not IsValid(e) or e:IsPlayer() or not (gmodcraft.dynamic and gmodcraft.dynamic.Wanted(e)) then return nil end
	local tr = util.TraceHull({ start = pos + CARRY_UP, endpos = pos - CARRY_DOWN, mins = CARRY_MIN, maxs = CARRY_MAX, mask = MASK_PLAYERSOLID,
		filter = carryTraceFilter })
	local hit = tr.Hit and not tr.StartSolid and tr.Entity
	if not IsValid(hit) or (hit ~= e and hit:GetParent() ~= e) then return nil end
	return e
end

-- Remembers report positions that passed the hull check (a few, spaced apart): where a forced
-- move back goes.
local function recordSafe(st, pos, now)
	if now - (st.safeT or 0) < SAFE_EVERY then return end
	st.safeT = now
	local list = st.safe
	local last = list[#list]
	if last and last:DistToSqr(pos) < SAFE_STEP * SAFE_STEP then return end
	list[#list + 1] = pos
	if #list > SAFE_KEEP then table.remove(list, 1) end
end

-- The newest remembered position that is still free (props included), else the first free spot
-- straight above the player (16-unit steps, up to 512), else nil.
local function pickSafe(ply, st)
	for i = #st.safe, 1, -1 do
		if hullFree(ply, st.safe[i], MASK_PLAYERSOLID) then return st.safe[i], "last free position" end
	end
	local p = ply:GetPos()
	for dz = 16, 512, 16 do
		local q = p + Vector(0, 0, dz)
		if math.abs(q.z) < WORLD_LIMIT and hullFree(ply, q, MASK_PLAYERSOLID) then return q, "free spot above" end
	end
	-- N1c: the last free position from before a noclip (kept aside while noclipping, PL.NoclipState).
	local pre = st.preNoclipSafe
	if pre and not st.inNoclip and hullFree(ply, pre, MASK_PLAYERSOLID) then return pre, "last free position before the noclip" end
	return nil
end

-- ---- teleports ---------------------------------------------------------------------------------
local REASONS = {
	spawn = { ev = "HostEvRespawn", code = "TeleportReasonSpawn" },
	attach = { ev = "HostEvRespawn", code = "TeleportReasonSpawn" },
	respawn = { ev = "HostEvRespawn", code = "TeleportReasonSpawn" },
	setpos = { ev = "HostEvTeleport", code = "TeleportReasonSetPos" },
	mode = { ev = "HostEvTeleport", code = "TeleportReasonSetPos" },
	follow = { ev = "HostEvTeleport", code = "TeleportReasonSetPos" },
	vehicle = { ev = "HostEvTeleport", code = "TeleportReasonVehicle" },  -- seated: follows the seat
	unseat = { ev = "HostEvTeleport", code = "TeleportReasonSetPos" },    -- left the seat: not vehicle (ends the hold)
	sanity = { ev = "HostEvTeleport", code = "TeleportReasonSetPos" },
}

local function sendTeleport(ply, st, reason)
	local r = REASONS[reason] or REASONS.setpos
	local id = PL.nextRequest
	PL.nextRequest = PL.nextRequest % 4000000000 + 1
	-- Where GMod put the player (SetPos, recovery), not where engine movement took it since: the
	-- puppet is off from the moment a move is seen, so gravity runs until the send (on a steep
	-- bank it slid the target 5 units down).
	local pos = st.teleportTarget or ply:GetPos()
	st.teleportTarget = nil
	local ang = ply:EyeAngles()
	local x, y, z = C.ToMc(pos)
	local ok = gmodcraft.PushHostEvent({
		type = K[r.ev], code = K[r.code], steamId = ply:SteamID64(), requestId = id, worldId = gmodcraft.serverLink.worldId,
		x = x, y = y, z = z, yaw = C.YawToMc(ang.y), pitch = math.Clamp(ang.p, -90, 90),
	})
	if not ok then
		st.needAfter = CurTime() + 0.5
		return
	end
	st.pending = { id = id, at = CurTime(), reason = reason, pos = pos }
	st.needTeleport = nil
	st.lastTeleportPos = pos
	if st.puppet then st.puppet = false end
	Log("puppet", "%s: teleport %d (%s) to %s = MC (%.2f, %.2f, %.2f)", ply:Nick(), id, reason, tostring(pos), x, y, z)
end

function PL.RequestTeleport(ply, reason, delay)
	local st = state(ply)
	st.needTeleport = reason
	st.needAfter = CurTime() + (delay or 0)
	st.puppet = false
	st.applied = nil         -- from before the move: CheckExternalMove would see the teleport itself as a GMod move
	st.safe = {}             -- positions from before the move: a recovery must not undo the teleport
	st.teleportTarget = nil  -- the callers that know the exact target set it after this
end

-- Entity:SetPos without the hook below (our own moves).
local ENTITY = FindMetaTable("Entity")
PL.rawSetPos = PL.rawSetPos or ENTITY.SetPos

-- Moves the player back to a hull-verified position (or leaves it where it is if none is known)
-- and makes Minecraft follow: the hold behind the sanity limits. Capped: after HOLD_CAP forced
-- moves within HOLD_WINDOW s it logs once and stops trying until the window has passed (Minecraft
-- keeps driving; a GMod-side SetPos still works).
local function holdAndRecover(ply, st, why)
	local now = CurTime()
	local h = st.holds
	while h[1] and now - h[1] > HOLD_WINDOW do table.remove(h, 1) end
	if #h >= HOLD_CAP then
		if not st.capLogged then
			st.capLogged = true
			gmodcraft.Info("%s: %d forced moves back within %d s; not trying again for now (%s)", ply:Nick(), #h, HOLD_WINDOW, why)
		end
		return false
	end
	st.capLogged = false
	local target, how = pickSafe(ply, st)
	if not target then
		-- N1c: nowhere to go. Nothing is moved (a teleport to where it is changes nothing) and it
		-- doesn't count towards HOLD_CAP; said once per spell.
		if not st.noTargetLogged then
			st.noTargetLogged = true
			gmodcraft.Info("%s: %s; no free spot known: left where it is", ply:Nick(), why)
		end
		st.insideSince = nil
		return false
	end
	st.noTargetLogged = false
	h[#h + 1] = now
	gmodcraft.Info("%s: %s; moving the player to %s (%s), Minecraft follows", ply:Nick(), why, tostring(target or ply:GetPos()),
		how or "no free spot known: where it is")
	if target then PL.rawSetPos(ply, target) end
	st.recoveries = st.recoveries + 1
	st.lastRecovery = { at = now, why = why, to = tostring(target or ply:GetPos()), how = how }
	st.violations = {}
	st.report = nil
	st.insideSince = nil
	PL.RequestTeleport(ply, "sanity")
	st.teleportTarget = target
	return true
end

-- A GMod-side move of an MC player by Lua (admin tools, other addons, our tests): Minecraft
-- follows. Caught here even when it lands in the same tick as a puppet update, which would
-- otherwise overwrite it (SetupMove sets the origin from the report). While a teleport is pending
-- this queues the next one: it goes out after the ack, to wherever the player is then.
function PL.OnSetPos(ply)
	local st = states[ply]
	if not st or not st.mcMode then return end
	st.corrections = st.corrections + 1
	Log("puppet", "%s: SetPos to %s; Minecraft follows", ply:Nick(), tostring(ply:GetPos()))
	local keep = st.needTeleport and st.needTeleport ~= "follow" and st.needTeleport or "setpos"
	PL.RequestTeleport(ply, keep)
	st.teleportTarget = ply:GetPos()
end

-- Engine-side moves (trigger_teleport, Entity:Teleport from C++) don't go through Lua: SetupMove
-- compares the origin it is handed with where the puppet put the player last tick. True: moved
-- (the caller must not overwrite the origin this tick).
function PL.CheckExternalMove(ply, st, origin)
	if not st.puppet or not st.applied then return false end
	if origin:DistToSqr(st.applied) <= 24 * 24 then return false end
	Log("puppet", "%s: moved by GMod (%.0f units from where the puppet was); teleporting Minecraft", ply:Nick(), origin:Distance(st.applied))
	st.corrections = st.corrections + 1
	st.applied = nil
	PL.RequestTeleport(ply, "setpos")
	st.teleportTarget = origin
	return true
end

-- Player:SetPos goes through PL.OnSetPos (wrapped once per Lua state; Entity:SetPos is untouched).
if not PL.setPosWrapped then
	PL.setPosWrapped = true
	local PLAYER = FindMetaTable("Player")
	function PLAYER:SetPos(pos, ...)
		PL.rawSetPos(self, pos, ...)
		if PL.OnSetPos then PL.OnSetPos(self) end
	end
end

-- ---- events from the MC server -----------------------------------------------------------------
local function bySteamId(sid)
	for ply, st in pairs(states) do
		if IsValid(ply) and ply:SteamID64() == sid then return ply, st end
	end
end
PL.BySteamId = bySteamId

local RESULT = {}
function PL.OnEvent(e)
	if e.type == K.EvTeleportAck then
		local ply, st = bySteamId(e.steamId)
		if not ply or not st.pending or st.pending.id ~= e.requestId then
			Log("puppet", "stray teleport ack %d (result %d) for %s", e.requestId, e.result, e.steamId)
			return
		end
		local p = st.pending
		st.pending = nil
		st.lastAck = { id = e.requestId, result = e.result, at = CurTime(), took = CurTime() - p.at }
		if e.result == K.TeleportOk or e.result == K.TeleportTimeout then
			-- From here McState is at the new place; the next report starts the puppet again. The
			-- client's camera interpolates a little in the past, so its first reports may still
			-- come from before the move: drop those quietly for a moment instead of counting them.
			st.lastGood, st.lastGoodT, st.report = p.pos, CurTime(), nil
			if hullFree(ply, p.pos, MASK_PLAYERSOLID) then st.safe = { p.pos } end
			st.violations = {}
			st.graceUntil = CurTime() + 1
			Log("puppet", "%s: teleport %d acked (%s) after %.2f s", ply:Nick(), e.requestId,
				e.result == K.TeleportOk and "ok" or "client didn't confirm in time", CurTime() - p.at)
		else
			-- No MC player for us yet (not mapped), wrong world or a bad position: try again soon.
			gmodcraft.Info("%s: teleport %d refused (result %d); retrying", ply:Nick(), e.requestId, e.result)
			PL.RequestTeleport(ply, p.reason, 1)
		end
	elseif e.type == K.EvWorldOpened then
		gmodcraft.mp.OnWorldOpened(e)
	elseif e.type == K.EvDevCommandResult then
		gmodcraft.mp.OnDevCommandResult(e)
	else
		-- Deaths, respawns, explosions, hits: the handlers registered in server/combat.lua.
		gmodcraft.combat.Dispatch(e)
	end
end

function PL.OnMcAttach()
	for ply, st in pairs(states) do
		if IsValid(ply) then
			st.pending, st.puppet, st.report = nil, false, nil
			st.needTeleport, st.needAfter = "attach", 0
		end
	end
	PL.hostPlayersKey = nil
	gmodcraft.mp.OnMcAttach()
end

function PL.OnMcDetach()
	for ply, st in pairs(states) do
		st.pending, st.puppet, st.report = nil, false, nil
	end
end

-- ---- weapons for the F6 modes ------------------------------------------------------------------
local function stripWeapons(ply, st)
	local list = {}
	for _, w in ipairs(ply:GetWeapons()) do
		if w:GetClass() ~= "gmodcraft_hands" then list[#list + 1] = w:GetClass() end  -- hybrid's hands aren't a loadout weapon
	end
	local active = ply:GetActiveWeapon()
	st.savedWeapons = list
	st.savedActive = IsValid(active) and active:GetClass() or nil
	ply:StripWeapons()
	st.stripped = true
	Log("puppet", "%s: MC mode, %d GMod weapons put away", ply:Nick(), #list)
end

local function restoreWeapons(ply, st)
	for _, c in ipairs(st.savedWeapons or {}) do
		if not ply:HasWeapon(c) then ply:Give(c) end
	end
	local pick = ply:HasWeapon("weapon_physgun") and "weapon_physgun" or st.savedActive
	-- Not a player's choice: hybrid mode's select request (v45) mustn't see it.
	local HY = gmodcraft.hybrid
	if pick then
		if HY and HY.SelectQuiet then HY.SelectQuiet(ply, pick) else ply:SelectWeapon(pick) end
	end
	st.stripped = false
	Log("puppet", "%s: GMod mode, %d weapons back (%s in hand)", ply:Nick(), #(st.savedWeapons or {}), tostring(pick))
end

-- ---- every tick --------------------------------------------------------------------------------
local VIEW_STAND, VIEW_DUCK = Vector(0, 0, 64), Vector(0, 0, 28)

function PL.Tick()
	local L = gmodcraft.serverLink
	local now = CurTime()
	local mcPlayers = {}
	for _, p in ipairs(gmodcraft.McPlayers()) do mcPlayers[p.steamId] = p end
	PL.mcPlayersBySteamId = mcPlayers

	for _, ply in ipairs(player.GetAll()) do
		local st = state(ply)
		-- No SteamID64 (nil: some bots, a player still connecting): nothing on the MC side can be
		-- keyed to it, so it gets no HostPlayers entry and can't be paired (identity is refused).
		local sid = ply:SteamID64()
		local mp = sid and mcPlayers[sid]
		st.mapped = mp ~= nil and band(mp.flags, K.McPlayerMapped) ~= 0
		st.mcHealth = mp and mp.health or nil
		local paired = st.identity ~= nil and L.mcAlive and L.slot ~= nil
		st.paired = paired  -- server/combat.lua: Minecraft owns the health (with st.mapped), in either mode
		local mcMode = st.mcWanted and paired
		-- Seated (jeep, airboat, chair): GMod drives. Entering and leaving are GMod moves of the
		-- player: Minecraft follows each (leaving: to the exit spot, then the puppet takes over).
		local seated = ply:InVehicle()
		if seated ~= (st.seated or false) then
			st.seated = seated
			Log("puppet", "%s: %s a vehicle (%s)", ply:Nick(), seated and "entered" or "left", tostring(ply:GetPos()))
			if paired and ply:Alive() then
				-- A teleport the other way still queued is replaced; a pending one is followed by this.
				PL.RequestTeleport(ply, seated and "vehicle" or "unseat")
			end
		end
		-- (After the seat: a seated player is MOVETYPE_NOCLIP by Source, never noclipping, IsNoclip.)
		-- gmodcraft_noclip_mc 0: no noclip in MC mode (switched off, or entered MC mode noclipping).
		if mcMode and gmodcraft.IsNoclip(ply) and not gmodcraft.NoclipMcAllowed() then
			ply:SetMoveType(MOVETYPE_WALK)
			gmodcraft.Info("%s: noclip off (gmodcraft_noclip_mc 0: not in MC mode)", ply:Nick())
		end
		local noclip = gmodcraft.IsNoclip(ply)
		if noclip ~= (st.noclip or false) then
			st.noclip = noclip
			Log("puppet", "%s: noclip %s", ply:Nick(), noclip and "on" or "off")
		end
		if ply:Alive() and not seated then PL.NoclipState(ply, st) end  -- N1c: before HostPlayers is written

		-- weapons follow the mode, unless hybrid mode (v17, server/hybrid.lua) mirrors them into
		-- the Minecraft inventory instead (gmodcraft_hybrid 1): then F6 changes input and view only.
		-- Per player: only while hybrid mode is active for them (paired AND mapped, a weapon set
		-- exists). A paired but unmapped player (joining, token refused) is still stripped in MC mode.
		local HY = gmodcraft.hybrid
		local hy = HY and HY.Enabled() and gmodcraft.McWeaponSets and HY.states and HY.states[ply]
		if hy and hy.active then
			if st.stripped then
				-- The weapons coming back are the loadout, not pickups: the loadout policy decides
				-- (union -> Minecraft gets them; strict -> stripped again), not "picked up here".
				hy.policy = hy.policy or "spawn"
				restoreWeapons(ply, st)
			end
		else
			if mcMode and not st.stripped and ply:Alive() then stripWeapons(ply, st) end
			if not mcMode and st.stripped then restoreWeapons(ply, st) end
		end

		if paired then
			if st.pending and now - st.pending.at > ACK_TIMEOUT then
				gmodcraft.Info("%s: teleport %d not acked in %.1f s; sending again", ply:Nick(), st.pending.id, ACK_TIMEOUT)
				PL.RequestTeleport(ply, st.pending.reason)
				st.pending = nil
			end
			if (not mcMode or seated) and not st.pending and ply:Alive() then
				-- GMod mode or seated: Minecraft follows the GMod player by teleport (not every tick: one at a time).
				local last = st.lastTeleportPos
				local stepU = seated and FOLLOW_STEP_SEATED or FOLLOW_STEP
				if not last or ply:GetPos():DistToSqr(last) > stepU * stepU then st.needTeleport = st.needTeleport or (seated and "vehicle" or "follow") end
			end
			if not st.pending and st.needTeleport and now >= (st.needAfter or 0) and st.mapped and ply:Alive() then
				sendTeleport(ply, st, st.needTeleport)
			end
		end

		-- The puppet runs while Minecraft drives and nothing is pending.
		local fresh = st.report ~= nil and now - st.report.t < REPORT_FRESH
		local puppet = mcMode and st.mapped and not st.pending and not st.needTeleport and ply:Alive() and fresh and not seated
		if puppet and st.puppet and st.applied and ply:GetPos():DistToSqr(st.applied) > 24 * 24 then
			-- Something in GMod moved the player (SetPos: admin tools, triggers): Minecraft follows.
			Log("puppet", "%s: moved by GMod (%.0f units from where the puppet was); teleporting Minecraft", ply:Nick(),
				ply:GetPos():Distance(st.applied))
			st.corrections = st.corrections + 1
			PL.RequestTeleport(ply, "setpos")
			puppet = false
		end
		if puppet ~= st.puppet then
			Log("puppet", "%s: puppet %s", ply:Nick(), puppet and "on (Minecraft drives)" or "off")
			if not puppet then st.applied = nil end
		end
		st.puppet = puppet
		if st.mcMode ~= mcMode then
			st.mcMode = mcMode
			gmodcraft.SetMcHull(ply, mcMode)
			if not mcMode then
				ply:SetViewOffset(VIEW_STAND)
				ply:SetViewOffsetDucked(VIEW_DUCK)
			end
		end
		if puppet then
			local eye = Vector(0, 0, st.report.eye * 40)
			ply:SetViewOffset(eye)
			ply:SetViewOffsetDucked(eye)
		end
		ply:SetNW2Bool("gmodcraft_mc", mcMode)
		ply:SetNW2Bool("gmodcraft_puppet", puppet)
	end
	PL.WriteHostPlayers(false)
end

-- HostPlayers on the server link: every GMod player in slot entIndex - 1 (one record per
-- SteamID64: bots may share one), with the identity its Minecraft reported and the hash of its
-- current join token. Written when it changes (force: now, e.g. a new token that is about to be
-- sent) and every 2 s.
function PL.WriteHostPlayers(force)
	local hp, keyParts, seen = {}, {}, {}
	for _, ply in ipairs(player.GetAll()) do
		local sid = ply:SteamID64()
		local st = states[ply]
		if st and sid and sid ~= "0" and not seen[sid] then
			seen[sid] = true
			local flags = 0
			if ply:Alive() then flags = bor(flags, K.HostPlayerAlive) end
			if ply:IsBot() then flags = bor(flags, K.HostPlayerBot) end
			if gmodcraft.IsNoclip(ply) or st.stuckHold then flags = bor(flags, K.HostPlayerNoclip or 0) end  -- v22; N1c: held stuck after it
			if IsValid(st.carry) then flags = bor(flags, K.HostPlayerCarried or 0) end  -- v30 (P6i): validated in PL.CarryEntity
			local id = st.identity
			if id then flags = bor(flags, K.HostPlayerHasMc) end
			local th = st.join and st.join.tokenHash or nil
			hp[#hp + 1] = { slot = ply:EntIndex() - 1, steamId = sid, ent = ply:EntIndex(), flags = flags, uuid = id and id.uuid or nil, name = id and id.name or nil,
				tokenHash = th }
			-- (the log shows the token's short hash only)
			keyParts[#keyParts + 1] = (ply:EntIndex() - 1) .. "=" .. sid .. ":" .. flags .. ":" .. (id and (id.uuid .. id.name) or "") .. (th and (":#" .. th:sub(1, 8)) or "")
		end
	end
	local now = CurTime()
	local key = table.concat(keyParts, "|")
	if force or key ~= PL.hostPlayersKey or now - PL.hostPlayersAt > 2 then
		if key ~= PL.hostPlayersKey then Log("link", "HostPlayers: %d players (%s)", #hp, key) end
		PL.hostPlayersKey, PL.hostPlayersAt = key, now
		gmodcraft.SetHostPlayers(hp)
	end
end

-- ---- client messages ---------------------------------------------------------------------------
-- Ask a client for its Minecraft identity once (it also sends it by itself whenever it changes).
function PL.RequestIdentity(ply)
	if not IsValid(ply) or ply:IsBot() then return end
	net.Start(gmodcraft.NET.identityReq)
	net.Send(ply)
end

net.Receive(gmodcraft.NET.identity, function(_, ply)
	if ply:IsBot() or not ply:SteamID64() or ply:SteamID64() == "0" then return end
	local flags = net.ReadUInt(32)
	local uuid = net.ReadString()
	local name = net.ReadString()
	if not uuid:match("^%x+$") or #uuid ~= 32 or #name > 16 or name:find("[^%w_]") then
		gmodcraft.Info("%s sent a malformed Minecraft identity; ignored", ply:Nick())
		return
	end
	local st = state(ply)
	local old = st.identity
	st.identity = { flags = flags, uuid = uuid:lower(), name = name, at = CurTime() }
	if not old or old.uuid ~= st.identity.uuid or old.name ~= name then
		gmodcraft.Info("%s plays Minecraft as %s (%s, %s account)", ply:Nick(), name, uuid,
			band(flags, K.IdOnlineAccount or 0) ~= 0 and "online" or "offline")
	end
end)

-- Position reports, with the server-side sanity limits (section 4.3). Not an anti-cheat: they
-- reject garbage and runaways.
-- The inside-solid recovery and the safe-position list run unless the player is noclipping (N1:
-- then it's in walls on purpose) or held stuck after it (N1c).
--  * noclip starts: the newest safe position is kept aside (st.preNoclipSafe, a last-resort
--    recovery target, never used while noclipping) and the list starts afresh;
--  * noclip ends inside solid: as in GMod, the player stays where it is. GMod keeps it marked
--    kHostPlayerNoclip in HostPlayers meanwhile (st.stuckHold), so Minecraft holds it too: no
--    gravity, no fall damage, never dropped through the map (LV1: z -275 -> -1741 -> -3964 when
--    Minecraft's physics came back inside solid, where it has no surfaces). It can move and leaves
--    the hold once its position is free (or it noclips again);
--  * noclip ends in free space: everything back at once.
-- Called by PL.Tick every tick (pos nil: the GMod position) and for every MC-mode report (pos).
function PL.NoclipState(ply, st, pos)
	if gmodcraft.IsNoclip(ply) then
		if not st.inNoclip then
			st.inNoclip = true
			st.preNoclipSafe = st.safe and st.safe[#st.safe] or st.preNoclipSafe
			st.safe = {}  -- from before the noclip: somewhere else entirely by now
		end
		st.stuckHold, st.insideSince = nil, nil
		return false
	end
	local p = pos or ply:GetPos()
	if st.inNoclip then
		st.inNoclip = nil
		st.insideSince = nil
		if embedded(ply, p) then
			st.stuckHold = true
			gmodcraft.Info("%s: noclip ended inside solid at %s: held there (Minecraft too: no gravity) until free space or noclip", ply:Nick(), tostring(p))
		end
	end
	if st.stuckHold then
		if embedded(ply, p) then return false end
		st.stuckHold = nil
		Log("puppet", "%s: out of the solid at %s: hold over", ply:Nick(), tostring(p))
	end
	return true
end

function PL.SafetyNets(ply, st, pos)
	return PL.NoclipState(ply, st, pos)
end

local function reject(ply, st, why, counts, pos)
	st.rejected = st.rejected + 1
	if pos then why = string.format("%s; at %s, %s", why, tostring(pos), contentsAt(pos)) end
	st.lastReject = why
	Log("puppet", "%s: report rejected (%s)", ply:Nick(), why)
	if not counts then return end
	local now = CurTime()
	local v = st.violations
	v[#v + 1] = now
	while v[1] and now - v[1] > 2 do table.remove(v, 1) end
	if #v >= VIOLATIONS_HOLD and not st.pending then
		holdAndRecover(ply, st, string.format("%d bad position reports in 2 s (%s)", #v, why))
	end
end

net.Receive(gmodcraft.NET.pos, function(_, ply)
	local st = states[ply]
	if not st or not st.mcWanted then return end
	local x, y, z = net.ReadFloat(), net.ReadFloat(), net.ReadFloat()
	local eye = net.ReadFloat()
	local flags = net.ReadUInt(16)
	local teleports = net.ReadUInt(32)
	local carryIdx = net.ReadUInt(16)  -- v30: the entity Minecraft carries the player with, 0 = none
	local now = CurTime()
	if now - st.rateT >= 1 then st.rateT, st.rateN = now, 0 end
	st.rateN = st.rateN + 1
	if st.rateN > cvMaxRate:GetInt() then return reject(ply, st, "more than " .. cvMaxRate:GetInt() .. " reports/s", false) end
	if bad(x) or bad(y) or bad(z) or bad(eye) then return reject(ply, st, "NaN/Inf", true) end
	if st.pending then return end  -- Minecraft is still at the old place: not a violation
	if st.seated then return end   -- GMod drives (a vehicle): Minecraft only follows, nothing to check
	-- Outside Source's coordinate range. (util.IsInWorld is also false INSIDE solid brushes, which
	-- isn't garbage: see below.)
	if math.abs(x) > WORLD_LIMIT or math.abs(y) > WORLD_LIMIT or math.abs(z) > WORLD_LIMIT then
		return reject(ply, st, string.format("outside the world (%.0f, %.0f, %.0f beyond +-16384)", x, y, z), true)
	end
	local pos = Vector(x, y, z)
	if eye < 0 or eye > 3 then return reject(ply, st, "eye height", true, pos) end
	if st.lastGood then
		local dt = math.max(now - (st.lastGoodT or now), engine.TickInterval())
		local maxd = cvMaxSpeed:GetFloat() * dt + 8
		local d = pos:Distance(st.lastGood)
		if d > maxd then
			local grace = st.graceUntil and now < st.graceUntil
			return reject(ply, st, string.format("moved %.0f units in %.3f s (limit %.0f)%s", d, dt, maxd, grace and ", just after a teleport" or ""), not grace, pos)
		end
	end
	-- Inside world brushes: Minecraft usually pushes the player out by itself, so this is soft:
	-- accepted, and only after INSIDE_RECOVER s in a row the player is moved back to the last
	-- position that had room for the hull (capped, see holdAndRecover).
	local th = SysTime()
	st.carry = PL.CarryEntity(carryIdx, pos)
	sanityCarry = st.carry
	-- Noclip (N1): in walls on purpose. No inside-solid recovery, and nothing remembered as safe.
	local nets = PL.SafetyNets(ply, st, pos)
	local inside, inHole = false, false
	if nets then inside, inHole = embedded(ply, pos) end
	if inHole then st.holeReports = (st.holeReports or 0) + 1 end
	-- Nothing on a moving platform is remembered as safe: it's gone from there a moment later.
	if nets and not inside and not st.carry and now - (st.safeT or 0) >= SAFE_EVERY and hullFree(ply, pos) then recordSafe(st, pos, now) end
	sanityCarry = nil
	gmodcraft.PerfAdd("sv report hull check", (SysTime() - th) * 1000)
	if not inside then
		st.insideSince = nil
	else
		st.insideSince = st.insideSince or now
		st.insideReports = (st.insideReports or 0) + 1
		if now - st.insideSince > INSIDE_RECOVER and not st.pending then
			st.insideSince = nil
			if holdAndRecover(ply, st, string.format("inside solid for %.1f s at %s (%s)", INSIDE_RECOVER, tostring(pos), contentsAt(pos))) then return end
		end
	end
	local prev = st.report
	local vel = Vector(0, 0, 0)
	if prev and now > prev.t then vel = (pos - prev.pos) / (now - prev.t) end
	st.report = { pos = pos, vel = vel, t = now, eye = eye, flags = flags, teleports = teleports }
	st.lastGood, st.lastGoodT = pos, now
	st.accepted = st.accepted + 1
end)

net.Receive(gmodcraft.NET.mode, function(_, ply)
	local st = state(ply)
	if st.modeAt and CurTime() - st.modeAt < 0.3 then return end
	st.modeAt = CurTime()
	st.mcWanted = not st.mcWanted
	st.carry = nil
	gmodcraft.Info("%s switched to %s mode", ply:Nick(), st.mcWanted and "Minecraft" or "GMod")
	if st.mcWanted then PL.RequestTeleport(ply, "mode") end
end)

-- ---- GMod events ---------------------------------------------------------------------------------
hook.Add("PlayerSpawn", "gmodcraft_players", function(ply)
	local st = state(ply)
	st.stripped = false  -- the loadout gives weapons again; the next tick puts them away in MC mode
	PL.RequestTeleport(ply, "spawn")
end)

hook.Add("PlayerDeath", "gmodcraft_players", function(ply)
	local st = states[ply]
	if st then st.puppet, st.report, st.carry = false, nil, nil end
end)

hook.Add("PlayerDisconnected", "gmodcraft_players", function(ply)
	states[ply] = nil
end)

-- For the debug tab (relayed to admins) and the dump.
function PL.DebugTable()
	local out = {}
	local now = CurTime()
	for ply, st in pairs(states) do
		if IsValid(ply) then
			out[#out + 1] = {
				nick = ply:Nick(), steamId = ply:SteamID64(), mcWanted = st.mcWanted, mcMode = st.mcMode, mapped = st.mapped, puppet = st.puppet,
				mcName = st.identity and st.identity.name, mcUuid = st.identity and st.identity.uuid,
				pending = st.pending and { id = st.pending.id, reason = st.pending.reason, age = now - st.pending.at } or nil,
				needTeleport = st.needTeleport, lastAck = st.lastAck, reportAge = st.report and now - st.report.t or nil,
				accepted = st.accepted, rejected = st.rejected, lastReject = st.lastReject, corrections = st.corrections,
				seated = st.seated, noclip = st.noclip, carry = IsValid(st.carry) and tostring(st.carry) or nil, recoveries = st.recoveries, lastRecovery = st.lastRecovery, insideReports = st.insideReports, holeReports = st.holeReports, safeKnown = #(st.safe or {}),
				health = st.mcHealth, pos = tostring(ply:GetPos()), slot = ply:EntIndex() - 1,
				joinId = st.join and st.join.joinId, token = st.join and st.join.tokenHash and ("#" .. st.join.tokenHash:sub(1, 8)),
			}
		end
	end
	return out
end
