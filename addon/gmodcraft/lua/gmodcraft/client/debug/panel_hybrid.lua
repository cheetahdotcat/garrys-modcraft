-- Spawnmenu tab "Garry's Modcraft", Play group: "Hybrid" (H2 input toggles, user 2026-10-05:
-- "toggleable until we find the perfect way"). Client convars of client/hybrid.lua.

local D = gmodcraft.debug

D.Register{
	id = "hybrid",
	title = "Hybrid",
	group = "play",
	order = 30,
	icon = "icon16/gun.png",
	desc = "GMod weapons in the Minecraft hotbar, and Minecraft keys in GMod mode",
	build = function(p)
		local w = D.Section(p, "GMod weapons in the Minecraft hotbar")
		D.AddNote(w, "Hold a GMod weapon item: the mouse buttons and the reload key fire it; any other item: Minecraft as usual.")
		D.AddChoice(w, "Use key (gmodcraft_use_mode)", "gmodcraft_use_mode", {
			"G = GMod use, E = Minecraft inventory (default)",
			"Context E: GMod use on doors, buttons, seats, vehicles; else the Minecraft inventory",
			"Swapped: E = GMod use, the Minecraft inventory on the key below",
		})
		D.AddBinder(w, "Minecraft inventory key with the swapped use key", "gmodcraft_inventory_key")
		D.AddBinder(w, "Reload key while a GMod weapon is held", "gmodcraft_reload_key")
		local v = D.Section(p, "Vehicles")
		D.AddChoice(v, "Seated in a GMod vehicle (gmodcraft_vehicle_controls)", "gmodcraft_vehicle_controls", {
			"GMod's own controls (default)",
			"Minecraft's movement keys (W A S D, Space, Shift) drive it",
		})
		local g = D.Section(p, "GMod mode: Minecraft shortcuts (NONE = off)")
		D.AddBinder(g, "Open the Minecraft inventory", "gmodcraft_gmod_mc_inventory_key")
		D.AddBinder(g, "Open the Minecraft chat", "gmodcraft_gmod_mc_chat_key")
	end,
}
