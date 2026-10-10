-- The map's floor hint for its Minecraft slot (P8 WP1, protocol v21, docs/DESIGN.md section 3).
-- A NEW slot gets a vertical offset oyUnits = 64 * 40 - floorZ (Source units, chosen by the MC
-- server), so the map's main floor sits exactly on Minecraft y = 64. This file finds floorZ and hands
-- it to server/link.lua, which sends it in ServerState (kServerAnchorReady); the MC server waits up to
-- kAnchorWaitMs for it before it allocates a new map's slot. Existing slots never move (WP2 re-anchors).
--
-- Precedence: the operator's data/gmodcraft/map_anchors.json > the measured table
-- (shared/map_anchors.lua) > detection: the module's MapAnchor (the floor most spawn points stand
-- on; navmesh areas, then walkable area, break ties; no spawns: the map's most common walkable floor).
-- `gmodcraft_anchor_probe` (admins, console) prints all of it.

local A = gmodcraft.anchor or {}
gmodcraft.anchor = A
local K = gmodcraft.K or {}

A.result = A.result      -- the hint for this map: { source, floorZ, minZ, maxZ, footMinX/Y, footMaxX/Y }
A.detected = A.detected  -- the module's MapAnchor result (debug)
A.forMap = A.forMap

local SPAWN_CLASSES = { "info_player_start", "info_player_deathmatch", "info_player_combine", "info_player_rebel",
	"info_player_counterterrorist", "info_player_terrorist", "gmod_player_start" }
local NAV_MAX = 4096

local SOURCE_NAMES = { [0] = "none", "spawns", "map mode", "curated table", "operator file", "forced (dev)", "legacy slot", "timeout" }
function A.SourceName(src) return SOURCE_NAMES[src or 0] or tostring(src) end

