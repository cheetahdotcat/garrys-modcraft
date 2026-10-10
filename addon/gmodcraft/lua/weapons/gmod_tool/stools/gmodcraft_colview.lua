-- Garry's Modcraft STool: Collision Viewer (P7b-2, everyone, client side). While this tool is out,
-- the collision Minecraft gets near you is drawn as wireframe: the map's triangles (white, terrain
-- green), GMod entities (magenta), Minecraft blocks (cyan, the server's block collision boxes) and
-- dug cells (orange). Radius and triangle budget in the panel; the HUD shows what it costs.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_colview.name"
TOOL.ClientConVar = { regions = "1", tris = "6000" }
TOOL.Information = { { name = "info" } }

function TOOL:LeftClick() return false end
function TOOL:RightClick() return false end

function TOOL:Holster()
	if CLIENT and gmodcraft.tools.view then gmodcraft.tools.view.Stop() end
end

if CLIENT then
	language.Add("tool.gmodcraft_colview.name", "Collision Viewer")
	language.Add("tool.gmodcraft_colview.desc", "See the collision Minecraft gets near you")
	language.Add("tool.gmodcraft_colview.0", "Hold it out: map triangles white/green, GMod entities magenta, MC blocks cyan, dug cells orange")

	function TOOL:DrawHUD()
		local lines = gmodcraft.tools.view.HudLines()
		local x, y = 24, ScrH() / 2 - 40
		draw.RoundedBox(4, x - 6, y - 4, 480, #lines * 16 + 8, Color(0, 0, 0, 190))
		for i, l in ipairs(lines) do draw.SimpleText(l, "DermaDefault", x, y + (i - 1) * 16, Color(230, 230, 230)) end
	end
end

function TOOL.BuildCPanel(panel)
	panel:Help("Draws the collision Minecraft gets near you while this tool is out.")
	panel:NumSlider("Radius (8-block regions)", "gmodcraft_colview_regions", 1, 2, 0)
	panel:NumSlider("Triangle budget", "gmodcraft_colview_tris", 500, 30000, 0)
end
