-- P6i (protocol v30): moving platforms carry MC players. Minecraft owns the player's position, so
-- GMod never moves it; instead, every frame, this finds the moving GMod entity under the MC player's
-- feet (a door / func_movelinear / func_tracktrain / func_train / func_platrot, a pushed or
-- physgun-held prop, a prop on a rope: anything in the dynamic layer, shared/dynamic.lua) and hands
-- it to Minecraft in HostState's carry block (link.lua's Think): its origin and yaw in MC space, their
-- rates per MC tick, and the top under the feet. Minecraft moves the player with it each tick and
-- pins the feet to the top (fabric CarryClient / world/Carry.java).
--
--   detect     a flat hull the size of MC's player traced from 24 units over the feet (a platform
--              going up meets them from below) to 80 under them (a jump over it still carries, as
--              Source's ground velocity does); world, MC blocks and other entities block it, players
--              don't. Only a dynamic-layer entity carries. Feet at most ON_TOP_ABOVE over its top:
--              standing on it (kCarryOnTop, pinned)
--   hold       the carry holds HOLD s after the platform is gone from under the feet
--   motion     the entity's interpolated origin / yaw as this client draws it (brush movers' velocity
--              isn't reliable on clients); rates from frame-to-frame deltas, smoothed
--   look       Minecraft takes the look from GMod (input.lua), so the platform's turn is applied to
--              the look here, while Minecraft reports it carries the player (McState.carryEnt)
-- Vehicles aren't in the dynamic layer and never carry; pitch / roll of a platform is ignored (v1).

local CA = gmodcraft.carry or {}
gmodcraft.carry = CA
local C = gmodcraft.convert
local K = gmodcraft.K or {}
local band = bit.band

local START_UP, DOWN = 24, 80
local ON_TOP_ABOVE = 6
local HOLD = 0.2
local SMOOTH = 0.5  -- weight of the newest frame in the rates
local HULL_MIN, HULL_MAX = Vector(-12, -12, 0), Vector(12, 12, 2)
local UP, DOWNV = Vector(0, 0, START_UP), Vector(0, 0, DOWN)

CA.seq = CA.seq or 0
CA.stats = CA.stats or { starts = 0, frames = 0 }

local function traceFilter(e)
	return not e:IsPlayer()
end

function CA.Clear()
	if CA.ent then gmodcraft.Log("carry", "carry ends (%s)", tostring(CA.ent)) end
	CA.ent, CA.holdUntil, CA.vel, CA.yawRate = nil, nil, nil, nil
end

-- What carries the local MC player right now: entity, top (Source z), on top? Or nil, and whether
-- the feet stand on something else (the world, an MC block): then the carry ends at once.
local function under(feet)
	local tr = util.TraceHull({ start = feet + UP, endpos = feet - DOWNV, mins = HULL_MIN, maxs = HULL_MAX, mask = MASK_PLAYERSOLID,
		filter = traceFilter })
	if not tr.Hit or tr.StartSolid then return nil, nil, false, false end
	local top = tr.HitPos.z
	local onTop = feet.z - top <= ON_TOP_ABOVE
	if not IsValid(tr.Entity) or not gmodcraft.dynamic.Wanted(tr.Entity) then return nil, nil, false, onTop end
	return tr.Entity, top, onTop, false
end

local function noCarry(s)
	s.carryEnt = 0
	return nil
end

-- Called by link.lua's Think every frame before HostState goes out: fills s.carry*.
function CA.Update(s)
	local CL = gmodcraft.clientLink
	local ply = LocalPlayer()
	local M = CL and CL.M
	if not IsValid(ply) or not C.slot.known or not CL.McInWorld() or not gmodcraft.IsPuppet(ply) or ply:InVehicle() or gmodcraft.IsNoclip(ply)
		or band(M.flags or 0, bit.bor(K.McFlying or 0x80, K.McHeld or 0x100, K.McDead or 0x20)) ~= 0 or not M.curX then
		if CA.ent then CA.Clear() end
		return noCarry(s)
	end
	local now = SysTime()
	local feet = C.FromMc(M.curX, M.curY, M.curZ)
	local hit, top, onTop, onOther = under(feet)
	if onOther and CA.ent then CA.Clear() end  -- stepped off onto the world / something that doesn't move
	if hit then
		if hit ~= CA.ent then
			CA.ent = hit
			CA.lastPos, CA.lastYaw, CA.lastT = hit:GetPos(), hit:GetAngles().y, now
			CA.vel, CA.yawRate = Vector(0, 0, 0), 0
			CA.stats.starts = CA.stats.starts + 1
			gmodcraft.Log("carry", "carried by %s", tostring(hit))
		end
		CA.holdUntil = now + HOLD
	elseif CA.ent and (not IsValid(CA.ent) or now > (CA.holdUntil or 0)) then
		CA.Clear()
	end
	local e = CA.ent
	if not IsValid(e) then
		CA.ent = nil
		return noCarry(s)
	end
	local pos, yaw = e:GetPos(), e:GetAngles().y
	local dt = now - CA.lastT
	if dt > 1e-4 then
		local dYaw = math.NormalizeAngle(yaw - CA.lastYaw)
		CA.vel = CA.vel * (1 - SMOOTH) + (pos - CA.lastPos) * (SMOOTH / dt)
		CA.yawRate = CA.yawRate * (1 - SMOOTH) + dYaw * (SMOOTH / dt)
		CA.lastPos, CA.lastYaw, CA.lastT = pos, yaw, now
		-- The look turns with the platform while Minecraft carries the player on it (MC yaw = 270 - Source yaw).
		local look = gmodcraft.input and gmodcraft.input.look
		if look and dYaw ~= 0 and M.carryEnt == e:EntIndex() then look.yaw = (look.yaw - dYaw) % 360 end
	end
	local tick = 1 / 20  -- one MC tick (McState.tickMs is how long the last tick took, not the interval)
	CA.seq = (CA.seq + 1) % 4294967296
	CA.stats.frames = CA.stats.frames + 1
	s.carryEnt = e:EntIndex()
	s.carrySeq = CA.seq
	s.carryFlags = (hit == e and onTop) and (K.CarryOnTop or 1) or 0
	-- Source (x, y, z) units/s -> MC (x, y, z) = (x, z, -y) / 40 blocks per tick.
	s.carryVelX, s.carryVelY, s.carryVelZ = CA.vel.x / 40 * tick, CA.vel.z / 40 * tick, -CA.vel.y / 40 * tick
	s.carryYawRate = -math.rad(CA.yawRate) * tick
	s.carryPivotX, s.carryPivotY, s.carryPivotZ = C.ToMc(pos)
	s.carryTopY = (hit == e and top) and C.ZToMcY(top) or 0
	s.carryYaw = math.rad(C.YawToMc(yaw))
	return e
end
