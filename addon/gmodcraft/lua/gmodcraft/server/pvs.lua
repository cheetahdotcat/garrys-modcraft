-- Entities stay visible while a player's eye is inside map geometry (P5d).
--
-- The engine sends a client only the entities in the PVS of its view origin. A view origin inside a
-- solid brush (a Minecraft player in a tunnel dug into the map: the GMod body is where Minecraft says)
-- has no PVS worth the name, so NPCs, props and other players on the surface go dormant on that
-- client, although its view (the dug hole, the sky drawn by client/blocks.lua) looks right out at
-- them. Here, while the player's eye is in CONTENTS_SOLID, SetupPlayerVisibility adds a nearby point
-- in open space as a second PVS origin: the open end of the tunnel straight above (or sideways), else
-- the last free position the player stood at (server/players.lua). Players with their eye in open
-- space are untouched (one PointContents per player per tick).
--
-- ON by default (convar 1) since the LV1 live run: with 0, client/solid_ents.lua counted 50 nearby
-- entities dormant while the eye was in solid (the server didn't send them); with 1, 7/7 were drawn.

local PV = gmodcraft.pvs or {}
gmodcraft.pvs = PV

local cvOn = CreateConVar("gmodcraft_pvs_in_solid", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: while a player's eye is inside map geometry (digging), also send them the entities seen from the nearest open space (1, default) or not (0)")

local SOLID = CONTENTS_SOLID or 1
PV.UP_RANGE = 2048      -- units: how far up the open end of a tunnel is looked for
PV.SIDE_RANGE = 640     -- units: sideways (16 blocks)
PV.CACHE_MOVE = 16      -- units: the proxy is looked for again when the eye moved this far
PV.CACHE_TIME = 1.0     -- s: ... or after this long
PV.stats = PV.stats or { searches = 0, found = 0, fallback = 0, none = 0, ticks = 0 }

local cache = setmetatable({}, { __mode = "k" })

local DIRS = { Vector(0, 0, 1), Vector(1, 0, 0), Vector(-1, 0, 0), Vector(0, 1, 0), Vector(0, -1, 0) }

-- The nearest point in open space from `eye` (which is in solid): along each direction (up first),
-- where a brush-only trace leaves solid, a little past it. traceFn / contentsFn default to
-- util.TraceLine / util.PointContents (tests pass mocks). Returns point, how (or nil).
function PV.FindProxy(eye, traceFn, contentsFn, fallbacks)
	traceFn = traceFn or util.TraceLine
	contentsFn = contentsFn or util.PointContents
	local best, bestD, how
	for i, d in ipairs(DIRS) do
		local range = i == 1 and PV.UP_RANGE or PV.SIDE_RANGE
		local tr = traceFn({ start = eye, endpos = eye + d * range, mask = MASK_SOLID_BRUSHONLY })
		if tr and tr.StartSolid and tr.FractionLeftSolid and tr.FractionLeftSolid > 0 and tr.FractionLeftSolid < 1 then
			local dist = range * tr.FractionLeftSolid
			local p = eye + d * (dist + 8)
			if bit.band(contentsFn(p), SOLID) == 0 and (not bestD or dist < bestD) then
				best, bestD, how = p, dist, i == 1 and "open above" or "open sideways"
			end
		end
		if i == 1 and best then break end  -- straight up is the hole a dug tunnel usually has
	end
	if best then return best, how end
	for _, p in ipairs(fallbacks or {}) do
		if bit.band(contentsFn(p), SOLID) == 0 then return p, "last free position" end
	end
	return nil
end

-- The proxy for a player whose eye is at `eye` (cached while the eye stays put).
function PV.ProxyFor(ply, eye, now)
	local c = cache[ply]
	if c and now - c.at < PV.CACHE_TIME and c.eye:DistToSqr(eye) < PV.CACHE_MOVE * PV.CACHE_MOVE then return c.proxy end
	local st = gmodcraft.player and gmodcraft.player.Get and gmodcraft.player.Get(ply)
	local fallbacks = {}
	if st and st.safe then
		for i = #st.safe, 1, -1 do fallbacks[#fallbacks + 1] = st.safe[i] + Vector(0, 0, 32) end
	end
	PV.stats.searches = PV.stats.searches + 1
	local proxy, how = PV.FindProxy(eye, nil, nil, fallbacks)
	if proxy then
		PV.stats.found = PV.stats.found + 1
		if how == "last free position" then PV.stats.fallback = PV.stats.fallback + 1 end
	else
		PV.stats.none = PV.stats.none + 1
	end
	if not c or (c.how ~= how) then
		gmodcraft.Log("puppet", "%s: eye in solid at %s; extra PVS origin %s (%s)", ply:Nick(), tostring(eye), tostring(proxy), how or "none found")
	end
	cache[ply] = { at = now, eye = eye, proxy = proxy, how = how }
	return proxy
end

hook.Add("SetupPlayerVisibility", "gmodcraft_pvs_in_solid", function(ply)
	if not cvOn:GetBool() or not IsValid(ply) then return end
	local eye = ply:EyePos()
	if bit.band(util.PointContents(eye), SOLID) == 0 then
		cache[ply] = nil
		return
	end
	PV.stats.ticks = PV.stats.ticks + 1
	local proxy = PV.ProxyFor(ply, eye, CurTime())
	if proxy then AddOriginToPVS(proxy) end
end)
