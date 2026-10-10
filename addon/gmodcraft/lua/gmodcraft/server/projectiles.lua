-- Minecraft projectiles hitting GMod entities (v15 kEvProjectileHit, P6h).
--
-- Minecraft reports an arrow, trident, snowball, egg, thrown item or small fireball that hit the
-- collision of a GMod entity (props, doors, breakables, glass: the dynamic layer, kTriDynamic).
-- The entity is found here with a short trace through the hit point along the flight; it takes a
-- DamageInfo (MC damage x gmodcraft_damage_scale, attacker = the shooter's GMod player) and a push
-- at the hit point. Players, NPCs and NextBots are never hit here (Minecraft hits their stand-ins:
-- kEvHitActor), the world and our block entities neither. Each entity takes at most one hit per
-- HIT_INTERVAL seconds (an arrow stream can't flood the physics).

local PJ = gmodcraft.projectiles or {}
gmodcraft.projectiles = PJ
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local CB = gmodcraft.combat
local Log = gmodcraft.Log
local bor = bit.bor

local UNITS = C.UNITS or 40
local TRACE_BACK = 16          -- units before the hit point (MC's copy of a prop can lag GMod's a little)
local TRACE_AHEAD = 24         -- and past it
local HULL = 4                 -- units: the fallback hull trace's half size
local HIT_INTERVAL = 0.1       -- s: at most one hit per entity in this window
local MAX_PUSH_SPEED = 600     -- units/s: the most one hit can add to an object's speed
local THROWN_MIN_MC = 0.2      -- MC damage a thrown item deals at least (x 5 = 1 GMod damage)
local NO_FORCE = DMG_PREVENT_PHYSICS_FORCE or 2048  -- our own push below is the only one

-- Per kind: GMod damage type, and "mass" (kg) x flight speed (units/s) = the push impulse.
local function kinds()
	return {
		[K.ProjArrow or 1] = { name = "arrow", dmg = DMG_BULLET, mass = 1.0 },
		[K.ProjSpectralArrow or 2] = { name = "spectral arrow", dmg = DMG_BULLET, mass = 1.0 },
		[K.ProjTippedArrow or 3] = { name = "tipped arrow", dmg = DMG_BULLET, mass = 1.0 },
		[K.ProjTrident or 4] = { name = "trident", dmg = DMG_CLUB, mass = 3.0 },
		[K.ProjSnowball or 5] = { name = "snowball", dmg = DMG_CLUB, mass = 0.3, thrown = true },
		[K.ProjEgg or 6] = { name = "egg", dmg = DMG_CLUB, mass = 0.3, thrown = true },
		[K.ProjThrown or 7] = { name = "thrown item", dmg = DMG_CLUB, mass = 0.5, thrown = true },
		[K.ProjSmallFireball or 8] = { name = "small fireball", dmg = DMG_BURN, mass = 1.0 },
	}
end
PJ.KINDS = kinds()

PJ.stats = PJ.stats or {}
local stats = PJ.stats
for _, k in ipairs({ "events", "hits", "missed", "skipped", "rateLimited", "badKind" }) do stats[k] = stats[k] or 0 end
PJ.lastHitAt = PJ.lastHitAt or setmetatable({}, { __mode = "k" })

-- The mixin's flight angles (yaw = atan2(v.x, v.z), pitch = atan2(v.y, horiz), degrees) -> a Source
-- direction (same as kEvArrowStuck).
function PJ.Dir(yaw, pitch)
	local y, p = math.rad(tonumber(yaw) or 0), math.rad(tonumber(pitch) or 0)
	local mx, my, mz = math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p)
	return Vector(mx, -mz, my)
end

local function finite(v) v = tonumber(v) or 0 return (v == v and v ~= math.huge and v ~= -math.huge) and v or 0 end

-- Pure: the event -> what to apply (nil, why when it can't be used). amount in GMod damage,
-- impulse in kg*units/s.
function PJ.Params(e)
	local w = tonumber(e.weapon) or 0
	local kind = PJ.KINDS[bit.band(w, K.ProjKindMask or 0xFF)]  -- v28: kind | kProjOnFire
	if not kind then return nil, "unknown kind " .. tostring(e.weapon) end
	local mc = math.max(finite(e.damage), 0)
	if kind.thrown then mc = math.max(mc, THROWN_MIN_MC) end
	local speed = math.Clamp(finite(e.speed), 0, 10) * 20 * UNITS  -- blocks/tick -> units/s
	return {
		kind = kind, mc = mc, amount = mc * CB.cvDamageScale:GetFloat(), type = bor(kind.dmg, NO_FORCE),
		onFire = bit.band(w, K.ProjOnFire or 0x100) ~= 0,
		pos = C.FromMc(finite(e.a), finite(e.b), finite(e.c)), dir = PJ.Dir(finite(e.d), finite(e.pitch)),
		impulse = kind.mass * speed,
	}
end

-- Entities a Minecraft projectile never hits here.
function PJ.Skip(ent)
	if ent == nil or ent == NULL or not IsValid(ent) then return true end
	if ent:IsWorld() or ent:IsPlayer() or ent:IsNPC() or (ent.IsNextBot and ent:IsNextBot()) then return true end
	local c = ent:GetClass()
	return c == "gmodcraft_blocks" or string.sub(c, 1, 17) == "gmodcraft_mcproxy"  -- MC mobs' bodies: Minecraft hits the mob itself
end

-- The GMod entity at the hit point (or nil): a line, then a small hull, through it along the flight.
function PJ.Find(pos, dir, shooter)
	local filter = function(ent)
		if ent == shooter then return false end
		return not PJ.Skip(ent) or ent:IsWorld()
	end
	local q = { start = pos - dir * TRACE_BACK, endpos = pos + dir * TRACE_AHEAD, filter = filter, mask = MASK_SHOT }
	local tr = util.TraceLine(q)
	if not (tr.Hit and not PJ.Skip(tr.Entity)) then
		q.mins, q.maxs = Vector(-HULL, -HULL, -HULL), Vector(HULL, HULL, HULL)
		tr = util.TraceHull(q)
	end
	if tr.Hit and not PJ.Skip(tr.Entity) then return tr.Entity, tr end
	return nil, tr
end

local function attackerFor(steamId)
	if steamId and steamId ~= "0" then
		local ply = gmodcraft.player.BySteamId(steamId)
		if IsValid(ply) then return ply end
	end
	return game.GetWorld()
end

-- Push the hit physics object (the one the trace hit, else every movable one) at the hit point.
local function push(ent, tr, p)
	if p.impulse <= 0 then return false end
	local objs = {}
	local bone = tr and tr.PhysicsBone or 0
	local hitObj = ent:GetPhysicsObjectNum(bone)
	if IsValid(hitObj) then objs[1] = hitObj else
		for i = 0, ent:GetPhysicsObjectCount() - 1 do objs[#objs + 1] = ent:GetPhysicsObjectNum(i) end
	end
	local at = tr and tr.HitPos or p.pos
	local any = false
	for _, phys in ipairs(objs) do
		if IsValid(phys) and phys:IsMotionEnabled() then
			local mass = math.max(phys:GetMass(), 1)
			local impulse = math.min(p.impulse, mass * MAX_PUSH_SPEED)
			phys:Wake()
			phys:ApplyForceOffset(p.dir * impulse, at)
			any = true
		end
	end
	return any
end

PJ.IGNITE_SECONDS = 8

function PJ.OnHit(e)
	stats.events = stats.events + 1
	if not C.slot.known then return end
	local p, why = PJ.Params(e)
	if not p then
		stats.badKind = stats.badKind + 1
		Log("combat", "MC projectile hit dropped: %s", why)
		return
	end
	local att = attackerFor(e.steamId)
	local ent, tr = PJ.Find(p.pos, p.dir, att:IsPlayer() and att or nil)
	if not ent then
		stats.missed = stats.missed + 1
		Log("combat", "MC %s hit at %s: no GMod entity there", p.kind.name, tostring(p.pos))
		return
	end
	local now = CurTime()
	local last = PJ.lastHitAt[ent]
	if last and now - last < HIT_INTERVAL then
		stats.rateLimited = stats.rateLimited + 1
		return
	end
	PJ.lastHitAt[ent] = now
	local class = ent:GetClass()
	local pushed = push(ent, tr, p)  -- first: the damage may break (remove) it
	if p.amount > 0 then
		local dmg = DamageInfo()
		dmg:SetDamage(p.amount)
		dmg:SetDamageType(p.type)
		dmg:SetAttacker(att)
		dmg:SetInflictor(att)
		dmg:SetDamagePosition(tr.HitPos or p.pos)
		dmg:SetDamageForce(p.dir * p.impulse)
		CB.applyingMcHit = true
		local ok, err = pcall(ent.TakeDamageInfo, ent, dmg)
		CB.applyingMcHit = false
		if not ok then gmodcraft.Info("MC projectile hit on %s failed: %s", tostring(ent), tostring(err)) end
	end
	-- v28 (F1): a flame arrow / fire charge sets what it hit on fire (props, ragdolls; NPCs take DMG_BURN)
	if p.onFire and IsValid(ent) and ent.Ignite and not ent:IsOnFire() then
		ent:Ignite(PJ.IGNITE_SECONDS)
		stats.ignited = (stats.ignited or 0) + 1
		gmodcraft.Info("MC burning %s set %s on fire", p.kind.name, class)
	end
	stats.hits = stats.hits + 1
	PJ.last = { t = now, class = class, kind = p.kind.name, mc = p.mc, gmod = p.amount, pushed = pushed, by = att:IsPlayer() and att:Nick() or "world" }
	Log("combat", "MC %s hit %s by %s: %.2f MC = %.1f GMod damage%s", p.kind.name, class, PJ.last.by, p.mc, p.amount, pushed and ", pushed" or "")
end

CB.On(K.EvProjectileHit, "p6h_projectile", PJ.OnHit)
