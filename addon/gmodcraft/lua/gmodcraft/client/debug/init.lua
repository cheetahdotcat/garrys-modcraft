-- Debug tab (DESIGN.md §12): a spawnmenu creation tab "Garry's Modcraft" (D-014; ids stay gmodcraft) holding one sub-tab
-- per registered panel. Later phases add panels with gmodcraft.debug.RegisterPanel.
--
--   gmodcraft.debug.RegisterPanel(id, {
--       title = "Render",        -- sub-tab label
--       order = 30,              -- sort key (lower first)
--       icon  = "icon16/...",    -- optional
--       build = function(parent) end,  -- fill a DPanel (Dock FILL); return nothing
--   })
--
-- Panels refresh themselves (e.g. with a Think hook on their own controls). Registering after the
-- spawnmenu was built takes effect on `spawnmenu_reload`.

gmodcraft.debug = gmodcraft.debug or {}
local D = gmodcraft.debug
D.panels = D.panels or {}

function D.RegisterPanel(id, def)
	assert(isstring(id) and istable(def) and isfunction(def.build), "RegisterPanel(id, {title, build})")
	def.id = id
	def.title = def.title or id
	def.order = def.order or 100
	D.panels[id] = def
end

function D.SortedPanels()
	local list = {}
	for _, def in pairs(D.panels) do list[#list + 1] = def end
	table.sort(list, function(a, b)
		if a.order ~= b.order then return a.order < b.order end
		return a.id < b.id
	end)
	return list
end

-- Small helpers shared by panels.
function D.AddLabel(parent, text)
	local l = vgui.Create("DLabel", parent)
	l:Dock(TOP)
	l:DockMargin(8, 4, 8, 0)
	l:SetText(text or "")
	l:SetDark(true)
	l:SetFont("DermaDefault")
	return l
end

function D.AddCheck(parent, text, convar)
	local c = vgui.Create("DCheckBoxLabel", parent)
	c:Dock(TOP)
	c:DockMargin(8, 6, 8, 0)
	c:SetText(text)
	c:SetDark(true)
	c:SetConVar(convar)
	return c
end

function D.AddHeader(parent, text)
	local l = D.AddLabel(parent, text)
	l:SetFont("DermaDefaultBold")
	l:DockMargin(8, 10, 8, 2)
	return l
end

function D.AddButton(parent, text, fn)
	local b = vgui.Create("DButton", parent)
	b:Dock(TOP)
	b:DockMargin(8, 4, 8, 0)
	b:SetText(text)
	b.DoClick = fn
	return b
end

-- A row of buttons.
function D.AddButtons(parent, list)
	local row = vgui.Create("DPanel", parent)
	row:Dock(TOP)
	row:DockMargin(8, 4, 8, 0)
	row:SetTall(24)
	row.Paint = function() end
	for i, def in ipairs(list) do
		local b = vgui.Create("DButton", row)
		b:Dock(LEFT)
		b:DockMargin(0, 0, 4, 0)
		b:SetWide(def[3] or 120)
		b:SetText(def[1])
		b.DoClick = def[2]
	end
	return row
end

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

spawnmenu.AddCreationTab("Garry's Modcraft", function()
	local root = vgui.Create("DPanel")
	root:Dock(FILL)
	local sheet = vgui.Create("DPropertySheet", root)
	sheet:Dock(FILL)
	sheet:DockMargin(4, 4, 4, 4)
	for _, def in ipairs(D.SortedPanels()) do
		local p = vgui.Create("DPanel", sheet)
		p:Dock(FILL)
		local ok, err = pcall(def.build, p)
		if not ok then D.AddLabel(p, "panel error: " .. tostring(err)) end
		sheet:AddSheet(def.title, p, def.icon)
	end
	return root
end, "icon16/bricks.png", 200, "Garry's Modcraft internals")
