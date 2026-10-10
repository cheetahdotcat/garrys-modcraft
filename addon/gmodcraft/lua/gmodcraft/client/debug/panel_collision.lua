-- Debug tab: "Collision" panel (docs/DESIGN.md section 12, P2). The decoded map (timings, counts by
-- kind, materials), both links' streaming (regions, bytes, messages, worker timings, ring fill),
-- buttons to re-send or clear, and world overlays near the player: the triangles sent (wireframe),
-- the voxel regions (boxes), the voxel blocks and the water grid. Overlays are rebuilt about once a
-- second from what the module sends for the regions around the player (gmodcraft.ColDebug), drawn
-- as line meshes.

local D = gmodcraft.debug
local C = gmodcraft.convert
local K = gmodcraft.K or {}
local band = bit.band

local cvTris = CreateClientConVar("gmodcraft_debug_col_tris", "0", false, false, "Garry's Modcraft: draw the collision triangles sent near you")
local cvRegions = CreateClientConVar("gmodcraft_debug_col_regions", "0", false, false, "Garry's Modcraft: draw the collision regions sent")
local cvBlocks = CreateClientConVar("gmodcraft_debug_col_blocks", "0", false, false, "Garry's Modcraft: draw the voxel blocks near you")
local cvWater = CreateClientConVar("gmodcraft_debug_col_water", "0", false, false, "Garry's Modcraft: draw the water grid")

local function fmtBytes(n)
	n = n or 0
	if n >= 1048576 then return string.format("%.1f MiB", n / 1048576) end
	if n >= 1024 then return string.format("%.1f KiB", n / 1024) end
	return string.format("%d B", n)
end

local DIG_NAMES = { [-1] = "no-dig", [0] = "none", "grass", "dirt", "stone", "cobble", "snow", "ice", "sand", "gravel", "mud", "oak", "spruce",
	"birch", "planks", "metal", "glass", "organic", "cloth", "bone", "web", "ash", "bedrock" }

-- ---- overlays ----------------------------------------------------------------------------------
-- Drawn over everything ($ignorez): the triangles lie exactly on the surfaces they stand for.
local lineMat = CreateMaterial("gmodcraft_col_lines_nz", "UnlitGeneric", { ["$basetexture"] = "color/white", ["$vertexcolor"] = 1, ["$vertexalpha"] = 1,
	["$ignorez"] = 1 })
local O = { meshes = {}, builtAt = 0, tris = 0, blocks = 0, waterCols = 0 }
D.collisionOverlay = O

local function freeMeshes()
	for _, m in ipairs(O.meshes) do if IsValid(m) then m:Destroy() end end
	O.meshes = {}
end

