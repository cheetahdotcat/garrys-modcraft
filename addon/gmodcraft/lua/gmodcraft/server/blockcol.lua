-- P5a: block collision for GMod entities (docs/DESIGN.md section 9). The module keeps the MC
-- server's solid sections (block ring kBlkSolids) and merges each into boxes (half blocks; v36:
-- plus microblocks' boxes from kBlkMicro, in eighths, keyed "m" .. box here); this file gives the
-- sections near players and NPCs frozen gmodcraft_blocks entities (shared/blockent.lua):
--
--   box mode    one entity per merged box (PhysicsInitBox + SOLID_BBOX): props, traces, NPCs and
--               players all collide with the exact box. A changed section is diffed against the
--               boxes it has (known limitation: props fall through box entities, runs 9/10): only new boxes are created (within a per-tick time budget, spread over
--               ticks) and only gone boxes removed (after the new ones exist; props around them are
--               woken). Solid type and bounds are networked, so clients predict without extra data.
--   multi mode  past the entity cap (gmodcraft_blockcol_max_ents) a section gets one
--               PhysicsInitMultiConvex entity with custom collisions instead: traces stay exact,
--               but props can fall through it (live run 8). Its boxes go to nearby clients by net.
--
-- The cap counts box entities, multi-convex entities and boxes reserved by updates in progress; a
-- section also goes multi-convex when the server's edicts run low (EDICT_RESERVE of 8192).
-- Entity work gets BUDGET_MS per tick, scan included; a tick that overran (a slow Spawn) has its
-- overrun taken from the next ticks' budgets (no minimum per tick). Unloaded while Minecraft is
-- away (the store is stale until its ClearAll + re-send).
--
-- A section starts a new update at most every REBUILD_MIN s. MC-paired players are not affected in
-- the sanity checks: server/players.lua's hull traces skip the class (their position comes from
-- Minecraft, which has its own blocks; slabs/carpets are full cubes here).
--
-- NextBots: their paths come from the navmesh and don't see these entities (a wall inside one nav
-- area); they bump and use their locomotion's stuck handling. A path cost function was tried and
-- dropped: it adds nothing when the obstacle is inside an area (docs/spikes/p5a/README.md).

local BC = gmodcraft.blockcol or {}
gmodcraft.blockcol = BC

local CLASS = "gmodcraft_blocks"
local NET_NAME = "gmodcraft_blockcol"
local SCAN_EVERY = 0.25    -- s between distance scans
local RANGE = 1600         -- u: sections within this of a player/NPC get entities (40 blocks)
local RANGE_KEEP = 2200    -- u: ... and lose them beyond this (hysteresis)
local REBUILD_MIN = 0.25   -- s between updates of one section
local BUDGET_MS = 2.0      -- per tick for scan + entity work; an overrun is carried into the next ticks
local DIRTY_PER_TICK = 256 -- dirty sections taken from the module per tick
local MAX_POINTS = 96      -- players + NPCs used as range centres
local NET_RANGE = 2400     -- u: clients within this of a multi-mode section get its boxes
local NET_BYTES_PASS = 32768  -- per client per sync pass (a single bigger message still goes alone)
local NET_EVERY = 0.2      -- s between client sync passes
local EDICT_LIMIT, EDICT_RESERVE = 8192, 1000  -- box entities only while edicts in use < limit - reserve

local cvEnabled = CreateConVar("gmodcraft_blockcol", "1", FCVAR_ARCHIVE, "Garry's Modcraft: GMod entities collide with Minecraft blocks (0 = remove the block collision entities)", 0, 1)
local cvMaxEnts = CreateConVar("gmodcraft_blockcol_max_ents", "1500", FCVAR_ARCHIVE, "Garry's Modcraft: most one-per-box block collision entities; past it a section gets one multi-convex entity (props may fall through those)", 0, 6000)

