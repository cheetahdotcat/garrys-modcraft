-- Input (docs/DESIGN.md section 5, D-004). While Minecraft drives the player, keys and mouse go
-- to Minecraft over the input ring as SDL scancodes / buttons, except the reserved keys:
--
--   G       GMod use: IN_USE in the usercmd while held (after ClearButtons)
--   Esc     closes an open MC screen; otherwise the GMod menu
--   O       Minecraft's pause/options menu (kInOpenMenu)
--   Q       tap: MC drop (press+release on key-up; Ctrl+tap drops the stack). Hold > 200 ms: spawnmenu
--   C, Tab, Y/U, ~, voice   GMod (context menu, scoreboard, chat, console, voice)
--   F6      switch between MC mode and GMod mode
--   V       (whatever `noclip` is bound to) GMod's noclip, as GMod rules allow it (N1)
--
-- Seated in a GMod vehicle or chair, nothing is captured: GMod drives it with its own binds and
-- mouse (G still works as +use, to get out).
--
-- Mouse look is integrated here with Minecraft's own sensitivity formula and sent as HostState's
-- yaw/pitch (zero added latency for the camera); Minecraft applies it to its player. While an MC
-- screen is open a full-screen invisible popup takes the cursor (kInCursor, overlay pixels) and
-- a hidden text entry takes typed characters (kInText).
--
-- S1 (v35): the spawnmenu's Minecraft tab (client/mcscreen.lua) can host the MC screen instead of
-- the popup (I.HostScreen): the same mouse handlers, text entry and per-frame key/cursor forwarding,
-- with its own overlay mapping; keys only while its text entry has the focus (a click in the tab).

local I = gmodcraft.input or {}
gmodcraft.input = I
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local CL = gmodcraft.clientLink
local band = bit.band
local Log = gmodcraft.Log
gmodcraft.Info("input.lua loaded (look resync debounce v1)")

-- GMod KEY_* -> SDL scancode (USB HID usage), the counterpart of Input.cpp's kDikToSdl.
local KEYMAP = {
	[KEY_0] = 39, [KEY_1] = 30, [KEY_2] = 31, [KEY_3] = 32, [KEY_4] = 33, [KEY_5] = 34, [KEY_6] = 35, [KEY_7] = 36, [KEY_8] = 37, [KEY_9] = 38,
	[KEY_A] = 4, [KEY_B] = 5, [KEY_C] = 6, [KEY_D] = 7, [KEY_E] = 8, [KEY_F] = 9, [KEY_G] = 10, [KEY_H] = 11, [KEY_I] = 12, [KEY_J] = 13,
	[KEY_K] = 14, [KEY_L] = 15, [KEY_M] = 16, [KEY_N] = 17, [KEY_O] = 18, [KEY_P] = 19, [KEY_Q] = 20, [KEY_R] = 21, [KEY_S] = 22, [KEY_T] = 23,
	[KEY_U] = 24, [KEY_V] = 25, [KEY_W] = 26, [KEY_X] = 27, [KEY_Y] = 28, [KEY_Z] = 29,
	[KEY_PAD_0] = 98, [KEY_PAD_1] = 89, [KEY_PAD_2] = 90, [KEY_PAD_3] = 91, [KEY_PAD_4] = 92, [KEY_PAD_5] = 93, [KEY_PAD_6] = 94,
	[KEY_PAD_7] = 95, [KEY_PAD_8] = 96, [KEY_PAD_9] = 97, [KEY_PAD_DIVIDE] = 84, [KEY_PAD_MULTIPLY] = 85, [KEY_PAD_MINUS] = 86,
	[KEY_PAD_PLUS] = 87, [KEY_PAD_ENTER] = 88, [KEY_PAD_DECIMAL] = 99,
	[KEY_LBRACKET] = 47, [KEY_RBRACKET] = 48, [KEY_SEMICOLON] = 51, [KEY_APOSTROPHE] = 52, [KEY_BACKQUOTE] = 53, [KEY_COMMA] = 54,
	[KEY_PERIOD] = 55, [KEY_SLASH] = 56, [KEY_BACKSLASH] = 49, [KEY_MINUS] = 45, [KEY_EQUAL] = 46, [KEY_ENTER] = 40, [KEY_SPACE] = 44,
	[KEY_BACKSPACE] = 42, [KEY_TAB] = 43, [KEY_CAPSLOCK] = 57, [KEY_NUMLOCK] = 83, [KEY_ESCAPE] = 41, [KEY_SCROLLLOCK] = 71,
	[KEY_INSERT] = 73, [KEY_DELETE] = 76, [KEY_HOME] = 74, [KEY_END] = 77, [KEY_PAGEUP] = 75, [KEY_PAGEDOWN] = 78, [KEY_BREAK] = 72,
	[KEY_LSHIFT] = 225, [KEY_RSHIFT] = 229, [KEY_LALT] = 226, [KEY_RALT] = 230, [KEY_LCONTROL] = 224, [KEY_RCONTROL] = 228,
	[KEY_LWIN] = 227, [KEY_RWIN] = 231, [KEY_APP] = 101, [KEY_UP] = 82, [KEY_LEFT] = 80, [KEY_DOWN] = 81, [KEY_RIGHT] = 79,
	[KEY_F1] = 58, [KEY_F2] = 59, [KEY_F3] = 60, [KEY_F4] = 61, [KEY_F5] = 62, [KEY_F6] = 63, [KEY_F7] = 64, [KEY_F8] = 65,
	[KEY_F9] = 66, [KEY_F10] = 67, [KEY_F11] = 68, [KEY_F12] = 69,
}
I.KEYMAP = KEYMAP
local MOUSEMAP = { [MOUSE_LEFT] = 1, [MOUSE_MIDDLE] = 2, [MOUSE_RIGHT] = 3, [MOUSE_4] = 4, [MOUSE_5] = 5 }
local SDL_Q, SDL_ESCAPE = 20, 41

