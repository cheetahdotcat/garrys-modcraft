-- Scripted P3c run (client): Minecraft's per-frame things drawn in GMod. Dev only (-gmodcraft_dev).
-- Starts when data/gmodcraft/p3ctest.txt exists at InitPostEntity, or with gmodcraft_test_p3c.
-- Every world change goes through MC chat commands (allowCommands) and is undone at the end:
-- blocks back to air, everything summoned carries the tag gmc_p3c and is killed, the game mode is
-- put back if the run had to switch it. No liquids, no inventory changes.
--
--   setup     a free cardinal direction from the feet; 3..6 blocks out
--   blocks    a chest and a bed (/setblock): block entities in the scene, pixels with
--             gmodcraft_entities on vs off in the same view
--   items     a diamond and a stone block item (/summon item, no pickup): sprite and cube drawn,
--             yaw changes over time (spin)
--   mob       /summon cow (NoAI): its texture arrives, the scene grows, pixels on vs off
--   outline   look at a stone: the selection box is that block (Source units), pixels on vs off
--   cracks    hold attack on the stone: a kWeCrack, pixels; then break a dirt block: particles
--             (the scene grows while they live). In creative mode blocks break at once: the run
--             switches to survival for this step and back
--   arrow     /summon arrow falling into the stone: kWeArrow drawn, pixels on vs off
--   avatar    F5: the avatar's batches arrive, pixels on vs off; F5 twice more back to first person
--   cost      48 synthetic mobs + 1000 particles (EntitiesDevInject) on top: prepare + draw per frame
-- Logs "[gmodcraft-test]" PASS/FAIL lines and numbers; at most 3 jpeg screenshots.

local T = {}
gmodcraft.testP3c = T
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local TAG = "gmc_p3c"

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
	if shots >= 3 then return end
	shots = shots + 1
	log("SHOT %d %s", shots, label)
	RunConsoleCommand("jpeg")
	wait(0.5)
end

