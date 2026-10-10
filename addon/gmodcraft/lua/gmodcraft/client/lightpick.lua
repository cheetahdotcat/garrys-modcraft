-- GMod light for one 4x4x4-block cell from its candidate points (P3b): pure logic, no engine calls,
-- so module/test/light_pick_test.py runs it headless with stubbed contents and light.
--
--   gmodcraft.lightPick.Pick(buf, base, k, isSolid, lightAt) -> lum | nil
--
-- buf[base + 1 .. base + 3 * k] are the candidate points (x, y, z; the module's BlocksLightQuery
-- order: the cell's face directions' points, then the cell centre). Points inside a brush (isSolid)
-- are skipped; the brightest of the rest wins. If every one is inside, each is moved 40 then 80
-- units along the six axes and the brightest free spot wins. nil: everything enclosed (the caller
-- uses full brightness, not black: P3b run 2 showed planks along a thick wall going black).
local P = {}

local STEPS = { 40, 80 }
local AXES = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } }

function P.Pick(buf, base, k, isSolid, lightAt)
	local best
	for i = 0, k - 1 do
		local o = base + i * 3
		local x, y, z = buf[o + 1], buf[o + 2], buf[o + 3]
		if not isSolid(x, y, z) then
			local l = lightAt(x, y, z)
			if not best or l > best then best = l end
		end
	end
	if best then return best end
	for _, step in ipairs(STEPS) do
		for i = 0, k - 1 do
			local o = base + i * 3
			for _, a in ipairs(AXES) do
				local x, y, z = buf[o + 1] + a[1] * step, buf[o + 2] + a[2] * step, buf[o + 3] + a[3] * step
				if not isSolid(x, y, z) then
					local l = lightAt(x, y, z)
					if not best or l > best then best = l end
				end
			end
		end
		if best then return best end
	end
	return nil
end

gmodcraft = gmodcraft or {}
gmodcraft.lightPick = P
return P
