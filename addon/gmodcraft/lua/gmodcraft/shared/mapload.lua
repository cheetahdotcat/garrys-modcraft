-- P2 map collision, the loading half (both realms, D-009): read what the module needs from GMod's
-- filesystem (mounted content included) and hand it over; the module decodes it on its worker
-- thread. gmodcraft.mapload.Start() runs once per Lua state; MapStatus() tells when it's ready.
--
--   maps/<map>.bsp           only the lumps the module asks for (header first, then Seek + Read)
--   models/*.phy / *.mdl      each solid static prop's collision (.phy), or its hull (SOLID_BBOX)
--   materials/<tex>.vmt       $surfaceprop / $surfaceprop2 of the displacement textures (patch
--                             materials are followed to what they include)
--   scripts/surfaceproperties*.txt   in manifest order: material names -> dig materials

local ML = gmodcraft.mapload or {}
gmodcraft.mapload = ML

local function readAll(path)
	local f = file.Open(path, "rb", "GAME")
	if not f then return nil end
	local n = f:Size()
	local data = n > 0 and f:Read(n) or ""
	f:Close()
	return data
end

-- What a ModelInfo-less realm can read of a model: studiohdr_t hull_min / hull_max (offset 104).
local function readHull(model)
	local f = file.Open(model, "rb", "GAME")
	if not f then return nil end
	f:Seek(104)
	local v = {}
	for i = 1, 6 do v[i] = f:ReadFloat() end
	f:Close()
	if not v[6] then return nil end
	return v
end

local function load()
	local t0 = SysTime()
	local map = game.GetMap()
	local st = { map = map, phyRead = 0, phyMissing = 0, bboxRead = 0, vmtRead = 0, vmtMissing = 0, surfaceFiles = 0, bspBytes = 0 }
	ML.stats = st
	local f = file.Open("maps/" .. map .. ".bsp", "rb", "GAME")
	if not f then return false, "can't open maps/" .. map .. ".bsp" end
	local header = f:Read(1036)
	local lumps, err = gmodcraft.MapBegin(map, header or "")
	if not lumps then f:Close() return false, err end
	for _, l in ipairs(lumps) do
		f:Seek(l.offset)
		local data = f:Read(l.length)
		if not data or #data ~= l.length then f:Close() return false, string.format("lump %d: short read", l.lump) end
		st.bspBytes = st.bspBytes + #data
		local ok, msg = gmodcraft.MapLump(l.lump, data)
		if not ok then f:Close() return false, msg end
		if msg then gmodcraft.Info("map load: %s", msg) end
	end
	f:Close()
	st.lumpsMs = (SysTime() - t0) * 1000

	local need, warn = gmodcraft.MapPrepare()
	if not need then return false, warn end
	if warn then gmodcraft.Info("map load: %s", warn) end
	for _, m in ipairs(need.phy) do
		local data = readAll((m:gsub("%.mdl$", ".phy")))
		if data and #data > 0 then
			gmodcraft.MapPhy(m, data)
			st.phyRead = st.phyRead + 1
		else
			st.phyMissing = st.phyMissing + 1
		end
	end
	for _, m in ipairs(need.bbox) do
		local h = readHull(m)
		if h then
			gmodcraft.MapBBox(m, h[1], h[2], h[3], h[4], h[5], h[6])
			st.bboxRead = st.bboxRead + 1
		end
	end
	st.models = #need.phy + #need.bbox
	st.propsMs = (SysTime() - t0) * 1000 - st.lumpsMs

	-- Displacement materials: the VMT itself (works in both realms, and on a dedicated server).
	st.textures = #need.textures
	for _, tex in ipairs(need.textures) do
		local path, hops = "materials/" .. tex .. ".vmt", 0
		while path and hops < 4 do
			local text = file.Read(path, "GAME")
			if not text then st.vmtMissing = st.vmtMissing + 1 break end
			st.vmtRead = st.vmtRead + 1
			local _, include = gmodcraft.MapVmt(tex, text)
			if include and include ~= "" then
				path = include:find("^materials/") and include or ("materials/" .. include)
				if not path:find("%.vmt$") then path = path .. ".vmt" end
			else
				path = nil
			end
			hops = hops + 1
		end
	end
	-- For the report: does Material():GetString("$surfaceprop") work in this realm?
	if Material and need.textures[1] then
		local ok, v = pcall(function() return Material(need.textures[1]):GetString("$surfaceprop") end)
		st.materialProbe = string.format("%s: %s", need.textures[1], ok and tostring(v) or ("error " .. tostring(v)))
	end

	local manifest = file.Read("scripts/surfaceproperties_manifest.txt", "GAME") or ""
	for path in manifest:gmatch('"file"%s+"([^"]+)"') do
		local text = file.Read(path, "GAME")
		if text then
			gmodcraft.MapSurfaceProps(text)
			st.surfaceFiles = st.surfaceFiles + 1
		end
	end
	if st.surfaceFiles == 0 then
		local text = file.Read("scripts/surfaceproperties.txt", "GAME")
		if text then gmodcraft.MapSurfaceProps(text) st.surfaceFiles = 1 end
	end
	st.luaMs = (SysTime() - t0) * 1000
	if not gmodcraft.MapDecode() then return false, "MapDecode refused" end
	ML.decodeStartedAt = SysTime()
	return true
end

-- Starts loading this map once (a pcall: a broken map leaves Garry's Modcraft without collision, not
-- without Lua).
function ML.Start()
	if ML.started or gmodcraft.missing or not gmodcraft.MapBegin then return end
	ML.started = true
	local ok, res, err = pcall(load)
	if not ok or not res then
		ML.error = tostring(ok and err or res)
		gmodcraft.Info("map collision: loading %s failed: %s", game.GetMap(), ML.error)
		return
	end
	local st = ML.stats
	gmodcraft.Info("map collision: %s read in %.1f ms (lumps %.1f ms, %.1f MiB; %d prop models, %d .phy, %d missing; %d VMTs; %d surfaceproperties files), decoding",
		st.map, st.luaMs, st.lumpsMs, st.bspBytes / 1048576, st.models or 0, st.phyRead, st.phyMissing, st.vmtRead, st.surfaceFiles)
end

-- The module's status, logged once when the decode finishes.
function ML.Status()
	if gmodcraft.missing or not gmodcraft.MapStatus then return nil end
	local s = gmodcraft.MapStatus()
	if s.state == "ready" and not ML.reported then
		ML.reported = true
		gmodcraft.Info("map collision ready: %d triangles, %d convexes, %d water volumes; decode %.1f ms, props %.1f ms (worker), %d/%d props placed",
			s.tris or 0, s.convexes or 0, s.water or 0, s.decodeMs or 0, s.propsMs or 0, s.propsPlaced or 0, s.props or 0)
		if (s.warnings or "") ~= "" then gmodcraft.Info("map collision warnings:\n%s", s.warnings) end
	elseif s.state == "error" and not ML.reported then
		ML.reported = true
		gmodcraft.Info("map collision: decode failed: %s", s.error)
	end
	return s
end
