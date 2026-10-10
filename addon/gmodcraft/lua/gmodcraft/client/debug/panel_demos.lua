-- Spawnmenu tab "Garry's Modcraft", Admin group: "Demos" (P7b, admins). One-click demo builds: Place
-- builds the demo where you aim (ground, or a wall for the dig wall), facing away from you; Clear removes
-- one and restores the Minecraft world exactly; Clear all removes every demo of this map. The server
-- checks admin rights and answers in chat (shared/demos.lua).

local D = gmodcraft.debug
local DM = gmodcraft.demos

-- An Admin page: the tab wraps it in D.AdminPage (debug/panel_server.lua), which checks admin rights
-- again while the page is shown (the spawnmenu is built before the player's usergroup arrives). The
-- server checks every request anyway.
D.Register{
	id = "demos",
	title = "Demos",
	group = "admin",
	order = 40,
	icon = "icon16/world_add.png",
	desc = "One-click demo builds at your crosshair",
	build = function(scroll)
		D.AddNote(scroll, "Place builds at your crosshair, facing away from you. Clear restores the Minecraft world as it was "
			.. "before the demo: anything changed inside the demo's area since then is overwritten.")
		local force = vgui.Create("DCheckBoxLabel", scroll)
		force:Dock(TOP)
		force:DockMargin(8, 6, 8, 0)
		force:SetText("Force (place even where the area isn't empty; Clear still restores what was there)")
		force:SetDark(true)
		local builds = D.Section(scroll, "Demo builds")
		for _, d in ipairs(DM.LIST) do
			D.AddHeader(builds, d.title)
			D.AddLabel(builds, d.desc)
			D.AddButtons(builds, { { "Place at crosshair", function() DM.Request("place", d.name, force:GetChecked()) end, 160 } })
		end
		local placed = D.Section(scroll, "Placed demos")
		local list = vgui.Create("DListView", placed)
		list:Dock(TOP)
		list:DockMargin(8, 4, 8, 0)
		list:SetTall(140)
		list:SetMultiSelect(false)
		list:AddColumn("#"):SetFixedWidth(40)
		list:AddColumn("Demo")
		list:AddColumn("Minecraft position")
		local function fill(items)
			if not IsValid(list) then return end
			list:Clear()
			for _, it in ipairs(items or {}) do
				local line = list:AddLine(it.id, it.name, string.format("%d %d %d", it.x, it.y, it.z))
				line.demoId = it.id
			end
		end
		DM.OnClientList = fill
		fill(DM.clientList)
		D.AddButtons(placed, {
			{ "Clear selected", function()
				local _, line = list:GetSelectedLine()
				if line and line.demoId then DM.Request("clear", "", false, line.demoId) end
			end, 120 },
			{ "Clear all", function() DM.Request("clearall") end, 100 },
			{ "Refresh", function() DM.Request("list") end, 80 },
		})
		DM.Request("list")
	end,
}
