-- X1: player view rays stop on Minecraft blocks (server realm).
-- Minecraft blocks exist on the GMod server only as box entities / physics regions near props, NPCs and
-- non-MC players (server/blockcol.lua, server/physworld.lua), so nothing solid lies along a player's view:
-- the toolgun, GetEyeTrace and prop spawning (sandbox CCSpawn) went through the Minecraft world.
--
-- util.TraceLine / util.TraceHull are wrapped. A trace counts as a player VIEW RAY when
--   * its table came from util.GetPlayerTrace (wrapped: tags it gmc_blocks = true; the toolgun's
--     DoToolTrace, Player:GetEyeTrace), or
--   * its filter is a player, or a plain table whose first entry is a player (sandbox's GetSpawnTrace uses
--     { ply, ply:GetVehicle() }), and its start is that player's GetShootPos().
-- Only a view ray asks the module (gmodcraft.BlockRay: section DDA over the block store's boxes); when a
-- block lies closer than the engine's hit, the result becomes a world hit there. Every other trace pays a
-- field read or two. A hull view ray is tested as a line (the toolgun's hull has zero extents).
-- Not covered: the client realm (no block store there: the toolgun ghost floats through blocks) and the
-- physgun beam (engine C++).
local BT = gmodcraft.blocktrace or {}
gmodcraft.blocktrace = BT

-- Originals kept once: a Lua refresh re-runs this file and must not wrap the wrappers.
BT.orig = BT.orig or { line = util.TraceLine, hull = util.TraceHull, player = util.GetPlayerTrace }
local origLine, origHull, origPlayer = BT.orig.line, BT.orig.hull, BT.orig.player
BT.stats = BT.stats or { rays = 0, hits = 0 }
local stats = BT.stats

local PLAYER = FindMetaTable("Player")
local getmetatable, type = getmetatable, type

-- Is trace table tr a player view ray?
function BT.IsViewRay(tr)
	if tr.gmc_blocks then return true end
	local f = tr.filter
	if f == nil then return false end
	local mt = getmetatable(f)
	if mt ~= PLAYER then
		if type(f) ~= "table" or mt ~= nil then return false end
		f = f[1]
		if f == nil or getmetatable(f) ~= PLAYER then return false end
	end
	local st = tr.start
	if not st or not IsValid(f) then return false end
	return st:DistToSqr(f:GetShootPos()) < 0.01
end

-- res: the engine's result (the caller's output table when given). Rewritten in place when a Minecraft
-- block lies closer along tr.start -> tr.endpos.
function BT.Apply(tr, res)
	local L = gmodcraft.serverLink
	local s = L and L.slot
	local ray = gmodcraft.BlockRay
	if not s or not ray or not res then return res end
	local st, en = tr.start, tr.endpos
	if not st or not en then return res end
	stats.rays = stats.rays + 1
	local f, nx, ny, nz = ray(st.x, st.y, st.z, en.x, en.y, en.z, s.ox, s.oz, s.oy or 0)
	if not f then return res end
	if res.Hit and (res.Fraction or 1) <= f then return res end  -- the engine's hit is closer
	stats.hits = stats.hits + 1
	res.HitPos = st + (en - st) * f
	res.HitNormal = Vector(nx, ny, nz)
	res.Normal = (en - st):GetNormalized()
	res.Fraction = f
	res.Hit, res.HitWorld, res.HitNonWorld = true, true, false
	res.Entity = game.GetWorld()
	res.StartSolid, res.AllSolid = false, false
	res.FractionLeftSolid = 0
	res.MatType = 0
	res.HitSky, res.HitNoDraw = false, false
	res.HitGroup, res.HitBox, res.PhysicsBone = 0, 0, 0
	return res
end

local IsViewRay, Apply = BT.IsViewRay, BT.Apply

function util.GetPlayerTrace(...)
	local tr = origPlayer(...)
	if tr then tr.gmc_blocks = true end
	return tr
end

function util.TraceLine(tr)
	local res = origLine(tr)
	if not IsViewRay(tr) then return res end
	return Apply(tr, tr.output or res)
end

function util.TraceHull(tr)
	local res = origHull(tr)
	if not IsViewRay(tr) then return res end
	return Apply(tr, tr.output or res)
end
