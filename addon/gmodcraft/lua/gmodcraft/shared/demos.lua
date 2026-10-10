-- P7b (protocol v19): one-click demo builds, GMod side. The Minecraft server owns the blocks and
-- the undo data (fabric .../demo/DemoWorld.java); this file
--   * gates and forwards admin requests (spawnmenu "Demos" page, console gmodcraft_demo) as
--     kHostEvAdminCommand + text slot (gmodcraft.PushAdminCommand), only for admins
--     (game.SinglePlayer() or IsAdmin / IsSuperAdmin), and answers in chat from kEvAdminResult;
--   * spawns each demo's GMod parts on kEvDemoPlaced (props, a jeep, NPCs, wire entities linked
--     through WireLib when Wiremod is there), tagged with the instance id, and removes them on
--     kEvDemoCleared; kEvDemoSyncDone (after Minecraft re-announces its live instances: a new link
--     session, map or Minecraft restart) removes parts of instances Minecraft no longer has.
--
-- Frame: like the Java shapes, parts are given in MC coordinates relative to the demo's origin
-- block for yaw quarter 0 (forward = +z = south, y = 0 = the ground layer) and turned q quarters
-- clockwise seen from above about the origin block's centre: (x, z) -> (-z, x) per quarter.

local DM = gmodcraft.demos or {}
gmodcraft.demos = DM

DM.LIST = {
	{ kind = 1, name = "rails", title = "Rail loop", desc = "Powered and detector rails on a floor, two minecarts going round." },
	{ kind = 2, name = "redstone", title = "Redstone <-> Wiremod", desc = "Lever, lamp and a clock around a bridge block; a Wire Button lights the MC lamp, indicators show the clock and the lever." },
	{ kind = 3, name = "range", title = "Projectile range", desc = "A dispenser of arrows on a clock, shooting at crates, a barrel and a glass plate." },
	{ kind = 4, name = "ramp", title = "Vehicle ramp", desc = "A Minecraft ramp and jump in half-block steps, a jeep at the start (GMod vehicles drive on MC blocks)." },
	{ kind = 5, name = "arena", title = "NPC arena", desc = "A Minecraft wall ring with two rebels and a combine soldier inside." },
	{ kind = 6, name = "digwall", title = "Dig wall", desc = "Aim at a map wall: a marker frame shows where to dig (hole walls)." },
}
DM.BY_NAME, DM.BY_KIND = {}, {}
for _, d in ipairs(DM.LIST) do DM.BY_NAME[d.name], DM.BY_KIND[d.kind] = d, d end

DM.NET_REQ = "gmodcraft_demo_req"    -- C->S: { action, name, force, instance } (admins only)
DM.NET_LIST = "gmodcraft_demo_list"  -- S->C: the live instances (to admins)
DM.TAG = "gmodcraft_demo"

-- (x, z) turned q quarters clockwise seen from above: (x, z) -> (-z, x) per quarter.
function DM.Rotate(x, z, q)
	for _ = 1, (q or 0) % 4 do x, z = -z, x end
	return x, z
end

-- A point relative to the origin block's corner, turned about that block's centre (like blocks).
function DM.RotateCentered(x, z, q)
	local rx, rz = DM.Rotate(x - 0.5, z - 0.5, q)
	return rx + 0.5, rz + 0.5
end

-- ply == nil: the server's own console (a dedicated srcds); counts as admin. Net requests always
-- carry a valid player, so nil only comes from concommand / server code.
function DM.IsAdmin(ply)
	if ply == nil then return SERVER == true end
	if game.SinglePlayer() then return true end
	return IsValid(ply) and (ply:IsAdmin() or ply:IsSuperAdmin()) or false
end

-- A relative face name (q = 0 frame) -> the MC direction name after turning q quarters.
local FACE_VEC = { North = { 0, -1 }, South = { 0, 1 }, East = { 1, 0 }, West = { -1, 0 } }
function DM.Face(name, q)
	local v = FACE_VEC[name]
	if not v then return name end
	local x, z = DM.Rotate(v[1], v[2], q)
	for n, w in pairs(FACE_VEC) do if w[1] == x and w[2] == z then return n end end
	return name
end

