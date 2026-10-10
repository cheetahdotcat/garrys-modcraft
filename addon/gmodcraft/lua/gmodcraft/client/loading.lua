-- L3: the loading screen while Minecraft starts. After a map loads, client/launch.lua starts
-- Minecraft; launching, linking, joining the world, pairing and the spawn teleport take tens of
-- seconds. This draws a full-screen overlay with the steps until the puppet runs (Minecraft drives),
-- and a small corner indicator when it was hidden (Space / Esc), when Minecraft was already running
-- at load, when it was started by hand, and when it drops mid-session ("reconnecting").
--
-- Only existing state is read (no new signals):
--   1 process   gmodcraft.mc.Status() (McProcess: running = the Minecraft lock is held), polled 1/s
--   2 link      gmodcraft.clientLink.mcAlive
--   3 world     gmodcraft.clientLink.McInWorld()
--   4 paired    gmodcraft.convert.slot.known and gmodcraft.IsMcPlayer (NW2: mcWanted and paired;
--               paired = the server has our identity, its server link and the slot)
--   5 spawned   McState.teleportCount above its value when Minecraft attached (the client never
--               sees the ack itself), or the puppet already on
--   6 ready     gmodcraft.IsPuppet (the puppet only runs with no teleport pending)
-- Failures: MC.lastResult "not started: ...", McProcess error / launch helper exit status, or the
-- Minecraft lock gone before the link came up. Past 180 s on one step: "taking longer than usual".
--
-- Not shown: gmodcraft_loading_screen 0, module missing, autostart off and nobody started
-- Minecraft (a start by hand later: corner indicator), Minecraft linked at load or within
-- ATTACH_GRACE_S of it (a changelevel). Running but not linked (the launcher's prewarm): full screen. "GMod-only"
-- has no state of its own: a player who stays out of MC mode (F6, or a server without Minecraft)
-- gets the corner indicator for a while, then nothing.
--
-- The phase logic (LD.Step) is pure: a snapshot in, the memory table updated, no GMod calls.

local LD = gmodcraft.loading or {}
gmodcraft.loading = LD

LD.STEPS = {
	"Minecraft process launching",
	"Link to GMod connected",
	"Minecraft world joined",
	"Slot and identity paired",
	"Spawned (teleport acknowledged)",
	"Ready",
}
LD.SLOW_S = 180          -- one step this long: "taking longer than usual"
LD.GMOD_MODE_S = 8       -- linked and in the world, but not MC mode this long: GMod mode by choice
LD.GMOD_CORNER_S = 20    -- ... and the corner indicator goes away after this much more
LD.ATTACH_GRACE_S = 3    -- Minecraft running at load: this long for it to attach before the full screen
LD.EXIT_GRACE_S = 5      -- Minecraft's lock gone this long (no launch, no restart): it exited

local function startsWith(s, p) return type(s) == "string" and s:sub(1, #p) == p end

-- Which steps are done in a snapshot (each needs the ones before it, so the ticks only grow).
function LD.Done(m, o)
	local p = o.proc or {}
	local d = {}
	d[1] = p.running == true or o.mcAlive
	d[2] = d[1] and o.mcAlive
	d[3] = d[2] and o.inWorld
	d[4] = d[3] and o.slotKnown and o.mcMode
	d[5] = d[4] and (o.puppet or (m.tpBase ~= nil and (o.teleportCount or 0) > m.tpBase))
	d[6] = d[5] and o.puppet
	local cur = #LD.STEPS + 1
	for i = 1, #LD.STEPS do
		if not d[i] then cur = i break end
	end
	return d, cur
end

-- Why it's stuck, or nil. Only before the link is up (after that, nothing here is fatal).
function LD.Failure(m, o)
	if o.mcAlive or o.restarting then return nil end
	local p = o.proc or {}
	if p.running or p.launching then return nil end
	if startsWith(o.lastResult, "not started") then return "Minecraft couldn't be started: " .. o.lastResult:sub(14) end
	if p.error and p.error ~= "" then return "Minecraft couldn't be started: " .. p.error end
	if (p.launchStatus or -1) > 0 then return "The launch helper exited with status " .. p.launchStatus end
	if startsWith(o.lastResult, "restart:") then return "Minecraft " .. o.lastResult end
	if m.sawRunning and m.goneAt and o.now - m.goneAt >= LD.EXIT_GRACE_S then
		return m.kind == "reconnect" and "Minecraft exited" or "Minecraft exited before it linked to GMod"
	end
	return nil
end

-- One frame. m: the memory (phase, kind, timers); o: the snapshot. Updates m, returns nothing.
-- Phases: wait (map loading) -> decide -> full | corner | watch | idle | off.
function LD.Step(m, o)
	m.phase = m.phase or "wait"
	if m.phase == "off" or m.phase == "wait" then return end
	local p = o.proc or {}
	-- the attach: baseline for the spawn teleport
	if o.mcAlive and not m.wasAlive then m.tpBase = o.teleportCount or 0 end
	if not o.mcAlive then m.tpBase = nil end
	m.wasAlive = o.mcAlive
	if p.running then m.sawRunning, m.goneAt = true, nil
	elseif m.sawRunning and not p.launching and not o.restarting then m.goneAt = m.goneAt or o.now
	else m.goneAt = nil end

	if m.phase == "decide" then
		m.t0 = m.loadedAt or o.now  -- elapsed counts from the map load
		if o.missing then m.phase = "off" return end
		if o.mcAlive then
			-- linked at load (a changelevel): nothing to show, just watch for drops
			m.phase, m.readyOnce = "idle", true
			return
		end
		if p.running and o.now - (m.loadedAt or o.now) < LD.ATTACH_GRACE_S then
			return  -- running already (a changelevel, or the launcher's prewarm): give the link a moment
		elseif p.running then
			m.phase, m.kind = "full", "start"     -- the launcher prewarmed it and it's still starting
		elseif not o.autostart and not p.launching and not startsWith(o.lastResult, "started") then
			m.phase = "watch"                     -- autostart off: only if someone starts it
			return
		else
			m.phase, m.kind = "full", "start"
		end
		m.step, m.stepAt = 0, o.now
	end

	if m.phase == "watch" then
		if not (p.launching or p.running or o.mcAlive or startsWith(o.lastResult, "started")) then return end
		m.phase, m.kind, m.t0, m.step, m.stepAt = "corner", "start", o.now, 0, o.now
	end

	if m.phase == "idle" then
		if not (m.readyOnce and (not o.mcAlive or o.restarting)) then return end
		m.phase, m.kind, m.t0, m.step, m.stepAt = "corner", "reconnect", o.now, 0, o.now
		m.gmodSince, m.note = nil, nil
	end

	-- full or corner
	local _, cur = LD.Done(m, o)
	if cur ~= m.step then m.step, m.stepAt = cur, o.now end
	if cur > #LD.STEPS and not o.restarting then  -- (a restart's old Minecraft is still up for a moment)
		m.phase, m.readyOnce, m.note, m.gmodSince = "idle", true, nil, nil
		return
	end
	-- In its world but not MC mode: GMod mode (F6), or a server without Minecraft (no slot, no pairing).
	if o.inWorld and not o.mcMode then
		m.gmodSince = m.gmodSince or o.now
		local t = o.now - m.gmodSince
		if t >= LD.GMOD_MODE_S then
			m.note = "Not in MC mode: F6 switches, or this server has no Minecraft"
			if m.phase == "full" then m.phase = "corner" end
			if t >= LD.GMOD_MODE_S + LD.GMOD_CORNER_S then
				m.phase, m.readyOnce, m.note = "idle", true, nil
			end
		end
	else
		m.gmodSince = nil
		if m.note and o.mcMode then m.note = nil end
	end
	m.failure = LD.Failure(m, o)
	m.slow = m.failure == nil and o.now - (m.stepAt or o.now) >= LD.SLOW_S
end

function LD.Dismiss(m)
	if m.phase == "full" then m.phase, m.dismissed = "corner", true end
end

-- ---- GMod glue ----------------------------------------------------------------------------------
if not hook or not CreateClientConVar then return end

local MC = gmodcraft.mc
local CL = gmodcraft.clientLink
local C = gmodcraft.convert
local cvOn = CreateClientConVar("gmodcraft_loading_screen", "1", true, false,
	"Garry's Modcraft: show the loading screen / corner indicator while Minecraft starts or reconnects")
local cvAuto = GetConVar("gmodcraft_autostart")

LD.m = LD.m or {}
local m = LD.m
local snap = { proc = {} }
local procAt = -1e9

hook.Add("InitPostEntity", "gmodcraft_loading", function()
	-- launch.lua's own InitPostEntity (MC.Start) runs in any order with this one: decide a moment later
	m.phase, m.loadedAt = "wait", RealTime()
end)

local function snapshot(now)
	local o = snap
	o.now = now
	o.missing = gmodcraft.missing == true or not MC or not CL
	if o.missing then return o end
	if now - procAt >= 1 then
		procAt = now
		local st = MC.Status() or {}
		o.proc = { running = st.running == true, launching = st.launching == true, error = st.error or "",
			launchStatus = tonumber(st.launchStatus) or -1 }
	end
	o.autostart = cvAuto == nil or cvAuto:GetBool()
	o.lastResult = MC.lastResult or ""
	o.restarting = timer.Exists("gmodcraft_mc_restart")  -- MC.Restart (launch.lua) waiting to start it again
	o.mcAlive = CL.mcAlive == true
	o.inWorld = CL.McInWorld() == true
	o.slotKnown = C.slot.known == true
	local ply = LocalPlayer()
	o.mcMode = gmodcraft.IsMcPlayer(ply)
	o.puppet = gmodcraft.IsPuppet(ply)
	o.teleportCount = (CL.haveMc and CL.M.teleportCount) or 0
	return o
end

local spaceDown, uiVisible = false, false
hook.Add("Think", "gmodcraft_loading", function()
	local now = RealTime()
	if m.phase == "wait" then
		if not m.loadedAt or now - m.loadedAt < 0.5 then return end
		m.phase = "decide"
		procAt = -1e9
		uiVisible = gui.IsGameUIVisible()  -- only a menu opened from now on hides the screen
	end
	if not m.phase or m.phase == "off" then return end
	LD.Step(m, snapshot(now))
	-- Hide the full screen: Space (only when nothing else takes it), or Esc (the GMod menu opens as usual).
	local ui = gui.IsGameUIVisible()
	local space = input.IsKeyDown(KEY_SPACE)
	if m.phase == "full" then
		local typing = IsValid(vgui.GetKeyboardFocus())
		if (space and not spaceDown and not ui and not typing) or (ui and not uiVisible) then LD.Dismiss(m) end
	end
	spaceDown, uiVisible = space, ui
end)

-- ---- drawing --------------------------------------------------------------------------------------
local fontScale
local function fonts()
	local s = math.max(ScrH(), 480) / 1080
	if s == fontScale then return s end
	fontScale = s
	surface.CreateFont("GmodcraftLoadTitle", { font = "Roboto", size = math.Round(44 * s), weight = 700, antialias = true })
	surface.CreateFont("GmodcraftLoadStep", { font = "Roboto", size = math.Round(26 * s), weight = 500, antialias = true })
	surface.CreateFont("GmodcraftLoadSmall", { font = "Roboto", size = math.Round(19 * s), weight = 500, antialias = true })
	return s
end

local COL = {
	backdrop = Color(10, 12, 16, 238), card = Color(24, 27, 33, 245), text = Color(235, 237, 240),
	dim = Color(150, 156, 166), done = Color(92, 184, 92), cur = Color(240, 173, 78), pending = Color(70, 75, 85),
	bad = Color(217, 83, 79), hint = Color(120, 126, 136),
}

-- word wrap for one font into lines no wider than w
local function wrap(text, font, w)
	surface.SetFont(font)
	local lines, line = {}, ""
	for word in tostring(text):gmatch("%S+") do
		local try = line == "" and word or (line .. " " .. word)
		if line ~= "" and surface.GetTextSize(try) > w then
			lines[#lines + 1] = line
			line = word
		else
			line = try
		end
	end
	if line ~= "" then lines[#lines + 1] = line end
	return lines
end

local function remedy()
	local r = "Hold Q > Garry's Modcraft > Links > Start MC / Restart MC, or start the GmodCraft instance from the Garry's Modcraft launcher (Prism)."
	if gmodcraft.devMode then r = r .. " Console: gmodcraft_mc_restart." end
	return r
end

local function dot(x, y, r, col)
	draw.RoundedBox(r, x - r, y - r, r * 2, r * 2, col)
end

local function drawFull(s, now)
	local W, H = ScrW(), ScrH()
	surface.SetDrawColor(COL.backdrop)
	surface.DrawRect(0, 0, W, H)
	local cw = math.min(W - 32, math.Round(760 * s))
	local pad = math.Round(32 * s)
	local rowH = math.Round(42 * s)
	local inner = cw - pad * 2
	local failLines = m.failure and wrap(m.failure, "GmodcraftLoadStep", inner) or {}
	local remLines = m.failure and wrap(remedy(), "GmodcraftLoadSmall", inner) or {}
	local hint = "Space or Esc hides this; GMod stays playable underneath. Minecraft's window may show up for a moment."
	if m.slow then hint = "Taking longer than usual. " .. hint end
	if m.note then hint = m.note .. ". " .. hint end
	local hintLines = wrap(hint, "GmodcraftLoadSmall", inner)
	local smallH = math.Round(26 * s)
	local ch = pad + math.Round(56 * s) + rowH * #LD.STEPS + math.Round(16 * s) + smallH * #hintLines
		+ (#failLines > 0 and (math.Round(16 * s) + math.Round(32 * s) * #failLines + smallH * #remLines) or 0) + pad
	local cx, cy = math.Round((W - cw) / 2), math.Round((H - ch) / 2)
	draw.RoundedBox(math.Round(10 * s), cx, cy, cw, ch, COL.card)

	local x, y = cx + pad, cy + pad
	draw.SimpleText("Starting Minecraft…", "GmodcraftLoadTitle", x, y, COL.text)
	draw.SimpleText(string.format("%d s", math.floor(now - (m.t0 or now))), "GmodcraftLoadStep", cx + cw - pad, y + math.Round(10 * s), COL.dim, TEXT_ALIGN_RIGHT)
	y = y + math.Round(56 * s)
	local r = math.Round(8 * s)
	local pulse = 0.55 + 0.45 * math.abs(math.sin(now * 3))
	for i, label in ipairs(LD.STEPS) do
		local col, tcol
		if i < m.step then col, tcol = COL.done, COL.text
		elseif i == m.step then
			col = m.failure and COL.bad or Color(COL.cur.r, COL.cur.g, COL.cur.b, 255 * pulse)
			tcol = COL.text
		else col, tcol = COL.pending, COL.dim end
		local my = y + rowH / 2
		dot(x + r, my, r, col)
		draw.SimpleText(label, "GmodcraftLoadStep", x + r * 2 + math.Round(16 * s), my, tcol, TEXT_ALIGN_LEFT, TEXT_ALIGN_CENTER)
		if i < m.step then
			draw.SimpleText("done", "GmodcraftLoadSmall", cx + cw - pad, my, COL.done, TEXT_ALIGN_RIGHT, TEXT_ALIGN_CENTER)
		elseif i == m.step and not m.failure then
			draw.SimpleText(string.format("%d s", math.floor(now - (m.stepAt or now))), "GmodcraftLoadSmall", cx + cw - pad, my, COL.dim, TEXT_ALIGN_RIGHT, TEXT_ALIGN_CENTER)
		end
		y = y + rowH
	end
	if #failLines > 0 then
		y = y + math.Round(16 * s)
		for _, l in ipairs(failLines) do
			draw.SimpleText(l, "GmodcraftLoadStep", x, y, COL.bad)
			y = y + math.Round(32 * s)
		end
		for _, l in ipairs(remLines) do
			draw.SimpleText(l, "GmodcraftLoadSmall", x, y, COL.text)
			y = y + smallH
		end
	end
	y = y + math.Round(16 * s)
	for _, l in ipairs(hintLines) do
		draw.SimpleText(l, "GmodcraftLoadSmall", x, y, COL.hint)
		y = y + smallH
	end
end

local function drawCorner(s, now)
	local title = m.kind == "reconnect" and "Minecraft reconnecting…" or "Starting Minecraft…"
	local step = LD.STEPS[m.step] or ""
	local line2
	if m.failure then line2 = m.failure .. " (Hold Q > Garry's Modcraft > Links)"
	elseif m.note then line2 = m.note
	else line2 = string.format("%d/%d %s · %d s", math.min(m.step or 1, #LD.STEPS), #LD.STEPS, step, math.floor(now - (m.t0 or now))) end
	surface.SetFont("GmodcraftLoadStep")
	local w1 = surface.GetTextSize(title)
	surface.SetFont("GmodcraftLoadSmall")
	local w2 = surface.GetTextSize(line2)
	local pad, r = math.Round(12 * s), math.Round(6 * s)
	local w = math.min(math.max(w1, w2) + pad * 3 + r * 2, ScrW() - 32)
	local h = math.Round(64 * s)
	local x, y = ScrW() - w - math.Round(16 * s), math.Round(16 * s)
	draw.RoundedBox(math.Round(8 * s), x, y, w, h, COL.card)
	local pulse = 0.55 + 0.45 * math.abs(math.sin(now * 3))
	dot(x + pad + r, y + h / 2, r, m.failure and COL.bad or Color(COL.cur.r, COL.cur.g, COL.cur.b, 255 * pulse))
	local tx = x + pad * 2 + r * 2
	draw.SimpleText(title, "GmodcraftLoadStep", tx, y + math.Round(8 * s), COL.text)
	draw.SimpleText(line2, "GmodcraftLoadSmall", tx, y + h - math.Round(8 * s), m.failure and COL.bad or COL.dim, TEXT_ALIGN_LEFT, TEXT_ALIGN_BOTTOM)
end

-- HUDPaint like view.lua's overlay, which skips Minecraft's HUD while the full screen is up (hook order is undefined).
hook.Add("HUDPaint", "gmodcraft_loading", function()
	if (m.phase ~= "full" and m.phase ~= "corner") or not cvOn:GetBool() then return end
	local s = fonts()
	local now = RealTime()
	if m.phase == "full" then drawFull(s, now) else drawCorner(s, now) end
end)
