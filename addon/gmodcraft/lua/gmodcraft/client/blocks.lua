-- Minecraft's placed blocks drawn in GMod's own frame (P3a, docs/DESIGN.md section 7). The gmcl
-- module drains the render ring (client/link.lua's Frame), keeps every section, bakes the light
-- into vertex colours and builds one static IMesh per section and pass (module/source/blocks.cpp).
-- This file makes the materials once the atlas texture exists, prepares the meshes once per frame
-- and draws them in the opaque and translucent hooks.
--
-- Materials (UnlitGeneric on the module's atlas texture):
--   gmodcraft_blocks_cutout_<atlas>       $vertexcolor, $alphatest .5   (opaque + cutout, writes depth)
--   gmodcraft_blocks_translucent_<atlas>  $vertexcolor $vertexalpha $translucent (water, stained glass)
-- Default (gmodcraft_blocks_lua 0): the module's own static IMesh path (verified live in the P3b
-- probe). If its run-time check fails, this file switches to the Lua Mesh() fallback by itself
-- (B.forceLua; the convar is left alone). Vertex colour: R,G,B,A bytes, multiplied in gamma space
-- (gmodcraft_blocks_rgba 1, gmodcraft_blocks_linear_vc 0: measured, D-017).
--
-- GMod's light (P3b): every 4x4x4-block cell with faces in it gets the brightest render.GetLightColor
-- of its candidate points not inside a brush (one per face direction, 12 u out, and the cell centre;
-- client/lightpick.lua; all enclosed: S = 1) (unsampled cells first, then all of them again every 1 / gmodcraft_blocks_light_hz
-- seconds, at most LIGHT_PER_FRAME per frame). S = min(1, lum * scale) ^ (1 / 2.2), floored, is the
-- bake's S (blockmesh.cpp LightRgb); a cell whose S moved more than the module's threshold re-bakes
-- its section.
--
-- Per frame this file allocates nothing of its own (tables are reused); the Lua meshes go when the
-- link closes and on ShutDown.

local B = gmodcraft.blocks or {}
gmodcraft.blocks = B
local CL = gmodcraft.clientLink
local C = gmodcraft.convert
local floor, rshift, band = math.floor, bit.rshift, bit.band

local cvDraw = CreateClientConVar("gmodcraft_blocks", "1", true, false, "Garry's Modcraft: draw Minecraft's blocks")
local cvOpaque = CreateClientConVar("gmodcraft_blocks_opaque", "1", false, false, "Garry's Modcraft: draw the opaque + cutout block pass")
local cvTrans = CreateClientConVar("gmodcraft_blocks_translucent", "1", false, false, "Garry's Modcraft: draw the translucent block pass (water, stained glass)")
-- Default 0: the module's static IMesh path (P3b). 1 forces the Lua Mesh() fallback.
local cvLua = CreateClientConVar("gmodcraft_blocks_lua", "0", false, false,
	"Garry's Modcraft: build blocks as Lua Mesh() objects (1, the fallback) or the module's static IMesh path (0, default)")
-- Not archived (P3b review): a P3a-era client.vdf holds linear_vc 1 / rgba 0; archived, those would
-- override the measured defaults (D-017).
local cvLinear = CreateClientConVar("gmodcraft_blocks_linear_vc", "0", false, false,
	"Garry's Modcraft: the shader multiplies vertex colour in linear light, so the baked brightness is sRGB-decoded first (default 0: gamma space, measured)")
local cvRgba = CreateClientConVar("gmodcraft_blocks_rgba", "1", false, false, "Garry's Modcraft: vertex colour byte order R,G,B,A (default 1, measured) or B,G,R,A (0, D3DCOLOR)")
-- Kept (archived in client.vdf): the full-atlas re-upload cap, used only when sub-rect uploads aren't possible.
local cvHz = CreateClientConVar("gmodcraft_blocks_atlas_hz", "2", true, false, "Garry's Modcraft: full atlas re-uploads per second while animated textures change (only when sub-rect uploads can't be used)")
-- New in P3b, not archived (nothing persists in client.vdf):
local cvAnim = CreateClientConVar("gmodcraft_blocks_anim", "1", false, false,
	"Garry's Modcraft: animated textures: 1 their own small texture (default), 2 sub-rect uploads into the atlas (experimental, ~3 ms per rect), 0 full atlas re-uploads at gmodcraft_blocks_atlas_hz")
local cvAnimHz = CreateClientConVar("gmodcraft_blocks_anim_hz", "10", false, false, "Garry's Modcraft: animated texture uploads per second at most (modes 1 and 2; 10: water and lava change at 10 Hz, ~1.8 ms per upload live)")
local cvLight = CreateClientConVar("gmodcraft_blocks_light", "1", false, false, "Garry's Modcraft: darken blocks by GMod's light at their place (render.GetLightColor per 4x4x4-block cell)")
-- 1.5: gm_construct's open sky measured lum 0.665 (P3b), so sky-lit blocks stay at full brightness
local cvLightScale = CreateClientConVar("gmodcraft_blocks_light_scale", "1.5", false, false, "Garry's Modcraft: GMod light multiplier before the S = min(1, lum * scale) ^ (1 / 2.2) mapping")
local cvLightMin = CreateClientConVar("gmodcraft_blocks_light_min", "0.12", false, false, "Garry's Modcraft: the darkest S GMod's light can give a block (0..1)")
local cvLightHz = CreateClientConVar("gmodcraft_blocks_light_hz", "1", false, false, "Garry's Modcraft: how often every cell's GMod light is sampled again (per second)")
-- P5b, not archived: holes Minecraft dug into GMod's map
local cvHoles = CreateClientConVar("gmodcraft_holes", "1", false, false,
	"Garry's Modcraft: holes Minecraft dug into GMod's map: 1 hide the map's surface over dug cells so the hole walls show (stencil, default), 2 a dark translucent cap over them (fallback), 0 off")
-- D2b, experimental, not archived: cut only the map faces whose brush lies in a dug cell (a wall),
-- not every surface in the cell (the room's floor and ceiling next to it). holes.cpp / holebox.cpp.
local cvCutBrush = CreateClientConVar("gmodcraft_dig_cut_brush", "0", false, false,
	"Garry's Modcraft (experimental): with gmodcraft_holes 1, cut only the map faces whose solid lies in a dug cell (walls), so floors and ceilings next to a hole stay. 0 off (default: every surface in a dug cell), 1 on")
-- P5c, not archived: the sky while the eye is inside the map's brushes (digging into the map)
local cvSkySolid = CreateClientConVar("gmodcraft_sky_in_solid", "1", false, false,
	"Garry's Modcraft: draw the map's sky where nothing else draws while the view is inside map geometry (the engine draws none there: black). 1 on (default), 0 off")
local cvSky3d = CreateClientConVar("gmodcraft_sky_in_solid_3d", "1", false, false,
	"Garry's Modcraft: with gmodcraft_sky_in_solid, render the map's 3D skybox (an extra view of the skybox room) instead of only the 2D sky. 1 on (default), 0 off")

B.stats = B.stats or { rtSkips = 0, rtOther = {}, drawn = 0 }

-- ---- module config ---------------------------------------------------------------------------------
-- Compared field by field every frame; `want` is refilled in place, not rebuilt.
local applied, want = {}, {}

-- The draw path in use: the Lua fallback when the convar asks for it, or when the module's IMesh
-- check failed (B.forceLua, set below; cleared when the convar changes, or by the panel's retry).
local function useLua() return cvLua:GetBool() or B.forceLua == true end
local lastCvLua, nextMeshCheck = nil, 0
local function checkMeshPath()
	local cv = cvLua:GetBool()
	if cv ~= lastCvLua then
		lastCvLua = cv
		B.forceLua = nil
	end
	if cv or B.forceLua or RealTime() < nextMeshCheck then return end
	nextMeshCheck = RealTime() + 1
	if gmodcraft.BlocksMeshOk() == 0 then
		B.forceLua = true
		print("[gmodcraft] the module's IMesh path failed its check: using the Lua Mesh() fallback (" .. tostring(gmodcraft.BlocksInfo().meshProbe) .. ")")
	end
end

local function applyConfig()
	checkMeshPath()
	want.linearVertexColor, want.rgbaOrder, want.atlasHz, want.luaMeshes = cvLinear:GetBool(), cvRgba:GetBool(), cvHz:GetFloat(), useLua()
	want.animHz, want.animMode, want.cellLight = cvAnimHz:GetFloat(), cvAnim:GetInt(), cvLight:GetBool()
	local same = true
	for k, v in pairs(want) do
		if applied[k] ~= v then same = false break end
	end
	if same then return end
	B.config = gmodcraft.BlocksConfig(want)
	for k, v in pairs(want) do applied[k] = v end
end

-- ---- GMod's light ---------------------------------------------------------------------------------
local LIGHT_PER_FRAME = 64
local qbuf, sbuf = {}, {}  -- BlocksLightQuery's output and BlocksLightSet's input, reused
local probePos = Vector()
local OFFSETS = { Vector(0, 0, 20), Vector(20, 0, 0), Vector(-20, 0, 0), Vector(0, 20, 0), Vector(0, -20, 0), Vector(0, 0, -20) }
local function lum(c) return 0.2126 * c.x + 0.7152 * c.y + 0.0722 * c.z end
-- GMod's light at a probe point; inside a brush the engine has none, so the brightest free point
-- 20 units around it counts instead.
local function lightAt(pos)
	if bit.band(util.PointContents(pos), CONTENTS_SOLID) == 0 then return lum(render.GetLightColor(pos)) end
	local best
	for _, o in ipairs(OFFSETS) do
		local p = pos + o
		if bit.band(util.PointContents(p), CONTENTS_SOLID) == 0 then
			local l = lum(render.GetLightColor(p))
			if not best or l > best then best = l end
		end
	end
	return best or lum(render.GetLightColor(pos))
end
B.LightAt = lightAt
-- The bake's S for a GMod light level (see the header).
local function lightS(l)
	local s = math.min(1, math.max(0, l * cvLightScale:GetFloat())) ^ (1 / 2.2)
	return math.max(math.Clamp(cvLightMin:GetFloat(), 0, 1), s)
end
B.LightS = lightS
B.lightStats = B.lightStats or { samples = 0, minLum = nil, maxLum = nil }
local lightNext = 0
-- the candidate-point choice (client/lightpick.lua) with the engine's contents and light
local pick = gmodcraft.lightPick.Pick
local function isSolid(x, y, z)
	probePos:SetUnpacked(x, y, z)
	return bit.band(util.PointContents(probePos), CONTENTS_SOLID) ~= 0
end
local function lightXYZ(x, y, z)
	probePos:SetUnpacked(x, y, z)
	return lum(render.GetLightColor(probePos))
end
local function sampleLight()
	if not cvLight:GetBool() then return end
	local now = RealTime()
	-- a new sweep once the previous one finished (the module refuses earlier: no starved cells)
	if now >= lightNext and gmodcraft.BlocksLightRefresh() then
		lightNext = now + 1 / math.max(0.05, cvLightHz:GetFloat())
	end
	local n = gmodcraft.BlocksLightQuery(LIGHT_PER_FRAME, qbuf)
	if not n or n <= 0 then return end
	local st = B.lightStats
	for i = 0, n - 1 do
		local q, o = i * 25, i * 4  -- BlocksLightQuery: cx, cy, cz, k, 7 x/y/z triples
		local l = pick(qbuf, q + 4, qbuf[q + 4], isSolid, lightXYZ)
		sbuf[o + 1], sbuf[o + 2], sbuf[o + 3] = qbuf[q + 1], qbuf[q + 2], qbuf[q + 3]
		if l then
			sbuf[o + 4] = lightS(l)
			st.minLum = math.min(st.minLum or l, l)
			st.maxLum = math.max(st.maxLum or l, l)
		else
			sbuf[o + 4] = 1  -- every candidate inside brushes: full brightness, not black
			st.enclosed = (st.enclosed or 0) + 1
		end
	end
	st.samples = st.samples + n
	gmodcraft.BlocksLightSet(sbuf, n)
end

-- ---- materials ----------------------------------------------------------------------------------------
-- Kept in B so a Lua autorefresh doesn't lose them while B.atlas says they exist (seen live:
-- render.SetMaterial(nil) every frame after a refresh).
B.mats = B.mats or {}
local mats = B.mats
local function makeMaterials(atlas)
	if B.atlas == atlas then return end
	local tag = atlas:gsub("[^%w]", "_")
	mats.opaque = CreateMaterial("gmodcraft_blocks_cutout_" .. tag, "UnlitGeneric", {
		["$basetexture"] = atlas, ["$vertexcolor"] = 1, ["$alphatest"] = 1, ["$alphatestreference"] = 0.5,
	})
	mats.translucent = CreateMaterial("gmodcraft_blocks_translucent_" .. tag, "UnlitGeneric", {
		["$basetexture"] = atlas, ["$vertexcolor"] = 1, ["$vertexalpha"] = 1, ["$translucent"] = 1,
	})
	-- The module gets the IMaterial objects themselves: FindMaterial doesn't see materials made with
	-- CreateMaterial (found in the P3a live run). The names are for the panel.
	B.matOpaque, B.matTranslucent = mats.opaque:GetName(), mats.translucent:GetName()
	B.atlas = atlas
	gmodcraft.Log("view", "block materials %s / %s on %s", B.matOpaque, B.matTranslucent, atlas)
end
-- The same two on the animated sprites' texture (its name changes with its size).
local function makeAnimMaterials(tex)
	if B.animTex == tex then return end
	local tag = tex:gsub("[^%w]", "_")
	mats.animOpaque = CreateMaterial("gmodcraft_blocks_cutout_" .. tag, "UnlitGeneric", {
		["$basetexture"] = tex, ["$vertexcolor"] = 1, ["$alphatest"] = 1, ["$alphatestreference"] = 0.5,
	})
	mats.animTranslucent = CreateMaterial("gmodcraft_blocks_translucent_" .. tag, "UnlitGeneric", {
		["$basetexture"] = tex, ["$vertexcolor"] = 1, ["$vertexalpha"] = 1, ["$translucent"] = 1,
	})
	B.animTex = tex
	gmodcraft.Log("view", "animated block materials on %s", tex)
end

-- ---- the Lua Mesh() fallback --------------------------------------------------------------------------
B.luaMeshes = B.luaMeshes or {}
local luaMeshes = B.luaMeshes  -- "sx,sy,sz" -> { version, centre, seen, dist, [0] = { IMesh, ... }, [1] = { ... } }
local luaGen = -1
local LUA_CHUNK = 30000  -- vertices per Mesh() (whole triangles)
local UV_SCALE = 16777215  -- module/source/blocks.cpp kUvScale
local vbuf = {}  -- BlocksLuaVerts fills this table (reused across calls) (5 numbers per vertex: x, y, z, uv, rgba)

-- n vertices from vbuf (the packing is described at BlocksLuaVerts in blocks.cpp).
local function buildLua(n)
	local out = {}
	local first = 0
	while first < n do
		local count = math.min(LUA_CHUNK, n - first)
		count = count - count % 3
		if count <= 0 then break end
		local m = Mesh()
		mesh.Begin(m, MATERIAL_TRIANGLES, count / 3)
		for i = first, first + count - 1 do
			local b = i * 5
			mesh.Position(Vector(vbuf[b + 1], vbuf[b + 2], vbuf[b + 3]))
			local uv = vbuf[b + 4]
			local u = floor(uv / 16777216)
			mesh.TexCoord(0, u / UV_SCALE, (uv - u * 16777216) / UV_SCALE)
			local c = vbuf[b + 5]
			mesh.Color(rshift(c, 24), band(rshift(c, 16), 255), band(rshift(c, 8), 255), band(c, 255))
			mesh.AdvanceVertex()
		end
		mesh.End()
		out[#out + 1] = m
		first = first + count
	end
	return out
end

local function freeLua(e)
	for pass = 0, 3 do
		for _, m in ipairs(e[pass] or {}) do m:Destroy() end
	end
end

local function syncLua()
	local gen = gmodcraft.BlocksLuaGeneration()
	if gen == luaGen then return end
	luaGen = gen
	for _, s in ipairs(gmodcraft.BlocksLuaSections()) do
		local key = s.sx .. "," .. s.sy .. "," .. s.sz
		local e = luaMeshes[key]
		if not e or e.version ~= s.version then
			if e then freeLua(e) end
			e = { version = s.version, centre = C.FromMc(s.sx * 16 + 8, s.sy * 16 + 8, s.sz * 16 + 8), dist = 0 }
			for pass = 0, 3 do  -- 0/1 atlas opaque/translucent, 2/3 the same on the animated texture
				local n = gmodcraft.BlocksLuaVerts(s.sx, s.sy, s.sz, pass, vbuf)
				e[pass] = (n and n > 0) and buildLua(n) or {}
			end
			luaMeshes[key] = e
		end
		e.seen = gen
	end
	for key, e in pairs(luaMeshes) do
		if e.seen ~= gen then
			freeLua(e)
			luaMeshes[key] = nil
		end
	end
	-- vbuf stays for the next sync (no garbage), unless a huge section blew it up (> ~60k vertices)
	if vbuf[300000] ~= nil then vbuf = {} end
end

-- Every Lua mesh goes (the link closed, the path switched, ShutDown).
local function clearLua()
	for key, e in pairs(luaMeshes) do freeLua(e) luaMeshes[key] = nil end
	luaGen = -1
end
B.ClearLuaMeshes = clearLua

local order = {}  -- the translucent pass's sections, reused every frame
local function farFirst(a, b) return a.dist > b.dist end
local function drawLua(pass)
	if pass == 0 then
		render.SetMaterial(mats.opaque)
		for _, e in pairs(luaMeshes) do
			for _, m in ipairs(e[0]) do m:Draw() end
		end
		if mats.animOpaque and B.animTex then
			render.SetMaterial(mats.animOpaque)
			for _, e in pairs(luaMeshes) do
				for _, m in ipairs(e[2]) do m:Draw() end
			end
		end
		return
	end
	local eye, n = EyePos(), 0
	for _, e in pairs(luaMeshes) do
		if #e[1] > 0 or #e[3] > 0 then
			n = n + 1
			order[n] = e
			e.dist = e.centre:DistToSqr(eye)
		end
	end
	for i = #order, n + 1, -1 do order[i] = nil end
	table.sort(order, farFirst)
	local anim = B.animTex and mats.animTranslucent
	for i = 1, n do
		local e = order[i]
		if #e[1] > 0 then
			render.SetMaterial(mats.translucent)
			for _, m in ipairs(e[1]) do m:Draw() end
		end
		if anim and #e[3] > 0 then
			render.SetMaterial(anim)
			for _, m in ipairs(e[3]) do m:Draw() end
		end
	end
end

-- ---- Minecraft's light sources light GMod (P3d) -------------------------------------------------------
-- The module clusters Minecraft's light-emitting blocks (kRenLights: torches, lanterns, lava,
-- glowstone, ...) the way SkyCraft's BlockLights does (3-block cells, the nearest within 72 blocks,
-- rebuilt every 0.25 s, sticky slots, flame / lava flicker) and hands back at most
-- gmodcraft_lights_max of them; each slot is one GMod dynamic light (DynamicLight, key LIGHT_KEY +
-- slot), lighting GMod's world and models. Minecraft's own blocks are UnlitGeneric (their block light
-- is baked), so these don't touch them. Kept alive with a short dietime; a slot that goes dark is
-- killed at once. No elights: a dlight already lights models.
-- Off by default (user decision 2026-10-05): any world dlight costs ~10 ms/frame on ToGL (docs/spikes/p3d).
local cvLights = CreateClientConVar("gmodcraft_lights", "0", false, false, "Garry's Modcraft: Minecraft's light sources (torches, lava, ...) light GMod's map as dynamic lights (costs ~10 ms/frame)")
local cvLightsMax = CreateClientConVar("gmodcraft_lights_max", "10", false, false, "Garry's Modcraft: at most this many dynamic lights for Minecraft's light sources (0..24)")
local cvLightsBright = CreateClientConVar("gmodcraft_lights_brightness", "2", false, false, "Garry's Modcraft: the dynamic lights' brightness (GMod's dlight exponent)")
-- P3d live run: any world dlight costs ~10 ms/frame on ToGL; a smaller radius may relight less lightmap (untested)
local cvLightsRadius = CreateClientConVar("gmodcraft_lights_radius", "1", false, false, "Garry's Modcraft: scale of the dynamic lights' radius (0.1..1)")
local LIGHT_KEY = 0x7F00  -- far above entity indices (dlight keys)
local INTENSITY_MAX = 1.4 * 1.15  -- the module's base (0.75 .. 1.4) times the flame flicker's top
B.lightsOn = B.lightsOn or {}  -- slot -> true while its dlight is alive (kept across a Lua autorefresh)
local lightsOn, lightsNow, lbuf, lpos = B.lightsOn, {}, {}, Vector()
B.lightStatsP3d = B.lightStatsP3d or { frames = 0, lit = 0 }
local lastLightsT
local function killLights(except)
	local now = CurTime()
	for slot in pairs(lightsOn) do
		if not (except and except[slot]) then
			local d = DynamicLight(LIGHT_KEY + slot)
			if d then
				d.dietime = now
				d.size = 0
			end
			lightsOn[slot] = nil
		end
	end
end
B.KillLights = killLights
local function updateLights()
	if not cvLights:GetBool() or not C.slot.known then
		if next(lightsOn) then killLights() end
		lastLightsT = nil
		return
	end
	local t = RealTime()
	local dt = lastLightsT and (t - lastLightsT) or 0
	lastLightsT = t
	local eye = EyePos()
	local n = gmodcraft.BlocksLights(eye.x, eye.y, eye.z, math.Clamp(cvLightsMax:GetInt(), 0, 24), dt, lbuf)
	for k in pairs(lightsNow) do lightsNow[k] = nil end
	local now, bright = CurTime(), math.Clamp(cvLightsBright:GetFloat(), 0, 10)
	for i = 0, (n or 0) - 1 do
		local o = i * 9
		local slot = lbuf[o + 1]
		local d = DynamicLight(LIGHT_KEY + slot)
		if d then
			local k = math.Clamp(lbuf[o + 9] / INTENSITY_MAX, 0, 1) * 255
			lpos:SetUnpacked(lbuf[o + 2], lbuf[o + 3], lbuf[o + 4])
			d.pos = lpos  -- copied into the dlight
			d.r, d.g, d.b = lbuf[o + 5] * k, lbuf[o + 6] * k, lbuf[o + 7] * k
			d.brightness = bright
			d.size = lbuf[o + 8] * math.Clamp(cvLightsRadius:GetFloat(), 0.1, 1)
			d.decay = 0
			d.dietime = now + 0.3
			lightsNow[slot] = true
			lightsOn[slot] = true
		end
	end
	killLights(lightsNow)  -- slots that went dark this frame
	local st = B.lightStatsP3d
	st.frames, st.lit = st.frames + 1, n or 0
end

-- ---- per frame ------------------------------------------------------------------------------------------
hook.Add("PreRender", "gmodcraft_blocks", function()
	if gmodcraft.missing then return end
	if not CL.open then
		if luaGen ~= -1 or next(luaMeshes) then clearLua() end
		if next(lightsOn) then killLights() end
		return
	end
	local t0 = SysTime()
	applyConfig()
	sampleLight()  -- before Prepare: a new section's cells get their light before its first bake
	local t1 = SysTime()
	gmodcraft.BlocksPrepare(4)
	local atlas = gmodcraft.BlocksAtlas()
	if atlas then makeMaterials(atlas) end
	local animTex = gmodcraft.BlocksAnim()
	if animTex then makeAnimMaterials(animTex) else B.animTex = nil end
	if useLua() then syncLua() elseif luaGen ~= -1 then clearLua() end
	B.holeBoxes = cvHoles:GetInt() > 0 and gmodcraft.HolesPrepare(C.WorldId(), cvCutBrush:GetBool()) or 0
	local t2 = SysTime()
	updateLights()
	gmodcraft.PerfAdd("cl BlocksLight", (t1 - t0) * 1000)
	gmodcraft.PerfAdd("cl BlocksPrepare", (t2 - t1) * 1000)
	gmodcraft.PerfAdd("cl BlockLights", (SysTime() - t2) * 1000)
end)

hook.Add("ShutDown", "gmodcraft_blocks", clearLua)

-- Water reflection / refraction views also run the draw hooks: they render into these targets.
-- The verdict per render target name is cached (no string work per frame).
local rtSkip = {}
local function skipView(depth, skybox, sky3d)
	if depth or skybox or sky3d or B.inSkyView then return true end  -- B.inSkyView: our own 3D sky render (P5c)
	local rt = render.GetRenderTarget()
	if rt then
		local name = rt:GetName() or ""
		local skip = rtSkip[name]
		if skip == nil then
			local n = string.lower(name)
			skip = n:find("reflect", 1, true) ~= nil or n:find("refract", 1, true) ~= nil
			rtSkip[name] = skip
		end
		if skip then
			B.stats.rtSkips = B.stats.rtSkips + 1
			return true
		end
		B.stats.rtOther[name] = (B.stats.rtOther[name] or 0) + 1
	end
	return false
end

B.SkipView = skipView  -- client/entities.lua draws in the same views

local function ready()
	return not gmodcraft.missing and CL.open and cvDraw:GetBool() and B.matOpaque ~= nil and C.slot.known
end

hook.Add("PostDrawOpaqueRenderables", "gmodcraft_blocks", function(depth, skybox, sky3d)
	if skipView(depth, skybox, sky3d) then return end
	if B.probeMaterial then gmodcraft.BlocksProbeDraw(B.probeMaterial) end  -- an IMaterial: dev calibration (test_p3a.lua)
	if not ready() or not cvOpaque:GetBool() then return end
	local UG = gmodcraft.underground
	if UG and UG.Active() then UG.FogFallback() end  -- U1: the cave fog, if the engine changed it since
	local t0 = SysTime()
	if useLua() then drawLua(0) else gmodcraft.BlocksDraw(0, mats.opaque, B.animTex and mats.animOpaque or nil) end
	B.stats.drawn = B.stats.drawn + 1
	gmodcraft.PerfAdd("cl BlocksDraw opaque", (SysTime() - t0) * 1000)
end)

hook.Add("PostDrawTranslucentRenderables", "gmodcraft_blocks", function(depth, skybox, sky3d)
	if not ready() or not cvTrans:GetBool() or skipView(depth, skybox, sky3d) then return end
	local UG = gmodcraft.underground
	if UG and UG.Active() then UG.FogFallback() end
	local t0 = SysTime()
	if useLua() then
		drawLua(1)
	else
		local eye = EyePos()
		gmodcraft.BlocksDraw(1, mats.translucent, B.animTex and mats.animTranslucent or nil, eye.x, eye.y, eye.z)
	end
	gmodcraft.PerfAdd("cl BlocksDraw translucent", (SysTime() - t0) * 1000)
	if cvHoles:GetInt() == 2 and (B.holeBoxes or 0) > 0 and mats.holeCap then
		gmodcraft.HolesDraw(mats.holeCap)  -- the fallback: a dark cap over the dug cells
	end
end)

-- ---- holes Minecraft dug into GMod's map (P5b, docs/DESIGN.md section 9) --------------------------------
-- GMod's BSP can't change, so a hole is drawn: the module keeps the dug cells (kRenDug) as a closed
-- volume of greedy-merged boxes (module/source/holes.cpp, holebox.cpp; grown by 0.5 units).
-- In PreDrawOpaqueRenderables the depth buffer holds only the world (brushes, displacements), so a
-- z-fail stencil count marks the pixels whose world surface lies inside a dug cell: the volume's
-- back faces increment where they fail the depth test, its front faces decrement (wrapping; colour
-- and depth writes off; either winding works, since only "not 0" counts). There colour and depth
-- are cleared (to a dark earth colour), and the opaque block pass then draws the hole walls
-- (DigWalls, in Minecraft's section meshes) into the gap. Props and NPCs draw after this pass, so
-- they stay visible; reflection, refraction, depth and skybox views are skipped (B.SkipView).
-- gmodcraft_holes 2 is the fallback for a renderer without stencil: a dark translucent cap.
-- Minecraft's "GMod destruction" toggle only stops new digging: holes already dug stay (no
-- collision, DigWalls drawn), so they are drawn here whatever it says.
local HOLE_R, HOLE_G, HOLE_B = 24, 19, 14  -- what shows where no wall covers the cleared pixels
mats.holeMask = mats.holeMask or CreateMaterial("gmodcraft_holes_mask", "UnlitGeneric", { ["$basetexture"] = "color/white" })
mats.holeCap = mats.holeCap or CreateMaterial("gmodcraft_holes_cap", "UnlitGeneric", {
	["$basetexture"] = "color/white", ["$color"] = "[0.09 0.07 0.05]", ["$alpha"] = 0.85, ["$translucent"] = 1,
})
-- The same without depth writes by material ($translucent): the mask variant "mat" (B.holeVariant),
-- for a renderer where OverrideDepthEnable would also turn the depth test off.
mats.holeMaskT = mats.holeMaskT or CreateMaterial("gmodcraft_holes_mask_t", "UnlitGeneric", { ["$basetexture"] = "color/white", ["$translucent"] = 1 })
B.holeStats = B.holeStats or { passes = 0, lastMs = 0 }

-- The stencil pass itself (the dev probe in test_p5b.lua calls it too).
function B.HoleStencilPass(r, g, b)
	local byMat = B.holeVariant == "mat"
	local mask = byMat and mats.holeMaskT or mats.holeMask
	render.SetStencilWriteMask(255)
	render.SetStencilTestMask(255)
	render.SetStencilReferenceValue(0)
	render.ClearStencil()
	render.SetStencilEnable(true)
	render.SetStencilCompareFunction(STENCIL_ALWAYS)
	render.SetStencilPassOperation(STENCIL_KEEP)
	render.SetStencilFailOperation(STENCIL_KEEP)
	render.OverrideColorWriteEnable(true, false)
	if not byMat then render.OverrideDepthEnable(true, false) end  -- the depth test stays, no depth writes
	render.SetStencilZFailOperation(STENCIL_INCR)
	render.CullMode(MATERIAL_CULLMODE_CW)  -- the back faces
	gmodcraft.HolesDraw(mask)
	render.CullMode(MATERIAL_CULLMODE_CCW)  -- the front faces (Source's default culling)
	render.SetStencilZFailOperation(STENCIL_DECR)
	gmodcraft.HolesDraw(mask)
	if not byMat then render.OverrideDepthEnable(false, false) end
	render.OverrideColorWriteEnable(false, false)
	render.SetStencilZFailOperation(STENCIL_KEEP)
	render.SetStencilCompareFunction(STENCIL_NOTEQUAL)
	render.ClearBuffersObeyStencil(r or HOLE_R, g or HOLE_G, b or HOLE_B, 255, true)
	render.SetStencilCompareFunction(STENCIL_ALWAYS)
	render.SetStencilEnable(false)
end

-- ---- the sky with the eye inside map geometry (P5c) ----------------------------------------------------------
-- A view origin in a solid leaf has no PVS: the leaf has no sky flags, so the engine draws neither
-- the 3D nor the 2D skybox, and RenderView clears to black (VIEW_CLEAR_COLOR when the origin is in
-- CONTENTS_SOLID). Where no world face draws (the open top of a hole, the map's sky brushes) the
-- player then sees black. Here, while that is the case and no skybox hook ran this frame, the map's
-- 2D sky is drawn as a cube around the eye at the far depth (DepthRange 1..1, depth test on, no depth
-- writes): it only fills pixels that still hold the cleared depth, never over the world or blocks.
-- Must run before the hole stencil pass (its ClearBuffersObeyStencil resets depth in the masks).
local SKY_FACES = {  -- suffix, then the corners top-left, top-right, bottom-right, bottom-left (unit cube)
	{ "rt", { 1, 1, 1 }, { 1, -1, 1 }, { 1, -1, -1 }, { 1, 1, -1 } },
	{ "lf", { -1, -1, 1 }, { -1, 1, 1 }, { -1, 1, -1 }, { -1, -1, -1 } },
	{ "bk", { -1, 1, 1 }, { 1, 1, 1 }, { 1, 1, -1 }, { -1, 1, -1 } },
	{ "ft", { 1, -1, 1 }, { -1, -1, 1 }, { -1, -1, -1 }, { 1, -1, -1 } },
	{ "up", { -1, 1, 1 }, { -1, -1, 1 }, { 1, -1, 1 }, { 1, 1, 1 } },
	{ "dn", { 1, 1, -1 }, { 1, -1, -1 }, { -1, -1, -1 }, { -1, 1, -1 } },
}
local SKY_HALF = 64
B.skyStats = B.skyStats or { frames = 0, lastMs = 0, skyHooks = 0, skipped = 0, name = "" }
-- The frame of the last sky hook, our own 3D sky render excluded (B.inSkyView). Not per render
-- target: measured live, the main view's sky hooks and its opaque hook see different targets
-- (the gate then let the cube draw over a sky the engine had drawn). A view drawn earlier in the
-- frame that shows the sky (a water reflection) still counts: the main view then stays as the
-- engine draws it.
local skyHookFrame = -1
local function rtName()
	local rt = render.GetRenderTarget()
	return rt and rt:GetName() or ""
end
-- The only owner of the sky hooks (K1 adds the Minecraft sky, client/mcsky.lua, here too: a second
-- PreDrawSkyBox hook returning true would stop this one and the frame gate above).
local function skyHook()
	if B.inSkyView then return end  -- the skybox room's own sky, inside our 3D sky render
	skyHookFrame = FrameNumber()
	B.skyStats.hookRT = rtName()
	B.skyStats.skyHooks = B.skyStats.skyHooks + 1
end
hook.Add("PreDrawSkyBox", "gmodcraft_sky_in_solid", function()
	skyHook()
	-- K1: never return true here. Skipping the engine's skybox (live, 2026-10-07) also stopped our opaque
	-- hooks: no Minecraft terrain, no sky. The Source sky draws as usual and the Minecraft sky is drawn over
	-- it in the main view at far depth (B.McSkyFar), like the sky in solid: the skybox leaves depth at far.
end)
-- Maps without a 3D skybox (or r_3dsky 0) never call PreDrawSkyBox: the Minecraft sky goes over the
-- 2D sky here, before the world draws.
hook.Add("PostDraw2DSkyBox", "gmodcraft_sky_in_solid", function()
	skyHook()
end)

-- K1: the Minecraft sky in the main view, at far depth: it fills only what nothing drew (the skybox's place).
function B.McSkyFar()
	local MS = gmodcraft.mcsky
	if B.inSkyView or not (MS and MS.Enabled()) then return false end
	return MS.Draw(true)
end

-- UnlitGeneric copies of the sky's six textures (the sky shader's materials don't draw as quads).
-- With B.holeVariant "mat" (a renderer where OverrideDepthEnable also turns the depth test off) the
-- materials are $translucent instead (no depth writes, the test stays) and no override is used.
local skyMats, skyMatsFor = nil, nil
local skyMissKey, skyMissUntil = nil, 0
local cvSkyName
-- One face's texture, or nil (never an error texture: then the cube isn't drawn at all). LDR first
-- (the _hdr ones are compressed/float textures only the Sky shader decodes, an UnlitGeneric copy
-- would show wrong colours), then the $basetexture named in the material, loaded as a texture of
-- its own (GMod's "painted" sky is a g_sky material whose GetTexture gives the error texture),
-- then the plain texture path skybox/<name><face>. Each try is recorded in B.skyStats.faces.
-- Measured live (gm_construct, 2026-10-05): GetTexture on the g_sky "painted" faces returns the
-- engine's "error" texture with IsError() false; it drew as the purple/black checker cube.
local function goodTexture(t)
	if not t or t:IsError() or t:Width() <= 8 then return nil end
	local n = string.lower(t:GetName() or "")
	if n == "" or n:find("error", 1, true) then return nil end
	return t
end
local function skyTexture(name, face)
	local path = "skybox/" .. name .. face
	local diag = {}
	B.skyStats.faces = B.skyStats.faces or {}
	B.skyStats.faces[face] = diag
	local src = Material(path)
	local paths = {}
	diag.material = src and not src:IsError() and src:GetShader() or "error"
	if src and not src:IsError() then
		local t = goodTexture(src:GetTexture("$basetexture"))
		if t then diag.used = "GetTexture " .. t:GetName() return t end
		local named = src:GetString("$basetexture")
		if named and named ~= "" then paths[#paths + 1] = named end
	end
	paths[#paths + 1] = path
	for _, p in ipairs(paths) do
		-- A texture loads through a material that names it (Lua has no plain texture loader).
		local probe = CreateMaterial("gmodcraft_sky_probe_" .. p:lower():gsub("%W", "_"), "UnlitGeneric", { ["$basetexture"] = p })
		local t = goodTexture(probe:GetTexture("$basetexture"))
		if t then diag.used = "texture " .. p return t end
	end
	diag.used = "none"
end
local function skyMaterials()
	cvSkyName = cvSkyName or GetConVar("sv_skyname")
	local name = GetGlobalString("gmodcraft_skyname", "")
	if name == "" then name = cvSkyName and cvSkyName:GetString() or "" end
	name = name:match("^[%w_%-/]+$") or ""  -- a server-sent string goes into material paths
	if name == "" then return nil end
	local key = name .. (B.holeVariant == "mat" and "/mat" or "")
	if skyMatsFor == key then return skyMats end
	if skyMissKey == key and RealTime() < skyMissUntil then return nil end  -- a miss: tried again every 5 s
	local out = {}
	local byMat = B.holeVariant == "mat"
	for i, f in ipairs(SKY_FACES) do
		local tex = skyTexture(name, f[1])
		if not tex then
			skyMissKey, skyMissUntil = key, RealTime() + 5
			return nil
		end
		local m = CreateMaterial("gmodcraft_sky_" .. f[1] .. (byMat and "_t" or ""), "UnlitGeneric",
			{ ["$basetexture"] = "color/white", ["$nocull"] = 1, ["$nofog"] = 1, ["$translucent"] = byMat and 1 or 0 })
		m:SetTexture("$basetexture", tex)
		out[i] = m
	end
	skyMats, skyMatsFor = out, key
	B.skyStats.name = name
	return out
end

local CONTENTS_SOLID_BIT = CONTENTS_SOLID or 1

-- The 3D skybox (P5c): the map's sky_camera (origin and scale from the server, server/link.lua)
-- views the skybox room from skyOrigin + eye / scale; that room's leaves have the 2D sky flags,
-- so the 2D sky comes with it. Rendered in RenderScene (before the main view) into a screen-sized
-- target, composited in the opaque hook like the cube (far depth only). B.inSkyView keeps our own
-- draw hooks out of that render.
local sky3dRT, sky3dMat, sky3dSize, sky3dFrame = nil, nil, nil, -1
local function sky3dTarget(w, h)
	local key = w .. "x" .. h .. (B.holeVariant == "mat" and "t" or "")
	if sky3dSize == key then return sky3dRT end
	sky3dSize = key
	sky3dRT = GetRenderTargetEx("gmodcraft_sky3d_" .. w .. "x" .. h, w, h, RT_SIZE_LITERAL, MATERIAL_RT_DEPTH_SEPARATE, 0, 0, IMAGE_FORMAT_RGB888)
	sky3dMat = CreateMaterial("gmodcraft_sky3d_" .. key, "UnlitGeneric",
		{ ["$basetexture"] = sky3dRT:GetName(), ["$nofog"] = 1, ["$translucent"] = B.holeVariant == "mat" and 1 or 0 })
	sky3dMat:SetTexture("$basetexture", sky3dRT)
	return sky3dRT
end
local skyView = { x = 0, y = 0, drawviewmodel = false, drawhud = false, drawmonitors = false, dopostprocess = false, bloomtone = false }
hook.Add("RenderScene", "gmodcraft_sky3d", function(origin, angles, fov)
	if B.inSkyView or not cvSkySolid:GetBool() or not cvSky3d:GetBool() then return end
	if gmodcraft.mcsky and gmodcraft.mcsky.Enabled() then return end  -- K1: the Minecraft sky instead (no skybox room)
	local UG = gmodcraft.underground
	if UG and UG.Update(origin) and UG.Mode() ~= 2 then return end  -- U1: the cave view shows no sky
	local scale = GetGlobalFloat("gmodcraft_skycam_scale", 0)
	if scale <= 0 or bit.band(util.PointContents(origin), CONTENTS_SOLID_BIT) == 0 then return end
	local t0 = SysTime()
	local w, h = ScrW(), ScrH()
	local rt = sky3dTarget(w, h)
	skyView.origin = GetGlobalVector("gmodcraft_skycam_pos", Vector(0, 0, 0)) + origin / scale
	skyView.angles, skyView.fov, skyView.w, skyView.h, skyView.aspect = angles, fov, w, h, w / h
	B.inSkyView = true
	render.PushRenderTarget(rt)
	local ok, err = pcall(render.RenderView, skyView)
	render.PopRenderTarget()
	B.inSkyView = false
	if not ok then B.skyStats.sky3dError = tostring(err) return end
	sky3dFrame = FrameNumber()
	local st = B.skyStats
	st.sky3dFrames, st.sky3dMs = (st.sky3dFrames or 0) + 1, (SysTime() - t0) * 1000
	gmodcraft.PerfAdd("cl Sky 3D render", st.sky3dMs)
end)

function B.SkyInSolid()
	if not cvSkySolid:GetBool() or skyHookFrame == FrameNumber() then return false end
	local eye = EyePos()
	if bit.band(util.PointContents(eye), CONTENTS_SOLID_BIT) == 0 then return false end
	local MS = gmodcraft.mcsky
	if MS and MS.Enabled() and MS.Draw(true) then  -- K1: the Minecraft sky, far depth only
		B.skyStats.frames = B.skyStats.frames + 1
		return true
	end
	if sky3dFrame == FrameNumber() and sky3dMat then
		local t0 = SysTime()
		local byMat = B.holeVariant == "mat"
		render.DepthRange(1, 1)
		if not byMat then render.OverrideDepthEnable(true, false) end
		render.SetMaterial(sky3dMat)
		render.DrawScreenQuad()
		if not byMat then render.OverrideDepthEnable(false, false) end
		render.DepthRange(0, 1)
		local st = B.skyStats
		st.frames, st.composites, st.lastMs = st.frames + 1, (st.composites or 0) + 1, (SysTime() - t0) * 1000
		gmodcraft.PerfAdd("cl Sky in solid", st.lastMs)
		return true
	end
	local m = skyMaterials()
	if not m then B.skyStats.skipped = B.skyStats.skipped + 1 return false end
	local t0 = SysTime()
	local byMat = B.holeVariant == "mat"
	render.DepthRange(1, 1)
	if not byMat then render.OverrideDepthEnable(true, false) end
	for i, f in ipairs(SKY_FACES) do
		local a, b, c, d = f[2], f[3], f[4], f[5]
		render.SetMaterial(m[i])
		render.DrawQuad(eye + Vector(a[1], a[2], a[3]) * SKY_HALF, eye + Vector(b[1], b[2], b[3]) * SKY_HALF,
			eye + Vector(c[1], c[2], c[3]) * SKY_HALF, eye + Vector(d[1], d[2], d[3]) * SKY_HALF)
	end
	if not byMat then render.OverrideDepthEnable(false, false) end
	render.DepthRange(0, 1)
	local st = B.skyStats
	st.frames, st.lastMs = st.frames + 1, (SysTime() - t0) * 1000
	gmodcraft.PerfAdd("cl Sky in solid", st.lastMs)
	return true
end

-- U1 (client/underground.lua): with the eye in the map's brushes, colour and depth are cleared to the
-- cave colour here instead. The sky and the world brushes drew before this hook, so they go; the
-- engine's entities (props, NPCs, static props) draw after it and stay, then the blocks. No sky in
-- solid (except gmodcraft_underground_view 2) and no hole stencil (no world left to cut).
B.undergroundStats = B.undergroundStats or { clears = 0 }
hook.Add("PreDrawOpaqueRenderables", "gmodcraft_holes", function(depth, skybox, sky3d)
	if skipView(depth, skybox, sky3d) then return end
	local UG = gmodcraft.underground
	if UG and UG.Active() then
		render.Clear(UG.R, UG.G, UG.B, 255, true, false)
		B.undergroundStats.clears = B.undergroundStats.clears + 1
		if UG.Mode() == 2 then B.SkyInSolid() end  -- far depth only: fills what nothing else covers
		UG.FogFallback()
		return
	end
	if not B.SkyInSolid() then B.McSkyFar() end  -- independent of the holes and the block drawing
	if (B.holeBoxes or 0) == 0 or cvHoles:GetInt() ~= 1 or not ready() or not cvOpaque:GetBool() then return end
	local t0 = SysTime()
	B.HoleStencilPass()
	local st = B.holeStats
	st.passes, st.lastMs = st.passes + 1, (SysTime() - t0) * 1000
	gmodcraft.PerfAdd("cl Holes stencil", st.lastMs)
end)
