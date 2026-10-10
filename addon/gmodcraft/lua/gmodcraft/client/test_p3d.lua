-- Scripted P3d run (client): Minecraft's light sources light GMod (dynamic lights), plus P3c's open
-- item check. Dev only (-gmodcraft_dev). Starts when data/gmodcraft/p3dtest.txt exists at
-- InitPostEntity, or with gmodcraft_test_p3d. World changes go through MC chat commands and are
-- undone at the end. Nothing of the user's is overwritten: blocks are placed with "keep" (only into
-- air) in boxes that held no light source before (BlocksLightsCount), and removed with
-- "fill ... air replace <that block>". Summoned items carry the tag gmc_p3d and are killed. No
-- liquids, no inventory changes, the game mode is left alone.
--
--   planks    P3b's view (the spawn eye, findWall's yaw, level): screen pixels with gmodcraft_blocks
--             on vs off; the ones that change are Minecraft blocks (the user's planks along the
--             building wall): they must not be near-black
--   wall      a torch and a lantern 1.5 blocks out from the nearest GMod wall: wall pixels next to
--             them with gmodcraft_lights 0 vs 1 (points Minecraft geometry covers are left out)
--   budget    30 torches in a 3 x 10 grid, 3 blocks apart (30 light cells): active lights and FPS at
--             gmodcraft_lights_max 0, 4, 8, 12
--   items     a diamond and a stone block item (no pickup): pixels with gmodcraft_entities on vs off
--   cleanup   torches / lantern removed by fill-replace; the boxes hold no light source again and
--             the emitter count is back where it started
-- Logs "[gmodcraft-test]" PASS/FAIL lines and numbers; at most 2 jpeg screenshots.

local T = {}
gmodcraft.testP3d = T
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local TAG = "gmc_p3d"

