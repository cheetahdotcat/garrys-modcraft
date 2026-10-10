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
-- P2: with the eye in open space, entities whose centre is in solid (sank into a dug hole) are drawn
-- here too (SE.DrawBuried, below).

local SE = gmodcraft.solidEnts or {}
gmodcraft.solidEnts = SE

local cvOn = CreateClientConVar("gmodcraft_ents_in_solid", "1", true, false,
	"Garry's Modcraft: draw the nearby (transmitted) NPCs, props and players the engine would cull while the view is inside map geometry, and those sunk into map geometry (dug holes, lit from the hole mouth) (1, default: not in the underground view, gmodcraft_underground_view), 2 also in the underground view, 0 never")

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

-- P2 lighting: Source lights a model from its origin. A centre inside the brushes samples black
-- (no lightmap, no ambient cube there), so a prop in a dug hole draws dark or black. For those our
-- draw replaces the engine's lighting with the light at the open point straight above (the hole's
-- mouth, found like server/pvs.lua's proxy: a brush-only trace up to LIGHT_UP), as a six-sided
-- ambient cube from render.ComputeLighting, cached per entity for LIGHT_PERIOD. Opaque models use
-- a nearer-or-equal depth test, so our redraw of one the engine drew dark too lands on top.
SE.LIGHT_UP = 2048
SE.LIGHT_PERIOD = 0.25
SE.light = SE.light or setmetatable({}, { __mode = "k" })
local LIGHT_DIRS = {  -- BOX_FRONT .. BOX_BOTTOM: +x, -x, +y, -y, +z, -z
	Vector(1, 0, 0), Vector(-1, 0, 0), Vector(0, 1, 0), Vector(0, -1, 0), Vector(0, 0, 1), Vector(0, 0, -1),
}

local function centre(e) return e.WorldSpaceCenter and e:WorldSpaceCenter() or e:GetPos() end

-- The point to light a model at whose centre `c` is in solid: just above where a trace straight up
-- leaves the brushes, or nil (centre in open space, or no way out within range).
function SE.LightPoint(c, contentsFn, traceFn)
	contentsFn, traceFn = contentsFn or util.PointContents, traceFn or util.TraceLine
	if bit.band(contentsFn(c), SOLID) == 0 then return nil end
	local tr = traceFn({ start = c, endpos = c + Vector(0, 0, SE.LIGHT_UP), mask = MASK_SOLID_BRUSHONLY })
	if not (tr and tr.StartSolid and tr.FractionLeftSolid and tr.FractionLeftSolid > 0 and tr.FractionLeftSolid < 1) then return nil end
	local p = c + Vector(0, 0, SE.LIGHT_UP * tr.FractionLeftSolid + 8)
	if bit.band(contentsFn(p), SOLID) ~= 0 then return nil end
	return p
end

-- The ambient cube for `e` (six colours) or false (engine lighting is fine), cached.
function SE.LightFor(e, now)
	local l = SE.light[e]
	if l and now - l.at < SE.LIGHT_PERIOD then return l.cube end
	local cube = false
	local p = SE.LightPoint(centre(e))
	if p then
		cube = {}
		for i, n in ipairs(LIGHT_DIRS) do cube[i] = render.ComputeLighting(p, n) end
	end
	SE.light[e] = { at = now, cube = cube }
	return cube
end

-- Draws `e`, with the hole mouth's light when its centre is in solid.
function SE.DrawLit(e, now)
	local cube = SE.LightFor(e, now)
	if not cube then
		e:DrawModel()
		return false
	end
	render.SuppressEngineLighting(true)
	for i = 1, 6 do
		local c = cube[i]
		render.SetModelLighting(i - 1, c.x, c.y, c.z)
	end
	e:DrawModel()
	render.SuppressEngineLighting(false)
	return true
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
	local now = CurTime()
	for i = 1, n do SE.DrawLit(list[i].e, now) end
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

-- P2: entities whose centre is in solid while the eye is in open space (a prop that sank into a dug
-- hole: server/pvs.lua keeps it sent). The engine files renderables by the leaves their bounds touch
-- and draws no solid leaf, so a prop fully inside the brushes is never drawn though it is here.
-- Those are drawn by us too. The list is rebuilt every BURIED_PERIOD (one PointContents per nearby
-- wanted entity); each frame only validity and dormancy are checked. Disjoint from SE.Draw (eye in
-- solid), so no entity is drawn twice by us; one that pokes out of the brushes may be drawn by the
-- engine too: opaque only, same depth, only cost.
SE.BURIED_PERIOD = 0.25
SE.buried = SE.buried or {}
SE.stats.buried = SE.stats.buried or 0
local nextBuried = 0

-- The nearby non-dormant wanted entities whose centre is in solid, nearest first, at most SE.MAX
-- (contentsFn defaults to util.PointContents; tests pass mocks).
function SE.SelectBuried(eye, me, cands, contentsFn)
	contentsFn = contentsFn or util.PointContents
	local list = {}
	for _, e in ipairs(cands) do
		if IsValid(e) and e ~= me and not e:IsDormant() and SE.Wanted(e, me) then
			local p = centre(e)
			if bit.band(contentsFn(p), SOLID) ~= 0 then list[#list + 1] = { e = e, d = p:DistToSqr(eye) } end
		end
	end
	table.sort(list, function(a, b) return a.d < b.d end)
	local out = {}
	for i = 1, math.min(#list, SE.MAX) do out[i] = list[i].e end
	return out
end

function SE.DrawBuried(eye, me, now)
	if now >= nextBuried then
		nextBuried = now + SE.BURIED_PERIOD
		SE.buried = SE.SelectBuried(eye, me, ents.FindInSphere(eye, SE.RADIUS))
	end
	local n = 0
	for _, e in ipairs(SE.buried) do
		if IsValid(e) and not e:IsDormant() and not e:GetNoDraw() then
			SE.DrawLit(e, now)
			n = n + 1
		end
	end
	SE.stats.buried = n
	return n
end

hook.Add("PostDrawOpaqueRenderables", "gmodcraft_solid_ents", function(depth, skybox, sky3d)
	if depth or skybox or sky3d or not cvOn:GetBool() then return end
	local B = gmodcraft.blocks
	if B and (B.inSkyView or (B.SkipView and B.SkipView(depth, skybox, sky3d))) then return end  -- our 3D sky, reflections
	local eye = EyePos()
	if bit.band(util.PointContents(eye), SOLID) == 0 then
		lastLogged = nil
		SE.DrawBuried(eye, LocalPlayer(), CurTime())
		return
	end
	local UG = gmodcraft.underground
	if UG and UG.Active() and cvOn:GetInt() ~= 2 then
		SE.stats.skipped = (SE.stats.skipped or 0) + 1
		return
	end
	SE.Draw(eye, LocalPlayer())
end)
