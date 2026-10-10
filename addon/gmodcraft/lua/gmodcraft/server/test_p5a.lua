-- P5a test helpers (server; dev only, singleplayer or superadmin). client/test_p5a.lua drives them.
-- gmodcraft_test_p5a_site: picks a flat, empty 12x12-block spot near the player (no MC blocks there
-- either), and plans the build: a 3-high, 10-long wall (MC z = bz+4) and a 1-high 4x3 platform behind
-- it (side B, MC z > the wall; side A is towards smaller MC z = larger Source y). While watching, the
-- server sends JSON state (net gmodcraft_test_p5a) every 0.2 s: the site, the zombie / prop / bot
-- positions and how far they got into the wall, the MC player's sanity counters, the block collision
-- stats, the synthetic 512-box build timing and the NextBot classes found. Spawned things are tagged p5a.

local function allowed(ply)
	return IsValid(ply) and (game.SinglePlayer() or ply:IsSuperAdmin())
end
local function tagged(tag)
	local out = {}
	for _, e in ipairs(ents.GetAll()) do
		if e:GetNW2String("gmodcraft_test", "") == tag then out[#out + 1] = e end
	end
	return out
end

util.AddNetworkString("gmodcraft_test_p5a")
local p5a = { watch = false }
local C5 = gmodcraft.convert

local function p5aLog(fmt, ...) print("[gmodcraft-test] p5a: " .. string.format(fmt, ...)) end

-- Source box of an MC block range [x0, x1) x [y0, y1) x [z0, z1) (blocks).
local function mcBox(x0, y0, z0, x1, y1, z1)
	local ox, oz, oy = C5.slot.ox, C5.slot.oz, C5.slot.oy or 0
	return Vector((x0 - ox) * 40, -(z1 - oz) * 40, y0 * 40 - oy), Vector((x1 - ox) * 40, -(z0 - oz) * 40, y1 * 40 - oy)
end

-- MC solid cells inside an MC block range [x0, x1) x [y0, y1) x [z0, z1), from the module's boxes.
local function mcBlocksIn(x0, y0, z0, x1, y1, z1)
	local n = 0
	for sx = math.floor(x0 / 16), math.floor((x1 - 1) / 16) do
		for sy = math.floor(y0 / 16), math.floor((y1 - 1) / 16) do
			for sz = math.floor(z0 / 16), math.floor((z1 - 1) / 16) do
				local packed = gmodcraft.BlockBoxes(sx, sy, sz)
				if packed then
					for i = 0, #packed / 6 - 1 do
						local a, b, c, d, e, f = string.byte(packed, i * 6 + 1, i * 6 + 6)  -- half blocks (v26)
						local ax, ay, az = math.max(sx * 16 + a / 2, x0), math.max(sy * 16 + b / 2, y0), math.max(sz * 16 + c / 2, z0)
						local bx, by, bz = math.min(sx * 16 + d / 2, x1), math.min(sy * 16 + e / 2, y1), math.min(sz * 16 + f / 2, z1)
						if bx > ax and by > ay and bz > az then n = n + (bx - ax) * (by - ay) * (bz - az) end
					end
				end
			end
		end
	end
	return n
end

local function findSite(ply)
	local P = ply:GetPos()
	local why = { floor = 0, flat = 0, clear = 0, mc = 0 }
	for _, dist in ipairs({ 200, 280, 360, 440, 560, 700, 850, 1000, 1200 }) do
		for a = 0, 345, 15 do
			local c = P + Angle(0, a, 0):Forward() * dist
			local tr = util.TraceLine({ start = c + Vector(0, 0, 100), endpos = c - Vector(0, 0, 400), mask = MASK_SOLID_BRUSHONLY })
			if tr.Hit and not tr.StartSolid and tr.HitNormal.z > 0.9 then
				local fz = tr.HitPos.z
				local flat = true
				for _, d in ipairs({ { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 }, { 0, 1 }, { 1, 0 }, { 0, -1 }, { -1, 0 } }) do
					local q = Vector(c.x + d[1] * 230, c.y + d[2] * 230, fz)
					local t2 = util.TraceLine({ start = q + Vector(0, 0, 60), endpos = q - Vector(0, 0, 60), mask = MASK_SOLID_BRUSHONLY })
					if not t2.Hit or t2.StartSolid or math.abs(t2.HitPos.z - fz) > 14 or t2.HitNormal.z < 0.9 then flat = false break end
				end
				local clear = flat and not util.TraceHull({ start = Vector(c.x, c.y, fz + 24), endpos = Vector(c.x, c.y, fz + 24), mins = Vector(-230, -230, 0),
					maxs = Vector(230, 230, 150), mask = MASK_SOLID, filter = ply }).StartSolid
				if not flat then why.flat = why.flat + 1 elseif not clear then why.clear = why.clear + 1 end
				if clear then
					local mx, _, mz = C5.ToMc(c)
					local bx, bz = math.floor(mx) - 6, math.floor(mz) - 6
					local fy = C5.ZToMcY(fz)  -- the floor in MC y (v21: the slot's oy)
					local by = math.floor(fy)
					if (by + 1 - fy) * 40 < 8 then by = by + 1 end  -- a sliver of gap rather than a wall a block shorter
					-- MC must have no blocks in the build volume (+1 around) either (the module's store)
					local empty = mcBlocksIn(bx - 1, by, bz - 1, bx + 13, by + 4, bz + 13) == 0
					if empty then return { bx = bx, by = by, bz = bz, floorZ = fz, cx = c.x, cy = c.y } end
					why.mc = why.mc + 1
				end
			else
				why.floor = why.floor + 1
			end
		end
	end
	p5aLog("no site around %s: rejected for floor %d, flatness %d, clearance %d, MC blocks %d", tostring(P), why.floor, why.flat, why.clear, why.mc)
end

local function tagP5a(e) e:SetNW2String("gmodcraft_test", "p5a") return e end

-- Distance from a point to a box (0 inside).
local function boxGap(p, mins, maxs)
	local dx = math.max(mins.x - p.x, 0, p.x - maxs.x)
	local dy = math.max(mins.y - p.y, 0, p.y - maxs.y)
	local dz = math.max(mins.z - p.z, 0, p.z - maxs.z)
	return math.sqrt(dx * dx + dy * dy + dz * dz)
end

-- How deep an entity's collision box overlaps the wall (0 = not at all).
local function overlap(e, wmin, wmax)
	local a, b = e:WorldSpaceAABB()
	local ox = math.min(b.x, wmax.x) - math.max(a.x, wmin.x)
	local oy = math.min(b.y, wmax.y) - math.max(a.y, wmin.y)
	local oz = math.min(b.z, wmax.z) - math.max(a.z, wmin.z)
	if ox <= 0 or oy <= 0 or oz <= 0 then return 0 end
	return math.min(ox, oy, oz)
end

concommand.Add("gmodcraft_test_p5a_site", function(ply)
	if not allowed(ply) then return end
	local s = findSite(ply)
	p5a.site = s
	if not s then p5aLog("no flat empty spot near the player") return end
	s.wallMin, s.wallMax = mcBox(s.bx, s.by, s.bz + 4, s.bx + 10, s.by + 3, s.bz + 5)
	s.platMin, s.platMax = mcBox(s.bx + 3, s.by, s.bz + 8, s.bx + 7, s.by + 1, s.bz + 11)
	p5aLog("site: blocks (%d, %d, %d), floor z %.1f; wall %s..%s, platform %s..%s", s.bx, s.by, s.bz, s.floorZ, tostring(s.wallMin), tostring(s.wallMax),
		tostring(s.platMin), tostring(s.platMax))
end)

concommand.Add("gmodcraft_test_p5a_watch", function(ply, _, args)
	if not allowed(ply) then return end
	p5a.watch = args[1] == "1"
	p5a.ply = ply
end)

local function sanity(ply)
	local st = gmodcraft.player.Get(ply) or {}
	return { rej = st.rejected or 0, cor = st.corrections or 0, rec = st.recoveries or 0, ins = st.insideReports or 0, acc = st.accepted or 0,
		lastReject = st.lastReject and tostring(st.lastReject) or nil }
end

-- gmodcraft_test_p5a_spawn zombie|prop|bot|removezombie
concommand.Add("gmodcraft_test_p5a_spawn", function(ply, _, args)
	if not allowed(ply) then return end
	local s = p5a.site
	if not s then return end
	local what = args[1]
	if what == "zombie" then
		-- on the platform (side B)
		local top = (s.platMin + s.platMax) * 0.5
		top.z = s.platMax.z + 2
		local e = tagP5a(ents.Create("npc_zombie"))
		e:SetPos(top)
		e:Spawn()
		e:Activate()
		p5a.zombie, p5a.zGap, p5a.zOverlap, p5a.zSideA = e, 99999, 0, false
		-- the wall hides the player: hand the zombie its enemy, so it chases
		timer.Simple(0.3, function()
			if not IsValid(e) or not IsValid(p5a.ply) then return end
			e:SetEnemy(p5a.ply)
			e:UpdateEnemyMemory(p5a.ply, p5a.ply:GetPos())
			e:SetSchedule(SCHED_CHASE_ENEMY)
		end)
		p5aLog("npc_zombie #%d on the platform at %s (platform top z %.1f)", e:EntIndex(), tostring(top), s.platMax.z)
	elseif what == "prop" then
		-- a melon onto the wall's 3rd column from the west end, centre of the block
		local mn, mx = mcBox(s.bx + 2, s.by + 2, s.bz + 4, s.bx + 3, s.by + 3, s.bz + 5)
		local pos = (mn + mx) * 0.5
		pos.z = mx.z + 30
		local e = tagP5a(ents.Create("prop_physics"))
		e:SetModel("models/props_junk/watermelon01.mdl")
		e:SetPos(pos)
		e:Spawn()
		p5a.prop = e
		p5aLog("melon #%d dropped at %s (wall top z %.1f)", e:EntIndex(), tostring(pos), mx.z)
	elseif what == "bot" then
		p5a.botWanted = CurTime()
		player.CreateNextBot("p5a_bot")
	elseif what == "removezombie" then
		if IsValid(p5a.zombie) then p5a.zombie:Remove() end
	end
end)

-- The bot: placed on side A 70 u from the wall's middle, then walks into the wall for 2.5 s.
hook.Add("PlayerSpawn", "gmodcraft_test_p5a", function(ply)
	if not p5a.botWanted or not ply:IsBot() or IsValid(p5a.bot) then return end
	p5a.bot = ply
	timer.Simple(0.5, function()
		local s = p5a.site
		if not IsValid(ply) or not s then return end
		local mid = (s.wallMin + s.wallMax) * 0.5
		local pos = Vector(mid.x + 60, s.wallMax.y + 70, s.floorZ + 16)
		ply:SetPos(pos)
		ply:SetEyeAngles(Angle(0, -90, 0))  -- facing -y: towards the wall
		p5a.botGap, p5a.botOverlap, p5a.botUntil = 99999, 0, CurTime() + 2.5
		p5aLog("bot %s at %s, walking into the wall (face y %.1f)", ply:Nick(), tostring(pos), s.wallMax.y)
	end)
end)
hook.Add("StartCommand", "gmodcraft_test_p5a", function(ply, cmd)
	if ply ~= p5a.bot or not p5a.botUntil or CurTime() > p5a.botUntil then return end
	cmd:ClearMovement()
	cmd:ClearButtons()
	cmd:SetViewAngles(Angle(0, -90, 0))
	cmd:SetForwardMove(400)
end)

-- The synthetic ~500-box build: a 3D checkerboard of 4 layers (512 boxes) on a throwaway entity.
local function synthBuild()
	local s = p5a.site
	local parts = {}
	for y = 0, 3 do
		for z = 0, 15 do
			for x = 0, 15 do
				if (x + y + z) % 2 == 0 then parts[#parts + 1] = string.char(x, y, z, x + 1, y + 1, z + 1) end
			end
		end
	end
	-- one box entity each (box mode), as blockcol creates them; then removed again
	local base = Vector(s.cx - 320, s.cy + 320, s.floorZ + 400)
	local made, times = {}, {}
	local t0 = SysTime()
	for i, b in ipairs(parts) do
		local x0, y0, z0, x1, y1, z1 = string.byte(b, 1, 6)
		local tb = SysTime()
		local e = ents.Create("gmodcraft_blocks")
		e:SetPos(base + Vector((x0 + x1) * 20, -(z0 + z1) * 20, (y0 + y1) * 20))
		e:Spawn()
		e:BuildBox(Vector((x1 - x0) * 20, (z1 - z0) * 20, (y1 - y0) * 20))
		times[i] = (SysTime() - tb) * 1000
		made[i] = e
	end
	local total = (SysTime() - t0) * 1000
	local tr = SysTime()
	for _, e in ipairs(made) do if IsValid(e) then e:Remove() end end
	local rm = (SysTime() - tr) * 1000
	table.sort(times)
	return { boxes = #parts, total = total, perBox = total / #parts, median = times[math.floor(#times / 2)], max = times[#times], removeMs = rm,
		ticks = math.ceil(total / 2) }
end
concommand.Add("gmodcraft_test_p5a_synth", function(ply)
	if not allowed(ply) or not p5a.site then return end
	p5a.synth = synthBuild()
	local y = p5a.synth
	p5aLog("synthetic %d box entities: %.2f ms total, %.3f ms per box (median %.3f, max %.3f), removal %.2f ms; with the 2 ms/tick budget ~%d ticks",
		y.boxes, y.total, y.perBox, y.median, y.max, y.removeMs, y.ticks)
end)

local function nextbots()
	local out = {}
	for cls, t in pairs(scripted_ents.GetList()) do
		local tb = t.t or {}
		if tb.Base == "base_nextbot" or tb.Type == "nextbot" then out[#out + 1] = cls .. (tb.Spawnable and " (spawnable)" or "") end
	end
	table.sort(out)
	return out
end

-- Physics state of the block entities near the site (live run 4: a prop fell through one).
concommand.Add("gmodcraft_test_p5a_physdiag", function(ply)
	if not allowed(ply) or not p5a.site then return end
	local c = Vector(p5a.site.cx, p5a.site.cy, p5a.site.floorZ)
	for _, e in ipairs(ents.FindByClass("gmodcraft_blocks")) do
		if e:GetPos():Distance(c) < 1500 then
			local po = e:GetPhysicsObject()
			local ok, cv = false, nil
			if IsValid(po) then ok, cv = pcall(po.GetMeshConvexes, po) end
			p5aLog("block ent #%d at %s: phys %s, motion %s, collisions %s, mass %s, asleep %s, phys pos %s, convexes %s, solid %d, movetype %d, group %d, bounds %s..%s",
				e:EntIndex(), tostring(e:GetPos()), tostring(IsValid(po)), IsValid(po) and tostring(po:IsMotionEnabled()) or "-",
				IsValid(po) and tostring(po:IsCollisionEnabled()) or "-", IsValid(po) and tostring(po:GetMass()) or "-", IsValid(po) and tostring(po:IsAsleep()) or "-",
				IsValid(po) and tostring(po:GetPos()) or "-", ok and cv and tostring(#cv) or "?", e:GetSolid(), e:GetMoveType(), e:GetCollisionGroup(),
				tostring(e:OBBMins()), tostring(e:OBBMaxs()))
		end
	end
end)

-- gmodcraft_test_p5a_trace: hull traces from side A into the wall's middle at 3 heights: they must
-- hit a block entity at the wall's face (state field "trace").
concommand.Add("gmodcraft_test_p5a_trace", function(ply)
	if not allowed(ply) or not p5a.site then return end
	local s = p5a.site
	local mid = (s.wallMin + s.wallMax) * 0.5
	local out = { ok = true, txt = {} }
	for _, z in ipairs({ s.wallMin.z + 20, mid.z, s.wallMax.z - 20 }) do
		local st = Vector(mid.x + 7, s.wallMax.y + 120, z)
		local tr = util.TraceHull({ start = st, endpos = Vector(st.x, s.wallMin.y - 40, z), mins = Vector(-8, -8, -8), maxs = Vector(8, 8, 8), mask = MASK_SOLID,
			filter = function(e) return e:GetClass() == "gmodcraft_blocks" end })
		local face = tr.HitPos.y - 8
		local hitBlock = IsValid(tr.Entity) and tr.Entity:GetClass() == "gmodcraft_blocks"
		local good = tr.Hit and hitBlock and math.abs(face - s.wallMax.y) < 2
		out.ok = out.ok and good
		out.txt[#out.txt + 1] = string.format("z %.0f: %s face y %.1f (wall %.1f)", z, hitBlock and "block ent" or (tr.Hit and "other" or "miss"), face, s.wallMax.y)
	end
	out.txt = table.concat(out.txt, "; ")
	p5a.trace = out
	p5aLog("trace into the wall: %s", out.txt)
end)

-- gmodcraft_test_p5a_count x0 y0 z0 x1 y1 z1 (inclusive MC block coords): MC solid cells there (state field "count").
concommand.Add("gmodcraft_test_p5a_count", function(ply, _, args)
	if not allowed(ply) then return end
	local a = {}
	for i = 1, 6 do a[i] = math.floor(tonumber(args[i]) or 0) end
	p5a.countArgs = a
end)

-- Physics experiment (live run 5: props fall through block entities that traces do hit): a
-- 3x3-block slab built several ways, a melon dropped on each; after 2.5 s the melon heights.
--   multi      gmodcraft_blocks as shipped (PhysicsInitMultiConvex, custom collisions, placeholder model)
--   nocustom   the same without EnableCustomCollisions
--   initbox    gmodcraft_blocks with PhysicsInitBox instead
--   convex1    gmodcraft_blocks with PhysicsInitConvex (one convex)
--   prop       a frozen prop_physics (control)
concommand.Add("gmodcraft_test_p5a_physexp", function(ply)
	if not allowed(ply) or not p5a.site then return end
	local s = p5a.site
	local base = Vector(s.cx - 240, s.cy + 60, s.floorZ + 100)  -- inside the site's verified-clear volume (run before the build)
	local packed = string.char(0, 0, 0, 3, 1, 3)
	local out = {}
	p5a.physexp = out
	local variants = { "multi", "nocustom", "initbox", "prop" }
	for i, v in ipairs(variants) do
		local origin = base + Vector((i - 1) * 125, 0, 0)
		local e
		if v == "prop" then
			e = tagP5a(ents.Create("prop_physics"))
			e:SetModel("models/hunter/blocks/cube1x1x025.mdl")
			e:SetPos(origin + Vector(60, -60, 0))
			e:Spawn()
			e:GetPhysicsObject():EnableMotion(false)
		else
			e = tagP5a(ents.Create("gmodcraft_blocks"))
			e:SetPos(origin)
			e:Spawn()
			if v == "multi" then
				e:BuildFromPacked(packed)
			elseif v == "nocustom" then
				e:BuildFromPacked(packed)
				e:EnableCustomCollisions(false)
			elseif v == "initbox" then
				e:PhysicsInitBox(Vector(0, -120, 0), Vector(120, 0, 40))
				e:SetSolid(SOLID_VPHYSICS)
				e:SetMoveType(MOVETYPE_VPHYSICS)
				e:GetPhysicsObject():EnableMotion(false)
			elseif v == "convex1" then
				local list = e.Convexes(packed)
				e:PhysicsInitConvex(list[1])
				e:SetSolid(SOLID_VPHYSICS)
				e:SetMoveType(MOVETYPE_VPHYSICS)
				e:EnableCustomCollisions(true)
				e:GetPhysicsObject():EnableMotion(false)
			end
		end
		local m = tagP5a(ents.Create("prop_physics"))
		m:SetModel("models/props_junk/watermelon01.mdl")
		m:SetPos(origin + Vector(60, -60, 90))
		m:Spawn()
		local top = origin.z + 40
		if v == "prop" then local _, mx = e:WorldSpaceAABB() top = mx.z end
		out[v] = { melon = m, top = top }
	end
	timer.Simple(2.5, function()
		local res = {}
		for _, v in ipairs(variants) do
			local o = out[v]
			local z = IsValid(o.melon) and o.melon:GetPos().z or -99999
			res[#res + 1] = string.format("%s: melon z - top %.1f (%s)", v, z - o.top, z > o.top - 5 and "rests" or "FELL THROUGH")
		end
		p5a.physexpText = table.concat(res, "; ")
		p5aLog("physics experiment: %s", p5a.physexpText)
	end)
end)

concommand.Add("gmodcraft_test_p5a_cleanup", function(ply)
	if not allowed(ply) then return end
	for _, e in ipairs(tagged("p5a")) do e:Remove() end
	if IsValid(p5a.bot) then p5a.bot:Kick("p5a test done") end
	p5a.bot, p5a.botWanted, p5a.zombie, p5a.prop = nil, nil, nil, nil
	p5a.watch = false
end)

local nextSend = 0
hook.Add("Tick", "gmodcraft_test_p5a", function()
	if not p5a.watch then return end
	local s = p5a.site
	if s then
		local z = p5a.zombie
		if IsValid(z) then
			local p = z:GetPos()
			p5a.zGap = math.min(p5a.zGap, boxGap(z:WorldSpaceCenter(), s.wallMin, s.wallMax))
			p5a.zOverlap = math.max(p5a.zOverlap, overlap(z, s.wallMin, s.wallMax))
			if p.y > s.wallMax.y + 8 then p5a.zSideA = true end
		end
		local b = p5a.bot
		if IsValid(b) and p5a.botUntil then
			p5a.botGap = math.min(p5a.botGap, b:GetPos().y - s.wallMax.y)  -- feet centre to the wall's side-A face
			p5a.botOverlap = math.max(p5a.botOverlap, overlap(b, s.wallMin, s.wallMax))
		end
	end
	if CurTime() < nextSend then return end
	nextSend = CurTime() + 0.2
	local ply = p5a.ply
	if not IsValid(ply) then return end
	local t = { site = s and { bx = s.bx, by = s.by, bz = s.bz, floorZ = s.floorZ, wallTop = s.wallMax.z, platTop = s.platMax.z, wallA = s.wallMax.y, wallB = s.wallMin.y,
		wallX0 = s.wallMin.x, wallX1 = s.wallMax.x } or nil, pc = sanity(ply), synth = p5a.synth, physexp = p5a.physexpText, trace = p5a.trace }
	local ca = p5a.countArgs
	if ca then
		t.count = mcBlocksIn(ca[1], ca[2], ca[3], ca[4] + 1, ca[5] + 1, ca[6] + 1)
		t.countKey = table.concat(ca, " ")
	end
	if gmodcraft.blockcol then
		local d = gmodcraft.blockcol.DebugTable()
		t.bc = { entities = d.entities, boxEnts = d.boxEnts, multi = d.multiSections, boxes = d.boxes, pending = d.pending, s = d.stats, m = d.module,
			msPerBox = d.msPerBox }
	end
	local z = p5a.zombie
	if IsValid(z) then
		local p = z:GetPos()
		t.z = { x = p.x, y = p.y, z = p.z, gap = p5a.zGap, ov = p5a.zOverlap, sideA = p5a.zSideA, ground = z:IsOnGround(), enemy = IsValid(z:GetEnemy()) }
	end
	local pr = p5a.prop
	if IsValid(pr) then
		local po = pr:GetPhysicsObject()
		local p = pr:GetPos()
		t.p = { x = p.x, y = p.y, z = p.z, v = IsValid(po) and po:GetVelocity():Length() or -1, asleep = IsValid(po) and po:IsAsleep() or false }
	end
	if IsValid(p5a.bot) then
		local p = p5a.bot:GetPos()
		t.b = { x = p.x, y = p.y, z = p.z, gap = p5a.botGap, ov = p5a.botOverlap, done = p5a.botUntil ~= nil and CurTime() > p5a.botUntil }
	end
	if s then
		-- MC solid cells in the build volume (0 before the build and after the restore)
		local n = mcBlocksIn(s.bx - 1, s.by, s.bz - 1, s.bx + 13, s.by + 4, s.bz + 13)
		t.siteBoxes = n
	end
	if not p5a.nb then p5a.nb = nextbots() end
	t.nb = p5a.nb
	local data = util.Compress(util.TableToJSON(t))
	net.Start("gmodcraft_test_p5a")
	net.WriteUInt(#data, 16)
	net.WriteData(data, #data)
	net.Send(ply)
end)
