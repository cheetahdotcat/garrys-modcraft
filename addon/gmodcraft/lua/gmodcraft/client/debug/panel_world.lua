-- Spawnmenu tab "Garry's Modcraft", Admin group: "World" (v34 control centre, admins). The running world's
-- type and the world list (the world and its backups, kAdminWorldList), a new world of a chosen type and
-- restoring a backup (kAdminWorldOp). Both are scheduled for the dedicated Minecraft server's next start:
-- its start script (tools/run_mc_server.sh) moves the running world aside as a backup first (never
-- deleted). "Apply now" also restarts the server when that script runs it. Single player: the launcher's
-- New world. The re-anchor stays on the Server page.

local D = gmodcraft.debug
local SA = gmodcraft.serverAdmin

local OPS = { [0] = "nothing", "a new world", "restore a backup" }

local function build(scroll)
	local status = D.AddLabel(scroll, "asking the server...")
	local current = D.AddLabel(scroll, "")
	local pendingLabel = D.AddLabel(scroll, "")
	local now = vgui.Create("DCheckBoxLabel", scroll)
	now:Dock(TOP)
	now:DockMargin(8, 6, 8, 0)
	now:SetText("Apply now: restart the Minecraft server at once (everyone in Minecraft is disconnected for a moment)")
	now:SetDark(true)
	D.AddNote(scroll, "For New world and Restore below; off: they wait for the Minecraft server's next start.")

	local nw = D.Section(scroll, "New world (the running one is kept as a backup)")
	local r = vgui.Create("DPanel", nw)
	r:Dock(TOP)
	r:DockMargin(8, 4, 8, 0)
	r:SetTall(22)
	r.Paint = function() end
	local wt = vgui.Create("DComboBox", r)
	wt:Dock(LEFT)
	wt:SetWide(260)
	for _, i in ipairs(SA.NEW_WORLD_TYPES) do wt:AddChoice(SA.WORLD_LABELS[i], SA.WORLD_TYPES[i], i == 0) end
	D.AddButtons(nw, { { "New world", function()
		local _, t = wt:GetSelected()
		if not t then return end
		local n = now:GetChecked()
		Derma_Query("Start a new " .. t .. " world" .. (n and " now" or " on the next Minecraft start") .. "? The running world is kept as a backup.",
			"New world", "New world", function() SA.Request("worldop", { op = "new", type = t, now = n }) end, "Cancel")
	end, 120 }, { "Cancel pending", function() SA.Request("worldop", { op = "cancel" }) end, 120 },
		{ "Refresh", function() SA.Request("worlds") end, 80 } })
	-- the GMod server's hull trace setting (gmodcraft_hull_fill_hills), sent at once on a click
	local hills = vgui.Create("DCheckBoxLabel", nw)
	hills:Dock(TOP)
	hills:DockMargin(8, 6, 8, 0)
	hills:SetText("Hull worlds: fill the inside of terrain hills (new hull worlds only)")
	hills:SetDark(true)
	hills:SetVisible(false)  -- until the server reports the setting
	local syncing = false
	hills.OnChange = function(_, v)
		if syncing then return end
		SA.Request("gmodrules", { hullFillHills = v and true or false })
	end
	D.AddNote(nw, "Off: hills stay hollow under one layer of grass, as the map's collision has them. A running hull world keeps its blocks.")

	local wl = D.Section(scroll, "Worlds and backups (newest first)")
	local list = vgui.Create("DListView", wl)
	list:Dock(TOP)
	list:DockMargin(8, 4, 8, 0)
	list:SetTall(180)
	list:SetMultiSelect(false)
	list:AddColumn("World")
	list:AddColumn("Saved (server time)")
	list:AddColumn("Size MiB"):SetFixedWidth(70)
	D.AddButtons(wl, { { "Restore selected", function()
		local _, line = list:GetSelectedLine()
		if not (line and line.stamp) then return end
		local n = now:GetChecked()
		Derma_Query("Restore the backup of " .. line.stamp .. (n and " now" or " on the next Minecraft start") .. "? The running world is kept as a backup first.",
			"Restore", "Restore it", function() SA.Request("worldop", { op = "restore", stamp = line.stamp, now = n }) end, "Cancel")
	end, 140 } })
	D.AddNote(wl, "Re-anchor (move this map's Minecraft content up or down): the Server page.")
	local answers = D.Section(scroll, "Last answers")
	local msgs = {}
	for i = 1, 3 do msgs[i] = D.AddLabel(answers, "") end

	local function fill(st)
		if not IsValid(status) then return end
		st = st or {}
		local rules, W = st.rules, st.worlds or {}
		status:SetText(st.linked and "linked" or "no Minecraft server linked")
		current:SetText(rules and string.format("Running world: %s; new maps' floors on y %d", SA.WORLD_LABELS[rules.worldTypeId or 0] or tostring(rules.worldType),
			rules.floorY or 64) or "")
		list:Clear()
		if W.unsupported then
			pendingLabel:SetText("This Minecraft server is a single-player (integrated) one: make a new world with the launcher's New world.")
		elseif W.asking then
			pendingLabel:SetText("asking Minecraft for the worlds...")
		else
			local what = ""
			if W.pending == 1 then what = " (" .. tostring(SA.WORLD_TYPES[W.what] or W.what) .. ")"
			elseif W.pending == 2 then
				for _, e in ipairs(W.entries or {}) do if e.index == W.what then what = " (" .. tostring(e.stamp) .. ")" end end
			end
			pendingLabel:SetText("Scheduled for the next start: " .. (OPS[W.pending or 0] or tostring(W.pending)) .. what)
		end
		for _, e in ipairs(W.entries or {}) do
			local line = list:AddLine(e.kind == "world" and "running world" or "backup", e.stamp or "", e.mib or 0)
			line.stamp = e.stamp
		end
		for i = 1, 3 do msgs[i]:SetText((st.messages or {})[i] or "") end
		local g = st.gmod
		if g and g.hullFillHills ~= nil then
			syncing = true
			hills:SetChecked(g.hullFillHills == true)
			syncing = false
			hills:SetVisible(true)
		end
	end
	SA.Listen("world", fill)
	fill(SA.state)
	SA.Request("worlds")
end

D.Register{
	id = "world",
	title = "World",
	group = "admin",
	order = 20,
	icon = "icon16/world.png",
	desc = "The running world, a new world of a chosen type, backups",
	build = build,
}
