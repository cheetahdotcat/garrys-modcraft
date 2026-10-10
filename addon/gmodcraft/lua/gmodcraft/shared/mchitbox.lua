-- H-approx (protocol v31): model-shaped hit boxes for the MC entity proxies (shared/mcproxy.lua).
--
-- Minecraft's collision box (w x h at the feet) is what the mob bumps with; its model sticks out of it
-- (zombie arms, cow / pig / sheep heads, spider legs). Rays (bullets, traces, melee, the crosshair) test
-- these boxes instead; movement, player hulls and the VPhysics body stay on the collision box.
--
--  * Hand-authored from the vanilla models (1 px = 1/16 block), at most 4 boxes per type.
--  * Frame: blocks, relative to the feet, in the body-yaw frame: +x forward (where the mob faces),
--    +y its left, +z up. A box is { x0, y0, z0, x1, y1, z1, head = true? }.
--  * w / h: the type's adult collision box (EntityType sized). A proxy's boxes are scaled by its
--    actual width / w (x, y) and height / h (z): babies and the scale attribute follow.
--  * Keyed like McEntity::typeHash: FNV-1a 32 of the entity type id ("minecraft:zombie").
--  * Unknown types: the collision box itself (axis-aligned, no yaw).
-- Pure numbers (no Vector) so module/test/mchitbox_test.py runs it headless.

local HB = gmodcraft.mchitbox or {}
gmodcraft.mchitbox = HB

local UNITS = 40  -- Source units per block (convert.lua)

-- ---- the table --------------------------------------------------------------------------------------
local H = true
HB.SHAPES = {
	{ name = "zombie", w = 0.6, h = 1.95, types = { "zombie", "husk", "drowned", "zombie_villager" }, boxes = {
		{ -0.125, -0.25, 0, 0.125, 0.25, 1.5 },             -- legs + body (8 wide, 4 deep, 24 tall)
		{ -0.25, -0.25, 1.5, 0.25, 0.25, 2.0, head = H },   -- head 8^3
		{ -0.125, 0.25, 1.25, 0.75, 0.5, 1.5 },             -- left arm, held forward (12 px from the shoulder)
		{ -0.125, -0.5, 1.25, 0.75, -0.25, 1.5 },           -- right arm
	} },
	{ name = "skeleton", w = 0.6, h = 1.99, types = { "skeleton", "stray", "bogged", "wither_skeleton" }, boxes = {
		{ -0.0625, -0.1875, 0, 0.0625, 0.1875, 0.75 },      -- legs (2 px thin)
		{ -0.125, -0.375, 0.75, 0.125, 0.375, 1.5 },        -- ribcage + arms at the sides
		{ -0.25, -0.25, 1.5, 0.25, 0.25, 2.0, head = H },
	} },
	{ name = "humanoid", w = 0.6, h = 1.95, types = { "piglin", "zombified_piglin", "piglin_brute" }, boxes = {
		{ -0.125, -0.25, 0, 0.125, 0.25, 0.75 },            -- legs
		{ -0.125, -0.5, 0.75, 0.125, 0.5, 1.5 },            -- body + arms at the sides
		{ -0.3125, -0.3125, 1.5, 0.25, 0.3125, 2.0, head = H },  -- a piglin's wide head, snout forward
	} },
	{ name = "villager", w = 0.6, h = 1.95, types = { "villager", "wandering_trader", "witch", "pillager", "vindicator", "evoker", "illusioner" }, boxes = {
		{ -0.1875, -0.25, 0, 0.1875, 0.25, 1.5 },           -- legs + robe (6 deep)
		{ -0.25, -0.25, 1.5, 0.25, 0.25, 2.125, head = H }, -- head 8 x 10 x 8
		{ 0.1875, -0.25, 0.9375, 0.4375, 0.25, 1.375 },     -- the crossed arms in front
		{ 0.25, -0.0625, 1.5, 0.375, 0.0625, 1.75, head = H }, -- the nose
	} },
	{ name = "creeper", w = 0.6, h = 1.7, types = { "creeper" }, boxes = {
		{ -0.375, -0.25, 0, 0.375, 0.25, 0.375 },           -- four feet, front and back
		{ -0.125, -0.25, 0.375, 0.125, 0.25, 1.125 },       -- body 8 x 12 x 4
		{ -0.25, -0.25, 1.125, 0.25, 0.25, 1.625, head = H },
	} },
	{ name = "spider", w = 1.4, h = 0.9, types = { "spider", "cave_spider" }, boxes = {
		{ -0.625, -0.9375, 0, 0.625, 0.9375, 0.625 },       -- the legs' spread (and the thorax)
		{ -0.9375, -0.3125, 0.3125, -0.1875, 0.3125, 0.8125 },  -- abdomen behind
		{ 0.1875, -0.25, 0.3125, 0.6875, 0.25, 0.8125, head = H },
	} },
	{ name = "cow", w = 0.9, h = 1.4, types = { "cow", "mooshroom" }, boxes = {
		{ -0.5, -0.375, 0, 0.4375, 0.375, 0.75 },           -- legs 12 px
		{ -0.625, -0.375, 0.75, 0.5, 0.375, 1.375 },        -- body 12 x 10 x 18
		{ 0.5, -0.3125, 1.0, 0.875, 0.3125, 1.5625, head = H },  -- head + horns, forward of the box
	} },
	{ name = "pig", w = 0.9, h = 0.9, types = { "pig" }, boxes = {
		{ -0.4375, -0.3125, 0, 0.4375, 0.3125, 0.375 },     -- legs 6 px
		{ -0.5, -0.3125, 0.375, 0.5, 0.3125, 0.875 },       -- body 10 x 8 x 16
		{ 0.375, -0.25, 0.5, 0.9375, 0.25, 1.0, head = H },  -- head 8^3 + snout
	} },
	{ name = "sheep", w = 0.9, h = 1.3, types = { "sheep" }, boxes = {
		{ -0.4375, -0.3125, 0, 0.4375, 0.3125, 0.75 },      -- legs 12 px
		{ -0.5625, -0.3125, 0.75, 0.5625, 0.3125, 1.25 },   -- body with wool
		{ 0.375, -0.25, 1.0, 0.875, 0.25, 1.375, head = H },
	} },
	{ name = "chicken", w = 0.4, h = 0.7, types = { "chicken" }, boxes = {
		{ -0.0625, -0.1875, 0, 0.125, 0.1875, 0.3125 },     -- legs
		{ -0.25, -0.25, 0.3125, 0.25, 0.25, 0.6875 },       -- body + wings
		{ 0.125, -0.125, 0.5625, 0.4375, 0.125, 0.9375, head = H },  -- head, beak, wattle
	} },
	{ name = "enderman", w = 0.6, h = 2.9, types = { "enderman" }, boxes = {
		{ -0.0625, -0.1875, 0, 0.0625, 0.1875, 1.75 },      -- the long legs
		{ -0.125, -0.375, 0.8, 0.125, 0.375, 2.5 },         -- body + the hanging arms
		{ -0.25, -0.25, 2.5, 0.25, 0.25, 3.0, head = H },
	} },
	{ name = "wolf", w = 0.6, h = 0.85, types = { "wolf" }, boxes = {
		{ -0.4375, -0.1875, 0, 0.375, 0.1875, 0.5 },        -- legs 8 px
		{ -0.6875, -0.25, 0.5, 0.4375, 0.25, 0.9375 },      -- body + mane + tail stub
		{ 0.3125, -0.1875, 0.4375, 0.875, 0.1875, 1.0, head = H },  -- head, ears, snout
	} },
	{ name = "horse", w = 1.3964844, h = 1.6, types = { "horse", "donkey", "mule", "skeleton_horse", "zombie_horse" }, boxes = {
		{ -0.6875, -0.3125, 0, 0.6875, 0.3125, 0.75 },      -- legs 11 px
		{ -0.6875, -0.3125, 0.75, 0.6875, 0.3125, 1.3125 }, -- body 10 x 10 x 22
		{ 0.4375, -0.1875, 1.1, 0.8125, 0.1875, 1.9 },      -- neck, up and forward
		{ 0.625, -0.1875, 1.5, 1.125, 0.1875, 2.125, head = H },
	} },
}