-- GMod parts per demo kind. pos = MC (x, y, z) relative to the origin corner, q = 0 frame;
-- yaw = MC degrees relative to forward (0 = facing +z).
DM.PARTS = {
	[2] = {
		{ id = "button", wire = "gmod_wire_button", model = "models/props_citizen_tech/firetrap_button01a.mdl", pos = { 0.5, 0.0, 5.5 }, yaw = 180,
			setup = { true, 0, 15, "Minecraft lamp", false } },
		{ id = "clock", wire = "gmod_wire_indicator", model = "models/jaanus/wiretool/wiretool_siren.mdl", pos = { 2.5, 0.0, 5.5 }, yaw = 180,
			setup = { 0, 255, 0, 0, 255, 15, 0, 255, 0, 255 } },
		{ id = "lever", wire = "gmod_wire_indicator", model = "models/jaanus/wiretool/wiretool_siren.mdl", pos = { -1.5, 0.0, 5.5 }, yaw = 180,
			setup = { 0, 255, 0, 0, 255, 15, 0, 255, 0, 255 } },
		-- bridge block at (0, 1, 3); links once its wire entity exists (it arrives with kEvBridgePlaced)
		links = {
			{ from = "button", out = "Out", toBridge = "East" },           -- button -> bridge East input -> MC lamp
			{ fromBridge = "North", to = "clock", input = "A" },         -- MC clock -> indicator
			{ fromBridge = "West", to = "lever", input = "A" },          -- MC lever -> indicator
		},
		bridge = { 0, 1, 3 },
	},
	[3] = {
		{ id = "glass", model = "models/props_phx/construct/glass/glass_plate1x2.mdl", pos = { 0.5, 0.0, 8.5 }, yaw = 0, roll = 90 },
		{ id = "crate1", model = "models/props_junk/wood_crate001a.mdl", pos = { 0.5, 0.0, 11.5 }, yaw = 0 },
		{ id = "crate2", model = "models/props_junk/wood_crate001a.mdl", pos = { 0.5, 1.0, 11.5 }, yaw = 15 },
		{ id = "crate3", model = "models/props_junk/wood_crate001a.mdl", pos = { -1.5, 0.0, 12.5 }, yaw = -10 },
		{ id = "barrel", model = "models/props_c17/oildrum001_explosive.mdl", pos = { 2.0, 0.0, 13.5 }, yaw = 0 },
	},
	[4] = {
		{ id = "jeep", vehicle = "Jeep", pos = { 0.5, 0.0, -5.0 }, yaw = 0 },
	},
	[5] = {
		{ id = "rebel1", npc = "npc_citizen", keys = { citizentype = "3", additionalequipment = "weapon_smg1" }, pos = { -2.5, 0.0, 5.5 }, yaw = 0 },
		{ id = "rebel2", npc = "npc_citizen", keys = { citizentype = "3", additionalequipment = "weapon_ar2" }, pos = { -2.5, 0.0, 8.5 }, yaw = 0 },
		{ id = "combine", npc = "npc_combine_s", model = "models/combine_soldier.mdl", keys = { additionalequipment = "weapon_ar2" }, pos = { 3.5, 0.0, 9.5 }, yaw = 180 },
	},
}

if SERVER then util.AddNetworkString(DM.NET_REQ) util.AddNetworkString(DM.NET_LIST) end
if CLIENT then
	DM.clientList = DM.clientList or {}
	net.Receive(DM.NET_LIST, function()
		local n = net.ReadUInt(8)
		local list = {}
		for i = 1, n do
			list[i] = { id = net.ReadUInt(32), name = net.ReadString(), x = net.ReadInt(32), y = net.ReadInt(32), z = net.ReadInt(32) }
		end
		DM.clientList = list
		if DM.OnClientList then DM.OnClientList(list) end
	end)
	-- the Demos page asks the server; the server checks admin rights itself
	function DM.Request(action, name, force, instance)
		net.Start(DM.NET_REQ)
		net.WriteString(action)
		net.WriteString(name or "")
		net.WriteBool(force == true)
		net.WriteUInt(instance or 0, 32)
		net.SendToServer()
	end
	return
end

