-- Combat on the GMod server (docs/DESIGN.md section 8, D-021).
--
--  * Dispatch: MC events other than teleport acks come here from PL.OnEvent; each event type has
--    handlers registered with CB.On(type, name, fn) (P4b: player deaths, respawns, explosions; P4a
--    adds hits on GMod NPCs).
--  * MC players in GMod (P4b): a player paired with a mapped Minecraft player always has Minecraft's
--    health and armour, in GMod mode too. GMod damage on them is cancelled and sent to Minecraft
--    as kHostEvHurt with the raw GMod amount (Minecraft divides by its own config); falls,
--    drowning and world crushes are dropped (Minecraft does those itself). GMod's health mirrors
--    Minecraft's. Minecraft's death kills the GMod player, Minecraft's respawn spawns it again, and
--    GMod's own respawn waits while the link is up. Console `kill` dies in Minecraft.
--  * Explosions from Minecraft: physics impulses only (Minecraft already damaged its proxies and
--    players): never util.BlastDamage.

local CB = gmodcraft.combat or {}
gmodcraft.combat = CB
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band, bor = bit.band, bit.bor
local Log = gmodcraft.Log

-- MC -> GMod damage factor (P4a multiplies MC hits by it); also the health mirror's factor
-- (20 MC health = 100 GMod health). Minecraft divides GMod hurts by its own config value.
CB.cvDamageScale = CB.cvDamageScale or CreateConVar("gmodcraft_damage_scale", "5", FCVAR_ARCHIVE,
	"Garry's Modcraft: Minecraft damage x this = GMod damage; also GMod health = Minecraft health x this", 0.1, 100)

