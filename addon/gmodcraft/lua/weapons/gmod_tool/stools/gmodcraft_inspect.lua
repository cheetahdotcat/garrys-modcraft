-- Garry's Modcraft STool: Inspector (P7b-2, everyone, read-only, client side). Aim: a HUD panel with
-- the MC block coordinate, what the client knows of the MC block (solid or not: the client has no
-- block states), whether the cell is dug, the collision source (static map / GMod entity / MC block
-- / none), the GMod surface (surface prop, material, texture, contents, entity) and the slot and
-- world id. LMB copies a one-line summary to the clipboard.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_inspect.name"
TOOL.Information = { { name = "left" } }

local function T() return gmodcraft.tools end

function TOOL:LeftClick(tr)
	if SERVER then return false end
	if not IsFirstTimePredicted() then return false end
	local _, summary = T().FormatInspect(T().Inspect(tr))
	SetClipboardText(summary)
	chat.AddText(Color(120, 200, 255), "[Garry's Modcraft] copied: ", color_white, summary)
	return false
end

function TOOL:RightClick() return false end

if CLIENT then
	language.Add("tool.gmodcraft_inspect.name", "Inspector")
	language.Add("tool.gmodcraft_inspect.desc", "What Minecraft and GMod know about the point you aim at")
	language.Add("tool.gmodcraft_inspect.left", "Copy a one-line summary")

	local last, lines = 0, {}
	function TOOL:DrawHUD()
		if RealTime() - last > 0.1 then
			last = RealTime()
			lines = T().FormatInspect(T().Inspect(LocalPlayer():GetEyeTrace()))
		end
		local x, y = ScrW() / 2 + 40, ScrH() / 2 - 60
		surface.SetFont("DermaDefault")
		local w = 0
		for _, l in ipairs(lines) do w = math.max(w, (surface.GetTextSize(l))) end
		draw.RoundedBox(4, x - 6, y - 4, w + 12, #lines * 16 + 8, Color(0, 0, 0, 190))
		for i, l in ipairs(lines) do draw.SimpleText(l, "DermaDefault", x, y + (i - 1) * 16, Color(230, 230, 230)) end
	end
end

function TOOL.BuildCPanel(panel)
	panel:Help("Aim at anything: the HUD shows its Minecraft cell, dug state, collision source and GMod surface. Left click copies a one-line summary.")
end
