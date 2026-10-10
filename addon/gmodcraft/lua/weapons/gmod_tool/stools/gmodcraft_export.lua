-- Garry's Modcraft STool: Structure Export (S2, protocol v38, admins only). LMB: first corner (the
-- Minecraft cell behind the face you aim at), RMB: second corner, Reload: export the box (at most 32
-- blocks per edge) as gmodcraft_cube props: into your AdvDupe2 clipboard + data/advdupe2/ when
-- AdvDupe2 is installed, else (or with "spawn" ticked) spawned at your aim point. Minecraft also
-- keeps a vanilla .nbt of it (<world>/generated/gmodcraft/structures/). See shared/structures.lua.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_export.name"
TOOL.ClientConVar = { spawn = "0" }
TOOL.Information = { { name = "left" }, { name = "right" }, { name = "reload" } }

local function T() return gmodcraft.tools end

local function corner(self, tr, which)
	if not tr.Hit then return false end
	if CLIENT then return true end
	local ply = self:GetOwner()
	if not T().Allowed(ply, "export") then T().Tell(ply, "Structure Export: not allowed (admins only)") return false end
	if not gmodcraft.convert.slot.known then T().Tell(ply, "Structure Export: the map's Minecraft area isn't known yet") return false end
	local x, y, z = T().Cell(tr.HitPos - tr.HitNormal * 20)
	ply:SetNW2Vector("gmc_export_" .. which, Vector(x, y, z))
	ply:SetNW2Bool("gmc_export_has_" .. which, true)
	return true
end

function TOOL:LeftClick(tr) return corner(self, tr, "a") end
function TOOL:RightClick(tr) return corner(self, tr, "b") end

function TOOL:Reload(tr)
	if CLIENT then return true end
	local ply = self:GetOwner()
	if not (ply:GetNW2Bool("gmc_export_has_a", false) and ply:GetNW2Bool("gmc_export_has_b", false)) then
		T().Tell(ply, "Structure Export: set both corners first (left and right click)")
		return false
	end
	local a, b = ply:GetNW2Vector("gmc_export_a"), ply:GetNW2Vector("gmc_export_b")
	local ok, msg = gmodcraft.structures.Export(ply, { a.x, a.y, a.z }, { b.x, b.y, b.z }, tr and tr.Hit and tr.HitPos or nil, self:GetClientNumber("spawn") == 1)
	if msg then T().Tell(ply, msg) end
	return ok
end

if CLIENT then
	language.Add("tool.gmodcraft_export.name", "Structure Export")
	language.Add("tool.gmodcraft_export.desc", "Turn a box of Minecraft blocks into a GMod dupe")
	language.Add("tool.gmodcraft_export.left", "First corner (the block you aim at)")
	language.Add("tool.gmodcraft_export.right", "Second corner")
	language.Add("tool.gmodcraft_export.reload", "Export the box")
end

function TOOL.BuildCPanel(panel)
	panel:Help("Admins only. Pick two corners of a box of Minecraft blocks (at most 32 per edge), then press Reload. "
		.. "Runs of the same block become one prop (40 units per block). With AdvDupe2 installed the dupe lands in your "
		.. "AdvDupe2 clipboard and in data/advdupe2/gmodcraft_export_<id>.txt; without it the props spawn at your aim point.")
	panel:CheckBox("Also spawn it at my aim point", "gmodcraft_export_spawn")
end
