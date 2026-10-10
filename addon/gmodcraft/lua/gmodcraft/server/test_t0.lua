-- 0.4 Tier 0 live check (dev only: -gmodcraft_dev; Minecraft with GMODCRAFT_DEV_COMMANDS=1). Server console:
--   gmodcraft_t0_live setup [x y]  Minecraft: a 7x1x11 stone platform beside (x, y) (default: gm_construct's west
--                                  grass), a 2x1x2 dug hole in the floor with stone under it
--   gmodcraft_t0_live run          props / a ragdoll on the platform, a drum and a can into the hole, a jeep driven
--                                  along the platform, an NPC walking, player traces, physics time with 30 crates
--   gmodcraft_t0_live setup2 / run2  review checks: a punt at an indoor ceiling, the jeep driving into a dug trench
--   gmodcraft_t0_live ramp [x z]   T0b: the demo ramp (quarter 3, origin MC (x, floor, z)), its jeep at full throttle
--   gmodcraft_t0_live trenchrest   the jeep dropped into the trench: resting height
--   gmodcraft_t0_live cleanup      the GMod entities spawned here removed (the Minecraft blocks stay)
-- Results print "[t0live]".

local TL = gmodcraft.t0live or {}
gmodcraft.t0live = TL
TL.spawned = TL.spawned or {}

