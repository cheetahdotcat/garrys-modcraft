-- GMod NPCs in Minecraft, client side (P4a).
--
--  * The client link's actor table, every frame: NPCs, NextBots and other non-MC players near the
--    local player at the positions GMod renders them this frame. Minecraft's ProxySync moves the
--    proxies the MC server made (from the server link's table) to these, matched by entId, so the
--    MC crosshair and reach line up with what is on screen. Entities the server didn't list have no
--    proxy and are ignored there.
--  * Arrows stuck in actors (net gmodcraft_arrow): a clientside crossbow bolt on the nearest bone,
--    removed when the entity dies or goes, or after ARROW_LIFETIME.

local CA = gmodcraft.clientActors or {}
gmodcraft.clientActors = CA
local C = gmodcraft.convert

local RANGE = 80 * 40
local SCAN_INTERVAL = 0.25
local ARROW_MODEL = "models/crossbow_bolt.mdl"
local ARROW_LIFETIME = 30
local ARROW_DEPTH = 6        -- units the tip goes in
local ARROWS_PER_ENT = 12
local ARROWS_MAX = 96

CA.cands = CA.cands or {}
CA.count = 0
CA.arrows = CA.arrows or {}

local function isActor(e, me)
	if not IsValid(e) or e == me then return false end
	if string.sub(e:GetClass(), 1, 17) == "gmodcraft_mcproxy" or e:GetNW2Bool("gmcMcBody", false) then return false end  -- MC mobs' bodies / targets
	if e:IsNPC() or e.Type == "nextbot" or (e.IsNextBot and e:IsNextBot()) then return true end
	return e:IsPlayer() and not gmodcraft.IsMcPlayer(e)
end

local nextScan = 0
local list = {}
hook.Add("Think", "gmodcraft_client_actors", function()
	if gmodcraft.missing or not gmodcraft.SetActors then return end
	local CL = gmodcraft.clientLink
	local me = LocalPlayer()
	if not CL or not CL.mcAlive or not C.slot.known or not IsValid(me) then
		if CA.count ~= 0 then gmodcraft.SetActors({}) CA.count = 0 end
		return
	end
	local now = RealTime()
	if now >= nextScan then
		nextScan = now + SCAN_INTERVAL
		local cands = {}
		for _, e in ipairs(ents.FindInSphere(me:GetPos(), RANGE)) do
			if isActor(e, me) then cands[#cands + 1] = e end
		end
		CA.cands = cands
	end
	local n = 0
	for _, e in ipairs(CA.cands) do
		if IsValid(e) and n < (gmodcraft.K.MaxActors or 256) then
			n = n + 1
			local r = list[n] or {}
			list[n] = r
			local x, y, z = C.ToMc(e:GetPos())  -- the interpolated (rendered) origin
			local mins, maxs = e:OBBMins(), e:OBBMaxs()
			r.ent, r.flags, r.x, r.y, r.z = e:EntIndex(), 0, x, y, z
			r.yaw = C.YawToMc(e:GetAngles().y)
			r.width = math.max(maxs.x - mins.x, maxs.y - mins.y) / 40
			r.height = (maxs.z - mins.z) / 40
			r.healthFrac = 1
			r.name = e:GetClass()
		end
	end
	for i = #list, n + 1, -1 do list[i] = nil end
	if n == 0 and CA.count == 0 then return end
	CA.count = gmodcraft.SetActors(list)
end)

-- ---- arrows ------------------------------------------------------------------------------------------
local function removeArrow(i)
	local a = table.remove(CA.arrows, i)
	if a and IsValid(a.model) then a.model:Remove() end
end

local function clearOn(ent)
	for i = #CA.arrows, 1, -1 do
		if CA.arrows[i].ent == ent then removeArrow(i) end
	end
end

local function nearestBone(ent, pos)
	ent:SetupBones()
	local best, bestD
	for b = 0, (ent:GetBoneCount() or 0) - 1 do
		local bp = ent:GetBonePosition(b)
		if bp then
			local d = bp:DistToSqr(pos)
			if not bestD or d < bestD then best, bestD = b, d end
		end
	end
	return best
end

local function addArrow(ent, pos, dir, texture)
	if not IsValid(ent) then return end
	local mine = 0
	for _, a in ipairs(CA.arrows) do if a.ent == ent then mine = mine + 1 end end
	if mine >= ARROWS_PER_ENT then clearOn(ent) end  -- simplest cap: start over on that entity
	while #CA.arrows >= ARROWS_MAX do removeArrow(1) end
	local m = ClientsideModel(ARROW_MODEL, RENDERGROUP_OPAQUE)
	if not IsValid(m) then return end
	local ang = dir:Angle()
	local at = pos + dir * ARROW_DEPTH
	if texture == 2 then m:SetColor(Color(255, 230, 120)) elseif texture == 1 then m:SetColor(Color(200, 160, 255)) end
	local bone = nearestBone(ent, at)
	if bone then
		local bp, ba = ent:GetBonePosition(bone)
		local lp, la = WorldToLocal(at, ang, bp, ba)
		m:FollowBone(ent, bone)
		m:SetLocalPos(lp)
		m:SetLocalAngles(la)
	else
		local lp, la = WorldToLocal(at, ang, ent:GetPos(), ent:GetAngles())
		m:SetParent(ent)
		m:SetLocalPos(lp)
		m:SetLocalAngles(la)
	end
	CA.arrows[#CA.arrows + 1] = { model = m, ent = ent, at = RealTime() }
	CA.arrowsAdded = (CA.arrowsAdded or 0) + 1
end
CA.AddArrow = addArrow

net.Receive(gmodcraft.NET.arrow, function()
	local add = net.ReadBool()
	local ent = net.ReadEntity()
	if not add then
		clearOn(ent)
		return
	end
	local pos = net.ReadVector()
	local dir = net.ReadNormal()
	local texture = net.ReadUInt(2)
	addArrow(ent, pos, dir, texture)
end)

hook.Add("Think", "gmodcraft_client_arrows", function()
	local now = RealTime()
	for i = #CA.arrows, 1, -1 do
		local a = CA.arrows[i]
		local e = a.ent
		if not IsValid(a.model) or not IsValid(e) or now - a.at > ARROW_LIFETIME or (e:IsPlayer() and not e:Alive()) or e:IsDormant() then
			removeArrow(i)
		end
	end
end)