-- ---- pixels (test_p3a's pattern: project in HUDPaint, read in PostRender) -----------------------------
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
hook.Add("HUDPaint", "gmodcraft_test_p3c", function()
	if pending and not pending.projected then project(pending) end
end)
hook.Add("PostRender", "gmodcraft_test_p3c", function()
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
local function capture(points)
	if holdLook then
		gmodcraft.input.look.yaw, gmodcraft.input.look.pitch = holdLook[1], holdLook[2]
	end
	wait(0.3)
	local req = { points = points }
	pending = req
	if not waitUntil(function() return req.done end, 3) then pending = nil log("capture timed out") end
	return points
end
local function diff(a, b)
	if not a.r or not b.r then return -1 end
	return math.max(math.abs(a.r - b.r), math.abs(a.g - b.g), math.abs(a.b - b.b))
end
local function px(p) return p.r and string.format("(%d,%d,%d)@%d,%d", p.r, p.g, p.b, p.x, p.y) or "none" end
-- Pixels at Source points with gmodcraft_entities on, then off, in the same view; returns the
-- largest difference and a detail string.
local function onOff(label, positions)
	local on, off = {}, {}
	for i, pos in ipairs(positions) do on[i], off[i] = { pos = pos }, { pos = pos } end
	capture(on)
	gmodcraft.test.SetConVarTemp("gmodcraft_entities", "0")
	capture(off)
	gmodcraft.test.SetConVarTemp("gmodcraft_entities", "1")
	local best, parts = -1, {}
	for i = 1, #on do
		local d = diff(on[i], off[i])
		best = math.max(best, d)
		parts[#parts + 1] = string.format("%s -> %s (d %d)", px(on[i]), px(off[i]), d)
	end
	log("PIX %s: %s", label, table.concat(parts, "; "))
	return best, table.concat(parts, "; ")
end

-- ---- MC chat commands ----------------------------------------------------------------------------------
local SDL_SLASH, SDL_RETURN, SDL_F5 = 56, 40, 62
local function mcCommand(text)
	gmodcraft.input.Tap(SDL_SLASH)
	wait(0.5)
	for _, cp in utf8.codes(text) do gmodcraft.PushInput(K.InText, 0, cp) end
	wait(0.3)
	gmodcraft.input.Tap(SDL_RETURN)
	wait(0.5)
	log("mc command: /%s", text)
end
local touched = {}  -- blocks this run set (restored to air at the end, newest first)
local function setblock(x, y, z, what)
	touched[#touched + 1] = { x, y, z, what }
	mcCommand(string.format("setblock %d %d %d %s", x, y, z, what))
end
local switchedMode = false
local function cleanupWorld()
	gmodcraft.input.Push(K.InMouseButton, 1, 0)
	if gmodcraft.EntitiesDevInject then gmodcraft.EntitiesDevInject(0, 0) end
	mcCommand("kill @e[tag=" .. TAG .. "]")
	for i = #touched, 1, -1 do
		local t = touched[i]
		mcCommand(string.format("setblock %d %d %d minecraft:air", t[1], t[2], t[3]))
	end
	if T.site then
		-- what breaking left behind near the site (the dirt item, a bed/chest drop if any)
		mcCommand(string.format('kill @e[type=item,x=%.2f,y=%.2f,z=%.2f,distance=..3,nbt={Item:{id:"minecraft:dirt"}}]', T.site[1] + 0.5, T.site[2] + 0.5, T.site[3] + 0.5))
	end
	if switchedMode then
		mcCommand("gamemode creative")
		switchedMode = false
	end
	local list = {}
	for _, t in ipairs(touched) do list[#list + 1] = string.format("%d %d %d %s", t[1], t[2], t[3], t[4]) end
	log("MC blocks touched (all set back to air): %s; entities tagged %s killed", table.concat(list, "; "), TAG)
	touched = {}
end

-- Look at an MC point (absolute blocks) and hold the look.
local function lookAt(mx, my, mz)
	local eye = gmodcraft.view.lastView.origin
	local ang = (C.FromMc(mx, my, mz) - eye):Angle()
	local look = gmodcraft.input.look
	look.yaw, look.pitch = C.YawToMc(ang.y), math.NormalizeAngle(ang.p)
	holdLook = { look.yaw, look.pitch }
	wait(0.6)
end

local function worldEnts()
	gmodcraft.entities.wantEntities = true
	wait(0.1)
	local w = gmodcraft.entities.world
	local list = {}
	for i = 0, (w.nEnts or 0) - 1 do
		local o = i * 6
		list[#list + 1] = { kind = w.ents[o + 1], id = w.ents[o + 2], yaw = w.ents[o + 3], x = w.ents[o + 4], y = w.ents[o + 5], z = w.ents[o + 6] }
	end
	return list
end
local function count(list, kind)
	local n = 0
	for _, e in ipairs(list) do if e.kind == kind then n = n + 1 end end
	return n
end

local function run()
	local ply = LocalPlayer()
	gmodcraft.test.SetConVarTemp("jpeg_quality", "70")
	gmodcraft.test.SetConVarTemp("cl_showhints", "0")
	gmodcraft.test.SetConVarTemp("gmodcraft_entities", "1")
	log("P3c run on %s", game.GetMap())
	local up = waitUntil(function() return gmodcraft.IsPuppet(ply) and gmodcraft.view.valid end, 300)
	check("puppet active", up)
	if not up then log("P3CTEST DONE (aborted)") return end
	waitUntil(function() return gmodcraft.BlocksAtlas() ~= nil end, 30)
	wait(3)
	gmodcraft.test.SetConVarTemp("gmodcraft_overlay", "0")  -- the hand would cover pixels
	local info0 = gmodcraft.EntitiesInfo()
	log("start: %d textures, scene %d v, avatar %d v, dynOk %d (%s)", info0.textures, info0.scene.vertices, info0.avatar.vertices, info0.dynOk, info0.dynProbe)

	-- setup: the cardinal direction with the most room (GMod brushes) at eye height
	local M = gmodcraft.clientLink.M
	local bx, by, bz = math.floor(M.x), math.floor(M.y), math.floor(M.z)
	local eye = gmodcraft.view.lastView.origin
	local best, bd = nil, -1
	for _, d in ipairs({ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) do
		local dir = Vector(d[1], -d[2], 0)
		local tr = util.TraceLine({ start = eye, endpos = eye + dir * 400, mask = MASK_SOLID_BRUSHONLY })
		local dist = tr.Hit and tr.HitPos:Distance(eye) or 400
		if dist > bd then best, bd = d, dist end
	end
	local fx, fz = best[1], best[2]
	local rx, rz = -fz, fx  -- right of forward in MC (x east, z south): forward (1,0) -> right (0,1)
	local function at(f, r, dy) return bx + fx * f + rx * r, by + (dy or 0), bz + fz * f + rz * r end
	T.site = { at(3, 1) }  -- the dirt (its drop is cleaned up)
	log("feet block %d %d %d, forward (%d, %d), room %.0f units", bx, by, bz, fx, fz, bd)
	if bd < 200 then log("little room in front: results may suffer") end
	local facing = fx == 1 and "east" or fx == -1 and "west" or fz == 1 and "south" or "north"
	lookAt(bx + 0.5 + fx * 4, by + 0.5, bz + 0.5 + fz * 4)
	local function centre(x, y, z, dy) return C.FromMc(x + 0.5, y + (dy or 0.5), z + 0.5) end

	-- blocks: chest, bed
	local cx, cy, cz = at(4, -2)
	local fbx, fby, fbz = at(4, 0)
	local hbx, hby, hbz = at(5, 0)
	local sx, sy, sz = at(3, 2)   -- the stone (outline, cracks, arrow)
	local dx, dy, dz = at(3, 1)   -- the dirt (broken: particles)
	setblock(cx, cy, cz, "minecraft:chest[facing=" .. (fx == 1 and "west" or fx == -1 and "east" or fz == 1 and "north" or "south") .. "]")
	setblock(fbx, fby, fbz, "minecraft:red_bed[facing=" .. facing .. ",part=foot]")
	setblock(hbx, hby, hbz, "minecraft:red_bed[facing=" .. facing .. ",part=head]")
	setblock(sx, sy, sz, "minecraft:stone")
	setblock(dx, dy, dz, "minecraft:dirt")
	-- items (no pickup) and a cow, tagged
	local ix, iy, iz = at(2, -1)
	local jx, jy, jz = at(2, 1)
	-- coordinates as %.2f of the block + offset ("%d.5" of a negative block is the wrong block: run 1 put the items under the floor)
	mcCommand(string.format('summon item %.2f %.2f %.2f {Item:{id:"minecraft:diamond",count:1},PickupDelay:32767,Tags:["%s"]}', ix + 0.5, iy + 0.2, iz + 0.5, TAG))
	mcCommand(string.format('summon item %.2f %.2f %.2f {Item:{id:"minecraft:stone",count:1},PickupDelay:32767,Tags:["%s"]}', jx + 0.5, jy + 0.2, jz + 0.5, TAG))
	local mx, my, mz = at(6, 2)
	mcCommand(string.format('summon cow %.2f %.2f %.2f {NoAI:1b,Tags:["%s"]}', mx + 0.5, my, mz + 0.5, TAG))
	wait(2)
	lookAt(bx + 0.5 + fx * 4, by + 0.3, bz + 0.5 + fz * 4)
	wait(1)
	local inf = gmodcraft.EntitiesInfo()
	log("scene after setup: %d vertices in %d batches, %d textures, dynOk %d (%s), chunk %d", inf.scene.vertices, inf.scene.batches, inf.textures, inf.dynOk,
		inf.dynProbe, inf.chunk)
	check("dynamic mesh path ok", inf.dynOk == 1, inf.dynProbe)
	check("scene arrives (chest, bed, cow)", inf.scene.vertices > 0 and inf.scene.batches > 0, string.format("%d v / %d batches", inf.scene.vertices, inf.scene.batches))
	check("entity textures arrive and get materials", inf.textures > 0 and inf.materials >= inf.textures, string.format("%d textures, %d materials", inf.textures, inf.materials))
	local d1 = onOff("chest", { centre(cx, cy, cz, 0.4) })
	check("chest drawn (pixel on vs off)", d1 >= 12, "d " .. d1)
	local bedPx = capture({ { pos = centre(fbx, fby, fbz, 0.4) } })[1]
	log("bed (a block model in MC 26: drawn by the section path): %s", px(bedPx))
	local d3 = onOff("cow", { centre(mx, my, mz, 0.8), centre(mx, my, mz, 1.1) })
	check("cow drawn (pixel on vs off)", d3 >= 12, "d " .. d3)

	-- items: drawn and spinning
	local e1 = worldEnts()
	wait(0.5)
	local e2 = worldEnts()
	local spin = {}
	for _, a in ipairs(e1) do
		for _, b in ipairs(e2) do
			if a.id == b.id and (a.kind == 2 or a.kind == 4) then spin[#spin + 1] = string.format("kind %d yaw %.1f -> %.1f", a.kind, a.yaw, b.yaw) end
		end
	end
	check("dropped items in WorldEntities (sprite + block cube)", count(e2, 2) >= 1 and count(e2, 4) >= 1, string.format("items %d, cubes %d", count(e2, 2), count(e2, 4)))
	local spun = 0
	for _, a in ipairs(e1) do
		for _, b in ipairs(e2) do
			if a.id == b.id and (a.kind == 2 or a.kind == 4) and math.abs(math.AngleDifference(a.yaw, b.yaw)) > 1 then spun = spun + 1 end
		end
	end
	check("items spin", spun >= 2, table.concat(spin, "; "))
	local d4 = onOff("items", { centre(ix, iy, iz, 0.45), centre(jx, jy, jz, 0.35) })
	check("items drawn (pixel on vs off)", d4 >= 12, "d " .. d4)
	shot("p3c_scene")

	-- outline: look at the stone
	lookAt(sx + 0.5, sy + 0.5, sz + 0.5)
	wait(0.5)
	local w = gmodcraft.entities.world
	local a, b = C.FromMc(sx, sy, sz), C.FromMc(sx + 1, sy + 1, sz + 1)
	local okSel = w.sel and math.abs(w.selMin[1] - math.min(a.x, b.x)) < 1 and math.abs(w.selMax[2] - math.max(a.y, b.y)) < 1 and math.abs(w.selMin[3] - a.z) < 1
	check("outline box is the stone", okSel == true, w.sel and string.format("min %.1f %.1f %.1f max %.1f %.1f %.1f", w.selMin[1], w.selMin[2], w.selMin[3], w.selMax[1],
		w.selMax[2], w.selMax[3]) or "no selection")

	-- cracks: hold attack on the stone
	local function hold(seconds, until_)
		gmodcraft.input.Push(K.InMouseButton, 1, 1)
		local deadline = RealTime() + seconds
		while RealTime() < deadline do
			coroutine.yield()
			if until_ and until_() then break end
		end
	end
	hold(0.4)
	local stoneGone = not gmodcraft.entities.world.sel
	gmodcraft.input.Push(K.InMouseButton, 1, 0)
	if stoneGone then
		log("the stone broke at once: creative mode; switching to survival for the mining checks")
		mcCommand("gamemode survival")
		switchedMode = true
		setblock(sx, sy, sz, "minecraft:stone")
		lookAt(sx + 0.5, sy + 0.5, sz + 0.5)
	end
	hold(1.5)
	local ec = worldEnts()
	check("mining cracks in WorldEntities", count(ec, 5) >= 1, "cracks " .. count(ec, 5))
	local inf2 = gmodcraft.EntitiesInfo()
	check("cracks baked", inf2.cracks >= 1, "cracks " .. inf2.cracks)
	-- the crack lines are sparse: a grid over the top face and the face towards the player
	local cpts = {}
	for u = 0.15, 0.86, 0.14 do
		for v = 0.15, 0.86, 0.14 do
			cpts[#cpts + 1] = C.FromMc(sx + u, sy + 1.001, sz + v)
			cpts[#cpts + 1] = C.FromMc(sx + (fx == 1 and 0 or fx == -1 and 1 or u), sy + v, sz + (fz == 1 and 0 or fz == -1 and 1 or u))
		end
	end
	local dc = onOff("cracks", cpts)
	check("cracks drawn (pixel on vs off)", dc >= 6, "d " .. dc)
	shot("p3c_cracks_outline")
	gmodcraft.input.Push(K.InMouseButton, 1, 0)
	wait(0.3)
	-- particles: break the dirt
	local base = gmodcraft.EntitiesInfo().scene.vertices
	lookAt(dx + 0.5, dy + 0.5, dz + 0.5)
	local peak = base
	hold(4, function()
		peak = math.max(peak, gmodcraft.EntitiesInfo().scene.vertices)
		return false
	end)
	gmodcraft.input.Push(K.InMouseButton, 1, 0)
	for _ = 1, 30 do
		coroutine.yield()
		peak = math.max(peak, gmodcraft.EntitiesInfo().scene.vertices)
	end
	check("particles while mining / breaking (scene grows)", peak > base, string.format("scene %d -> peak %d vertices", base, peak))

	-- arrow: falls into the stone's top
	mcCommand(string.format('summon arrow %.2f %.2f %.2f {Motion:[0.0d,-1.5d,0.0d],Tags:["%s"],pickup:0b}', sx + 0.5, sy + 4, sz + 0.5, TAG))
	wait(2)
	lookAt(sx + 0.5, sy + 1.2, sz + 0.5)
	local ea = worldEnts()
	check("arrow in WorldEntities", count(ea, 1) >= 1, "arrows " .. count(ea, 1))
	local arrowAt
	for _, e in ipairs(ea) do if e.kind == 1 then arrowAt = C.FromMc(e.x, e.y, e.z) end end
	if arrowAt then
		local d5 = onOff("arrow", { arrowAt, arrowAt + Vector(0, 0, 4), arrowAt + Vector(0, 0, 8) })
		check("arrow drawn (pixel on vs off)", d5 >= 12, "d " .. d5)
	end

	-- avatar: F5
	lookAt(bx + 0.5 + fx * 4, by + 0.5, bz + 0.5 + fz * 4)
	gmodcraft.input.Tap(SDL_F5)
	local got = waitUntil(function() return gmodcraft.EntitiesInfo().avatar.vertices > 0 end, 5)
	wait(1)
	local av = gmodcraft.EntitiesInfo().avatar
	check("F5 avatar arrives", got, string.format("%d v / %d batches", av.vertices, av.batches))
	local feet = gmodcraft.view.feet
	local d6 = onOff("avatar", { feet + Vector(0, 0, 40), feet + Vector(0, 0, 60), feet + Vector(0, 0, 20) })
	check("F5 avatar drawn (pixel on vs off)", d6 >= 12, "d " .. d6)
	shot("p3c_avatar_f5")
	gmodcraft.input.Tap(SDL_F5)
	wait(0.5)
	gmodcraft.input.Tap(SDL_F5)
	local back = waitUntil(function() return gmodcraft.EntitiesInfo().avatar.vertices == 0 end, 5)
	check("first person again: avatar gone", back)

	-- light
	local li = gmodcraft.EntitiesInfo()
	log("LIGHT %d cells, %d pending, %d sets, S at eye %.2f, %d Lua samples", li.lightCells, li.lightPending, li.lightSets, li.lightDefault,
		gmodcraft.entities.stats.lightSamples)
	check("GMod light sampled for entity cells", li.lightSets > 0, li.lightSets .. " sets")

	-- cost: a busy scene on top of the real one
	local vInj = gmodcraft.EntitiesDevInject(48, 1000, M.x, M.y, M.z)
	wait(1)
	local frames, per = 0, {}
	local t0 = SysTime()
	while SysTime() - t0 < 4 do
		coroutine.yield()
		local e = gmodcraft.EntitiesInfo()
		per[#per + 1] = e.prepareMs.last + e.drawOpaqueMs.last + e.drawTranslucentMs.last
		frames = frames + 1
	end
	table.sort(per)
	local e = gmodcraft.EntitiesInfo()
	local med, p95, worst = per[math.max(1, math.floor(#per * 0.5))] or -1, per[math.max(1, math.floor(#per * 0.95))] or -1, per[#per] or -1
	log("COST busy scene: %d injected + real -> %d baked vertices, %d groups, %d + %d draw calls; per frame median %.3f ms, p95 %.3f, max %.3f (%d frames); prepare %.3f/%.3f (bake %.3f/%.3f), draw opaque %.3f/%.3f, translucent %.3f/%.3f (avg/max)",
		vInj or -1, e.bakedVertices, e.groups, e.drawCallsOpaque, e.drawCallsTranslucent, med, p95, worst, frames, e.prepareMs.avg, e.prepareMs.max, e.bakeMs.avg,
		e.bakeMs.max, e.drawOpaqueMs.avg, e.drawOpaqueMs.max, e.drawTranslucentMs.avg, e.drawTranslucentMs.max)
	check("busy scene under 2 ms per frame (median)", med >= 0 and med < 2, string.format("median %.3f ms", med))
	gmodcraft.EntitiesDevInject(0, 0)
	local rr = gmodcraft.Stats().linkStats.mc.rings.render
	check("no render ring stall", rr.drops == 0, string.format("drops %d, high water %.1f MiB", rr.drops, rr.highWater / 1048576))
	log("ENTITIES %s", util.TableToJSON(gmodcraft.EntitiesInfo()))

	holdLook = nil
	cleanupWorld()
	wait(1)
	local nf = 0
	for _, r in ipairs(results) do if not r[2] then nf = nf + 1 end end
	log("P3CTEST DONE: %d checks, %d failed", #results, nf)
end

local co
function T.Start()
	if co then return end
	results = {}
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then
			log("ERROR %s", tostring(err))
			pcall(cleanupWorld)
			log("P3CTEST DONE (error)")
		end
		holdLook = nil
		gmodcraft.entities.wantEntities = nil
		gmodcraft.test.RestoreConVars("end of run")
	end)
	hook.Add("Think", "gmodcraft_test_p3c", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_p3c")
			co = nil
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_p3c", T.Start)
hook.Add("InitPostEntity", "gmodcraft_test_p3c", function()
	if file.Exists("gmodcraft/p3ctest.txt", "DATA") then
		log("p3ctest.txt present: starting in 5 s")
		timer.Simple(5, T.Start)
	end
end)
