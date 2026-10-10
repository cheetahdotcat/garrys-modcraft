-- Garry's Modcraft STool: Resync (P7b-2, admins). LMB: the Minecraft server re-sends its block
-- sections within 16 blocks of the hit point, and the map collision is streamed again. RMB: every
-- loaded section (the whole slot). Rate-limited per player (2 s near, 10 s whole). The map
-- collision resend is all-or-nothing in the module, so near and whole re-send all of it.
TOOL.Category = "Garry's Modcraft"
TOOL.Name = "#tool.gmodcraft_resync.name"
TOOL.Information = { { name = "left" }, { name = "right" } }

local function T() return gmodcraft.tools end

local function go(self, tr, whole)
	if CLIENT then return true end
	local ply = self:GetOwner()
	local C = gmodcraft.convert
	if not whole and not C.slot.known then T().Tell(ply, "Resync: the map's Minecraft area isn't known yet") return false end
	local pos
	if tr and tr.Hit and C.slot.known then
		local x, y, z = C.ToMc(tr.HitPos)
		pos = { x, y, z }
	end
	local ok, msg = T().Resync(ply, whole, pos)
	if msg then T().Tell(ply, msg) end
	return ok
end

function TOOL:LeftClick(tr) return tr.Hit and go(self, tr, false) or false end
function TOOL:RightClick(tr) return go(self, tr, true) end

if CLIENT then
	language.Add("tool.gmodcraft_resync.name", "Resync")
	language.Add("tool.gmodcraft_resync.desc", "Send Minecraft's blocks and the map collision again")
	language.Add("tool.gmodcraft_resync.left", "Resync around the hit point")
	language.Add("tool.gmodcraft_resync.right", "Resync the whole map")
end

function TOOL.BuildCPanel(panel)
	panel:Help("Admins: when collision or Minecraft blocks look out of date, resync them. Left: around where you aim (16 blocks). Right: the whole map. Rate-limited.")
end
