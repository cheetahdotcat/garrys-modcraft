-- Physics blocks (protocol v44), the GMod server's half besides the bodies (shared/mcproxy.lua makes falling
-- blocks and primed TNT GMod physics while they fly). Server only.
--
--   gravgun pull   a player holding the gravgun's pull (secondary) on a Minecraft block sends
--                  kHostEvPullBlock: Minecraft detaches it as a falling block with a push toward the player,
--                  its body appears GMod's a tick later and the still-pulling gravgun takes it. Minecraft
--                  decides (its break rules for that player's Minecraft player; admins only without one;
--                  never mirror blocks, bedrock, block entities). Convar gmodcraft_gravgun_blocks.
--   blasts         GMod explosions send kHostEvBlast (centre, power): Minecraft breaks blocks there like its
--                  own explosion of that power, blocks only (GMod's blast already hurt and pushed things),
--                  under its rules (tnt_explodes, map carving). Seen through env_explosion's Explode input
--                  (explosive barrels, the RPG, SMG grenades, map explosions), util.BlastDamage /
--                  BlastDamageInfo (Lua weapons), a frag grenade going off, an exploding prop breaking and,
--                  as a fallback, blast damage on any entity. Sources of one explosion are merged (one
--                  tick, within MERGE_DIST: the strongest estimate wins). Convar gmodcraft_blast_blocks.
--   Minecraft's own explosions never come back: GMod applies them as impulses only (server/combat.lua).

local PB = gmodcraft.physblocks or {}
gmodcraft.physblocks = PB
local K = gmodcraft.K or {}
local C = gmodcraft.convert

local cvPull = CreateConVar("gmodcraft_gravgun_blocks", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: the gravgun's pull detaches Minecraft blocks as falling blocks (0 off, 1 everyone, 2 admins only)", 0, 2)
local cvBlast = CreateConVar("gmodcraft_blast_blocks", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: GMod explosions break Minecraft blocks like a Minecraft explosion (Minecraft's rules decide what breaks)", 0, 1)

PB.PULL_RANGE = 250         -- units (physcannon_tracelength)
PB.PULL_GAP = 0.5           -- s between pulls of one player
PB.MERGE_DIST = 64          -- units: sources of one explosion
PB.RECENT_TIME = 0.3        -- s: a source this soon after a sent blast nearby is the same explosion
PB.UNITS_PER_POWER = 80     -- blast radius (units) per Minecraft power: a barrel's 256 -> 3.2 (TNT is 4)
PB.DAMAGE_PER_POWER = 30    -- fallback (radius unknown): the strongest damage seen per power
PB.FRAG_POWER = 3           -- npc_grenade_frag: 125 damage, radius 250
PB.FALLBACK_MAX = PB.FRAG_POWER  -- the damage guess never outranks a known source's estimate in the merge
PB.PROP_POWER = 3           -- an exploding prop (gas can, barrel) when its env_explosion wasn't seen
PB.stats = PB.stats or { pulls = 0, pullRefusedLocal = 0, blastsSent = 0, sources = 0, merged = 0, dropped = 0 }
PB.pending = PB.pending or {}
PB.recent = PB.recent or {}
PB.recentPulls = PB.recentPulls or {}  -- { pos (Source, the cell's bottom centre), at }: pulls sent, for the grace (mcproxy)
PB.PULL_MATCH = 60          -- units: a new falling-block body this close to a recent pull is the pulled block
PB.PULL_MATCH_TIME = 1.5    -- s
PB.holding = PB.holding or setmetatable({}, { __mode = "k" })
local stats = PB.stats

local function finite(v) return v == v and v ~= math.huge and v ~= -math.huge end

function PB.MaxPower() return K.BlastMaxPower or 6 end

-- Power from a blast radius (units); 0: none.
function PB.PowerFromRadius(r)
	r = tonumber(r) or 0
	if not (r > 0) or not finite(r) then return 0 end
	return math.Clamp(r / PB.UNITS_PER_POWER, 1, PB.MaxPower())
end

-- Power from the strongest blast damage seen (radius unknown); 0: none.
function PB.PowerFromDamage(d)
	d = tonumber(d) or 0
	if not (d > 0) or not finite(d) then return 0 end
	return math.Clamp(d / PB.DAMAGE_PER_POWER, 1, PB.FALLBACK_MAX)
end

-- The Minecraft cell behind a hit on a block's face (integer MC coords).
function PB.Cell(hitPos, normal)
	local x, y, z = C.ToMc(hitPos - normal * 2)
	return math.floor(x), math.floor(y), math.floor(z)
end

-- One explosion's sources -> one per explosion: the strongest first, the rest within MERGE_DIST dropped.
function PB.Merge(list)
	table.sort(list, function(a, b) return a.power > b.power end)
	local out = {}
	local d2 = PB.MERGE_DIST * PB.MERGE_DIST
	for _, s in ipairs(list) do
		local dup = false
		for _, o in ipairs(out) do
			if s.pos:DistToSqr(o.pos) < d2 then dup = true break end
		end
		if dup then stats.merged = stats.merged + 1 else out[#out + 1] = s end
	end
	return out
end

local function linked()
	local L = gmodcraft.serverLink
	return L and L.mcAlive and C.slot.known and L.worldId
end

-- A source of a GMod explosion at pos (Vector) with this power.
function PB.Add(pos, power, attacker, src)
	if cvBlast:GetInt() == 0 or not pos or not (power > 0) or not (finite(pos.x) and finite(pos.y) and finite(pos.z)) then return end
	if PB.suppress then return end
	stats.sources = stats.sources + 1
	PB.pending[#PB.pending + 1] = { pos = Vector(pos.x, pos.y, pos.z), power = power, attacker = attacker, src = src }
	if not PB.flushQueued then
		PB.flushQueued = true
		timer.Simple(0, function() PB.flushQueued = false PB.Flush() end)
	end
end

-- The sources of this tick -> kHostEvBlast.
function PB.Flush()
	local list = PB.pending
	PB.pending = {}
	if #list == 0 then return end
	local now = CurTime()
	for i = #PB.recent, 1, -1 do
		if now - PB.recent[i].at > PB.RECENT_TIME then table.remove(PB.recent, i) end
	end
	local worldId = linked()
	if not worldId or not K.HostEvBlast then
		stats.dropped = stats.dropped + #list
		return
	end
	local d2 = PB.MERGE_DIST * PB.MERGE_DIST
	for _, s in ipairs(PB.Merge(list)) do
		local seen = false
		for _, r in ipairs(PB.recent) do
			if s.pos:DistToSqr(r.pos) < d2 then seen = true break end
		end
		if seen then
			stats.merged = stats.merged + 1
		else
			local x, y, z = C.ToMc(s.pos)
			local att = s.attacker
			local ok = gmodcraft.PushHostEvent({ type = K.HostEvBlast, worldId = worldId, x = x, y = y, z = z,
				a = math.floor(math.min(s.power, PB.MaxPower()) * 100 + 0.5), steamId = IsValid(att) and att:IsPlayer() and att:SteamID64() or "0" })
			PB.recent[#PB.recent + 1] = { pos = s.pos, at = now }
			if ok then stats.blastsSent = stats.blastsSent + 1 else stats.dropped = stats.dropped + 1 end
			gmodcraft.Log("combat", "GMod explosion (%s) at %s, Minecraft power %.1f%s", s.src or "?", tostring(s.pos), s.power, ok and "" or " (not sent)")
		end
	end
end

-- ---- sources ---------------------------------------------------------------------------------------
-- env_explosion: ExplosionCreate (barrels, the RPG, SMG grenades, combine balls...) and map explosions.
hook.Add("AcceptInput", "gmodcraft_physblocks", function(ent, input)
	if not IsValid(ent) or ent:GetClass() ~= "env_explosion" or string.lower(input or "") ~= "explode" then return end
	if ent:HasSpawnFlags(1) then return end  -- "No Damage": a show only
	local mag = tonumber(ent:GetInternalVariable("m_iMagnitude")) or tonumber(ent:GetInternalVariable("iMagnitude")) or 0
	local over = tonumber(ent:GetInternalVariable("m_iRadiusOverride")) or tonumber(ent:GetInternalVariable("iRadiusOverride")) or 0
	if mag <= 0 then return end
	PB.Add(ent:GetPos(), PB.PowerFromRadius(over > 0 and over or mag * 2.5), ent:GetOwner(), "env_explosion")
end)

-- util.BlastDamage / BlastDamageInfo from Lua (weapons, entities). Wrapped once (a Lua refresh re-runs this).
PB.orig = PB.orig or { blast = util.BlastDamage, blastInfo = util.BlastDamageInfo }
function util.BlastDamage(inflictor, attacker, pos, radius, damage, ...)
	if (tonumber(damage) or 0) > 0 then pcall(PB.Add, pos, PB.PowerFromRadius(radius), attacker, "BlastDamage") end
	return PB.orig.blast(inflictor, attacker, pos, radius, damage, ...)
end
function util.BlastDamageInfo(dmg, pos, radius, ...)
	if dmg and dmg:GetDamage() > 0 then pcall(PB.Add, pos, PB.PowerFromRadius(radius), dmg:GetAttacker(), "BlastDamageInfo") end
	return PB.orig.blastInfo(dmg, pos, radius, ...)
end

-- A frag grenade going off (it does its own radius damage, no env_explosion).
hook.Add("EntityRemoved", "gmodcraft_physblocks", function(ent)
	if not IsValid(ent) or ent:GetClass() ~= "npc_grenade_frag" then return end
	local at = tonumber(ent:GetInternalVariable("m_flDetonateTime"))
	if not at or at <= 0 or CurTime() + 0.05 < at then return end  -- removed before its time (cleanup): no blast
	PB.Add(ent:WorldSpaceCenter(), PB.FRAG_POWER, ent:GetOwner(), "frag")
end)

-- An exploding prop (gas can, explosive barrel, propane) breaking.
hook.Add("PropBreak", "gmodcraft_physblocks", function(_, prop)
	local FI = gmodcraft.fire
	if not (IsValid(prop) and FI and FI.EXPLOSIVE and FI.EXPLOSIVE[string.lower(prop:GetModel() or "")]) then return end
	PB.Add(prop:WorldSpaceCenter(), PB.PROP_POWER, nil, "prop")
end)

-- Fallback: blast damage on anything (the centre is the damage position Source reports for a blast).
hook.Add("EntityTakeDamage", "gmodcraft_physblocks", function(target, dmg)
	if bit.band(dmg:GetDamageType(), DMG_BLAST or 64) == 0 then return end
	local pos = dmg:GetDamagePosition()
	if not pos or pos == vector_origin then
		local inf = dmg:GetInflictor()
		pos = IsValid(inf) and inf:GetPos() or nil
	end
	if pos then PB.Add(pos, PB.PowerFromDamage(dmg:GetDamage()), dmg:GetAttacker(), "damage") end
end)

-- Was a block pulled out near pos (Source; a new body's feet) just now? (mcproxy gives it a grace before it rests.)
function PB.PulledNear(pos)
	local now = CurTime()
	for i = #PB.recentPulls, 1, -1 do
		local r = PB.recentPulls[i]
		if now - r.at > PB.PULL_MATCH_TIME then
			table.remove(PB.recentPulls, i)
		elseif r.pos:DistToSqr(pos) < PB.PULL_MATCH * PB.PULL_MATCH then
			return true
		end
	end
	return false
end

-- ---- gravgun pull ------------------------------------------------------------------------------------
hook.Add("GravGunOnPickedUp", "gmodcraft_physblocks", function(ply, ent) PB.holding[ply] = ent end)
-- After the gravgun lets something go (drop, punt) a still-held secondary doesn't pull the next block out: the
-- pull waits until the secondary is released (review run: a punt pulled a second block).
hook.Add("GravGunOnDropped", "gmodcraft_physblocks", function(ply) PB.holding[ply] = nil if IsValid(ply) then ply.gmcPullRearm = true end end)
hook.Add("GravGunPunt", "gmodcraft_physblocks", function(ply) if IsValid(ply) then ply.gmcPullRearm = true end end)

-- The Minecraft block a player's gravgun pulls at (MC cell x, y, z), or nil: the nearest hit along the view
-- must be a Minecraft block (the module's block store), not a prop or the map in front of it.
function PB.PullTarget(ply)
	local ray = gmodcraft.BlockRay
	local L = gmodcraft.serverLink
	local s = L and L.slot
	if not ray or not s then return nil end
	local st = ply:GetShootPos()
	local en = st + ply:GetAimVector() * PB.PULL_RANGE
	local f, nx, ny, nz = ray(st.x, st.y, st.z, en.x, en.y, en.z, s.ox, s.oz, s.oy or 0)
	if not f then return nil end
	local line = gmodcraft.blocktrace and gmodcraft.blocktrace.orig and gmodcraft.blocktrace.orig.line or util.TraceLine
	local tr = line({ start = st, endpos = en, filter = ply, mask = MASK_SHOT })
	if tr.Hit and (tr.Fraction or 1) < f and not (IsValid(tr.Entity) and tr.Entity:GetClass() == "gmodcraft_blocks") then return nil end
	return PB.Cell(st + (en - st) * f, Vector(nx, ny, nz))
end

function PB.PullTick()
	local mode = cvPull:GetInt()
	if mode == 0 or not K.HostEvPullBlock then return end
	local worldId = linked()
	local MP = gmodcraft.mcproxy
	if not worldId or not (MP and MP.Enabled and MP.Enabled()) then return end  -- the body could not be GMod's (rule physgunMobs)
	local now = CurTime()
	for _, ply in ipairs(player.GetHumans()) do
		if ply.gmcPullRearm and not ply:KeyDown(IN_ATTACK2) then ply.gmcPullRearm = nil end
		if not ply.gmcPullRearm and ply:KeyDown(IN_ATTACK2) and ply:Alive() and now >= (ply.gmcPullAt or 0) and not IsValid(PB.holding[ply]) then
			local w = ply:GetActiveWeapon()
			if IsValid(w) and w:GetClass() == "weapon_physcannon" then
				local admin = ply:IsAdmin() or game.SinglePlayer()
				local x, y, z
				if mode ~= 2 or admin then x, y, z = PB.PullTarget(ply) end
				if x then
					ply.gmcPullAt = now + PB.PULL_GAP
					local ang = ply:EyeAngles()
					local ok = gmodcraft.PushHostEvent({ type = K.HostEvPullBlock, steamId = ply:SteamID64() or "0", worldId = worldId, x = x, y = y, z = z,
						yaw = C.YawToMc(ang.y), pitch = math.Clamp(math.NormalizeAngle(ang.p), -90, 90), flags = admin and (K.PullByAdmin or 1) or 0 })
					if ok then
						stats.pulls = stats.pulls + 1
						PB.recentPulls[#PB.recentPulls + 1] = { pos = C.FromMc(x + 0.5, y, z + 0.5), at = now }
					end
					gmodcraft.Log("combat", "%s pulls the Minecraft block at %d %d %d%s", ply:Nick(), x, y, z, ok and "" or " (not sent)")
				end
			end
		end
	end
end
hook.Add("Tick", "gmodcraft_physblocks", function()
	local ok, err = pcall(PB.PullTick)
	if not ok then gmodcraft.Info("physblocks pull failed: %s", tostring(err)) end
end)

function PB.DebugTable()
	return { pull = cvPull:GetInt(), blast = cvBlast:GetInt(), stats = stats }
end
