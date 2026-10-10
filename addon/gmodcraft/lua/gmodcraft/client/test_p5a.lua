-- Scripted P5a run (client): block collision for GMod entities (server/blockcol.lua). Dev only
-- (-gmodcraft_dev). Starts when data/gmodcraft/p5atest.txt exists at InitPostEntity, or with
-- gmodcraft_test_p5a. Needs a world with allowCommands. The server half is server/test_p5a.lua.
--
--   build    MC /fill: a 3-high, 10-long stone wall and a 1-high platform on an empty flat spot the
--            server picks; the server builds the collision entities; this client builds them too
--   synth    a 512-box section (3D checkerboard) built on a throwaway entity: the build time
--   walk     the MC player walks along the wall touching it: no new sanity rejections, holds,
--            corrections or inside reports
--   zombie   an npc_zombie spawned on the platform stands on it, then chases the player behind the
--            wall: it never overlaps the wall (the HL2 NPC behaviour is logged: around or stuck)
--   prop     a melon dropped on the wall rests on it; digging the block under it (/fill air) makes
--            it fall into the notch (rebuild + wake)
--   bot      a GMod bot walking into the wall stops at it
--   restore  /fill air over the whole build; the server's store has no blocks left there
-- MC state touched: resistance effect (cleared at the end); no inventory slots.

local T = {}
gmodcraft.testP5a = T
local C = gmodcraft.convert

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

local K = gmodcraft.K or {}
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
-- A GMod-side move (teleport request -> MC ack -> grace), not an MC /tp: an MC jump of hundreds
-- of units would trip the per-update speed clamp and could force a hold.
local function moveTo(x, y, z)
	local v = C.FromMc(x, y, z)
	RunConsoleCommand("gmodcraft_test_setpos", string.format("%.2f", v.x), string.format("%.2f", v.y), string.format("%.2f", v.z))
	wait(0.3)
	waitUntil(function() return gmodcraft.IsPuppet(LocalPlayer()) end, 8)
end
local function key(code, on) gmodcraft.input.inject[code] = on or nil end

local S = {}
net.Receive("gmodcraft_test_p5a", function()
	local n = net.ReadUInt(16)
	local t = util.JSONToTable(util.Decompress(net.ReadData(n)) or "")
	if t then S = t end
end)

-- /fill a range and check the server's store shows the result (count = volume or 0); up to 3 tries
-- (live run 6: a stray character in the chat line turned one restore fill into an unknown command).
local function fillChecked(x0, y0, z0, x1, y1, z1, block)
	local want = block == "minecraft:air" and 0 or (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)
	local k = string.format("%d %d %d %d %d %d", x0, y0, z0, x1, y1, z1)
	RunConsoleCommand("gmodcraft_test_p5a_count", x0, y0, z0, x1, y1, z1)
	for try = 1, 3 do
		mcCommand(string.format("fill %s %s", k, block))
		if waitUntil(function() return S.countKey == k and S.count == want end, 3) then return true, try end
		log("fill %s %s: store shows %s cells (want %d), retrying", k, block, tostring(S.countKey == k and S.count or "?"), want)
		wait(0.5)
	end
	return false, 3
end

local function sanityText(pc) return string.format("rejected %d, corrections %d, holds %d, inside reports %d", pc.rej, pc.cor, pc.rec, pc.ins) end

