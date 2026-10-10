-- S1 (v35): the "Minecraft" spawnmenu tab. It shows Minecraft's own screen (its inventory, opened
-- when the tab comes up) inside the tab: the overlay texture (client/view.lua V.DrawOverlay),
-- scaled to fit, and the tab's mouse (and keys, after a click in it) go to Minecraft the way the
-- MC-screen popup's do (client/input.lua I.HostScreen / I.AttachScreenInput). Hiding the tab or
-- closing the spawnmenu closes the MC screen.
--
-- A weapon icon dragged from the spawnmenu's Weapons tab (hover the Minecraft tab's button to
-- switch to it mid-drag) and dropped onto a hotbar slot asks the server for that weapon's item in
-- that slot (gmodcraft_mc_drop -> server/hybrid.lua HY.GiveToSlot, the same gate as any weapon
-- from Minecraft). Minecraft says which slot is under the cursor (McScreen::hoveredSlot); the drop
-- waits until it reports the drop position.

local S = gmodcraft.mcscreen or {}
gmodcraft.mcscreen = S
local CL = gmodcraft.clientLink
local I = gmodcraft.input
local V = gmodcraft.view
local SDL_E, SDL_ESCAPE = 8, 41
S.DROP_TIMEOUT = 0.6   -- s to wait for Minecraft to report the slot under the drop position
S.CLOSE_GRACE = 0.5    -- s the tab keeps hosting after it hid, until the Esc it sent closed the screen

-- The overlay frame's rectangle inside a w x h panel (aspect kept, centred).
function S.Fit(w, h, ow, oh)
	local scale = math.min(w / ow, h / oh)
	local dw, dh = ow * scale, oh * scale
	return (w - dw) / 2, (h - dh) / 2, dw, dh, scale
end

-- Panel-local position -> overlay pixels (clamped to the frame).
function S.ToOverlay(lx, ly, w, h, ow, oh)
	local dx, dy, _, _, scale = S.Fit(w, h, ow, oh)
	local ox = math.Clamp(math.floor((lx - dx) / scale), 0, ow - 1)
	local oy = math.Clamp(math.floor((ly - dy) / scale), 0, oh - 1)
	return ox, oy
end

local function overlaySize()
	local O = V and V.overlay
	return (O and O.w) or ScrW(), (O and O.h) or ScrH()
end

-- Visible on screen: the panel and every parent (the property sheet hides inactive tabs).
local function shown(p)
	while IsValid(p) do
		if not p:IsVisible() then return false end
		p = p:GetParent()
	end
	return true
end

local function notify(text)
	if notification and notification.AddLegacy then notification.AddLegacy(text, NOTIFY_HINT or 3, 4) end
	gmodcraft.Info("Minecraft tab: %s", text)
end

-- ---- the drop ------------------------------------------------------------------------------------
S.drop = nil  -- { class, ox, oy, at }
local function endDrop()
	S.drop = nil
	if S.host then S.host.holdCursor = nil end  -- the cursor follows the mouse again
end

local function finishDrop(slot)
	local d = S.drop
	endDrop()
	if not slot or slot < 0 then
		notify("Drop the weapon onto a Minecraft hotbar slot")
		return
	end
	if slot > 8 then
		notify("Weapons go into the hotbar (the bottom row)")
		return
	end
	net.Start(gmodcraft.NET.mcDrop)
	net.WriteString(d.class)
	net.WriteUInt(slot, 8)
	net.SendToServer()
	gmodcraft.Log("input", "Minecraft tab: %s dropped onto hotbar slot %d", d.class, slot)
end

local function pollDrop()
	local d = S.drop
	if not d then return end
	local m = gmodcraft.McScreen and gmodcraft.McScreen()
	if m and m.cursorX == d.ox and m.cursorY == d.oy then
		-- the frame after the one that first reported the position: its hoveredSlot is for it
		d.matchFrame = d.matchFrame or m.frame
		if m.frame > d.matchFrame then return finishDrop(m.container and m.hoveredSlot or -1) end
	end
	if RealTime() - d.at > S.DROP_TIMEOUT then
		endDrop()
		notify("Minecraft didn't answer; try again")
	end
end

