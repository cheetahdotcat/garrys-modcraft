-- Scripted P5b run (client): holes Minecraft digs into GMod's map become visible (the stencil pass
-- in client/blocks.lua). Dev only (-gmodcraft_dev). Starts when data/gmodcraft/p5btest.txt exists
-- at InitPostEntity, or with gmodcraft_test_p5b.
--
--   probe     stencil on ToGL: an injected box (HolesDevInject, the DigStore and Minecraft's
--             collision are left alone) around the floor in view: its floor pixels must turn the
--             clear colour, a floating box's pixels (nothing inside it) and everything else must not
--             change (depth test on, no holes in unrelated surfaces). A failed probe switches the
--             rest of the run to the fallback cap (gmodcraft_holes 2)
--   holes     the Minecraft player digs a 2 x 2 x 3 hole into gm_construct's grass (displacement)
--             and one into concrete with an iron pickaxe (given into a free slot, found with the
--             hotbar keys; tagged custom_data gmc_p5b and cleared at the end); block drops off for
--             the run (gamerule block_drops, back to true at the end). Pixels into each hole with
--             gmodcraft_holes 1 vs 0: the hole changes, nothing outside its screen box does
--   entities  a clientside barrel in the grass hole stays visible, and a halo around it draws
--   cost      frames with the hole in view, gmodcraft_holes 0 vs 1, and the pass's CPU time
--   cleanup   the tool cleared, the gamerule back to what it was, the player back at its start, each
--             one checked afterwards (PASS/FAIL "cleanup: ... (verified)"); dug cells can't be undone
--             (no undig in SkyDig): every dug cell in the two hole areas is logged
-- Minecraft commands (P6b): no typing into Minecraft's chat. They go through the GMod server's dev
-- relay as console commands (gmodcraft.test.McCommand, kHostEvDevCommand; the Minecraft server needs
-- GMODCRAFT_DEV_COMMANDS=1), and conditions are read from the command's result ("execute if ...":
-- count > 0), so nothing is placed in the world to signal them. No liquids. At most 2 jpeg
-- screenshots. Logs "[gmodcraft-test]" PASS/FAIL lines.

local T = {}
gmodcraft.testP5b = T
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local TOOL = "minecraft:iron_pickaxe[minecraft:custom_data={gmc_p5b:1b}]"
local HOLE_RGB = { 24, 19, 14 }  -- blocks.lua's clear colour

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

-- ---- pixels (project in HUDPaint, read in PostRender) -------------------------------------------------
local pending
local function project(req)
	for _, p in ipairs(req.points) do
		if p.pos then
			local s = p.pos:ToScreen()
			p.x, p.y, p.vis = math.floor(s.x), math.floor(s.y), s.visible
		end
	end
	req.projected = true
