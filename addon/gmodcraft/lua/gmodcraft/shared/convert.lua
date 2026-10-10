-- Coordinate mapping, docs/DESIGN.md section 3 (D-003). 1 block = 40 units.
--   mc.x =  src.x / 40 + ox            src.x =  (mc.x - ox) * 40
--   mc.y = (src.z + oy) / 40           src.z =   mc.y * 40 - oy
--   mc.z = -src.y / 40 + oz            src.y = -(mc.z - oz) * 40
-- oy (v21, P8): the slot's vertical offset in SOURCE UNITS (an int, may be a fraction of a block), so
-- a map's floor sits on a block boundary; 0 for slots from before v21.
--   mc.yaw = (270 - src.yaw) mod 360 (its own inverse); pitch is the same (both positive-down)
--   src.fov = 2 atan(tan(mc.vfov / 2) * 4/3)
-- (ox, oz) is the map's slot origin: the MC server answers it on the server link, the GMod server
-- forwards it to clients (net gmodcraft_slot). Until it is known, nothing is converted for MC.

local C = gmodcraft.convert or {}
gmodcraft.convert = C

C.UNITS = 40
C.slot = C.slot or { known = false, ox = 0, oz = 0, oy = 0, worldId = 0 }
C.slot.oy = C.slot.oy or 0

function C.SetSlot(known, ox, oz, worldId, oy)
	C.slot.known, C.slot.ox, C.slot.oz, C.slot.worldId, C.slot.oy = known, ox or 0, oz or 0, worldId or 0, oy or 0
end

-- This map's worldId: FNV-1a 32 of the lowercased map name.
function C.WorldId()
	if gmodcraft.WorldId then return gmodcraft.WorldId(game.GetMap()) end
	return 0
end

-- Source position (Vector) -> MC x, y, z (blocks, slot origin included).
function C.ToMc(v, ox, oz, oy)
	ox, oz, oy = ox or C.slot.ox, oz or C.slot.oz, oy or C.slot.oy
	return v.x / 40 + ox, (v.z + oy) / 40, -v.y / 40 + oz
end

-- MC x, y, z -> Source Vector.
function C.FromMc(x, y, z, ox, oz, oy)
	ox, oz, oy = ox or C.slot.ox, oz or C.slot.oz, oy or C.slot.oy
	return Vector((x - ox) * 40, -(z - oz) * 40, y * 40 - oy)
end

-- Source z <-> MC y alone (heights; the slot's oy).
function C.ZToMcY(z, oy) return (z + (oy or C.slot.oy)) / 40 end
function C.McYToZ(y, oy) return y * 40 - (oy or C.slot.oy) end

function C.YawToMc(srcYaw)
	return (270 - srcYaw) % 360
end

-- MC yaw -> Source yaw in (-180, 180].
function C.YawFromMc(mcYaw)
	local y = (270 - mcYaw) % 360
	if y > 180 then y = y - 360 end
	return y
end

-- MC's vertical FOV (degrees) -> GMod's CalcView fov (horizontal FOV of a 4:3 view).
function C.FovFromMc(vfov)
	return 2 * math.deg(math.atan(math.tan(math.rad(vfov) / 2) * 4 / 3))
end

-- The worked examples of DESIGN.md section 3. Returns ok, report.
function C.SelfTest()
	local fails = {}
	local function near(a, b, eps) return math.abs(a - b) <= (eps or 1e-6) end
	local function chk(name, ok) if not ok then fails[#fails + 1] = name end end
	-- three yaws: src 0 -> mc 270 (east), src 90 -> mc 180 (north), src 180 -> mc 90 (west)
	chk("yaw 0", near(C.YawToMc(0), 270))
	chk("yaw 90", near(C.YawToMc(90), 180))
	chk("yaw 180", near(C.YawToMc(180), 90))
	for _, y in ipairs({ -170, -90, 0, 45, 90, 179 }) do chk("yaw round trip " .. y, near(C.YawFromMc(C.YawToMc(y)), y, 1e-9)) end
	-- MC forward of mc yaw y, pitch p: (-sin y cos p, -sin p, cos y cos p)
	local function mcFwd(y, p)
		y, p = math.rad(y), math.rad(p)
		return -math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)
	end
	local fx, fy, fz = mcFwd(C.YawToMc(0), 0)
	chk("src +X is mc east", near(fx, 1) and near(fz, 0))
	fx, fy, fz = mcFwd(C.YawToMc(90), 0)
	chk("src +Y is mc north", near(fx, 0) and near(fz, -1))
	-- worked example: (400, 800, 120), angles (10, 90, 0), slot (0, 0) -> (10, 3, -20) yaw 180 pitch 10
	local x, y, z = C.ToMc(Vector(400, 800, 120), 0, 0)
	chk("example slot (0,0)", near(x, 10) and near(y, 3) and near(z, -20))
	x, y, z = C.ToMc(Vector(400, 800, 120), 2 * 2048, -1 * 2048)
	chk("example slot (2,-1)", near(x, 4106) and near(y, 3) and near(z, -2068))
	local v = C.FromMc(4106, 3, -2068, 4096, -2048)
	chk("example inverse", near(v.x, 400, 1e-9) and near(v.y, 800, 1e-9) and near(v.z, 120, 1e-9))
	-- v21: a vertical offset in Source units (a fraction of a block): gm_construct's floor z -144 with
	-- oy 2704 lands on y 64 exactly; 37 units round-trips
	x, y, z = C.ToMc(Vector(400, 800, -144), 0, 0, 2704)
	chk("oy 2704: floor -144 -> y 64", near(y, 64, 1e-9) and near(x, 10) and near(z, -20))
	x, y, z = C.ToMc(Vector(400, 800, 120), 4096, -2048, 37)
	v = C.FromMc(x, y, z, 4096, -2048, 37)
	chk("oy 37 round trip", near(y, 157 / 40, 1e-9) and near(v.x, 400, 1e-9) and near(v.y, 800, 1e-9) and near(v.z, 120, 1e-9))
	chk("ZToMcY / McYToZ", near(C.ZToMcY(-144, 2704), 64, 1e-9) and near(C.McYToZ(64, 2704), -144, 1e-9))
	fx, fy, fz = mcFwd(180, 10)
	chk("example look north and down", near(fx, 0, 1e-9) and near(fy, -0.17365, 1e-4) and near(fz, -0.98481, 1e-4))
	chk("fov 70 -> 86.07", near(C.FovFromMc(70), 86.066, 0.01))
	return #fails == 0, #fails == 0 and "all coordinate checks passed" or ("FAILED: " .. table.concat(fails, ", "))
end
