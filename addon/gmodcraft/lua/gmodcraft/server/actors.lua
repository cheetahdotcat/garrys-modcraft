-- GMod NPCs in Minecraft (P4a, docs/DESIGN.md section 8, D-021).
--
--  * The actor table (server link, authoritative), 20 times a second: every NPC, NextBot and
--    non-MC player within 80 blocks of a linked MC player. Minecraft keeps one invisible hittable
--    proxy per live actor (SkyCombat.sync). Linked players (paired + mapped, either mode) are never
--    actors: their hits come the other way (server/combat.lua).
--  * kEvHitActor: Minecraft hit a proxy. Applied to the real entity as a DamageInfo: attacker = the
--    GMod player with that SteamID (else the world), amount = MC damage x gmodcraft_damage_scale
--    (x the boss factor, off by default), damage type by weapon class (+ DMG_BURN on fire),
--    knockback through FromMc's linear part, crits and strong hits flinch NPCs.
--  * kEvArrowStuck: a clientside arrow on the hit entity (net gmodcraft_arrow), gone on death or
--    after a timeout.
--  * The Actors/Combat debug panel's data (A.DebugTable).

local A = gmodcraft.actors or {}
gmodcraft.actors = A
local CB = gmodcraft.combat
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band, bor = bit.band, bit.bor
local Log = gmodcraft.Log

