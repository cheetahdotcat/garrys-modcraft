-- P7 (D-013, protocol v16): the GMod half of the Minecraft redstone <-> Wiremod bridge.
--
-- Each gmodcraft:redstone_bridge block in Minecraft (inside this map's slot) gets one wire entity,
-- gmod_wire_gmodcraft_bridge, at the block's centre. Ports, one per block face (MC directions:
-- North = -Z, East = +X; in Source: North = +y, East = +x, Up = +z):
--   wire inputs  North South East West Up Down  (0-15, rounded and clamped): a LINKED input drives
--                that face, which gives weak redstone power of its level in Minecraft;
--   wire outputs North South East West Up Down: the redstone level Minecraft reads next to each
--                undriven face (a driven face reads 0).
-- Latency: wire -> redstone arrives at the MC server's next tick (<= 50 ms) plus our tick; redstone ->
-- wire likewise, so a round trip is ~100-150 ms. Both directions send only changes, latest value
-- wins, at most once per tick: clocks faster than ~20 Hz (MC's tick rate) alias.
--
-- Wiremod is optional: Wiremod's autorun runs after ours (alphabetical), so the class is registered
-- from the gamemode's Initialize hook (InitPostEntity as a fallback), in both realms, and only when
-- WireLib and base_wire_entity exist. Without Wiremod nothing is registered, the server link doesn't
-- set kServerWiremod, Minecraft keeps its bridges inert and sends no bridge events.
--
-- The entity: frozen (MOVETYPE_NONE), SOLID_BBOX 42 units (a little larger than the 40 unit block,
-- so the Wire tool's trace hits it before the block's own collision box), COLLISION_GROUP_WORLD
-- (players and props pass through), not drawn (Minecraft draws the block) except the wire overlay
-- when looked at. Not duplicable, not movable, not removable by tools: it lives and dies with the
-- block. A Minecraft restart keeps it (and its wires): Minecraft announces its bridges again.

local W = gmodcraft.wirebridge or {}
gmodcraft.wirebridge = W

W.CLASS = "gmod_wire_gmodcraft_bridge"
-- Port names in menu order, and the BridgeFace index (MC Direction order: Down Up North South West East).
W.PORTS = { "North", "South", "East", "West", "Up", "Down" }
W.FACE = { Down = 0, Up = 1, North = 2, South = 3, West = 4, East = 5 }
W.NAME = {}
for name, f in pairs(W.FACE) do W.NAME[f] = name end
W.MAX_ENTITIES = 512
W.EDICT_HEADROOM = 7900

-- ---- pure helpers (unit-tested in module/test/wirebridge_test.py) ---------------------------------

-- A wire value -> a redstone level: rounded, clamped to 0..15, non-numbers and NaN -> 0.
function W.Level(v)
	v = tonumber(v)
	if not v or v ~= v then return 0 end
	v = math.floor(v + 0.5)
	if v < 0 then return 0 end
	if v > 15 then return 15 end
	return v
end

-- levels[face + 1] (0..15 each) -> 24-bit packed (bits 4i..4i+3 = face i).
function W.Pack(levels)
	local p = 0
	for f = 0, 5 do p = p + W.Level(levels[f + 1]) * 2 ^ (4 * f) end
	return p
end

-- Packed -> levels[face + 1].
function W.Unpack(p)
	local out = {}
	p = math.floor(tonumber(p) or 0)
	for f = 0, 5 do
		out[f + 1] = math.floor(p / 2 ^ (4 * f)) % 16
	end
	return out
end

-- Driven mask (bit i = face i) of an entity: a face is driven while its wire input is linked.
function W.Mask(ent)
	local mask = 0
	local inputs = ent.Inputs or {}
	for name, f in pairs(W.FACE) do
		local inp = inputs[name]
		if inp and IsValid(inp.Src) then mask = mask + 2 ^ f end
	end
	return mask
end

-- What the entity drives: mask + packed levels, undriven faces normalised to 0 (so a value on an
-- unlinked input never causes a send).
function W.Outputs(ent)
	local mask = W.Mask(ent)
	local levels = {}
	for f = 0, 5 do
		levels[f + 1] = math.floor(mask / 2 ^ f) % 2 == 1 and W.Level(ent.wireIn and ent.wireIn[f + 1]) or 0
	end
	return mask, W.Pack(levels)
end

function W.Key(x, y, z)
	return string.format("%d,%d,%d", x, y, z)
end

-- ---- the entity class ----------------------------------------------------------------------------

local function buildClass()
	local ENT = {}
	ENT.Type = "anim"
	ENT.Base = "base_wire_entity"
	ENT.PrintName = "Minecraft Redstone Bridge"
	ENT.WireDebugName = "MC Redstone Bridge"
	ENT.Author = "Garry's Modcraft"
	ENT.Spawnable = false
	ENT.AdminOnly = true
	ENT.PhysgunDisabled = true
	ENT.DisableDuplicator = true
	ENT.DoNotDuplicate = true
	ENT.IsGmodcraftBridge = true

	local MINS, MAXS = Vector(-21, -21, -21), Vector(21, 21, 21)

	function ENT:Initialize()
		self:DrawShadow(false)
		if CLIENT then
			self:SetRenderBounds(MINS, MAXS)
			return
		end
		self:SetModel("models/hunter/blocks/cube025x025x025.mdl")
		self:SetMoveType(MOVETYPE_NONE)
		self:SetSolid(SOLID_BBOX)
		self:SetCollisionBounds(MINS, MAXS)
		self:SetCollisionGroup(COLLISION_GROUP_WORLD)
		self.wireIn = { 0, 0, 0, 0, 0, 0 }   -- wire input values (face + 1)
		self.mcIn = { 0, 0, 0, 0, 0, 0 }     -- redstone levels from Minecraft (face + 1)
		self.Inputs = WireLib.CreateInputs(self, W.PORTS)
		self.Outputs = WireLib.CreateOutputs(self, W.PORTS)
		self:UpdateOverlay()
	end

	if SERVER then
		function ENT:TriggerInput(name, value)
			local f = W.FACE[name]
			if f then self.wireIn[f + 1] = W.Level(value) end
			self:UpdateOverlay()
		end

		-- Minecraft's levels (kEvBridgePlaced / kEvBridgeInputs) -> wire outputs.
		function ENT:SetMcLevels(levels)
			for f = 0, 5 do
				local v = W.Level(levels[f + 1])
				if self.mcIn[f + 1] ~= v then
					self.mcIn[f + 1] = v
					WireLib.TriggerOutput(self, W.NAME[f], v)
				end
			end
			self:UpdateOverlay()
		end

		function ENT:UpdateOverlay()
			local mask = W.Mask(self)
			local lines = { string.format("Minecraft redstone bridge %s", self.bridgeKey or "") }
			for _, name in ipairs(W.PORTS) do
				local f = W.FACE[name]
				if math.floor(mask / 2 ^ f) % 2 == 1 then
					lines[#lines + 1] = string.format("%-5s  wire -> MC  %2d", name, self.wireIn[f + 1])
				else
					lines[#lines + 1] = string.format("%-5s  MC -> wire  %2d", name, self.mcIn[f + 1])
				end
			end
			self:SetOverlayText(table.concat(lines, "\n"))
		end

		function ENT:UpdateTransmitState() return TRANSMIT_PVS end
	else
		-- Minecraft draws the block: only the wire overlay (and wires) here.
		function ENT:Draw()
			if self:BeingLookedAtByLocalPlayer() then self:AddWorldTip() end
			if Wire_Render then Wire_Render(self) end
		end
	end

	function ENT:CanProperty() return false end
	function ENT:GravGunPickupAllowed() return false end
	function ENT:GravGunPunt() return false end
	return ENT
end

-- Registers the class when Wiremod is there (idempotent). Returns whether it is registered.
function W.Register()
	if W.registered then return true end
	if not WireLib or not scripted_ents.GetStored("base_wire_entity") then return false end
	scripted_ents.Register(buildClass(), W.CLASS)
	W.registered = true
	gmodcraft.Info("Wiremod found: %s registered (redstone bridge)", W.CLASS)
	return true
end

-- Wiremod is present and the class is ready (the server sets kServerWiremod from this).
function W.Present()
	return W.registered == true and WireLib ~= nil
end

hook.Add("Initialize", "gmodcraft_wirebridge", function() W.Register() end)
hook.Add("InitPostEntity", "gmodcraft_wirebridge", function()
	if not W.Register() then gmodcraft.Info("Wiremod not installed: Minecraft redstone bridges stay inert") end
end)
if WireLib then W.Register() end  -- a Lua refresh after start

-- Tools: only wire, wire_adv, wire_debugger, wire_namer and gmodcraft_maplink (R2) may touch it; never the remover, physgun,
-- gravgun, properties or the duplicator.
local function isBridge(ent) return IsValid(ent) and ent:GetClass() == W.CLASS end
-- Only the tools that link, inspect or name ports; any other mode (wire_* spawners, remover,
-- colour, ...) is refused. Under a prop protection (FPP etc.) the entity has no CPPI owner (it
-- belongs to a Minecraft block, not a GMod player), so such addons may still refuse non-admins.
W.TOOLS = { wire = true, wire_adv = true, wire_debugger = true, wire_namer = true, gmodcraft_maplink = true }  -- R2: the Map Link tool clicks the bridge
hook.Add("CanTool", "gmodcraft_wirebridge", function(_, tr, tool)
	if tr and isBridge(tr.Entity) and not W.TOOLS[tostring(tool or "")] then return false end
end)
hook.Add("CanProperty", "gmodcraft_wirebridge", function(_, _, ent) if isBridge(ent) then return false end end)
local function deny(_, ent) if isBridge(ent) then return false end end
hook.Add("PhysgunPickup", "gmodcraft_wirebridge", deny)
hook.Add("GravGunPickupAllowed", "gmodcraft_wirebridge", deny)
hook.Add("GravGunPunt", "gmodcraft_wirebridge", deny)
hook.Add("CanPlayerUnfreeze", "gmodcraft_wirebridge", deny)
hook.Add("CanDrive", "gmodcraft_wirebridge", deny)

if CLIENT then return end

-- ---- server: bridges <-> entities -----------------------------------------------------------------
-- W.bridges[key] = { x, y, z, worldId, levels (MC inputs), ent, sentMask, sentLevels }
W.bridges = W.bridges or {}
W.stats = W.stats or { placed = 0, removed = 0, inputs = 0, pushed = 0, spawnFailed = 0 }

local function K() return gmodcraft.K or {} end

local function liveCount()
	local n = 0
	for _, o in pairs(W.bridges) do if IsValid(o.ent) then n = n + 1 end end
	return n
end

-- live: how many wire entities exist (the caller counts once per batch).
local function spawn(b, live)
	if not W.Present() or not gmodcraft.convert.slot.known then return end
	if (live or liveCount()) >= W.MAX_ENTITIES or (ents.GetEdictCount and ents.GetEdictCount() >= W.EDICT_HEADROOM) then
		if not b.capLogged then gmodcraft.Info("redstone bridge %s: no wire entity (limit reached)", b.key) end
		b.capLogged = true
		return
	end
	local ent = ents.Create(W.CLASS)
	if not IsValid(ent) then
		W.stats.spawnFailed = W.stats.spawnFailed + 1
		return
	end
	ent.bridgeKey = b.key
	ent:SetPos(gmodcraft.convert.FromMc(b.x + 0.5, b.y + 0.5, b.z + 0.5))
	ent:SetAngles(Angle(0, 0, 0))
	ent:Spawn()
	ent:Activate()
	b.ent = ent
	b.sentMask, b.sentLevels = nil, nil
	ent:SetMcLevels(b.levels)
end

-- MC event -> true when it was a bridge event (handled or ignored).
function W.OnEvent(e)
	local k = K()
	local t = e.type
	if t == nil or (t ~= k.EvBridgePlaced and t ~= k.EvBridgeRemoved and t ~= k.EvBridgeInputs) then return false end
	if not W.Present() then return true end
	local L = gmodcraft.serverLink
	if e.worldId ~= (L and L.worldId) then return true end
	local key = W.Key(e.x, e.y, e.z)
	local b = W.bridges[key]
	if t == k.EvBridgeRemoved then
		W.stats.removed = W.stats.removed + 1
		if b then
			if IsValid(b.ent) then b.ent:Remove() end
			W.bridges[key] = nil
			gmodcraft.Log("link", "redstone bridge %s removed", key)
		end
		return true
	end
	if t == k.EvBridgePlaced then
		W.stats.placed = W.stats.placed + 1
		if not b then
			b = { key = key, x = e.x, y = e.y, z = e.z }
			W.bridges[key] = b
			gmodcraft.Info("redstone bridge at %s (MC): wire entity follows", key)
		end
		b.worldId = e.worldId
		b.levels = e.levels
		b.sentMask, b.sentLevels = nil, nil  -- (re)announced: Minecraft drives nothing yet, send ours again
		if IsValid(b.ent) then b.ent:SetMcLevels(b.levels) else spawn(b) end
		return true
	end
	W.stats.inputs = W.stats.inputs + 1
	if b then
		b.levels = e.levels
		if IsValid(b.ent) then b.ent:SetMcLevels(b.levels) end
	end
	return true
end

-- Once per server tick (link alive): entities for new bridges, then at most one output event per
-- bridge, only when its driven mask or levels changed.
function W.Tick()
	if not W.Present() then return end
	local k = K()
	local L = gmodcraft.serverLink
	local live
	for _, b in pairs(W.bridges) do
		if not IsValid(b.ent) then
			live = live or liveCount()
			spawn(b, live)
			if IsValid(b.ent) then live = live + 1 end
		end
		local ent = b.ent
		if IsValid(ent) then
			local mask, levels = W.Outputs(ent)
			if gmodcraft.mapio then mask, levels = gmodcraft.mapio.Merge(b.key, mask, levels) end  -- R2: a map link drives the faces the wire doesn't
			if mask ~= b.sentMask or levels ~= b.sentLevels then
				local ok = gmodcraft.PushHostEvent({ type = k.HostEvBridgeOutputs, worldId = L and L.worldId or 0,
					x = b.x, y = b.y, z = b.z, code = mask, flags = levels })
				if ok then
					b.sentMask, b.sentLevels = mask, levels
					W.stats.pushed = W.stats.pushed + 1
					ent:UpdateOverlay()
				end
			end
		end
	end
end

-- Server link flags: kServerWiremod while the class is registered.
function W.LinkFlags()
	return W.Present() and (K().ServerWiremod or 0) or 0
end

function W.DebugTable()
	local n = 0
	for _ in pairs(W.bridges) do n = n + 1 end
	return { present = W.Present(), bridges = n, stats = W.stats }
end
