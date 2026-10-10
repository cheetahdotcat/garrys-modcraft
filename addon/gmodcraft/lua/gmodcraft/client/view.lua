-- The camera and what's drawn for an MC player (docs/DESIGN.md sections 4.4, 4.5, 7):
--  * Minecraft's 20 Hz physics ticks interpolated on GMod's own frame clock, the way Minecraft's
--    renderer does with partial ticks (ported from SkyCraft's Game.cpp: a short tick history, a
--    render delay that follows how late ticks arrive, so it never extrapolates);
--  * CalcView: Minecraft's eye with its walk bob, FOV and F5 camera;
--  * the HUD/hand overlay over the HUD, premultiplied (D-007);
--  * GMod's own player model, viewmodel and HUD elements hidden.

local V = gmodcraft.view or {}
gmodcraft.view = V
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local CL = gmodcraft.clientLink
local band = bit.band

-- ---- tick interpolation (Game.cpp PerFrame) ----------------------------------------------------
local hist = {}          -- { at = ms, slots = n, s = { tick fields } }
local tickDue = {}       -- how late each tick was, ms (last 2 s)
local tickDueNext = 0
local renderDelay = 8.0
local lastFrameMs = 0
local outliers = 0
local lastTeleports = -1
V.stats = V.stats or { frames = 0, late = 0, ticks = 0 }

function V.Reset()
	hist, tickDue, tickDueNext, renderDelay, lastFrameMs, outliers, lastTeleports = {}, {}, 0, 8.0, 0, 0, -1
end

local FIELDS = { "prevX", "prevY", "prevZ", "curX", "curY", "curZ", "eyeO", "eye", "walkO", "walk", "bobO", "bob" }

local function copyTick(M)
	local s = {}
	for _, f in ipairs(FIELDS) do s[f] = M[f] end
	return s
end

local function clamp(v, a, b) return v < a and a or (v > b and b or v) end

