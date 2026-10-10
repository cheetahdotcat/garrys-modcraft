-- Tier 0 (0.4 Physics): one static physics world for GMod's VPhysics, made of the map AND Minecraft.
-- Props, ragdolls and vehicles stop colliding with the BSP world (brushes, displacements, static
-- props) and collide with per-region static entities instead, which the module builds on a worker
-- (module/source/physbuild.*, physworld.cpp): the map's convexes clipped to the region, minus the
-- cells Minecraft dug, plus Minecraft's solid blocks as boxes. So props fall into dug holes, rest on
-- Minecraft blocks (D-023a) and vehicles drive on them. Players, NPCs, bullets and every trace keep
-- the BSP: region entities are VPhysics only (TestCollision never hits) and never sent to clients.
--
--   filter     tracked entities (props, ragdolls, vehicles) get SetCustomCollisionCheck; the
--              ShouldCollide hook drops (entity, world) pairs only while the entity is COVERED:
--              every region its box (plus a speed margin) touches is built. Uncovered entities keep
--              the BSP, exactly today's behaviour; a flip calls CollisionRulesChanged.
--   streaming  awake tracked entities and players need the regions around them (built on demand,
--              a few per tick); a covered entity, awake or asleep, holds its regions (never unloaded
--              under a resting prop); regions nobody needs go after UNLOAD_AFTER s.
--   rebuild    a dug cell or a Minecraft section change marks its regions: the new entity is built
--              first, then the old one removed, then the tracked entities in the box are woken.
--   convar     gmodcraft_physics_world 0: no filter, no regions (today's behaviour).
--
-- (Spike: docs in second-brain/notes/blending-ideas.md "T0 spike result".)

local PW = gmodcraft.physworld or {}
gmodcraft.physworld = PW

local CLASS = "gmodcraft_physregion"
PW.SIZE = PW.SIZE or 8         -- region edge, MC blocks
local SCAN_EVERY = 0.1         -- s between coverage scans
local BUDGET_MS = 4            -- per tick for entity builds (one always goes)
local UNLOAD_AFTER = 30        -- s a region may sit unneeded (the caps below still hold)
local BAD_RETRY = 10           -- s before a region whose entities failed is tried again (truncated ones never)
local MAX_REGIONS = 600        -- entities; past it no new requests (entities stay on the BSP)
local MAX_QUEUED = 48          -- builds in flight
local PLAYER_PAD = 1           -- regions around a player's region (horizontally; one below too)
local MARGIN = 40              -- u around an entity's box
local SPEED_LOOKAHEAD = 0.3    -- s of travel added to the margin (capped below)
local REQUEST_AHEAD = 1.0      -- s: regions where a fast mover will be are requested this early
local MARGIN_MAX = 640
local EDICT_LIMIT, EDICT_RESERVE = 8192, 1500  -- no new regions past limit - reserve (blockcol keeps 1000)
local CHUNK = 6                -- convexes per region entity (live run: 12 convexes cost up to 8 ms)
PW.CHUNK = CHUNK

local cv = CreateConVar("gmodcraft_physics_world", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: props, ragdolls and vehicles collide with one physics world of the map and Minecraft's blocks (dug holes, MC blocks); 0 = the BSP as usual", 0, 1)

PW.regions = PW.regions or {}  -- key -> { rx, ry, rz, gen, want, ent, status = nil|"wait"|"ready"|"bad", need = CurTime() }
PW.tracked = PW.tracked or {}  -- ent -> true
PW.building = PW.building or {}  -- built regions whose entities are being made, oldest first
PW.list = PW.list or {}        -- tracked, as an array (scan order)
PW.stats = PW.stats or { builds = 0, rebuilds = 0, bad = 0, removed = 0, maxBuildMs = 0, lastBuildMs = 0, maxEntMs = 0, flips = 0,
	covered = 0, tracked = 0, regions = 0, tickMs = 0, maxTickMs = 0, woken = 0 }
local stats = PW.stats
local active = false
local lastScan, lastSlotKey = 0, nil
local WORLD = nil

-- A number, not a string (no allocation in the per-tick coverage loop): rx, rz within +-2^20 regions,
-- ry within +-2^10 (exact in a double's 53 bits).
local function key(rx, ry, rz) return ((rx + 1048576) * 2048 + (ry + 1024)) * 2097152 + (rz + 1048576) end
PW.Key = key

local function slot()
	return gmodcraft.serverLink and gmodcraft.serverLink.slot
end

-- ---- the region entity ---------------------------------------------------------------------------
local ENT = {}
ENT.Type = "anim"
ENT.Base = "base_anim"
ENT.PrintName = "Physics world region"
ENT.Spawnable = false
ENT.PhysgunDisabled = true
ENT.DisableDuplicator = true
ENT.DoNotDuplicate = true
ENT.m_tblToolsAllowed = {}
function ENT:Initialize()
	self:SetModel("models/hunter/blocks/cube025x025x025.mdl")  -- placeholder, never drawn (as blockent)
	self:DrawShadow(false)
	self:SetNoDraw(true)
end
function ENT:UpdateTransmitState() return TRANSMIT_NEVER end
function ENT:TestCollision() end  -- traces never hit it: players, NPCs and bullets keep the BSP
function ENT:CanTool() return false end
function ENT:CanProperty() return false end
function ENT:GravGunPickupAllowed() return false end
-- convexes: { { Vector, ... }, ... } local to the entity. True when the physics exists.
function ENT:Build(convexes, mins, maxs)
	if not self:PhysicsInitMultiConvex(convexes) then return false end
	self:SetSolid(SOLID_VPHYSICS)
	self:SetMoveType(MOVETYPE_VPHYSICS)  -- MOVETYPE_NONE: vehicle wheels sink through it (T0 spike)
	self:EnableCustomCollisions(true)
	self:SetCollisionBounds(mins, maxs)
	local po = self:GetPhysicsObject()
	if not IsValid(po) then return false end
	po:EnableMotion(false)
	return true
end
scripted_ents.Register(ENT, CLASS)
PW.ENT = ENT

-- ---- the filter ----------------------------------------------------------------------------------
-- Called for every pair with a custom-collision-check entity: cheap, no allocation.
function PW.ShouldCollide(a, b)
	if a == WORLD then
		if b.gmcPWc then return false end
	elseif b == WORLD then
		if a.gmcPWc then return false end
	end
end

-- Any VPhysics-moved entity (props, ragdolls, vehicles, func_physbox, wheels, thrusters,
-- wire SENTs, dropped weapons...), except players, NPCs, our own and Minecraft's collision entities and
-- the denylist (things that ride on players / are parented: they follow something else).
local DENY = { gmodcraft_physregion = true, gmodcraft_blocks = true, predicted_viewmodel = true, physgun_beam = true,
	gmodcraft_mcproxy = true, gmodcraft_mcproxy_npc = true }  -- MC mobs' bodies (an anim with a VPhysics box since v31)
function PW.Trackable(e)
	if e:IsPlayer() or e:IsNPC() or (e.IsNextBot and e:IsNextBot()) or DENY[e:GetClass()] then return false end
	if IsValid(e:GetParent()) then return false end
	return e:GetMoveType() == MOVETYPE_VPHYSICS and IsValid(e:GetPhysicsObject())  -- frozen ones too (unfreezing comes later)
end

local function setCovered(e, on)
	if (e.gmcPWc == true) == on then return end
	e.gmcPWc = on or nil
	stats.flips = stats.flips + 1
	e:CollisionRulesChanged()
end

function PW.Track(e)
	if not IsValid(e) or PW.tracked[e] or not PW.Trackable(e) then return end
	PW.tracked[e] = true
	PW.list[#PW.list + 1] = e
	if not e:GetCustomCollisionCheck() then
		e.gmcPWmine = true
		e:SetCustomCollisionCheck(true)
	end
end

local function untrackAll()
	for _, e in ipairs(PW.list) do
		if IsValid(e) then
			setCovered(e, false)
			if e.gmcPWmine then
				e.gmcPWmine = nil
				e:SetCustomCollisionCheck(false)
			end
		end
	end
	PW.tracked, PW.list = {}, {}
end

local count
local function removeRegion(k)
	local r = PW.regions[k]
	if not r then return end
	for _, e in ipairs(r.ents or {}) do if IsValid(e) then e:Remove() stats.removed = stats.removed + 1 end end
	PW.regions[k] = nil
	count = math.max(0, (count or 1) - 1)
end

-- Everything back to the BSP.
function PW.Reset()
	untrackAll()
	for k in pairs(PW.regions) do removeRegion(k) end
	for _, job in ipairs(PW.building) do
		for _, e in ipairs(job.ents) do if IsValid(e) then e:Remove() end end
	end
	PW.regions, PW.building = {}, {}
	count = 0
	active = false
end

-- ---- geometry ------------------------------------------------------------------------------------
-- Source AABB (mins, maxs) grown by m -> region index ranges (MC: x = sx/40 + ox, y = (sz + oy)/40, z = -sy/40 + oz).
local function regionRange(mins, maxs, m, s)
	local S, f = PW.SIZE, math.floor
	local x0, x1 = (mins.x - m) / 40 + s.ox, (maxs.x + m) / 40 + s.ox
	local y0, y1 = (mins.z - m + (s.oy or 0)) / 40, (maxs.z + m + (s.oy or 0)) / 40
	local z0, z1 = -(maxs.y + m) / 40 + s.oz, -(mins.y - m) / 40 + s.oz
	return f(x0 / S), f(x1 / S), f(y0 / S), f(y1 / S), f(z0 / S), f(z1 / S)
end
PW.RegionRange = regionRange

-- A region's Source box (mins, maxs).
local function regionBox(rx, ry, rz, s)
	local S = PW.SIZE
	local x0, y0, z0 = rx * S, ry * S, rz * S
	local mins = Vector((x0 - s.ox) * 40, -(z0 + S - s.oz) * 40, y0 * 40 - (s.oy or 0))
	return mins, mins + Vector(S * 40, S * 40, S * 40)
end
PW.RegionBox = regionBox

local function wakeIn(mins, maxs)
	local n = 0
	for _, e in ipairs(ents.FindInBox(mins - Vector(40, 40, 40), maxs + Vector(40, 40, 40))) do
		if PW.tracked[e] then
			for i = 0, e:GetPhysicsObjectCount() - 1 do
				local p = e:GetPhysicsObjectNum(i)
				if IsValid(p) then p:Wake() n = n + 1 end
			end
		end
	end
	stats.woken = stats.woken + n
end

-- ---- requests / results --------------------------------------------------------------------------
local queued = 0
count = 0
PW.nextGen = PW.nextGen or 0  -- one counter for all requests: a result of an unloaded region never matches a new one

-- The region at k, needed now: requested when it has no build (nil past MAX_REGIONS).
-- hold: when this need may lapse (default now: the full UNLOAD_AFTER from now); prefetches pass a short one.
local function request(k, rx, ry, rz, now, hold)
	local r = PW.regions[k]
	if not r then
		-- caps: regions, and edicts (each region is up to a few entities; "no free edicts" kills srcds)
		if count >= MAX_REGIONS or ents.GetEdictCount() > EDICT_LIMIT - EDICT_RESERVE then return nil end
		r = { rx = rx, ry = ry, rz = rz, gen = 0, want = 0 }
		PW.regions[k] = r
		count = count + 1
	end
	local need = hold or now
	if not r.need or need > r.need then r.need = need end
	if r.status == nil and queued < MAX_QUEUED then
		PW.nextGen = PW.nextGen + 1
		if gmodcraft.PhysRegionRequest(rx, ry, rz, PW.SIZE, PW.nextGen) then
			r.want = PW.nextGen
			r.status = "wait"
			queued = queued + 1
		end
	end
	return r
end

-- A built region becomes entities of at most CHUNK convexes each, one entity per step within the tick
-- budget (PhysicsInitMultiConvex costs ~0.2 ms a convex: live run 2026-10-06, 57 convexes = 12 ms); when
-- all exist the old ones go and the sleepers in the box are woken.
local function removeEnts(list)
	for _, e in ipairs(list or {}) do
		if IsValid(e) then e:Remove() stats.removed = stats.removed + 1 end
	end
end

local function startBuild(res)
	local k = key(res.rx, res.ry, res.rz)
	local r = PW.regions[k]
	if not r or res.size ~= PW.SIZE or res.gen ~= r.want then return end  -- unloaded or superseded
	if r.dirty then  -- changed again while building: build again, keep the old entities meanwhile
		r.dirty = nil
		r.status = nil
		r.stale = r.gen > 0
		return
	end
	local job = { r = r, k = k, gen = res.gen, res = res, i = 1, ents = {}, chunks = {}, ms = 0 }
	if res.ok and not res.truncated then
		local v, i, chunk = res.verts, 1, nil
		for c = 1, #res.counts do
			if not chunk or #chunk >= CHUNK then
				chunk = {}
				job.chunks[#job.chunks + 1] = chunk
			end
			local pts = {}
			for j = 1, res.counts[c] do
				pts[j] = Vector(v[i], v[i + 1], v[i + 2])
				i = i + 3
			end
			chunk[#chunk + 1] = pts
		end
	end
	PW.building[#PW.building + 1] = job
end

local function live(job) return PW.regions[job.k] == job.r and job.r.want == job.gen end

-- One entity of the job; true when the job has no more steps.
local function stepBuild(job)
	if not live(job) then
		job.dead = true
		return true
	end
	if job.i > #job.chunks then return true end
	local t0 = SysTime()
	local res = job.res
	if ents.GetEdictCount() > EDICT_LIMIT - 200 then
		job.failed = true
		return true
	end
	local e = ents.Create(CLASS)
	if IsValid(e) then
		e:SetPos(Vector(res.ox, res.oy, res.oz))
		e:SetAngles(angle_zero)
		e:Spawn()
		if e:Build(job.chunks[job.i], Vector(0, 0, 0), Vector(PW.SIZE * 40, PW.SIZE * 40, PW.SIZE * 40)) then
			job.ents[#job.ents + 1] = e
		else
			e:Remove()
			job.failed = true
		end
	else
		job.failed = true
	end
	job.i = job.i + 1
	local ms = (SysTime() - t0) * 1000
	job.ms = job.ms + ms
	stats.maxEntMs = math.max(stats.maxEntMs, ms)
	return job.failed == true or job.i > #job.chunks
end

local function finish(job, s)
	local r, res = job.r, job.res
	if job.dead or not live(job) then removeEnts(job.ents) return end
	local ok = res.ok and not res.truncated and not job.failed
	local old = r.ents
	if ok then r.ents = job.ents else removeEnts(job.ents) r.ents = nil end
	r.empty = ok and #job.chunks == 0  -- air: covered, no entity
	r.gen, r.stale = job.gen, nil
	r.status = ok and "ready" or "bad"
	r.info = { brushes = res.brushes, prisms = res.prisms, boxes = res.boxes, carved = res.carved, wedges = res.wedges, convexes = #res.counts, verts = #res.verts / 3,
		buildMs = res.buildMs, entMs = job.ms, ents = #job.ents }
	if not ok then
		stats.bad = stats.bad + 1
		r.badAt, r.truncated = CurTime(), (res.truncated or not res.ok) and true or nil  -- truncated / failed build: permanent
	end
	if old and #old > 0 then
		removeEnts(old)
		stats.rebuilds = stats.rebuilds + 1
		local mins, maxs = regionBox(r.rx, r.ry, r.rz, s)
		wakeIn(mins, maxs)
	end
	if r.dirty then  -- changed while its entities were made: once more
		r.dirty = nil
		r.stale = ok
		r.status = nil
	end
	stats.builds = stats.builds + 1
	stats.lastBuildMs = res.buildMs or 0
	stats.maxBuildMs = math.max(stats.maxBuildMs, stats.lastBuildMs)
end

-- A changed region: rebuilt (it is requested again; the old entity stays until the new one exists).
local function markDirty(rx, ry, rz)
	local r = PW.regions[key(rx, ry, rz)]
	if not r then return end
	if r.status == "wait" then
		r.dirty = true
	elseif r.status ~= nil then
		r.stale = r.status == "ready"  -- the next scan requests it again; it still covers meanwhile
		r.status = nil
	end
end

local function takeDirty()
	local d = gmodcraft.PhysDirty()
	if d.all then
		for _, r in pairs(PW.regions) do markDirty(r.rx, r.ry, r.rz) end
		return
	end
	local S, f, c = PW.SIZE, math.floor, d.cells
	for i = 1, #c, 3 do markDirty(f(c[i] / S), f(c[i + 1] / S), f(c[i + 2] / S)) end
	local sc = d.sections
	for i = 1, #sc, 3 do
		local x0, y0, z0 = sc[i] * 16, sc[i + 1] * 16, sc[i + 2] * 16
		-- one block around: a neighbour region's step wedges read these blocks (T0b)
		for rx = f((x0 - 1) / S), f((x0 + 16) / S) do
			for ry = f((y0 - 1) / S), f((y0 + 16) / S) do
				for rz = f((z0 - 1) / S), f((z0 + 16) / S) do markDirty(rx, ry, rz) end
			end
		end
	end
end

-- ---- scan: coverage and needs --------------------------------------------------------------------
-- Built (a stale region being rebuilt still counts: its old entity stays until the new one exists).
local function entsValid(r)
	if not r.ents or #r.ents == 0 then return false end
	for _, e in ipairs(r.ents) do if not IsValid(e) then return false end end
	return true
end

local function ready(r)
	return r ~= nil and (r.status == "ready" or (r.stale == true and (entsValid(r) or r.empty == true)))
end

-- Coverage of the tracked entities: every tick for the awake ones (a punt at 2000 u/s crosses a region
-- in 0.15 s: the flag must follow before ShouldCollide trusts it), and for the sleeping covered ones on
-- the full scans (they hold their regions). `full`: also compact the list and count.
local function cover(s, now, full)
	local list, n, covered = PW.list, 0, 0
	for i = 1, #list do
		local e = list[i]
		if IsValid(e) then
			n = n + 1
			list[n] = e
			local po = e:GetPhysicsObject()
			local awake = IsValid(po) and po:IsMotionEnabled() and not po:IsAsleep()
			if awake or (full and e.gmcPWc) then
				local mins, maxs = e:WorldSpaceAABB()
				if awake then  -- the box swept along the velocity (capped): where it can be soon
					local v = po:GetVelocity()
					local dx = math.Clamp(v.x * SPEED_LOOKAHEAD, -MARGIN_MAX, MARGIN_MAX)
					local dy = math.Clamp(v.y * SPEED_LOOKAHEAD, -MARGIN_MAX, MARGIN_MAX)
					local dz = math.Clamp(v.z * SPEED_LOOKAHEAD, -MARGIN_MAX, MARGIN_MAX)
					mins = Vector(mins.x + math.min(dx, 0), mins.y + math.min(dy, 0), mins.z + math.min(dz, 0))
					maxs = Vector(maxs.x + math.max(dx, 0), maxs.y + math.max(dy, 0), maxs.z + math.max(dz, 0))
				end
				local x0, x1, y0, y1, z0, z1 = regionRange(mins, maxs, MARGIN, s)
				local all = true
				for rx = x0, x1 do
					for ry = y0, y1 do
						for rz = z0, z1 do
							local r = request(key(rx, ry, rz), rx, ry, rz, now)
							if not ready(r) then all = false end
						end
					end
				end
				setCovered(e, all)
				if awake and full then  -- ahead of a fast mover: built before it gets there (a jeep at 300 u/s, live run)
					local v = po:GetVelocity()
					if v:LengthSqr() > 200 * 200 then
						local c0 = e:WorldSpaceCenter()
						for step = 1, 4 do  -- along the path, a quarter of REQUEST_AHEAD apart
							local c = c0 + v * (REQUEST_AHEAD * step / 4)
							local ax0, ax1, ay0, ay1, az0, az1 = regionRange(c, c, MARGIN, s)
							for rx = ax0, ax1 do
								for ry = ay0 - 1, ay1 do
									for rz = az0, az1 do request(key(rx, ry, rz), rx, ry, rz, now, now - UNLOAD_AFTER + 3) end  -- prefetch: held 3 s
								end
							end
						end
					end
				end
			end
			if e.gmcPWc then covered = covered + 1 end
		else
			PW.tracked[e] = nil
		end
	end
	for i = n + 1, #list do list[i] = nil end
	if full then stats.covered, stats.tracked = covered, n end
end

local function scan(s, now)
	-- regions whose entity vanished (game.CleanUpMap ...) are built again
	for _, r in pairs(PW.regions) do
		if r.status == "ready" and not r.empty and not entsValid(r) then
			for _, e in ipairs(r.ents or {}) do if IsValid(e) then e:Remove() end end
			r.status, r.ents, r.gen, r.stale = nil, nil, 0, nil
		end
	end
	-- players: the regions around them, so props they knock over are covered at once
	for _, p in ipairs(player.GetAll()) do
		local x0, x1, y0, y1, z0, z1 = regionRange(p:GetPos(), p:GetPos(), 0, s)
		for rx = x0 - PLAYER_PAD, x1 + PLAYER_PAD do
			for ry = y0 - 1, y1 + 1 do
				for rz = z0 - PLAYER_PAD, z1 + PLAYER_PAD do request(key(rx, ry, rz), rx, ry, rz, now) end
			end
		end
	end
end

-- ---- tick ----------------------------------------------------------------------------------------
function PW.Tick()
	local s = slot()
	local L = gmodcraft.serverLink
	if gmodcraft.missing or not gmodcraft.PhysRegionRequest or not cv:GetBool() or not s or not (L and L.mcAlive) then
		if active then PW.Reset() end
		return
	end
	local t0 = SysTime()
	WORLD = WORLD or game.GetWorld()
	local sk = s.ox .. "," .. s.oz .. "," .. (s.oy or 0)
	if sk ~= lastSlotKey then  -- a new slot origin moves every region
		PW.Reset()
		lastSlotKey = sk
		gmodcraft.PhysDirty()
	end
	if not active then
		active = true
		for _, e in ipairs(ents.GetAll()) do PW.Track(e) end
	end
	takeDirty()
	-- finished builds -> entities, within the budget (at least one)
	local deadline = t0 + BUDGET_MS / 1000
	repeat
		local job = PW.building[1]
		if job then
			if stepBuild(job) then
				table.remove(PW.building, 1)
				finish(job, s)
			end
		else
			local got = gmodcraft.PhysRegionTake(1)
			if not got[1] then break end
			queued = math.max(0, queued - 1)
			startBuild(got[1])
		end
	until SysTime() >= deadline
	local now = CurTime()
	local full = now - lastScan >= SCAN_EVERY
	if full then
		lastScan = now
		scan(s, now)
	end
	cover(s, now, full)
	if full then  -- unload after the entities renewed what they need
		local live = 0
		for k, r in pairs(PW.regions) do
			if now - (r.need or 0) > UNLOAD_AFTER and r.status ~= "wait" then
				removeRegion(k)
			elseif r.status == "bad" and not r.truncated and now - (r.badAt or now) > BAD_RETRY then
				r.status, r.badAt = nil, nil  -- a failed entity build: try again while needed
				live = live + 1
			else
				live = live + 1
			end
		end
		stats.regions = live
	end
	local ms = (SysTime() - t0) * 1000
	stats.tickMs = ms
	if ms > stats.maxTickMs then stats.maxTickMs = ms end
	if gmodcraft.PerfAdd then gmodcraft.PerfAdd("sv physworld", ms) end
end

hook.Add("Tick", "gmodcraft_physworld", PW.Tick)
hook.Add("ShouldCollide", "gmodcraft_physworld", PW.ShouldCollide)
hook.Add("OnEntityCreated", "gmodcraft_physworld", function(e)
	if not active then return end
	timer.Simple(0, function() if active then PW.Track(e) end end)  -- class and physics exist next tick
end)
hook.Add("EntityRemoved", "gmodcraft_physworld", function(e) PW.tracked[e] = nil end)
cvars.AddChangeCallback("gmodcraft_physics_world", function(_, _, v)
	if v == "0" then PW.Reset() end
end, "gmodcraft_physworld")

function PW.DebugTable()
	local info = gmodcraft.PhysInfo and gmodcraft.PhysInfo() or {}
	return { stats = stats, queued = info.queued, builds = info.builds, truncated = info.truncated, workerMaxMs = info.maxMs, size = PW.SIZE }
end

function PW.PrintInfo(ply)
	local t = PW.DebugTable()
	local st = t.stats
	local msg = string.format("[gmodcraft] physics world: %d regions (%d builds, %d rebuilds, %d bad, %d queued), %d/%d entities covered, %d flips, "
		.. "worker max %.2f ms, entity max %.2f ms, tick %.2f ms (max %.2f)", st.regions, st.builds, st.rebuilds, st.bad, t.queued or 0, st.covered,
		st.tracked, st.flips, t.workerMaxMs or 0, st.maxEntMs, st.tickMs, st.maxTickMs)
	if IsValid(ply) then ply:PrintMessage(HUD_PRINTCONSOLE, msg) else print(msg) end
	-- the costliest regions (entity creation on the game thread)
	local list = {}
	for _, r in pairs(PW.regions) do if r.info then list[#list + 1] = { r.rx .. "," .. r.ry .. "," .. r.rz, r.info } end end
	table.sort(list, function(a, b) return a[2].entMs > b[2].entMs end)
	local n, sum, conv = #list, 0, 0
	for _, x in ipairs(list) do sum, conv = sum + x[2].entMs, conv + x[2].convexes end
	msg = string.format("[gmodcraft]   %d built regions: entity %.2f ms avg, %d convexes in all", n, n > 0 and sum / n or 0, conv)
	if IsValid(ply) then ply:PrintMessage(HUD_PRINTCONSOLE, msg) else print(msg) end
	for i = 1, math.min(5, n) do
		local k, x = list[i][1], list[i][2]
		msg = string.format("[gmodcraft]   region %s: entity %.2f ms, worker %.2f ms, %d convexes (%d vertices): %d brush, %d prism, %d box pieces, %d carved, %d wedges",
			k, x.entMs, x.buildMs or 0, x.convexes, x.verts, x.brushes or 0, x.prisms or 0, x.boxes or 0, x.carved or 0, x.wedges or 0)
		if IsValid(ply) then ply:PrintMessage(HUD_PRINTCONSOLE, msg) else print(msg) end
	end
end

concommand.Add("gmodcraft_physics_world_info", function(ply)
	if IsValid(ply) and not ply:IsAdmin() then return end
	PW.PrintInfo(ply)
end)
