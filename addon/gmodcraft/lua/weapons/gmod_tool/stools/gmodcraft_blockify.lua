-- Garry's Modcraft STool: Blockify (S2, protocol v38, admins only). LMB: select / unselect the prop
-- you aim at (with Use held: its whole constrained contraption). RMB: blockify the selection (or,
-- with nothing selected, the aimed contraption): voxelized at 1 block = 40 units, materials mapped
-- to Minecraft blocks, the props removed and the blocks placed in Minecraft (air / replaceable cells
-- only). Undo (Z) brings the props back and breaks the placed blocks. Reload: clear the selection.
-- See shared/structures.lua.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_blockify.name"
TOOL.Information = { { name = "left" }, { name = "left_use" }, { name = "right" }, { name = "reload" } }

local function T() return gmodcraft.tools end
local function S() return gmodcraft.structures end

local function contraption(ent)
	local out = {}
	if constraint and constraint.GetAllConstrainedEntities then
		for _, e in pairs(constraint.GetAllConstrainedEntities(ent) or {}) do out[#out + 1] = e end
	end
	if #out == 0 then out[1] = ent end
	return out
end

local function setSel(self, ent, on)
	self.gmcSel = self.gmcSel or {}
	self.gmcSel[ent] = on or nil
	ent:SetNW2Bool("gmc_blockify_sel", on and true or false)
	ent:SetNW2Entity("gmc_blockify_by", on and self:GetOwner() or NULL)
end

local function clear(self)
	for e in pairs(self.gmcSel or {}) do if IsValid(e) then setSel(self, e, false) end end
	self.gmcSel = {}
end

local function selection(self)
	local out = {}
	for e in pairs(self.gmcSel or {}) do if IsValid(e) then out[#out + 1] = e end end
	return out
end

function TOOL:LeftClick(tr)
	if not IsValid(tr.Entity) then return false end
	if CLIENT then return true end
	local ply = self:GetOwner()
	if not T().Allowed(ply, "blockify") then T().Tell(ply, "Blockify: not allowed (admins only)") return false end
	if not S().Blockifiable(tr.Entity) then T().Tell(ply, "Blockify: only props") return false end
	local list = ply:KeyDown(IN_USE) and contraption(tr.Entity) or { tr.Entity }
	local on = not (self.gmcSel and self.gmcSel[tr.Entity])
	for _, e in ipairs(list) do if S().Blockifiable(e) then setSel(self, e, on) end end
	return true
end

function TOOL:RightClick(tr)
	if CLIENT then return true end
	local ply = self:GetOwner()
	local list = selection(self)
	if #list == 0 and IsValid(tr.Entity) then list = contraption(tr.Entity) end
	local ok, msg = S().Blockify(ply, list)
	if ok then clear(self) end
	if msg then T().Tell(ply, msg) end
	return ok
end

function TOOL:Reload()
	if CLIENT then return true end
	clear(self)
	return true
end

function TOOL:Holster()
	if SERVER then clear(self) end
end

if CLIENT then
	language.Add("tool.gmodcraft_blockify.name", "Blockify")
	language.Add("tool.gmodcraft_blockify.desc", "Turn GMod props into Minecraft blocks")
	language.Add("tool.gmodcraft_blockify.left", "Select / unselect a prop")
	language.Add("tool.gmodcraft_blockify.left_use", "Select / unselect its whole contraption")
	language.Add("tool.gmodcraft_blockify.right", "Blockify the selection (or the aimed contraption)")
	language.Add("tool.gmodcraft_blockify.reload", "Clear the selection")
end

function TOOL.BuildCPanel(panel)
	panel:Help("Admins only. Props become Minecraft blocks: 1 block = 40 units, a block wherever a prop reaches into the cell; "
		.. "the block comes from the prop's material (wood -> planks, metal -> copper, glass -> glass, concrete -> cobblestone, else stone; "
		.. "Minecraft structure props keep their own block). Only air / replaceable cells are filled. At most 4096 blocks; undo with Z.")
end
