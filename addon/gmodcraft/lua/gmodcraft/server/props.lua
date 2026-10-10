-- P1 (protocol v33): GMod props as Minecraft items (gmodcraft:gmod_prop). Server only.
--   Pickup: a paired Minecraft player sneak-uses (empty main hand) a GMod prop's collision -> kEvPropRequest
--     (kPropOpPickup, entId = the hit triangle's entity). Gates (PR.PickupGate): prop items on, hybrid mode
--     active for the player, a prop_physics (not ours), no constraints (v1), prop protection (CPPI
--     CPPICanPhysgun, else CPPIGetOwner / GetCreator must be the player or an admin), then the
--     PhysgunPickup hook. The prop is removed only once the give is in the ring (kHostEvPropResult + text).
--   Place: a stack used on a surface -> kEvPropRequest (kPropOpPlace + the prop text). Gates (PR.PlaceGate):
--     a sane model path that exists (util.IsValidModel), sbox_maxprops (CheckLimit "props"), then
--     PlayerSpawnProp. Spawned with its bottom on the hit point, facing the player; counted, cleanup and
--     undo registered, PlayerSpawnedProp run (prop protection addons take ownership there). Then acked:
--     Minecraft consumes the stack.
--   The player's GMod client is told the model (gmodcraft_prop_icon) so it sends the spawn icon
--   (client/propicons.lua).

local PR = gmodcraft.props or {}
gmodcraft.props = PR
local K = gmodcraft.K or {}
local C = gmodcraft.convert

local cvProps = CreateConVar("gmodcraft_prop_items", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: 1 = paired Minecraft players can pick up GMod props as items (sneak + use) and place them; 0 = off")

PR.NET_ICON = "gmodcraft_prop_icon"
if util and util.AddNetworkString then util.AddNetworkString(PR.NET_ICON) end
PR.stats = PR.stats or { pickups = 0, places = 0, refused = 0, failed = 0 }

local function R(name, fallback) return K[name] or fallback end

-- Canonical model path (lowercase, forward slashes), or nil when it isn't a plain "models/....mdl" path.
function PR.CleanModel(m)
	if type(m) ~= "string" then return nil end
	m = string.lower(string.gsub(m, "\\", "/"))
	if #m < 12 or #m > R("PropModelMaxBytes", 260) then return nil end
	if string.sub(m, 1, 7) ~= "models/" or string.sub(m, -4) ~= ".mdl" then return nil end
	if string.find(m, "..", 1, true) or string.find(m, "[%c]") then return nil end
	return m
end

-- Body groups as Entity:SetBodyGroups' string (one base-36 digit each), trailing zeros cut.
function PR.BodygroupString(ent)
	local parts = {}
	local n = ent.GetNumBodyGroups and ent:GetNumBodyGroups() or 0
	for i = 0, math.min(n, R("PropBodygroupsMaxBytes", 32)) - 1 do
		local v = math.Clamp(ent:GetBodygroup(i) or 0, 0, 35)
		parts[#parts + 1] = v < 10 and tostring(v) or string.char(87 + v)
	end
	return (string.gsub(table.concat(parts), "0+$", ""))
end

function PR.ColorToU32(c)
	if not c then return 0xFFFFFFFF end
	local r, g, b, a = math.Clamp(c.r or 255, 0, 255), math.Clamp(c.g or 255, 0, 255), math.Clamp(c.b or 255, 0, 255), math.Clamp(c.a or 255, 0, 255)
	return ((r * 256 + g) * 256 + b) * 256 + a
end

function PR.U32ToColor(u)
	u = tonumber(u) or 0xFFFFFFFF
	local a = u % 256; u = (u - a) / 256
	local b = u % 256; u = (u - b) / 256
	local g = u % 256; u = (u - g) / 256
	return u % 256, g, b, a
end

local function isAdmin(ply) return ply.IsAdmin and ply:IsAdmin() == true end

-- Hybrid gate: prop items on, the player in hybrid mode (paired + mapped, gmodcraft_hybrid on).
function PR.Enabled(ply)
	if not cvProps:GetBool() then return false end
	local HY = gmodcraft.hybrid
	if HY and HY.Enabled and not HY.Enabled() then return false end
	return IsValid(ply) and ply:GetNW2Bool("gmodcraft_hybrid", false) == true
end

-- May ply take ent? Returns a PropResult.
function PR.PickupGate(ply, ent)
	if not PR.Enabled(ply) then return R("PropDisabled", 5) end
	if not IsValid(ent) or ent:GetClass() ~= "prop_physics" or not PR.CleanModel(ent:GetModel()) then return R("PropNotAProp", 3) end
	if constraint and constraint.HasConstraints and constraint.HasConstraints(ent) then return R("PropConstrained", 4) end
	-- prop protection: CPPI when an addon provides it, else GMod's own creator
	if ent.CPPICanPhysgun then
		if ent:CPPICanPhysgun(ply) == false then return R("PropNotAllowed", 1) end
	else
		local owner = ent.CPPIGetOwner and ent:CPPIGetOwner() or (ent.GetCreator and ent:GetCreator())
		if IsValid(owner) and owner ~= ply and not isAdmin(ply) then return R("PropNotAllowed", 1) end
	end
	if hook.Run("PhysgunPickup", ply, ent) == false then return R("PropNotAllowed", 1) end
	return R("PropOk", 0)
end

-- May ply spawn model? Returns a PropResult.
function PR.PlaceGate(ply, model)
	if not PR.Enabled(ply) then return R("PropDisabled", 5) end
	if not model or (util.IsValidModel and not util.IsValidModel(model)) then return R("PropNotAProp", 3) end
	if ply.CheckLimit and not ply:CheckLimit("props") then return R("PropLimit", 2) end
	if hook.Run("PlayerSpawnProp", ply, model) == false then return R("PropNotAllowed", 1) end
	return R("PropOk", 0)
end

local function answer(e, op, result, extra)
	local t = { steamId = e.steamId, requestId = e.requestId, op = op, result = result }
	for k, v in pairs(extra or {}) do t[k] = v end
	local ok, err = gmodcraft.PushPropResult(t)
	if not ok then gmodcraft.Info("props: answer %d (op %d, result %d) failed: %s", e.requestId or 0, op, result, tostring(err)) end
	return ok == true
end

local function tellIcon(ply, model)
	if not (net and net.Start) then return end
	net.Start(PR.NET_ICON)
	net.WriteString(model)
	net.Send(ply)
end

function PR.OnPickup(e)
	local op = R("PropOpPickup", 1)
	local ply = player.GetBySteamID64(e.steamId)
	if not IsValid(ply) then return end
	local ent = Entity(tonumber(e.ent) or 0)
	local result = PR.PickupGate(ply, ent)
	if result ~= R("PropOk", 0) then
		PR.stats.refused = PR.stats.refused + 1
		gmodcraft.Log("props", "%s: pickup of %s refused (%d)", ply:Nick(), tostring(ent), result)
		answer(e, op, result, { ent = e.ent })
		return
	end
	local model = PR.CleanModel(ent:GetModel())
	local mat = string.lower(ent:GetMaterial() or "")
	if #mat > R("PropMaterialMaxBytes", 96) then mat = "" end
	local ok = answer(e, op, result, {
		ent = ent:EntIndex(), skin = ent:GetSkin() or 0, color = PR.ColorToU32(ent:GetColor()),
		model = model, material = mat, bodygroups = PR.BodygroupString(ent), dupe = "",
	})
	if not ok then PR.stats.failed = PR.stats.failed + 1 return end  -- the prop stays: nothing was given
	PR.stats.pickups = PR.stats.pickups + 1
	gmodcraft.Info("props: %s picked up %s (%s) into Minecraft", ply:Nick(), tostring(ent), model)
	ent:Remove()
	tellIcon(ply, model)
end

function PR.OnPlace(e)
	local op = R("PropOpPlace", 2)
	local ply = player.GetBySteamID64(e.steamId)
	if not IsValid(ply) then return end
	if e.complete == false then answer(e, op, R("PropMalformed", 6)) return end
	local model = PR.CleanModel(e.model)
	local result = PR.PlaceGate(ply, model)
	if result ~= R("PropOk", 0) then
		PR.stats.refused = PR.stats.refused + 1
		gmodcraft.Log("props", "%s: place of %s refused (%d)", ply:Nick(), tostring(e.model), result)
		answer(e, op, result)
		return
	end
	local ent = ents.Create("prop_physics")
	if not IsValid(ent) then answer(e, op, R("PropFailed", 7)) return end
	ent:SetModel(model)
	ent:SetSkin(tonumber(e.skin) or 0)
	if e.bodygroups and e.bodygroups ~= "" then ent:SetBodyGroups(e.bodygroups) end
	if e.material and e.material ~= "" then ent:SetMaterial(e.material) end
	local r, g, b, a = PR.U32ToColor(e.color)
	ent:SetColor(Color(r, g, b, a))
	if a < 255 then ent:SetRenderMode(RENDERMODE_TRANSCOLOR) end
	-- Facing the player (MC yaw -> Source), bottom centre on the hit point.
	local ang = Angle(0, C.YawFromMc(tonumber(e.yaw) or 0) + 180, 0)
	ent:SetAngles(ang)
	local hit = C.FromMc(tonumber(e.a) or 0, tonumber(e.b) or 0, tonumber(e.c) or 0)
	local mins, maxs = ent:OBBMins(), ent:OBBMaxs()
	local centre = LocalToWorld(Vector((mins.x + maxs.x) / 2, (mins.y + maxs.y) / 2, 0), Angle(0, 0, 0), Vector(0, 0, 0), ang)
	ent:SetPos(hit - Vector(centre.x, centre.y, mins.z - 0.5))
	ent:Spawn()
	ent:Activate()
	if ent.SetCreator then ent:SetCreator(ply) end
	if ply.AddCount then ply:AddCount("props", ent) end
	if ply.AddCleanup then ply:AddCleanup("props", ent) end
	if undo and undo.Create then
		undo.Create("Prop")
		undo.AddEntity(ent)
		undo.SetPlayer(ply)
		undo.Finish("Prop (" .. model .. ")")
	end
	gamemode.Call("PlayerSpawnedProp", ply, model, ent)
	PR.stats.places = PR.stats.places + 1
	gmodcraft.Info("props: %s placed %s from Minecraft", ply:Nick(), model)
	answer(e, op, R("PropOk", 0), { ent = ent:EntIndex() })
	tellIcon(ply, model)
end

function PR.OnRequest(e)
	if (tonumber(e.op) or 0) == R("PropOpPickup", 1) then PR.OnPickup(e)
	elseif (tonumber(e.op) or 0) == R("PropOpPlace", 2) then PR.OnPlace(e)
	elseif e.steamId and (e.requestId or 0) > 0 then
		gmodcraft.Log("props", "request %d: unknown op %s", e.requestId, tostring(e.op))
	end
end

if gmodcraft.combat and gmodcraft.combat.On and K.EvPropRequest then gmodcraft.combat.On(K.EvPropRequest, "p1_props", PR.OnRequest) end

function PR.DebugTable()
	return { enabled = cvProps:GetBool(), stats = PR.stats }
end