local function log(fmt, ...) print("[gmodcraft-test] " .. string.format(fmt, ...)) end
local results = {}
local function check(name, ok, detail)
	results[#results + 1] = { name, ok }
	log("%s %s%s", ok and "PASS" or "FAIL", name, detail and (": " .. detail) or "")
end
local function wait(s)
	local u = RealTime() + s
	while RealTime() < u do coroutine.yield() end
end
local function waitUntil(fn, timeout)
	local deadline = RealTime() + timeout
	while RealTime() < deadline do
		if fn() then return true end
		coroutine.yield()
	end
	return false
end
local shots = 0
local function shot(label)
	if shots >= 2 then return end
	shots = shots + 1
	log("SHOT %d %s", shots, label)
	RunConsoleCommand("jpeg")
	wait(0.5)
end

-- ---- pixels (project in HUDPaint, read in PostRender; points may also carry x, y directly) ---------------
local pending
local function project(req)
	for _, p in ipairs(req.points) do
		if p.pos then
			local s = p.pos:ToScreen()
			p.x, p.y = math.floor(s.x), math.floor(s.y)
		end
	end
	req.projected = true
end
hook.Add("HUDPaint", "gmodcraft_test_p3d", function()
	if pending and not pending.projected then project(pending) end
end)
hook.Add("PostRender", "gmodcraft_test_p3d", function()
	if not pending then return end
	if not pending.projected then
		pending.late = (pending.late or 0) + 1
		if pending.late < 30 then return end
		project(pending)
	end
	render.CapturePixels()
	for _, p in ipairs(pending.points) do
		p.r = nil
		if p.x and p.x >= 0 and p.y >= 0 and p.x < ScrW() and p.y < ScrH() then p.r, p.g, p.b = render.ReadPixel(p.x, p.y) end
	end
	pending.done = true
	pending = nil
end)
local holdLook
local function capture(points, settle)
	if holdLook then
		gmodcraft.input.look.yaw, gmodcraft.input.look.pitch = holdLook[1], holdLook[2]
	end
	wait(settle or 0.3)
	for _, p in ipairs(points) do p.r = nil end
	local req = { points = points }
	pending = req
	if not waitUntil(function() return req.done end, 3) then pending = nil log("capture timed out") end
	return points
end
local function copyPoints(src)
	local out = {}
	for i, p in ipairs(src) do out[i] = { pos = p.pos, x = (not p.pos) and p.x or nil, y = (not p.pos) and p.y or nil } end
	return out
end
local function diff(a, b)
	if not a.r or not b.r then return -1 end
	return math.max(math.abs(a.r - b.r), math.abs(a.g - b.g), math.abs(a.b - b.b))
end
local function lum(p) return p.r and (0.2126 * p.r + 0.7152 * p.g + 0.0722 * p.b) or -1 end
local function px(p) return p.r and string.format("(%d,%d,%d)@%d,%d", p.r, p.g, p.b, p.x, p.y) or "none" end
local function median(list)
	if #list == 0 then return -1 end
	table.sort(list)
	return list[math.max(1, math.floor(#list * 0.5 + 0.5))]
end

-- ---- MC chat commands ------------------------------------------------------------------------------------
local SDL_SLASH, SDL_RETURN = 56, 40
local function mcCommand(text)
	gmodcraft.input.Tap(SDL_SLASH)
	wait(0.5)
	for _, cp in utf8.codes(text) do gmodcraft.PushInput(K.InText, 0, cp) end
	wait(0.3)
	gmodcraft.input.Tap(SDL_RETURN)
	wait(0.5)
	log("mc command: /%s", text)
end
-- what this run may have placed: { x0, y0, z0, x1, y1, z1, block } boxes, removed by fill-replace
local placed = {}
local function place(x, y, z, block)
	placed[#placed + 1] = { x, y, z, x, y, z, block }
	mcCommand(string.format("setblock %d %d %d %s keep", x, y, z, block))
end
local function cleanupWorld()
	mcCommand("kill @e[tag=" .. TAG .. "]")
	local list = {}
	for i = #placed, 1, -1 do
		local b = placed[i]
		mcCommand(string.format("fill %d %d %d %d %d %d minecraft:air replace %s", b[1], b[2], b[3], b[4], b[5], b[6], b[7]))
		list[#list + 1] = string.format("%d %d %d %s", b[1], b[2], b[3], b[7])
	end
	log("MC blocks this run may have placed (each removed by fill ... air replace <block>): %s; entities tagged %s killed", table.concat(list, "; "), TAG)
	return list
end

local function lookAt(pos)
	local eye = gmodcraft.view.lastView.origin
	local ang = (pos - eye):Angle()
	local look = gmodcraft.input.look
	look.yaw, look.pitch = C.YawToMc(ang.y), math.NormalizeAngle(ang.p)
	holdLook = { look.yaw, look.pitch }
	wait(0.8)
end

-- P3a / P3b's wall search (the first yaw whose wall is further than minDist)
local function findWall(eye, minDist)
	for i = 0, 23 do
		local yaw = i * 15
		local dir = Angle(0, yaw, 0):Forward()
		local tr = util.TraceLine({ start = eye, endpos = eye + dir * 1500, mask = MASK_SOLID_BRUSHONLY })
		if tr.Hit and not tr.StartSolid and tr.HitPos:Distance(eye) > minDist and math.abs(tr.HitNormal.z) < 0.3 then return dir, yaw, tr.HitPos end
	end
end
-- the nearest vertical brush wall around the eye (60..500 units)
local function nearestWall(eye)
	local best
	for i = 0, 35 do
		local dir = Angle(0, i * 10, 0):Forward()
		local tr = util.TraceLine({ start = eye, endpos = eye + dir * 500, mask = MASK_SOLID_BRUSHONLY })
		local d = tr.HitPos:Distance(eye)
		if tr.Hit and not tr.StartSolid and d > 100 and math.abs(tr.HitNormal.z) < 0.1 and (not best or d < best.d) then
			best = { d = d, hit = tr.HitPos, normal = tr.HitNormal, yaw = i * 10 }
		end
	end
	return best
end

-- average FPS and frame time over `seconds`
local function measure(seconds)
	local t0, frames, ft = SysTime(), 0, {}
	while SysTime() - t0 < seconds do
		coroutine.yield()
		frames = frames + 1
		ft[#ft + 1] = RealFrameTime() * 1000
	end
	local el = SysTime() - t0
	table.sort(ft)
	return frames / el, ft[math.max(1, math.floor(#ft * 0.5))] or -1, ft[math.max(1, math.floor(#ft * 0.95))] or -1, frames
end

local function run()
	local ply = LocalPlayer()
	local TU = gmodcraft.test
	TU.SetConVarTemp("jpeg_quality", "70")
	TU.SetConVarTemp("cl_showhints", "0")
	TU.SetConVarTemp("gmodcraft_entities", "1")
	TU.SetConVarTemp("gmodcraft_blocks", "1")
	TU.SetConVarTemp("gmodcraft_lights", "1")
	TU.SetConVarTemp("gmodcraft_lights_max", "10")
	log("P3d run on %s", game.GetMap())
	local up = waitUntil(function() return gmodcraft.IsPuppet(ply) and gmodcraft.view.valid end, 300)
	check("puppet active", up)
	if not up then log("P3DTEST DONE (aborted)") return end
	waitUntil(function() return gmodcraft.BlocksAtlas() ~= nil end, 30)
	wait(4)
	TU.SetConVarTemp("gmodcraft_overlay", "0")  -- the hand would cover pixels
	local function cv(n) local c = GetConVar(n) return c and c:GetString() or "?" end
	local L0 = gmodcraft.BlocksInfo().lights
	log("ENV r_dynamic %s, mat_queue_mode %s, fps_max %s, %dx%d; lights: %d emitters in %d sections, %d messages, %d malformed, %d active", cv("r_dynamic"),
		cv("mat_queue_mode"), cv("fps_max"), ScrW(), ScrH(), L0.emitters, L0.sections, L0.messages, L0.malformed, L0.active)
	local emitters0 = L0.emitters
	if cv("r_dynamic") == "0" then
		TU.SetConVarTemp("r_dynamic", "1")  -- dlights can't light the world otherwise
		log("r_dynamic was 0: set to 1 for the run")
	end
	if L0.messages == 0 then log("NOTE no kRenLights message yet (the user's world may hold no light source in range)") end
	local M = gmodcraft.clientLink.M
	local by = math.floor(M.y)
	local eye0 = gmodcraft.view.lastView.origin

	-- 1. P3b's planks along the building wall, from P3b's view (no light source placed yet)
	do
		local dir, yaw = findWall(eye0, 300)
		if not dir then dir, yaw = findWall(eye0, 120) end
		if dir then
			local look = gmodcraft.input.look
			look.yaw, look.pitch = C.YawToMc(yaw), 0
			holdLook = { look.yaw, 0 }
			wait(1.5)
			local grid = {}
			for x = 20, ScrW() - 20, 24 do
				for y = 40, ScrH() - 40, 24 do grid[#grid + 1] = { x = x, y = y } end
			end
			local on = capture(copyPoints(grid), 1)
			TU.SetConVarTemp("gmodcraft_blocks", "0")
			local off = capture(copyPoints(grid), 0.5)
			TU.SetConVarTemp("gmodcraft_blocks", "1")
			local lums, dark, darkRight, nRight, worst = {}, 0, 0, 0, nil
			for i = 1, #on do
				if diff(on[i], off[i]) > 24 then
					local l = lum(on[i])
					lums[#lums + 1] = l
					if l < 20 then dark = dark + 1 end
					if on[i].x >= ScrW() / 2 then
						nRight = nRight + 1
						if l < 20 then darkRight = darkRight + 1 end
					end
					if not worst or l < lum(worst) then worst = on[i] end
				end
			end
			local n = #lums
			local med = median(lums)
			log("PLANKS yaw %d, eye %s: %d block pixels (%d right half), median lum %.1f, %d near-black (lum < 20; %d right half), darkest %s; ref 754,236 %s",
				yaw, tostring(eye0), n, nRight, med, dark, darkRight, worst and px(worst) or "none", (function()
					for _, p in ipairs(on) do if math.abs(p.x - 754) <= 12 and math.abs(p.y - 236) <= 12 then return px(p) end end
					return "n/a"
				end)())
			check("P3b planks not near-black", n >= 10 and med >= 35 and dark <= n * 0.15,
				string.format("%d block pixels, median lum %.1f, near-black %d (%.0f%%)", n, med, dark, n > 0 and dark * 100 / n or 0))
			shot("p3d_planks_p3b_view")
		else
			check("P3b view: a wall", false)
		end
	end

	-- 2. a torch and a lantern next to the nearest GMod wall
	local wall = nearestWall(eye0)
	if not wall then
		check("a GMod wall near the player", false)
		log("P3DTEST DONE (aborted)")
		return
	end
	local N = Vector(wall.normal.x, wall.normal.y, 0):GetNormalized()
	local A = N:Cross(Vector(0, 0, 1))  -- along the wall
	local foot = Vector(wall.hit.x, wall.hit.y, C.McYToZ(by))
	local function mcAt(v) local x, _, z = C.ToMc(v) return math.floor(x), math.floor(z) end
	-- wall points around the torch's spot, 0.5 .. 3.5 blocks up, -2 .. +4 blocks along (the lantern
	-- goes about 3 along); visible from the eye past GMod's brushes
	local tx0, tz0 = mcAt(foot + N * 60)
	local tpos = C.FromMc(tx0 + 0.5, by, tz0 + 0.5)
	local wallBase = tpos - N * ((tpos - wall.hit):Dot(N) - 1)  -- on the wall plane, 1 unit out
	local wpts = {}
	for along = -2, 4, 1 do
		for upb = 0.5, 3.5, 1 do
			local p = Vector(wallBase.x, wallBase.y, C.McYToZ(by) + upb * 40) + A * (along * 40)
			local tr = util.TraceLine({ start = eye0, endpos = p, mask = MASK_SOLID_BRUSHONLY })
			if not tr.Hit or tr.HitPos:Distance(p) < 4 then wpts[#wpts + 1] = { pos = p } end
		end
	end
	local wallLook = Vector(wallBase.x, wallBase.y, C.McYToZ(by) + 60) + A * 40
	lookAt(wallLook)
	local before = capture(copyPoints(wpts), 0.8)  -- lights on, nothing placed yet
	local rebakes0 = gmodcraft.BlocksInfo().light.rebakes
	-- the torch 1.5, 2.5 or 3.5 blocks out (the first spot "keep" can fill: the user's planks may sit
	-- along the wall); the lantern 3 blocks along from it
	local tx, tz, lx, lz
	local function freeAt(x, z) return gmodcraft.BlocksLightsCount(x - 1, by - 1, z - 1, x + 1, by + 1, z + 1) == 0 end
	local function tryPlace(x, z, block)
		local n0 = gmodcraft.BlocksInfo().lights.emitters
		place(x, by, z, block)
		return waitUntil(function() return gmodcraft.BlocksInfo().lights.emitters > n0 end, 3)
	end
	for _, out in ipairs({ 60, 100, 140 }) do
		local x, z = mcAt(foot + N * out)
		if freeAt(x, z) and tryPlace(x, z, "minecraft:torch") then
			tx, tz = x, z
			break
		end
		log("torch spot %d %d %d (%d units out): not placed (occupied or not free)", x, by, z, out)
	end
	if tx then
		local base = C.FromMc(tx + 0.5, by, tz + 0.5)
		for _, along in ipairs({ 120, -120, 160 }) do
			local x, z = mcAt(base + A * along)
			if freeAt(x, z) and tryPlace(x, z, "minecraft:lantern") then
				lx, lz = x, z
				break
			end
			log("lantern spot %d %d %d: not placed", x, by, z)
		end
	end
	log("wall at %s (normal %s, %.0f units), torch %s, lantern %s", tostring(wall.hit), tostring(N), wall.d, tx and string.format("%d %d %d", tx, by, tz) or "none",
		lx and string.format("%d %d %d", lx, by, lz) or "none")
	check("torch and lantern placed next to the wall (into air, spots free of the user's light sources)", tx ~= nil and lx ~= nil)
	if not tx then
		cleanupWorld()
		log("P3DTEST DONE (aborted)")
		return
	end
	wait(1)
	local Li = gmodcraft.BlocksInfo().lights
	log("LIGHTS after placing: %d -> %d emitters, %d active lights, %d cells", emitters0, Li.emitters, Li.active, Li.cells)
	lookAt(wallLook)
	local lit = capture(copyPoints(wpts), 0.8)
	TU.SetConVarTemp("gmodcraft_lights_brightness", "4")
	local lit4 = capture(copyPoints(wpts), 0.8)  -- calibration: the same at brightness 4
	TU.SetConVarTemp("gmodcraft_lights_brightness", "2")
	TU.SetConVarTemp("gmodcraft_lights", "0")
	local dark = capture(copyPoints(wpts), 0.8)
	TU.SetConVarTemp("gmodcraft_blocks", "0")
	TU.SetConVarTemp("gmodcraft_lights", "1")
	local bare = capture(copyPoints(wpts), 0.8)  -- lights on, Minecraft's blocks hidden: what covers the wall
	TU.SetConVarTemp("gmodcraft_blocks", "1")
	local gains, parts, best, gain4 = {}, {}, nil, 0
	for i = 1, #wpts do
		local covered = diff(lit[i], bare[i]) > 12
		if lit[i].r and dark[i].r and before[i].r and not covered then
			local g = lum(lit[i]) - lum(dark[i])
			gains[#gains + 1] = g
			if lit4[i].r then gain4 = gain4 + lum(lit4[i]) - lum(dark[i]) end
			if not best or g > best.g then best = { g = g, i = i } end
		end
		if i <= 40 then parts[#parts + 1] = string.format("%s/%s/%s%s", px(before[i]), px(dark[i]), px(lit[i]), covered and "c" or "") end
	end
	local mean = 0
	for _, g in ipairs(gains) do mean = mean + g end
	mean = #gains > 0 and mean / #gains or -1
	log("WALL points before / lights 0 / lights 1 (c = covered by MC geometry): %s", table.concat(parts, "; "))
	log("WALL mean lum gain at brightness 2: %.1f, at brightness 4: %.1f (%d points)", mean, #gains > 0 and gain4 / #gains or -1, #gains)
	if best then
		log("WALL brightest gain %.1f at %s: before %s, lights 0 %s, lights 1 %s", best.g, tostring(wpts[best.i].pos), px(before[best.i]), px(dark[best.i]), px(lit[best.i]))
	end
	check("GMod wall brightens next to the torch and lantern (lights 0 vs 1)", #gains >= 3 and mean >= 6 and best and best.g >= 12,
		string.format("%d uncovered points, mean lum gain %.1f, best %.1f", #gains, mean, best and best.g or -1))
	shot("p3d_torch_lantern_wall")
	-- does our light reach the blocks' S sampling (render.GetLightColor)? re-bakes while lit
	wait(5)
	local rebakes1 = gmodcraft.BlocksInfo().light.rebakes
	log("FEEDBACK block re-bakes over ~8 s with the two lights on: %d (before placing: %d)", rebakes1 - rebakes0, rebakes0)

	-- 3. 30 torches in a 3 x 10 grid, 3 blocks apart: 30 light cells
	local nx, nz = math.Round(N.x), math.Round(-N.y)  -- the wall normal in MC axes (x east, z = -Source y)
	if math.abs(nx) + math.abs(nz) ~= 1 then nx, nz = (math.abs(N.x) > math.abs(N.y)) and (N.x > 0 and 1 or -1) or 0, (math.abs(N.x) > math.abs(N.y)) and 0 or (N.y > 0 and -1 or 1) end
	local ax, az = -nz, nx
	local gx0, gz0, gx1, gz1 = math.huge, math.huge, -math.huge, -math.huge
	local spots = {}
	for r = 1, 3 do
		for c = -5, 4 do
			local x, z = tx + nx * 3 * r + ax * 3 * c, tz + nz * 3 * r + az * 3 * c
			spots[#spots + 1] = { x, z }
			gx0, gz0, gx1, gz1 = math.min(gx0, x), math.min(gz0, z), math.max(gx1, x), math.max(gz1, z)
		end
	end
	local gridFree = gmodcraft.BlocksLightsCount(gx0, by - 1, gz0, gx1, by + 1, gz1) == 0
	check("torch grid box holds no light source of the user's", gridFree, string.format("box %d %d %d .. %d %d %d", gx0, by - 1, gz0, gx1, by + 1, gz1))
	if gridFree then
		for _, s in ipairs(spots) do mcCommand(string.format("setblock %d %d %d minecraft:torch keep", s[1], by, s[2])) end
		placed[#placed + 1] = { gx0, by, gz0, gx1, by, gz1, "minecraft:torch" }
		waitUntil(function() return gmodcraft.BlocksLightsCount(gx0, by, gz0, gx1, by, gz1) >= 30 end, 5)
		wait(1)
		local nGrid = gmodcraft.BlocksLightsCount(gx0, by, gz0, gx1, by, gz1)
		local Lg = gmodcraft.BlocksInfo().lights
		check("30 torches placed", nGrid >= 28, string.format("%d torches in the grid, %d emitters, %d light cells in range", nGrid, Lg.emitters, Lg.cells))
		-- look over the grid towards the wall
		lookAt(C.FromMc(tx + nx * 6 + 0.5, by + 0.5, tz + nz * 6 + 0.5))
		local fps, okBudget, rows = {}, true, {}
		for _, budget in ipairs({ 0, 4, 8, 12 }) do
			TU.SetConVarTemp("gmodcraft_lights_max", tostring(budget))
			wait(1.5)
			local rb0 = gmodcraft.BlocksInfo().light.rebakes
			local f, med, p95, frames = measure(4)
			local Lb = gmodcraft.BlocksInfo().lights
			local active, lit = Lb.active, gmodcraft.blocks.lightStatsP3d.lit
			okBudget = okBudget and active <= budget and lit <= budget
			fps[budget] = f
			rows[#rows + 1] = string.format("max %d: %d active (%d cells), FPS %.1f, frame median %.2f ms, p95 %.2f ms (%d frames), re-bakes %d", budget, active, Lb.cells,
				f, med, p95, frames, gmodcraft.BlocksInfo().light.rebakes - rb0)
			log("FPS %s", rows[#rows])
		end
		TU.SetConVarTemp("gmodcraft_lights_max", "10")
		check("lights stay within the budget (0, 4, 8, 12)", okBudget, table.concat(rows, "; "))
		check("no FPS cliff (12 lights >= 70% of 0 lights)", fps[12] and fps[0] and fps[12] >= 0.7 * fps[0], string.format("%.1f vs %.1f FPS", fps[12] or -1, fps[0] or -1))
	end

	-- 4. dropped items on screen (P3c's open check)
	do
		local ix, iz = tx + nx * 2 - ax * 2, tz + nz * 2 - az * 2
		local jx, jz = ix - ax, iz - az
		mcCommand(string.format('summon item %.2f %.2f %.2f {Item:{id:"minecraft:diamond",count:1},PickupDelay:32767,Tags:["%s"]}', ix + 0.5, by + 0.2, iz + 0.5, TAG))
		mcCommand(string.format('summon item %.2f %.2f %.2f {Item:{id:"minecraft:stone",count:1},PickupDelay:32767,Tags:["%s"]}', jx + 0.5, by + 0.2, jz + 0.5, TAG))
		wait(2)
		local ipos, jpos = C.FromMc(ix + 0.5, by + 0.45, iz + 0.5), C.FromMc(jx + 0.5, by + 0.35, jz + 0.5)
		lookAt((ipos + jpos) * 0.5)
		local pts = {}
		for _, base in ipairs({ ipos, jpos }) do
			for _, o in ipairs({ Vector(0, 0, 0), Vector(0, 0, 4), Vector(0, 0, -4), Vector(4, 0, 0), Vector(-4, 0, 0), Vector(0, 4, 0), Vector(0, -4, 0) }) do
				pts[#pts + 1] = { pos = base + o }
			end
		end
		local on = capture(copyPoints(pts), 0.5)
		TU.SetConVarTemp("gmodcraft_entities", "0")
		local off = capture(copyPoints(pts), 0.5)
		TU.SetConVarTemp("gmodcraft_entities", "1")
		local bestD, parts = -1, {}
		for i = 1, #on do
			local d = diff(on[i], off[i])
			bestD = math.max(bestD, d)
			parts[#parts + 1] = string.format("%s -> %s (d %d)", px(on[i]), px(off[i]), d)
		end
		log("PIX items: %s", table.concat(parts, "; "))
		local e = gmodcraft.EntitiesInfo()
		check("dropped items drawn on screen (pixel on vs off)", bestD >= 12, string.format("d %d; items %d, cubes %d baked", bestD, e.items, e.blocks))
	end

	holdLook = nil
	cleanupWorld()
	-- verify: the boxes hold no light source again (the module's view of Minecraft's world), the
	-- emitter count is back, and once more fill-replace (MC's log must say nothing was filled)
	wait(2)
	local leftover = 0
	for _, b in ipairs(placed) do
		local n = gmodcraft.BlocksLightsCount(b[1], b[2], b[3], b[4], b[5], b[6])
		leftover = leftover + math.max(0, n)
		mcCommand(string.format("fill %d %d %d %d %d %d minecraft:air replace %s", b[1], b[2], b[3], b[4], b[5], b[6], b[7]))
	end
	local Le = gmodcraft.BlocksInfo().lights
	check("cleanup: no light source left in the boxes, emitters back", leftover == 0 and Le.emitters == emitters0,
		string.format("%d left in boxes, emitters %d (start %d)", leftover, Le.emitters, emitters0))
	placed = {}
	wait(1)
	local nf = 0
	for _, r in ipairs(results) do if not r[2] then nf = nf + 1 end end
	log("P3DTEST DONE: %d checks, %d failed", #results, nf)
end

local co
function T.Start()
	if co then return end
	results = {}
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then
			log("ERROR %s", tostring(err))
			holdLook = nil
			pcall(cleanupWorld)
			placed = {}
			log("P3DTEST DONE (error)")
		end
		holdLook = nil
		gmodcraft.test.RestoreConVars("end of run")
	end)
	hook.Add("Think", "gmodcraft_test_p3d", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_p3d")
			co = nil
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_p3d", T.Start)
hook.Add("InitPostEntity", "gmodcraft_test_p3d", function()
	if file.Exists("gmodcraft/p3dtest.txt", "DATA") then
		log("p3dtest.txt present: starting in 5 s")
		timer.Simple(5, T.Start)
	end
end)
