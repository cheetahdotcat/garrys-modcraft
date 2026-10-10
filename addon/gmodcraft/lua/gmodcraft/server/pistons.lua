-- Pistons push GMod things (protocol v42, kEvPistonMove). Server only.
--   Minecraft sends one event per cell a piston starts moving (the pushed or pulled blocks, and the head
--   when it extends): the cell before the move, the direction, the travel time (2 MC ticks). Minecraft
--   moves its own entities, Minecraft-mode players included; this file moves GMod's:
--     pushed   physics props (ragdolls: every bone), NPCs / NextBots and GMod-mode players in the cell a
--              block moves into: just out of it, along the move, over the travel time;
--     riders   the same standing on a moving block (bottom on its top face, centre over it) ride the
--              whole block along, also on a sticky pull; nothing rides a head (its base stays).
--   Each thing moves once per move (the largest amount of its cells). Not moved: frozen or held props,
--   parented things, seated or noclipping players, Minecraft-mode players, our own entities.
--   Block collision: the moved cells' sections are rebuilt promptly (blockcol Prompt), right after the
--   travel, so the new box doesn't appear under a thing still being moved out of it.
--   Limits: Minecraft sends at most kPistonEventsPerTick cells per MC tick; a move past that cap still
--   moves its blocks, but pushes nothing in GMod. The module must be rebuilt together with this addon
--   (an older module has no K.EvPistonMove: no event reaches this file, nothing is pushed).

local PU = gmodcraft.pistons or {}
gmodcraft.pistons = PU
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band = bit.band

local U = C.UNITS or 40
PU.EPS = 0.5             -- u: the target cell's box is shrunk this much (a thing only touching it stays)
PU.CLEAR = 0.5           -- u: pushed this much past the block's face
PU.RIDE_TOL = 4          -- u: a rider's bottom within this of the block's top face
PU.MAX_ENTS = 32         -- things moved per move at most
PU.MAX_MOTIONS = 256     -- motions in progress at most
PU.MAX_MOVES_TICK = 64   -- moves applied per tick at most (the rest next tick)
PU.KEEP_IDS = 1.0        -- s a move's id is remembered (its cells may come in two batches)
PU.SKIP = { gmodcraft_blocks = true, gmodcraft_physregion = true, gmodcraft_mcproxy = true, gmodcraft_mcproxy_npc = true,
	gmodcraft_mapio_relay = true, gmod_wire_gmodcraft_bridge = true }

-- Minecraft's Direction order (BridgeFace): MC step x, y, z
PU.STEP = { [0] = { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } }

PU.queue = PU.queue or {}      -- moves waiting for the tick: { id, t, dur, cells = { { x, y, z, dir, head } } }
PU.motions = PU.motions or {}  -- { ent, dir (unit Vector), total, done, t0, dur }
PU.applied = PU.applied or {}  -- move id -> { t, ents = { [ent] = amount } }
PU.stats = PU.stats or { events = 0, refused = 0, moves = 0, pushed = 0, riders = 0, skippedMc = 0, dropped = 0 }
local stats = PU.stats

local function finite(v) v = tonumber(v) return v and v == v and v ~= math.huge and v ~= -math.huge and v or nil end

-- ---- what moves ------------------------------------------------------------------------------------
local function isNpc(ent) return ent:IsNPC() or (ent.IsNextBot and ent:IsNextBot()) or false end

-- Minecraft drives this player (MC mode): Minecraft pushes it already.
function PU.McDriven(ply)
	local P = gmodcraft.player
	local st = P and P.Get and P.Get(ply)
	return st ~= nil and st.mcMode == true
end

function PU.Movable(ent)
	if not IsValid(ent) or ent:IsWorld() or PU.SKIP[ent:GetClass()] then return false end
	if IsValid(ent:GetParent()) then return false end
	if ent:IsPlayer() then
		if not ent:Alive() or ent:InVehicle() or ent:GetMoveType() == MOVETYPE_NOCLIP then return false end
		if PU.McDriven(ent) then
			stats.skippedMc = stats.skippedMc + 1
			return false
		end
		return true
	end
	if isNpc(ent) then return true end
	if ent:GetMoveType() ~= MOVETYPE_VPHYSICS then return false end
	if ent.IsPlayerHolding and ent:IsPlayerHolding() then return false end
	local po = ent:GetPhysicsObject()
	return IsValid(po) and po:IsMotionEnabled()
end

local function bounds(ent)
	if ent:IsPlayer() or isNpc(ent) then
		local p = ent:GetPos()
		return p + ent:OBBMins(), p + ent:OBBMaxs()
	end
	return ent:WorldSpaceAABB()
