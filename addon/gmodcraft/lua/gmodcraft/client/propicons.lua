-- P1 (protocol v33): spawn icons of the GMod props players hold as Minecraft items. The server names every
-- model that became a prop item (gmodcraft_prop_icon: a count, then the models; all of them when this
-- client joins, new ones as they come, G1); this client draws "spawnicons/<model>.png" (else the model
-- itself, rendered into the icon target) and sends it on the client link's icon ring keyed by the model
-- hash (kColWeaponIcon, the weapon icons' ring). A new Minecraft session gets every known model again.

local PI = gmodcraft.propIcons or {}
gmodcraft.propIcons = PI
local HS = gmodcraft.hybridShared
local HC = gmodcraft.hybridClient

PI.known = PI.known or {}   -- model -> true (this GMod session)
PI.queue = PI.queue or {}
PI.sent = PI.sent or {}     -- hash -> true (this Minecraft session)
PI.MAX = 512

local function hash(model) return HS and HS.Hash(model) or 0 end

function PI.Want(model)
	if type(model) ~= "string" or model == "" then return end
	if not PI.known[model] then
		if table.Count(PI.known) >= PI.MAX then return end
		PI.known[model] = true
	end
	if not PI.sent[hash(model)] then PI.queue[#PI.queue + 1] = model end
end

net.Receive("gmodcraft_prop_icon", function()
	for _ = 1, net.ReadUInt(8) do PI.Want(net.ReadString()) end
end)

local rt, mats = nil, {}
local function capture(model)
	local S = (HC and HC.ICON_SIZE) or 64
	rt = rt or GetRenderTargetEx("gmodcraft_prop_icon", S, S, RT_SIZE_LITERAL, MATERIAL_RT_DEPTH_SEPARATE, 0, 0, IMAGE_FORMAT_RGBA8888)
	local png = "spawnicons/" .. string.sub(model, 1, -5) .. ".png"
	local usePng = file.Exists("materials/" .. png, "GAME")
	render.PushRenderTarget(rt)
	render.OverrideAlphaWriteEnable(true, true)
	render.Clear(0, 0, 0, 0, true, true)
	local ok = pcall(function()
		if usePng then
			local m = mats[model] or Material(png, "smooth")
			mats[model] = m
			cam.Start2D()
			render.OverrideBlend(true, BLEND_SRC_ALPHA, BLEND_ONE_MINUS_SRC_ALPHA, BLENDFUNC_ADD, BLEND_ONE, BLEND_ONE_MINUS_SRC_ALPHA, BLENDFUNC_ADD)
			surface.SetMaterial(m)
			surface.SetDrawColor(255, 255, 255, 255)
			surface.DrawTexturedRect(0, 0, S, S)
			render.OverrideBlend(false)
			cam.End2D()
		else
			-- No spawn icon on disk: the model itself, framed like a spawn icon.
			local ent = ClientsideModel(model, RENDERGROUP_OPAQUE)
			if not IsValid(ent) then error("no model") end
			ent:SetNoDraw(true)
			local mins, maxs = ent:GetRenderBounds()
			local centre = (mins + maxs) / 2
			local size = math.max((maxs - mins):Length(), 1)
			local eye = centre + Vector(1, 0.8, 0.6):GetNormalized() * size * 1.1
			cam.Start3D(eye, (centre - eye):Angle(), 40, 0, 0, S, S, 1, size * 4)
			render.SuppressEngineLighting(true)
			render.ResetModelLighting(0.8, 0.8, 0.8)
			render.SetModelLighting(BOX_TOP, 1, 1, 1)
			ent:DrawModel()
			render.SuppressEngineLighting(false)
			cam.End3D()
			ent:Remove()
		end
	end)
	render.CapturePixels()
	local data = ok and HC.PackPixels(function(x, y) return render.ReadPixel(x, y) end, S, S) or nil
	if data and HC.AllTransparent(data) then data = nil end
	render.OverrideAlphaWriteEnable(false)
	render.PopRenderTarget()
	return data, S, usePng and "png" or "model"
end

hook.Add("PostRender", "gmodcraft_prop_icons", function()
	if not (gmodcraft.SendWeaponIcon and HC and HC.PackPixels) then return end
	local CL = gmodcraft.clientLink
	if not (CL and CL.mcAlive) then return end
	if PI.nonce ~= CL.mcNonce then
		-- a new Minecraft session: it has no icons yet
		PI.nonce, PI.sent, PI.queue = CL.mcNonce, {}, {}
		for model in pairs(PI.known) do PI.queue[#PI.queue + 1] = model end
	end
	local model = table.remove(PI.queue, 1)
	if not model then return end
	local h = hash(model)
	if PI.sent[h] then return end
	local data, S, source = capture(model)
	if not data then
		PI.sent[h] = true  -- nothing to draw: Minecraft keeps the crate sprite
		return
	end
	if gmodcraft.SendWeaponIcon(h, S, S, data) then
		PI.sent[h] = true
		gmodcraft.Log("hybrid", "prop icon for %s (%s) sent", model, source)
	else
		PI.queue[#PI.queue + 1] = model  -- ring full: later
	end
end)