end
hook.Add("HUDPaint", "gmodcraft_test_p5b", function()
	if pending and not pending.projected then project(pending) end
end)
hook.Add("PostRender", "gmodcraft_test_p5b", function()
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
	if holdLook then gmodcraft.input.look.yaw, gmodcraft.input.look.pitch = holdLook[1], holdLook[2] end
	wait(settle or 0.3)
	for _, p in ipairs(points) do p.r = nil end
	local req = { points = points }
	pending = req
	if not waitUntil(function() return req.done end, 3) then pending = nil log("capture timed out") end
	return points
end
local function copyPoints(src)
	local out = {}
	for i, p in ipairs(src) do out[i] = { pos = p.pos, x = (not p.pos) and p.x or nil, y = (not p.pos) and p.y or nil, tag = p.tag } end
	return out
end
local function diff(a, b)
	if not a.r or not b.r then return -1 end
	return math.max(math.abs(a.r - b.r), math.abs(a.g - b.g), math.abs(a.b - b.b))
end
local function px(p) return p.r and string.format("(%d,%d,%d)@%d,%d", p.r, p.g, p.b, p.x, p.y) or "none" end
local fill  -- the clear colour as the probe measured it (else HOLE_RGB)
local function isClear(p)
	local f = fill or HOLE_RGB
	return p.r and math.abs(p.r - f[1]) <= 10 and math.abs(p.g - f[2]) <= 10 and math.abs(p.b - f[3]) <= 10
end
local function grid(step)
	local g = {}
	for x = 8, ScrW() - 8, step do
		for y = 8, ScrH() - 8, step do g[#g + 1] = { x = x, y = y } end
	end
	return g
end
-- the screen box of some Source points (projected in HUDPaint) grown by `margin` pixels
local function screenBox(pts, margin)
	local x0, y0, x1, y1 = math.huge, math.huge, -math.huge, -math.huge
	for _, p in ipairs(pts) do
		if p.x then x0, y0, x1, y1 = math.min(x0, p.x), math.min(y0, p.y), math.max(x1, p.x), math.max(y1, p.y) end
	end
	return { x0 - margin, y0 - margin, x1 + margin, y1 + margin }
end
local function inBox(b, p) return p.x >= b[1] and p.x <= b[3] and p.y >= b[2] and p.y <= b[4] end
-- the 8 corners of an MC box (half-open cells), grown like the mask volume
local function boxCorners(x0, y0, z0, x1, y1, z1)
	local out = {}
	for _, x in ipairs({ x0, x1 }) do
		for _, y in ipairs({ y0, y1 }) do
			for _, z in ipairs({ z0, z1 }) do out[#out + 1] = { pos = C.FromMc(x, y, z) } end
		end
	end
	return out
end

-- holes 1 vs 0 over `pts` (with pos or x/y) plus a screen grid: returns on, off, grid on, grid off
local TU
local function onOff(pts, gstep)
	local g = grid(gstep or 40)
	local all = copyPoints(pts)
	for _, p in ipairs(g) do all[#all + 1] = { x = p.x, y = p.y, tag = "grid" } end
	local mode = TU.holesMode
	TU.SetConVarTemp("gmodcraft_holes", tostring(mode))
	local on = capture(copyPoints(all), 0.8)
	TU.SetConVarTemp("gmodcraft_holes", "0")
	local off = capture(copyPoints(all), 0.8)
	TU.SetConVarTemp("gmodcraft_holes", tostring(mode))
	return on, off
end

-- ---- Minecraft input ------------------------------------------------------------------------------------
local SDL_1 = 30
-- A console command on the Minecraft server (the GMod server's dev relay); true when it succeeded.
-- Console commands have no @s: the player is named (its Minecraft name).
local function mcCommand(text)
	local r = TU.McCommand(text)
	return r ~= nil and r.ok, r
end
local function me() return TU.McName() or "@p" end
local function press(button) gmodcraft.PushInput(K.InMouseButton, button, 1) end
local function release(button) gmodcraft.PushInput(K.InMouseButton, button, 0) end
local function setLook(pos)
	local eye = gmodcraft.view.lastView.origin
	local ang = (pos - eye):Angle()
	local look = gmodcraft.input.look
	look.yaw, look.pitch = C.YawToMc(ang.y), math.Clamp(math.NormalizeAngle(ang.p), -89, 89)
	holdLook = { look.yaw, look.pitch }
end
local function lookAt(pos, settle)
	setLook(pos)
	local u = RealTime() + (settle or 0.8)
	while RealTime() < u do
		setLook(pos)  -- the eye moves while the player settles
		coroutine.yield()
	end
end

-- A condition Minecraft checks for us: "execute <cond>" passes (count > 0) or not.
local function mcSignal(cond)
	return (TU.McTest(cond))
end
local function airCond(cells)
	local parts = {}
	for _, c in ipairs(cells) do parts[#parts + 1] = string.format("if block %d %d %d #minecraft:air", c[1], c[2], c[3]) end
	return table.concat(parts, " ")
end

-- ---- sites -----------------------------------------------------------------------------------------
local function surfaceAt(mx, mz, zRef)
	local p = C.FromMc(mx, 0, mz)
	local tr = util.TraceLine({ start = Vector(p.x, p.y, zRef + 160), endpos = Vector(p.x, p.y, zRef - 240), mask = MASK_SOLID_BRUSHONLY })
	if not tr.Hit or tr.StartSolid then return nil end
	return tr
end
-- A 4 x 4 block area (hole 2 x 2 at hx, hz; the stand column west of it) of one surface kind,
-- flat within 0.4 blocks, 3 blocks of headroom, no dug cells yet.
local function findSite(kind, origin, minDist, avoid)
	for r = minDist, 3000, 160 do
		for a = 0, 345, 15 do
			local p = origin + Vector(math.cos(math.rad(a)) * r, math.sin(math.rad(a)) * r, 0)
			local tr = util.TraceLine({ start = Vector(p.x, p.y, origin.z + 200), endpos = Vector(p.x, p.y, origin.z - 800), mask = MASK_SOLID_BRUSHONLY })
			local disp = tr.HitTexture == "**displacement**"
			local okKind = tr.Hit and not tr.StartSolid and tr.HitNormal.z > 0.97 and
				((kind == "grass" and disp and (tr.MatType == MAT_GRASS or tr.MatType == MAT_DIRT)) or (kind == "concrete" and not disp and tr.MatType == MAT_CONCRETE))
			if okKind and not (avoid and tr.HitPos:Distance2D(avoid) < 400) then
				local mx, _, mz = C.ToMc(tr.HitPos)
				local hx, hz = math.floor(mx), math.floor(mz)
				local ys = math.floor(C.ZToMcY(tr.HitPos.z) - 0.01)
				local zs, ok = {}, true
				for dx = -2, 2 do
					for dz = -1, 2 do
						local t = surfaceAt(hx + dx + 0.5, hz + dz + 0.5, tr.HitPos.z)
						if not t or t.MatType ~= tr.MatType or (t.HitTexture == "**displacement**") ~= disp or math.abs(t.HitPos.z - tr.HitPos.z) > 16 then ok = false break end
						local up = util.TraceLine({ start = t.HitPos + Vector(0, 0, 2), endpos = t.HitPos + Vector(0, 0, 130), mask = MASK_SOLID })
						if up.Hit then ok = false break end
						if dx >= 0 and dx <= 1 and dz >= 0 and dz <= 1 then
							-- every hole column's surface in the same cell row, in its lower 60% (the hole stays visible from the rim)
							if math.floor(C.ZToMcY(t.HitPos.z) - 0.01) ~= ys or C.ZToMcY(t.HitPos.z) - ys > 0.6 then ok = false break end
							zs[#zs + 1] = { hx + dx, hz + dz, t.HitPos.z }
						end
					end
					if not ok then break end
				end
				if ok then
					local cells = gmodcraft.HolesCells(C.WorldId(), hx - 2, ys - 4, hz - 2, hx + 3, ys + 2, hz + 3)
					if #cells == 0 then
						return { kind = kind, hx = hx, hz = hz, ys = ys, z = tr.HitPos.z, cols = zs, tex = tr.HitTexture, src = tr.HitPos }
					end
				end
			end
		end
	end
end

-- measure: average FPS and frame-time median / p95 over `seconds`
local function measure(seconds)
	local t0, frames, ft = SysTime(), 0, {}
	while SysTime() - t0 < seconds do
		if holdLook then gmodcraft.input.look.yaw, gmodcraft.input.look.pitch = holdLook[1], holdLook[2] end
		coroutine.yield()
		frames = frames + 1
		ft[#ft + 1] = RealFrameTime() * 1000
	end
	local el = SysTime() - t0
	table.sort(ft)
	return frames / el, ft[math.max(1, math.floor(#ft * 0.5))] or -1, ft[math.max(1, math.floor(#ft * 0.95))] or -1
end

local function dugCount() return gmodcraft.HolesInfo().cells end
local function sectionUpdates() return gmodcraft.BlocksInfo().sectionUpdates end

-- Holds the attack while looking at `aim` until Minecraft reports a change (a dug cell or a section
-- sent again), at most `timeout` seconds.
local function mine(aim, timeout)
	lookAt(aim, 0.5)
	local d0, s0 = dugCount(), sectionUpdates()
	press(1)
	local ok = waitUntil(function()
		setLook(aim)
		return dugCount() ~= d0 or sectionUpdates() ~= s0
	end, timeout or 5)
	release(1)
	wait(0.35)
	return ok
end

-- ---- one hole -----------------------------------------------------------------------------------------
local toolSlot
local function digHole(site)
	local hx, hz, ys = site.hx, site.hz, site.ys
	-- stand west of the hole (the player's box spans x hx-0.7 .. hx-0.1, column hx-1); 2.5 blocks up so
	-- the collision regions can arrive before landing (no fall damage); again if it fell through
	local M = gmodcraft.clientLink.M
	for _ = 1, 2 do
		mcCommand(string.format("tp %s %.2f %.2f %.2f", me(), hx - 0.4, C.ZToMcY(site.z) + 2.5, hz + 1))
		wait(4)
		if M.y >= C.ZToMcY(site.z) - 0.5 then break end
		log("%s: the player is at y %.2f after the teleport (surface %.2f): again", site.kind, M.y, C.ZToMcY(site.z))
	end
	log("%s: player at %.3f %.3f %.3f", site.kind, M.x, M.y, M.z)
	-- what the eye can see inside the hole: rays crossing the west rim (x = hx) above the surface
	local rim = C.ZToMcY(site.z)
	for _, c in ipairs(site.cols) do rim = math.max(rim, C.ZToMcY(c[3])) end
	local st = surfaceAt(hx - 0.5, hz + 1, site.z)
	if st then rim = math.max(rim, C.ZToMcY(st.HitPos.z)) end
	function site.Visible(x, y, z)
		local ex, ey = C.ToMc(gmodcraft.view.lastView.origin)
		if ex >= hx or x <= hx then return ex >= hx end
		return ey + (y - ey) * (hx - ex) / (x - ex) > rim + 0.05
	end
	if not toolSlot then
		mcCommand("give " .. me() .. " " .. TOOL)
		for n = 1, 9 do
			gmodcraft.input.Tap(SDL_1 + n - 1)
			wait(0.3)
			if mcSignal("if items entity " .. me() .. " weapon.mainhand " .. TOOL) then toolSlot = n break end
		end
		check("pickaxe in a free hotbar slot", toolSlot ~= nil, toolSlot and ("slot " .. toolSlot) or "not in the hotbar (no free slot?)")
		if not toolSlot then return false end
	else
		gmodcraft.input.Tap(SDL_1 + toolSlot - 1)
		wait(0.3)
	end
	-- the hole's cells and the ones above must be Minecraft air (nothing of the user's there)
	local free = true
	for dy = 0, 2 do
		local cells = {}
		for _, c in ipairs(site.cols) do cells[#cells + 1] = { c[1], ys + dy, c[2] } end
		free = free and mcSignal(airCond(cells))
	end
	if not free then
		check(site.kind .. ": hole and headroom are Minecraft air", false)
		return false
	end
	wait(0.5)
	local layers = {}
	for layer = 0, 2 do
		local cells = {}
		for _, c in ipairs(site.cols) do
			local y = ys - layer
			cells[#cells + 1] = { c[1], y, c[2] }
			local aim = layer == 0 and C.FromMc(c[1] + 0.5, C.ZToMcY(c[3]), c[2] + 0.5) or C.FromMc(c[1] + 0.5, y + 1, c[2] + 0.5)
			if not mine(aim, 6) then
				log("%s layer %d cell %d %d %d: no change within 6 s", site.kind, layer, c[1], y, c[2])
			end
		end
		wait(0.5)
		local open = mcSignal(airCond(cells))
		if not open then  -- once more for each cell (a section update may have ended a hold early)
			for _, c in ipairs(cells) do mine(layer == 0 and C.FromMc(c[1] + 0.5, C.ZToMcY(site.z), c[3] + 0.5) or C.FromMc(c[1] + 0.5, c[2] + 1, c[3] + 0.5), 6) end
			open = mcSignal(airCond(cells))
		end
		layers[#layers + 1] = open
		log("%s layer %d (y %d) open: %s", site.kind, layer, ys - layer, tostring(open))
	end
	local dug = gmodcraft.HolesCells(C.WorldId(), hx, ys - 2, hz, hx + 1, ys, hz + 1)
	check(site.kind .. ": 2 x 2 x 3 hole dug (12 cells dug, all Minecraft air)", #dug == 36 and layers[1] and layers[2] and layers[3],
		string.format("%d dug cells in the hole box, layers open %s/%s/%s", #dug / 3, tostring(layers[1]), tostring(layers[2]), tostring(layers[3])))
	return true
end

-- Pixels into the hole (holes on vs off) from two views; returns the steep view's points for reuse.
local function checkHole(site, label)
	local hx, hz, ys = site.hx, site.hz, site.ys
	local floorC = C.FromMc(hx + 1, ys - 2 + 0.02, hz + 1)
	local pts, dropped = {}, 0
	local function add(x, y, z, tag)
		if site.Visible(x, y, z) then pts[#pts + 1] = { pos = C.FromMc(x, y, z), tag = tag } else dropped = dropped + 1 end
	end
	for _, ox in ipairs({ 0.75, 1, 1.25, 1.5, 1.75 }) do
		for _, oz in ipairs({ 0.6, 1, 1.4 }) do add(hx + ox, ys - 2 + 0.02, hz + oz, "floor") end
	end
	for _, y in ipairs({ ys - 1.75, ys - 1.25, ys - 0.75, ys - 0.25 }) do  -- the far (east) wall
		add(hx + 2 - 0.02, y, hz + 0.6, "wall")
		add(hx + 2 - 0.02, y, hz + 1.4, "wall")
	end
	log("HOLE %s: %d points inside the hole visible from the eye (%d hidden by the near rim dropped)", label, #pts, dropped)
	local corners = boxCorners(hx, ys - 2, hz, hx + 2, ys + 1, hz + 2)
	for _, c in ipairs(corners) do c.tag = "corner" pts[#pts + 1] = c end
	local function judge(view)
		local on, off = onOff(pts, 32)
		local cs = {}
		for i, p in ipairs(on) do if p.tag == "corner" then cs[#cs + 1] = p end end
		local box = screenBox(cs, 10)
		local inside, changedIn, clear, outside, changedOut, worst, parts = 0, 0, 0, 0, 0, nil, {}
		for i, p in ipairs(on) do
			if p.tag == "floor" or p.tag == "wall" then
				inside = inside + 1
				if diff(p, off[i]) > 20 then changedIn = changedIn + 1 end
				if isClear(p) then clear = clear + 1 end
				parts[#parts + 1] = string.format("%s %s -> %s", p.tag, px(off[i]), px(p))
			elseif p.tag == "grid" and p.r and off[i].r and not inBox(box, p) then
				outside = outside + 1
				local d = diff(p, off[i])
				if d > 12 then
					changedOut = changedOut + 1
					if not worst or d > worst.d then worst = { d = d, p = p, o = off[i] } end
				end
			end
		end
		log("HOLE %s %s: %s", label, view, table.concat(parts, "; "))
		log("HOLE %s %s: screen box %d,%d..%d,%d; %d of %d hole points changed, %d show the clear colour; %d of %d grid points outside changed%s", label, view,
			box[1], box[2], box[3], box[4], changedIn, inside, clear, changedOut, outside,
			worst and string.format(" (worst d %d: %s off %s)", worst.d, px(worst.p), px(worst.o)) or "")
		return changedIn, inside, clear, changedOut, outside
	end
	-- steep view from the stand spot into the hole
	lookAt(floorC, 1)
	local ci, n, cl, co, no = judge("steep")
	check(site.kind .. ": looking into the hole shows its walls, not the map surface (holes 1 vs 0)", n >= 3 and ci >= math.ceil(n * 0.6) and cl <= n * 0.3,
		string.format("%d/%d hole points changed, %d clear-colour", ci, n, cl))
	check(site.kind .. ": no change outside the hole (steep view)", no > 20 and co <= math.max(2, no * 0.01), string.format("%d of %d changed", co, no))
	shot(label .. "_steep")
	-- shallow view: the hole low on screen, the horizon and sky above
	local far = C.FromMc(hx + 7, ys - 1.2, hz + 1)  -- ~20 degrees down: the hole's far half low in frame
	lookAt(far, 1)
	ci, n, cl, co, no = judge("shallow")
	check(site.kind .. ": no change outside the hole (shallow view, horizon and sky)", no > 20 and co <= math.max(2, no * 0.01), string.format("%d of %d changed", co, no))
	return floorC
end

-- ---- run --------------------------------------------------------------------------------------------
local tidy = {}
local function cleanup()
	holdLook = nil
	release(1)
	if tidy.model and IsValid(tidy.model) then tidy.model:Remove() end
	hook.Remove("PreDrawHalos", "gmodcraft_test_p5b")
	if gmodcraft.HolesDevInject then gmodcraft.HolesDevInject() end
	-- Every restore is checked afterwards (P6b: no restore is trusted blind).
	local t = tidy
	tidy = {}
	if t.tool then
		local ok, why = TU.McEdit("clear " .. me() .. " " .. TOOL, "unless items entity " .. me() .. " container.* " .. TOOL)
		check("cleanup: the test pickaxe is gone (verified)", ok, why)
	end
	if t.gamerule ~= nil then
		mcCommand("gamerule minecraft:block_drops " .. t.gamerule)
		local _, q = mcCommand("gamerule minecraft:block_drops")
		check("cleanup: gamerule block_drops back to " .. t.gamerule .. " (verified)", q ~= nil and q.ok and q.count == (t.gamerule == "true" and 1 or 0),
			q and q.output or "no answer")
	end
	if t.start then
		mcCommand(string.format("tp %s %.3f %.3f %.3f", me(), t.start[1], t.start[2], t.start[3]))
		local M = gmodcraft.clientLink.M
		local back = waitUntil(function() return math.abs(M.x - t.start[1]) < 0.5 and math.abs(M.y - t.start[2]) < 1 and math.abs(M.z - t.start[3]) < 0.5 end, 5)
		check("cleanup: the player is back at its start (verified)", back, string.format("at %.2f %.2f %.2f, start %.2f %.2f %.2f", M.x, M.y, M.z,
			t.start[1], t.start[2], t.start[3]))
	end
end

local function run()
	local ply = LocalPlayer()
	TU = gmodcraft.test
	TU.holesMode = 1
	TU.SetConVarTemp("jpeg_quality", "70")
	TU.SetConVarTemp("cl_showhints", "0")
	TU.SetConVarTemp("gmodcraft_blocks", "1")
	TU.SetConVarTemp("gmodcraft_holes", "1")
	log("P5b run on %s", game.GetMap())
	local up = waitUntil(function() return gmodcraft.IsPuppet(ply) and gmodcraft.view.valid end, 300)
	check("puppet active", up)
	if not up then log("P5BTEST DONE (aborted)") return end
	waitUntil(function() return gmodcraft.BlocksAtlas() ~= nil end, 30)
	wait(4)
	TU.SetConVarTemp("gmodcraft_overlay", "0")  -- the hand would cover pixels
	local function cv(n) local c = GetConVar(n) return c and c:GetString() or "?" end
	local H0 = gmodcraft.HolesInfo()
	log("ENV mat_queue_mode %s, fps_max %s, %dx%d; holes at start: %d cells, %d boxes, world %08x; meshOk %s", cv("mat_queue_mode"), cv("fps_max"), ScrW(), ScrH(),
		H0.cells, H0.boxes, C.WorldId(), tostring(gmodcraft.BlocksMeshOk()))
	local M = gmodcraft.clientLink.M
	tidy.start = { M.x, M.y, M.z }
	log("MC player starts at %.3f %.3f %.3f", M.x, M.y, M.z)
	local eye0 = gmodcraft.view.lastView.origin

	-- 1. the stencil probe: injected boxes around the floor in view
	do
		local fwd = Angle(35, ply:EyeAngles().y, 0):Forward()
		local tr = util.TraceLine({ start = eye0, endpos = eye0 + fwd * 700, mask = MASK_SOLID_BRUSHONLY })
		if not tr.Hit or tr.HitNormal.z < 0.7 then
			for a = 0, 330, 30 do
				fwd = Angle(35, a, 0):Forward()
				tr = util.TraceLine({ start = eye0, endpos = eye0 + fwd * 700, mask = MASK_SOLID_BRUSHONLY })
				if tr.Hit and tr.HitNormal.z >= 0.7 and tr.HitPos:Distance(eye0) > 120 then break end
			end
		end
		if not tr.Hit or tr.HitNormal.z < 0.7 then
			check("probe: a floor in view", false)
		else
			local mx, my, mz = C.ToMc(tr.HitPos)
			local cx, cy, cz = math.floor(mx), math.floor(my - 0.01), math.floor(mz)
			gmodcraft.HolesDevInject(cx - 1, cy - 1, cz - 1, cx + 1, cy + 1, cz + 1)
			-- a floating box beside it, 2 blocks over the floor (nothing of the world inside)
			local side = Angle(0, fwd:Angle().y + 90, 0):Forward()
			local sx, _, sz = C.ToMc(tr.HitPos + side * 140)
			sx, sz = math.floor(sx), math.floor(sz)
			gmodcraft.HolesDevInject(sx - 1, cy + 2, sz - 1, sx + 1, cy + 4, sz + 1)
			lookAt(tr.HitPos + side * 70, 1)
			local pts = {}
			for _, o in ipairs({ { 0, 0 }, { 0.4, 0 }, { -0.4, 0 }, { 0, 0.4 }, { 0, -0.4 } }) do  -- around the box's centre (cx, cz)
				pts[#pts + 1] = { pos = C.FromMc(cx + o[1], my, cz + o[2]), tag = "in" }
			end
			pts[#pts + 1] = { pos = C.FromMc(sx, cy + 3, sz), tag = "float" }
			pts[#pts + 1] = { pos = C.FromMc(sx + 0.5, cy + 3.5, sz - 0.5), tag = "float" }
			for _, c in ipairs(boxCorners(cx - 1, cy - 1, cz - 1, cx + 1, cy + 1, cz + 1)) do c.tag = "corner" pts[#pts + 1] = c end
			-- variant A (OverrideDepthEnable), then B (a $translucent mask material), then the cap
			local okStencil
			for _, variant in ipairs({ false, "mat" }) do
				gmodcraft.blocks.holeVariant = variant or nil
				local on, off = onOff(pts, 40)
				local cs = {}
				for _, p in ipairs(on) do if p.tag == "corner" then cs[#cs + 1] = p end end
				local box = screenBox(cs, 10)
				local nIn, changedIn, nFloat, sameFloat, nOut, changedOut, parts, fills = 0, 0, 0, 0, 0, 0, {}, {}
				for i, p in ipairs(on) do
					if p.tag == "in" then
						nIn = nIn + 1
						if diff(p, off[i]) > 20 then
							changedIn = changedIn + 1
							fills[#fills + 1] = p
						end
						parts[#parts + 1] = string.format("in %s -> %s", px(off[i]), px(p))
					elseif p.tag == "float" then
						-- only points on screen count (the first live run had both off screen: "none -> none")
						if p.r and off[i].r then nFloat = nFloat + 1 end
						if diff(p, off[i]) >= 0 and diff(p, off[i]) <= 8 then sameFloat = sameFloat + 1 end
						parts[#parts + 1] = string.format("float %s -> %s", px(off[i]), px(p))
					elseif p.tag == "grid" and p.r and off[i].r and not inBox(box, p) then
						nOut = nOut + 1
						if diff(p, off[i]) > 12 then changedOut = changedOut + 1 end
					end
				end
				-- the changed pixels all show one fill colour (the clear; its RGB may arrive gamma-converted)
				local uniform = #fills > 0
				for _, p in ipairs(fills) do uniform = uniform and diff(p, fills[1]) <= 10 end
				if uniform then fill = { fills[1].r, fills[1].g, fills[1].b } end
				local name = variant and "variant mat ($translucent mask)" or "variant override (OverrideDepthEnable)"
				log("PROBE %s: floor %s cell %d %d %d, float box at %d %d %d: %s; fill %s; %d of %d grid points outside changed; pass %.3f ms (CPU), %d passes",
					name, tostring(tr.HitPos), cx, cy, cz, sx, cy + 2, sz, table.concat(parts, "; "), fill and table.concat(fill, ",") or "none", changedOut, nOut,
					gmodcraft.blocks.holeStats.lastMs, gmodcraft.blocks.holeStats.passes)
				okStencil = nIn > 0 and changedIn >= nIn - 1 and uniform
				check("probe (" .. name .. "): stencil masks the floor inside the box (one fill colour there)", okStencil, string.format("%d of %d changed, uniform %s", changedIn, nIn, tostring(uniform)))
				if nFloat > 0 then
					check("probe (" .. name .. "): depth test on (a box with nothing inside changes nothing)", sameFloat == nFloat,
						string.format("%d of %d unchanged", sameFloat, nFloat))
				else
					log("NOTE probe (%s): the floating box is off screen; the depth test is judged by the outside grid alone", name)
				end
				check("probe (" .. name .. "): nothing outside the box changes", nOut > 20 and changedOut <= math.max(2, nOut * 0.01), string.format("%d of %d", changedOut, nOut))
				okStencil = okStencil and sameFloat == nFloat and nOut > 20 and changedOut <= math.max(2, nOut * 0.01)
				if okStencil then break end
			end
			if not okStencil then
				gmodcraft.blocks.holeVariant = nil
				TU.holesMode = 2
				TU.SetConVarTemp("gmodcraft_holes", "2")
				log("PROBE failed: the rest of the run uses the fallback cap (gmodcraft_holes 2)")
			end
		end
		gmodcraft.HolesDevInject()
	end

	-- 2. holes
	local _, before = mcCommand("gamerule minecraft:block_drops")  -- the value before: restored at the end
	if not before or not before.ok then
		check("Minecraft commands through the GMod server (dev relay, GMODCRAFT_DEV_COMMANDS=1)", false, before and before.output or "no answer")
		cleanup()
		log("P5BTEST DONE (aborted)")
		return
	end
	tidy.gamerule = before.count == 1 and "true" or "false"
	local off = mcCommand("gamerule minecraft:block_drops false")
	check("block drops off for the run (was " .. tidy.gamerule .. ")", off)
	tidy.tool = true
	local origin = gmodcraft.view.lastView.origin
	local grass = findSite("grass", origin, 300)
	local concrete = findSite("concrete", origin, 200, grass and grass.src)
	log("SITES grass %s, concrete %s", grass and string.format("hole %d %d %d (Source %s, %s)", grass.hx, grass.ys, grass.hz, tostring(grass.src), grass.tex) or "none",
		concrete and string.format("hole %d %d %d (Source %s, %s)", concrete.hx, concrete.ys, concrete.hz, tostring(concrete.src), concrete.tex) or "none")
	check("a grass displacement site and a concrete site", grass ~= nil and concrete ~= nil)
	local dugSites = {}
	for _, site in ipairs({ grass, concrete }) do
		if site and digHole(site) then
			dugSites[#dugSites + 1] = site
			wait(1)
			local floorC = checkHole(site, "p5b_" .. site.kind)
			if site.kind == "grass" then
				-- frame cost with the hole in view
				lookAt(floorC, 0.8)
				local rows, fps = {}, {}
				for _, mode in ipairs({ 0, TU.holesMode, 0, TU.holesMode }) do
					TU.SetConVarTemp("gmodcraft_holes", tostring(mode))
					wait(1)
					local f, med, p95 = measure(3)
					fps[#fps + 1] = { mode, f, med }
					rows[#rows + 1] = string.format("holes %d: FPS %.1f, median %.2f ms, p95 %.2f ms", mode, f, med, p95)
				end
				TU.SetConVarTemp("gmodcraft_holes", tostring(TU.holesMode))
				local Hi = gmodcraft.HolesInfo()
				log("COST %s; pass CPU %.3f ms; volume %d boxes, %d vertices, %d meshes, last build %.2f ms (max %.2f), %d rebuilds", table.concat(rows, "; "),
					gmodcraft.blocks.holeStats.lastMs, Hi.boxes, Hi.vertices, Hi.meshes, Hi.lastBuildMs, Hi.maxBuildMs, Hi.rebuilds)
				-- a barrel in the hole, then a halo around it
				local mdl = ClientsideModel("models/props_c17/oildrum001.mdl", RENDERGROUP_OPAQUE)
				if IsValid(mdl) then
					tidy.model = mdl
					mdl:SetPos(C.FromMc(site.hx + 1, site.ys - 2 + 0.05, site.hz + 1))
					mdl:SetNoDraw(true)
					local bc = {}  -- points on the barrel's axis (inside it) the eye sees past the rim
					for _, y in ipairs({ site.ys - 0.95, site.ys - 1.15, site.ys - 1.35, site.ys - 1.55, site.ys - 1.75 }) do
						if site.Visible(site.hx + 1, y, site.hz + 1) then bc[#bc + 1] = { pos = C.FromMc(site.hx + 1, y, site.hz + 1) } end
					end
					local without = capture(copyPoints(bc), 0.5)
					mdl:SetNoDraw(false)
					local with = capture(copyPoints(bc), 0.5)
					local dBarrel, parts = -1, {}
					for i = 1, #bc do
						dBarrel = math.max(dBarrel, diff(with[i], without[i]))
						parts[#parts + 1] = string.format("%s -> %s", px(without[i]), px(with[i]))
					end
					check("a prop inside the hole is drawn (not covered by the walls)", #bc > 0 and dBarrel > 20,
						string.format("%d visible axis points: %s", #bc, table.concat(parts, ", ")))
					-- halo: magenta pixels in a ring around the barrel's screen box
					local ring = {}
					local cs = {}
					local mins, maxs = mdl:OBBMins(), mdl:OBBMaxs()
					for _, x in ipairs({ mins.x, maxs.x }) do
						for _, y in ipairs({ mins.y, maxs.y }) do
							for _, z in ipairs({ mins.z, maxs.z }) do cs[#cs + 1] = { pos = mdl:LocalToWorld(Vector(x, y, z)) } end
						end
					end
					capture(cs, 0.2)
					local b = screenBox(cs, 0)
					for x = b[1] - 12, b[3] + 12, 2 do
						for y = b[2] - 12, b[4] + 12, 2 do ring[#ring + 1] = { x = x, y = y } end
					end
					local function magenta(list)
						local n = 0
						for _, p in ipairs(list) do if p.r and p.r > 150 and p.b > 150 and p.r - p.g > 60 then n = n + 1 end end
						return n
					end
					local before = magenta(capture(copyPoints(ring), 0.3))
					hook.Add("PreDrawHalos", "gmodcraft_test_p5b", function()
						if IsValid(tidy.model) then halo.Add({ tidy.model }, Color(255, 0, 255), 4, 4, 2, true, false) end
					end)
					local after = magenta(capture(copyPoints(ring), 0.5))
					hook.Remove("PreDrawHalos", "gmodcraft_test_p5b")
					check("a halo around the prop in the hole draws with the stencil pass on", after >= before + 8, string.format("magenta pixels %d -> %d", before, after))
					mdl:Remove()
					tidy.model = nil
				else
					check("a barrel model", false)
				end
			end
		end
	end

	-- 3. cleanup: the tool, the gamerule, back to the start; the dug cells stay (no undig)
	holdLook = nil
	for _, site in ipairs(dugSites) do
		local cells = gmodcraft.HolesCells(C.WorldId(), site.hx - 2, site.ys - 4, site.hz - 2, site.hx + 3, site.ys + 1, site.hz + 3)
		local list = {}
		for i = 1, #cells, 3 do list[#list + 1] = string.format("%d %d %d", cells[i], cells[i + 1], cells[i + 2]) end
		log("DUG %s (stays: no undig): %d cells: %s", site.kind, #list, table.concat(list, "; "))
	end
	cleanup()
	wait(2)
	local nf = 0
	for _, r in ipairs(results) do if not r[2] then nf = nf + 1 end end
	log("P5BTEST DONE: %d checks, %d failed", #results, nf)
end

local co
function T.Start()
	if co then return end
	results = {}
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then
			log("ERROR %s", tostring(err))
			pcall(cleanup)
			log("P5BTEST DONE (error)")
		end
		holdLook = nil
		gmodcraft.test.RestoreConVars("end of run")
	end)
	hook.Add("Think", "gmodcraft_test_p5b", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_p5b")
			co = nil
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_p5b", T.Start)
hook.Add("InitPostEntity", "gmodcraft_test_p5b", function()
	if file.Exists("gmodcraft/p5btest.txt", "DATA") then
		log("p5btest.txt present: starting in 5 s")
		timer.Simple(5, T.Start)
	end
end)