end

-- The cell's Source box and the Source step of one block along dir.
local function cellBox(x, y, z)
	local a, b = C.FromMc(x, y, z), C.FromMc(x + 1, y + 1, z + 1)
	return Vector(math.min(a.x, b.x), math.min(a.y, b.y), math.min(a.z, b.z)), Vector(math.max(a.x, b.x), math.max(a.y, b.y), math.max(a.z, b.z))
end

-- How far ent must go along the unit axis n to leave the box [mn, mx] (0 when it's already out that way).
local function outOf(n, emn, emx, mn, mx)
	local need = 0
	if n.x > 0.5 then need = mx.x - emn.x elseif n.x < -0.5 then need = emx.x - mn.x
	elseif n.y > 0.5 then need = mx.y - emn.y elseif n.y < -0.5 then need = emx.y - mn.y
	elseif n.z > 0.5 then need = mx.z - emn.z elseif n.z < -0.5 then need = emx.z - mn.z end
	return math.Clamp(need + PU.CLEAR, 0, U + PU.CLEAR)
end

-- ---- events ----------------------------------------------------------------------------------------
function PU.OnMove(e)
	stats.events = stats.events + 1
	local L = gmodcraft.serverLink
	local x, y, z = finite(e.a), finite(e.b), finite(e.c)
	local flags = tonumber(e.flags) or 0
	local dir = band(flags, K.PistonDirMask or 7)
	if not (L and C.slot.known and e.requestId == L.worldId and x and y and z and PU.STEP[dir]) then
		stats.refused = stats.refused + 1
		return
	end
	local id = tonumber(e.result) or 0
	local q = PU.queue[#PU.queue]
	if not (q and q.id == id) then
		if #PU.queue >= PU.MAX_MOVES_TICK * 4 then stats.dropped = stats.dropped + 1 return end
		local ticks = math.Clamp(tonumber(e.weapon) or K.PistonTravelTicks or 2, 1, 10)
		q = { id = id, t = CurTime(), dur = ticks * 0.05, cells = {}, sections = {} }
		PU.queue[#PU.queue + 1] = q
	end
	q.cells[#q.cells + 1] = { x, y, z, dir, band(flags, K.PistonHead or 0x10) ~= 0 }
	-- The sections the cell leaves and enters: their block collision holds until the travel ends, then
	-- rebuilds promptly. Set here, at the event, so the hold exists before blockcol takes the section's
	-- change (the order of the Tick hooks is not fixed).
	local BC = gmodcraft.blockcol
	local s, f = PU.STEP[dir], math.floor
	for _, p in ipairs({ { x, y, z }, { x + s[1], y + s[2], z + s[3] } }) do
		local sx, sy, sz = f(p[1] / 16), f(p[2] / 16), f(p[3] / 16)
		local k = sx .. "," .. sy .. "," .. sz
		if not q.sections[k] then
			q.sections[k] = true
			if BC and BC.Prompt then BC.Prompt(sx, sy, sz, q.t + q.dur) end
		end
	end
end

-- The block collision entities of the move's sections: a pushed player's / NPC's trace ignores them (it
-- starts inside the moving block's new box), but still stops at every other block and at the map.
local function moveBlockEnts(q)
	local BC, out = gmodcraft.blockcol, {}
	for k in pairs(q.sections) do
		local r = BC and BC.ents and BC.ents[k]
		if r then
			for _, e in pairs(r.boxEnts or {}) do out[e] = true end
			if r.multi then out[r.multi] = true end
		end
	end
	return out
end

-- One move: who goes how far (each thing once: the largest amount of its cells).
function PU.Apply(q)
	stats.moves = stats.moves + 1
	local ignore = moveBlockEnts(q)
	local now = CurTime()
	local rec = PU.applied[q.id]
	if not rec then
		rec = { t = now, ents = {} }
		PU.applied[q.id] = rec
	end
	local amount, dirOf, order = {}, {}, {}
	local function want(ent, n, a, rider)
		if a <= 0 then return end
		if amount[ent] == nil then
			if #order >= PU.MAX_ENTS or not PU.Movable(ent) then amount[ent] = false return end
			order[#order + 1] = ent
			amount[ent], dirOf[ent] = 0, n
			if rider then stats.riders = stats.riders + 1 else stats.pushed = stats.pushed + 1 end
		end
		if amount[ent] and a > amount[ent] then amount[ent] = a end
	end
	for _, c in ipairs(q.cells) do
		local x, y, z, dir, head = c[1], c[2], c[3], c[4], c[5]
		local s = PU.STEP[dir]
		local d = C.FromMc(x + s[1], y + s[2], z + s[3]) - C.FromMc(x, y, z)
		local n = d * (1 / U)
		local smn, smx = cellBox(x, y, z)
		local dmn, dmx = smn + d, smx + d
		local eps = Vector(PU.EPS, PU.EPS, PU.EPS)
		-- pushed: in the cell the block moves into
		for _, ent in ipairs(ents.FindInBox(dmn + eps, dmx - eps)) do
			if amount[ent] ~= false then
				local emn, emx = bounds(ent)
				want(ent, n, outOf(n, emn, emx, dmn, dmx), false)
			end
		end
		-- riders: standing on the moving block (not on a head)
		if not head then
			local top = smx.z
			for _, ent in ipairs(ents.FindInBox(Vector(smn.x, smn.y, top - PU.RIDE_TOL), Vector(smx.x, smx.y, top + PU.RIDE_TOL))) do
				if amount[ent] ~= false then
					local emn, emx = bounds(ent)
					local cx, cy = (emn.x + emx.x) / 2, (emn.y + emx.y) / 2
					if math.abs(emn.z - top) <= PU.RIDE_TOL and cx >= smn.x and cx <= smx.x and cy >= smn.y and cy <= smx.y then
						want(ent, n, U, true)
					end
				end
			end
		end
	end
	for _, ent in ipairs(order) do
		local a = amount[ent]
		if a then
			local before = rec.ents[ent] or 0  -- the same move's earlier batch moved it already
			if a > before then
				rec.ents[ent] = a
				if #PU.motions < PU.MAX_MOTIONS then
					PU.motions[#PU.motions + 1] = { ent = ent, dir = dirOf[ent], total = a - before, done = 0, t0 = q.t, dur = q.dur, ignore = ignore }
				else
					stats.dropped = stats.dropped + 1
				end
			end
		end
	end
end

-- ---- moving ----------------------------------------------------------------------------------------
function PU.MoveBy(ent, delta, dir, ignore)
	if ent:IsPlayer() or isNpc(ent) then
		local pos = ent:GetPos()
		local tr = util.TraceHull({ start = pos, endpos = pos + delta, mins = ent:OBBMins(), maxs = ent:OBBMaxs(),
			mask = ent:IsPlayer() and MASK_PLAYERSOLID or MASK_NPCSOLID, filter = function(e) return e ~= ent and not (ignore and ignore[e]) end })
		-- the map in the way: as far as it goes (already stuck: Minecraft would push it anyway)
		ent:SetPos((tr.Hit and not tr.StartSolid) and tr.HitPos or pos + delta)
		return
	end
	local count = ent:GetPhysicsObjectCount()
	if count <= 0 then
		ent:SetPos(ent:GetPos() + delta)
		return
	end
	for i = 0, count - 1 do
		local po = ent:GetPhysicsObjectNum(i)
		if IsValid(po) then
			po:SetPos(po:GetPos() + delta)
			local v = po:GetVelocity()
			local back = v:Dot(dir)
			if back < 0 then po:SetVelocity(v - dir * back) end  -- no speed against the push (gravity on a lift)
			po:Wake()
		end
	end
end

function PU.Step(now)
	local list, n = PU.motions, 0
	for i = 1, #list do
		local m = list[i]
		local keep = false
		if IsValid(m.ent) then
			local f = math.Clamp((now - m.t0) / m.dur, 0, 1)
			local goal = m.total * f
			if goal > m.done then
				PU.MoveBy(m.ent, m.dir * (goal - m.done), m.dir, m.ignore)
				m.done = goal
			end
			keep = f < 1
		end
		if keep then
			n = n + 1
			list[n] = m
		end
	end
	for i = n + 1, #list do list[i] = nil end
end

function PU.Tick()
	local now = CurTime()
	local applied = 0
	while PU.queue[1] and applied < PU.MAX_MOVES_TICK do
		local q = table.remove(PU.queue, 1)
		local ok, err = pcall(PU.Apply, q)
		if not ok then gmodcraft.Info("piston move failed: %s", tostring(err)) end
		applied = applied + 1
	end
	if PU.motions[1] then PU.Step(now) end
	for id, rec in pairs(PU.applied) do
		if now - rec.t > PU.KEEP_IDS then PU.applied[id] = nil end
	end
end

hook.Add("Tick", "gmodcraft_pistons", PU.Tick)
if gmodcraft.combat and gmodcraft.combat.On then gmodcraft.combat.On(K.EvPistonMove, "pistons", PU.OnMove) end

function PU.DebugTable()
	return { stats = PU.stats, motions = #PU.motions, queued = #PU.queue }
end
