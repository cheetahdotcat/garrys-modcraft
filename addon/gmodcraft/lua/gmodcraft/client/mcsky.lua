-- The Minecraft sky instead of the map's skybox (K1, gmodcraft_mc_sky 1), client side.
--
-- Minecraft writes what its sky renderer would draw this frame (client link McSky, v37: sun / moon /
-- star angles, sky, fog and sunrise colours, rain, moon phase; read here with gmodcraft.McSky()).
-- client/blocks.lua owns the sky hooks and calls MS.Draw:
--   PreDrawSkyBox    -> MS.Draw(false), then returns true: the engine skips the 3D and 2D skybox;
--   PostDraw2DSkyBox -> MS.Draw(false) on maps without a 3D skybox (PreDrawSkyBox doesn't fire there);
--   B.SkyInSolid     -> MS.Draw(true): the eye in map geometry (no engine sky) and underground mode 2.
-- Drawn around the eye at SKY_R units (the skybox hooks: in a cam.Start3D view at the origin), no depth writes ($translucent / $additive). DepthRange(0, 0)
-- in the skybox hooks (over whatever the pass holds, the world draws after it); DepthRange(1, 1) in
-- solid (only the pixels nothing else covered). Like vanilla: the clear colour is the fog colour
-- (the horizon and everything below), the sky colour above it, the sunrise / sunset fan toward the
-- sun, then sun, moon and stars additive, faded by rain. Approximations: the horizon gradient is a
-- fixed band (vanilla: the sky fog distance), sun and moon are plain squares (no Mojang textures in
-- the addon), the moon phase is the lit part as a rectangle, the stars are our own random set.
-- Not replaced: the world fog (a fog controller's colour stays), clouds.

local MS = gmodcraft.mcsky or {}
gmodcraft.mcsky = MS

-- ---- pure geometry (the headless test, module/test/mcsky_test.py, loads only this part) ----------

-- Vanilla turns a celestial body with Ry(-90) * Rx(a) from Minecraft's +Y; Minecraft (x, y, z) is
-- Source (x, -z, y). Returns the 3x3 rotation (rows) taking a point p of that unturned frame to
-- Source axes: the sun at p = (0, 1, 0) lands on (-sin a, 0, cos a) (east, up, west over the day).
function MS.Basis(a)
	local s, c = math.sin(a), math.cos(a)
	return 0, -s, -c,
		-1, 0, 0,
		0, c, -s
end

-- The lit part of the moon for MoonPhase.index() (0 full, 4 new): fraction of the width, and which
-- side it sits on (+1 / -1; waning and waxing light opposite sides).
local MOON_LIT = { [0] = 1, 0.75, 0.5, 0.25, 0, 0.25, 0.5, 0.75 }
function MS.MoonLit(phase)
	phase = math.floor(tonumber(phase) or 0) % 8
	return MOON_LIT[phase], phase < 4 and 1 or -1
end

-- The sunrise / sunset fan (vanilla renderSunriseAndSunset: Rx(90) * Rz(sin a < 0 and 270 or 90) *
-- scale(1, 1, alpha) over a fan of centre (0, 100, 0) and 16 rim points (120 sin f, 120 cos f,
-- -40 cos f)), in Source axes, 100 = the sky radius. Returns the centre and the rim (alpha 0).
function MS.SunriseFan(sunAngle, alpha)
	local side = math.sin(sunAngle) < 0 and 1 or -1  -- +1: the sun is east (+X), rising
	local rim = {}
	for i = 0, 16 do
		local f = i * 2 * math.pi / 16
		local cf = math.cos(f)
		-- Minecraft (side * 120 cos f, 40 alpha cos f, 120 sin f) -> Source (x, -z, y)
		rim[#rim + 1] = { side * 120 * cf, -120 * math.sin(f), 40 * alpha * cf }
	end
	return { side * 100, 0, 0 }, rim
end

-- Sky colour at an elevation (radians): the fog colour at and below the horizon, the sky colour
-- from about 15 degrees up, linear in between (vanilla blends by the sky fog distance).
function MS.DomeColor(elev, sky, fog)
	local t = math.max(0, math.min(1, math.sin(elev) * 4))
	return fog[1] + (sky[1] - fog[1]) * t, fog[2] + (sky[2] - fog[2]) * t, fog[3] + (sky[3] - fog[3]) * t
end

-- 1500 tries at random stars like vanilla's buildStars (its own seed, our own generator): a point in
-- the unit cube, kept when 0.1 < |p| < 1, pushed out to radius 100, size 0.15..0.25, a random roll.
-- Returns { {x, y, z, size, roll}, ... } in the unturned frame (Minecraft axes, 100 = sky radius).
function MS.Stars(count, seed)
	local state = seed or 10842
	local function rnd()  -- LCG (Numerical Recipes), deterministic and independent of math.random
		state = (state * 1664525 + 1013904223) % 4294967296
		return state / 4294967296
	end
	local out = {}
	for _ = 1, count or 1500 do
		local x, y, z = rnd() * 2 - 1, rnd() * 2 - 1, rnd() * 2 - 1
		local size = 0.15 + rnd() * 0.1
		local roll = rnd() * math.pi * 2
		local d = x * x + y * y + z * z
		if d > 0.010000001 and d < 1 then
			local k = 100 / math.sqrt(d)
			out[#out + 1] = { x * k, y * k, z * k, size, roll }
		end
	end
	return out
end

if not CLIENT or not CreateClientConVar then return MS end  -- the headless tests load only the logic above

local cvOn = CreateClientConVar("gmodcraft_mc_sky", "0", true, false,
	"Garry's Modcraft: replace the map's skybox (2D and 3D) with the Minecraft sky as your Minecraft client sees it (time of day, sunrise / sunset, sun, moon phase, stars, rain). 1 on, 0 off (default)")

local SKY_R = 64        -- units: the sky's radius around the eye (inside the near / far planes)
local K = SKY_R / 100   -- vanilla's sky is 100 units out
MS.stats = MS.stats or { frames = 0, far = 0, noData = 0 }

function MS.Enabled() return cvOn:GetBool() end

-- This frame's McSky (nil: Minecraft not in a world or nothing written: the map's sky stays).
local cache, cacheFrame = nil, -1
function MS.State()
	local fn = FrameNumber()
	if cacheFrame == fn then return cache end
	cacheFrame = fn
	cache = nil
	local CL = gmodcraft.clientLink
	if gmodcraft.McSky and CL and CL.McInWorld and CL.McInWorld() then
		local s = gmodcraft.McSky()
		-- (all black: the camera's attribute probe before its first tick, defaults only)
		local blank = s and s.sky.r + s.sky.g + s.sky.b + s.fog.r + s.fog.g + s.fog.b == 0
		if s and s.valid and not blank then cache = s end
	end
	if not cache then MS.stats.noData = MS.stats.noData + 1 end
	return cache
end

-- Materials and the star mesh, made on first use (none at load: the headless loaders stub GMod).
local mats, starMesh
local function materials()
	if mats then return mats end
	local base = { ["$basetexture"] = "color/white", ["$vertexcolor"] = 1, ["$vertexalpha"] = 1, ["$nofog"] = 1, ["$nocull"] = 1 }
	local function make(name, extra)
		local t = table.Copy(base)
		for k, v in pairs(extra) do t[k] = v end
		return CreateMaterial(name, "UnlitGeneric", t)
	end
	mats = {
		dome = make("gmodcraft_mcsky_dome", { ["$translucent"] = 1 }),
		glow = make("gmodcraft_mcsky_glow", { ["$additive"] = 1 }),
		stars = make("gmodcraft_mcsky_stars", { ["$additive"] = 1 }),
	}
	return mats
end

local function buildStars()
	local stars = MS.Stars(1500)
	local m = Mesh(materials().stars)
	mesh.Begin(m, MATERIAL_QUADS, #stars)
	for _, st in ipairs(stars) do
		local px, py, pz, size, roll = st[1], st[2], st[3], st[4] * K, st[5]
		local d = Vector(px, py, pz)
		d:Normalize()
		-- two axes across the direction, turned by the roll
		local up = math.abs(d.y) < 0.99 and Vector(0, 1, 0) or Vector(1, 0, 0)
		local u = d:Cross(up) u:Normalize()
		local v = d:Cross(u) v:Normalize()
		local cr, sr = math.cos(roll), math.sin(roll)
		local a, b = u * cr + v * sr, v * cr - u * sr
		local c = d * SKY_R
		for _, q in ipairs({ { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } }) do
			mesh.Position(c + a * (q[1] * size) + b * (q[2] * size))
			mesh.TexCoord(0, 0.5, 0.5)
			mesh.Color(255, 255, 255, 255)
			mesh.AdvanceVertex()
		end
	end
	mesh.End()
	return m
end

local function byte(x) return math.floor(math.max(0, math.min(1, x)) * 255 + 0.5) end

-- The dome: latitude rings around the eye, vertex colours from MS.DomeColor (uniform fog colour
-- without the overworld skybox: the Nether, and the End approximated).
local RINGS, SEGS = 12, 16
local function drawDome(eye, s, sky, fog)
	render.SetMaterial(materials().dome)
	mesh.Begin(MATERIAL_TRIANGLES, RINGS * SEGS * 2)
	local function vert(e, az)
		local ce = math.cos(e)
		mesh.Position(eye + Vector(ce * math.cos(az) * SKY_R, ce * math.sin(az) * SKY_R, math.sin(e) * SKY_R))
		local r, g, b = MS.DomeColor(e, sky, fog)
		if not s.overworld then r, g, b = fog[1], fog[2], fog[3] end
		mesh.TexCoord(0, 0.5, 0.5)
		mesh.Color(byte(r), byte(g), byte(b), 255)
		mesh.AdvanceVertex()
	end
	for i = 0, RINGS - 1 do
		local e0, e1 = -math.pi / 2 + math.pi * i / RINGS, -math.pi / 2 + math.pi * (i + 1) / RINGS
		for j = 0, SEGS - 1 do
			local a0, a1 = 2 * math.pi * j / SEGS, 2 * math.pi * (j + 1) / SEGS
			vert(e0, a0) vert(e1, a0) vert(e1, a1)
			vert(e0, a0) vert(e1, a1) vert(e0, a1)
		end
	end
	mesh.End()
end

local function drawSunrise(eye, s)
	local c = s.sunrise
	if not c or (c.a or 0) <= 0.001 then return end
	local centre, rim = MS.SunriseFan(s.sunAngle, c.a)
	render.SetMaterial(materials().dome)
	mesh.Begin(MATERIAL_TRIANGLES, #rim - 1)
	local cr, cg, cb = byte(c.r), byte(c.g), byte(c.b)
	for i = 1, #rim - 1 do
		mesh.Position(eye + Vector(centre[1], centre[2], centre[3]) * K)
		mesh.TexCoord(0, 0.5, 0.5) mesh.Color(cr, cg, cb, byte(c.a)) mesh.AdvanceVertex()
		for _, p in ipairs({ rim[i], rim[i + 1] }) do
			mesh.Position(eye + Vector(p[1], p[2], p[3]) * K)
			mesh.TexCoord(0, 0.5, 0.5) mesh.Color(cr, cg, cb, 0) mesh.AdvanceVertex()
		end
	end
	mesh.End()
end

local turn = Matrix()
local function pushTurn(eye, a)
	local r11, r12, r13, r21, r22, r23, r31, r32, r33 = MS.Basis(a)
	turn:SetUnpacked(r11, r12, r13, eye.x, r21, r22, r23, eye.y, r31, r32, r33, eye.z, 0, 0, 0, 1)
	cam.PushModelMatrix(turn)
end

-- A square in the unturned frame at height SKY_R (x from x0 to x1, z from -h to h), additive.
local function square(x0, x1, h, r, g, b)
	mesh.Begin(MATERIAL_QUADS, 1)
	for _, q in ipairs({ { x0, -h }, { x1, -h }, { x1, h }, { x0, h } }) do
		mesh.Position(Vector(q[1] * K, SKY_R, q[2] * K))
		mesh.TexCoord(0, 0.5, 0.5) mesh.Color(byte(r), byte(g), byte(b), 255) mesh.AdvanceVertex()
	end
	mesh.End()
end

local function drawCelestial(eye, s)
	local rain = math.max(0, math.min(1, s.rainBrightness or 1))
	render.SetMaterial(materials().glow)
	-- the sun: a bright core in a soft square halo (vanilla: 30 units half-size at 100)
	pushTurn(eye, s.sunAngle)
	square(-30, 30, 30, 0.35 * rain, 0.3 * rain, 0.18 * rain)
	square(-15, 15, 15, 1.0 * rain, 0.97 * rain, 0.8 * rain)
	cam.PopModelMatrix()
	-- the moon (20 at 100), its lit part for the phase
	local frac, side = MS.MoonLit(s.moonPhase)
	if frac > 0 then
		pushTurn(eye, s.moonAngle)
		local h = 10
		local x0, x1 = -h, -h + 2 * h * frac
		if side < 0 then x0, x1 = h - 2 * h * frac, h end
		square(x0, x1, h, 0.8 * rain, 0.82 * rain, 0.9 * rain)
		cam.PopModelMatrix()
	end
	-- the stars
	local bright = (s.starBrightness or 0) * rain
	if bright > 0.003 then
		starMesh = starMesh or buildStars()
		local m = materials().stars
		m:SetVector("$color", Vector(bright, bright, bright))
		render.SetMaterial(m)
		pushTurn(eye, s.starAngle)
		starMesh:Draw()
		cam.PopModelMatrix()
	end
end

-- Draws the Minecraft sky. far: only where nothing drew (the depth buffer still at the far plane).
-- false when there is nothing to draw (no data: the caller leaves the map's sky).
function MS.Draw(far)
	local s = MS.State()
	if not s then return false end
	local t0 = SysTime()
	local sky = { s.sky.r, s.sky.g, s.sky.b }
	local fog = { s.fog.r, s.fog.g, s.fog.b }
	-- In the skybox hooks a view of our own at the origin (whatever view the engine has set up for
	-- the skybox pass); in solid (the opaque hook, the main view) world space around the eye.
	local eye = far and EyePos() or Vector(0, 0, 0)
	if not far then cam.Start3D(eye, EyeAngles()) end
	local z = far and 1 or 0
	render.DepthRange(z, z)
	drawDome(eye, s, sky, fog)
	if s.overworld then
		drawSunrise(eye, s)
		drawCelestial(eye, s)
	end
	render.DepthRange(0, 1)
	if not far then cam.End3D() end
	local st = MS.stats
	st.frames = st.frames + 1
	if far then st.far = st.far + 1 end
	st.lastMs = (SysTime() - t0) * 1000
	if gmodcraft.PerfAdd then gmodcraft.PerfAdd("cl MC sky", st.lastMs) end
	return true
end

return MS