local function run()
	local ply = LocalPlayer()
	log("P5a run on %s", game.GetMap())
	local up = waitUntil(function() return gmodcraft.IsPuppet(ply) and gmodcraft.view.valid end, 300)
	check("puppet active", up)
	if not up then log("P5ATEST DONE (aborted)") return end
	RunConsoleCommand("gmodcraft_test_p5a_watch", "1")
	wait(1)
	RunConsoleCommand("gmodcraft_test_p5a_site")
	local site = waitUntil(function() return S.site ~= nil end, 5)
	check("empty flat site found", site, S.site and string.format("blocks (%d, %d, %d), floor z %.1f", S.site.bx, S.site.by, S.site.bz, S.site.floorZ) or "none")
	if not site then log("P5ATEST DONE (aborted)") return end
	local s = S.site
	T.site = s
	log("nextbot classes: %s", #(S.nb or {}) > 0 and table.concat(S.nb, ", ") or "none")
	-- leftovers of an earlier run (p5atest.txt lines "clean x0 y0 z0 x1 y1 z1")
	for line in (file.Read("gmodcraft/p5atest.txt", "DATA") or ""):gmatch("[^\n]+") do
		local a = { line:match("^clean (%-?%d+) (%-?%d+) (%-?%d+) (%-?%d+) (%-?%d+) (%-?%d+)") }
		if #a == 6 then
			for i = 1, 6 do a[i] = tonumber(a[i]) end
			local ok, tries = fillChecked(a[1], a[2], a[3], a[4], a[5], a[6], "minecraft:air")
			check("pre-clean leftover " .. line:sub(7), ok, string.format("%d tries, store %s", tries, tostring(S.count)))
		end
	end
	mcCommand("effect give @s minecraft:resistance 300 4 true")

	-- build
	local ent0 = (S.bc or {}).entities or 0
	fillChecked(s.bx, s.by, s.bz + 4, s.bx + 9, s.by + 2, s.bz + 4, "minecraft:stone")
	fillChecked(s.bx + 3, s.by, s.bz + 8, s.bx + 6, s.by, s.bz + 10, "minecraft:stone")
	local built = waitUntil(function() return (S.siteBoxes or 0) >= 2 and (S.bc or {}).entities > ent0 and (S.bc.pending or 1) == 0 end, 10)
	local bs = (S.bc or {}).s or {}
	check("collision entities built for the wall + platform", built, string.format("site cells %s, entities %d -> %d (box ents %d, multi sections %d), last update %.2f ms (%d boxes changed)",
		tostring(S.siteBoxes), ent0, (S.bc or {}).entities or -1, (S.bc or {}).boxEnts or -1, (S.bc or {}).multi or -1, bs.lastMs or -1, bs.lastBoxes or -1))
	local clientBuilt = waitUntil(function()
		local n = 0
		for _, e in ipairs(ents.FindByClass("gmodcraft_blocks")) do
			if (e:GetSolid() == SOLID_VPHYSICS or e:GetSolid() == SOLID_BBOX) and e:GetPos():DistToSqr(Vector(s.wallX0, s.wallA, s.floorZ)) < 600 * 600 then n = n + 1 end
		end
		return n >= 2
	end, 5)
	check("this client sees the solid box entities (prediction)", clientBuilt)
	RunConsoleCommand("gmodcraft_test_p5a_trace")
	waitUntil(function() return S.trace ~= nil end, 3)
	check("hull traces hit the box entity at the wall face", S.trace ~= nil and S.trace.ok, S.trace and S.trace.txt or "no result")

	-- synthetic 512 boxes
	RunConsoleCommand("gmodcraft_test_p5a_synth")
	waitUntil(function() return S.synth ~= nil end, 5)
	local sy = S.synth or {}
	check("512 boxes as box entities: per-box cost fits the 2 ms/tick budget (spread over ticks)", (sy.perBox or 99) <= 1,
		string.format("%d boxes: %.2f ms total, %.3f ms/box (median %.3f, max %.3f), removal %.2f ms, ~%d ticks at 2 ms",
			sy.boxes or -1, sy.total or -1, sy.perBox or -1, sy.median or -1, sy.max or -1, sy.removeMs or -1, sy.ticks or -1))

	-- walk along the wall (side A, touching it), facing +x (MC yaw -90)
	local mcY = C.ZToMcY(s.floorZ)
	local look = gmodcraft.input.look
	local pcStart = S.pc
	log("sanity before any move: %s", sanityText(pcStart))
	look.yaw, look.pitch = 270, 0
	moveTo(s.bx + 0.5, mcY + 0.05, s.bz + 3.69)
	wait(2.5)
	local pc0 = S.pc
	key(KEY_W, true)
	wait(2.2)
	key(KEY_W, false)
	wait(2.5)
	local pc1 = S.pc
	local _, _, pz = C.ToMc(ply:GetPos())
	check("MC player walks along the wall: no new rejections / holds / corrections / inside reports",
		pc1.rej == pc0.rej and pc1.rec == pc0.rec and pc1.cor == pc0.cor and pc1.ins == pc0.ins,
		string.format("before %s; after %s; MC z %.2f (wall face at %d), accepted +%d", sanityText(pc0), sanityText(pc1), pz, s.bz + 4, pc1.acc - pc0.acc))

	-- zombie: stands on the platform, then chases the player behind the wall (middle of side A)
	look.yaw = 0
	moveTo(s.bx + 5, mcY + 0.05, s.bz + 2.5)
	wait(1.5)
	local pz0 = S.pc
	RunConsoleCommand("gmodcraft_test_p5a_spawn", "zombie")
	waitUntil(function() return S.z ~= nil end, 3)
	wait(1.2)
	local zs = S.z or {}
	check("npc_zombie stands on the 1-high platform", zs.z ~= nil and math.abs(zs.z - s.platTop) < 4,
		string.format("zombie z %.1f, platform top %.1f, on ground %s", zs.z or -1, s.platTop, tostring(zs.ground)))
	local zt0 = RealTime()
	waitUntil(function() return S.z and S.z.sideA end, 20)
	local z = S.z or {}
	check("npc_zombie chasing the player never overlaps the wall", z.ov == 0 and (z.gap or 0) >= 8,
		string.format("max overlap %.1f u, closest centre-to-wall %.1f u, reached the player's side %s after %.1f s, enemy %s, at (%.0f, %.0f, %.0f)",
			z.ov or -1, z.gap or -1, tostring(z.sideA), RealTime() - zt0, tostring(z.enemy), z.x or 0, z.y or 0, z.z or 0))
	RunConsoleCommand("gmodcraft_test_p5a_spawn", "removezombie")
	local pz1 = S.pc
	log("sanity during the zombie stage: before %s; after %s", sanityText(pz0), sanityText(pz1))

	-- prop: rests on the wall, falls when the block under it is dug
	RunConsoleCommand("gmodcraft_test_p5a_spawn", "prop")
	wait(3.5)
	local p0 = S.p or {}
	check("a melon dropped on the wall rests on top of it", p0.z ~= nil and p0.z > s.wallTop and p0.z < s.wallTop + 24 and (p0.v or 99) < 5,
		string.format("melon z %.1f (wall top %.1f), speed %.1f, asleep %s", p0.z or -1, s.wallTop, p0.v or -1, tostring(p0.asleep)))
	local rb0 = ((S.bc or {}).s or {}).rebuilds or 0
	mcCommand(string.format("fill %d %d %d %d %d %d minecraft:air", s.bx + 2, s.by + 2, s.bz + 4, s.bx + 2, s.by + 2, s.bz + 4))
	local fell = waitUntil(function() return S.p and p0.z and S.p.z < p0.z - 25 end, 4)
	wait(1)
	local p1, bs1 = S.p or {}, (S.bc or {}).s or {}
	check("digging the block under it: rebuild, the melon falls into the notch", fell and (bs1.rebuilds or 0) > rb0,
		string.format("melon z %.1f -> %.1f, rebuilds %d -> %d, last update %.2f ms (%d boxes changed, merge %.3f ms), props woken %d",
			p0.z or -1, p1.z or -1, rb0, bs1.rebuilds or -1, bs1.lastMs or -1, bs1.lastBoxes or -1, bs1.mergeMs or -1, bs1.woken or -1))

	-- bot walks into the wall
	RunConsoleCommand("gmodcraft_test_p5a_spawn", "bot")
	waitUntil(function() return S.b and S.b.done end, 8)
	local b = S.b or {}
	check("a GMod bot walking into the wall stops at it", b.done == true and b.ov == 0 and (b.gap or -1) >= 12,
		string.format("feet centre to wall face min %.1f u, overlap %.1f, at (%.0f, %.0f, %.0f)", b.gap or -1, b.ov or -1, b.x or 0, b.y or 0, b.z or 0))

	-- restore
	RunConsoleCommand("gmodcraft_test_p5a_cleanup")
	RunConsoleCommand("gmodcraft_test_p5a_watch", "1")
	fillChecked(s.bx, s.by, s.bz + 4, s.bx + 9, s.by + 2, s.bz + 4, "minecraft:air")
	fillChecked(s.bx + 3, s.by, s.bz + 8, s.bx + 6, s.by, s.bz + 10, "minecraft:air")
	mcCommand("effect clear @s minecraft:resistance")
	local clean = waitUntil(function() return S.siteBoxes == 0 end, 6)
	check("restored: no MC blocks left at the site (server store)", clean, "site boxes " .. tostring(S.siteBoxes))
	local fin = (S.bc or {}).s or {}
	log("blockcol totals: sections created %d, removed %d, rebuilds %d; boxes created %d, removed %d, %.3f ms per created box; worst update %.2f ms (%d boxes changed), worst tick %.2f ms; multi builds %d; module merges %d (max %.3f ms)",
		fin.created or -1, fin.removed or -1, fin.rebuilds or -1, fin.boxesCreated or -1, fin.boxesRemoved or -1, (S.bc or {}).msPerBox or -1, fin.maxMs or -1,
		fin.maxMsBoxes or -1, fin.maxTickMs or -1, fin.multiBuilds or -1, ((S.bc or {}).m or {}).merges or -1, ((S.bc or {}).m or {}).maxMergeMs or -1)
	RunConsoleCommand("gmodcraft_test_p5a_watch", "0")
	local nf = 0
	for _, r in ipairs(results) do if not r[2] then nf = nf + 1 end end
	log("P5ATEST DONE: %d checks, %d failed", #results, nf)
end

local co
function T.Start()
	if co then return end
	results = {}
	T.site = nil
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then
			log("ERROR %s", tostring(err))
			for k in pairs(gmodcraft.input.inject) do gmodcraft.input.inject[k] = nil end
			RunConsoleCommand("gmodcraft_test_p5a_cleanup")
			local s = T.site
			if s then
				-- put the world back even after an error
				mcCommand(string.format("fill %d %d %d %d %d %d minecraft:air", s.bx, s.by, s.bz + 4, s.bx + 9, s.by + 2, s.bz + 4))
				mcCommand(string.format("fill %d %d %d %d %d %d minecraft:air", s.bx + 3, s.by, s.bz + 8, s.bx + 6, s.by, s.bz + 10))
				mcCommand("effect clear @s minecraft:resistance")
			end
			log("P5ATEST DONE (error)")
		end
	end)
	hook.Add("Think", "gmodcraft_test_p5a", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_p5a")
			co = nil
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_p5a", T.Start)
hook.Add("InitPostEntity", "gmodcraft_test_p5a", function()
	if file.Exists("gmodcraft/p5atest.txt", "DATA") then
		log("p5atest.txt present: starting in 5 s")
		timer.Simple(5, T.Start)
	end
end)
