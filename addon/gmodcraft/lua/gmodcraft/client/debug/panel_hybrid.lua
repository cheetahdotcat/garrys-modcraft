-- Spawnmenu tab "Garry's Modcraft": "Hybrid" settings (H2 input toggles, user 2026-10-05:
-- "toggleable until we find the perfect way"). Client convars of client/hybrid.lua.

local D = gmodcraft.debug

local function addChoice(p, text, convar, choices)
	D.AddLabel(p, text)
	local c = vgui.Create("DComboBox", p)
	c:Dock(TOP)
	c:DockMargin(8, 2, 8, 0)
	local cv = GetConVar(convar)
	for i, label in ipairs(choices) do
		c:AddChoice(label, i - 1, cv and cv:GetInt() == i - 1)
	end
	c.OnSelect = function(_, _, _, value) RunConsoleCommand(convar, tostring(value)) end
	return c
end

local function addBinder(p, text, convar)
	D.AddLabel(p, text)
	local b = vgui.Create("DBinder", p)
	b:Dock(TOP)
	b:DockMargin(8, 2, 8, 0)
	b:SetTall(24)
	local cv = GetConVar(convar)
	b:SetValue(cv and cv:GetInt() or 0)
	b.OnChange = function(_, num) RunConsoleCommand(convar, tostring(num or 0)) end
	return b
end

D.RegisterPanel("hybrid", {
	title = "Hybrid",
	order = 5,
	icon = "icon16/gun.png",
	build = function(p)
		D.AddHeader(p, "GMod weapons in the Minecraft hotbar (hybrid mode)")
		D.AddLabel(p, "Hold a GMod weapon item: the mouse buttons and the reload key fire it; any other item: Minecraft as usual.")
		addChoice(p, "Use key (gmodcraft_use_mode)", "gmodcraft_use_mode", {
			"G = GMod use, E = Minecraft inventory (default)",
			"Context E: GMod use on doors, buttons, seats, vehicles; else the Minecraft inventory",
			"Swapped: E = GMod use, the Minecraft inventory on the key below",
		})
		addBinder(p, "Minecraft inventory key with the swapped use key (gmodcraft_inventory_key)", "gmodcraft_inventory_key")
		addBinder(p, "Reload key while a GMod weapon is held (gmodcraft_reload_key)", "gmodcraft_reload_key")
		addChoice(p, "Seated in a GMod vehicle (gmodcraft_vehicle_controls)", "gmodcraft_vehicle_controls", {
			"GMod's own controls (default)",
			"Minecraft's movement keys (W A S D, Space, Shift) drive it",
		})
		D.AddHeader(p, "GMod mode: Minecraft shortcuts (NONE = off)")
		addBinder(p, "Open the Minecraft inventory (gmodcraft_gmod_mc_inventory_key)", "gmodcraft_gmod_mc_inventory_key")
		addBinder(p, "Open the Minecraft chat (gmodcraft_gmod_mc_chat_key)", "gmodcraft_gmod_mc_chat_key")
	end,
})
