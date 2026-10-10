-- Hybrid mode, part 2 (H2, D-016): input routing and the weapon view for a player whose Minecraft
-- hand holds a gmodcraft:gmod_weapon (McState.heldWeapon = its class hash, 0 = a normal item).
--  * Routing (client/input.lua asks HC.Route per key): while a GMod weapon is held and no MC screen
--    is open, the mouse buttons and the reload key are GMod's (IN_ATTACK / ATTACK2 / RELOAD in the
--    usercmd; a button Minecraft still holds is released first). Held = 0: everything is Minecraft's
--    and GMod holds the hands SWEP. CreateMove selects the held weapon (HC.Select).
--  * Use key (gmodcraft_use_mode): 0 = G is GMod +use, E opens Minecraft's inventory (default);
--    1 = context E: E is GMod +use while the crosshair is on a GMod usable within use range (door,
--    button, seat, vehicle, wire button), else Minecraft's inventory (decided when E goes down);
--    2 = swapped: E is GMod +use, Minecraft's inventory is on gmodcraft_inventory_key (I).
--  * gmodcraft_reload_key (R), gmodcraft_vehicle_controls (0 = GMod's own binds while seated, 1 = W/A/S/D,
--    Space, Shift as Minecraft's defaults drive the vehicle whatever GMod's binds are),
--    gmodcraft_gmod_mc_inventory_key / gmodcraft_gmod_mc_chat_key (0 = off): in GMod mode, open
--    Minecraft's inventory / chat without switching modes.
--  * View: the viewmodel is drawn only while the held weapon is the active one (first person), placed
--    at Minecraft's camera (CalcViewModelView); GMod's ammo HUD shows while it is; MC players' weapon
--    world models are hidden (Minecraft draws their avatars). HostState gets hybridFlags + ammo.

local HC = gmodcraft.hybridClient or {}
gmodcraft.hybridClient = HC
local HS = gmodcraft.hybridShared
local K = gmodcraft.K or {}
local CL = gmodcraft.clientLink
local bor = bit.bor

HC.cvUseMode = CreateClientConVar("gmodcraft_use_mode", "0", true, true,
	"Garry's Modcraft: 0 = G is GMod use, E Minecraft's inventory; 1 = context E (GMod use on doors/buttons/seats/vehicles, else the inventory); 2 = E is GMod use, the inventory on gmodcraft_inventory_key", 0, 2)
HC.cvInvKey = CreateClientConVar("gmodcraft_inventory_key", tostring(KEY_I or 18), true, false,
	"Garry's Modcraft: the key that opens Minecraft's inventory with gmodcraft_use_mode 2 (a KEY_* code)")
HC.cvReloadKey = CreateClientConVar("gmodcraft_reload_key", tostring(KEY_R or 28), true, false,
	"Garry's Modcraft: reload key while a GMod weapon is held in Minecraft (a KEY_* code)")
HC.cvVehicle = CreateClientConVar("gmodcraft_vehicle_controls", "0", true, false,
	"Garry's Modcraft: seated in a GMod vehicle as an MC player: 0 = GMod's own binds; 1 = W/A/S/D, Space, Shift (Minecraft's defaults) drive it", 0, 1)
HC.cvShortInv = CreateClientConVar("gmodcraft_gmod_mc_inventory_key", "0", true, false,
	"Garry's Modcraft: in GMod mode, this key opens Minecraft's inventory (a KEY_* code; 0 = off)")
HC.cvShortChat = CreateClientConVar("gmodcraft_gmod_mc_chat_key", "0", true, false,
	"Garry's Modcraft: in GMod mode, this key opens Minecraft's chat (a KEY_* code; 0 = off)")

HC.USE_RANGE = 80  -- units: within the server's +use reach (about 80 from the eye), so a routed E always uses something

-- ---- routing (pure; module/test/hybrid_client_test.py) ---------------------------------------------
-- ctx = { held, useMode, reloadKey, invKey, eTarget }. Returns "mc" (to Minecraft as itself),
-- "mc_e" (to Minecraft as its inventory key E), or "gmod" (a GMod button; never to Minecraft).
function HC.Route(code, ctx)
	if code == MOUSE_LEFT or code == MOUSE_RIGHT then return ctx.held and "gmod" or "mc" end
	if code == ctx.reloadKey and ctx.held then return "gmod" end
	if code == KEY_E then
		if ctx.useMode == 2 then return "gmod" end
		if ctx.useMode == 1 and ctx.eTarget then return "gmod" end
		return "mc"
	end
	if ctx.useMode == 2 and code == ctx.invKey then return "mc_e" end
	return "mc"
end