-- ---- server --------------------------------------------------------------------------------------
DM.instances = DM.instances or {}   -- id -> { id, kind, x, y, z, q, worldId, parts = { [partId] = ent }, linked, linkUntil, placedBy }
DM.pending = DM.pending or {}       -- requestId -> { ply, action, name, at }
DM.seen = DM.seen or {}             -- instance ids announced since the last kEvDemoSyncDone
DM.stats = DM.stats or { placed = 0, cleared = 0, spawned = 0, removed = 0, refused = 0, linked = 0, pushed = 0 }
-- One request id counter for everything on the admin channel (demos and the v20 STools): each
-- kEvAdminResult goes back to whoever owns its id.
gmodcraft.adminRequestId = gmodcraft.adminRequestId or 0
function gmodcraft.NextAdminRequest()
	gmodcraft.adminRequestId = gmodcraft.adminRequestId + 1
	return gmodcraft.adminRequestId
end

local function K() return gmodcraft.K or {} end
local function C() return gmodcraft.convert end
local function Info(fmt, ...) gmodcraft.Info(fmt, ...) end

local RESULT_TEXT = {  -- also DM.RESULT_TEXT (the STools use it)
	[0] = "done",
	[1] = "the area isn't empty: aim somewhere free, or tick Force",
	[2] = "unknown demo",
	[3] = "that is outside the GMod map's Minecraft area (or the map isn't linked yet)",
	[4] = "no such demo instance",
	[5] = "malformed request",
	[6] = "Minecraft couldn't do it (see its log)",
	[7] = "not allowed (admins only)",
	[8] = "nothing to do there",
	[9] = "not a block Minecraft knows (or an operator block)",
	[10] = "liquids only with 'allow liquids' ticked",
}
DM.RESULT_TEXT = RESULT_TEXT

local function tell(ply, msg)
	if IsValid(ply) then ply:ChatPrint("[Garry's Modcraft] " .. msg) else Info("demos: %s", msg) end
end
DM.Tell = tell

-- World position (Source Vector) and yaw (GMod degrees) of a part.
function DM.PartTransform(inst, part)
	local px, pz = DM.RotateCentered(part.pos[1], part.pos[3], inst.q)
	local pos = C().FromMc(inst.x + px, inst.y + part.pos[2], inst.z + pz)
	local mcYaw = (part.yaw or 0) + inst.q * 90
	return pos, C().YawFromMc(mcYaw)
end

local function tag(ent, inst, owner)
	ent.gmodcraftDemo = inst.id
	ent:SetNW2Int(DM.TAG, inst.id)
	if IsValid(owner) and ent.CPPISetOwner then pcall(ent.CPPISetOwner, ent, owner) end
end

local function spawnPart(inst, part, owner)
	local pos, yaw = DM.PartTransform(inst, part)
	local ang = Angle(0, yaw, part.roll or 0)
	local ent
	if part.wire then
		if not WireLib or not WireLib.MakeWireEnt then return nil end
		local ok, e = pcall(WireLib.MakeWireEnt, IsValid(owner) and owner or nil, { Class = part.wire, Pos = pos + Vector(0, 0, 4), Angle = ang, Model = part.model, frozen = true },
			unpack(part.setup or {}))
		ent = ok and e or nil
		-- MakeWireEnt returns false for a model WireLib.CanModel refuses (a wrong path) or a hit limit
		if not IsValid(ent) then gmodcraft.Info("demo %d: wire part %s (%s, %s) didn't spawn: %s", inst.id or 0, part.id, part.wire, part.model, ok and tostring(e) or tostring(e)) end
	elseif part.vehicle then
		local v = list.Get("Vehicles")[part.vehicle]
		if not v then return nil end
		ent = ents.Create(v.Class)
		if not IsValid(ent) then return nil end
		ent:SetModel(v.Model)
		for k, val in pairs(v.KeyValues or {}) do ent:SetKeyValue(k, val) end
		ent:SetPos(pos + Vector(0, 0, 16))
		ent:SetAngles(Angle(0, yaw - 90, 0)) -- Source vehicles face their local +y
		ent:Spawn()
		ent:Activate()
	elseif part.npc then
		ent = ents.Create(part.npc)
		if not IsValid(ent) then return nil end
		if part.model then ent:SetModel(part.model) end
		for k, val in pairs(part.keys or {}) do ent:SetKeyValue(k, val) end
		ent:SetPos(pos + Vector(0, 0, 8))
		ent:SetAngles(Angle(0, yaw, 0))
		ent:Spawn()
		ent:Activate()
		if ent.DropToFloor then ent:DropToFloor() end
	else
		ent = ents.Create("prop_physics")
		if not IsValid(ent) then return nil end
		ent:SetModel(part.model)
		ent:SetPos(pos + Vector(0, 0, 2))
		ent:SetAngles(ang)
		ent:Spawn()
		ent:Activate()
		if part.roll and ent.DropToFloor then ent:DropToFloor() end
	end
	if not IsValid(ent) then return nil end
	tag(ent, inst, owner)
	DM.stats.spawned = DM.stats.spawned + 1
	return ent
