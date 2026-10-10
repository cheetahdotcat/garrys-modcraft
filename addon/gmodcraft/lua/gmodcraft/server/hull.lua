-- World type `hull` (server): the map traced into Minecraft blocks by the module's hull job
-- (gmodcraft.HullBuild / gmodcraft.HullStatus) and written to /dev/shm/gmodcraft/hull/ for the
-- Minecraft server (protocol/hull_format.md). Started on its own when the Minecraft server runs a
-- `hull` world and the map is decoded with its slot known; again after the slot's offsets change.
-- Admin: gmodcraft_hull_build [force|status] traces on any world type.
-- gmodcraft_hull_fill_hills: fill the inside of terrain hills (the space under displacements) or
-- leave it hollow. It is part of the file (header flag) and of the trace cache; a world keeps the
-- file it was made with, so a change shows in new hull worlds only. Both modes write the same file
-- name, so the other mode's file is removed from /dev/shm (and a trace of it cancelled) at load and
-- on every change, before a new hull world can take it (gmodcraft.HullPrune).

local H = gmodcraft.hull or {}
gmodcraft.hull = H
local band = bit.band

-- kWorldHull (protocol v41). Compared by value: older modules have no K.WorldHull.
local WORLD_HULL = 5

H.autoKey = H.autoKey    -- map + offsets the automatic trace last started for
H.watching = H.watching  -- a trace is running: progress is logged
H.lastTenth = -1

H.cvFillHills = CreateConVar("gmodcraft_hull_fill_hills", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: hull worlds fill the inside of terrain hills (1, default) or leave them hollow under one surface layer (0). " ..
	"New hull worlds only: a world keeps the map file it was made with.")

-- Removes this map's /dev/shm file of the other hills mode (and cancels a trace of it).
function H.Prune()
	if gmodcraft.missing or not gmodcraft.HullPrune then return 0 end
	local hills = H.cvFillHills:GetBool()
	local n = gmodcraft.HullPrune(string.lower(game.GetMap()), hills) or 0
	if n > 0 then gmodcraft.Info("hull: removed %d file(s) of %s from the other mode (hills %s)", n, game.GetMap(), hills and "hollow" or "filled") end
	return n
end

