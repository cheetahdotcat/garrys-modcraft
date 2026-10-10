-- Spawnmenu tab "Garry's Modcraft": "Demos" (P7b, admins). One-click demo builds: Place builds the
-- demo where you aim (ground, or a wall for the dig wall), facing away from you; Clear removes one
-- and restores the Minecraft world exactly; Clear all removes every demo of this map. The server
-- checks admin rights and answers in chat (shared/demos.lua).

local D = gmodcraft.debug
local DM = gmodcraft.demos

D.RegisterPanel("demos", {
	title = "Demos",
	order = 6,
	icon = "icon16/world_add.png",
	build = function(p)
		local scroll = vgui.Create("DScrollPanel", p)
		scroll:Dock(FILL)
		D.AddHeader(scroll, "Demo builds (admins only)")
		local me = LocalPlayer()
		if not game.SinglePlayer() and not (IsValid(me) and (me:IsAdmin() or me:IsSuperAdmin())) then
			-- the server checks again; this only keeps the buttons away from non-admins
			D.AddLabel(scroll, "Demos are for server admins. Ask an admin to place one.")
			return
		end
		D.AddLabel(scroll, "Place builds at your crosshair, facing away from you. Clear restores the Minecraft world as it was")
		D.AddLabel(scroll, "before the demo: anything changed inside the demo's area since then is overwritten.")
		local force = vgui.Create("DCheckBoxLabel", scroll)
		force:Dock(TOP)
		force:DockMargin(8, 6, 8, 0)
		force:SetText("Force (place even where the area isn't empty; Clear still restores what was there)")
		force:SetDark(true)
		for _, d in ipairs(DM.LIST) do
			D.AddHeader(scroll, d.title)
			D.AddLabel(scroll, d.desc)
			D.AddButtons(scroll, { { "Place at crosshair", function() DM.Request("place", d.name, force:GetChecked()) end, 160 } })
		end
		D.AddHeader(scroll, "Placed demos")
		local list = vgui.Create("DListView", scroll)
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
		D.AddButtons(scroll, {
			{ "Clear selected", function()
				local _, line = list:GetSelectedLine()
				if line and line.demoId then DM.Request("clear", "", false, line.demoId) end
			end, 120 },
			{ "Clear all", function() DM.Request("clearall") end, 100 },
			{ "Refresh", function() DM.Request("list") end, 80 },
		})
		DM.Request("list")
	end,
})