end

local function spawnParts(inst)
	local defs = DM.PARTS[inst.kind]
	inst.parts = inst.parts or {}
	if not defs or not C().slot.known then return end
	local owner = inst.placedByPly
	for _, part in ipairs(defs) do
		if not IsValid(inst.parts[part.id]) then
			local ent = spawnPart(inst, part, owner)
			if ent then inst.parts[part.id] = ent end
		end
	end
	inst.spawned = true
	if defs.links then
		inst.linked = false
		inst.linkUntil = CurTime() + 15
	end
end

local function removeParts(inst)
	for _, ent in pairs(inst.parts or {}) do
		if IsValid(ent) then ent:Remove() DM.stats.removed = DM.stats.removed + 1 end
	end
	inst.parts = {}
	-- anything that came loose (a dropped weapon keeps no tag, but ragdolls / re-created parts may)
	for _, ent in ipairs(ents.GetAll()) do
		if ent.gmodcraftDemo == inst.id and IsValid(ent) then ent:Remove() end
	end
end

-- The bridge's wire entity (P7) for an instance, once it exists.
function DM.BridgeEnt(inst)
	local defs = DM.PARTS[inst.kind]
	local W = gmodcraft.wirebridge
	if not defs or not defs.bridge or not W or not W.bridges then return nil end
	local bx, bz = DM.Rotate(defs.bridge[1], defs.bridge[3], inst.q)
	local b = W.bridges[W.Key(inst.x + bx, inst.y + defs.bridge[2], inst.z + bz)]
	return b and IsValid(b.ent) and b.ent or nil
end

-- Wire links of an instance (redstone demo): waits for the bridge entity, at most 15 s.
local function tryLinks(inst)
	local defs = DM.PARTS[inst.kind]
	if inst.linked ~= false or not defs or not defs.links then return end
	if not WireLib or not WireLib.Link_Start then inst.linked = true return end
	local bridge = DM.BridgeEnt(inst)
	if not bridge then
		if CurTime() > (inst.linkUntil or 0) then
			inst.linked = true
			tell(inst.placedByPly, "demo #" .. inst.id .. ": the bridge's wire entity didn't appear (is Wiremod on this server?): parts not linked")
		end
		return
	end
	local idx = -7000 - inst.id
	local n = 0
	for _, l in ipairs(defs.links) do
		if l.toBridge then
			local src = inst.parts[l.from]
			if IsValid(src) and WireLib.Link_Start(idx, bridge, bridge:GetPos(), DM.Face(l.toBridge, inst.q), "cable/cable2", Color(255, 60, 60), 1) then
				WireLib.Link_End(idx, src, src:GetPos(), l.out, nil)
				n = n + 1
			end
		else
			local dst = inst.parts[l.to]
			if IsValid(dst) and WireLib.Link_Start(idx, dst, dst:GetPos(), l.input, "cable/cable2", Color(60, 160, 255), 1) then
				WireLib.Link_End(idx, bridge, bridge:GetPos(), DM.Face(l.fromBridge, inst.q), nil)
				n = n + 1
			end
		end
	end
	inst.linked = true
	DM.stats.linked = DM.stats.linked + n
	Info("demo #%d: %d wire links to the bridge", inst.id, n)
end

