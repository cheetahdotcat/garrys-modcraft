-- Spawnmenu tab "Garry's Modcraft", Play group: "Settings". The options a player sets for themselves
-- (client convars), in sections. The page is built from the settings registry (debug/init.lua), so a
-- feature adds its option with one line, here or from its own file:
--
--   gmodcraft.debug.AddSetting("holes", { kind = "check", convar = "gmodcraft_x", text = "..." })
--
-- Rows whose convar doesn't exist are left out; a section without rows is not shown.

local D = gmodcraft.debug
local S = D.AddSetting

D.SettingsSection("client", "Minecraft client", 10)
S("client", { convar = "gmodcraft_autostart", text = "Start Minecraft when a map loads (if it isn't running)" })
S("client", { convar = "gmodcraft_loading_screen", text = "Loading screen / corner indicator while Minecraft starts or reconnects" })

D.SettingsSection("view", "View and controls", 20)
S("view", { convar = "gmodcraft_camera", text = "Camera follows Minecraft's eye (off: GMod's own camera)" })
S("view", { convar = "gmodcraft_overlay", text = "Draw Minecraft's HUD and hand" })
S("view", { kind = "slider", convar = "gmodcraft_look_scale", text = "Mouse look factor (1 = Minecraft's own feel)", min = 0.1, max = 4, decimals = 2 })

-- Sky & light: the sun / sky sync's client options go here (the server-side sync rules and the
-- Minecraft-sky server default live on the Server page).
D.SettingsSection("sky", "Sky & light", 30)
S("sky", { convar = "gmodcraft_mc_sky", text = "Minecraft sky instead of the map's skybox (time of day, sun, moon, stars, rain)" })
S("sky", { convar = "gmodcraft_lights", text = "Minecraft torches and lava light the GMod map (costs ~10 ms per frame)" })

-- Holes: how holes Minecraft digs into the map look.
D.SettingsSection("holes", "Holes", 40)
S("holes", { kind = "choice", convar = "gmodcraft_holes", text = "Holes dug into the map", choices = {
	"Off",
	"See-through: the hole walls show (default)",
	"Dark translucent cap over dug cells (fallback)",
} })
S("holes", { convar = "gmodcraft_hole_hide", text = "Hide the dug parts of map faces in the engine (holes through walls show what is behind)" })
S("holes", { kind = "choice", convar = "gmodcraft_hole_crust", text = "Map surface on the hole's rim (needs the option above)", choices = {
	"Off: Minecraft blocks right up to the cut",
	"The slab's thickness, up to ~1 block (default)",
	"The slab's full thickness",
} })
S("holes", { convar = "gmodcraft_dig_cut_brush", text = "Experimental: cut only walls (floors and ceilings next to a hole stay)" })

D.SettingsSection("underground", "Underground (the view inside dug tunnels)", 50)
S("underground", { kind = "choice", convar = "gmodcraft_underground_view", text = "View inside the map's brushes", choices = {
	"The map and its sky as usual (default)",
	"Minecraft only: cave colour and fog",
	"Minecraft only, the map's sky still fills the rest",
} })
S("underground", { convar = "gmodcraft_sky_in_solid", text = "Draw the map's sky where nothing else draws (else black)" })
S("underground", { convar = "gmodcraft_sky_in_solid_3d", text = "... with the 3D skybox, not only the 2D sky" })
S("underground", { kind = "choice", convar = "gmodcraft_ents_in_solid", text = "NPCs, props and players near you", choices = {
	"Never drawn by Garry's Modcraft",
	"Drawn (not in the Minecraft-only view) (default)",
	"Drawn, also in the Minecraft-only view",
} })

D.SettingsSection("draw", "Minecraft blocks and entities", 60)
S("draw", { convar = "gmodcraft_blocks", text = "Draw Minecraft's blocks" })
S("draw", { convar = "gmodcraft_entities", text = "Draw Minecraft's entities, particles, items and the F5 model" })
S("draw", { convar = "gmodcraft_entities_light", text = "GMod's light on Minecraft's entities" })
S("draw", { convar = "gmodcraft_entities_outline", text = "Outline of the block Minecraft targets" })
S("draw", { convar = "gmodcraft_entities_shadows", text = "Shadow blobs under Minecraft's players and mobs" })

D.Register{
	id = "settings",
	title = "Settings",
	group = "play",
	order = 10,
	icon = "icon16/cog.png",
	desc = "Your own options (this client); hover a setting for details",
	build = function(p)
		for _, sec in ipairs(D.SettingsSections()) do
			local live = {}
			for _, r in ipairs(sec.rows) do if GetConVar(r.convar) then live[#live + 1] = r end end
			if #live > 0 then
				local body = D.Section(p, sec.title, sec.collapsed)
				if sec.note then D.AddNote(body, sec.note) end
				for _, r in ipairs(live) do D.AddSettingRow(body, r) end
			end
		end
		D.AddNote(p, "Hybrid mode keys and vehicle controls: the Hybrid page.")
	end,
}