local RANGE = 80 * 40           -- units: actors within 80 blocks of a linked MC player
local WRITE_INTERVAL = 0.05     -- s: 20 Hz
local LISTED_GRACE = 1.0        -- s: a hit on an actor listed this recently still lands (MC is a tick or two behind)
local KNOCKBACK_SPEED = 800     -- units/s per MC knockback strength (0.4 = 8 blocks/s, MC's sword push)
local FLINCH_STRENGTH = 0.45    -- SkyCraft's stagger rule: d > 0.45 or a crit (D-021)
local LOG_N = 16
local ARROW_RATE = 20           -- arrows per second at most (all players)

CB.cvBossFactor = CB.cvBossFactor or CreateConVar("gmodcraft_boss_factor", "0", FCVAR_ARCHIVE,
	"Garry's Modcraft: Minecraft hits on GMod NPCs with more than 100 max health are multiplied by (max health / 100) ^ this (0 = off)", 0, 1)

A.stats = A.stats or {}
local stats = A.stats
for _, k in ipairs({ "writes", "listed", "hits", "hitsDropped", "kills", "flinches", "arrows", "arrowsDropped" }) do stats[k] = stats[k] or 0 end
A.hitLog = A.hitLog or {}
A.hurtLog = A.hurtLog or {}
A.listed = A.listed or {}      -- entIndex -> { ent, at } of everything written recently
A.last = A.last or {}          -- the last table written (records, for the debug panel)

local function pushLog(list, rec)
	list[#list + 1] = rec
	while #list > LOG_N do table.remove(list, 1) end
end
A.PushLog = pushLog

-- ---- who is an actor -----------------------------------------------------------------------------
local tracked = A.tracked or {}  -- NPCs and NextBots that exist (players come from player.GetAll)
A.tracked = tracked

local function isNextBot(e) return e.IsNextBot ~= nil and e:IsNextBot() end

local function track(e)
	-- B1: never the proxies of Minecraft's own mobs (shared/mcproxy.lua): they'd come back as stand-ins
	if IsValid(e) and (e:IsNPC() or isNextBot(e)) and string.sub(e:GetClass(), 1, 17) ~= "gmodcraft_mcproxy" and not e.gmcMcBody then  -- also their NPC targets (npc_bullseye)
		tracked[e] = true
	end
end
hook.Add("OnEntityCreated", "gmodcraft_actors", function(e)
	-- IsNextBot / the NPC's hull are set up after creation
	timer.Simple(0, function() track(e) end)
end)
hook.Add("EntityRemoved", "gmodcraft_actors", function(e) tracked[e] = nil end)
for _, e in ipairs(ents.GetAll()) do track(e) end

-- A linked player (paired + mapped, either mode) or a paired one is never an actor (D-021: they have
-- Minecraft's health; a proxy would echo their own hits back).
local function isMcSide(ply)
	if CB.Linked(ply) then return true end
	local st = gmodcraft.player.Get(ply)
	return st ~= nil and st.paired == true
end
A.IsMcSide = isMcSide

local npcNames
local function displayName(e)
	if e:IsPlayer() then return e:Nick() end
	local cls = e:GetClass()
	npcNames = npcNames or list.Get("NPC") or {}
	local def = npcNames[cls]
	local name = def and def.Name
	-- the spawn list names are often localisation tokens ("#npc_zombie"): the server can't resolve those
	if not name or name:sub(1, 1) == "#" then return cls end
	return name
end

-- Hostile: D_HT to any linked player (NPCs), an enemy that is a linked player (NextBots).
local function hostility(e, centres)
	local hostile, combat = false, false
	if e:IsNPC() then
		for _, c in ipairs(centres) do
			if e:Disposition(c.ply) == D_HT then hostile = true break end
		end
		combat = e:GetNPCState() == NPC_STATE_COMBAT
	elseif isNextBot(e) and e.GetEnemy then
		local ok, enemy = pcall(e.GetEnemy, e)
		if ok and IsValid(enemy) then
			combat = true
			hostile = enemy:IsPlayer() and CB.Linked(enemy) ~= nil
		end
	end
	return hostile, combat
end

local function record(e, centres)
	local x, y, z = C.ToMc(e:GetPos())
	local mins, maxs = e:OBBMins(), e:OBBMaxs()
	local hp, maxHp = e:Health(), e:GetMaxHealth()
	local dead = hp <= 0 or (e:IsPlayer() and not e:Alive())
	local flags = 0
	if dead then flags = bor(flags, K.ActorDead) end
	if e:IsFlagSet(FL_GODMODE) then flags = bor(flags, K.ActorEssential) end
	if not dead then
		local hostile, combat = hostility(e, centres)
		if hostile then flags = bor(flags, K.ActorHostile) end
		if combat then flags = bor(flags, K.ActorInCombat) end
	end
	return {
		ent = e:EntIndex(), flags = flags, x = x, y = y, z = z, yaw = C.YawToMc(e:GetAngles().y),
		width = math.max(maxs.x - mins.x, maxs.y - mins.y) / 40, height = (maxs.z - mins.z) / 40,
		healthFrac = maxHp > 0 and math.Clamp(hp / maxHp, 0, 1) or (dead and 0 or 1), tier = 0, name = displayName(e),
		class = e:GetClass(),
	}
end

-- The candidates near the linked players, nearest first, at most K.MaxActors.
function A.Collect()
	local centres = {}
	for _, ply in ipairs(player.GetAll()) do
		if CB.Linked(ply) then centres[#centres + 1] = { ply = ply, pos = ply:GetPos() } end
	end
	local out = {}
	if #centres == 0 then return out, centres end
	local function consider(e)
		local p = e:GetPos()
		local best = math.huge
		for _, c in ipairs(centres) do best = math.min(best, p:DistToSqr(c.pos)) end
		if best <= RANGE * RANGE then out[#out + 1] = { e = e, d = best } end
	end
	for e in pairs(tracked) do
		if IsValid(e) then consider(e) else tracked[e] = nil end
	end
	for _, ply in ipairs(player.GetAll()) do
		if not isMcSide(ply) then consider(ply) end
	end
	table.sort(out, function(a, b) return a.d < b.d end)
	for i = #out, (K.MaxActors or 256) + 1, -1 do out[i] = nil end
	return out, centres
end

local nextWrite = 0
local wroteEmpty = false
-- Every tick from CB.Tick; writes at 20 Hz while the link is up and the slot known.
function A.Tick()
	local now = CurTime()
	if now < nextWrite then return end
	nextWrite = now + WRITE_INTERVAL
	local L = gmodcraft.serverLink
	if not L.open or not L.mcAlive or not C.slot.known then
		if L.open and not wroteEmpty then gmodcraft.SetActors({}) wroteEmpty = true end
		A.last = {}
		return
	end
	local cands, centres = A.Collect()
	local list = {}
	for i, c in ipairs(cands) do
		local ok, rec = pcall(record, c.e, centres)
		if ok then
			list[#list + 1] = rec
			A.listed[rec.ent] = { ent = c.e, at = now }
			rec.dist = math.sqrt(c.d) / 40
		end
	end
	if #list == 0 and wroteEmpty then A.last = list return end
	gmodcraft.SetActors(list)
	wroteEmpty = #list == 0
	stats.writes = stats.writes + 1
	stats.listed = #list
	A.last = list
	for idx, l in pairs(A.listed) do
		if now - l.at > LISTED_GRACE * 4 then A.listed[idx] = nil end
	end
end

-- The entity behind an MC event's entId, only if we listed it recently and it is still that
-- entity (EntIndex is reused after a removal), and never an MC-side player.
function A.Target(entId)
	local l = A.listed[entId or -1]
	if not l or CurTime() - l.at > LISTED_GRACE then return nil, "not a listed actor" end
	local e = Entity(entId)
	if not IsValid(e) or e ~= l.ent then return nil, "entity gone or index reused" end
	if e:IsPlayer() and isMcSide(e) then return nil, "an MC player" end
	return e
end

-- ---- hits from Minecraft --------------------------------------------------------------------------
local WEAPON_DMG = {}
local function weaponDmg(weapon)
	if not next(WEAPON_DMG) then
		WEAPON_DMG[K.WeaponUnarmed or 0] = DMG_CLUB
		WEAPON_DMG[K.WeaponBlade or 1] = DMG_SLASH
		WEAPON_DMG[K.WeaponAxe or 2] = DMG_SLASH
		WEAPON_DMG[K.WeaponBlunt or 3] = DMG_CLUB
		WEAPON_DMG[K.WeaponPierce or 4] = bor(DMG_BULLET, DMG_NEVERGIB)
		WEAPON_DMG[K.WeaponArrow or 5] = bor(DMG_BULLET, DMG_NEVERGIB)
	end
	return WEAPON_DMG[weapon] or DMG_CLUB
end

-- The pure part (headless-tested in module/test/combat_test.py): MC hit -> GMod damage amount,
-- damage type, Source knockback velocity and whether it flinches.
function A.HitParams(e, maxHealth)
	local scale = CB.cvDamageScale:GetFloat()
	local boss = 1
	local bf = CB.cvBossFactor:GetFloat()
	if bf > 0 and (maxHealth or 0) > 100 then boss = (maxHealth / 100) ^ bf end
	local mc = math.max(0, tonumber(e.a) or 0)
	if mc ~= mc or mc == math.huge then mc = 0 end
	local flags = math.floor(tonumber(e.flags) or 0)
	local weapon = math.floor(tonumber(e.weapon) or 0)
	local dtype = weaponDmg(weapon)
	if band(flags, K.HitFire or 8) ~= 0 then
		-- fire with no weapon behind it (burning, lava) is just burn; a fire-aspect sword or a
		-- flaming arrow is its weapon's type plus burn
		local weaponless = weapon == (K.WeaponUnarmed or 0) and band(flags, K.HitProjectile or 2) == 0
		dtype = weaponless and DMG_BURN or bor(dtype, DMG_BURN)
	end
	local d = tonumber(e.d) or 0
	if d ~= d then d = 0 end
	local bx, bz = tonumber(e.b) or 0, tonumber(e.c) or 0
	if bx ~= bx or bz ~= bz then bx, bz = 0, 0 end
	local vel = Vector(bx * d * KNOCKBACK_SPEED, -bz * d * KNOCKBACK_SPEED, 0)  -- FromMc's linear part: (x, y, z) -> (x, -z, y)
	local crit = band(flags, K.HitCritical or 1) ~= 0
	return {
		mc = mc, scale = scale, boss = boss, amount = mc * scale * boss, type = dtype, vel = vel, d = d,
		flinch = crit or d > FLINCH_STRENGTH, crit = crit, flags = flags,
	}
end

local function attackerFor(steamId)
	if steamId and steamId ~= "0" then
		local ply = gmodcraft.player.BySteamId(steamId)
		if IsValid(ply) then return ply end
	end
	return game.GetWorld()
end

local function onHitActor(e)
	local target, why = A.Target(e.ent)
	if not target then
		stats.hitsDropped = stats.hitsDropped + 1
		Log("combat", "MC hit on #%s dropped: %s", tostring(e.ent), why)
		return
	end
	local p = A.HitParams(e, target:GetMaxHealth())
	local att = attackerFor(e.steamId)
	local hpBefore = target:Health()
	if p.amount > 0 then
		local dmg = DamageInfo()
		dmg:SetDamage(p.amount)
		dmg:SetDamageType(p.type)
		dmg:SetAttacker(att)
		dmg:SetInflictor(att)
		dmg:SetDamagePosition(target:WorldSpaceCenter())
		local dir = p.vel:LengthSqr() > 1e-6 and p.vel:GetNormalized() or (att ~= game.GetWorld() and (target:GetPos() - att:GetPos()):GetNormalized() or Vector(0, 0, 0))
		dmg:SetDamageForce(dir * (p.amount * 300 + p.d * 4000))
		-- EntityTakeDamage (server/combat.lua) drops anything on an MC player while this is set:
		-- an MC hit can never travel back to Minecraft as a hurt.
		CB.applyingMcHit = true
		local ok, err = pcall(target.TakeDamageInfo, target, dmg)
		CB.applyingMcHit = false
		if not ok then gmodcraft.Info("MC hit on %s failed: %s", tostring(target), tostring(err)) end
	end
	local alive = IsValid(target) and target:Health() > 0
	if alive and p.d > 0 then
		if target:IsPlayer() then
			target:SetVelocity(p.vel + Vector(0, 0, 100 * math.min(p.d, 1)))
		elseif isNextBot(target) and target.loco then
			target.loco:SetVelocity(target.loco:GetVelocity() + p.vel)
		elseif target:IsNPC() then
			target:SetVelocity(p.vel)
		end
	end
	if alive and p.flinch and target:IsNPC() then
		target:SetSchedule(SCHED_BIG_FLINCH or SCHED_FLINCH_PHYSICS)
		stats.flinches = stats.flinches + 1
	end
	stats.hits = stats.hits + 1
	local hpAfter = IsValid(target) and target:Health() or 0
	if hpBefore > 0 and hpAfter <= 0 then stats.kills = stats.kills + 1 end
	local rec = {
		t = CurTime(), ent = e.ent, class = IsValid(target) and target:GetClass() or "?", by = att:IsPlayer() and att:Nick() or "world",
		mc = p.mc, scale = p.scale, boss = p.boss, gmod = p.amount, type = p.type, weapon = e.weapon, flags = p.flags, d = p.d,
		flinch = p.flinch, hpBefore = hpBefore, hpAfter = hpAfter,
	}
	pushLog(A.hitLog, rec)
	A.lastHit = rec
	Log("combat", "MC hit %s #%d by %s: %.2f MC x %.2f x %.2f = %.1f GMod (type 0x%x, knockback %.2f%s): health %d -> %d", rec.class, e.ent,
		rec.by, p.mc, p.scale, p.boss, p.amount, p.type, p.d, p.flinch and ", flinch" or "", hpBefore, hpAfter)
end
CB.On(K.EvHitActor, "p4a_hit", onHitActor)

-- ---- arrows stuck in actors ---------------------------------------------------------------------
A.arrowsOn = A.arrowsOn or {}
local arrowWindow, arrowCount = 0, 0

-- The mixin's flight angles (AbstractArrowMixin): yaw = atan2(v.x, v.z), pitch = atan2(v.y, horiz),
-- both in degrees -> the flight direction in Source.
function A.ArrowDir(yaw, pitch)
	local y, p = math.rad(tonumber(yaw) or 0), math.rad(tonumber(pitch) or 0)
	local mx, my, mz = math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p)
	return Vector(mx, -mz, my)
end

local function onArrowStuck(e)
	local target = A.Target(e.ent)
	if not target or not C.slot.known then
		stats.arrowsDropped = stats.arrowsDropped + 1
		return
	end
	local now = CurTime()
	if now - arrowWindow >= 1 then arrowWindow, arrowCount = now, 0 end
	if arrowCount >= ARROW_RATE then
		stats.arrowsDropped = stats.arrowsDropped + 1
		return
	end
	arrowCount = arrowCount + 1
	local pos = C.FromMc(e.a, e.b, e.c)
	local dir = A.ArrowDir(e.d, e.pitch)
	net.Start(gmodcraft.NET.arrow)
	net.WriteBool(true)
	net.WriteEntity(target)
	net.WriteVector(pos)
	net.WriteNormal(dir)
	net.WriteUInt(math.Clamp(math.floor(tonumber(e.weapon) or 0), 0, 3), 2)
	net.Broadcast()
	A.arrowsOn[target] = true
	stats.arrows = stats.arrows + 1
	Log("combat", "arrow stuck in %s #%d at %s", target:GetClass(), e.ent, tostring(pos))
end
CB.On(K.EvArrowStuck, "p4a_arrow", onArrowStuck)

local function clearArrows(ent)
	if not A.arrowsOn[ent] then return end
	A.arrowsOn[ent] = nil
	net.Start(gmodcraft.NET.arrow)
	net.WriteBool(false)
	net.WriteEntity(ent)
	net.Broadcast()
end
hook.Add("OnNPCKilled", "gmodcraft_actors", function(npc) clearArrows(npc) end)
hook.Add("PlayerDeath", "gmodcraft_actors", function(ply) clearArrows(ply) end)
hook.Add("EntityRemoved", "gmodcraft_actors_arrows", function(e) A.arrowsOn[e] = nil end)

-- ---- debug panel data ------------------------------------------------------------------------------
function A.DebugTable()
	local actors = {}
	for i, r in ipairs(A.last or {}) do
		if i > 48 then break end
		actors[i] = { ent = r.ent, class = r.class, name = r.name, flags = r.flags, hp = r.healthFrac, dist = r.dist, w = r.width, h = r.height }
	end
	return {
		actors = actors, count = #(A.last or {}), hits = A.hitLog, hurts = A.hurtLog, stats = stats, cb = CB.stats,
		scale = CB.cvDamageScale:GetFloat(), boss = CB.cvBossFactor:GetFloat(),
	}
end
