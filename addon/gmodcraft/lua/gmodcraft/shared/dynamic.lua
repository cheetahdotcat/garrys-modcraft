-- P2c: the dynamic collision layer's Lua half (both realms; docs/DESIGN.md section 6). A few times
-- a second it lists the solid entities near the MC players (the server: every MC player; a
-- client: itself, from the networked state) and hands them to the module (ColDynUpdate), which
-- places each one's cached collision and re-sends the regions it left or entered.
--
--   classes   func_door, func_door_rotating, func_movelinear, func_brush, func_breakable,
--             func_physbox, prop_door_rotating, prop_physics (+ _multiplayer, _override; sandbox
--             props), prop_dynamic (+ _override), and scripted entities (SENTs) with VPHYSICS
--   filter    IsSolid, a solid type, not a player / NPC / NextBot / weapon / vehicle / ragdoll,
--             not parented to a player, a networked entity (index > 0), collision group not one
--             players pass through (DEBRIS, DEBRIS_TRIGGER, INTERACTIVE_DEBRIS, PASSABLE_DOOR,
--             WEAPON, IN_VEHICLE, WORLD, DOOR_BLOCKER)
--   models    "*N" come from the map (the module has them); a model's .phy is read once
--             (file.Open, like the static props) and given to the module (ColDynModel), which
--             falls back to the entity's OBB without one; on the server an entity with custom
--             physics (PhysicsInitConvex / FromMesh: no .phy, a physics object) is read once with
--             GetMeshConvexes (ColDynMesh). Ragdolls are skipped (their bones move apart; an OBB
--             of a ragdoll is mostly air).

local DY = gmodcraft.dynamic or {}
gmodcraft.dynamic = DY

local INTERVAL = 0.15  -- s between scans (~6.7 Hz)
-- How far around an MC player to look (units): the streamed region set (5 regions = 40 blocks
-- around, 3 below, 2 above) plus one region of slack for alignment and big entities.
local REGION_U = 8 * 40
local RANGE_XY, RANGE_DOWN, RANGE_UP = 6 * REGION_U, 4 * REGION_U, 3 * REGION_U

local CLASSES = {
	func_door = true, func_door_rotating = true, func_movelinear = true, func_brush = true, func_breakable = true, func_physbox = true,
	prop_door_rotating = true, prop_physics = true, prop_physics_multiplayer = true, prop_physics_override = true,
	prop_dynamic = true, prop_dynamic_override = true,
	func_button = true, func_rot_button = true, func_breakable_surf = true, func_wall_toggle = true, func_rotating = true,
	func_tracktrain = true, func_train = true, func_platrot = true, func_physbox_multiplayer = true,
}
local SKIP_GROUPS = {
	[COLLISION_GROUP_DEBRIS] = true, [COLLISION_GROUP_DEBRIS_TRIGGER] = true, [COLLISION_GROUP_INTERACTIVE_DEBRIS] = true,
	[COLLISION_GROUP_PASSABLE_DOOR] = true, [COLLISION_GROUP_WEAPON] = true, [COLLISION_GROUP_IN_VEHICLE] = true,
	[COLLISION_GROUP_WORLD] = true, [COLLISION_GROUP_DOOR_BLOCKER] = true,
}

DY.stats = DY.stats or { scans = 0, lastMs = 0, maxMs = 0, sumMs = 0, found = 0, listed = 0, phyRead = 0, phyMissing = 0, meshRead = 0, regions = 0 }
local stats = DY.stats
local phyExists = {}  -- model -> bool

local function hasPhy(model)
	local v = phyExists[model]
	if v == nil then
		v = file.Exists((model:gsub("%.mdl$", ".phy")), "GAME")
		phyExists[model] = v
	end
	return v
end