-- FNV-1a 32 (unsigned, as the module hands typeHash over): h * 16777619 = (h << 24) + h * 0x193 (mod 2^32).
local TWO32 = 4294967296
function HB.Hash(s)
	local h = 0x811C9DC5
	for i = 1, #s do
		h = bit.bxor(h, s:byte(i)) % TWO32
		h = (bit.lshift(h, 24) % TWO32 + h * 0x193) % TWO32
	end
	return h
end

HB.byHash = {}
for i, sh in ipairs(HB.SHAPES) do
	for _, t in ipairs(sh.types) do
		local h = HB.Hash("minecraft:" .. t)
		assert(HB.byHash[h] == nil, "mchitbox: type listed twice or hash collision: " .. t)
		HB.byHash[h] = i
	end
end

-- The shape index for a McEntity::typeHash (0: unknown, the collision box). Networked (an Int), not the hash.
function HB.ShapeFor(typeHash)
	if not typeHash then return 0 end
	return HB.byHash[typeHash % TWO32] or 0
end

-- ---- geometry ---------------------------------------------------------------------------------------
-- A proxy's boxes in Source units (feet-relative, body frame), scaled to its width / height (blocks).
-- g.n boxes in g[1..n] = { x0, y0, z0, x1, y1, z1, head }; g.r: the yaw-independent envelope's half-size
-- (max horizontal radius over all corners, at least the collision box's); g.zlo / g.zhi its bottom / top;
-- g.fixed: the collision box only (axis-aligned, yaw ignored).
function HB.Build(shape, w, h)
	w, h = tonumber(w) or 0, tonumber(h) or 0
	local hw, top = w * UNITS / 2, h * UNITS
	local sh = HB.SHAPES[shape or 0]
	local g = { n = 0, r = hw, zlo = 0, zhi = top, fixed = sh == nil or w <= 0 or h <= 0 }
	if g.fixed then
		g.n = 1
		g[1] = { -hw, -hw, 0, hw, hw, top, false }
		return g
	end
	local sx, sz = w / sh.w * UNITS, h / sh.h * UNITS
	local r2 = hw * hw * 2  -- the collision box's own corner (it stays axis-aligned in the world)
	for i, b in ipairs(sh.boxes) do
		local x0, y0, z0, x1, y1, z1 = b[1] * sx, b[2] * sx, b[3] * sz, b[4] * sx, b[5] * sx, b[6] * sz
		g[i] = { x0, y0, z0, x1, y1, z1, b.head == true }
		for _, cx in ipairs({ x0, x1 }) do
			for _, cy in ipairs({ y0, y1 }) do
				local d = cx * cx + cy * cy
				if d > r2 then r2 = d end
			end
		end
		if z0 < g.zlo then g.zlo = z0 end
		if z1 > g.zhi then g.zhi = z1 end
	end
	g.n = #sh.boxes
	g.r = math.sqrt(r2)
	return g
end

-- cos / sin of the Source yaw a Minecraft yaw (degrees) faces: Source yaw = 270 - MC yaw (convert.lua).
function HB.YawCS(mcYaw)
	local a = math.rad(270 - (mcYaw or 0))
	return math.cos(a), math.sin(a)
end

-- The nearest hit of the ray start + t * delta (world, Source units), t in [0, 1], on g placed at the
-- feet o with the body yaw (c, s). Returns fraction, world normal (x, y, z), box index; nil: no hit.
-- A start inside a box hits at 0 (normal: against the ray), as the collision-box test did.
function HB.RayTest(g, c, s, ox, oy, oz, px, py, pz, dx, dy, dz)
	if g.fixed then c, s = 1, 0 end
	local rx, ry = px - ox, py - oy
	local lx, ly, lz = rx * c + ry * s, -rx * s + ry * c, pz - oz  -- start in the body frame
	local ex, ey, ez = dx * c + dy * s, -dx * s + dy * c, dz       -- delta in the body frame
	local best, bn, bi = 2, 0, 0
	for i = 1, g.n do
		local b = g[i]
		local tmin, tmax, n = 0, 1, 0
		local miss = false
		-- x
		if ex > -1e-9 and ex < 1e-9 then
			if lx < b[1] or lx > b[4] then miss = true end
		else
			local t1, t2, sgn = (b[1] - lx) / ex, (b[4] - lx) / ex, -1
			if t1 > t2 then t1, t2, sgn = t2, t1, 1 end
			if t1 > tmin then tmin, n = t1, sgn end
			if t2 < tmax then tmax = t2 end
			if tmin > tmax then miss = true end
		end
		-- y
		if not miss then
			if ey > -1e-9 and ey < 1e-9 then
				if ly < b[2] or ly > b[5] then miss = true end
			else
				local t1, t2, sgn = (b[2] - ly) / ey, (b[5] - ly) / ey, -2
				if t1 > t2 then t1, t2, sgn = t2, t1, 2 end
				if t1 > tmin then tmin, n = t1, sgn end
				if t2 < tmax then tmax = t2 end
				if tmin > tmax then miss = true end
			end
		end
		-- z
		if not miss then
			if ez > -1e-9 and ez < 1e-9 then
				if lz < b[3] or lz > b[6] then miss = true end
			else
				local t1, t2, sgn = (b[3] - lz) / ez, (b[6] - lz) / ez, -3
				if t1 > t2 then t1, t2, sgn = t2, t1, 3 end
				if t1 > tmin then tmin, n = t1, sgn end
				if t2 < tmax then tmax = t2 end
				if tmin > tmax then miss = true end
			end
		end
		if not miss and tmin < best then best, bn, bi = tmin, n, i end
	end
	if bi == 0 then return nil end
	local nx, ny, nz = 0, 0, 0
	if bn == 1 or bn == -1 then nx, ny = bn * c, bn * s        -- local +-x back to world
	elseif bn == 2 or bn == -2 then
		local k = bn / 2
		nx, ny = -k * s, k * c                                  -- local +-y back to world
	elseif bn == 3 or bn == -3 then nz = bn / 3
	else
		local len = math.sqrt(dx * dx + dy * dy + dz * dz)       -- started inside: against the ray
		if len > 0 then nx, ny, nz = -dx / len, -dy / len, -dz / len end
	end
	return best, nx, ny, nz, bi
end

-- Is the world point p inside (or within pad units of) a box tagged head? (Headshots: the damage position.)
function HB.InHead(g, c, s, ox, oy, oz, px, py, pz, pad)
	if g.fixed then return false end
	pad = pad or 1
	local rx, ry = px - ox, py - oy
	local lx, ly, lz = rx * c + ry * s, -rx * s + ry * c, pz - oz
	for i = 1, g.n do
		local b = g[i]
		if b[7] and lx >= b[1] - pad and lx <= b[4] + pad and ly >= b[2] - pad and ly <= b[5] + pad and lz >= b[3] - pad and lz <= b[6] + pad then
			return true
		end
	end
	return false
end

return HB