-- Lines as {x1,y1,z1, x2,y2,z2, r,g,b} entries (Source units), in meshes of up to 16000 lines.
local function buildMeshes(lines)
	freeMeshes()
	local n = #lines
	local i = 1
	while i <= n do
		local count = math.min(16000, n - i + 1)
		local m = Mesh(lineMat)
		mesh.Begin(m, MATERIAL_LINES, count)
		for k = i, i + count - 1 do
			local l = lines[k]
			mesh.Position(l[1])
			mesh.Color(l[3], l[4], l[5], 255)
			mesh.AdvanceVertex()
			mesh.Position(l[2])
			mesh.Color(l[3], l[4], l[5], 255)
			mesh.AdvanceVertex()
		end
		mesh.End()
		O.meshes[#O.meshes + 1] = m
		i = i + count
	end
end

local function triColor(f)
	if band(f, K.TriGhost or 4) ~= 0 then return 255, 60, 60 end
	if band(f, K.TriStairHelper or 1) ~= 0 then return 255, 220, 0 end
	if band(f, K.TriTerrain or 8) ~= 0 then return 120, 220, 80 end
	if band(f, K.TriDiggable or 2) ~= 0 then return 80, 200, 255 end
	return 230, 230, 230
end

local function rebuild()
	O.builtAt = RealTime()
	local CL = gmodcraft.clientLink
	local p = CL and CL.colPos
	if not p or not C.slot.known then freeMeshes() return end
	local wantTris, wantBlocks = cvTris:GetBool(), cvBlocks:GetBool()
	-- The module builds the overlay's payloads on its worker: poll for a new build (no arrays come
	-- back while O.dataId is the newest) and rebuild the line meshes only when one arrives (or the
	-- water grid is due).
	local waterDue = cvWater:GetBool() and RealTime() - (O.waterAt or 0) > 1
	if wantTris or wantBlocks then
		local r = gmodcraft.ColDebug(p.x, p.y, p.z, 1, 6000, wantBlocks, O.dataId or -1)
		if r then O.debugMs = r.buildMs end
		if r and r.id ~= O.dataId and r.tris then
			O.data, O.dataId = r, r.id
		elseif not waterDue then
			return
		end
	elseif not waterDue then
		return
	end
	if waterDue then O.waterAt = RealTime() end
	local t0 = SysTime()
	local lines = {}
	O.tris, O.blocks, O.waterCols = 0, 0, 0
	if wantTris or wantBlocks then
		local d = O.data
		if d and wantTris then
			local v, fl = d.tris, d.flags
			O.tris = #fl
			for i = 1, #fl do
				local b = (i - 1) * 9
				local a1 = C.FromMc(v[b + 1], v[b + 2], v[b + 3])
				local a2 = C.FromMc(v[b + 4], v[b + 5], v[b + 6])
				local a3 = C.FromMc(v[b + 7], v[b + 8], v[b + 9])
				local r, g, bl = triColor(fl[i])
				lines[#lines + 1] = { a1, a2, r, g, bl }
				lines[#lines + 1] = { a2, a3, r, g, bl }
				lines[#lines + 1] = { a3, a1, r, g, bl }
			end
			-- the dynamic layer (doors, props: P2c) on top, in its own colour; Source units already
			local dv = d.dynTris or {}
			O.dynTris = #dv / 9
			for b = 0, #dv - 9, 9 do
				local a1 = Vector(dv[b + 1], dv[b + 2], dv[b + 3])
				local a2 = Vector(dv[b + 4], dv[b + 5], dv[b + 6])
				local a3 = Vector(dv[b + 7], dv[b + 8], dv[b + 9])
				lines[#lines + 1] = { a1, a2, 255, 60, 255 }
				lines[#lines + 1] = { a2, a3, 255, 60, 255 }
				lines[#lines + 1] = { a3, a1, 255, 60, 255 }
			end
		end
		if d and wantBlocks then
			local b = d.blocks
			for i = 1, #b, 4 do
				local x, y, z, fill = b[i], b[i + 1], b[i + 2], b[i + 3]
				if math.abs(x + 0.5 - p.x) <= 6 and math.abs(z + 0.5 - p.z) <= 6 and math.abs(y + 0.5 - p.y) <= 4 then
					O.blocks = O.blocks + 1
					-- a box shrunk a little, red when the block is full, orange when partly filled
					local s = 0.04
					local lo = C.FromMc(x + s, y + s, z + 1 - s)
					local hi = C.FromMc(x + 1 - s, y + 1 - s, z + s)
					local r, g, bl = 255, fill >= 512 and 40 or 160, 0
					local c = { Vector(lo.x, lo.y, lo.z), Vector(hi.x, lo.y, lo.z), Vector(hi.x, hi.y, lo.z), Vector(lo.x, hi.y, lo.z),
						Vector(lo.x, lo.y, hi.z), Vector(hi.x, lo.y, hi.z), Vector(hi.x, hi.y, hi.z), Vector(lo.x, hi.y, hi.z) }
					for _, e in ipairs({ { 1, 2 }, { 2, 3 }, { 3, 4 }, { 4, 1 }, { 5, 6 }, { 6, 7 }, { 7, 8 }, { 8, 5 }, { 1, 5 }, { 2, 6 }, { 3, 7 }, { 4, 8 } }) do
						lines[#lines + 1] = { c[e[1]], c[e[2]], r, g, bl }
					end
				end
			end
		end
	end
	if cvWater:GetBool() then
		local w = gmodcraft.ColWater()
		if w then
			local n = K.WaterGridSize or 16
			for dz = 0, n - 1 do
				for dx = 0, n - 1 do
					local y = w.surface[dz * n + dx + 1]
					if y then
						O.waterCols = O.waterCols + 1
						local x0, z0 = w.originX + dx + 0.05, w.originZ + dz + 0.05
						local a = C.FromMc(x0, y, z0)
						local b2 = C.FromMc(x0 + 0.9, y, z0)
						local c = C.FromMc(x0 + 0.9, y, z0 + 0.9)
						local e = C.FromMc(x0, y, z0 + 0.9)
						for _, l in ipairs({ { a, b2 }, { b2, c }, { c, e }, { e, a }, { a, c } }) do lines[#lines + 1] = { l[1], l[2], 40, 120, 255 } end
					end
				end
			end
		end
	end
	O.lines = #lines
	buildMeshes(lines)
	O.meshMs = (SysTime() - t0) * 1000  -- game thread: Lua lines + meshes (only when a build arrives)
	O.meshMsMax = math.max(O.meshMsMax or 0, O.meshMs)
	gmodcraft.PerfAdd("cl overlay rebuild", O.meshMs)
end

hook.Add("PostDrawTranslucentRenderables", "gmodcraft_collision_overlay", function(_, skybox)
	if skybox or gmodcraft.missing or (gmodcraft.blocks and gmodcraft.blocks.inSkyView) then return end
	local any = cvTris:GetBool() or cvBlocks:GetBool() or cvWater:GetBool()
	if any then
		local CL = gmodcraft.clientLink
		local p = CL and CL.colPos
		if RealTime() - O.builtAt > 0.1 then rebuild() end
		render.SetMaterial(lineMat)
		for _, m in ipairs(O.meshes) do m:Draw() end
	elseif #O.meshes > 0 then
		freeMeshes()
		O.data, O.dataId = nil, nil
	end
	if cvRegions:GetBool() and C.slot.known then
		local CL = gmodcraft.clientLink
		local p = CL and CL.colPos
		if not p then return end
		if not O.regions or RealTime() - (O.regionsAt or 0) > 1 then
			O.regions, O.regionsAt = gmodcraft.ColRegions(), RealTime()
		end
		local cx, cy, cz = math.floor(p.x / 8), math.floor(p.y / 8), math.floor(p.z / 8)
		for _, r in ipairs(O.regions) do
			if math.abs(r.rx - cx) <= 2 and math.abs(r.rz - cz) <= 2 and math.abs(r.ry - cy) <= 1 then
				local lo = C.FromMc(r.rx * 8, r.ry * 8, r.rz * 8 + 8)
				local hi = C.FromMc(r.rx * 8 + 8, r.ry * 8 + 8, r.rz * 8)
				local col = r.blocks > 0 and Color(255, 140, 0) or (r.tris > 0 and Color(80, 200, 255) or Color(120, 120, 120))
				render.DrawWireframeBox(vector_origin, angle_zero, lo + Vector(2, 2, 2), hi - Vector(2, 2, 2), col, true)
			end
		end
	end
end)

-- ---- panel -------------------------------------------------------------------------------------
local function colAdmin(what)
	net.Start(gmodcraft.NET.colAdmin)
	net.WriteString(what)
	net.SendToServer()
end

local function statusLines(s, load)
	local out = {}
	if not s then return { "  (no data)" } end
	out[#out + 1] = string.format("  %s: %s%s", s.map or "?", s.state or "?", (s.error or "") ~= "" and (" - " .. s.error) or "")
	if load then
		out[#out + 1] = string.format("  Lua read %.1f ms (lumps %.1f ms, %.1f MiB; props %.1f ms: %d models, %d .phy, %d missing; %d displacement textures, %d VMTs read, %d missing; %d surfaceproperties files)",
			load.luaMs or 0, load.lumpsMs or 0, (load.bspBytes or 0) / 1048576, load.propsMs or 0, load.models or 0, load.phyRead or 0, load.phyMissing or 0,
			load.textures or 0, load.vmtRead or 0, load.vmtMissing or 0, load.surfaceFiles or 0)
		if load.materialProbe then out[#out + 1] = "  Material():GetString(\"$surfaceprop\") probe: " .. load.materialProbe end
	end
	if s.state == "ready" then
		local k, c = s.trisByKind or {}, s.convexesByKind or {}
		out[#out + 1] = string.format("  worker: decode %.1f ms, static props %.1f ms, total %.1f ms (waited %.1f ms); region index %.1f ms; input %s",
			s.decodeMs or 0, s.propsMs or 0, s.totalMs or 0, s.queueMs or 0, s.indexMs or 0, fmtBytes(s.inputBytes))
		out[#out + 1] = string.format("  %d triangles: world %d, displacement %d (%s), static props %d, player clip %d | %d convexes: world %d, props %d, clip %d | %d water volumes%s",
			s.tris or 0, k.world or 0, k.displacement or 0, s.dispRebuilt and "rebuilt" or "polysoup", k.staticProp or 0, k.playerClip or 0,
			s.convexes or 0, c.world or 0, c.staticProp or 0, c.playerClip or 0, s.water or 0,
			s.waterSurfaceZ and string.format(" (first: surface z %.1f = MC y %.3f)", s.waterSurfaceZ, C.ZToMcY(s.waterSurfaceZ)) or "")
		out[#out + 1] = string.format("  static props: %d in the map (sprp v%d), %d placed, %d missing model, %d bad .phy, %d boxes (SOLID_BBOX)",
			s.props or 0, s.sprpVersion or 0, s.propsPlaced or 0, s.propsMissing or 0, s.propsBadPhy or 0, s.propsBBox or 0)
		local mats = {}
		for i, m in ipairs(s.materials or {}) do if i <= 10 then mats[#mats + 1] = string.format("%s %d", m.name, m.tris) end end
		out[#out + 1] = "  materials (triangles): " .. table.concat(mats, ", ")
		local dig = {}
		for _, m in ipairs(s.dig or {}) do dig[#dig + 1] = string.format("%s %d", DIG_NAMES[m.dig] or tostring(m.dig), m.tris) end
		out[#out + 1] = "  dig materials (triangles): " .. table.concat(dig, ", ")
		if (s.warnings or "") ~= "" then out[#out + 1] = "  warnings: " .. s.warnings:gsub("\n", "; ") end
	end
	return out
end

local function streamLines(c, ring)
	if not c then return { "  (no data)" } end
	ring = ring or {}
	return {
		string.format("  epoch %d, map view %s | regions queued %d, sent %d (known %d, in flight %d, waiting for the ring %d) | re-sent near %d, after digging %d, stale %d, ring waits %d, clears %d",
			c.epoch or 0, c.haveView and "ready" or "none", c.regionsQueued or 0, c.regionsSent or 0, c.known or 0, c.inflight or 0, c.ready or 0,
			c.refreshes or 0, c.urgent or 0, c.stale or 0, c.ringWaits or 0, c.clears or 0),
		string.format("  sent %d messages, %s | per region on the worker: gather %.3f ms, triangles %.3f ms, voxels %.3f ms (max %.2f ms) | ring writes %.2f ms (max %.2f) | worker queue %d",
			c.messages or 0, fmtBytes(c.bytes), c.avgGatherMs or 0, c.avgTrisMs or 0, c.avgVoxelMs or 0, c.maxRegionMs or 0, c.lastWriteMs or 0, c.maxWriteMs or 0,
			c.workerPending or 0),
		string.format("  collision ring: fill %s, high water %s, %s/s, drops %d | dug cells %d (%d sections, %d messages) | water grid: %d columns at origin (%d, %d), %d writes",
			fmtBytes(ring.fill), fmtBytes(ring.highWater), fmtBytes(ring.bytesPerSec), ring.drops or 0, c.dugCells or 0, c.dugSections or 0, c.dugMessages or 0,
			c.waterColumns or 0, c.waterOriginX or 0, c.waterOriginZ or 0, c.waterWrites or 0),
	}
end

-- The dynamic layer: both realms' counters, then this client's entities (source, last update).
local function dynSummary(realm, d, scan)
	if not d then return string.format("  %s: (no data)", realm) end
	scan = scan or {}
	return string.format("  %s: %d entities (%d placed, %d fast), %d models, %d tris | scans %d: Lua %.2f ms (max %.2f, avg %.2f; %d found, %d listed), C++ %.3f ms (max %.3f)"
		.. " | placements %d, removals %d, fast skips %d, rate limited %d | regions marked %d, re-sent %d, change -> ring avg %.0f ms (max %.0f)",
		realm, d.entityCount or 0, d.placed or 0, d.fast or 0, d.models or 0, d.tris or 0, d.scans or 0, scan.lastMs or 0, scan.maxMs or 0,
		(scan.sumMs or 0) / math.max(1, scan.scans or 0), scan.found or 0, scan.listed or 0, d.lastUpdateMs or 0, d.maxUpdateMs or 0, d.placements or 0,
		d.removals or 0, d.fastSkips or 0, d.rateLimited or 0, d.regionsMarked or 0, d.dynSent or 0, d.latencyAvgMs or 0, d.latencyMaxMs or 0)
end

local function dynLines(cd, cscan, sd, sscan)
	local out = { dynSummary("client", cd, cscan), dynSummary("server", sd, sscan) }
	if cd and cd.entities then
		for _, e in ipairs(cd.entities) do
			if #out >= 16 then break end
			out[#out + 1] = string.format("    #%d %s: %s%s, %s, %d tris, %d placements, last %s at (%.0f, %.0f, %.0f)%s", e.ent, e.model, e.source,
				e.surfaceprop ~= "" and (" " .. e.surfaceprop) or "", e.fast and "moving fast (out)" or (e.placed and "placed" or "not placed"), e.tris,
				e.placements, e.ageMs >= 0 and string.format("%.1f s ago", e.ageMs / 1000) or "never", e.x, e.y, e.z, "")
		end
	end
	return out
end

D.RegisterPanel("collision", {
	title = "Collision",
	order = 25,
	icon = "icon16/shape_square.png",
	build = function(p)
		local scroll = vgui.Create("DScrollPanel", p)
		scroll:Dock(FILL)
		D.AddHeader(scroll, "Map (this client's module)")
		local map = {}
		for i = 1, 9 do map[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Client link: streamed around you")
		local cl = {}
		for i = 1, 3 do cl[i] = D.AddLabel(scroll) end
		D.AddButtons(scroll, {
			{ "Re-send", function() gmodcraft.ColResend() end },
			{ "Clear (new epoch)", function() local CL = gmodcraft.clientLink CL.epoch = CL.epoch + 1 end },
		})
		D.AddHeader(scroll, "Server link: streamed around every MC player (relayed to admins)")
		local sv = {}
		for i = 1, 5 do sv[i] = D.AddLabel(scroll) end
		D.AddButtons(scroll, {
			{ "Re-send (server)", function() colAdmin("resend") end },
			{ "Clear (server, new epoch)", function() colAdmin("clear") end },
		})
		D.AddHeader(scroll, "Dynamic layer: doors, brush entities, props (P2c)")
		local dyn = {}
		for i = 1, 16 do dyn[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Overlays near you (rebuilt about once a second)")
		D.AddCheck(scroll, "Triangles sent (white: solid, blue: diggable, green: terrain, yellow: stair helper, red: ghost, magenta: dynamic entities)", "gmodcraft_debug_col_tris")
		D.AddCheck(scroll, "Voxel regions (orange: has blocks, blue: triangles only, grey: empty)", "gmodcraft_debug_col_regions")
		D.AddCheck(scroll, "Voxel blocks within 6 blocks (red: full, orange: partly filled)", "gmodcraft_debug_col_blocks")
		D.AddCheck(scroll, "Water grid (16 x 16 columns around you)", "gmodcraft_debug_col_water")
		local ov = D.AddLabel(scroll)
		local function fill(labels, lines)
			for i, l in ipairs(labels) do l:SetText(lines[i] or "") end
		end
		local nextUpdate = 0
		p.Think = function()
			if RealTime() < nextUpdate then return end
			nextUpdate = RealTime() + 0.5
			if gmodcraft.missing then fill(map, { "  gmcl module not loaded" }) return end
			D.RequestServerStats()
			fill(map, statusLines(gmodcraft.mapload.Status(), gmodcraft.mapload.stats))
			local st = gmodcraft.Stats()
			local ring = st.linkStats and st.linkStats.host and st.linkStats.host.rings and st.linkStats.host.rings.collision
			fill(cl, streamLines(gmodcraft.ColStats(), ring))
			local ss = D.serverStats
			if ss and ss.open then
				local sring = ss.linkStats and ss.linkStats.host and ss.linkStats.host.rings and ss.linkStats.host.rings.collision
				local lines = streamLines(ss.col, sring)
				local ms = ss.mapStatus or {}
				lines[#lines + 1] = string.format("  server map: %s %s, decode %.1f ms, props %.1f ms, %d triangles; streaming around %d MC players",
					ms.map or "?", ms.state or "?", ms.decodeMs or 0, ms.propsMs or 0, ms.tris or 0, ss.colPlayers or 0)
				if ss.mapLoad and ss.mapLoad.materialProbe then lines[#lines + 1] = "  server Material() probe: " .. ss.mapLoad.materialProbe end
				fill(sv, lines)
			else
				fill(sv, { "  server link not open (or no stats yet)" })
			end
			fill(dyn, dynLines(gmodcraft.ColDynInfo and gmodcraft.ColDynInfo(), gmodcraft.dynamic and gmodcraft.dynamic.stats, ss and ss.dyn, ss and ss.dynScan))
			ov:SetText(string.format("  overlay: %d triangles (%d dynamic), %d blocks, %d water columns, %d lines; build %.1f ms (worker), lines + meshes %.1f ms (game thread, max %.1f)",
				O.tris or 0, O.dynTris or 0, O.blocks or 0, O.waterCols or 0, O.lines or 0, O.debugMs or 0, O.meshMs or 0, O.meshMsMax or 0))
		end
	end,
})