-- The usercmd buttons for the keys GMod owns this frame. down(code) -> bool; route(code) -> as above.
function HC.Buttons(down, route, ctx)
	local b = 0
	if down(MOUSE_LEFT) and route(MOUSE_LEFT) == "gmod" then b = bor(b, IN_ATTACK) end
	if down(MOUSE_RIGHT) and route(MOUSE_RIGHT) == "gmod" then b = bor(b, IN_ATTACK2) end
	if down(ctx.reloadKey) and route(ctx.reloadKey) == "gmod" then b = bor(b, IN_RELOAD) end
	if down(KEY_E) and route(KEY_E) == "gmod" then b = bor(b, IN_USE) end
	return b
end

-- H3 physgun: G (+use) while LMB holds something rotates it with the mouse (Minecraft's look stays
-- frozen, the mouse counts go into the usercmd), Shift snaps; the wheel pushes/pulls while LMB is
-- held with the physgun, else it scrolls Minecraft's hotbar. ctx = { active (class), lmb, use, shift,
-- holding }: holding = the server's NW2 "gmodcraft_physgun_holding" (OnPhysgunPickup / PhysgunDrop),
-- so LMB on nothing neither freezes the look nor takes the wheel.
function HC.Physgun(ctx)
	local phys = ctx.active == "weapon_physgun" and ctx.lmb == true and ctx.holding == true
	local rotate = phys and ctx.use == true
	return { rotate = rotate, snap = rotate and ctx.shift == true, wheelToGmod = phys }
end

-- What a seat gets from Minecraft's default movement keys (gmodcraft_vehicle_controls 1).
function HC.VehicleInput(down)
	local b, fwd, side = 0, 0, 0
	if down(KEY_W) then b = bor(b, IN_FORWARD) fwd = fwd + 10000 end
	if down(KEY_S) then b = bor(b, IN_BACK) fwd = fwd - 10000 end
	if down(KEY_A) then b = bor(b, IN_MOVELEFT) side = side - 10000 end
	if down(KEY_D) then b = bor(b, IN_MOVERIGHT) side = side + 10000 end
	if down(KEY_SPACE) then b = bor(b, IN_JUMP) end
	if down(KEY_LSHIFT) then b = bor(b, IN_SPEED) end
	return b, fwd, side
end

-- GMod usables for context E, by class (vehicles are asked with IsVehicle).
local USABLE = {
	prop_door_rotating = true, func_door = true, func_door_rotating = true, func_button = true, func_rot_button = true,
	momentary_rot_button = true, gmod_button = true, gmod_wire_button = true, gmod_wire_dual_input = true, gmod_wire_keypad = true,
	prop_vehicle_prisoner_pod = true, prop_vehicle_jeep = true, prop_vehicle_airboat = true, prop_vehicle_apc = true,
	func_tracktrain = true, item_healthcharger = true, item_suitcharger = true, func_healthcharger = true, func_recharge = true,
}
HC.USABLE = USABLE

function HC.UsableClass(class, isVehicle)
	if isVehicle then return true end
	if not class then return false end
	return USABLE[class] == true or class:sub(1, 12) == "prop_vehicle" or class:sub(1, 14) == "gmod_sent_vehi"
end

-- The GMod usable under the crosshair within use range, or nil.
function HC.UseTarget(ply)
	if not IsValid(ply) then return nil end
	local start = ply:EyePos()
	local tr = util.TraceLine({ start = start, endpos = start + ply:GetAimVector() * HC.USE_RANGE, filter = ply,
		mask = bor(MASK_SOLID, CONTENTS_DEBRIS, CONTENTS_PLAYERCLIP) })
	local e = tr.Entity
	if not IsValid(e) then return nil end
	if HC.UsableClass(e:GetClass(), e.IsVehicle and e:IsVehicle()) then return e end
	return nil
end

-- ---- held weapon -------------------------------------------------------------------------------
function HC.HeldHash()
	return (CL and CL.M and CL.M.heldWeapon) or 0
end

function HC.Hybrid(ply)
	return IsValid(ply) and ply:GetNW2Bool("gmodcraft_hybrid", false)
end

-- The weapon the player owns whose class hashes to h (nil: not owned yet; the server gives it).
function HC.WeaponForHash(ply, h)
	if h == 0 or not IsValid(ply) then return nil end
	for _, w in ipairs(ply:GetWeapons()) do
		local c = w:GetClass()
		if c ~= HS.HANDS and HS.Hash(c) == h then return w end
	end
	return nil
end

-- The held GMod weapon, when it is the active one.
function HC.ActiveHeld(ply)
	if not HC.Hybrid(ply) then return nil end
	local h = HC.HeldHash()
	if h == 0 then return nil end
	local w = ply:GetActiveWeapon()
	if IsValid(w) and w:GetClass() ~= HS.HANDS and HS.Hash(w:GetClass()) == h then return w end
	return nil
