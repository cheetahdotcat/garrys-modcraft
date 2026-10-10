-- Entities while the eye is inside map geometry (P5d), client side.
--
-- With the view origin in a solid brush (digging into the map) the user sees the world and our sky
-- (client/blocks.lua) but no NPCs or props on the surface. Two possible causes, which can't be told
-- apart without a live run:
--   a) the client culls them: the entities are here (not dormant) but not drawn;
--   b) the server stopped sending them (no PVS from a solid view origin): they are dormant here.
-- This file handles (a) and tells which one it is:
--   * while the eye is in CONTENTS_SOLID it draws the nearby opaque entities that are NOT dormant
--     itself, after the opaque pass (DrawModel). If the engine drew them too, the second draw lands
--     on exactly the same depth (opaque: no visible difference, only cost), so translucent ones are
--     left alone (they would blend twice);
--   * it counts, per frame, the nearby entities that are dormant / drawn by us
--     (gmodcraft.solidEnts.stats; the Render debug panel; a debug log line when it changes).
-- Many dormant ones while in solid = cause (b): the server side, gmodcraft_pvs_in_solid 1
-- (server/pvs.lua, on by default since the LV1 live run confirmed it).

local SE = gmodcraft.solidEnts or {}
gmodcraft.solidEnts = SE

local cvOn = CreateClientConVar("gmodcraft_ents_in_solid", "1", false, false,
	"Garry's Modcraft: while the view is inside map geometry, draw the nearby (transmitted) NPCs, props and players the engine would cull (1, default: not in the underground view, gmodcraft_underground_view), 2 also in the underground view, 0 never")

SE.RADIUS = 3000   -- units: entities this close to the eye are considered
SE.MAX = 64        -- at most this many drawn per frame (nearest first)
SE.stats = SE.stats or { frames = 0, candidates = 0, dormant = 0, drawn = 0, lastMs = 0 }

local SOLID = CONTENTS_SOLID or 1

-- U1: in the underground view (client/underground.lua) the engine's own entity pass is the single
-- draw: it runs after the cave clear, and with no vis from a solid leaf every leaf's entities go. So
-- the redraw is skipped there (gmodcraft_ents_in_solid 2 keeps it, for a live check: a prop or NPC
-- that vanishes underground with 1 but shows with 2 means the engine does cull it). SE.stats.skipped
-- counts those frames.

-- Should `e` be drawn by us? Pure checks on the entity (the tests pass mocks).
function SE.Wanted(e, me)
	if e == me or e:GetNoDraw() then return false end
	if e.IsEffectActive and e:IsEffectActive(EF_NODRAW or 32) then return false end
	local cls = e:GetClass() or ""
	if cls:sub(1, 10) == "gmodcraft_" or cls == "viewmodel" or cls:find("^predicted_") then return false end
	if not e:GetModel() or e:GetModel() == "" then return false end
	if IsValid(e:GetOwner()) and e.IsWeapon and e:IsWeapon() then return false end  -- held weapons draw with their owner
	if e.GetRenderGroup and e:GetRenderGroup() ~= (RENDERGROUP_OPAQUE or 7) then return false end
	return e.DrawModel ~= nil
end

local lastLogged
function SE.Draw(eye, me)
	local t0 = SysTime()
	local st = SE.stats
	local list = {}
	local dormant = 0
	for _, e in ipairs(ents.FindInSphere(eye, SE.RADIUS)) do
		if IsValid(e) and e ~= me then
			if e:IsDormant() then
				dormant = dormant + 1
			elseif SE.Wanted(e, me) then
				list[#list + 1] = { e = e, d = e:GetPos():DistToSqr(eye) }
			end
		end
	end
	table.sort(list, function(a, b) return a.d < b.d end)
	local n = math.min(#list, SE.MAX)
	for i = 1, n do list[i].e:DrawModel() end
	st.frames, st.candidates, st.dormant, st.drawn = st.frames + 1, #list, dormant, n
	st.lastMs = (SysTime() - t0) * 1000
	gmodcraft.PerfAdd("cl entities in solid", st.lastMs)
	local key = (dormant > 0 and "dormant" or "none") .. "/" .. (n > 0 and "drawn" or "none")
	if key ~= lastLogged then
		lastLogged = key
		gmodcraft.Log("render", "eye in solid: %d nearby entities drawn by us, %d dormant (dormant ones mean the server doesn't send them: gmodcraft_pvs_in_solid 1)",
			n, dormant)
	end
	return n, dormant
end

hook.Add("PostDrawOpaqueRenderables", "gmodcraft_solid_ents", function(depth, skybox, sky3d)
	if depth or skybox or sky3d or not cvOn:GetBool() then return end
	local B = gmodcraft.blocks
	if B and (B.inSkyView or (B.SkipView and B.SkipView(depth, skybox, sky3d))) then return end  -- our 3D sky, reflections
	local eye = EyePos()
	if bit.band(util.PointContents(eye), SOLID) == 0 then
		lastLogged = nil
		return
	end
	local UG = gmodcraft.underground
	if UG and UG.Active() and cvOn:GetInt() ~= 2 then
		SE.stats.skipped = (SE.stats.skipped or 0) + 1
		return
	end
	SE.Draw(eye, LocalPlayer())
end)
