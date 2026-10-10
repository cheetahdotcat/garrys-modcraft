-- Garry's Modcraft STool: Block Tool (P7b-2). LMB: place the chosen Minecraft block on the hit face.
-- RMB: break the targeted Minecraft block (only MC blocks: the server's block collision boxes;
-- non-solid MC blocks like torches and rails have none and can't be targeted; GMod geometry is never
-- touched). Reload: undo your last change (10 kept, on the Minecraft server). Goes through
-- Minecraft's normal setBlock, logged with your SteamID. Liquids only with "allow liquids" (admins);
-- operator blocks never. Admins only unless gmodcraft_tool_blocks_everyone 1.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_blocks.name"
TOOL.ClientConVar = { block = "minecraft:stone", liquids = "0" }
TOOL.Information = { { name = "left" }, { name = "right" }, { name = "reload" } }

local function T() return gmodcraft.tools end

function TOOL:LeftClick(tr)
	if not tr.Hit then return false end
	if CLIENT then return true end
	local ply = self:GetOwner()
	local K = gmodcraft.K or {}
	if not gmodcraft.convert.slot.known then T().Tell(ply, "Block Tool: the map's Minecraft area isn't known yet") return false end
	local block = self:GetClientInfo("block")
	local liquids = self:GetClientNumber("liquids") == 1 and T().IsAdmin(ply)  -- "allow liquids" is an admin's choice
	local verdict = T().CheckBlock(block, liquids)
	if verdict ~= "ok" then
		T().Tell(ply, "Block Tool: " .. (verdict == "liquid" and "liquids only with 'allow liquids' ticked (admins)" or verdict == "operator"
			and "operator blocks can't be placed" or ("not a block id: " .. block)))
		return false
	end
	local x, y, z = T().Cell(tr.HitPos + tr.HitNormal * 20) -- the cell in front of the hit face
	local ok, msg = T().Send(ply, "blocks", K.AdminBlockPlace, "Block Tool", { x + 0.5, y + 0.5, z + 0.5 }, 0, liquids and K.AdminAllowLiquid or 0, block)
	if msg then T().Tell(ply, msg) end
	return ok
end

function TOOL:RightClick(tr)
	if not tr.Hit then return false end
	if CLIENT then return true end
	local ply = self:GetOwner()
	if not IsValid(tr.gmcBlocks) then  -- the block entity (the toolgun retargets it to the world)
		T().Tell(ply, "Block Tool: that is " .. (tr.HitWorld and "GMod geometry" or "a GMod entity") .. ", not a Minecraft block")
		return false
	end
	local x, y, z = T().Cell(tr.HitPos - tr.HitNormal * 20) -- the cell behind the hit face
	local ok, msg = T().Send(ply, "blocks", (gmodcraft.K or {}).AdminBlockBreak, "Block Tool", { x + 0.5, y + 0.5, z + 0.5 }, 0)
	if msg then T().Tell(ply, msg) end
	return ok
end

function TOOL:Reload()
	if CLIENT then return true end
	local ply = self:GetOwner()
	local ok, msg = T().Send(ply, "blocks", (gmodcraft.K or {}).AdminBlockUndo, "Block Tool undo", nil, 0)
	if msg then T().Tell(ply, msg) end
	return ok
end

if CLIENT then
	language.Add("tool.gmodcraft_blocks.name", "Block Tool")
	language.Add("tool.gmodcraft_blocks.desc", "Place and break Minecraft blocks from GMod")
	language.Add("tool.gmodcraft_blocks.left", "Place the block on the face you aim at")
	language.Add("tool.gmodcraft_blocks.right", "Break the Minecraft block you aim at")
	language.Add("tool.gmodcraft_blocks.reload", "Undo your last change")
end

function TOOL.BuildCPanel(panel)
	panel:Help("Place and break Minecraft blocks. Admins only unless the server sets gmodcraft_tool_blocks_everyone 1.")
	panel:TextEntry("Block id (with [properties])", "gmodcraft_blocks_block")
	-- The admin-only blocks (tnt) are always listed, marked: this panel is built once, often before the
	-- player's usergroup arrives. Non-admins never place them (tools.lua EVERYONE_DENY, fabric BlockRules).
	local function add(id, suffix)
		local b = panel:Button((id:gsub("^minecraft:", "")) .. (suffix or ""))
		b.DoClick = function() RunConsoleCommand("gmodcraft_blocks_block", id) end  -- hygiene: ok (the tool's own client convar)
	end
	for _, id in ipairs(gmodcraft.tools.PALETTE) do add(id) end
	for _, id in ipairs(gmodcraft.tools.PALETTE_ADMIN) do add(id, " (admins)") end
	panel:CheckBox("Allow liquids (admins)", "gmodcraft_blocks_liquids")
	panel:Help("Torches, rails and other non-solid Minecraft blocks can't be targeted for breaking. Undo keeps your last 10 changes.")
end
