-- Garry's Modcraft STool: Terrain Repair (P7b-2). LMB: the dug cells within the radius of the hit
-- point become solid GMod geometry again (for Minecraft; the hole stencil and walls go too). RMB:
-- every dug cell of the hit point's 16 x 16 chunk column. Reload: undo your last repair (5 kept,
-- on the Minecraft server, lost when it restarts). Minecraft blocks in dug cells: the terrain that
-- digging revealed goes with the repair (and comes back on undo); anything built there keeps its
-- cell dug and is counted. Admins only unless gmodcraft_tool_repair_everyone 1.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_repair.name"
TOOL.ClientConVar = { radius = "3" }
TOOL.Information = { { name = "left" }, { name = "right" }, { name = "reload" } }

local function T() return gmodcraft.tools end

local function send(self, tr, code, label, a)
	if CLIENT then return true end
	local ply = self:GetOwner()
	local K = gmodcraft.K or {}
	local C = gmodcraft.convert
	if not C.slot.known then T().Tell(ply, label .. ": the map's Minecraft area isn't known yet") return false end
	local x, y, z = C.ToMc(tr.HitPos - tr.HitNormal * 4) -- just inside what was hit
	local ok, msg = T().Send(ply, "repair", code, label, { x, y, z }, a)
	if msg then T().Tell(ply, msg) end
	return ok
end

function TOOL:LeftClick(tr)
	if not tr.Hit then return false end
	return send(self, tr, (gmodcraft.K or {}).AdminRepairRadius, "Terrain Repair", T().Radius(self:GetClientNumber("radius")))
end

function TOOL:RightClick(tr)
	if not tr.Hit then return false end
	return send(self, tr, (gmodcraft.K or {}).AdminRepairColumn, "Terrain Repair (chunk column)", 0)
end

function TOOL:Reload()
	if CLIENT then return true end
	local ply = self:GetOwner()
	local ok, msg = T().Send(ply, "repair", (gmodcraft.K or {}).AdminRepairUndo, "Terrain Repair undo", nil, 0)
	if msg then T().Tell(ply, msg) end
	return ok
end

if CLIENT then
	language.Add("tool.gmodcraft_repair.name", "Terrain Repair")
	language.Add("tool.gmodcraft_repair.desc", "Make dug-out GMod geometry solid again for Minecraft")
	language.Add("tool.gmodcraft_repair.left", "Repair the dug cells within the radius")
	language.Add("tool.gmodcraft_repair.right", "Repair every dug cell of this 16x16 chunk column")
	language.Add("tool.gmodcraft_repair.reload", "Undo your last repair")
end

function TOOL.BuildCPanel(panel)
	panel:Help("Dug cells become solid GMod geometry again for Minecraft. Terrain blocks the digging revealed go with them; cells with blocks someone built stay dug.")
	panel:NumSlider("Radius (blocks)", "gmodcraft_repair_radius", 1, 8, 0)
	panel:Help("Admins only unless the server sets gmodcraft_tool_repair_everyone 1. Undo keeps your last 5 repairs (until Minecraft restarts).")
end