-- ---- the tab -------------------------------------------------------------------------------------
local function build()
	local root = vgui.Create("DPanel")
	root:Dock(FILL)
	local h = { panel = root }
	S.host = h
	function h.toOverlay(x, y)
		local lx, ly = root:ScreenToLocal(x, y)
		local ow, oh = overlaySize()
		return S.ToOverlay(lx, ly, root:GetWide(), root:GetTall(), ow, oh)
	end
	h.te = I.AttachScreenInput(root, {
		enabled = function() return S.active and CL.McScreenOpen() end,
		onPress = function(self, code)
			self:MouseCapture(true)
			if IsValid(h.te) and not h.te:HasFocus() then h.te:RequestFocus() end  -- keys to Minecraft (the spawnmenu hangs open)
			if S.active and CL.McInWorld() and not CL.McScreenOpen() then
				I.Tap(SDL_E)  -- a click in the tab opens the inventory again
				return true
			end
		end,
		onRelease = function(self) self:MouseCapture(false) end,
	})
	root.Paint = function(self, w, hgt)
		surface.SetDrawColor(18, 18, 18, 235)
		surface.DrawRect(0, 0, w, hgt)
		local ow, oh = overlaySize()
		local dx, dy, dw, dh = S.Fit(w, hgt, ow, oh)
		local sx, sy = self:LocalToScreen(0, 0)
		local drew = CL.McScreenOpen() and V.DrawOverlay(sx + dx, sy + dy, dw, dh)
		local msg
		if not CL.mcAlive then msg = "Minecraft isn't running"
		elseif not CL.McInWorld() then msg = "Minecraft isn't in a world yet"
		elseif not CL.McScreenOpen() then msg = "Click to open the Minecraft inventory"
		elseif not drew then msg = "Waiting for Minecraft's picture..." end
		if msg then draw.SimpleText(msg, "DermaLarge", w / 2, hgt / 2, color_white, TEXT_ALIGN_CENTER, TEXT_ALIGN_CENTER) end
		draw.SimpleText("Drag weapons from the Weapons tab onto a hotbar slot", "DermaDefault", w / 2, hgt - 8, Color(200, 200, 200),
			TEXT_ALIGN_CENTER, TEXT_ALIGN_BOTTOM)
	end
	-- Spawnmenu icons are droppable as "SandboxContentPanel" (ContentContainer's IconList).
	root:Receiver("SandboxContentPanel", function(_, panels, dropped)
		if not dropped then return end
		for _, p in ipairs(panels) do
			local typ = p.GetContentType and p:GetContentType()
			local class = p.GetSpawnName and p:GetSpawnName()
			if typ == "weapon" and isstring(class) and class ~= "" then
				if not (S.active and CL.McScreenOpen()) then
					notify("Open the Minecraft inventory first (click the tab)")
					return
				end
				local ox, oy = h.toOverlay(gui.MousePos())
				I.Push(gmodcraft.K.InCursor, 0, ox, oy)  -- Minecraft hovers the drop position (if it hasn't yet)
				S.drop = { class = class, ox = ox, oy = oy, at = RealTime() }
				h.holdCursor = true  -- until Minecraft reported the slot at exactly this position
				return
			end
		end
		notify("Only weapons can go into the Minecraft hotbar here")
	end)
	return root
end

-- Hosting follows the tab's visibility (switching tabs, closing the spawnmenu).
hook.Add("Think", "gmodcraft_mcscreen", function()
	if gmodcraft.missing or not CL.open then return end
	local h = S.host
	if not h or not IsValid(h.panel) then
		S.active = false
		return
	end
	local vis = IsValid(g_SpawnMenu) and g_SpawnMenu:IsVisible() and shown(h.panel)
	if vis and not S.active then
		S.active, S.closingAt = true, nil
		I.HostScreen(h)
		if CL.McInWorld() and not CL.McScreenOpen() then I.Tap(SDL_E) end
	elseif not vis and S.active then
		S.active = false
		endDrop()
		if IsValid(h.te) and h.te:HasFocus() then h.te:KillFocus() end
		if CL.McScreenOpen() then
			I.Tap(SDL_ESCAPE)
			S.closingAt = RealTime()  -- keep hosting until it is closed: no popup flash in MC mode
		else
			I.UnhostScreen(h)
		end
	end
	if S.closingAt and (not CL.McScreenOpen() or RealTime() - S.closingAt > S.CLOSE_GRACE) then
		S.closingAt = nil
		if not S.active then I.UnhostScreen(h) end
	end
	pollDrop()
end)

if spawnmenu and spawnmenu.AddCreationTab then
	spawnmenu.AddCreationTab("Minecraft", build, "icon16/box.png", 199, "Minecraft's inventory; drag weapons onto its hotbar")
end