-- Is this entity part of the dynamic layer?
local function wanted(e)
	if not IsValid(e) or e:EntIndex() <= 0 then return false end
	local c = e:GetClass()
	if c == "gmodcraft_blocks" then return false end  -- Minecraft's own blocks (P5a): never back to MC
	if c == "gmodcraft_physregion" then return false end  -- Tier 0: the map and MC blocks for VPhysics, never to MC
	if string.sub(c, 1, 17) == "gmodcraft_mcproxy" then return false end  -- MC mobs' bodies (anim since v31)
	if not CLASSES[c] then
		if not (e:IsScripted() and e:GetSolid() == SOLID_VPHYSICS) then return false end
	end
	if e:IsPlayer() or e:IsNPC() or e:IsNextBot() or e:IsWeapon() or e:IsVehicle() or c == "prop_ragdoll" then return false end
	if not e:IsSolid() or e:GetSolid() == SOLID_NONE then return false end
	if SKIP_GROUPS[e:GetCollisionGroup()] then return false end
	local parent = e:GetParent()
	if IsValid(parent) and parent:IsPlayer() then return false end
	local m = e:GetModel()
	return m ~= nil and m ~= ""
end
DY.Wanted = wanted  -- P6i: client/carry.lua (only these entities carry an MC player)

-- The model key the module knows the entity's collision by (reads / sends what it lacks).
local function modelKey(e)
	local m = e:GetModel()
	if m:sub(1, 1) == "*" then return m end
	m = m:lower():gsub("\\", "/")
	if SERVER and not hasPhy(m) then
		-- Custom physics (no .phy, but a physics object): its convexes, once per entity.
		local po = e:GetPhysicsObject()
		if IsValid(po) then
			local key = string.format("#mesh:%d:%d", e:EntIndex(), e:GetCreationID())
			if not gmodcraft.ColDynKnows(key) then
				local ok, convexes = pcall(po.GetMeshConvexes, po)
				local flat = {}
				if ok and convexes then
					for i, cv in ipairs(convexes) do
						local f = {}
						for _, v in ipairs(cv) do
							local p = v.pos
							f[#f + 1], f[#f + 2], f[#f + 3] = p.x, p.y, p.z
						end
						flat[i] = f
					end
				end
				gmodcraft.ColDynMesh(key, flat)
				stats.meshRead = stats.meshRead + 1
			end
			return key
		end
	end
	if not gmodcraft.ColDynKnows(m) then
		local data
		local f = file.Open((m:gsub("%.mdl$", ".phy")), "rb", "GAME")
		if f then
			local n = f:Size()
			data = n > 0 and f:Read(n) or nil
			f:Close()
		end
		gmodcraft.ColDynModel(m, data)
		if data then stats.phyRead = stats.phyRead + 1 else stats.phyMissing = stats.phyMissing + 1 end
	end
	return m
end

local list = {}
-- centres: Source positions of the MC players to look around. Call every tick / frame; it scans
-- every INTERVAL s.
function DY.Update(centres)
	if gmodcraft.missing or not gmodcraft.ColDynUpdate then return end
	local now = SysTime()
	if now < (DY.nextScan or 0) then return end
	DY.nextScan = now + INTERVAL
	local seen = {}
	local n, found = 0, 0
	for _, c in ipairs(centres) do
		for _, e in ipairs(ents.FindInBox(c - Vector(RANGE_XY, RANGE_XY, RANGE_DOWN), c + Vector(RANGE_XY, RANGE_XY, RANGE_UP))) do
			found = found + 1
			if not seen[e] and wanted(e) then
				seen[e] = true
				n = n + 1
				local t = list[n] or {}
				list[n] = t
				local p, a, mn, mx = e:GetPos(), e:GetAngles(), e:OBBMins(), e:OBBMaxs()
				t.ent, t.model = e:EntIndex(), modelKey(e)
				t.x, t.y, t.z, t.p, t.yaw, t.r = p.x, p.y, p.z, a.p, a.y, a.r
				t.minX, t.minY, t.minZ, t.maxX, t.maxY, t.maxZ = mn.x, mn.y, mn.z, mx.x, mx.y, mx.z
				t.physics = e:GetMoveType() == MOVETYPE_VPHYSICS
				t.asleep = false
				if SERVER and t.physics then
					local po = e:GetPhysicsObject()
					t.asleep = IsValid(po) and (po:IsAsleep() or not po:IsMotionEnabled())
				end
			end
		end
	end
	for i = n + 1, #list do list[i] = nil end
	stats.regions = stats.regions + gmodcraft.ColDynUpdate(list)
	local ms = (SysTime() - now) * 1000
	stats.scans = stats.scans + 1
	stats.lastMs, stats.sumMs = ms, stats.sumMs + ms
	stats.maxMs = math.max(stats.maxMs, ms)
	stats.found, stats.listed = found, n
end
