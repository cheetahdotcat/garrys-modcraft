-- F1 (protocol v28): fire both ways between GMod and Minecraft. Server only.
--   MC -> GMod: kEvFireContact (Minecraft fire / lava touches a GMod prop's collision): the physics props
--     at that block catch fire (Ignite). Ragdolls aren't in MC's collision layer, so contact never lights them. Flame arrows / fire charges: server/projectiles.lua (kProjOnFire).
--     GMod NPCs in MC fire already burn through their MC stand-ins (P5), so they're left alone here.
--   GMod -> MC: burning GMod entities (IsOnFire: props, ragdolls, NPCs; a lit env_fire) and exploding fire
--     props (gas cans, explosive barrels, propane) send kHostEvFire; Minecraft sets fire to flammable MC
--     blocks next to them (its own caps and checks: slot, dug cells, map surfaces, gamerule).
-- Both only while the Minecraft server reports the rule fireCrossover (McServerState ruleFlags).

local FI = gmodcraft.fire or {}
gmodcraft.fire = FI
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band = bit.band

local function finite(v) v = tonumber(v) or 0 return (v == v and v ~= math.huge and v ~= -math.huge) and v or 0 end

-- An env_fire only burns while its heat is up (Source CFire::IsBurning: m_flHeatLevel > 0); a dormant or
-- put-out one has heat 0. Unknown field (nil) counts as not burning.
function FI.EnvFireLit(ent)
	local heat = ent:GetInternalVariable("m_flHeatLevel")
	return (tonumber(heat) or 0) > 0
end

FI.CONTACT_IGNITE = { [1] = 6, [2] = 12 }  -- BlockHazard -> seconds (fire, lava)
FI.CONTACT_HALF = 32                        -- units around the burning block's centre (block 40 u + the 0.3-block touch margin)
FI.SEND_EVERY = 0.5                         -- s between scans of burning entities
FI.ENT_COOLDOWN = 1.5                       -- s between kHostEvFire for one burning entity
FI.PER_SCAN = 4                             -- kHostEvFire per scan at most
FI.EXPLOSIVE = {
	["models/props_junk/gascan001a.mdl"] = true,
	["models/props_c17/oildrum001_explosive.mdl"] = true,
	["models/props_junk/propane_tank001a.mdl"] = true,
	["models/props_junk/propanecanister001a.mdl"] = true,
	["models/props_phx/oildrum001_explosive.mdl"] = true,
}
FI.stats = FI.stats or { contacts = 0, ignited = 0, sent = 0, explosions = 0, refused = 0 }
FI.lastSent = FI.lastSent or setmetatable({}, { __mode = "k" })

-- The rule fireCrossover, as the Minecraft server reports it (off when it doesn't: older than v28).
function FI.Enabled()
	local mss = gmodcraft.McServerState and gmodcraft.McServerState()
	if not (mss and mss.ruleFlags) then return false end
	return band(mss.ruleFlags, K.RulesValid or 1) ~= 0 and band(mss.ruleFlags, K.RuleFireCrossover or 0) ~= 0
end

local function linked()
	local L = gmodcraft.serverLink
	return L and L.mcAlive and C.slot.known and L.worldId
end

-- Entities MC fire may set alight: physics props, not players / NPCs / our block entity. A ragdoll would pass,
-- but ragdolls aren't in the dynamic collision layer MC tests contact against, so contact never reaches one.
function FI.Burnable(ent)
	if not IsValid(ent) or ent:IsWorld() or ent:IsPlayer() or ent:IsNPC() or (ent.IsNextBot and ent:IsNextBot()) then return false end
	-- our own entities (MC block collision, Tier 0 physics regions, bridges) never burn
	if string.sub(ent:GetClass(), 1, 10) == "gmodcraft_" or not ent.Ignite then return false end
	return ent:GetMoveType() == MOVETYPE_VPHYSICS
end

function FI.OnContact(e)
	FI.stats.contacts = FI.stats.contacts + 1
	if not FI.Enabled() or e.requestId ~= (gmodcraft.serverLink or {}).worldId then FI.stats.refused = FI.stats.refused + 1 return end
	local pos = C.FromMc(finite(e.a), finite(e.b), finite(e.c))
	local h = FI.CONTACT_HALF
	local secs = FI.CONTACT_IGNITE[tonumber(e.weapon) or 1] or 6
	for _, ent in ipairs(ents.FindInBox(pos - Vector(h, h, h), pos + Vector(h, h, h))) do
		if FI.Burnable(ent) and not ent:IsOnFire() then
			ent:Ignite(secs)
			FI.stats.ignited = FI.stats.ignited + 1
			gmodcraft.Info("Minecraft %s set %s (%s) on fire", e.weapon == (K.HazardLava or 2) and "lava" or "fire", ent:GetClass(), ent:GetModel() or "")
		end
	end
end

local function push(pos, radius, cause)
	local L = gmodcraft.serverLink
	local x, y, z = C.ToMc(pos)
	local ok = gmodcraft.PushHostEvent({ type = K.HostEvFire, worldId = L.worldId, x = x, y = y, z = z, a = radius, code = cause })
	if ok then FI.stats.sent = FI.stats.sent + 1 end
	return ok
end

-- Burning GMod entities -> kHostEvFire (each at most every ENT_COOLDOWN, PER_SCAN per scan).
function FI.Scan()
	if not (linked() and FI.Enabled() and K.HostEvFire) then return end
	local now, n = CurTime(), 0
	for _, ent in ipairs(ents.GetAll()) do
		if n >= FI.PER_SCAN then break end
		if IsValid(ent) and string.sub(ent:GetClass(), 1, 10) ~= "gmodcraft_" and (ent:IsOnFire() or (ent:GetClass() == "env_fire" and FI.EnvFireLit(ent))) then
			local last = FI.lastSent[ent]
			if not last or now - last >= FI.ENT_COOLDOWN then
				FI.lastSent[ent] = now
				local mins, maxs = ent:OBBMins(), ent:OBBMaxs()
				local size = math.max(maxs.x - mins.x, maxs.y - mins.y, maxs.z - mins.z) / 40
				if push(ent:WorldSpaceCenter(), math.Clamp(math.ceil(size / 2) + 1, 1, 2), K.FireBurning or 1) then n = n + 1 end
			end
		end
	end
end
timer.Create("gmodcraft_fire_scan", FI.SEND_EVERY, 0, function()
	local ok, err = pcall(FI.Scan)
	if not ok then gmodcraft.Info("fire scan failed: %s", tostring(err)) end
end)

-- An exploding fire prop (gas can, explosive barrel, propane) lights MC blocks around it.
hook.Add("PropBreak", "gmodcraft_fire", function(_, prop)
	if not (IsValid(prop) and FI.EXPLOSIVE[string.lower(prop:GetModel() or "")]) then return end
	if not (linked() and FI.Enabled() and K.HostEvFire) then return end
	if push(prop:WorldSpaceCenter(), 3, K.FireExplosion or 2) then FI.stats.explosions = FI.stats.explosions + 1 end
end)

if gmodcraft.combat and gmodcraft.combat.On then gmodcraft.combat.On(K.EvFireContact, "f1_fire", FI.OnContact) end

function FI.DebugTable()
	return { enabled = FI.Enabled(), stats = FI.stats }
end
