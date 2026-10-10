-- Minecraft's per-frame things drawn in GMod's own frame (P3c, docs/DESIGN.md section 7): the F5
-- player model, mobs, block entities (chests, beds, ...), particles, dropped items (sprites and
-- spinning block cubes), arrows, tridents and mining cracks. The gmcl module keeps what the render
-- ring and the WorldEntities region send, bakes it once per frame (EntitiesPrepare) with the
-- blocks' bake (Minecraft's light word times GMod's light S, face shade) and draws it through the
-- material system's dynamic mesh (module/source/entities.cpp). This file makes the materials,
-- samples GMod's light where the module asks, and draws the block outline and the shadow blobs.
--
-- Materials (UnlitGeneric, $nocull: item sprites, arrow fins and open models are single-sided):
--   gmodcraft_ent_cutout_<texture>       $vertexcolor, $alphatest .1   (opaque + cutout)
--   gmodcraft_ent_translucent_<texture>  $vertexcolor $vertexalpha $translucent (particles, cracks)
-- one pair on the block/item atlas (texture 0) and one per entity texture Minecraft sent.
--
-- GMod's light: S per 4x4x4-block cell, sampled with the blocks' LightAt (skips brushes) where the
-- module first needed it, refreshed every second while used; unknown cells use S at the eye.
-- Per frame this file allocates nothing of its own (tables are reused).

local E = gmodcraft.entities or {}
gmodcraft.entities = E
local CL = gmodcraft.clientLink
local C = gmodcraft.convert
local B = gmodcraft.blocks

-- Archived: the Settings page options persist in client.vdf.
local cvDraw = CreateClientConVar("gmodcraft_entities", "1", true, false, "Garry's Modcraft: draw Minecraft's entities, particles, dropped items and the F5 player model")
local cvLight = CreateClientConVar("gmodcraft_entities_light", "1", true, false, "Garry's Modcraft: darken Minecraft's entities by GMod's light at their place")
local cvOutline = CreateClientConVar("gmodcraft_entities_outline", "1", true, false, "Garry's Modcraft: draw the outline of the block Minecraft targets")
local cvShadows = CreateClientConVar("gmodcraft_entities_shadows", "1", true, false, "Garry's Modcraft: draw soft shadows under Minecraft's players and mobs")

E.mats = E.mats or {}  -- texture id -> { name, cutout, translucent } (kept across a Lua autorefresh)
local mats = E.mats
E.stats = E.stats or { lightSamples = 0 }

local function makePair(tag, tex)
	local cut = CreateMaterial("gmodcraft_ent_cutout_" .. tag, "UnlitGeneric", {
		["$basetexture"] = tex, ["$vertexcolor"] = 1, ["$alphatest"] = 1, ["$alphatestreference"] = 0.1, ["$nocull"] = 1,
	})
	local tr = CreateMaterial("gmodcraft_ent_translucent_" .. tag, "UnlitGeneric", {
		["$basetexture"] = tex, ["$vertexcolor"] = 1, ["$vertexalpha"] = 1, ["$translucent"] = 1, ["$nocull"] = 1,
	})
	return cut, tr
end

local function setPair(id, tex)
	local m = mats[id]
	if m and m.name == tex then return end
	local cut, tr = makePair(tex:gsub("[^%w]", "_"), tex)
	mats[id] = { name = tex, cutout = cut, translucent = tr }
	gmodcraft.EntitiesSetMaterials(id, cut, tr)
	gmodcraft.Log("view", "entity materials for texture %d on %s", id, tex)
end

local texGen
local function syncMaterials()
	local atlas = gmodcraft.BlocksAtlas()
	if atlas then setPair(0, atlas) end
	local gen, list = gmodcraft.EntitiesTextures(texGen)
	if not gen then return end
	texGen = gen
	for _, t in ipairs(list) do setPair(t.id, t.name) end
end