end

-- CreateMove: the usercmd selects what Minecraft's hand holds (held = 0: the hands).
function HC.Select(cmd, ply)
	if not HC.Hybrid(ply) then return end
	local h = HC.HeldHash()
	local want = h ~= 0 and HC.WeaponForHash(ply, h) or ply:GetWeapon(HS.HANDS)
	if not IsValid(want) then return end
	local cur = ply:GetActiveWeapon()
	if cur ~= want then cmd:SelectWeapon(want) end
end

-- HostState hybrid fields: flags, clip1, maxClip1, ammo1, ammo2.
function HC.HostFields(ply)
	if not HC.Hybrid(ply) then return 0, -1, -1, -1, -1 end
	local flags = K.HostHybrid or 1
	local w = HC.ActiveHeld(ply)
	if not w then return flags, -1, -1, -1, -1 end
	flags = bor(flags, K.HostWeaponActive or 2)
	local a1, a2 = w:GetPrimaryAmmoType(), w:GetSecondaryAmmoType()
	return flags, w:Clip1(), w:GetMaxClip1(), a1 >= 0 and ply:GetAmmoCount(a1) or -1, a2 >= 0 and ply:GetAmmoCount(a2) or -1
end

-- The viewmodel is drawn only for the held weapon, in first person, on foot.
function HC.DrawViewModel(ply, wep)
	if not IsValid(wep) or HC.ActiveHeld(ply) ~= wep then return false end
	if ply:InVehicle() or ((CL.M and CL.M.cameraMode) or 0) ~= 0 then return false end
	return true
end

