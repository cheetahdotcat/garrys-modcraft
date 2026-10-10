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

-- ---- props, NPCs and ragdolls inside a dug hole (P2) ------------------------------------------------
-- A prop that falls into a hole Minecraft dug (server/physworld.lua lets it) and sinks below the map
-- surface sits inside the map's brushes: every leaf its bounds touch is solid (cluster -1), so it is
-- in nobody's PVS and the engine stops sending it: dormant (invisible, still there) on every client.
-- Once a bit of it pokes out it is sent again, which is the user's "renders while a bit is outside".
-- AddOriginToPVS can't help (no cluster to add). So such entities get EFL_IN_SKYBOX, which makes
-- them pass the engine's "in PVS" check for transmission: only while their centre is in solid AND
-- in a dug cell AND within HOLE_RANGE of a player. The flag is cleared again once that no longer
-- holds. Flags the engine set itself (entities in the 3D skybox) are never touched. Drawing them on
-- the client: client/solid_ents.lua.
local cvHoles = CreateConVar("gmodcraft_send_ents_in_holes", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: keep sending props, NPCs and ragdolls that sank fully into a hole Minecraft dug (inside the map's brushes, else nobody's PVS) to players within range (1, default) or not (0)")

local EFL_SKY = EFL_IN_SKYBOX or 131072
PV.HOLE_RANGE = 3000      -- units: from any player
PV.HOLE_PERIOD = 0.25     -- s between passes
PV.HOLE_MAX_CHECKS = 256  -- point checks (PointContents + dug cell) per pass
PV.HOLE_MAX_FLAGGED = 128 -- entities kept sent this way at most
PV.holeStats = PV.holeStats or { passes = 0, checks = 0, flagged = 0, added = 0, cleared = 0, lastMs = 0 }
PV.holeFlagged = PV.holeFlagged or setmetatable({}, { __mode = "k" })  -- entities WE flagged

-- Is `e` a kind of entity this is for? Props, ragdolls, NPCs, NextBots and other free physics
-- objects; not players, our own entities, held weapons or parented ones (they follow their parent).
function PV.HoleKind(e)
	if e.IsPlayer and e:IsPlayer() then return false end
	local cls = e:GetClass() or ""
	if cls:sub(1, 10) == "gmodcraft_" then return false end
	if e.GetParent and IsValid(e:GetParent()) then return false end
	if (e.IsNPC and e:IsNPC()) or (e.IsNextBot and e:IsNextBot()) then return true end
	if cls:find("^prop_physics") or cls == "prop_ragdoll" then return true end
	if e.IsWeapon and e:IsWeapon() and IsValid(e:GetOwner()) then return false end
	return e.GetMoveType ~= nil and e:GetMoveType() == (MOVETYPE_VPHYSICS or 6)
end

-- Is `pos` inside solid map geometry that lies in a cell Minecraft dug? contentsFn(pos) and
-- dugFn(pos) -> bool default to util.PointContents and the module's dug-cell store.
function PV.InDugSolid(pos, contentsFn, dugFn)
	if bit.band((contentsFn or util.PointContents)(pos), SOLID) == 0 then return false end
	return dugFn(pos) == true
end

-- The module's dug-cell store for one point (nil when there is no link / slot yet).
local function dugLookup()
	local C = gmodcraft.convert
	if not gmodcraft.ColDugCells or not C or not C.slot or not C.slot.known then return nil end
	local world, memo = C.WorldId(), {}
	return function(pos)
		local x, y, z = C.ToMc(pos)
		x, y, z = math.floor(x), math.floor(y), math.floor(z)
		local k = x .. "," .. y .. "," .. z
		if memo[k] == nil then
			local flat = gmodcraft.ColDugCells(world, x, y, z, x, y, z)
			memo[k] = flat ~= nil and #flat >= 3
		end
		return memo[k]
	end
end

-- One selection pass (pure: the tests pass mocks). cands: entities of the right kind; eyes: player
-- positions; flagged: the set flagged so far (checked first, so a capped pass never drops one it
-- didn't look at); start: rotating offset into cands for the rest. Returns the wanted set, the
-- number of point checks and the next start.
function PV.HoleSelect(cands, eyes, flagged, inDugFn, start, maxChecks, maxFlagged, range)
	maxChecks, maxFlagged, range = maxChecks or PV.HOLE_MAX_CHECKS, maxFlagged or PV.HOLE_MAX_FLAGGED, range or PV.HOLE_RANGE
	local r2 = range * range
	local want, nWant, checks = {}, 0, 0
	local function near(p)
		for _, eye in ipairs(eyes) do if p:DistToSqr(eye) < r2 then return true end end
		return false
	end
	local function consider(e)
		if checks >= maxChecks or nWant >= maxFlagged or want[e] then return end
		local p = e:WorldSpaceCenter()
		if not near(p) then return end
		checks = checks + 1
		if inDugFn(p) then want[e], nWant = true, nWant + 1 end
	end
	for e in pairs(flagged) do if IsValid(e) then consider(e) end end
	local n = #cands
	start = (n > 0) and ((start or 0) % n) or 0
	local i = 0
	while i < n and checks < maxChecks and nWant < maxFlagged do
		local e = cands[(start + i) % n + 1]
		if IsValid(e) and not flagged[e] then consider(e) end
		i = i + 1
	end
	return want, checks, (n > 0) and ((start + i) % n) or 0
end

-- Applies a selection: flags the wanted ones that don't carry the flag yet (and remembers them),
-- clears ours that aren't wanted any more. flagFns = { has, add, remove } (tests pass mocks).
function PV.HoleApply(flagged, want, flagFns)
	local added, cleared = 0, 0
	for e in pairs(want) do
		if not flagged[e] and not flagFns.has(e) then
			flagFns.add(e)
			flagged[e] = true
			added = added + 1
		end
	end
	for e in pairs(flagged) do
		if not want[e] then
			if IsValid(e) then flagFns.remove(e) end
			flagged[e] = nil
			cleared = cleared + 1
		end
	end
	return added, cleared
end

local engineFlags = {
	has = function(e) return bit.band(e:GetEFlags(), EFL_SKY) ~= 0 end,
	add = function(e) e:AddEFlags(EFL_SKY) end,
	remove = function(e) e:RemoveEFlags(EFL_SKY) end,
}

-- Candidates: kept from OnEntityCreated (a tick later, once spawned), seeded once from ents.GetAll.
local tracked, seeded = setmetatable({}, { __mode = "k" }), false
local function track(e) if IsValid(e) and PV.HoleKind(e) then tracked[e] = true end end
hook.Add("OnEntityCreated", "gmodcraft_ents_in_holes", function(e)
	if not IsValid(e) or (e:GetClass() or ""):sub(1, 10) == "gmodcraft_" then return end  -- physworld / blockcol bursts
	timer.Simple(0, function() track(e) end)
end)
hook.Add("EntityRemoved", "gmodcraft_ents_in_holes", function(e)
	tracked[e] = nil
	PV.holeFlagged[e] = nil
end)

local nextPass, startAt = 0, 0
function PV.HolePass(now)
	local st = PV.holeStats
	local t0 = SysTime()
	local dug = cvHoles:GetBool() and dugLookup() or nil
	local want = {}
	if dug then
		if not seeded then
			seeded = true
			for _, e in ipairs(ents.GetAll()) do track(e) end
		end
		local eyes = {}
		for _, ply in ipairs(player.GetAll()) do eyes[#eyes + 1] = ply:GetPos() end
		local cands = {}
		for e in pairs(tracked) do if IsValid(e) then cands[#cands + 1] = e else tracked[e] = nil end end
		local checks
		want, checks, startAt = PV.HoleSelect(cands, eyes, PV.holeFlagged, function(p) return PV.InDugSolid(p, nil, dug) end, startAt)
		st.checks = checks
	end
	local added, cleared = PV.HoleApply(PV.holeFlagged, want, engineFlags)
	local n = 0
	for _ in pairs(PV.holeFlagged) do n = n + 1 end
	st.passes, st.flagged, st.added, st.cleared = st.passes + 1, n, st.added + added, st.cleared + cleared
	st.lastMs = (SysTime() - t0) * 1000
	if added > 0 or cleared > 0 then
		gmodcraft.Log("puppet", "entities in dug holes: %d kept sent (+%d, -%d)", n, added, cleared)
	end
end

hook.Add("Think", "gmodcraft_ents_in_holes", function()
	local now = CurTime()
	if now < nextPass then return end
	nextPass = now + PV.HOLE_PERIOD
	PV.HolePass(now)
end)