-- The shadow blob material (the module's 64x64 texture).
local shadowMat
local function shadowMaterial()
	if shadowMat then return shadowMat end
	local tex = gmodcraft.EntitiesShadowTexture()
	if not tex then return nil end
	shadowMat = CreateMaterial("gmodcraft_ent_shadow", "UnlitGeneric", {
		["$basetexture"] = tex, ["$vertexcolor"] = 1, ["$vertexalpha"] = 1, ["$translucent"] = 1,
	})
	return shadowMat
end

-- ---- GMod's light ---------------------------------------------------------------------------------
local LIGHT_PER_FRAME = 16
local qbuf, sbuf, probe = {}, {}, Vector()
local eyeS, eyeNext = 1, 0
local function sampleLight()
	if not cvLight:GetBool() or not B.LightAt then return end
	local now = RealTime()
	if now >= eyeNext then
		eyeNext = now + 0.25
		eyeS = B.LightS(B.LightAt(EyePos()))
	end
	local n = gmodcraft.EntitiesLightQuery(LIGHT_PER_FRAME, qbuf)
	if not n or n <= 0 then return end
	for i = 0, n - 1 do
		local q, o = i * 6, i * 4
		probe:SetUnpacked(qbuf[q + 4], qbuf[q + 5], qbuf[q + 6])
		sbuf[o + 1], sbuf[o + 2], sbuf[o + 3] = qbuf[q + 1], qbuf[q + 2], qbuf[q + 3]
		sbuf[o + 4] = B.LightS(B.LightAt(probe))
	end
	E.stats.lightSamples = E.stats.lightSamples + n
	gmodcraft.EntitiesLightSet(sbuf, n)
end

-- ---- per frame ------------------------------------------------------------------------------------
local args = { feet = false, x = 0, y = 0, z = 0, s = 1, light = true, avatar = true, scene = true, world = true }
local world = {}
E.world = world

hook.Add("PreRender", "gmodcraft_entities", function()
	-- nothing is baked while nothing would be drawn (P3c review)
	if gmodcraft.missing or not CL.open or not gmodcraft.EntitiesPrepare or not cvDraw:GetBool() or not C.slot.known then return end
	local t0 = SysTime()
	syncMaterials()
	if cvShadows:GetBool() then shadowMaterial() end  -- the blob texture is made here, not inside a draw hook
	sampleLight()
	local V = gmodcraft.view
	local feet = V and V.valid and V.feet and gmodcraft.IsMcPlayer(LocalPlayer())
	args.feet = feet and true or false
	if feet then args.x, args.y, args.z = V.feet.x, V.feet.y, V.feet.z end
	args.light = cvLight:GetBool()
	args.s = args.light and eyeS or 1
	gmodcraft.EntitiesPrepare(args)
	gmodcraft.EntitiesWorld(world, E.wantEntities == true)
	gmodcraft.PerfAdd("cl EntitiesPrepare", (SysTime() - t0) * 1000)
end)

local function ready()
	return not gmodcraft.missing and CL.open and cvDraw:GetBool() and C.slot.known and gmodcraft.EntitiesDraw ~= nil
end

local OUTLINE = Color(0, 0, 0, 115)  -- Minecraft's outline: black, 45%
local ORIGIN, ZERO = Vector(0, 0, 0), Angle(0, 0, 0)
local selMin, selMax = Vector(), Vector()

hook.Add("PostDrawOpaqueRenderables", "gmodcraft_entities", function(depth, skybox, sky3d)
	if not ready() or (B.SkipView and B.SkipView(depth, skybox, sky3d)) then return end
	local t0 = SysTime()
	gmodcraft.EntitiesDraw(0)
	if cvOutline:GetBool() and world.sel then
		local a, b = world.selMin, world.selMax
		selMin:SetUnpacked(a[1], a[2], a[3])
		selMax:SetUnpacked(b[1], b[2], b[3])
		render.DrawWireframeBox(ORIGIN, ZERO, selMin, selMax, OUTLINE, true)
	end
	gmodcraft.PerfAdd("cl EntitiesDraw opaque", (SysTime() - t0) * 1000)
end)

local UP = Vector(0, 0, 1)
local SHADOW = Color(255, 255, 255, 150)
local spos = Vector()
hook.Add("PostDrawTranslucentRenderables", "gmodcraft_entities", function(depth, skybox, sky3d)
	if not ready() or (B.SkipView and B.SkipView(depth, skybox, sky3d)) then return end
	local t0 = SysTime()
	local n = world.nShadows or 0
	if cvShadows:GetBool() and n > 0 then
		local m = shadowMat  -- made in PreRender
		if m then
			render.SetMaterial(m)
			local s = world.shadows
			for i = 0, n - 1 do
				local o = i * 4
				spos:SetUnpacked(s[o + 1], s[o + 2], s[o + 3] + 0.6)
				local d = s[o + 4] * 2
				render.DrawQuadEasy(spos, UP, d, d, SHADOW, 0)
			end
		end
	end
	gmodcraft.EntitiesDraw(1)
	gmodcraft.PerfAdd("cl EntitiesDraw translucent", (SysTime() - t0) * 1000)
end)