-- ---- view hooks (viewmodel / HUD only) -----------------------------------------------------------
hook.Add("CalcViewModelView", "gmodcraft_hybrid", function(wep, vm, oldPos, oldAng, pos, ang)
	local ply = LocalPlayer()
	if not (gmodcraft.IsPuppet(ply) and HC.Hybrid(ply)) then return end
	-- The view this frame (with gmodcraft_camera 1 that is Minecraft's camera, from CalcView); the
	-- last Minecraft view only if the engine passed none.
	local V = gmodcraft.view
	local p, a = pos, ang
	if not (p and a) then
		if not (V and V.lastView) then return end
		p, a = V.lastView.origin, V.lastView.angles
	end
	p, a = p * 1, a * 1
	-- The weapon's own sway / iron sights on top, as the base gamemode does.
	if wep.GetViewModelPosition then
		local np, na = wep:GetViewModelPosition(p * 1, a * 1)
		p, a = np or p, na or a
	end
	if wep.CalcViewModelView then
		local np, na = wep:CalcViewModelView(vm, oldPos, oldAng, p * 1, a * 1)
		p, a = np or p, na or a
	end
	return p, a
end)

hook.Add("PreDrawPlayerHands", "gmodcraft_hybrid", function(hands, vm, ply, wep)
	if gmodcraft.IsMcPlayer(ply) and not HC.DrawViewModel(ply, wep) then return true end
end)

-- Other MC players' weapons: Minecraft draws their avatars, so no GMod world model floats there.
local hidden = {}
hook.Add("Think", "gmodcraft_hybrid_worldmodels", function()
	if RealTime() - (HC.wmAt or 0) < 0.25 then return end
	HC.wmAt = RealTime()
	local seen = {}
	local me = LocalPlayer()
	for _, p in ipairs(player.GetAll()) do
		-- Not our own: its world model is never drawn (ShouldDrawLocalPlayer), and hiding the active
		-- weapon could take the viewmodel with it.
		if p ~= me and gmodcraft.IsMcPlayer(p) then
			for _, w in ipairs(p:GetWeapons()) do
				seen[w] = true
				if not hidden[w] then w:SetNoDraw(true) hidden[w] = true end
			end
		end
	end
	for w in pairs(hidden) do
		if not seen[w] then
			if IsValid(w) then w:SetNoDraw(false) end
			hidden[w] = nil
		end
	end
end)

-- ---- H3: weapon icons for Minecraft's hotbar (protocol v18 kColWeaponIcon) -------------------------
-- Each weapon class this player owns gets its icon sent once per Minecraft session (a new mcNonce
-- starts over): materials/entities/<class>.png (HL2 weapons and most workshop SWEPs have one), else
-- the SWEP's own weapon selection drawing. Drawn into a 64 x 64 render target and read back, one
-- per frame. A class with neither keeps Minecraft's category sprite.
HC.ICON_SIZE = 64
HC.icons = HC.icons or { sent = {}, failed = {}, queue = {}, nonce = nil }

-- Where a class's icon comes from: "png", "swep" or nil. exists(path) -> bool; swep: its table.
function HC.IconSource(class, exists, swep)
	if exists("materials/entities/" .. class .. ".png") then return "png" end
	if swep and (swep.DrawWeaponSelection or swep.WepSelectIcon) then return "swep" end
	return nil
end

-- Which owned classes still need an icon (pure). classes: list; ic: HC.icons.
function HC.IconsToSend(classes, ic, hash)
	local out = {}
	for _, c in ipairs(classes) do
		if c ~= HS.HANDS then
			local h = hash(c)
			if not ic.sent[h] and not ic.failed[h] then out[#out + 1] = c end
		end
	end
	return out
end

-- w x h RGBA8 bytes from read(x, y) -> r, g, b, a (a nil: opaque), top row first.
function HC.PackPixels(read, w, h)
	local parts = {}
	local ch = string.char
	for y = 0, h - 1 do
		for x = 0, w - 1 do
			local r, g, b, a = read(x, y)
			parts[#parts + 1] = ch(r or 0, g or 0, b or 0, a or 255)
		end
	end
	return table.concat(parts)
end

-- True when every pixel's alpha is 0 (a capture that drew nothing).
function HC.AllTransparent(rgba)
	for i = 4, #rgba, 4 do
		if rgba:byte(i) ~= 0 then return false end
	end
	return true
end

local iconRT, iconMats = nil, {}
local function captureIcon(class, source)
	local S = HC.ICON_SIZE
	iconRT = iconRT or GetRenderTargetEx("gmodcraft_weapon_icon", S, S, RT_SIZE_LITERAL, MATERIAL_RT_DEPTH_NONE, 0, 0, IMAGE_FORMAT_RGBA8888)
	render.PushRenderTarget(iconRT)
	render.OverrideAlphaWriteEnable(true, true)
	render.Clear(0, 0, 0, 0, true, true)
	cam.Start2D()
	-- Straight alpha into the cleared RT (colour blended by alpha, alpha accumulated), so the read-back
	-- pixels are what Minecraft expects: no dark premultiplied fringes.
	render.OverrideBlend(true, BLEND_SRC_ALPHA, BLEND_ONE_MINUS_SRC_ALPHA, BLENDFUNC_ADD, BLEND_ONE, BLEND_ONE_MINUS_SRC_ALPHA, BLENDFUNC_ADD)
	local ok = pcall(function()
		if source == "png" then
			local m = iconMats[class] or Material("entities/" .. class .. ".png", "smooth")
			iconMats[class] = m
			surface.SetMaterial(m)
			surface.SetDrawColor(255, 255, 255, 255)
			surface.DrawTexturedRect(0, 0, S, S)
		else
			local wep = LocalPlayer():GetWeapon(class)
			if IsValid(wep) and wep.DrawWeaponSelection then wep:DrawWeaponSelection(0, 0, S, S, 255) end
		end
	end)
	render.OverrideBlend(false)
	cam.End2D()
	render.CapturePixels()
	local data = ok and HC.PackPixels(function(x, y) return render.ReadPixel(x, y) end, S, S) or nil
	if data and HC.AllTransparent(data) then data = nil end  -- nothing drawn: keep the category sprite
	render.OverrideAlphaWriteEnable(false)
	render.PopRenderTarget()
	return data
end

hook.Add("PostRender", "gmodcraft_hybrid_icons", function()
	local ply = LocalPlayer()
	if not (CL and CL.mcAlive and gmodcraft.SendWeaponIcon and HC.Hybrid(ply)) then return end
	local ic = HC.icons
	if ic.nonce ~= CL.mcNonce then ic.nonce, ic.sent, ic.failed, ic.queue = CL.mcNonce, {}, {}, {} end
	if #ic.queue == 0 then
		if RealTime() - (ic.scanAt or 0) < 1 then return end
		ic.scanAt = RealTime()
		local classes = {}
		for _, w in ipairs(ply:GetWeapons()) do classes[#classes + 1] = w:GetClass() end
		ic.queue = HC.IconsToSend(classes, ic, HS.Hash)
		return
	end
	local class = table.remove(ic.queue, 1)
	local h = HS.Hash(class)
	local wep = ply:GetWeapon(class)
	local source = HC.IconSource(class, function(p) return file.Exists(p, "GAME") end, IsValid(wep) and wep or nil)
	if not source then
		ic.failed[h] = true
		return
	end
	local data = captureIcon(class, source)
	if not data then
		ic.failed[h] = true
		return
	end
	local ok = gmodcraft.SendWeaponIcon(h, HC.ICON_SIZE, HC.ICON_SIZE, data)
	if ok then
		ic.sent[h] = true
		gmodcraft.Log("hybrid", "icon for %s (%s) sent", class, source)
	end  -- else (ring full): it comes up again in a second
end)