-- Returns MC feet x, y, z, eye height, bob phase, bob amount for this frame.
local function sample(M, nowMs)
	if not M.tickAtMs or M.tickAtMs == 0 or not M.tickMs or M.tickMs <= 0 then
		return M.x, M.y, M.z, M.eyeHeight, M.bobPhase, M.bobAmount
	end
	local period = M.tickMs
	if M.teleportCount ~= lastTeleports then
		-- The MC server moved the player: never interpolate across a teleport.
		hist, lastTeleports = {}, M.teleportCount
	end
	local last = hist[#hist]
	if not last or last.s.tickAtMs ~= M.tickAtMs then
		if last and M.tickAtMs < last.s.tickAtMs then hist, last = {}, nil end  -- Minecraft restarted
		local s = copyTick(M)
		s.tickAtMs = M.tickAtMs
		local tick = { s = s, at = M.tickAtMs, slots = 1 }
		if last then
			local n = math.floor((M.tickAtMs - last.at) / period + 0.5)
			local err = M.tickAtMs - (last.at + n * period)
			if n == 0 and last.slots >= 2 then
				-- Minecraft ran two ticks in one frame and we saw both: the first carries the
				-- second's stamp. It belongs a tick earlier.
				last.at = last.at - period
				last.slots = last.slots - 1
				tick.at = last.at + period
			elseif n >= 1 and n <= 10 and math.abs(err) < period * 0.3 then
				tick.at = last.at + n * period + err / 16  -- the rhythm is exact; the stamps are noisy
				tick.slots = n
				outliers = 0
			elseif n <= 10 and outliers + 1 < 3 then
				outliers = outliers + 1
				tick.slots = math.max(n, 1)
				tick.at = last.at + tick.slots * period  -- one odd stamp (a hitch): keep the rhythm
			else
				outliers = 0  -- lost the rhythm (a pause, a new tick rate): start from this stamp
			end
		end
		if lastFrameMs > 0 then
			local dueMs = lastFrameMs - tick.at
			if dueMs < 30 then
				tickDue[tickDueNext % 40 + 1] = dueMs
				tickDueNext = tickDueNext + 1
			end
		end
		hist[#hist + 1] = tick
		if #hist > 8 then table.remove(hist, 1) end
		V.stats.ticks = V.stats.ticks + 1
	end

	-- The render delay follows how late ticks have been over the last 2 s.
	local frameMs = lastFrameMs > 0 and nowMs - lastFrameMs or 0
	lastFrameMs = nowMs
	if #tickDue > 0 then
		local worst = -1e9
		for _, d in ipairs(tickDue) do if d > worst then worst = d end end
		local target = clamp(worst + 1, 4, 30)
		local dt = math.min(frameMs, 100) / 1000
		if target > renderDelay then renderDelay = math.min(target, renderDelay + 20 * dt)
		else renderDelay = math.max(target, renderDelay - 2 * dt) end
	end
	local renderAt = nowMs - renderDelay

	local i = 1
	for k = #hist, 1, -1 do
		if hist[k].at <= renderAt then i = k break end
	end
	local tick, nxt = hist[i], hist[i + 1]
	local s = tick.s
	local ticks = (renderAt - tick.at) / period
	local t = clamp(ticks, 0, 1)
	local fx = s.prevX + (s.curX - s.prevX) * t
	local fy = s.prevY + (s.curY - s.prevY) * t
	local fz = s.prevZ + (s.curZ - s.prevZ) * t
	local eyeH = s.eyeO + (s.eye - s.eyeO) * t
	-- MC's GameRenderer.bobView: phase = -(walkDist + (walkDist - walkDistO) * partial) (Game.cpp:762)
	local phase = -(s.walk + (s.walk - s.walkO) * t)
	local bob = s.bobO + (s.bob - s.bobO) * t
	if ticks > 1 and nxt then
		-- Past this tick's end and the next one we have starts later: Minecraft ran a tick we
		-- never saw. Carry on from this tick's end to the next one's start.
		local n = nxt.s
		local gap = nxt.at - (tick.at + period)
		local u = gap > 0 and clamp((renderAt - (tick.at + period)) / gap, 0, 1) or 1
		fx = s.curX + (n.prevX - s.curX) * u
		fy = s.curY + (n.prevY - s.curY) * u
		fz = s.curZ + (n.prevZ - s.curZ) * u
		eyeH = s.eye + (n.eyeO - s.eye) * u
		local endPhase = -(s.walk + (s.walk - s.walkO))
		phase = endPhase + (-n.walk - endPhase) * u
		bob = s.bob + (n.bobO - s.bob) * u
	elseif ticks > 1 then
		V.stats.late = V.stats.late + 1  -- the next tick hasn't arrived: the player stands still this frame
	end
	V.stats.frames = V.stats.frames + 1
	V.stats.renderDelay = renderDelay
	V.stats.t = ticks
	-- velocity (blocks/s) from the tick we're in
	V.velMc = { (s.curX - s.prevX) * 1000 / period, (s.curY - s.prevY) * 1000 / period, (s.curZ - s.prevZ) * 1000 / period }
	return fx, fy, fz, eyeH, phase, bob
end

-- Called once per frame (PreRender): the pose everything else this frame uses.
local function update()
	V.valid = false
	if not CL.McInWorld() or not C.slot.known then return end
	local M = CL.M
	local fx, fy, fz, eyeH, phase, bob = sample(M, gmodcraft.MonoMs())
	V.feetMc = { fx, fy, fz }
	V.feet = C.FromMc(fx, fy, fz)
	V.eyeHeight = eyeH
	V.bobPhase, V.bobAmount = phase, bob
	local vm = V.velMc or { 0, 0, 0 }
	V.vel = Vector(vm[1] * 40, -vm[3] * 40, vm[2] * 40)
	V.valid = true
end

-- The puppet's pose for the owning client's SetupMove (multiplayer prediction) and its report.
function V.PuppetPose()
	if not V.valid then return nil end
	return V.feet, V.vel
end

-- ---- camera ------------------------------------------------------------------------------------
local cvCamera = CreateClientConVar("gmodcraft_camera", "1", true, false, "Garry's Modcraft: the view follows Minecraft's eye (0: GMod's own camera)")
local zoom, zoomMode = 0, 0
local lastCalcT = 0

hook.Add("CalcView", "gmodcraft_view", function(ply, origin, angles, fov, znear, zfar)
	if not cvCamera:GetBool() or not V.valid or not gmodcraft.IsPuppet(ply) or ply:InVehicle() then return end
	local M = CL.M
	local look = gmodcraft.input.look
	local now = RealTime()
	local dt = math.max(now - lastCalcT, 0)
	lastCalcT = now
	-- Minecraft's walk bob (GameRenderer.bobView), as a camera offset: sway sideways, lift, dip.
	local phase = V.bobPhase * math.pi
	local bob = V.bobAmount
	local side = -math.sin(phase) * bob * 0.5
	local lift = math.abs(math.cos(phase) * bob)
	local bobPitch = math.abs(math.cos(phase - 0.2) * bob) * 5
	local bobRoll = math.sin(phase) * bob * 3
	local yaw = C.YawFromMc(look.yaw)
	local eye = V.feet + Vector(0, 0, V.eyeHeight * 40)
	eye = eye + Angle(0, yaw, 0):Right() * (side * 40) + Vector(0, 0, lift * 40)
	-- F5: behind the eye (1), or in front looking back (2), as far as Minecraft's own zoom
	-- collision let its camera go. Minecraft pulls in at once and eases back out.
	local mode = M.cameraMode or 0
	local mirrored = mode == 2
	local camYaw = mirrored and yaw + 180 or yaw
	local lookPitch = mirrored and -look.pitch or look.pitch
	local detached = mode ~= 0 and (M.cameraDistance or 0) > 0
	if not detached or zoomMode ~= mode or M.cameraDistance < zoom then
		zoom = detached and M.cameraDistance or 0
	else
		zoom = zoom + (M.cameraDistance - zoom) * (1 - math.exp(-dt / 0.2))
	end
	zoomMode = mode
	if detached then
		eye = eye - Angle(lookPitch, camYaw, 0):Forward() * (zoom * 40)
	end
	local view = {
		origin = eye,
		angles = Angle(lookPitch + bobPitch, camYaw, bobRoll),
		fov = (M.fov and M.fov > 1) and C.FovFromMc(M.fov) or fov,
		drawviewer = false,
	}
	V.lastView = view
	return view
end)

-- ---- what GMod draws --------------------------------------------------------------------------
local HIDE = {
	CHudHealth = true, CHudBattery = true, CHudAmmo = true, CHudSecondaryAmmo = true, CHudCrosshair = true,
	CHudWeaponSelection = true, CHudDamageIndicator = true, CHudSuitPower = true, CHudZoom = true, CHudPoisonDamageIndicator = true,
}
local AMMO = { CHudAmmo = true, CHudSecondaryAmmo = true }
hook.Add("HUDShouldDraw", "gmodcraft_view", function(name)
	if HIDE[name] and gmodcraft.IsMcPlayer(LocalPlayer()) then
		-- H2 hybrid mode: GMod's ammo HUD while the held GMod weapon is the active one.
		local HC = gmodcraft.hybridClient
		if AMMO[name] and HC and HC.ActiveHeld(LocalPlayer()) then return end
		return false
	end
end)

-- MC players are drawn by their own Minecraft clients' render scene (P3), never as GMod models.
hook.Add("PrePlayerDraw", "gmodcraft_view", function(ply)
	if gmodcraft.IsMcPlayer(ply) then return true end
end)

hook.Add("PreDrawViewModel", "gmodcraft_view", function(vm, ply, wep)
	local lp = LocalPlayer()
	if gmodcraft.IsMcPlayer(lp) then
		-- H2 hybrid mode: only the held GMod weapon's viewmodel, in first person (client/hybrid.lua).
		local HC = gmodcraft.hybridClient
		if HC and HC.DrawViewModel(lp, wep) then return end
		return true
	end
end)

hook.Add("ShouldDrawLocalPlayer", "gmodcraft_view", function(ply)
	if gmodcraft.IsMcPlayer(ply) then return false end
end)

-- ---- the HUD/hand overlay -----------------------------------------------------------------------
-- The module copies Minecraft's frame into a BGRA texture, sRGB-encoded and premultiplied
-- (D-007); drawn with $linearwrite and OverrideBlend(ONE, ONE_MINUS_SRC_ALPHA).
local cvOverlay = CreateClientConVar("gmodcraft_overlay", "1", true, false, "Garry's Modcraft: draw Minecraft's HUD and hand over GMod")
local mats = {}
V.overlay = V.overlay or {}

local function overlayMaterial(name)
	local m = mats[name]
	if not m then
		m = CreateMaterial("gmodcraft_" .. name:gsub("[^%w]", "_"), "UnlitGeneric", {
			["$basetexture"] = name, ["$translucent"] = 1, ["$vertexcolor"] = 1, ["$ignorez"] = 1, ["$linearwrite"] = 1,
		})
		mats[name] = m
	end
	return m
end

hook.Add("PreRender", "gmodcraft_view", function()
	if gmodcraft.missing or not CL.open then return end
	update()
	local O = V.overlay
	O.material = nil
	local shortcut = gmodcraft.input and gmodcraft.input.ShortcutScreen and gmodcraft.input.ShortcutScreen()  -- H2: MC screen opened from GMod mode
	local hosted = V.OverlayHosted()  -- S1: the spawnmenu's Minecraft tab shows the overlay
	if not CL.McInWorld() or not (hosted or cvOverlay:GetBool() and (gmodcraft.IsMcPlayer(LocalPlayer()) or shortcut)) then return end
	-- Seated (GMod drives, GMod's camera): no Minecraft hand or HUD over the vehicle view.
	if LocalPlayer():InVehicle() and not hosted then return end
	local name, w, h, fresh, flags = gmodcraft.OverlayTake()
	if not name then
		O.error = w
		return
	end
	O.name, O.w, O.h, O.flags, O.error = name, w, h, flags, nil
	if fresh then O.frames = (O.frames or 0) + 1 end
	O.material = overlayMaterial(name)
end)

-- S1: a panel (the spawnmenu's Minecraft tab) draws the overlay itself; the HUD doesn't then.
function V.OverlayHosted()
	local I = gmodcraft.input
	return I ~= nil and I.HostActive ~= nil and I.HostActive()
end

-- Draws the overlay frame into a screen rectangle (premultiplied); false when there is none.
function V.DrawOverlay(x, y, w, h)
	local m = V.overlay.material
	if not m then return false end
	render.OverrideBlend(true, BLEND_ONE, BLEND_ONE_MINUS_SRC_ALPHA, BLENDFUNC_ADD)
	render.SetMaterial(m)
	render.DrawScreenQuadEx(x, y, w, h)
	render.OverrideBlend(false)
	return true
end

hook.Add("HUDPaint", "gmodcraft_view_overlay", function()
	if not V.overlay.material or V.OverlayHosted() then return end
	local LD = gmodcraft.loading  -- L3: no MC HUD over the loading screen
	if LD and LD.m and LD.m.phase == "full" and GetConVar("gmodcraft_loading_screen"):GetBool() then return end
	V.DrawOverlay(0, 0, ScrW(), ScrH())
end)