-- Keys GMod keeps during gameplay (never sent to Minecraft as keys).
local function reservedKeys()
	local r = { [KEY_G] = true, [KEY_Q] = true, [KEY_C] = true, [KEY_TAB] = true, [KEY_Y] = true, [KEY_U] = true,
		[KEY_BACKQUOTE] = true, [KEY_F6] = true, [KEY_ESCAPE] = true, [KEY_O] = true }
	for _, bind in ipairs({ "+voicerecord", "noclip" }) do
		local key = input.LookupBinding(bind)
		local code = key and input.GetKeyCode(key)
		if code and code > 0 then r[code] = true end
	end
	return r
end
local RESERVED = reservedKeys()
I.RESERVED = RESERVED

-- Binds GMod still gets while Minecraft drives (PlayerBindPress lets them through).
local ALLOWED_BINDS = {
	["+showscores"] = true, ["-showscores"] = true, ["+menu_context"] = true, ["-menu_context"] = true, messagemode = true,
	messagemode2 = true, toggleconsole = true, ["+voicerecord"] = true, ["-voicerecord"] = true, cancelselect = true,
	noclip = true,  -- N1: GMod's noclip in MC mode too (PlayerNoClip / gmodcraft_noclip_mc decide)
}

-- ---- state -----------------------------------------------------------------------------------------
I.look = I.look or { yaw = 0, pitch = 0, init = false }   -- authoritative look, MC degrees
local look = I.look
local down = {}          -- KEY_* / MOUSE_* -> true while we told Minecraft it's held
local capturing = false
local popup = nil
local q = { down = false, at = 0, menu = false }
local f6Down = false
local swallow = {}
local lastCursor = { -1, -1 }
I.counts = I.counts or { events = 0, dropped = 0 }
I.log = I.log or {}

local cvLook = CreateClientConVar("gmodcraft_look_scale", "1", true, false, "Garry's Modcraft: extra factor on mouse look (1 = Minecraft's own feel)")

local NAMES = { [K.InKey or 1] = "key", [K.InMouseButton or 2] = "mouse", [K.InScroll or 3] = "scroll", [K.InCursor or 4] = "cursor",
	[K.InText or 5] = "text", [K.InReleaseAll or 6] = "releaseAll", [K.InOpenMenu or 8] = "openMenu" }