-- key -> { sx, sy, sz, boxEnts = { [6-byte box] = ent }, nBox, multi = ent | nil, packed, id, built,
--          boxes = count, missing = { box, ... } | nil, stale = { box, ... } | nil, workMs, changed }
BC.ents = BC.ents or {}
BC.pending = BC.pending or {}  -- key -> true: changed or newly in range, an update to start
BC.work = BC.work or {}        -- key -> true: an update in progress (boxes still to create/remove)
BC.boxEntCount = BC.boxEntCount or 0  -- live box entities
BC.multiCount = BC.multiCount or 0    -- live multi-convex entities
BC.reserved = BC.reserved or 0        -- boxes of updates in progress, not created yet
BC.stats = BC.stats or { created = 0, removed = 0, rebuilds = 0, failed = 0, lastMs = 0, maxMs = 0, lastBoxes = 0, maxMsBoxes = 0, mergeMs = 0,
	woken = 0, netMsgs = 0, netBytes = 0, lastNetBytes = 0, deferred = 0, scanMs = 0, boxesCreated = 0, boxesRemoved = 0, boxMs = 0, multiBuilds = 0,
	maxTickMs = 0 }
local stats = BC.stats
local nextId = BC.nextId or 0

local function key(sx, sy, sz) return sx .. "," .. sy .. "," .. sz end

local function slot()
	return gmodcraft.serverLink and gmodcraft.serverLink.slot
end

-- Source corner (origin) of section (sx, sy, sz).
local function sectionOrigin(sx, sy, sz, s)
	return Vector((16 * sx - s.ox) * 40, -(16 * sz - s.oz) * 40, 640 * sy - (s.oy or 0))  -- v21: oy, Source units
end

-- Wakes the physics objects touching / resting on a world box.
local function wakeIn(mins, maxs)
	local n = 0
	for _, o in ipairs(ents.FindInBox(mins - Vector(8, 8, 8), maxs + Vector(8, 8, 48))) do
		if o:GetClass() ~= CLASS and o:GetMoveType() == MOVETYPE_VPHYSICS then
			for i = 0, o:GetPhysicsObjectCount() - 1 do
				local po = o:GetPhysicsObjectNum(i)
				if IsValid(po) and po:IsMotionEnabled() then
					po:Wake()
					n = n + 1
				end
			end
		end
	end
	stats.woken = stats.woken + n
end

local function removeEnt(e)
	if not IsValid(e) then return end
	local mins, maxs = e:WorldSpaceAABB()
	e:Remove()
	wakeIn(mins, maxs)
end

local function removeBoxEnt(r, b)
	local e = r.boxEnts[b]
	if not e then return end
	r.boxEnts[b] = nil
	r.nBox = r.nBox - 1
	BC.boxEntCount = BC.boxEntCount - 1
	stats.boxesRemoved = stats.boxesRemoved + 1
	removeEnt(e)
end

local function removeEntry(k)
	local r = BC.ents[k]
	if not r then return end
	BC.ents[k], BC.work[k] = nil, nil
	BC.reserved = BC.reserved - (r.reserved or 0)
	r.reserved = 0
	for b in pairs(r.boxEnts) do removeBoxEnt(r, b) end
	if IsValid(r.multi) then BC.multiCount = BC.multiCount - 1 end
	removeEnt(r.multi)
	r.multi = nil
	stats.removed = stats.removed + 1
end

local function newEntry(k, q)
	local r = { sx = q.sx, sy = q.sy, sz = q.sz, boxEnts = {}, nBox = 0, boxes = 0, reserved = 0 }
	BC.ents[k] = r
	stats.created = stats.created + 1
	return r
end

-- One box entity of section r (b: 6-byte packed box). Returns the entity or nil.
-- v36: a microblock box is keyed "m" .. its 6 bytes, in eighths (5 units each).
local function createBox(r, b, s)
	local micro = #b == 7
	local x0, y0, z0, x1, y1, z1 = string.byte(b, micro and 2 or 1, micro and 7 or 6)
	local o = sectionOrigin(r.sx, r.sy, r.sz, s)
	local e = ents.Create(CLASS)
	if not IsValid(e) then return nil end
	-- packed boxes are in half blocks (v26 shapes: slabs, stairs): 20 units each; microblock boxes
	-- in eighths: 5 units each. h = half of a unit.
	local h = micro and 2.5 or 10
	e:SetPos(o + Vector((x0 + x1) * h, -(z0 + z1) * h, (y0 + y1) * h))
	e:SetAngles(Angle(0, 0, 0))
	e:Spawn()
	if not e:BuildBox(Vector((x1 - x0) * h, (z1 - z0) * h, (y1 - y0) * h)) then
		e:Remove()
		return nil
	end
	return e
