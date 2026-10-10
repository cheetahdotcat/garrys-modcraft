-- Underground view mode (U1), client side.
--
-- With the render eye inside the map's brushes (a tunnel Minecraft dug into or below the map) the
-- engine still draws the world: no vis from a solid leaf, so every leaf goes (frustum culled only)
-- and the brush the eye is in is back-face culled. Map faces seen from behind or below show through
-- Minecraft's blocks, and every pixel nothing covers became our sky (client/blocks.lua SkyInSolid):
-- the user saw "the skybox replacement and the cutting between the two worlds".
--
-- While U.active, client/blocks.lua's opaque pre-hook clears colour and depth to the cave colour
-- instead (that wipes the 2D/3D sky and the world brushes, which drew before it), skips the sky in
-- solid, the hole stencil and the 3D sky render, and the frame shows Minecraft's blocks, MC entities
-- and the GMod entities the engine draws after the hook (props, NPCs, static props). Dark cave fog
-- in the same colour (SetupWorldFog, with a fallback in the pre-hook) fades distant blocks into it.
--
-- The flag, once per frame (RenderScene's origin is the render eye: F5 and gmodcraft_camera 0 come
-- free; the pre-hook recomputes from EyePos() if RenderScene didn't run):
--   on  the frame the eye is in CONTENTS_SOLID;
--   off only when the eye is in air AND none of six probes BAND units out along the axes is solid.
-- So an eye bobbing across a brush face (a hole's opening) keeps the cave frame: Minecraft's walk
-- bob moves the eye <= ~4 u up/down and ~2 u sideways (view.lua, bob <= 0.1 * 40 u), BAND is 6 u.
-- The probes run only while the flag is on and the eye is in air (normally: never).

local U = gmodcraft.underground or {}
gmodcraft.underground = U

U.BAND = 6                      -- units: the eye must be this far out of every brush to leave the cave view
U.FOG_START, U.FOG_END = 160, 1400  -- units (4 .. 35 blocks)
U.R, U.G, U.B = 14, 11, 9       -- the cave colour: clear colour == fog colour (else fogged faces and bare pixels differ)

local PROBES = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } }

-- Pure: the next flag from the last one. eyeSolid: the eye's contents are solid. probeSolid(dx, dy, dz)
-- tells whether eye + (dx, dy, dz) is solid; called only while `active` and the eye is in air.
function U.Next(active, eyeSolid, probeSolid, band)
	if eyeSolid then return true end
	if not active then return false end
	band = band or U.BAND
	for _, d in ipairs(PROBES) do
		if probeSolid(d[1] * band, d[2] * band, d[3] * band) then return true end
	end
	return false
end

if not CLIENT or not CreateClientConVar then return U end  -- the headless tests load only the logic above

local cvOn = CreateClientConVar("gmodcraft_underground_view", "0", true, false,
	"Garry's Modcraft: with the view inside the map's brushes (a dug tunnel), draw a Minecraft-only frame: 1 cave colour + fog instead of the map and its sky, 2 the same but the map's sky still fills what nothing covers, 0 off (default: the map and the sky in solid as before; user 2026-10-07: seeing outside matters more)")

local SOLID = CONTENTS_SOLID or 1
U.active = false
U.frame = -1
U.stats = U.stats or { frames = 0, toggles = 0, fogHook = 0, fogFallback = 0, probes = 0 }

local function solidAt(p)
	return bit.band(util.PointContents(p), SOLID) ~= 0
end

-- Computes the flag for this frame (once; later calls return the cached value).
function U.Update(eye)
	local fn = FrameNumber()
	if U.frame == fn then return U.active end
	U.frame = fn
	local was = U.active
	if not cvOn:GetBool() then
		U.active = false
	else
		local eyeSolid = solidAt(eye)
		U.eyeSolid = eyeSolid
		U.active = U.Next(was, eyeSolid, function(dx, dy, dz)
			U.stats.probes = U.stats.probes + 1
			return solidAt(eye + Vector(dx, dy, dz))
		end)
	end
	if U.active ~= was then
		U.stats.toggles = U.stats.toggles + 1
		gmodcraft.Log("render", "underground view %s", U.active and "on" or "off")
	end
	if U.active then U.stats.frames = U.stats.frames + 1 end
	return U.active
end

-- The flag for the frame being drawn (computed now if RenderScene didn't).
function U.Active()
	if U.frame ~= FrameNumber() then return U.Update(EyePos()) end
	return U.active
end

function U.Mode() return cvOn:GetInt() end

-- RenderScene runs before the frame's views, with the render eye. Added here, before blocks.lua's
-- gmodcraft_sky3d RenderScene hook reads the flag (hooks of one event run in no fixed order, so that
-- one calls U.Active(), which computes it if this hasn't run yet).
hook.Add("RenderScene", "gmodcraft_underground", function(origin)
	U.Update(origin)
end)

local fogFrame = -1
local function applyFog()
	render.FogMode(MATERIAL_FOG_LINEAR)
	render.FogStart(U.FOG_START)
	render.FogEnd(U.FOG_END)
	render.FogMaxDensity(1)
	render.FogColor(U.R, U.G, U.B)
end
U.ApplyFog = applyFog

hook.Add("SetupWorldFog", "gmodcraft_underground", function()
	if U.frame ~= FrameNumber() or not U.active then return end
	if gmodcraft.blocks and gmodcraft.blocks.inSkyView then return end  -- mode 2's 3D sky render keeps its own fog
	applyFog()
	fogFrame = FrameNumber()
	U.stats.fogHook = U.stats.fogHook + 1
	return true
end)

-- client/blocks.lua's draw hooks while U.active: the fog set again before our own draws (the engine
-- may skip world fog setup for a view in solid, or change it between passes). Counts the frames
-- SetupWorldFog didn't fire in (the Render debug stats: fogHook vs fogFallback).
function U.FogFallback()
	if fogFrame ~= FrameNumber() then U.stats.fogFallback = U.stats.fogFallback + 1 end
	applyFog()
end

return U