cvars.AddChangeCallback("gmodcraft_hull_fill_hills", function()
	-- the next tick traces again in a hull world (the automatic trace's key holds the mode)
	H.Prune()
end, "gmodcraft_hull")
H.Prune()

local function readBsp()
	local f = file.Open("maps/" .. game.GetMap() .. ".bsp", "rb", "GAME")
	if not f then return nil end
	local data = f:Read(f:Size())
	f:Close()
	return data
end

-- Starts a trace of the loaded map at the current slot's offsets (force: ignore the cache).
function H.Build(force)
	if gmodcraft.missing or not gmodcraft.HullBuild then return false, "the module has no hull trace" end
	local slot = gmodcraft.serverLink and gmodcraft.serverLink.slot
	if not slot then return false, "no map slot from Minecraft yet" end
	local hills = H.cvFillHills:GetBool()
	local opts = { ox = slot.ox, oy = slot.oy or 0, oz = slot.oz, force = force == true, hills = hills }
	-- The module keeps the map's hash after the first build: the .bsp is read only when it asks.
	local ok, why, need = gmodcraft.HullBuild(nil, opts)
	if not ok and need == "bsp" then
		local data = readBsp()
		if not data then return false, "can't read maps/" .. game.GetMap() .. ".bsp" end
		ok, why = gmodcraft.HullBuild(data, opts)
	end
	if not ok then return false, why end
	H.watching = true
	H.lastTenth = -1
	gmodcraft.Info("hull trace of %s started (offsets %d, %d units, %d; hills %s%s)", game.GetMap(), slot.ox, slot.oy or 0, slot.oz,
		hills and "filled" or "hollow", force and ", cache ignored" or "")
	return true
end

function H.Status()
	return gmodcraft.HullStatus and gmodcraft.HullStatus() or { state = "idle" }
end

local function worldIsHull()
	local K = gmodcraft.K or {}
	local mss = gmodcraft.McServerState and gmodcraft.McServerState()
	if not mss or not mss.ruleFlags or band(mss.ruleFlags, K.RulesValid or 1) == 0 then return false end
	return mss.worldType == WORLD_HULL
end

local function watch()
	local s = H.Status()
	if s.state == "tracing" then
		local tenth = math.floor((s.progress or 0) * 10)
		if tenth > H.lastTenth then
			H.lastTenth = tenth
			gmodcraft.Info("hull trace: %d%% (%d / %d regions, %.1f s)", tenth * 10, s.done or 0, s.total or 0, (s.ms or 0) / 1000)
		end
	elseif s.state == "done" then
		H.watching = false
		gmodcraft.Info("hull trace of %s done in %.1f s%s: %d regions, %d solid blocks (%d fill, %d under terrain; hills %s), %d bytes -> %s%s", s.map or "?",
			(s.ms or 0) / 1000, s.cached and " (from the cache)" or "", s.regions or 0, s.solidBlocks or 0, s.fillBlocks or 0, s.hillBlocks or 0,
			s.hills == false and "hollow" or "filled", s.bytes or 0, s.path or "?",
			(s.error or "") ~= "" and (" (" .. s.error .. ")") or "")
	elseif s.state == "error" or s.state == "cancelled" then
		H.watching = false
		gmodcraft.Info("hull trace of %s %s%s", s.map or "?", s.state, (s.error or "") ~= "" and (": " .. s.error) or "")
	end
end

local nextCheck = 0
hook.Add("Tick", "gmodcraft_hull", function()
	if gmodcraft.missing or not gmodcraft.HullBuild then return end
	local now = SysTime()
	if now < nextCheck then return end
	nextCheck = now + 1
	if H.watching then watch() end
	local slot = gmodcraft.serverLink and gmodcraft.serverLink.slot
	if not slot or not worldIsHull() then return end
	-- the hills setting too: a change traces again (for the next new hull world; the running one keeps its file)
	local key = string.format("%s %d %d %d %s", game.GetMap(), slot.ox, slot.oy or 0, slot.oz, H.cvFillHills:GetBool() and "hills" or "hollow")
	if H.autoKey == key then return end
	local ms = gmodcraft.MapStatus and gmodcraft.MapStatus()
	-- viewId: the region index for the slot exists (what HullBuild needs); before that, wait
	-- without reading the .bsp.
	if not ms or ms.state ~= "ready" or ms.viewId == nil then return end
	H.autoKey = key  -- one attempt per map + offsets + hills (gmodcraft_hull_build retries by hand)
	local ok, why = H.Build(false)
	if not ok then gmodcraft.Info("hull trace not started: %s", tostring(why)) end
end)

concommand.Add("gmodcraft_hull_build", function(ply, _, args)
	if IsValid(ply) and not (game.SinglePlayer() or ply:IsSuperAdmin()) then return end
	local function say(fmt, ...)
		local text = string.format(fmt, ...)
		if IsValid(ply) then ply:PrintMessage(HUD_PRINTCONSOLE, text) end
		gmodcraft.Info("%s", text)
	end
	if args[1] == "status" then
		local s = H.Status()
		say("hull trace: %s, %.0f%% (%d / %d regions), %s%s", s.state or "?", (s.progress or 0) * 100, s.done or 0, s.total or 0,
			(s.path or "") ~= "" and s.path or "no file", (s.error or "") ~= "" and (" (" .. s.error .. ")") or "")
		return
	end
	local ok, why = H.Build(args[1] == "force")
	if ok then say("hull trace started: progress in the server log (gmodcraft_hull_build status)")
	else say("hull trace not started: %s", tostring(why)) end
end)