local function say(fmt, ...) print("[t0live] " .. string.format(fmt, ...)) end
local function track(e) TL.spawned[#TL.spawned + 1] = e return e end
local function after(sec, fn) timer.Simple(sec, function()
	local ok, err = pcall(fn)
	if not ok then say("error: %s", tostring(err)) end
end) end
local function z(e) return IsValid(e) and string.format("%.1f", e:GetPos().z) or "gone" end
local C = gmodcraft.convert

local function floorAt(x, y)
	local tr = util.TraceLine({ start = Vector(x, y, 3000), endpos = Vector(x, y, -4000), mask = MASK_SOLID_BRUSHONLY })
	return tr.Hit and not tr.HitSky and tr.HitPos.z or nil
end

local function mc(cmd)
	local sid = gmodcraft.mp and gmodcraft.mp.NextDevRequest and gmodcraft.mp.NextDevRequest() or math.random(1, 2 ^ 30)
	local ok, err = gmodcraft.PushDevCommand(sid, cmd)
	say("mc /%s -> %s", cmd, ok and "sent" or tostring(err))
end

local function prop(model, pos)
	local e = ents.Create("prop_physics")
	e:SetModel(model)
	e:SetPos(pos)
	e:Spawn()
	e:Activate()
	local p = e:GetPhysicsObject()
	if IsValid(p) then p:Wake() end
	return track(e)
end

-- the spot: MC block coords of the floor's top block and Source helpers
function TL.Spot(x, y)
	x, y = x or -2400, y or -1400
	local fz = floorAt(x, y)
	local s = gmodcraft.serverLink.slot
	local mx, my, mz = C.ToMc(Vector(x, y, fz), s.ox, s.oz, s.oy)
	TL.spot = { x = x, y = y, fz = fz, bx = math.floor(mx), by = math.floor(my + 0.01) - 1, bz = math.floor(mz) }  -- by: the block under the surface
	return TL.spot
end

local function src(bx, by, bz) local s = gmodcraft.serverLink.slot return C.FromMc(bx, by, bz, s.ox, s.oz, s.oy) end

function TL.Setup(x, y)
	local p = TL.Spot(x, y)
	say("spot: floor z %.1f at (%d, %d) = MC block (%d, %d, %d) under the surface", p.fz, p.x, p.y, p.bx, p.by, p.bz)
	-- platform: one block on the floor, east of the spot
	mc(string.format("fill %d %d %d %d %d %d minecraft:stone", p.bx + 2, p.by + 1, p.bz - 5, p.bx + 8, p.by + 1, p.bz + 5))
	-- hole: 2x1x2 cells dug out of the floor west of the spot, stone under them
	mc(string.format("gmodcraft tool dig %d %d %d %d %d %d", p.bx - 4, p.by, p.bz, p.bx - 3, p.by, p.bz + 1))
	mc(string.format("fill %d %d %d %d %d %d minecraft:stone", p.bx - 4, p.by - 1, p.bz, p.bx - 3, p.by - 1, p.bz + 1))
end

function TL.Run()
	local p = TL.spot or TL.Spot()
	local PW = gmodcraft.physworld
	say("physics world: convar %s, regions %d", GetConVar("gmodcraft_physics_world"):GetString(), table.Count(PW.regions))
	-- platform centre (block centre of bx+5, by+1, bz) and top
	local plat = src(p.bx + 5.5, p.by + 2, p.bz + 0.5)
	local drum = prop("models/props_c17/oildrum001.mdl", plat + Vector(-60, 0, 60))
	local crate = prop("models/props_junk/wood_crate001a.mdl", plat + Vector(60, 60, 60))
	local rag = track(ents.Create("prop_ragdoll"))
	rag:SetModel("models/Humans/Group01/male_07.mdl")
	rag:SetPos(plat + Vector(0, -100, 60))
	rag:Spawn()
	rag:Activate()
	-- hole: centre of the 2x2 cells, bottom = top of the stone under it
	local hole = src(p.bx - 3, p.by, p.bz + 1)  -- the shared corner of the 4 cells, at the cells' bottom
	local hdrum = prop("models/props_c17/oildrum001.mdl", hole + Vector(0, 0, 120))
	local hcan = prop("models/props_junk/PopCan01a.mdl", hole + Vector(12, 12, 100))
	-- jeep on the platform
	local j = track(ents.Create("prop_vehicle_jeep"))
	j:SetModel("models/buggy.mdl")
	j:SetKeyValue("vehiclescript", "scripts/vehicles/jeep_test.txt")
	j:SetPos(plat + Vector(0, 80, 40))
	j:SetAngles(Angle(0, 90, 0))
	j:Spawn()
	j:Activate()
	-- an NPC walking over the regions (navigation on the BSP)
	local npc = track(ents.Create("npc_citizen"))
	npc:SetPos(Vector(p.x - 300, p.y + 200, p.fz + 8))
	npc:Spawn()
	npc:Activate()
	local npc0 = npc:GetPos()
	after(1, function()
		npc:SetLastPosition(npc0 + Vector(0, -400, 0))
		npc:SetSchedule(SCHED_FORCED_GO_RUN)
	end)
	after(4, function()
		local platTop = plat.z
		local rp = rag:GetPhysicsObjectNum(0)
		say("platform top z %.1f (floor %.1f): drum z %s, crate z %s, ragdoll pelvis z %.1f, jeep z %s", platTop, p.fz, z(drum), z(crate),
			IsValid(rp) and rp:GetPos().z or -1, z(j))
		say("hole bottom z %.1f (floor %.1f): drum z %s, can z %s", hole.z, p.fz, z(hdrum), z(hcan))
		local covered = 0
		for _, e in ipairs({ drum, crate, rag, hdrum, hcan, j }) do if IsValid(e) and e.gmcPWc then covered = covered + 1 end end
		say("covered entities: %d of 6", covered)
		-- traces: player hull and bullets keep the BSP (region entities never hit)
		local tr = util.TraceHull({ start = Vector(p.x, p.y, p.fz + 100), endpos = Vector(p.x, p.y, p.fz - 100), mins = Vector(-16, -16, 0),
			maxs = Vector(16, 16, 72), mask = MASK_PLAYERSOLID })
		local tl = util.TraceLine({ start = hole + Vector(0, 0, 200), endpos = hole + Vector(0, 0, -100), mask = MASK_SHOT })
		say("player hull trace: world %s z %.1f, entity %s; shot into the hole: world %s z %.1f entity %s", tostring(tr.HitWorld), tr.HitPos.z,
			tostring(tr.Entity), tostring(tl.HitWorld), tl.HitPos.z, tostring(tl.Entity))
		-- drive the jeep along the platform (no driver: the vehicle's inputs)
		local j0 = IsValid(j) and j:GetPos()
		if IsValid(j) then
			j:Fire("TurnOn")
			j:Fire("HandBrakeOff")
			j:Fire("Throttle", "0.6")
		end
		after(0.8, function()
			say("jeep driving on the platform: moved %.1f, z %s (top %.1f)", IsValid(j) and (j:GetPos() - j0):Length2D() or -1, z(j), platTop)
			if IsValid(j) then j:Fire("Throttle", "0") j:Fire("HandBrakeOn") end
		end)
		after(1.5, function()
			say("npc moved %.1f, z %s (floor %.1f)", IsValid(npc) and (npc:GetPos() - npc0):Length2D() or -1, z(npc), p.fz)
		end)
		-- physics time: 30 crates falling on the area
		after(2, function()
			local sum, n, max = 0, 0, 0
			hook.Add("Tick", "gmodcraft_t0live", function()
				local t = physenv.GetLastSimulationTime() * 1000
				sum, n, max = sum + t, n + 1, math.max(max, t)
			end)
			for i = 1, 30 do prop("models/props_junk/wood_crate001a.mdl", Vector(p.x + math.random(-500, 500), p.y + math.random(-500, 500), p.fz + 150 + i * 10)) end
			after(3, function()
				hook.Remove("Tick", "gmodcraft_t0live")
				say("30 crates: physics %.3f ms avg, %.3f max over %d ticks", n > 0 and sum / n or -1, max, n)
				gmodcraft.physworld.PrintInfo()
			end)
		end)
	end)
end

-- Review checks: a punt at an indoor ceiling (a resting covered crate shot up at 2000 u/s must hit the
-- ceiling, not pass it), and the jeep driving into a dug trench (2 deep, stone at the bottom).
function TL.Setup2()
	local p = TL.spot or TL.Spot()
	-- trench along MC z, 3 wide, 2 deep, west of the hole
	mc(string.format("gmodcraft tool dig %d %d %d %d %d %d", p.bx - 13, p.by - 1, p.bz - 4, p.bx - 11, p.by, p.bz + 8))
	mc(string.format("fill %d %d %d %d %d %d minecraft:stone", p.bx - 13, p.by - 2, p.bz - 4, p.bx - 11, p.by - 2, p.bz + 8))
end

function TL.Run2()
	local p = TL.spot or TL.Spot()
	-- a ceiling: a grid point (several start heights) in open air with a floor below and a ceiling 150..400 above it
	local spot
	for x = -3000, 3000, 200 do
		for y = -3000, 3000, 200 do
			for _, h in ipairs({ -120, 40, 200, 400 }) do
				local st = Vector(x, y, h)
				if not spot and bit.band(util.PointContents(st), CONTENTS_SOLID) == 0 then
					local dn = util.TraceLine({ start = st, endpos = st - Vector(0, 0, 300), mask = MASK_SOLID_BRUSHONLY })
					local up = util.TraceLine({ start = st, endpos = st + Vector(0, 0, 400), mask = MASK_SOLID_BRUSHONLY })
					if dn.Hit and up.Hit and not up.HitSky and not dn.StartSolid and up.HitPos.z - dn.HitPos.z > 150 then
						spot = { x = x, y = y, f = dn.HitPos.z, c = up.HitPos.z }
					end
				end
			end
		end
	end
	if not spot then say("no ceiling found") else
		say("ceiling spot (%d, %d): floor %.1f, ceiling %.1f", spot.x, spot.y, spot.f, spot.c)
		local crate = prop("models/props_junk/wood_crate001a.mdl", Vector(spot.x, spot.y, spot.f + 30))
		after(4, function()
			local po = crate:GetPhysicsObject()
			say("crate before the punt: z %s, asleep %s, covered %s", z(crate), tostring(po:IsAsleep()), tostring(crate.gmcPWc))
			po:Wake()
			po:SetVelocity(Vector(0, 0, 2000))
			local maxz = 0
			hook.Add("Tick", "gmodcraft_t0live_punt", function()
				if IsValid(crate) then maxz = math.max(maxz, crate:GetPos().z) end
			end)
			after(2, function()
				hook.Remove("Tick", "gmodcraft_t0live_punt")
				say("punt: crate highest z %.1f (ceiling %.1f, crate half-height ~20), now z %s, covered %s", maxz, spot.c, z(crate), tostring(crate.gmcPWc))
			end)
		end)
	end
	-- jeep into the trench: starts south of it, drives north (-MC z = +Source y)
	local start = src(p.bx - 11.5, p.by + 1, p.bz + 15)  -- south of the trench (the grass ends north of it)
	local bottom = src(p.bx - 11.5, p.by - 1, p.bz + 2).z
	local j = track(ents.Create("prop_vehicle_jeep"))
	j:SetModel("models/buggy.mdl")
	j:SetKeyValue("vehiclescript", "scripts/vehicles/jeep_test.txt")
	j:SetPos(start + Vector(0, 0, 30))
	j:SetAngles(Angle(0, 0, 0))  -- HL2 vehicles face their local +y: yaw 0 drives toward +y (-MC z), into the trench
	j:Spawn()
	j:Activate()
	after(2, function()
		j:Fire("TurnOn")
		j:Fire("HandBrakeOff")
		j:Fire("Throttle", "0.5")
		local minz, path = 1e9, {}
		hook.Add("Tick", "gmodcraft_t0live_jeep", function()
			if not IsValid(j) then return end
			local q = j:GetPos()
			if q.z < minz then minz = q.z end
			if engine.TickCount() % 33 == 0 then path[#path + 1] = string.format("(%.0f %.0f %.0f %s)", q.x, q.y, q.z, j.gmcPWc and "c" or "u") end
		end)
		after(5, function()
			hook.Remove("Tick", "gmodcraft_t0live_jeep")
			j:Fire("Throttle", "0")
			say("jeep into the trench: z %s, lowest %.1f (trench bottom %.1f, floor %.1f), moved %.1f, covered %s", z(j), minz, bottom, p.fz,
				(j:GetPos() - start):Length2D(), tostring(j.gmcPWc))
			say("jeep path: %s", table.concat(path, " "))
		end)
	end)
end

-- T0b: the demo ramp (half-block steps up 4 blocks, a gap, a landing ramp) placed by Minecraft with its
-- GMod jeep; the jeep drives at it with full throttle: how high it gets (the ramp top is 4 blocks up).
function TL.Ramp(ox, oz)
	local p = TL.spot or TL.Spot()
	ox, oz = ox or -80, oz or 28
	local before = {}
	for _, e in ipairs(ents.FindByClass("prop_vehicle_jeep*")) do before[e] = true end
	if not TL.rampPlaced then  -- once: the demo stays (its jeep is found again by position)
		mc(string.format("gmodcraft demo placeat ramp %d %d %d 3", ox, p.by + 1, oz))  -- quarter 3: forward = +MC x
		TL.rampPlaced = true
	end
	local want = src(ox - 4.5, p.by + 1, oz + 0.5)  -- the demo's jeep: 5 blocks before the origin
	local tries = 0
	local function drive()
		local j
		for _, e in ipairs(ents.FindByClass("prop_vehicle_jeep*")) do
			if e:GetPos():Distance(want) < 200 then j = e end
		end
		tries = tries + 1
		if not IsValid(j) then
			if tries < 30 then after(1, drive) else say("ramp: no demo jeep appeared") end
			return
		end
		local base = src(ox, p.by + 1, oz + 0.5)
		local j0 = j:GetPos()
		say("ramp: jeep at %s (ground z %.1f, ramp top z %.1f), covered %s", tostring(j0), base.z, base.z + 160, tostring(j.gmcPWc))
		j:Fire("TurnOn")
		j:Fire("HandBrakeOff")
		j:Fire("Throttle", "1")
		local maxz, path = -1e9, {}
		hook.Add("Tick", "gmodcraft_t0live_ramp", function()
			if not IsValid(j) then return end
			local q = j:GetPos()
			if q.z > maxz then maxz = q.z end
			if engine.TickCount() % 22 == 0 then path[#path + 1] = string.format("(%.0f %.0f)", q.x - j0.x, q.z - base.z) end
		end)
		after(6, function()
			hook.Remove("Tick", "gmodcraft_t0live_ramp")
			j:Fire("Throttle", "0")
			j:Fire("HandBrakeOn")
			say("ramp: highest z %.1f (= %.1f above the ground; the top is 160), now %.0f along, z %.1f above ground", maxz, maxz - base.z,
				j:GetPos().x - j0.x, j:GetPos().z - base.z)
			say("ramp path (along, height): %s", table.concat(path, " "))
			-- back to the start for another run
			after(1, function() if IsValid(j) then j:SetPos(j0) j:SetAngles(Angle(0, -90, 0)) j:GetPhysicsObject():SetVelocity(Vector()) end end)
		end)
	end
	after(1, drive)
end

-- T0b nit: the jeep's resting height in the trench with the throttle cut
function TL.TrenchRest()
	local p = TL.spot or TL.Spot()
	local j = track(ents.Create("prop_vehicle_jeep"))
	j:SetModel("models/buggy.mdl")
	j:SetKeyValue("vehiclescript", "scripts/vehicles/jeep_test.txt")
	local c = src(p.bx - 11.5, p.by - 1, p.bz + 6)  -- the trench's middle, at its bottom (stone top)
	j:SetPos(c + Vector(0, 0, 40))
	j:SetAngles(Angle(0, 0, 0))  -- along the trench
	j:Spawn()
	j:Activate()
	after(4, function()
		local w = {}
		for i = 0, j:GetWheelCount() - 1 do
			local wp = j:GetWheel(i)
			w[#w + 1] = string.format("%.1f", IsValid(wp) and wp:GetPos().z or -1)
		end
		say("trench rest: jeep z %.1f (bottom %.1f: +%.1f; on the BSP it's +2.7), wheels z %s, covered %s", j:GetPos().z, c.z, j:GetPos().z - c.z,
			table.concat(w, " "), tostring(j.gmcPWc))
	end)
end

-- debug: the bounds of a region's convexes (Source), those whose top lies in [zlo, zhi]
function TL.Dump(rx, ry, rz, zlo, zhi)
	local r = gmodcraft.physworld.regions[gmodcraft.physworld.Key(rx, ry, rz)]
	if not r then say("dump: no region") return end
	for _, e in ipairs(r.ents or {}) do
		for _, cv in ipairs(e:GetPhysicsObject():GetMeshConvexes()) do
			local lo, hi = Vector(1e9, 1e9, 1e9), Vector(-1e9, -1e9, -1e9)
			for _, v in ipairs(cv) do
				local q = e:LocalToWorld(v.pos)
				lo = Vector(math.min(lo.x, q.x), math.min(lo.y, q.y), math.min(lo.z, q.z))
				hi = Vector(math.max(hi.x, q.x), math.max(hi.y, q.y), math.max(hi.z, q.z))
			end
			if hi.z >= zlo and hi.z <= zhi then say("dump: %d tris %s .. %s", #cv / 3, tostring(lo), tostring(hi)) end
		end
	end
end

function TL.Cleanup()
	local n = 0
	for _, e in ipairs(TL.spawned) do if IsValid(e) then e:Remove() n = n + 1 end end
	TL.spawned = {}
	hook.Remove("Tick", "gmodcraft_t0live")
	say("cleanup: %d entities removed", n)
end

concommand.Add("gmodcraft_t0_live", function(ply, _, args)
	if IsValid(ply) then return end  -- server console only
	local step = args[1] or ""
	local fn = ({ setup = function() TL.Setup(tonumber(args[2]), tonumber(args[3])) end, run = TL.Run, setup2 = TL.Setup2, run2 = TL.Run2, ramp = function() TL.Ramp(tonumber(args[2]), tonumber(args[3])) end,
		trenchrest = TL.TrenchRest, cleanup = TL.Cleanup,
		dump = function() TL.Dump(tonumber(args[2]), tonumber(args[3]), tonumber(args[4]), tonumber(args[5]) or -1e9, tonumber(args[6]) or 1e9) end })[step]
	if not fn then say("usage: gmodcraft_t0_live setup [x y] | run | setup2 | run2 | ramp [mcx mcz] | trenchrest | cleanup") return end
	local ok, err = pcall(fn)
	if not ok then say("error: %s", tostring(err)) end
end)