end

local function buildMulti(r, packed, micro, s)
	local e = ents.Create(CLASS)
	if not IsValid(e) then return false end
	e:SetPos(sectionOrigin(r.sx, r.sy, r.sz, s))
	e:SetAngles(Angle(0, 0, 0))
	e:Spawn()
	if not e:BuildFromPacked(packed, micro) then
		e:Remove()
		return false
	end
	if IsValid(r.multi) then removeEnt(r.multi) else BC.multiCount = BC.multiCount + 1 end
	r.multi = e
	nextId = nextId + 1
	BC.nextId = nextId
	r.id = nextId
	e:SetNW2Int("gmc_build", nextId)
	stats.multiBuilds = stats.multiBuilds + 1
	return true
end

-- Finishes an update: logs its cost.
local function finish(k, r)
	BC.work[k] = nil
	r.missing, r.stale = nil, nil
	stats.lastMs, stats.lastBoxes = r.workMs or 0, r.changed or 0
	if (r.workMs or 0) > stats.maxMs then stats.maxMs, stats.maxMsBoxes = r.workMs, r.changed or 0 end
end

-- Starts an update of section k: diff against what it has. Cheap; the entity work is in step().
local function start(k, q, s)
	local packed, count, mode, mergeMs, micro, microCount = gmodcraft.BlockBoxes(q.sx, q.sy, q.sz)
	if not packed then
		removeEntry(k)
		return
	end
	local r = BC.ents[k] or newEntry(k, q)
	if r.built then stats.rebuilds = stats.rebuilds + 1 end
	micro, microCount = micro or "", microCount or 0  -- v36: microblock boxes (eighths)
	count = count + microCount
	r.built, r.boxes, r.mergeMs = CurTime(), count, mergeMs
	stats.mergeMs = mergeMs
	local want = {}
	for i = 0, count - microCount - 1 do want[packed:sub(i * 6 + 1, i * 6 + 6)] = true end
	for i = 0, microCount - 1 do want["m" .. micro:sub(i * 6 + 1, i * 6 + 6)] = true end
	local missing, stale = {}, {}
	for b in pairs(want) do if not r.boxEnts[b] then missing[#missing + 1] = b end end
	for b in pairs(r.boxEnts) do if not want[b] then stale[#stale + 1] = b end end
	r.workMs, r.changed = 0, #missing + #stale
	-- the cap: everything held or reserved, minus this section's own, plus what it would hold; and edicts
	local own = r.nBox + (IsValid(r.multi) and 1 or 0)
	local used = BC.boxEntCount + BC.multiCount + BC.reserved
	if used - own + count > cvMaxEnts:GetInt() or ents.GetEdictCount() + #missing > EDICT_LIMIT - EDICT_RESERVE then
		local t0 = SysTime()
		r.packed, r.micro = packed, micro
		if buildMulti(r, packed, micro, s) then
			for b in pairs(r.boxEnts) do removeBoxEnt(r, b) end
		else
			stats.failed = stats.failed + 1
		end
		r.workMs = (SysTime() - t0) * 1000
		finish(k, r)
		return
	end
	r.packed, r.micro = nil, nil
	r.missing, r.stale = missing, stale
	r.reserved = #missing
	BC.reserved = BC.reserved + #missing
	BC.work[k] = true
end

-- Does entity work for section k until the deadline. Returns true when the update is done.
local function step(k, r, s, deadline)
	local t0 = SysTime()
	local m = r.missing
	while m and #m > 0 do
		if SysTime() >= deadline then
			r.workMs = r.workMs + (SysTime() - t0) * 1000
			return false
		end
		local b = table.remove(m)
		r.reserved = r.reserved - 1
		BC.reserved = BC.reserved - 1
		local tb = SysTime()
		local e = createBox(r, b, s)
		if e then
			r.boxEnts[b] = e
			r.nBox = r.nBox + 1
			BC.boxEntCount = BC.boxEntCount + 1
			stats.boxesCreated = stats.boxesCreated + 1
			stats.boxMs = stats.boxMs + (SysTime() - tb) * 1000
		else
			stats.failed = stats.failed + 1
		end
	end
	-- all new boxes exist: drop the gone ones (and a multi entity from before)
	for _, b in ipairs(r.stale or {}) do removeBoxEnt(r, b) end
	if r.multi then
		if IsValid(r.multi) then BC.multiCount = BC.multiCount - 1 end
		removeEnt(r.multi)
		r.multi, r.id = nil, nil
	end
	r.workMs = r.workMs + (SysTime() - t0) * 1000
	finish(k, r)
	return true
end

function BC.RemoveAll()
	for k in pairs(BC.ents) do removeEntry(k) end
	BC.pending, BC.work = {}, {}
	BC.reserved = 0
end

-- Range centres: every player (MC or not, bots too) and every NPC / NextBot, capped.
local function centres()
	local pts = {}
	local function add(p)
		if #pts < MAX_POINTS * 3 then pts[#pts + 1], pts[#pts + 2], pts[#pts + 3] = p.x, p.y, p.z end
	end
	for _, p in ipairs(player.GetAll()) do add(p:GetPos()) end
	for _, e in ipairs(ents.GetAll()) do
		if #pts >= MAX_POINTS * 3 then break end
		if (e:IsNPC() or e:IsNextBot()) and string.sub(e:GetClass(), 1, 17) ~= "gmodcraft_mcproxy" and not e.gmcMcBody then add(e:WorldSpaceCenter()) end  -- B1: not MC mobs' bodies
	end
	return pts
end

local lastScan, lastSlotKey, lastNet = 0, nil, 0
local wanted = {}  -- key -> { sx, sy, sz }: in range

local function scan(s)
	local t0 = SysTime()
	local pts = centres()
	wanted = {}
	for _, q in ipairs(gmodcraft.BlockNear(pts, RANGE, s.ox, s.oz, nil, s.oy or 0)) do
		local k = key(q.sx, q.sy, q.sz)
		wanted[k] = q
		if not BC.ents[k] then BC.pending[k] = true end
	end
	local keep = {}
	for _, q in ipairs(gmodcraft.BlockNear(pts, RANGE_KEEP, s.ox, s.oz, nil, s.oy or 0)) do keep[key(q.sx, q.sy, q.sz)] = true end
	for k in pairs(BC.ents) do
		if not keep[k] then removeEntry(k) end
	end
	stats.scanMs = (SysTime() - t0) * 1000
end

-- ---- clients: packed boxes of multi-mode sections, for prediction -------------------------------
local sent = {}  -- ply -> { [entIndex] = build id }

local function syncClients()
	for _, ply in ipairs(player.GetHumans()) do
		local have = sent[ply]
		if not have then
			have = {}
			sent[ply] = have
		end
		local pos = ply:GetPos()
		local budget = NET_BYTES_PASS
		for _, r in pairs(BC.ents) do
			local e = r.multi
			if IsValid(e) and r.id and r.packed then
				local idx = e:EntIndex()
				if have[idx] ~= r.id then
					local mins, maxs = e:WorldSpaceAABB()
					local c = (mins + maxs) * 0.5
					local micro = r.micro or ""
					local size = #r.packed + #micro
					if c:DistToSqr(pos) < NET_RANGE * NET_RANGE and size <= 60000 then
						if budget < size and budget < NET_BYTES_PASS then break end
						net.Start(NET_NAME)
						net.WriteUInt(idx, 16)
						net.WriteUInt(r.id, 32)
						net.WriteUInt(#r.packed, 16)
						if #r.packed > 0 then net.WriteData(r.packed, #r.packed) end
						net.WriteUInt(#micro, 16)  -- v36: microblock boxes (eighths)
						if #micro > 0 then net.WriteData(micro, #micro) end
						net.Send(ply)
						have[idx] = r.id
						budget = budget - size - 10
						stats.netMsgs = stats.netMsgs + 1
						stats.netBytes = stats.netBytes + size + 10
						stats.lastNetBytes = size + 10
					end
				end
			end
		end
	end
end

hook.Add("PlayerDisconnected", "gmodcraft_blockcol", function(ply) sent[ply] = nil end)
hook.Add("EntityRemoved", "gmodcraft_blockcol", function(e)
	if e:GetClass() ~= CLASS then return end
	local idx = e:EntIndex()
	for _, have in pairs(sent) do have[idx] = nil end
end)

-- ---- tick ------------------------------------------------------------------------------------
hook.Add("Tick", "gmodcraft_blockcol", function()
	if gmodcraft.missing or not gmodcraft.BlockDirty then return end
	local s = slot()
	local L = gmodcraft.serverLink
	if not cvEnabled:GetBool() or not s or not (L and L.mcAlive) then
		if next(BC.ents) then BC.RemoveAll() end
		gmodcraft.BlockDirty(1e6)  -- keep the dirty queue drained; a scan rebuilds what's needed
		return
	end
	-- A new slot origin moves every section: start over.
	local sk = s.ox .. "," .. s.oz .. "," .. (s.oy or 0)
	if sk ~= lastSlotKey then
		BC.RemoveAll()
		lastSlotKey = sk
		lastScan = 0
	end
	for _, d in ipairs(gmodcraft.BlockDirty(DIRTY_PER_TICK)) do
		local k = key(d.sx, d.sy, d.sz)
		if BC.ents[k] then BC.pending[k] = true end
	end
	local now = CurTime()
	-- Budget: BUDGET_MS per tick (scan included), minus the overrun of earlier ticks carried over.
	local t0 = SysTime()
	if now - lastScan >= SCAN_EVERY then
		lastScan = now
		scan(s)
	end
	local budget = BUDGET_MS - (BC.debt or 0)
	local deadline = t0 + math.max(budget, 0) / 1000
	-- Start pending updates (at most every REBUILD_MIN per section; not while one is in progress).
	for k in pairs(BC.pending) do
		if SysTime() >= deadline then break end
		local r = BC.ents[k]
		if BC.work[k] or (r and r.built and now - r.built < REBUILD_MIN) then
			stats.deferred = stats.deferred + 1
		else
			BC.pending[k] = nil
			local q = r or wanted[k]
			if q then start(k, q, s) end
		end
	end
	-- Entity work, within the budget.
	for k in pairs(BC.work) do
		local r = BC.ents[k]
		if not r then
			BC.work[k] = nil
		elseif not step(k, r, s, deadline) then
			break
		end
		if SysTime() >= deadline then break end
	end
	local spent = (SysTime() - t0) * 1000
	BC.debt = math.max(0, (BC.debt or 0) + spent - BUDGET_MS)
	if spent > stats.maxTickMs then stats.maxTickMs = spent end
	gmodcraft.PerfAdd("sv blockcol", spent)
	if now - lastNet >= NET_EVERY then
		lastNet = now
		syncClients()
	end
end)

cvars.AddChangeCallback("gmodcraft_blockcol", function(_, _, v)
	if v == "0" then BC.RemoveAll() else lastScan = 0 end
end, "gmodcraft_blockcol")

-- Debug panel data (Blocks/Dig, relayed with the admin stats).
function BC.DebugTable()
	local sections, multi, boxes = 0, 0, 0
	for _, r in pairs(BC.ents) do
		sections = sections + 1
		boxes = boxes + (r.boxes or 0)
		if IsValid(r.multi) then multi = multi + 1 end
	end
	local pend, work = 0, 0
	for _ in pairs(BC.pending) do pend = pend + 1 end
	for _ in pairs(BC.work) do work = work + 1 end
	return { entities = BC.boxEntCount + BC.multiCount, boxEnts = BC.boxEntCount, multiSections = multi, reserved = BC.reserved, sections = sections, boxes = boxes, pending = pend + work,
		cap = cvMaxEnts:GetInt(), stats = stats, module = gmodcraft.BlockStats and gmodcraft.BlockStats() or nil, enabled = cvEnabled:GetBool(), range = RANGE,
		msPerBox = stats.boxesCreated > 0 and stats.boxMs / stats.boxesCreated or 0 }
end

-- Is this a block collision entity? (server/players.lua's sanity traces skip them.)
function BC.IsBlockEntity(e)
	return IsValid(e) and e:GetClass() == CLASS
end
