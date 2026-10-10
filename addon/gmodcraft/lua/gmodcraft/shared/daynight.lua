-- Day-night sync (protocol v43), both realms.
--
-- The rule gmodcraft_sun_sync (server, admins: the Server page) says who owns the time of day:
--   0 off                nobody: Minecraft's clock stands (its start-up default), the map stays as built;
--   1 Minecraft -> GMod  (default) Minecraft's overworld time (server link McServerSky) drives the map:
--                        world brightness (the sun's light style: 0, or a named light_environment's own
--                        style, gm_construct's 33; 4 steps per sunrise / sunset, c f h k m; clients
--                        then download the lightmaps again; Minecraft blocks scale their GMod light the
--                        same way), env_sun (one is made if the map has
--                        none), shadow_control (prop shadows), env_fog_controller colour and env_skypaint
--                        (the painted sky; clients with gmodcraft_mc_sky 1 paint the Minecraft sky over it).
--                        Stands down while StormFox2 runs (it owns the map's lighting then);
--   2 GMod -> Minecraft  StormFox2's time (StormFox2.Time.Get / GetSpeed / IsPaused) drives Minecraft's
--                        clock (kHostEvSetDayTime). Without StormFox2 nothing drives it.
-- The mode reaches the Minecraft server in ServerState::sunSync, which runs its clock in modes 1 and 2.
-- gmodcraft_sun_sync_skip_maps lists maps the sync leaves alone (indoor maps: where the sun shares style
-- 0 with the lamps, interiors darken too); Minecraft's clock still runs there. Leaving mode 1 (or a skipped map) restores what was changed.
--
-- gmodcraft_mc_sky_default (server, replicated): the Minecraft sky setting players get until they pick
-- their own (client/mcsky.lua).

local DN = gmodcraft.daynight or {}
gmodcraft.daynight = DN

-- ---- pure logic (module/test/daynight_test.py loads only this part) ------------------------------

DN.TICKS_PER_DAY = 24000
DN.LETTER_DAY = "m"            -- light style 0's normal value: the map as compiled
DN.NIGHT_LETTER = "c"          -- default darkest letter (gmodcraft_sun_sync_night)
DN.SKY_DAY, DN.SKY_NIGHT = 15, 4  -- Minecraft's sky light level at noon / at night (SKY_LIGHT_LEVEL)
DN.FOG_NOON = { 0.753, 0.847, 1.0 }  -- Minecraft's overworld fog colour by day (#C0D8FF): the map's own fog there
DN.SHADOW_MIN_ELEV = math.rad(20)  -- prop shadows never flatter than this (long smears)

local function clamp(x, a, b) return x < a and a or (x > b and b or x) end

-- Light style letter for Minecraft's sky light level: day (15) -> 'm', night (4) -> night letter,
-- linear in between, rounded. nightLetter: 'a'..'m' (else the default).
-- In DN.STEPS steps: each step makes every client download its lightmaps again (a hitch), so a sunrise
-- or sunset is a few visible steps (night 'c': c, f, h, k, m), not one per letter.
DN.STEPS = 4
function DN.Letter(skyLight, nightLetter)
	local n = type(nightLetter) == "string" and nightLetter:match("^[a-m]$") and nightLetter or DN.NIGHT_LETTER
	local lo, hi = n:byte(), DN.LETTER_DAY:byte()
	local f = clamp(((tonumber(skyLight) or DN.SKY_DAY) - DN.SKY_NIGHT) / (DN.SKY_DAY - DN.SKY_NIGHT), 0, 1)
	f = math.floor(f * DN.STEPS + 0.5) / DN.STEPS
	return string.char(math.floor(lo + (hi - lo) * f + 0.5))
end

-- The engine's brightness for a light style letter relative to 'm' (the map as compiled): Source
-- scales a style's lightmap by (letter - 'a') * 22 / 264.
function DN.StyleFactor(letter)
	local b = type(letter) == "string" and letter:byte(1) or nil
	if not b or b < 97 or b > 122 then return 1 end
	return (b - 97) * 22 / 264
end

-- The night factor for one light sample: all of it when the sun shares style 0 with everything static;
-- with the sun in its own style only where the sample sees the sky (else the map's lamps light it).
function DN.SampleFactor(letter, sunStyle, seesSky)
	local f = DN.StyleFactor(letter)
	if (tonumber(sunStyle) or 0) == 0 or seesSky then return f end
	return 1
end

-- The light style that carries a map's sun and sky light: a light_environment with a name was compiled
-- into its own switchable style (32 and up; gm_construct's "sun_light" is 33), else everything static
-- shares style 0. styles: the light_environments' style values (numbers or strings; nil / bad ignored).
function DN.SunStyleOf(styles)
	for _, s in pairs(styles or {}) do  -- (pairs: holes from unreadable values)
		local n = math.floor(tonumber(s) or -1)
		if n >= 32 and n < 64 then return n end
	end
	return 0
end

-- The sun's direction (unit vector, Source axes) for Minecraft's sun angle (radians, McServerSky):
-- Minecraft (-sin a, cos a, 0), and Minecraft (x, y, z) is Source (x, -z, y). Noon: straight up;
-- sunrise (a = -pi/2): east (+x).
function DN.SunDir(a)
	return -math.sin(a), 0, math.cos(a)
end

-- Pitch / yaw (Source degrees, QAngle convention: pitch +90 looks down) of the direction (x, y, z).
function DN.Angles(x, y, z)
	local len = math.sqrt(x * x + y * y + z * z)
	if len < 1e-9 then return 0, 0 end
	local pitch = -math.deg(math.asin(clamp(z / len, -1, 1)))
	local yaw = (x * x + y * y) > 1e-12 and math.deg(math.atan2(y, x)) or 0
	return pitch, yaw
end

-- The direction prop shadows are cast in (pointing down, away from the light), as pitch / yaw: from the
-- sun by day, from the moon when the sun is below the horizon, never flatter than SHADOW_MIN_ELEV.
function DN.ShadowAngles(sunAngle, moonAngle)
	local x, y, z = DN.SunDir(sunAngle)
	if z < 0 then x, y, z = DN.SunDir(moonAngle or (sunAngle + math.pi)) end
	local h = math.sqrt(x * x + y * y)
	local elev = math.max(math.atan2(z, h), DN.SHADOW_MIN_ELEV)
	if h < 1e-9 then x, h = 1, 1 end
	local c = math.cos(elev)
	return DN.Angles(-x / h * c, -y / h * c, -math.sin(elev))
end

-- The map's fog colour (0..255 each) tinted by Minecraft's: unchanged at Minecraft's day fog, darker
-- and warmer toward dusk and night (per channel: map * mc / FOG_NOON, clamped).
function DN.FogColor(orig, mcFog)
	local out = {}
	for i = 1, 3 do
		local k = clamp((tonumber(mcFog[i]) or DN.FOG_NOON[i]) / DN.FOG_NOON[i], 0, 1.25)
		out[i] = math.floor(clamp((tonumber(orig[i]) or 255) * k, 0, 255) + 0.5)
	end
	return out
end

-- StormFox2 time (minutes since midnight, 0..1440) <-> Minecraft time of day (ticks, 0 = 06:00).
function DN.MinutesToTicks(minutes)
	local t = math.floor(((tonumber(minutes) or 720) / 60 - 6) * 1000 + 0.5)
	return t % DN.TICKS_PER_DAY
end

function DN.TicksToMinutes(ticks)
	return ((((tonumber(ticks) or 6000) / 1000) + 6) * 60) % 1440
end

-- StormFox2's speed (game seconds per real second) -> Minecraft clock rate (ticks per server tick):
-- vanilla runs 20 ticks per second at 3.6 game seconds per tick = 72 game seconds per real second.
function DN.Rate(speed, paused)
	if paused then return 0 end
	local s = tonumber(speed) or 0
	if s ~= s or s <= 0 then return 0 end
	return math.min(s / 72, 1000)
end

-- Signed shortest difference a - b of two times of day (ticks), in -12000 .. 12000.
function DN.TodDiff(a, b)
	local d = (a - b) % DN.TICKS_PER_DAY
	if d > DN.TICKS_PER_DAY / 2 then d = d - DN.TICKS_PER_DAY end
	return d
end

-- Does the skip list (names separated by spaces / commas; a trailing * matches a prefix) name map?
function DN.MapSkipped(list, map)
	map = string.lower(map or "")
	for name in string.gmatch(string.lower(list or ""), "[^%s,;]+") do
		if name:sub(-1) == "*" then
			if map:sub(1, #name - 1) == name:sub(1, -2) then return true end
		elseif name == map then
			return true
		end
	end
	return false
end

-- Mode clamp: 0 off, 1 Minecraft -> GMod, 2 GMod -> Minecraft.
function DN.ModeOf(v)
	v = math.floor(tonumber(v) or 1)
	return (v >= 0 and v <= 2) and v or 1
end

if not CreateConVar then return DN end  -- the headless tests load only the logic above

DN.NET_STYLE = "gmodcraft_lightstyle"

-- Server rule; replicated so the client's settings (and the Server page) can show it.
-- (archived on the server only: a client would keep a dedicated server's value in its own client.vdf)
DN.cvDefaultSky = CreateConVar("gmodcraft_mc_sky_default", "0", SERVER and bit.bor(FCVAR_ARCHIVE, FCVAR_REPLICATED) or FCVAR_REPLICATED,
	"Garry's Modcraft: the Minecraft sky (gmodcraft_mc_sky) for players who haven't chosen it themselves. 1 on, 0 off (default)")

if SERVER then
	util.AddNetworkString(DN.NET_STYLE)
	DN.cvMode = CreateConVar("gmodcraft_sun_sync", "1", FCVAR_ARCHIVE,
		"Garry's Modcraft: day-night sync. 0 off, 1 Minecraft's time drives GMod's world (brightness, sun, shadows, fog, sky; default), 2 StormFox2's time drives Minecraft")
	DN.cvSkip = CreateConVar("gmodcraft_sun_sync_skip_maps", "", FCVAR_ARCHIVE,
		"Garry's Modcraft: maps the day-night sync leaves alone (indoor maps), separated by spaces or commas; a trailing * matches a prefix")
	DN.cvNight = CreateConVar("gmodcraft_sun_sync_night", DN.NIGHT_LETTER, FCVAR_ARCHIVE,
		"Garry's Modcraft: the darkest light style letter at night (a..m; m = as bright as day). Default c")
end

-- ---- server --------------------------------------------------------------------------------

if SERVER then
	DN.stats = DN.stats or { steps = 0, letter = DN.LETTER_DAY, mode = 1, skipped = false, stormfox = false, sends = 0, sets = 0 }
	local orig = {}        -- what the map had before the sync touched it (restored on off)
	local touched = false  -- the map is changed now
	local letter = DN.LETTER_DAY
	local warned = {}

	function DN.Mode() return DN.ModeOf(DN.cvMode:GetInt()) end
	function DN.MapSkippedNow() return DN.MapSkipped(DN.cvSkip:GetString(), game.GetMap()) end
	function DN.StormFox()
		local T = istable(StormFox2) and istable(StormFox2.Time) and StormFox2.Time or nil
		return T and isfunction(T.Get) and T or nil
	end

	local function once(key, fmt, ...)
		if warned[key] then return end
		warned[key] = true
		gmodcraft.Info(fmt, ...)
	end

	local function sendStyle(ply)
		net.Start(DN.NET_STYLE)
		net.WriteString(letter)
		net.WriteUInt(DN.SunStyle(), 8)  -- which light style: blocks scale only the sun's share (client)
		if ply then net.Send(ply) else net.Broadcast() end
	end

	-- The style the map's sun is in (DN.SunStyleOf), looked up once per map: a named light_environment stays
	-- an entity at run time (unnamed ones are compiled away and light style 0).
	local sunStyle
	function DN.SunStyle()
		if sunStyle then return sunStyle end
		local styles = {}
		for _, e in ipairs(ents.FindByClass("light_environment")) do
			local ok, v = pcall(e.GetInternalVariable, e, "m_iStyle")
			styles[#styles + 1] = ok and v or nil
			styles[#styles + 1] = (e:GetKeyValues() or {}).style
		end
		sunStyle = DN.SunStyleOf(styles)
		DN.stats.sunStyle = sunStyle
		return sunStyle
	end

	local function setLetter(l)
		if l == letter then return end
		letter = l
		engine.LightStyle(DN.SunStyle(), l)
		DN.stats.steps = DN.stats.steps + 1
		DN.stats.letter = l
		sendStyle()
	end

	local function first(class)
		local list = ents.FindByClass(class)
		return list[1]
	end

	local function vecStr(v) return string.format("%.4f %.4f %.4f", v.x, v.y, v.z) end

	-- Entities are only touched when what they'd get changes (sun / shadow angles in half degrees, colours
	-- in whole steps): a stopped clock costs nothing, a running one an update every few seconds.
	local function changed(key, value)
		orig.last = orig.last or {}
		if orig.last[key] == value then return false end
		orig.last[key] = value
		DN.stats.updates = (DN.stats.updates or 0) + 1
		return true
	end
	local function half(deg) return math.floor(deg * 2 + 0.5) / 2 end

	-- env_sun: direction from use_angles / pitch / angle (CSun::Activate), off below the horizon.
	local function applySun(sky)
		local sun = first("env_sun")
		if not IsValid(sun) then
			sun = ents.Create("env_sun")
			if not IsValid(sun) then return end
			sun:SetKeyValue("size", "16")
			sun:SetKeyValue("overlaysize", "-1")
			sun:Spawn()
			orig.sunMade = sun
		elseif not orig.sun then
			local kv = sun:GetKeyValues()
			orig.sun = { ent = sun, useAngles = kv.use_angles, pitch = kv.pitch, angle = kv.angle, dir = sun:GetInternalVariable("m_vDirection") }
		end
		local x, y, z = DN.SunDir(sky.sunAngle)
		local pitch, yaw = DN.Angles(x, y, z)
		pitch, yaw = half(pitch), half(yaw)
		if not changed("sun", pitch .. " " .. yaw) then return end
		-- SetupLightNormalFromProps makes the light's travel direction (cos angle cos pitch, sin angle
		-- cos pitch, sin pitch) (pitch -90: straight down); env_sun points m_vDirection back at the sun,
		-- so the keyvalues describe the opposite of (x, y, z): pitch asin(-z), angle yaw + 180.
		sun:SetKeyValue("use_angles", "1")
		sun:SetKeyValue("pitch", string.format("%.3f", pitch))
		sun:SetKeyValue("angle", string.format("%.3f", (yaw + 180) % 360))
		sun:SetAngles(Angle(0, (yaw + 180) % 360, 0))
		sun:Activate()
		DN.stats.sunDir = string.format("%.2f %.2f %.2f", x, y, z)
		local d = sun:GetInternalVariable("m_vDirection")
		if isvector(d) then DN.stats.sunEngineDir = vecStr(d) end
		local up = z > -0.02
		if up ~= orig.sunOn then
			sun:Fire(up and "TurnOn" or "TurnOff")
			orig.sunOn = up
		end
	end

	local function applyShadows(sky)
		local sc = first("shadow_control")
		if not IsValid(sc) then
			sc = ents.Create("shadow_control")
			if not IsValid(sc) then return end
			sc:Spawn()
			orig.shadowMade = sc
		elseif not orig.shadow then
			orig.shadow = { ent = sc, angles = sc:GetAngles() }
		end
		local p, y = DN.ShadowAngles(sky.sunAngle, sky.moonAngle)
		local a = string.format("%.1f %.1f 0", half(p), half(y))
		if changed("shadow", a) then sc:Fire("SetAngles", a) end
	end

	local function parseColor(s)
		local r, g, b = string.match(tostring(s or ""), "^%s*(%d+)%s+(%d+)%s+(%d+)")
		if r then return { tonumber(r), tonumber(g), tonumber(b) } end
	end

	local function applyFog(sky)
		for _, fc in ipairs(ents.FindByClass("env_fog_controller")) do
			orig.fog = orig.fog or {}
			local o = orig.fog[fc]
			if not o then
				local kv = fc:GetKeyValues()
				o = { c1 = parseColor(kv.fogcolor), c2 = parseColor(kv.fogcolor2) }
				orig.fog[fc] = o
			end
			local mc = { sky.fog.r, sky.fog.g, sky.fog.b }
			if o.c1 then
				local c = DN.FogColor(o.c1, mc)
				local v = string.format("%d %d %d", c[1], c[2], c[3])
				if changed(fc, v) then fc:Fire("SetColor", v) end
				DN.stats.fog = v
			end
			if o.c2 then
				local c = DN.FogColor(o.c2, mc)
				local v = string.format("%d %d %d", c[1], c[2], c[3])
				if changed(tostring(fc) .. "2", v) then fc:Fire("SetColorSecondary", v) end
			end
		end
	end

	local SKYPAINT = { "TopColor", "BottomColor", "DuskColor", "DuskIntensity", "SunNormal", "DrawStars" }
	local function applySkyPaint(sky)
		local sp = first("env_skypaint")
		if not IsValid(sp) or not sp.SetTopColor then return end
		if not orig.skypaint then
			local o = { ent = sp }
			for _, k in ipairs(SKYPAINT) do if sp["Get" .. k] then o[k] = sp["Get" .. k](sp) end end
			orig.skypaint = o
		end
		local key = string.format("%.3f %.3f %.3f %.3f %.3f %.3f %.3f %.3f %.3f %.2f %.2f %d", sky.sky.r, sky.sky.g, sky.sky.b, sky.fog.r, sky.fog.g,
			sky.fog.b, sky.sunrise.r, sky.sunrise.g, sky.sunrise.b, sky.sunrise.a or 0, sky.sunAngle, (sky.starBrightness or 0) > 0.01 and 1 or 0)
		if not changed("skypaint", key) then return end
		sp:SetTopColor(Vector(sky.sky.r, sky.sky.g, sky.sky.b))
		sp:SetBottomColor(Vector(sky.fog.r, sky.fog.g, sky.fog.b))
		sp:SetDuskColor(Vector(sky.sunrise.r, sky.sunrise.g, sky.sunrise.b))
		sp:SetDuskIntensity((sky.sunrise.a or 0) * 2)
		local x, y, z = DN.SunDir(sky.sunAngle)
		sp:SetSunNormal(Vector(x, y, z))
		sp:SetDrawStars((sky.starBrightness or 0) > 0.01)
	end

	function DN.Restore()
		if not touched then return end
		touched = false
		setLetter(DN.LETTER_DAY)
		if IsValid(orig.sunMade) then orig.sunMade:Remove() end
		local s = orig.sun
		if s and IsValid(s.ent) then
			s.ent:SetKeyValue("use_angles", tostring(s.useAngles or 0))
			s.ent:SetKeyValue("pitch", tostring(s.pitch or 0))
			s.ent:SetKeyValue("angle", tostring(s.angle or 0))
			s.ent:Activate()
			s.ent:Fire("TurnOn")
		end
		if IsValid(orig.shadowMade) then orig.shadowMade:Remove() end
		if orig.shadow and IsValid(orig.shadow.ent) then
			local a = orig.shadow.angles
			orig.shadow.ent:Fire("SetAngles", string.format("%.2f %.2f %.2f", a.p, a.y, a.r))
		end
		for fc, o in pairs(orig.fog or {}) do
			if IsValid(fc) then
				if o.c1 then fc:Fire("SetColor", table.concat(o.c1, " ")) end
				if o.c2 then fc:Fire("SetColorSecondary", table.concat(o.c2, " ")) end
			end
		end
		local sp = orig.skypaint
		if sp and IsValid(sp.ent) then
			for _, k in ipairs(SKYPAINT) do
				if sp[k] ~= nil and sp.ent["Set" .. k] then sp.ent["Set" .. k](sp.ent, sp[k]) end
			end
		end
		orig = {}
	end

	-- Mode 1: Minecraft's time of day onto the map.
	function DN.ApplyMc(sky)
		touched = true
		setLetter(DN.Letter(sky.skyLight, DN.cvNight:GetString()))
		applySun(sky)
		applyShadows(sky)
		applyFog(sky)
		applySkyPaint(sky)
		DN.stats.timeOfDay = sky.timeOfDay
		DN.stats.skyLight = sky.skyLight
	end

	-- Mode 2: StormFox2's time onto Minecraft's clock. force: send the time even without drift.
	local lastRate = nil
	function DN.ResetDrive() lastRate = nil end  -- (a new Minecraft or mode: send the rate again)
	function DN.DriveMc(force)
		local T = DN.StormFox()
		if not T or not gmodcraft.PushHostEvent then return false end
		local K = gmodcraft.K or {}
		local paused = isfunction(T.IsPaused) and T.IsPaused() == true
		local rate = DN.Rate(isfunction(T.GetSpeed) and T.GetSpeed() or 60, paused)
		local tod = DN.MinutesToTicks(T.Get())
		local sky = gmodcraft.McServerSky and gmodcraft.McServerSky() or nil
		-- nothing to correct while Minecraft's world isn't there (half loaded): the next valid read does it
		if not (sky and sky.valid) then return true end
		-- McServerSky is a few ticks old at most: allow for that much clock movement before correcting
		local drift = math.abs(DN.TodDiff(tod, sky.timeOfDay))
		local set = force or drift > 40 + 10 * rate
		if not set and lastRate ~= nil and math.abs(rate - lastRate) < 1e-4 then return true end
		local ok = gmodcraft.PushHostEvent({ type = K.HostEvSetDayTime or 21, flags = set and (K.DayTimeSet or 1) or 0, a = tod, x = rate })
		if ok then
			lastRate = rate
			DN.stats.sends = DN.stats.sends + 1
			if set then DN.stats.sets = DN.stats.sets + 1 end
			DN.stats.sfTicks, DN.stats.sfRate = tod, rate
		end
		return ok
	end

	-- A sky that turns invalid for a moment (a half-built world, a failed write) keeps the map as it is
	-- for this long before it is restored (no restore-and-back lightmap downloads).
	DN.INVALID_GRACE_S = 3
	local lastValidAt, prevMode, prevLinked = nil, nil, nil
	function DN.Think()
		local mode = DN.Mode()
		local skipped = DN.MapSkippedNow()
		local sf = DN.StormFox() ~= nil
		DN.stats.mode, DN.stats.skipped, DN.stats.stormfox = mode, skipped, sf
		local L = gmodcraft.serverLink
		local linked = (not gmodcraft.missing and L and L.mcAlive) and true or false
		if mode ~= prevMode or linked ~= prevLinked then
			DN.ResetDrive()
			lastValidAt = nil
		end
		prevMode, prevLinked = mode, linked
		if mode == 1 and not skipped and linked and not sf then
			local sky = gmodcraft.McServerSky and gmodcraft.McServerSky() or nil
			if sky and sky.valid then
				lastValidAt = SysTime()
				DN.ApplyMc(sky)
				return
			end
			if lastValidAt and SysTime() - lastValidAt < DN.INVALID_GRACE_S then return end
		elseif mode == 1 and sf then
			once("sf1", "day-night sync: StormFox2 runs, so Minecraft's time doesn't drive this map (gmodcraft_sun_sync 2 lets StormFox2 drive Minecraft)")
		elseif mode == 2 and linked then
			if not DN.DriveMc(false) then once("nosf", "day-night sync 2 (GMod -> Minecraft): StormFox2 isn't installed; nothing drives Minecraft's time") end
		end
		DN.Restore()
	end

	timer.Create("gmodcraft_daynight", 0.5, 0, function()
		local ok, err = pcall(DN.Think)
		if not ok then once("err", "day-night sync error: %s", tostring(err)) end
	end)
	hook.Add("StormFox2.Time.Changed", "gmodcraft_daynight", function()
		if DN.Mode() == 2 then pcall(DN.DriveMc, true) end
	end)
	-- A player joining in the dark downloads the lightmaps once the style has arrived.
	hook.Add("PlayerInitialSpawn", "gmodcraft_daynight", function(ply)
		if letter ~= DN.LETTER_DAY then timer.Simple(2, function() if IsValid(ply) then sendStyle(ply) end end) end
	end)
	-- A new map starts from its own light styles and entities.
	hook.Add("InitPostEntity", "gmodcraft_daynight", function()
		letter, touched, orig, sunStyle, lastValidAt = DN.LETTER_DAY, false, {}, nil, nil
	end)
	function DN.CurrentLetter() return letter end
end

-- ---- client --------------------------------------------------------------------------------

if CLIENT then
	DN.cstats = DN.cstats or { redownloads = 0, lastMs = 0, maxMs = 0, letter = DN.LETTER_DAY, sunStyle = 0 }
	-- What the sun's light style does to the map's light now (1 by day). render.GetLightColor doesn't
	-- follow light styles (measured), so client/blocks.lua scales its samples by DN.LightFactorAt.
	function DN.LightFactor() return DN.StyleFactor(DN.cstats.letter) end
	-- At a point: the factor where the sun's style lights it. A sun in style 0 lights everything there; a
	-- sun in its own style (gm_construct's 33) lights what sees the sky, and the map's lamps (style 0)
	-- keep the rest lit. Sky visibility per 80-unit cell, cached until the letter changes.
	local skyCache, skyCacheN, skyCacheLetter = {}, 0, nil
	local up = Vector(0, 0, 16384)
	local tr = { mask = MASK_SOLID_BRUSHONLY }
	function DN.LightFactorAt(x, y, z)
		local st = DN.cstats
		local f = DN.StyleFactor(st.letter)
		if f >= 1 then return 1 end
		if (st.sunStyle or 0) == 0 then return f end
		if skyCacheLetter ~= st.letter or skyCacheN > 50000 then skyCache, skyCacheN, skyCacheLetter = {}, 0, st.letter end
		local key = math.floor(x / 80) .. " " .. math.floor(y / 80) .. " " .. math.floor(z / 80)
		local sees = skyCache[key]
		if sees == nil then
			tr.start = Vector(x, y, z)
			tr.endpos = tr.start + up
			sees = util.TraceLine(tr).HitSky == true
			skyCache[key], skyCacheN = sees, skyCacheN + 1
		end
		return DN.SampleFactor(st.letter, st.sunStyle, sees)
	end
	-- The light style travels in a string table, which may land after this message: download a second later.
	net.Receive(DN.NET_STYLE, function()
		local l = net.ReadString()
		local style = net.ReadUInt(8)
		timer.Create("gmodcraft_daynight_redownload", 1, 1, function()
			local t0 = SysTime()
			render.RedownloadAllLightmaps(false, false)
			local ms = (SysTime() - t0) * 1000
			local st = DN.cstats
			st.redownloads, st.lastMs, st.maxMs, st.letter, st.sunStyle = st.redownloads + 1, ms, math.max(st.maxMs, ms), l, style
			gmodcraft.Log("render", "day-night: light style %s, lightmaps downloaded again in %.1f ms", l, ms)
			hook.Run("GmodcraftLightmapsRedownloaded", l, ms)
		end)
	end)
end

return DN