-- v34 (control centre, the Server page's "Mobs" rules; GMod-side, so GMod convars): whether GMod NPCs fight
-- hostile Minecraft mobs, and a factor on GMod damage that reaches Minecraft mobs (mcproxy's hurts).
CB.cvNpcVsMobs = CB.cvNpcVsMobs or CreateConVar("gmodcraft_npc_vs_mobs", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: 1 = GMod NPCs fight hostile Minecraft mobs, 0 = they ignore them", 0, 1)
CB.cvMobDamage = CB.cvMobDamage or CreateConVar("gmodcraft_mob_damage_scale", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: GMod damage on Minecraft mobs x this (before Minecraft's own damage scale)", 0, 100)

-- The disposition a GMod NPC takes towards a Minecraft mob's proxy (mcproxy's relate).
function CB.NpcMobDisposition(hostile)
	return (hostile and CB.cvNpcVsMobs:GetBool()) and D_HT or D_NU
end

-- The factor on GMod damage that reaches a Minecraft mob (finite, 0 .. 100).
function CB.MobDamageScale()
	local f = CB.cvMobDamage:GetFloat()
	if f ~= f or f < 0 then return 0 end
	return math.min(f, 100)
end

-- The relation changed: every NPC takes the new one towards the proxies there are (new pairs use it anyway).
if cvars and cvars.AddChangeCallback then cvars.AddChangeCallback("gmodcraft_npc_vs_mobs", function()
	local MP = gmodcraft.mcproxy
	if not (MP and MP.byId) then return end
	local npcs = {}
	for _, e in ipairs(ents.GetAll()) do if e:IsNPC() and not e.gmcMcBody then npcs[#npcs + 1] = e end end
	for _, p in pairs(MP.byId) do
		if IsValid(p) and IsValid(p.npc) then
			local d = CB.NpcMobDisposition(p:GetMcCategory() == (K.McEntHostile or 1))
			for _, npc in ipairs(npcs) do pcall(npc.AddEntityRelationship, npc, p.npc, d, 50) end
		end
	end
end, "gmodcraft_combat") end

local KILL_DAMAGE = 1000           -- GMod damage a GMod-side death (console kill) deals in Minecraft
local SPAWN_AFTER_DEATH = 0.75     -- s: earliest Spawn() after our Kill() (immediate respawn sends both at once)
local GMOD_RESPAWN_FALLBACK = 5    -- s: GMod-dead but Minecraft alive this long: GMod's own respawn may run
local EXPLOSION_MAX_ENTS = 256
local UNITS = C.UNITS or 40

CB.handlers = CB.handlers or {}
CB.stats = CB.stats or {}
local stats = CB.stats
for _, k in ipairs({ "forwarded", "dropped", "pushFailed", "kills", "spawns", "suicides", "gmodDeaths", "explosions", "pushedEnts" }) do
	stats[k] = stats[k] or 0
end

-- ---- dispatch ------------------------------------------------------------------------------------
-- CB.On(K.EvSomething, "name", fn(e)): one handler per (type, name); a reload replaces it.
function CB.On(evType, name, fn)
	if not evType then return end
	local list = CB.handlers[evType] or {}
	CB.handlers[evType] = list
	list[name] = fn
end

function CB.Dispatch(e)
	local list = CB.handlers[e.type]
	if not list or next(list) == nil then
		Log("combat", "MC event type %s: no handler", tostring(e.type))
		return
	end
	for name, fn in pairs(list) do
		local ok, err = pcall(fn, e)
		if not ok then gmodcraft.Info("combat handler %s for MC event %s failed: %s", name, tostring(e.type), tostring(err)) end
	end
end

-- ---- who is linked -------------------------------------------------------------------------------
local function PL() return gmodcraft.player end

-- The player's state if Minecraft owns its health: paired (identity, live link, slot) and mapped
-- on the MC server. Paired but not mapped yet: GMod damage stays GMod's (Minecraft would drop the
-- hurt: no Minecraft player plays as them).
function CB.Linked(ply)
	local st = PL().Get(ply)
	if st and st.paired and st.mapped and gmodcraft.serverLink.mcAlive then return st end
	return nil
end

local function mcPlayer(ply)
	local list = PL().mcPlayersBySteamId
	local sid = ply:SteamID64()
	return list and sid and list[sid] or nil
end

local function pushHurt(ply, kind, amount, attacker, why)
	local ok = gmodcraft.PushHostEvent({
		type = K.HostEvHurt, code = kind, steamId = ply:SteamID64(), ent = attacker or 0, flags = 0,
		a = math.min(math.floor(amount * 100 + 0.5), 2147483647),
	})
	if not ok then
		stats.pushFailed = stats.pushFailed + 1
		gmodcraft.Info("%s: hurt %.1f (%s) not sent: host event ring full or closed", ply:Nick(), amount, why)
	end
	Log("combat", "%s: hurt %.1f (%s, kind %d, attacker %d)", ply:Nick(), amount, why, kind, attacker or 0)
	return ok
end

-- ---- GMod damage on MC players ---------------------------------------------------------------------
local MAGIC = bor(DMG_SHOCK or 0, DMG_ENERGYBEAM or 0, DMG_DISSOLVE or 0, DMG_PLASMA or 0)
local PROJECTILE = bor(DMG_BULLET or 0, DMG_BUCKSHOT or 0, DMG_SNIPER or 0, DMG_AIRBOAT or 0)
CB.PROJECTILE = PROJECTILE  -- also the headshot gate on MC entity proxies (shared/mcproxy.lua)
local MELEE = bor(DMG_SLASH or 0, DMG_CLUB or 0)
local DROPPED = bor(DMG_FALL or 0, DMG_DROWN or 0, DMG_DROWNRECOVER or 0)

-- The routing decision (pure; module/test-style headless check in docs/spikes/p4b): GMod damage
-- type bits and whether the inflictor is the world (or our block entities) -> HurtKind and a
-- label, or nil and why it is dropped. DMG_GENERIC is 0: it lands in "other".
function CB.Route(dmgType, fromWorld)
	if band(dmgType, DROPPED) ~= 0 then return nil, "fall/drown: Minecraft does it" end
	if band(dmgType, DMG_CRUSH or 0) ~= 0 and fromWorld then return nil, "world crush: Minecraft does it" end
	if band(dmgType, MAGIC) ~= 0 then return K.HurtMagic, "magic" end
	if band(dmgType, PROJECTILE) ~= 0 then return K.HurtProjectile, "projectile" end
	if band(dmgType, MELEE) ~= 0 then return K.HurtMelee, "melee" end
	return K.HurtOther, "other"
end

-- The Actors/Combat panel's last hurts (server/actors.lua keeps the list): GMod's raw amount and
-- what Minecraft makes of it (÷ its own config, assumed equal to gmodcraft_damage_scale).
local function logHurt(ply, amount, kind, dtype, att, why)
	local A = gmodcraft.actors
	if not A or not A.PushLog then return end
	local valid = att ~= nil and att ~= NULL and IsValid(att)
	A.PushLog(A.hurtLog, { t = CurTime(), who = ply:Nick(), gmod = amount, mc = amount / CB.cvDamageScale:GetFloat(), kind = kind, type = dtype,
		by = valid and (att:IsPlayer() and att:Nick() or att:GetClass()) or "world", why = why })
end

-- The world entity is not IsValid, but its methods work; NULL is neither.
local function isWorldish(e)
	if e == nil or e == NULL then return true end
	if e:IsWorld() then return true end
	return e:GetClass() == "gmodcraft_blocks"
end

hook.Add("EntityTakeDamage", "gmodcraft_combat", function(target, dmg)
	if not target:IsPlayer() then return end
	local st = CB.Linked(target)
	if not st or st.killBypass then return end
	local amount = dmg:GetDamage()
	local dtype = dmg:GetDamageType()
	dmg:SetDamage(0)
	if not target:Alive() then return true end
	if CB.applyingMcHit then
		-- server/actors.lua is applying a Minecraft hit: it never reaches an MC player, and if it
		-- somehow did, it must not go back to Minecraft as a hurt (no loop)
		stats.dropped = stats.dropped + 1
		Log("combat", "%s: MC hit %.1f on an MC player dropped", target:Nick(), amount)
		return true
	end
	local kind, why = CB.Route(dtype, isWorldish(dmg:GetInflictor()))
	if not kind then
		stats.dropped = stats.dropped + 1
		Log("combat", "%s: GMod damage %.1f type 0x%x dropped (%s)", target:Nick(), amount, dtype, why)
		return true
	end
	if amount <= 0 then return true end
	local att = dmg:GetAttacker()
	local attIdx = (att ~= nil and att ~= NULL and not att:IsWorld()) and att:EntIndex() or 0
	stats.forwarded = stats.forwarded + 1
	stats.lastHurt = { amount = amount, kind = kind, type = dtype, attacker = attIdx, at = CurTime() }
	logHurt(target, amount, kind, dtype, att, why)
	pushHurt(target, kind, amount, attIdx, why)
	return true  -- cancelled: Minecraft applies it (armour, shield, i-frames) and the mirror follows
end)

-- GMod's own fall damage never applies to a linked player. In MC mode Minecraft does falls itself
-- (it moves the player, so it knows the real fall). In GMod mode Minecraft only follows by teleport
-- (which resets its fall distance), so the landing here is sent as a hurt with Minecraft's rule:
-- (fall height in blocks - 3) MC damage, height from the landing speed (v^2 / 2g).
hook.Add("GetFallDamage", "gmodcraft_combat", function(ply)
	if CB.Linked(ply) then return 0 end
end)

local SAFE_FALL_BLOCKS = 3
-- Landing speed (units/s, positive down) -> MC fall damage (pure; combat_test.py).
function CB.FallDamageMc(speed, gravity)
	speed, gravity = math.abs(tonumber(speed) or 0), tonumber(gravity) or 600
	if gravity <= 0 then return 0 end
	local blocks = speed * speed / (2 * gravity) / UNITS
	return math.max(0, math.ceil(blocks - SAFE_FALL_BLOCKS - 1e-6))
end

local cvGravity
hook.Add("OnPlayerHitGround", "gmodcraft_combat", function(ply, inWater, onFloater, speed)
	local st = CB.Linked(ply)
	if not st or st.mcMode or inWater or not ply:Alive() then return end
	cvGravity = cvGravity or GetConVar("sv_gravity")
	local mc = CB.FallDamageMc(speed, cvGravity and cvGravity:GetFloat() or 600)
	if mc <= 0 then return end
	local amount = mc * CB.cvDamageScale:GetFloat()  -- raw GMod damage: Minecraft divides by its config
	stats.falls = (stats.falls or 0) + 1
	stats.lastFall = { speed = speed, mc = mc, at = CurTime() }
	logHurt(ply, amount, K.HurtOther, DMG_FALL or 32, nil, "fall (GMod mode)")
	pushHurt(ply, K.HurtOther, amount, 0, "fall (GMod mode)")
end)

-- Console `kill` / `explode`: die in Minecraft; its death event kills the GMod player.
hook.Add("CanPlayerSuicide", "gmodcraft_combat", function(ply)
	local st = CB.Linked(ply)
	if not st or st.killBypass then return end
	if not ply:Alive() then return false end
	stats.suicides = stats.suicides + 1
	gmodcraft.Info("%s: kill in GMod: dying in Minecraft", ply:Nick())
	pushHurt(ply, K.HurtOther, KILL_DAMAGE, 0, "suicide")
	return false
end)

-- ---- deaths and respawns ---------------------------------------------------------------------------
-- A GMod-side death we didn't cause (another addon's Kill, KillSilent, a death that got past the
-- damage hook): Minecraft follows, so the two can't stay apart. Our own Kill() has the bypass flag.
local function onGmodDeath(ply)
	local st = PL().Get(ply)
	if not st then return end
	st.gmodDeadAt = CurTime()
	if st.killBypass or not CB.Linked(ply) then return end
	local mp = mcPlayer(ply)
	if mp and band(mp.flags, K.McPlayerDead or 0) ~= 0 then return end  -- already dead there
	stats.gmodDeaths = stats.gmodDeaths + 1
	gmodcraft.Info("%s died in GMod: dying in Minecraft too", ply:Nick())
	pushHurt(ply, K.HurtOther, KILL_DAMAGE, 0, "GMod death")
end
hook.Add("PlayerDeath", "gmodcraft_combat", onGmodDeath)
hook.Add("PlayerSilentDeath", "gmodcraft_combat", onGmodDeath)

-- GMod's own respawn waits while Minecraft owns the player: Minecraft's respawn spawns it
-- (kEvPlayerRespawned). Falls back to GMod's respawn when the link is down, or when Minecraft has
-- stayed alive for a while after a GMod death (a hurt it shrugged off: creative, a totem).
hook.Add("PlayerDeathThink", "gmodcraft_combat", function(ply)
	local st = CB.Linked(ply)
	if not st then return end
	-- Either way (Minecraft alive, or dead too but without a respawn of its own: no immediate
	-- respawn and its death screen waiting), after the fallback time GMod's respawn runs; PlayerSpawn
	-- then sends kHostEvRespawn, which respawns Minecraft's player too.
	if not st.spawnAt and st.gmodDeadAt and CurTime() - st.gmodDeadAt > GMOD_RESPAWN_FALLBACK then return end
	return true
end)

local function onMcDied(e)
	local ply, st = PL().BySteamId(e.steamId)
	if not ply then return end
	if not ply:Alive() then
		Log("combat", "%s died in Minecraft (already dead in GMod)", ply:Nick())
		return
	end
	stats.kills = stats.kills + 1
	gmodcraft.Info("%s died in Minecraft (attacker %d): killing the GMod player", ply:Nick(), e.ent or 0)
	st.killBypass = true
	local ok, err = pcall(ply.Kill, ply)
	st.killBypass = false
	st.gmodDeadAt = CurTime()
	st.mcKilledAt = CurTime()
	if not ok then gmodcraft.Info("%s: Kill failed: %s", ply:Nick(), tostring(err)) end
end

local function onMcRespawned(e)
	local ply, st = PL().BySteamId(e.steamId)
	if not ply then return end
	if ply:Alive() then
		-- Minecraft respawned on its own while GMod is alive: GMod decides where it goes.
		PL().RequestTeleport(ply, "respawn")
		return
	end
	-- Spawn() picks a GMod spawn point; PlayerSpawn then sends kHostEvRespawn (just a teleport now).
	st.spawnAt = math.max(CurTime(), (st.mcKilledAt or 0) + SPAWN_AFTER_DEATH)
	Log("combat", "%s respawned in Minecraft: GMod spawn in %.2f s", ply:Nick(), st.spawnAt - CurTime())
end

CB.On(K.EvPlayerDied, "p4b_death", onMcDied)
CB.On(K.EvPlayerRespawned, "p4b_respawn", onMcRespawned)

-- ---- explosions -------------------------------------------------------------------------------------
-- SkyCraft's ApplyExplosion (reference/skse/src/Combat.cpp): reach = 2 x radius blocks, power =
-- radius / 4 (TNT = 1), linear falloff, out and up at 14 blocks/s x falloff x power. Loose physics
-- objects only (props, ragdolls, physics bones); players, NPCs and NextBots are Minecraft's to
-- damage (proxies), frozen objects stay frozen.
local function onExplosion(e)
	local radius = tonumber(e.d) or 0
	if not C.slot.known or radius ~= radius or radius <= 0 or radius > 64 then return end
	local centre = C.FromMc(e.a, e.b, e.c)
	local reach = radius * 2 * UNITS
	local power = radius / 4
	local moved, seen = 0, 0
	for _, ent in ipairs(ents.FindInSphere(centre, reach)) do
		if IsValid(ent) and not ent:IsPlayer() and not ent:IsNPC() and not (ent.IsNextBot and ent:IsNextBot())
			and ent:GetMoveType() == MOVETYPE_VPHYSICS and ent:GetClass() ~= "gmodcraft_blocks"
			and (string.sub(ent:GetClass(), 1, 17) ~= "gmodcraft_mcproxy" or (ent.gmcPhysBlock and ent.own)) then  -- MC mobs: Minecraft's explosion moves them there (v44: not the falling blocks / TNT GMod simulates)
			-- the cap counts pushable objects only (a crowd of NPCs or brushes can't starve the props)
			seen = seen + 1
			if seen > EXPLOSION_MAX_ENTS then break end
			local pos = ent:WorldSpaceCenter()
			local dist = pos:Distance(centre)
			local falloff = math.Clamp(1 - dist / reach, 0, 1)
			if falloff > 0 then
				local dir = pos - centre
				dir.z = dir.z + 0.5 * dist + 30  -- out and up
				if dir:LengthSqr() > 1e-6 then
					dir:Normalize()
					local speed = 14 * UNITS * falloff * power  -- units/s
					local any = false
					for i = 0, ent:GetPhysicsObjectCount() - 1 do
						local phys = ent:GetPhysicsObjectNum(i)
						if IsValid(phys) and phys:IsMotionEnabled() then
							phys:Wake()
							phys:ApplyForceCenter(dir * (speed * phys:GetMass()))
							any = true
						end
					end
					if any then moved = moved + 1 end
				end
			end
		end
	end
	stats.explosions = stats.explosions + 1
	stats.pushedEnts = stats.pushedEnts + moved
	stats.lastExplosion = { centre = centre, radius = radius, moved = moved, at = CurTime() }
	Log("combat", "Minecraft explosion at %s, radius %.1f blocks: %d loose objects pushed", tostring(centre), radius, moved)
end
CB.On(K.EvExplosion, "p4b_explosion", onExplosion)

-- ---- every tick (after PL.Tick) ----------------------------------------------------------------------
-- The health mirror and the delayed spawns.
function CB.Tick()
	local now = CurTime()
	local scale = CB.cvDamageScale:GetFloat()
	if gmodcraft.actors then gmodcraft.actors.Tick() end  -- the actor table at 20 Hz (server/actors.lua)
	for _, ply in ipairs(player.GetAll()) do
		local st = PL().Get(ply)
		if st then
			local linked = CB.Linked(ply) ~= nil
			if st.spawnAt and now >= st.spawnAt then
				st.spawnAt = nil
				if not ply:Alive() and linked then
					stats.spawns = stats.spawns + 1
					gmodcraft.Info("%s: Minecraft respawned: spawning in GMod", ply:Nick())
					ply:Spawn()
				end
			end
			local mp = linked and mcPlayer(ply) or nil
			if mp and ply:Alive() and mp.maxHealth and mp.maxHealth > 0 then
				-- At least 1 while alive: Minecraft's death event does the killing.
				local hp = math.max(1, math.ceil(mp.health * scale - 1e-3))
				local maxHp = math.max(1, math.floor(mp.maxHealth * scale + 0.5))
				if ply:GetMaxHealth() ~= maxHp then ply:SetMaxHealth(maxHp) end
				if ply:Health() ~= hp then ply:SetHealth(hp) end
				st.healthMirrored = true
			elseif st.healthMirrored and not linked then
				st.healthMirrored = false
				ply:SetMaxHealth(100)
				if ply:Health() > 100 then ply:SetHealth(100) end
			end
		end
	end
end
