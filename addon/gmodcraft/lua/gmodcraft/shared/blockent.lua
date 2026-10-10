-- The gmodcraft_blocks entity class (registered here with scripted_ents.Register from
-- autorun/gmodcraft_init.lua's shared files, not discovered from lua/entities/: live run 7 found
-- the class missing, "Attempted to create unknown entity type", with no load error).
--
-- P5a: one invisible, frozen collision entity per 16^3 Minecraft section (docs/DESIGN.md section 9),
-- so GMod NPCs, props and non-MC players collide with what Minecraft players build.
--
-- The server creates and rebuilds these (gmodcraft/server/blockcol.lua) from the module's merged
-- boxes. Boxes travel as a packed string, 6 bytes per box (x0 y0 z0 x1 y1 z1, MC section-local,
-- half-open, in HALF BLOCKS since v26 (slabs, stairs): 0..32); the entity's origin is the section's
-- Source corner (MC block (16sx, 16sy, 16sz)), so a box maps to local Source [20 x0, 20 x1] x
-- [-20 z1, -20 z0] x [20 y0, 20 y1]. v36: microblocks' boxes come as a second packed string, the
-- same 6 bytes per box in EIGHTHS (0..128, 5 units each), kept apart from the half-block boxes.
--
-- Physics: PhysicsInitMultiConvex, MOVETYPE_VPHYSICS with motion disabled (HL2 NPCs then treat it
-- as a large vphysics obstacle and mark node links blocked), EnableCustomCollisions so traces use
-- the convexes and not the 640 u OBB, collision bounds = the boxes' bounds.
--
-- Clients get the same physics (for player movement prediction): the server sends the packed
-- boxes (net "gmodcraft_blockcol") to players near the section, tagged with the entity's build id
-- (NW2 int "gmc_build"); the client builds when the id matches. Works without the client module
-- (non-MC players don't have it). Not grabbable: physgun, gravgun, properties, unfreeze and the
-- duplicator are refused (hooks in this file). The toolgun acts on the map at the hit point instead.

local ENT = {}
ENT.Type = "anim"
ENT.Base = "base_anim"
ENT.PrintName = "Minecraft blocks (collision)"
ENT.Spawnable = false
ENT.AdminOnly = true
ENT.PhysgunDisabled = true
ENT.DisableDuplicator = true
ENT.DoNotDuplicate = true
ENT.m_tblToolsAllowed = {}

local CLASS = "gmodcraft_blocks"
local NET_NAME = "gmodcraft_blockcol"
local U = 20  -- Source units per half block (v26)
local N = 32  -- half blocks per section edge
local U8, N8 = 5, 128  -- v36 microblocks: Source units per eighth, eighths per section edge

-- Corner vectors of the 33^3 grid, shared by every build (PhysicsInitMultiConvex copies them).
local grid = {}
local function corner(x, y, z)
	local i = x + 33 * (y + 33 * z)
	local v = grid[i]
	if not v then
		v = Vector(x * U, -z * U, y * U)
		grid[i] = v
	end
	return v
end

-- packed boxes (half blocks) [+ v36 micro: packed microblock boxes, eighths] -> convex list,
-- local mins, local maxs, box count (nil when empty or malformed).
local function convexes(packed, micro)
	local n, m = math.floor(#packed / 6), math.floor(#(micro or "") / 6)
	if n + m == 0 then return nil end
	local out = {}
	-- bounds in Source units
	local mnx, mny, mnz, mxx, mxy, mxz = N * U, N * U, N * U, 0, 0, 0
	local byte = string.byte
	local function bound(x0, y0, z0, x1, y1, z1, u)
		if x0 * u < mnx then mnx = x0 * u end
		if y0 * u < mny then mny = y0 * u end
		if z0 * u < mnz then mnz = z0 * u end
		if x1 * u > mxx then mxx = x1 * u end
		if y1 * u > mxy then mxy = y1 * u end
		if z1 * u > mxz then mxz = z1 * u end
	end
	for i = 0, n - 1 do
		local x0, y0, z0, x1, y1, z1 = byte(packed, i * 6 + 1, i * 6 + 6)
		if x1 > N or y1 > N or z1 > N or x0 >= x1 or y0 >= y1 or z0 >= z1 then return nil end
		out[#out + 1] = {
			corner(x0, y0, z0), corner(x1, y0, z0), corner(x0, y1, z0), corner(x1, y1, z0),
			corner(x0, y0, z1), corner(x1, y0, z1), corner(x0, y1, z1), corner(x1, y1, z1),
		}
		bound(x0, y0, z0, x1, y1, z1, U)
	end
	for i = 0, m - 1 do
		local x0, y0, z0, x1, y1, z1 = byte(micro, i * 6 + 1, i * 6 + 6)
		if x1 > N8 or y1 > N8 or z1 > N8 or x0 >= x1 or y0 >= y1 or z0 >= z1 then return nil end
		local function c(x, y, z) return Vector(x * U8, -z * U8, y * U8) end
		out[#out + 1] = {
			c(x0, y0, z0), c(x1, y0, z0), c(x0, y1, z0), c(x1, y1, z0),
			c(x0, y0, z1), c(x1, y0, z1), c(x0, y1, z1), c(x1, y1, z1),
		}
		bound(x0, y0, z0, x1, y1, z1, U8)
	end
	-- Source local: x = U x, y = -U z, z = U y
	return out, Vector(mnx, -mxz, mny), Vector(mxx, -mnz, mxy), n + m
end
ENT.Convexes = convexes

function ENT:SetupDataTables()
end

-- Builds (or rebuilds) the physics from packed boxes (+ v36 packed microblock boxes). Returns true on success.
function ENT:BuildFromPacked(packed, micro)
	local list, mins, maxs = convexes(packed, micro)
	if not list then return false end
	if not self:PhysicsInitMultiConvex(list) then return false end
	self:SetSolid(SOLID_VPHYSICS)
	self:SetMoveType(MOVETYPE_VPHYSICS)
	self:EnableCustomCollisions(true)
	self:SetCollisionBounds(mins, maxs)
	local po = self:GetPhysicsObject()
	if IsValid(po) then
		po:EnableMotion(false)
		po:SetMaterial("default")
	end
	self.boxCount = #list
	return true
end

-- Box mode (the default since live run 8): one entity per merged box, centred on it, half-size
-- `half`. PhysicsInitBox, no custom collisions (they made props fall through, run 8), SOLID_BBOX:
-- traces, NPCs and players collide with the exact box (run 9). VPhysics ignores the SOLID_BBOX
-- entity: props, ragdolls and vehicles get Minecraft's blocks from the 0.4 physics world instead
-- (server/physworld.lua, gmodcraft_physics_world; D-023a).
-- Solid type and bounds are networked: clients predict without extra data.
function ENT:BuildBox(half)
	if not self:PhysicsInitBox(-half, half) then return false end
	self:SetMoveType(MOVETYPE_VPHYSICS)
	self:SetSolid(SOLID_BBOX)
	self:SetCollisionBounds(-half, half)
	local po = self:GetPhysicsObject()
	if IsValid(po) then
		po:EnableMotion(false)
		po:SetMaterial("default")
	end
	self.boxMode = true
	self.boxCount = 1
	return true
end

-- A placeholder model (never drawn): engine paths for custom physics / collisions expect one.
local PLACEHOLDER = "models/hunter/blocks/cube025x025x025.mdl"

function ENT:Initialize()
	if SERVER then self:SetModel(PLACEHOLDER) end
	self:DrawShadow(false)
	self:SetNoDraw(true)
	if CLIENT then self:ClientTryBuild() end
end

function ENT:CanTool() return false end
function ENT:CanProperty() return false end
function ENT:GravGunPickupAllowed() return false end
function ENT:GravGunPunt() return false end

-- Toolgun on Minecraft blocks: the tool acts as on the map at that point (weld, rope, axis anchor
-- to the world; remover, colour, material do nothing). These entities rebuild per section, so a
-- constraint to them would not last. The trace the hook gets is the one the tool then gets, so it
-- is retargeted to the world in place and the hook returns nothing (other hooks and the gamemode
-- still decide). Our own tools that need the block find the original entity in tr.gmcBlocks.
-- Shared: the toolgun is predicted, the client runs the same check (beam, ghost).
-- gmodcraft_physregion (server/physworld.lua) is listed too, though its traces pass through.
local TOOL_TO_WORLD = { [CLASS] = true, gmodcraft_physregion = true }
hook.Add("CanTool", "gmodcraft_blocks", function(_, tr)
	local e = tr and tr.Entity
	if not (IsValid(e) and TOOL_TO_WORLD[e:GetClass()]) then return end
	tr.gmcBlocks = e
	tr.Entity = game.GetWorld()
	tr.HitWorld = true
	tr.HitNonWorld = false
	tr.PhysicsBone = 0
	tr.HitBox = 0
end)

if SERVER then
	util.AddNetworkString(NET_NAME)

	function ENT:UpdateTransmitState()
		return TRANSMIT_PVS
	end

	-- Not touchable by players' tools (any gamemode): hooks return false only for this class.
	local function deny(_, ent) if IsValid(ent) and ent:GetClass() == CLASS then return false end end
	hook.Add("PhysgunPickup", "gmodcraft_blocks", deny)
	hook.Add("GravGunPickupAllowed", "gmodcraft_blocks", deny)
	hook.Add("GravGunPunt", "gmodcraft_blocks", deny)
	hook.Add("CanPlayerUnfreeze", "gmodcraft_blocks", deny)
	hook.Add("CanProperty", "gmodcraft_blocks", function(_, _, ent) if IsValid(ent) and ent:GetClass() == CLASS then return false end end)
	hook.Add("CanDrive", "gmodcraft_blocks", deny)
else
	-- Packed boxes received per entity index: { id = build id, packed = string }.
	local received = {}

	function ENT:ClientTryBuild()
		local id = self:GetNW2Int("gmc_build", 0)
		if id == 0 or self.builtId == id then return end
		local r = received[self:EntIndex()]
		if not r or r.id ~= id then return end
		if self:BuildFromPacked(r.packed, r.micro) then
			self.builtId = id
			received[self:EntIndex()] = nil
		end
	end

	function ENT:Think()
		self:ClientTryBuild()
		self:SetNextClientThink(CurTime() + 0.1)
		return true
	end

	function ENT:Draw() end

	net.Receive(NET_NAME, function()
		local idx = net.ReadUInt(16)
		local id = net.ReadUInt(32)
		local len = net.ReadUInt(16)
		local packed = len > 0 and net.ReadData(len) or ""
		local mlen = net.ReadUInt(16)  -- v36: microblock boxes (eighths)
		local micro = mlen > 0 and net.ReadData(mlen) or ""
		received[idx] = { id = id, packed = packed, micro = micro }
		local e = Entity(idx)
		if IsValid(e) and e:GetClass() == CLASS then e:ClientTryBuild() end
	end)
end

scripted_ents.Register(ENT, CLASS)