local function push(typ, code, a, b)
	local ok = gmodcraft.PushInput(typ, code or 0, a or 0, b or 0, 0)
	I.counts.events = I.counts.events + 1
	if not ok then I.counts.dropped = I.counts.dropped + 1 end
	if typ ~= K.InCursor then
		local line = string.format("%.2f %s %d %d %d", RealTime(), NAMES[typ] or tostring(typ), code or 0, a or 0, b or 0)
		I.log[#I.log + 1] = line
		if #I.log > 40 then table.remove(I.log, 1) end
		Log("input", "%s", line)
	end
	return ok
end
I.Push = push

-- A key press and its release a moment later: Minecraft drops a press and release that arrive in
-- the same frame (seen with Esc on its pause screen).
function I.Tap(sdl)
	push(K.InKey, sdl, 1)
	timer.Simple(0.06, function() push(K.InKey, sdl, 0) end)
end

function I.ReleaseAll()
	if gmodcraft.PushInput and CL.open then push(K.InReleaseAll, 0) end
	down = {}
end

function I.PopupOpen()
	return IsValid(popup)
end

-- Test hook: pretend a key / button changed (same path as polling). Used by client/test.lua.
I.inject = I.inject or {}

local function isDown(code)
	local inj = I.inject[code]
	if inj ~= nil then return inj end
	if code >= MOUSE_FIRST and code <= MOUSE_LAST then return input.IsMouseDown(code) end
	return input.IsKeyDown(code)
end
I.IsDown = isDown

-- down[code] holds the scancode / button Minecraft was told is pressed (the route can change while
-- the key is held, e.g. the inventory key sent as E): the release goes out with that one.
local function edge(code, now, sdlType, sdl)
	local was = down[code] ~= nil
	if now == was then return end
	if now then
		down[code] = sdl
		push(sdlType, sdl, 1)
	else
		local sent = down[code]
		down[code] = nil
		push(sdlType, type(sent) == "number" and sent or sdl, 0)
	end
end

-- ---- the MC screen popup ---------------------------------------------------------------------------
local function cursorToOverlay(x, y)
	local O = gmodcraft.view and gmodcraft.view.overlay
	local ow, oh = (O and O.w) or ScrW(), (O and O.h) or ScrH()
	return math.floor(x * ow / ScrW()), math.floor(y * oh / ScrH())
end

-- The MC screen's mouse buttons, wheel and typed characters on a panel (the popup, or the S1 tab):
-- returns the hidden text entry the characters arrive through (it keeps none of them).
-- opts (the tab): onPress(panel, code) runs first on a button press and returns true to keep it
-- from Minecraft; onRelease(panel, code) after a release; enabled() false = nothing goes to Minecraft.
function I.AttachScreenInput(pnl, opts)
	opts = opts or {}
	pnl.OnMousePressed = function(self, code)
		if opts.onPress and opts.onPress(self, code) then return end
		if opts.enabled and not opts.enabled() then return end
		local b = MOUSEMAP[code]
		if b and not down[code] then down[code] = b push(K.InMouseButton, b, 1) end
	end
	pnl.OnMouseReleased = function(self, code)
		local b = MOUSEMAP[code]
		if b and down[code] then down[code] = nil push(K.InMouseButton, b, 0) end
		if opts.onRelease then opts.onRelease(self, code) end
	end
	pnl.OnMouseWheeled = function(_, delta)
		if opts.enabled and not opts.enabled() then return end
		push(K.InScroll, 0, math.floor(delta * 120 + 0.5))
		return true
	end
	local te = vgui.Create("DTextEntry", pnl)
	te:SetPos(0, 0)
	te:SetSize(1, 1)
	te:SetAlpha(0)
	te:SetDrawLanguageID(false)
	te.AllowInput = function(_, ch)
		local ok, cp = pcall(utf8.codepoint, ch)
		if ok and cp then push(K.InText, 0, cp) end
		return true
	end
	return te
end

local function openPopup()
	popup = vgui.Create("EditablePanel")
	popup:SetPos(0, 0)
	popup:SetSize(ScrW(), ScrH())
	popup:MakePopup()
	popup:SetKeyboardInputEnabled(true)
	popup:SetMouseInputEnabled(true)
	popup.Paint = function() end
	local te = I.AttachScreenInput(popup)
	te:RequestFocus()
	popup.te = te
	-- Minecraft centres its cursor when a screen opens.
	input.SetCursorPos(ScrW() / 2, ScrH() / 2)
	local x, y = cursorToOverlay(ScrW() / 2, ScrH() / 2)
	push(K.InCursor, 0, x, y)
	lastCursor[1], lastCursor[2] = ScrW() / 2, ScrH() / 2
	Log("input", "MC screen open: popup up")
end

local function closePopup()
	if IsValid(popup) then popup:Remove() end
	popup = nil
	I.popupClosedAt = RealTime()
	Log("input", "MC screen closed: popup down")
end

-- ---- S1: the MC screen hosted by a panel (the spawnmenu's Minecraft tab) ---------------------------
-- h = { panel, te, toOverlay(x, y) -> overlay px, q = true: Q stays GMod's (the spawnmenu key) }.
local host = nil
function I.HostScreen(h)
	if host == h then return end
	host = h
	-- keys held when the tab came up (Q for the spawnmenu) stay GMod's until released
	swallow = {}
	for code in pairs(KEYMAP) do if isDown(code) then swallow[code] = true end end
	lastCursor[1], lastCursor[2] = -1, -1
	Log("input", "MC screen hosted by a panel")
end
function I.UnhostScreen(h)
	if host ~= h then return end
	host = nil
	for code, sdl in pairs(down) do
		if code >= MOUSE_FIRST and code <= MOUSE_LAST then push(K.InMouseButton, sdl, 0) else push(K.InKey, sdl, 0) end
	end
	down = {}
	Log("input", "MC screen no longer hosted")
end
local function hostActive()
	return host ~= nil and IsValid(host.panel)
end
I.HostActive = hostActive
-- The popup or a hosting panel shows the MC screen (no look, no gameplay keys).
local function screenUp()
	return IsValid(popup) or hostActive()
end

-- One frame of an open MC screen: keys (when keys is true) and the cursor (unless holdCursor: the
-- tab waits for Minecraft to report the slot under a drop position).
local function forwardScreen(toOverlay, keys, skipQ, holdCursor)
	for code, sdl in pairs(KEYMAP) do
		if swallow[code] and not isDown(code) then swallow[code] = nil end
		if code ~= KEY_ESCAPE and code ~= KEY_F6 and not swallow[code] and not (skipQ and code == KEY_Q) then
			edge(code, keys and isDown(code), K.InKey, sdl)
		end
	end
	if holdCursor then return end
	local x, y = gui.MousePos()
	if x ~= lastCursor[1] or y ~= lastCursor[2] then
		lastCursor[1], lastCursor[2] = x, y
		local ox, oy = toOverlay(x, y)
		push(K.InCursor, 0, ox, oy)
	end
end

-- Esc with an MC screen open closes the screen instead of opening GMod's menu.
hook.Add("OnPauseMenuShow", "gmodcraft_input", function()
	if screenUp() and CL.McScreenOpen() then
		I.Tap(SDL_ESCAPE)
		return false
	end
end)

-- ---- H2 hybrid routing ---------------------------------------------------------------------------
-- The route of a key this frame (client/hybrid.lua HC.Route). Context E is decided when E goes
-- down (what the crosshair is on then) and kept until it is released.
local eLatch = nil
function I.RouteCtx()
	local HC = gmodcraft.hybridClient
	if not HC then return nil end
	local ply = LocalPlayer()
	local held = HC.Hybrid(ply) and HC.HeldHash() ~= 0
	return { held = held, useMode = HC.cvUseMode:GetInt(), reloadKey = HC.cvReloadKey:GetInt(), invKey = HC.cvInvKey:GetInt() }
end

function I.RouteOf(code)
	local HC = gmodcraft.hybridClient
	local ctx = I.routeCtx
	if not HC or not ctx then return "mc" end
	if code == KEY_E then
		if not isDown(KEY_E) then eLatch = nil
		elseif not eLatch then
			ctx.eTarget = ctx.useMode == 1 and HC.UseTarget(LocalPlayer()) ~= nil
			eLatch = HC.Route(KEY_E, ctx)
		end
		return eLatch or HC.Route(KEY_E, ctx)
	end
	return HC.Route(code, ctx)
end

-- H3 physgun state this frame (client/hybrid.lua HC.Physgun): rotate / snap / wheel to GMod.
local NO_PHYS = { rotate = false, snap = false, wheelToGmod = false }
function I.PhysgunState()
	local HC = gmodcraft.hybridClient
	local ctx = I.routeCtx
	if not HC or not ctx or not ctx.held or screenUp() then return NO_PHYS end
	local w = LocalPlayer():GetActiveWeapon()
	return HC.Physgun({ active = IsValid(w) and w:GetClass() or nil, lmb = isDown(MOUSE_LEFT), use = isDown(KEY_G),
		holding = LocalPlayer():GetNW2Bool("gmodcraft_physgun_holding", false),
		shift = isDown(KEY_LSHIFT) or isDown(KEY_RSHIFT) })
end

-- GMod mode -> Minecraft shortcuts (gmodcraft_gmod_mc_inventory_key / _chat_key): the screen they
-- open is captured like MC mode's until it closes.
local shortcut = { at = 0, open = false, keys = {} }
function I.ShortcutScreen()
	return shortcut.open
end
local SDL_E, SDL_T = 8, 23
local function pollShortcuts(ply)
	local HC = gmodcraft.hybridClient
	if not HC or not CL.mcAlive or not CL.McInWorld() or gmodcraft.IsMcPlayer(ply) or not IsValid(ply) or ply:InVehicle() then
		shortcut.open = false
		return
	end
	if shortcut.open then
		if CL.McScreenOpen() then shortcut.seen = true
		elseif shortcut.seen or RealTime() - shortcut.at > 1.5 then shortcut.open, shortcut.seen = false, false end
		return
	end
	if vgui.GetKeyboardFocus() or gui.IsGameUIVisible() or vgui.CursorVisible() then return end
	for _, def in ipairs({ { HC.cvShortInv, SDL_E }, { HC.cvShortChat, SDL_T } }) do
		local code = def[1]:GetInt()
		if code > 0 then
			local d = isDown(code)
			if d and not shortcut.keys[code] then
				shortcut.open, shortcut.seen, shortcut.at = true, false, RealTime()
				I.Tap(def[2])
			end
			shortcut.keys[code] = d
		end
	end
end

-- ---- every frame ---------------------------------------------------------------------------------
local function wantCapture(ply)
	if hostActive() and CL.mcAlive and IsValid(ply) and CL.McInWorld() then return true end  -- S1: the tab hosts the screen
	if shortcut.open and CL.mcAlive and IsValid(ply) and CL.McInWorld() and CL.McScreenOpen() then return true end
	return CL.mcAlive and IsValid(ply) and gmodcraft.IsMcPlayer(ply) and CL.McInWorld() and ply:Alive() and not ply:InVehicle()
end

local function pollQ()
	local qd = isDown(KEY_Q)
	if qd and not q.down then
		q.down, q.at, q.menu = true, RealTime(), false
	elseif qd and q.down and not q.menu and RealTime() - q.at > 0.2 then
		q.menu = true
		hook.Run("OnSpawnMenuOpen")
		Log("input", "Q held: spawnmenu")
	elseif not qd and q.down then
		q.down = false
		if q.menu then
			hook.Run("OnSpawnMenuClose")
		elseif capturing and not hostActive() then  -- S1: Q in the tab is the spawnmenu's, never an MC drop
			I.Tap(SDL_Q)
		end
	end
end

hook.Add("Think", "gmodcraft_input", function()
	if gmodcraft.missing or not CL.open then return end
	local ply = LocalPlayer()
	-- While GMod drives (not puppeted), the look follows GMod's own view, so Minecraft faces the
	-- same way when it takes over.
	-- Only once GMod has really been driving for a moment: a puppet flag that drops for a frame or
	-- two must not snap the look back to GMod's (stale) view angles.
	local puppet = gmodcraft.IsPuppet(ply)
	if puppet then
		if I.notPuppetSince then
			I.puppetFlips = (I.puppetFlips or 0) + 1
			if RealTime() - (I.flipWarnAt or 0) > 10 and I.puppetFlips >= 5 then
				I.flipWarnAt = RealTime()
				gmodcraft.Info("puppet flag flickered %d times (look resync suppressed)", I.puppetFlips)
				I.puppetFlips = 0
			end
		end
		I.notPuppetSince = nil
	elseif IsValid(ply) then
		I.notPuppetSince = I.notPuppetSince or RealTime()
		if RealTime() - I.notPuppetSince > 0.3 then
			local a = ply:EyeAngles()
			look.yaw, look.pitch, look.init = C.YawToMc(a.y), math.Clamp(a.p, -90, 90), true
		end
	end

	-- F6: MC mode <-> GMod mode (whenever no text field has focus). Also while no Minecraft is
	-- attached: the player is in GMod mode then anyway, and F6 picks the mode it comes back in.
	local f6 = isDown(KEY_F6)
	local focus = vgui.GetKeyboardFocus()
	local typing = IsValid(focus) and not (IsValid(popup) and focus == popup.te)
	if f6 and not f6Down and not gui.IsGameUIVisible() and not typing then
		net.Start(gmodcraft.NET.mode)
		net.SendToServer()
		Log("input", "F6: mode switch requested")
	end
	f6Down = f6

	I.routeCtx = I.RouteCtx()  -- H2: this frame's routing context (held weapon, use mode, keys)
	if not capturing then pollShortcuts(ply) end
	local want = wantCapture(ply)
	-- I1 (client/capture.lua): capture needs GMod's window focus; the popup never opens unfocused.
	-- The removed popup leaves the cursor visible for a frame or two: that isn't a GMod menu
	-- (treating it as one would release and re-press held keys, e.g. re-open the inventory).
	local focused = system.HasFocus()
	local hosted = hostActive()
	local step = gmodcraft.capture.Step({ want = want, mcScreen = want and CL.McScreenOpen(), focused = focused, wasFocused = I.focused,
		menu = CL.MenuOpen(), popupOpen = IsValid(popup), popupClosedAgo = RealTime() - (I.popupClosedAt or 0), hosted = hosted })
	I.focused = focused
	if step.focus then
		Log("input", "focus %s (want %s, linked %s, McState %s, in world %s, MC mode %s, puppet %s, screen %s, capture %s, cursor %s)", step.focus,
			tostring(want), tostring(CL.mcAlive), tostring(CL.haveMc), tostring(CL.McInWorld()), tostring(gmodcraft.IsMcPlayer(ply)),
			tostring(gmodcraft.IsPuppet(ply)), tostring(step.screen), tostring(capturing), tostring(vgui.CursorVisible()))
		if step.dropMouse then I.dropMouse = step.dropMouse end
	end
	local screen = step.screen
	if step.openPopup then
		-- A reserved key still held from gameplay (O that opened the pause menu, G, Q...) stays
		-- GMod's until released: Minecraft would get a stray press inside its screen.
		swallow = {}
		for code in pairs(RESERVED) do if isDown(code) then swallow[code] = true end end
		openPopup()
	elseif step.deferred and not I.popupDeferred then
		Log("input", "MC screen open while GMod is unfocused: popup (and its cursor warp) waits for the focus")
	end
	I.popupDeferred = step.deferred
	if step.closePopup then closePopup() end

	-- S1: a GMod-mode player's spawnmenu is GMod's own (+menu); only MC mode's Q-hold is ours.
	if want and not (hosted and not gmodcraft.IsMcPlayer(ply)) then pollQ() elseif q.down then q.down = false if q.menu then hook.Run("OnSpawnMenuClose") end end
	local active = step.active
	if active ~= capturing then
		capturing = active
		if not active then I.dropMouse = 0 end  -- drops belong to the capture they came with
		I.ReleaseAll()
		Log("input", "capture %s (want %s, focus %s, screen %s, gameUI %s, console %s, cursor %s, popup %s)", active and "on" or "off",
			tostring(want), tostring(focused), tostring(screen), tostring(gui.IsGameUIVisible()), tostring(gui.IsConsoleVisible()),
			tostring(vgui.CursorVisible()), tostring(IsValid(popup)))
	end
	-- Once a second while logging input: the look state, for diagnosing mouse-look problems.
	if gmodcraft.LogOn("input") and RealTime() - (I.lookLogAt or 0) > 1 then
		I.lookLogAt = RealTime()
		local ls, ea = I.lookStats, ply:EyeAngles()
		Log("input", "look yaw %.1f pitch %.1f | eye yaw %.1f pitch %.1f | puppet %s capture %s | mouse calls %d sum %.0f/%.0f last %s/%s cmd %s/%s",
			look.yaw, look.pitch, ea.y, ea.p, tostring(gmodcraft.IsPuppet(ply)), tostring(capturing), ls.calls, ls.sumX, ls.sumY,
			tostring(ls.lastX), tostring(ls.lastY), tostring(ls.cmdX), tostring(ls.cmdY))
	end
	if not active then return end

	if screen and IsValid(popup) then
		-- Everything is Minecraft's while its screen is up (typing, Esc via OnPauseMenuShow).
		forwardScreen(cursorToOverlay, true, false)
		if IsValid(popup.te) and not popup.te:HasFocus() then popup.te:RequestFocus() end
		return
	end
	if screen and hosted then
		-- S1: keys only while the tab's text entry has the focus (after a click in it).
		forwardScreen(host.toOverlay, IsValid(host.te) and host.te:HasFocus(), true, host.holdCursor)
		return
	end
	if hosted then return end  -- the tab without an MC screen: nothing goes to Minecraft

	-- H2 hybrid mode: a held GMod weapon owns the mouse buttons and the reload key; the use mode
	-- decides E (I.RouteOf). A key routed to GMod that Minecraft still holds is released first.
	for code, sdl in pairs(KEYMAP) do
		if RESERVED[code] then
			if down[code] then edge(code, false, K.InKey, sdl) end  -- held when the screen closed
		else
			local r = I.RouteOf(code)
			if r == "mc" then edge(code, isDown(code), K.InKey, sdl)
			elseif r == "mc_e" then edge(code, isDown(code), K.InKey, KEYMAP[KEY_E])
			elseif down[code] then edge(code, false, K.InKey, sdl) end
		end
	end
	for code, b in pairs(MOUSEMAP) do
		if I.RouteOf(code) == "mc" then edge(code, isDown(code), K.InMouseButton, b)
		elseif down[code] then edge(code, false, K.InMouseButton, b) end
	end
	local o = isDown(KEY_O)
	if o and not I.oDown then
		I.ReleaseAll()
		push(K.InOpenMenu, 0)
	end
	I.oDown = o
	-- Test injection of raw mouse counts (InputMouseApply only runs while GMod has focus).
	if I.injectLook then
		I.ApplyLook(I.injectLook[1], I.injectLook[2])
		I.injectLook = nil
	end
end)

-- ---- usercmd ---------------------------------------------------------------------------------
hook.Add("CreateMove", "gmodcraft_input", function(cmd)
	if not capturing then
		-- Seated MC player: G is +use here too (gets out of the seat), on top of GMod's own binds.
		local ply = LocalPlayer()
		if IsValid(ply) and ply:InVehicle() and gmodcraft.IsMcPlayer(ply) and not vgui.GetKeyboardFocus() and not gui.IsGameUIVisible()
			and not gui.IsConsoleVisible() then
			if isDown(KEY_G) then cmd:SetButtons(bit.bor(cmd:GetButtons(), IN_USE)) end
			-- H2 toggle gmodcraft_vehicle_controls 1: Minecraft's default movement keys drive the seat.
			local HC = gmodcraft.hybridClient
			if HC and HC.cvVehicle:GetInt() == 1 then
				local b, fwd, side = HC.VehicleInput(isDown)
				cmd:SetButtons(bit.bor(cmd:GetButtons(), b))
				if fwd ~= 0 then cmd:SetForwardMove(fwd) end
				if side ~= 0 then cmd:SetSideMove(side) end
			end
		end
		return
	end
	if screenUp() then return end
	local wheel = cmd:GetMouseWheel()
	local phys = I.PhysgunState()
	-- H3: the wheel pushes/pulls a physgun-held object (it stays in the usercmd); else Minecraft's hotbar.
	if wheel ~= 0 and cmd:CommandNumber() ~= 0 and not phys.wheelToGmod then push(K.InScroll, 0, wheel * 120) end
	cmd:ClearMovement()
	-- H3: the physgun rotation's mouse counts (InputMouseApply) survive the clear.
	if phys.rotate and I.rotateMouse then cmd:SetMouseX(I.rotateMouse[1]) cmd:SetMouseY(I.rotateMouse[2]) end
	I.rotateMouse = nil
	cmd:ClearButtons()
	local buttons = isDown(KEY_G) and IN_USE or 0
	-- H2 hybrid mode: the held GMod weapon is selected and gets the buttons GMod owns this frame.
	local HC = gmodcraft.hybridClient
	if HC and I.routeCtx then
		buttons = bit.bor(buttons, HC.Buttons(isDown, I.RouteOf, I.routeCtx))
		if phys.snap then buttons = bit.bor(buttons, IN_SPEED) end  -- H3: Shift snaps the physgun rotation
		HC.Select(cmd, LocalPlayer())
	end
	cmd:SetButtons(buttons)
	-- Only while Minecraft drives: during a teleport GMod may turn the player (SetEyeAngles), and
	-- the look follows GMod until the puppet resumes (see Think).
	if gmodcraft.IsPuppet(LocalPlayer()) then cmd:SetViewAngles(Angle(look.pitch, C.YawFromMc(look.yaw), 0)) end
end)

-- Minecraft's mouse look: degrees per count = (s * 0.6 + 0.2)^3 * 8 * 0.15, s = its sensitivity
-- option (McState.sensitivity). GMod hands us its own sensitivity-scaled delta; undo that first.
I.lookStats = I.lookStats or { calls = 0, sumX = 0, sumY = 0 }

-- Turns the look by raw mouse counts, the way Minecraft's MouseHandler.turnPlayer does.
function I.ApplyLook(dx, dy)
	local s = (CL.M.sensitivity and CL.M.sensitivity > 0) and CL.M.sensitivity or 0.5
	s = s * 0.6 + 0.2
	local f = s * s * s * 8 * 0.15 * cvLook:GetFloat()
	look.yaw = (look.yaw + dx * f) % 360
	look.pitch = math.Clamp(look.pitch + dy * f, -90, 90)
end
hook.Add("InputMouseApply", "gmodcraft_input", function(cmd, x, y, ang)
	-- I1: GMod unfocused: no mouse look at all for an MC player (neither Minecraft's nor GMod's own,
	-- which would turn the usercmd's view for a frame before capture catches up).
	if not system.HasFocus() and gmodcraft.IsMcPlayer(LocalPlayer()) then return true end
	-- Focus just came back: drop the first deltas (the engine's re-centring jump); used up every
	-- frame, so none wait for a later screen close or F6 (capture.lua MouseFrame).
	local dropped
	dropped, I.dropMouse = gmodcraft.capture.MouseFrame(I.dropMouse, capturing, screenUp())
	if dropped then
		if x ~= 0 or y ~= 0 then Log("input", "mouse delta %d/%d dropped (focus just came back)", x, y) end
		return true
	end
	if not capturing or screenUp() then return end
	local gs = GetConVar("sensitivity"):GetFloat()
	if gs <= 0 then gs = 1 end
	local dx, dy = x / gs, y / gs
	local ls = I.lookStats
	ls.calls, ls.sumX, ls.sumY, ls.lastX, ls.lastY = ls.calls + 1, ls.sumX + dx, ls.sumY + dy, x, y
	ls.cmdX, ls.cmdY = cmd:GetMouseX(), cmd:GetMouseY()
	if not gmodcraft.IsPuppet(LocalPlayer()) then return true end  -- frozen while a teleport is pending
	if I.PhysgunState().rotate then
		-- H3: G + LMB with the physgun rotates the held object: the look stays where it is and the
		-- mouse counts go to the physgun through the usercmd (set explicitly: we return true).
		I.rotateMouse = { x, y }  -- re-applied after CreateMove's ClearMovement
		cmd:SetMouseX(x)
		cmd:SetMouseY(y)
		ang.p, ang.y, ang.r = look.pitch, C.YawFromMc(look.yaw), 0
		cmd:SetViewAngles(ang)
		return true
	end
	I.ApplyLook(dx, dy)
	ang.p, ang.y, ang.r = look.pitch, C.YawFromMc(look.yaw), 0
	cmd:SetViewAngles(ang)
	-- Keep GMod's own view angles with the look, so EyeAngles() never lags at the spawn heading.
	local lp = LocalPlayer()
	if IsValid(lp) then lp:SetEyeAngles(ang) end
	return true
end)

-- Every GMod bind except the reserved ones is Minecraft's while it drives.
hook.Add("PlayerBindPress", "gmodcraft_input", function(ply, bind, pressed)
	if not capturing then return end
	if hostActive() and not gmodcraft.IsMcPlayer(ply) then return end  -- S1: GMod mode keeps its binds (+menu)
	local b = bind:lower()
	if ALLOWED_BINDS[b] then return end
	-- H3: the wheel's binds belong to a physgun holding something (push / pull).
	if (b == "invnext" or b == "invprev") and I.PhysgunState().wheelToGmod then return end
	return true
end)