local function sendList(to)
	local admins = {}
	if IsValid(to) then admins = { to } else
		for _, p in ipairs(player.GetHumans()) do if DM.IsAdmin(p) then admins[#admins + 1] = p end end
	end
	if #admins == 0 then return end
	local ids = {}
	for id in pairs(DM.instances) do ids[#ids + 1] = id end
	table.sort(ids)
	net.Start(DM.NET_LIST)
	net.WriteUInt(math.min(#ids, 255), 8)
	for i = 1, math.min(#ids, 255) do
		local inst = DM.instances[ids[i]]
		local d = DM.BY_KIND[inst.kind]
		net.WriteUInt(inst.id, 32)
		net.WriteString(d and d.name or "?")
		net.WriteInt(inst.x, 32)
		net.WriteInt(inst.y, 32)
		net.WriteInt(inst.z, 32)
	end
	net.Send(admins)
end
DM.SendList = sendList

-- MC events (server/link.lua). Returns true when it was a demo / admin event.
function DM.OnEvent(e)
	local k = K()
	local t = e.type
	if t == nil or (t ~= k.EvAdminResult and t ~= k.EvDemoPlaced and t ~= k.EvDemoCleared and t ~= k.EvDemoSyncDone) then return false end
	local L = gmodcraft.serverLink
	if t == k.EvAdminResult then
		local p = DM.pending[e.requestId]
		if not p then return false end  -- not a demos request (v20: the STools share the channel)
		DM.pending[e.requestId] = nil
		if e.result == (k.AdminOk or 0) then
			if p.action == "place" then tell(p.ply, string.format("placed demo %s (#%d); Clear removes it and restores the world exactly", p.name, e.flags))
			elseif p.action == "clear" then tell(p.ply, string.format("cleared demo #%d: the world is as it was", e.flags))
			elseif p.action == "clearall" then tell(p.ply, string.format("cleared %d demo(s)", e.a or 0)) end
		else
			DM.stats.refused = DM.stats.refused + 1
			tell(p.ply, string.format("%s %s refused: %s", p.action, p.name ~= "" and p.name or "", RESULT_TEXT[e.result] or ("result " .. tostring(e.result))))
		end
		return true
	end
	if t == k.EvDemoSyncDone then
		if e.result ~= (L and L.worldId) then return true end
		for id, inst in pairs(DM.instances) do
			if not DM.seen[id] then
				removeParts(inst)
				DM.instances[id] = nil
				Info("demo #%d: Minecraft no longer has it, GMod parts removed", id)
			end
		end
		DM.seen = {}
		sendList()
		return true
	end
	if e.worldId ~= (L and L.worldId) then return true end
	local id = e.instance
	if t == k.EvDemoCleared then
		local inst = DM.instances[id]
		if inst then removeParts(inst) end
		DM.instances[id] = nil
		DM.seen[id] = nil
		DM.stats.cleared = DM.stats.cleared + 1
		sendList()
		return true
	end
	-- kEvDemoPlaced (fresh or announced again): idempotent
	DM.seen[id] = true
	local inst = DM.instances[id]
	if not inst then
		inst = { id = id, kind = e.kind, x = e.x, y = e.y, z = e.z, q = e.yawQ or 0, worldId = e.worldId, parts = {} }
		DM.instances[id] = inst
		DM.stats.placed = DM.stats.placed + 1
		-- the placing admin (steamId of the event; "0" from the MC console): owner of the wire parts
		local sid = tostring(e.steamId or "0")
		if sid ~= "0" and player.GetBySteamID64 then inst.placedByPly = player.GetBySteamID64(sid) or nil end
	end
	spawnParts(inst)
	sendList()
	return true
end

-- Once per server tick (link alive): parts that couldn't spawn yet, wire links waiting for the bridge.
function DM.Tick()
	for _, inst in pairs(DM.instances) do
		if not inst.spawned then spawnParts(inst) end
		tryLinks(inst)
	end
	local now = CurTime()
	for id, p in pairs(DM.pending) do
		if now - p.at > 15 then
			DM.pending[id] = nil
			tell(p.ply, p.action .. ": Minecraft didn't answer (is it linked?)")
		end
	end
end

-- gmod_admin_cleanup / game.CleanUpMap() removed every part: spawn them again (next tick), wire them again.
hook.Add("PostCleanupMap", "gmodcraft_demos", function()
	for _, inst in pairs(DM.instances) do
		inst.spawned, inst.parts, inst.linked = false, {}, nil
	end
end)

-- An admin request (Demos page, console). Returns ok, message.
function DM.Request(ply, action, name, force, instance)
	if not DM.IsAdmin(ply) then
		DM.stats.refused = DM.stats.refused + 1
		return false, "demos are for admins only"
	end
	if gmodcraft.missing or not gmodcraft.PushAdminCommand then return false, "the gmodcraft module isn't loaded" end
	local L = gmodcraft.serverLink
	if action ~= "list" and L and L.slotBusy then return false, "a re-anchor is running in Minecraft: try again when it's done" end  -- P8 WP2 review
	if action == "list" then
		sendList(ply)
		local n = table.Count(DM.instances)
		return true, n == 0 and "no demos placed" or (n .. " demo(s) placed")
	end
	if not (L and L.mcAlive) then return false, "no Minecraft server is linked" end
	local k = K()
	local req = { steamId = IsValid(ply) and (ply:SteamID64() or "0") or "0", requestId = gmodcraft.NextAdminRequest(), worldId = L.worldId, name = "" }
	if action == "place" then
		if not DM.BY_NAME[name or ""] then return false, "unknown demo '" .. tostring(name) .. "'" end
		if not C().slot.known then return false, "the map's Minecraft area isn't known yet" end
		if not IsValid(ply) then return false, "place needs a player (it builds where you aim)" end
		local start = ply:EyePos()
		local tr = util.TraceLine({ start = start, endpos = start + ply:GetAimVector() * 4096, filter = ply, mask = MASK_SOLID })
		if not tr.Hit then return false, "aim at the ground (or at a wall for the dig wall)" end
		local p = tr.HitPos + tr.HitNormal * 20  -- the air block in front of / above what you aimed at
		local x, y, z = C().ToMc(p)
		req.code = k.AdminDemoPlace
		req.x, req.y, req.z = math.floor(x) + 0.5, math.floor(y) + 0.5, math.floor(z) + 0.5
		req.yaw = C().YawToMc(ply:EyeAngles().y)
		req.flags = force and (k.AdminForce or 1) or 0
		req.name = name
	elseif action == "clear" then
		req.code = k.AdminDemoClear
		req.a = tonumber(instance) or 0
	elseif action == "clearall" then
		req.code = k.AdminDemoClearAll
	else
		return false, "unknown action " .. tostring(action)
	end
	local ok, err = gmodcraft.PushAdminCommand(req)
	if not ok then return false, "couldn't send it: " .. tostring(err) end
	DM.pending[req.requestId] = { ply = ply, action = action, name = name or "", at = CurTime() }
	DM.stats.pushed = DM.stats.pushed + 1
	return true, nil
end

net.Receive(DM.NET_REQ, function(_, ply)
	if not IsValid(ply) then return end
	local now = RealTime()
	if ply.gmodcraftDemoReq and now - ply.gmodcraftDemoReq < 0.5 then return end
	ply.gmodcraftDemoReq = now
	local action, name, force, instance = net.ReadString(), net.ReadString(), net.ReadBool(), net.ReadUInt(32)
	local ok, msg = DM.Request(ply, action, name, force, instance)
	if action == "list" then return end  -- the page asks quietly when it opens
	if msg then tell(ply, (ok and "" or (action .. " refused: ")) .. msg) end
end)

concommand.Add("gmodcraft_demo", function(ply, _, args)
	local action = args[1] or "list"
	local name, force, instance = args[2], args[3] == "force", tonumber(args[2])
	if action == "place" then instance = nil end
	local ok, msg = DM.Request(IsValid(ply) and ply or nil, action, name, force, instance)
	if msg or not ok then tell(IsValid(ply) and ply or nil, (ok and "" or (action .. " refused: ")) .. (msg or "")) end
	if not IsValid(ply) and action == "list" then
		for id, inst in SortedPairs(DM.instances) do
			print(string.format("  #%d %s at %d %d %d (quarter %d)", id, (DM.BY_KIND[inst.kind] or {}).name or "?", inst.x, inst.y, inst.z, inst.q))
		end
	end
end, nil, "Garry's Modcraft demos (admins): place <" .. table.concat((function() local t = {} for _, d in ipairs(DM.LIST) do t[#t + 1] = d.name end return t end)(), "|")
	.. "> [force] | clear <id> | clearall | list")

function DM.DebugTable()
	return { instances = table.Count(DM.instances), pending = table.Count(DM.pending), stats = DM.stats }
end
