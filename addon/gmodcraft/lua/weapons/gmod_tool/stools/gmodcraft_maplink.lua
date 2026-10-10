-- Garry's Modcraft STool: Map Link (R2, protocol v25). LMB on a map entity (door, button, trigger,
-- relay, func_movelinear, light, env_sprite) selects it; LMB on a redstone bridge block then links
-- the two (saved with the block, re-paired when the map loads). RMB on a bridge: unlink. Reload:
-- drop the selection. Direction in the panel: auto (buttons / triggers / relays drive redstone,
-- doors / movelinear / lights / sprites are driven by it), or forced in / out. Admins only unless
-- gmodcraft_maplink_everyone 1. Works without Wiremod.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_maplink.name"
TOOL.ClientConVar = { mode = "auto" }
TOOL.Information = { { name = "left" }, { name = "right" }, { name = "reload" } }

local function MI() return gmodcraft.mapio end
local function T() return gmodcraft.tools end

-- The bridge a trace hit: its MC block (block collision box) or its Wiremod entity. -> x, y, z | nil
local function bridgeAt(tr)
	local e = tr.Entity
	if IsValid(e) and e:GetClass() == "gmod_wire_gmodcraft_bridge" and e.bridgeKey then
		local x, y, z = e.bridgeKey:match("^(%-?%d+),(%-?%d+),(%-?%d+)$")
		if x then return tonumber(x), tonumber(y), tonumber(z) end
	end
	if IsValid(tr.gmcBlocks) and gmodcraft.convert.slot.known then  -- the toolgun retargets blocks to the world
		local x, y, z = T().Cell(tr.HitPos - tr.HitNormal * 20)
		if MI().bridges[MI().Key(x, y, z)] then return x, y, z end
	end
	return nil
end

function TOOL:LeftClick(tr)
	if not tr.Hit then return false end
	if CLIENT then return true end
	local ply = self:GetOwner()
	local ok = T().Allowed(ply, "maplink")
	if not ok then T().Tell(ply, "Map Link: not allowed (admins only)") return false end
	local x, y, z = bridgeAt(tr)
	if x then
		local sel = MI().selection[ply]
		if not sel then T().Tell(ply, "Map Link: first click a map door, button, trigger, ... then the bridge block") return false end
		local mode = MI().ModeFor(sel.kind, self:GetClientInfo("mode"))
		if not mode then T().Tell(ply, string.format("Map Link: a %s can't go that way (%s)", sel.kind, self:GetClientInfo("mode"))) return false end
		local label = string.format("Map Link: %s '%s' %s bridge %d %d %d", sel.class, sel.name, mode == "in" and "->" or "<-", x, y, z)
		local sent, msg = T().Send(ply, "maplink", (gmodcraft.K or {}).AdminBridgeLink, label, { x + 0.5, y + 0.5, z + 0.5 }, sel.id, 0, MI().LinkText(sel.kind, mode, sel.name))
		if msg then T().Tell(ply, msg) end
		if sent then MI().selection[ply] = nil end
		return sent
	end
	local e = tr.Entity
	if not MI().KindOf(e) then
		-- triggers aren't solid to the tool trace: the first trigger the ray passes through
		for _, t in ipairs(ents.FindAlongRay(tr.StartPos, tr.HitPos)) do
			if MI().KindOf(t) == "trigger" and t:MapCreationID() >= 0 then e = t break end
		end
	end
	if not MI().KindOf(e) and IsValid(tr.gmcBlocks) then
		T().Tell(ply, "Map Link: that Minecraft block isn't a (known) redstone bridge")
		return false
	end
	local ok2, msg = MI().Select(ply, e)
	T().Tell(ply, msg)
	return ok2
end

function TOOL:RightClick(tr)
	if not tr.Hit then return false end
	if CLIENT then return true end
	local ply = self:GetOwner()
	local x, y, z = bridgeAt(tr)
	if not x then T().Tell(ply, "Map Link: aim at a redstone bridge block to unlink it") return false end
	local ok, msg = T().Send(ply, "maplink", (gmodcraft.K or {}).AdminBridgeLink, string.format("Map Link: unlink bridge %d %d %d", x, y, z), { x + 0.5, y + 0.5, z + 0.5 }, -1, 0, "")
	if msg then T().Tell(ply, msg) end
	return ok
end

function TOOL:Reload()
	if CLIENT then return true end
	MI().selection[self:GetOwner()] = nil
	T().Tell(self:GetOwner(), "Map Link: selection cleared")
	return false
end

if CLIENT then
	language.Add("tool.gmodcraft_maplink.name", "Map Link")
	language.Add("tool.gmodcraft_maplink.desc", "Link map doors, buttons and triggers to a redstone bridge block")
	language.Add("tool.gmodcraft_maplink.left", "Select a map entity, then click the bridge block to link them")
	language.Add("tool.gmodcraft_maplink.right", "Unlink the bridge block you aim at")
	language.Add("tool.gmodcraft_maplink.reload", "Clear the selection")
end

function TOOL.BuildCPanel(panel)
	panel:Help("Click a map door, button, trigger, relay, func_movelinear, light or env_sprite, then a redstone bridge block. No Wiremod needed.")
	local c = panel:ComboBox("Direction", "gmodcraft_maplink_mode")
	c:AddChoice("auto (buttons, triggers, relays -> redstone; redstone -> doors, lights)", "auto")
	c:AddChoice("map entity -> redstone (pressed / open / occupied powers the bridge)", "in")
	c:AddChoice("redstone -> map entity (a powered bridge opens / presses / turns on)", "out")
	panel:Help("The link is saved with the block and comes back when the map loads. Admins only unless the server sets gmodcraft_maplink_everyone 1.")
end
