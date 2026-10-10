-- The spawnmenu creation tab "Garry's Modcraft" (DESIGN.md §12; ids stay gmodcraft): a sidebar with
-- three groups of pages on the left, the selected page on the right.
--
--   Play   settings and pages for every player (Settings, Player, Hybrid)
--   Admin  server admins only (Server, World, Maps, Demos); locked for everyone else, unlocked while
--          shown once the player's admin rights arrive
--   Debug  raw stats and internals (collapsed, with a count)
--
-- A page:
--
--   gmodcraft.debug.Register{
--       id = "render",            -- unique id (gmodcraft.debug.ShowPage(id) opens it)
--       title = "Render",         -- sidebar label and page title
--       group = "debug",          -- "play" | "admin" | "debug" (default "debug")
--       order = 30,               -- sort key inside the group (lower first)
--       icon  = "icon16/...",     -- optional
--       desc  = "...",            -- optional one-line subtitle under the page title
--       build = function(body) end,  -- fill body (a DScrollPanel; scrolled = false: a plain panel)
--   }
--
-- Pages are built the first time they are shown. Admin pages (group "admin") go through
-- D.AdminPage (debug/panel_server.lua), which re-checks the rights while shown. Pages refresh
-- themselves (a Think on body runs only while the page is visible). The old
-- D.RegisterPanel(id, def) still works (group "debug").
--
-- Building blocks for pages (all dock TOP into parent):
--   D.Section(parent, title, collapsed)   a collapsible section; returns its body to fill
--   D.AddCheck(parent, text, convar)      a checkbox bound to a convar (tooltip: the convar's help)
--   D.AddChoice(parent, text, convar, { "label for 0", "label for 1", ... } or { { label, value }, ... })
--   D.AddSlider(parent, text, convar, min, max, decimals)
--   D.AddBinder(parent, text, convar)     a key binder for a KEY_* convar
--   D.AddNote(parent, text)               wrapped help text
--   D.AddStats(parent, n)                 n labels for live lines; returns them
--   D.AddLabel / AddHeader / AddButton / AddButtons
--
-- The Settings page (panel_settings.lua) is built from a registry, so a feature can add its option
-- with one line from its own file:
--   gmodcraft.debug.AddSetting("holes", { kind = "check", convar = "gmodcraft_x", text = "..." })
-- (kind "check" | "choice" (choices) | "slider" (min, max, decimals) | "binder"; optional order, help.)

gmodcraft.debug = gmodcraft.debug or {}
local D = gmodcraft.debug
D.panels = D.panels or {}

D.GROUPS = {
	{ id = "play", title = "Play" },
	{ id = "admin", title = "Admin", admin = true },
	{ id = "debug", title = "Debug", collapsed = true, badge = true },
}

function D.Register(def)
	assert(istable(def) and isstring(def.id) and isfunction(def.build), "Register{ id, title, group, build }")
	def.title = def.title or def.id
	def.group = def.group or "debug"
	def.order = def.order or 100
	if def.admin == nil then def.admin = def.group == "admin" end
	D.panels[def.id] = def
	return def
end

function D.RegisterPanel(id, def)
	assert(isstring(id) and istable(def), "RegisterPanel(id, {title, build})")
	def.id = id
	return D.Register(def)
end

-- The pages of one group (all pages: group nil), sorted.
function D.Pages(group)
	local list = {}
	for _, def in pairs(D.panels) do
		if group == nil or def.group == group then list[#list + 1] = def end
	end
	table.sort(list, function(a, b)
		if a.order ~= b.order then return a.order < b.order end
		return a.id < b.id
	end)
	return list
end
D.SortedPanels = D.Pages

-- ---- settings registry ---------------------------------------------------------------------------
-- Sections of the Settings page, each with rows bound to client convars. Rows are keyed by convar, so
-- a Lua refresh replaces instead of doubling them.
D.settings = D.settings or {}
local rowSeq = 0

function D.SettingsSection(id, title, order, opts)
	local s = D.settings[id] or { rows = {} }
	s.id, s.title, s.order = id, title or id, order or 100
	opts = opts or {}
	s.collapsed, s.note = opts.collapsed, opts.note
	D.settings[id] = s
	return s
end

function D.AddSetting(section, def)
	assert(isstring(section) and istable(def) and isstring(def.convar), "AddSetting(section, { kind, convar, text })")
	local s = D.settings[section] or D.SettingsSection(section)
	rowSeq = rowSeq + 1
	local old = s.rows[def.convar]
	def.kind = def.kind or "check"
	def.order = def.order or (old and old.order) or rowSeq
	s.rows[def.convar] = def
	return def
end

function D.SettingsSections()
	local list = {}
	for _, s in pairs(D.settings) do
		local rows = {}
		for _, r in pairs(s.rows) do rows[#rows + 1] = r end
		table.sort(rows, function(a, b) return a.order < b.order end)
		list[#list + 1] = { id = s.id, title = s.title, order = s.order, collapsed = s.collapsed, note = s.note, rows = rows }
	end
	table.sort(list, function(a, b)
		if a.order ~= b.order then return a.order < b.order end
		return a.id < b.id
	end)
	return list
end

-- ---- building blocks -----------------------------------------------------------------------------
local function helpOf(convar, help)
	if help then return help end
	local cv = convar and GetConVar(convar)
	local t = cv and cv.GetHelpText and cv:GetHelpText()
	if not isstring(t) or t == "" then return nil end
	return (t:gsub("^Garry's Modcraft[^:]*: ", "")) .. "\n[" .. convar .. "]"
end

function D.AddLabel(parent, text)
	local l = vgui.Create("DLabel", parent)
	l:Dock(TOP)
	l:DockMargin(8, 4, 8, 0)
	l:SetText(text or "")
	l:SetDark(true)
	l:SetFont("DermaDefault")
	return l
end

function D.AddHeader(parent, text)
	local l = D.AddLabel(parent, text)
	l:SetFont("DermaDefaultBold")
	l:DockMargin(8, 10, 8, 2)
	return l
end

-- Wrapped, dimmed help text.
function D.AddNote(parent, text)
	local l = D.AddLabel(parent, text)
	l:SetWrap(true)
	l:SetAutoStretchVertical(true)
	l:SetTextColor(Color(90, 90, 90))
	return l
end

-- n labels for live text (stats); returns the list.
function D.AddStats(parent, n)
	local out = {}
	for i = 1, n do out[i] = D.AddLabel(parent) end
	return out
end

function D.AddCheck(parent, text, convar, help)
	local c = vgui.Create("DCheckBoxLabel", parent)
	c:Dock(TOP)
	c:DockMargin(8, 6, 8, 0)
	c:SetText(text)
	c:SetDark(true)
	c:SetConVar(convar)
	local tip = helpOf(convar, help)
	if tip then c:SetTooltip(tip) end
	return c
end

-- A row: a label on the left (labelWide), the control filling the rest up to wide.
local function row(parent, text, class, help, convar, wide)
	local r = vgui.Create("DPanel", parent)
	r:Dock(TOP)
	r:DockMargin(8, 6, 8, 0)
	r:SetTall(22)
	r.Paint = function() end
	local l = vgui.Create("DLabel", r)
	l:Dock(LEFT)
	l:SetWide(240)
	l:SetText(text)
	l:SetDark(true)
	local c = vgui.Create(class, r)
	c:Dock(LEFT)
	c:SetWide(wide or 420)
	local tip = helpOf(convar, help)
	if tip then l:SetTooltip(tip) c:SetTooltip(tip) end
	return c, r
end

-- A combo box for an integer convar: choices are labels for 0, 1, 2, ... or { label, value } pairs.
-- Follows the convar when it changes elsewhere.
function D.AddChoice(parent, text, convar, choices, help)
	local c = row(parent, text, "DComboBox", help, convar)
	local cv = GetConVar(convar)
	local values = {}
	for i, ch in ipairs(choices) do
		local label, value = ch, i - 1
		if istable(ch) then label, value = ch[1], ch[2] end
		values[i] = tostring(value)
		c:AddChoice(label, value)
	end
	local shown
	local function sync()
		local v = cv and cv:GetString() or ""
		if v == shown then return end
		shown = v
		c.syncing = true  -- showing the convar's value is not a choice: no command
		local found = false
		for i, val in ipairs(values) do
			if tonumber(val) == tonumber(v) then c:ChooseOptionID(i) found = true break end
		end
		if not found then c:SetValue(v) end
		c.syncing = false
	end
	c.OnSelect = function(self, _, _, value)
		if self.syncing then return end
		shown = tostring(value)
		RunConsoleCommand(convar, tostring(value))
	end
	sync()
	c.Think = function(self)
		if (self.nextSync or 0) > RealTime() or self:IsMenuOpen() then return end
		self.nextSync = RealTime() + 0.5
		sync()
	end
	return c
end

function D.AddSlider(parent, text, convar, lo, hi, decimals, help)
	local s = vgui.Create("DNumSlider", parent)
	s:Dock(TOP)
	s:DockMargin(8, 2, 8, 0)
	s:SetText(text)
	s:SetMinMax(lo, hi)
	s:SetDecimals(decimals or 2)
	s:SetConVar(convar)
	s:SetDark(true)
	local tip = helpOf(convar, help)
	if tip then s:SetTooltip(tip) end
	return s
end

function D.AddBinder(parent, text, convar, help)
	local b = row(parent, text, "DBinder", help, convar, 160)
	b:GetParent():SetTall(24)
	local cv = GetConVar(convar)
	b:SetValue(cv and cv:GetInt() or 0)
	b.OnChange = function(_, num) RunConsoleCommand(convar, tostring(num or 0)) end
	return b
end

-- One row of the settings registry (D.AddSetting). Rows for convars that don't exist are skipped.
function D.AddSettingRow(parent, def)
	if not GetConVar(def.convar) then return nil end
	if def.kind == "choice" then return D.AddChoice(parent, def.text, def.convar, def.choices or {}, def.help) end
	if def.kind == "slider" then return D.AddSlider(parent, def.text, def.convar, def.min or 0, def.max or 1, def.decimals, def.help) end
	if def.kind == "binder" then return D.AddBinder(parent, def.text, def.convar, def.help) end
	return D.AddCheck(parent, def.text, def.convar, def.help)
end

function D.AddButton(parent, text, fn)
	local b = vgui.Create("DButton", parent)
	b:Dock(TOP)
	b:DockMargin(8, 4, 8, 0)
	b:SetText(text)
	b.DoClick = fn
	return b
end

-- A row of buttons: { { text, fn, width }, ... }.
function D.AddButtons(parent, list)
	local r = vgui.Create("DPanel", parent)
	r:Dock(TOP)
	r:DockMargin(8, 4, 8, 0)
	r:SetTall(24)
	r.Paint = function() end
	for _, def in ipairs(list) do
		local b = vgui.Create("DButton", r)
		b:Dock(LEFT)
		b:DockMargin(0, 0, 4, 0)
		b:SetWide(def[3] or 120)
		b:SetText(def[1])
		b.DoClick = def[2]
	end
	return r
end

-- A collapsible section; returns its body (fill it like a page) and the category panel.
function D.Section(parent, title, collapsed)
	local cat = vgui.Create("DCollapsibleCategory", parent)
	cat:Dock(TOP)
	cat:DockMargin(6, 6, 6, 0)
	cat:SetLabel(title)
	local body = vgui.Create("DPanel")
	body:DockPadding(0, 0, 0, 8)
	-- The body is as tall as its docked children (the category sizes itself to the body).
	body.PerformLayout = function(self)
		local h = 0
		for _, c in ipairs(self:GetChildren()) do
			if c:IsVisible() then
				local _, _, _, mb = c:GetDockMargin()
				h = math.max(h, c:GetY() + c:GetTall() + mb)
			end
		end
		if self:GetTall() ~= h + 8 then self:SetTall(h + 8) end
	end
	cat:SetContents(body)
	cat:SetExpanded(not collapsed)
	return body, cat
end

-- ---- server stats relay --------------------------------------------------------------------------
-- Server-side link stats, relayed to admins by the server (net gmodcraft_stats). Panels call
-- D.RequestServerStats() from their Think; the reply lands in D.serverStats.
D.serverStats = D.serverStats or nil
D.serverStatsAt = D.serverStatsAt or 0
local lastAsk = 0
function D.RequestServerStats()
	if RealTime() - lastAsk < 0.5 then return end
	lastAsk = RealTime()
	net.Start(gmodcraft.NET.statsReq)
	net.SendToServer()
end
net.Receive(gmodcraft.NET.stats, function()
	local n = net.ReadUInt(16)
	local data = net.ReadData(n)
	local json = util.Decompress(data or "")
	local t = json and util.JSONToTable(json)
	if t then D.serverStats, D.serverStatsAt = t, RealTime() end
end)

-- Dump state: a JSON snapshot for bug reports, data/gmodcraft/dump_<time>.json.
function D.Dump()
	local CL = gmodcraft.clientLink
	local snap = {
		time = os.date("%Y-%m-%d %H:%M:%S"), map = game.GetMap(), module = gmodcraft.Version and gmodcraft.Version() or "missing",
		client = not gmodcraft.missing and gmodcraft.Stats() or nil, server = D.serverStats, mcState = CL and CL.M,
		mcProcess = gmodcraft.mc and gmodcraft.mc.Status(), slot = gmodcraft.convert.slot, view = gmodcraft.view and gmodcraft.view.stats,
		input = gmodcraft.input and { counts = gmodcraft.input.counts, log = gmodcraft.input.log, look = gmodcraft.input.look },
		player = D.playerStats, convars = {},
	}
	for sys in pairs(gmodcraft.logSystems or {}) do
		for _, realm in ipairs({ "cl", "sv" }) do
			-- the server's names exist here only on a listen server
			local name = gmodcraft.LogConVarName(sys, realm)
			local cv = name and GetConVar(name)
			if cv then snap.convars[name] = cv:GetInt() end
		end
	end
	file.CreateDir("gmodcraft")
	local name = "gmodcraft/dump_" .. os.date("%Y%m%d_%H%M%S") .. ".json"
	file.Write(name, util.TableToJSON(snap, true))
	gmodcraft.Info("state dumped to data/%s", name)
	return name
end
concommand.Add("gmodcraft_dump", function() D.Dump() end)

-- ---- the tab -------------------------------------------------------------------------------------
local SIDEBAR_W = 168
local COL_SEL, COL_HOT = Color(60, 120, 200), Color(0, 0, 0, 30)

local function isAdmin()
	local SA = gmodcraft.serverAdmin
	return SA and SA.IsAdmin and SA.IsAdmin(LocalPlayer()) or false
end

-- The page container for def (built on first show).
local function buildPage(ui, def)
	local page = vgui.Create("DPanel", ui.content)
	page:Dock(FILL)
	page.Paint = function() end
	page:SetVisible(false)
	local head = vgui.Create("DPanel", page)
	head:Dock(TOP)
	head:DockMargin(8, 6, 8, 2)
	head:SetTall(def.desc and 42 or 28)
	head.Paint = function() end
	local t = vgui.Create("DLabel", head)
	t:Dock(TOP)
	t:SetTall(26)
	t:SetFont("DermaLarge")
	t:SetTextColor(Color(40, 40, 40))
	t:SetText(def.title)
	if def.desc then
		local s = vgui.Create("DLabel", head)
		s:Dock(TOP)
		s:SetDark(true)
		s:SetText(def.desc)
	end
	local ok, err
	if def.admin then
		ok, err = pcall(D.AdminPage, page, nil, def.build, def.scrolled)
	else
		local body = vgui.Create(def.scrolled == false and "DPanel" or "DScrollPanel", page)
		body:Dock(FILL)
		if def.scrolled == false then body.Paint = function() end end
		ok, err = pcall(def.build, body)
		if not ok then D.AddLabel(body, "panel error: " .. tostring(err)) end
	end
	if not ok and def.admin then D.AddLabel(page, "panel error: " .. tostring(err)) end
	return page
end

-- Opens page id in the tab (built if needed); returns the page panel or nil (no tab / no page).
function D.ShowPage(id)
	local ui = D.ui
	if not (ui and IsValid(ui.root)) then return nil end
	local def = D.panels[id]
	local entry = ui.entries[id]
	if not def or not entry then return nil end
	if IsValid(ui.pages[ui.current]) then ui.pages[ui.current]:SetVisible(false) end
	if not IsValid(ui.pages[id]) then ui.pages[id] = buildPage(ui, def) end
	ui.pages[id]:SetVisible(true)
	ui.content:InvalidateLayout(true)
	ui.current, D.lastPage = id, id
	local cat = ui.groups[def.group]
	if IsValid(cat) and not cat:GetExpanded() then cat:Toggle() end
	return ui.pages[id]
end

local function sidebarEntry(ui, parent, def)
	local b = vgui.Create("DButton", parent)
	b:Dock(TOP)
	b:DockMargin(2, 1, 2, 0)
	b:SetTall(24)
	b:SetText(def.title)
	b:SetContentAlignment(4)
	b:SetImage(def.icon or "icon16/page.png")
	b.Paint = function(self, w, h)
		local sel = ui.current == def.id
		if sel then
			draw.RoundedBox(4, 0, 0, w, h, COL_SEL)
		elseif self:IsHovered() then
			draw.RoundedBox(4, 0, 0, w, h, COL_HOT)
		end
		self:SetTextColor(sel and color_white or Color(30, 30, 30))
	end
	b.DoClick = function() D.ShowPage(def.id) end
	ui.entries[def.id] = b
	return b
end

local function buildTab()
	local ui = { entries = {}, pages = {}, groups = {} }
	D.ui = ui
	local root = vgui.Create("DPanel")
	root:Dock(FILL)
	ui.root = root

	local side = vgui.Create("DScrollPanel", root)
	side:Dock(LEFT)
	side:SetWide(SIDEBAR_W)
	side:DockMargin(4, 4, 0, 4)
	ui.side = side
	ui.content = vgui.Create("DPanel", root)
	ui.content:Dock(FILL)
	ui.content:DockMargin(4, 4, 4, 4)

	for _, g in ipairs(D.GROUPS) do
		local pages = D.Pages(g.id)
		if #pages > 0 then
			local body, cat = D.Section(side, g.title, g.collapsed)
			cat:DockMargin(0, 0, 0, 4)
			ui.groups[g.id] = cat
			if g.badge then
				-- the count on the right of the header
				local n = tostring(#pages)
				cat.Header.PaintOver = function(self, w, h)
					surface.SetFont("DermaDefaultBold")
					local tw = surface.GetTextSize(n)
					draw.RoundedBox(6, w - tw - 16, 3, tw + 10, h - 6, Color(200, 90, 40))
					draw.SimpleText(n, "DermaDefaultBold", w - 11 - tw / 2, h / 2, color_white, TEXT_ALIGN_CENTER, TEXT_ALIGN_CENTER)
				end
			end
			for _, def in ipairs(pages) do sidebarEntry(ui, body, def) end
			if g.admin then
				-- locked (lock icons, "(locked)") until the player's admin rights arrive; checked again
				-- while the tab is shown (the spawnmenu is built before the usergroup arrives)
				local admin
				local function update()
					local now = isAdmin()
					if now == admin then return end
					admin = now
					cat:SetLabel(admin and g.title or (g.title .. " (locked)"))
					for _, def in ipairs(pages) do
						local e = ui.entries[def.id]
						if IsValid(e) then e:SetImage(admin and (def.icon or "icon16/page.png") or "icon16/lock.png") end
					end
				end
				update()
				local nextCheck = 0
				body.Think = function()
					if admin or RealTime() < nextCheck then return end
					nextCheck = RealTime() + 1
					update()
				end
			end
		end
	end

	local first = D.lastPage
	if not (first and D.panels[first]) then first = D.panels.settings and "settings" or (D.Pages()[1] or {}).id end
	if first then D.ShowPage(first) end
	return root
end

spawnmenu.AddCreationTab("Garry's Modcraft", buildTab, "icon16/bricks.png", 200, "Garry's Modcraft settings, admin pages and internals")