function A.Spawns()
	local out = {}
	for _, cls in ipairs(SPAWN_CLASSES) do
		for _, e in ipairs(ents.FindByClass(cls)) do
			local p = e:GetPos()
			out[#out + 1] = { x = p.x, y = p.y, z = p.z }
		end
	end
	return out
end

function A.NavHeights()
	local out = {}
	if not (navmesh and navmesh.IsLoaded and navmesh.IsLoaded()) then return out end
	for _, area in ipairs(navmesh.GetAllNavAreas()) do
		out[#out + 1] = area:GetCenter().z
		if #out >= NAV_MAX then break end
	end
	return out
end

-- The operator's overrides: { "<map>": { "floorZ": n }, ... } (lowercase map names).
function A.OperatorTable()
	local text = file.Read("gmodcraft/map_anchors.json", "DATA")
	if not text or text == "" then return {} end
	local t = util.JSONToTable(text)
	if type(t) ~= "table" then
		gmodcraft.Info("data/gmodcraft/map_anchors.json doesn't parse as JSON; ignored")
		return {}
	end
	return t
end

local function finite(v) return type(v) == "number" and v == v and v > -1e6 and v < 1e6 end

-- Works the hint out once the module has decoded the map. Returns it (or nil while not ready).
function A.Compute(force)
	local map = string.lower(game.GetMap())
	if A.forMap == map and A.result and not force then return A.result end
	if gmodcraft.missing or not gmodcraft.MapAnchor then return nil end
	local cam = ents.FindByClass("sky_camera")[1]  -- the map mode leaves the 3D skybox room out
	local sky = cam and IsValid(cam) and cam:GetPos() or nil
	local det = gmodcraft.MapAnchor({ spawns = A.Spawns(), nav = A.NavHeights(), sky = sky and { x = sky.x, y = sky.y, z = sky.z } or nil })
	if not det then return nil end  -- not decoded yet
	A.detected = det
	local r = { source = det.source, floorZ = det.floorZ, minZ = det.minZ, maxZ = det.maxZ, footMinX = det.footMinX, footMinY = det.footMinY,
		footMaxX = det.footMaxX, footMaxY = det.footMaxY }
	local curated = gmodcraft.mapAnchors and gmodcraft.mapAnchors[map]
	if curated and finite(curated.floorZ) then r.source, r.floorZ = K.AnchorCurated or 3, curated.floorZ end
	local op = A.OperatorTable()[map]
	if type(op) == "table" and finite(op.floorZ) then r.source, r.floorZ = K.AnchorOperator or 4, op.floorZ end
	if r.source == (K.AnchorNone or 0) then
		-- Still a hint (kServerAnchorReady with kAnchorNone): the MC server makes a new slot at once,
		-- with no offset, instead of waiting kAnchorWaitMs for a floor that won't come.
		A.result, A.forMap = r, map
		gmodcraft.Info("map floor for %s: none found (no walkable geometry); a new slot gets no vertical offset", map)
		return r
	end
	A.result, A.forMap = r, map
	gmodcraft.Info("map floor for %s: z %.1f (%s; detected %.1f from %d spawns in %.1f ms) -> a new slot puts it on y %d", map, r.floorZ,
		A.SourceName(r.source), det.floorZ, det.spawns or 0, det.ms or 0, K.AnchorFloorY or 64)
	return r
end

-- For ServerState: the hint (source kAnchorNone when the map has no floor), or nil while it isn't
-- known yet (then kServerAnchorReady stays clear).
function A.Get()
	local map = string.lower(game.GetMap())
	if A.forMap == map then return A.result end
	local st = gmodcraft.mapload and gmodcraft.mapload.Status and gmodcraft.mapload.Status()
	if not st or st.state ~= "ready" then return nil end
	return A.Compute()
end

concommand.Add("gmodcraft_anchor_probe", function(ply)
	if IsValid(ply) and not (game.SinglePlayer() or ply:IsAdmin()) then return end
	local function say(fmt, ...)
		local s = string.format(fmt, ...)
		if IsValid(ply) then ply:PrintMessage(HUD_PRINTCONSOLE, s) else print(s) end
	end
	local map = string.lower(game.GetMap())
	local r = A.Compute(true)
	local det = A.detected
	say("[gmodcraft] anchor probe for %s", map)
	if not det then say("  the module hasn't decoded the map yet (MapStatus %s)", gmodcraft.MapStatus and gmodcraft.MapStatus().state or "?") return end
	local floors = {}
	for _, f in ipairs(det.spawnFloors or {}) do floors[#floors + 1] = string.format("%.0f", f) end
	say("  detected: %s, floor z %.2f (%d spawns, floors [%s]), map mode %.0f, lowest >5%% %.0f, z %.1f .. %.1f, footprint %.0f %.0f .. %.0f %.0f, %.1f ms",
		A.SourceName(det.source), det.floorZ, det.spawns or 0, table.concat(floors, " "), det.modeZ, det.lowestZ, det.minZ, det.maxZ, det.footMinX,
		det.footMinY, det.footMaxX, det.footMaxY, det.ms or 0)
	for i, b in ipairs(det.bins or {}) do say("    floor %d: z %.0f, %.0f square units", i, b.z, b.area) end
	local curated = gmodcraft.mapAnchors and gmodcraft.mapAnchors[map]
	say("  curated table: %s; operator file: %s", curated and string.format("z %.1f", curated.floorZ) or "-",
		A.OperatorTable()[map] and string.format("z %.1f", A.OperatorTable()[map].floorZ or 0) or "-")
	if r then
		local oy = (K.AnchorFloorY or 64) * 40 - math.floor(r.floorZ + 0.5)
		say("  hint: %s, floor z %.2f -> a NEW slot would get oyUnits %d (%.3f blocks)", A.SourceName(r.source), r.floorZ, oy, oy / 40)
	end
	local s = gmodcraft.serverLink and gmodcraft.serverLink.slot
	say("  current slot: %s", s and string.format("origin (%d, %d), oyUnits %d (%s): the floor z %.1f is at y %.3f", s.ox, s.oz, s.oy or 0,
		A.SourceName(s.anchorSource), r and r.floorZ or 0, ((r and r.floorZ or 0) + (s.oy or 0)) / 40) or "none yet")
end)
