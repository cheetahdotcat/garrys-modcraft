-- B1, Tier 1 "MC mob hulls" (protocol v27; docs/DESIGN.md section 8): every Minecraft mob, animal,
-- minecart and boat near the GMod players gets an invisible GMod body: a gmodcraft_mcproxy (an anim
-- SENT: rays, hulls, damage, the T2 physics body) plus, for HL2 NPCs to fight it, a gmodcraft_mcproxy_npc
-- NextBot that follows it (H-approx, v31: Lua TestCollision never runs on a NextBot, live spike 2026-10-06,
-- so rays on a NextBot body only ever hit its model's hitbox).
--
--  * the MC server lists them (McEntities on the server link: id, category, position, hitbox,
--    health); every tick the GMod server creates, moves (SetPos, kinematic) and removes the proxies
--    to match. Not drawn (Minecraft draws the mob), but transmitted, so players' movement prediction
--    bumps into them;
--  * solid for bullets, traces, players and NPCs: hulls (movement, bumping) hit the Minecraft collision
--    box (the collision bounds); rays (FSOLID_CUSTOMRAYTEST + ENT:TestCollision) hit model-shaped boxes
--    (H-approx, v31: shared/mchitbox.lua) turned to the mob's body yaw, the same test in both realms
--    (shape, size and yaw are NetworkVars), found through the surrounding bounds (a yaw-independent
--    square around all the boxes). A bullet on a head box counts double (headshot);
--  * damage on a proxy never applies in GMod: it is summed per tick and sent to Minecraft as
--    kHostEvHurtMcEntity (raw GMod amount; Minecraft divides by its damage scale, the same factor
--    as gmodcraft_damage_scale), credited to the attacking GMod player's Minecraft player (PvP-style)
--    or the GMod NPC's stand-in there;
--  * HL2 NPCs hate the hostile ones (zombies, skeletons, creepers...): the NPC target NextBot is in their
--    sensing, and AddEntityRelationship is set both ways round in time (new proxy / new NPC). Their bullets
--    aim at its centre and hit the anim around it; their line of sight (MASK_BLOCKLOS) passes the anim. Passive ones and
--    vehicles are left alone (no NPC target at all).
--  * T2 (v29): the physgun, gravgun, freeze, ropes and balloons work on them (rule physgunMobs). Each
--    proxy has a real VPhysics box of the hitbox (MOVETYPE_VPHYSICS) but stays SOLID_BBOX for traces and
--    player hulls. One owner at a time:
--      - Minecraft (default): the body is motion-disabled and placed from McEntities every tick;
--      - GMod (held by a physgun / gravgun, frozen by the physgun, or constrained): the body simulates
--        in GMod, the engine moves the entity from it (physobj -> entity), MP.Sync never places it and
--        ignores Minecraft's position, and HeldMcEntities tells Minecraft where it is (20 Hz) so the mob
--        is pinned there (AI and gravity paused). When none of the three holds any more it goes back to
--        Minecraft: the table drops it, Minecraft applies the last velocity once (the throw).
--    A gravgun punt on a Minecraft-owned one sends kHostEvPuntMcEntity (an impulse).
-- Never proxied: Minecraft players (puppets), the host actor stand-ins (GMod's own NPCs), items.
-- Excluded from the HostActor table (server/actors.lua), the dynamic layer and Tier 0 (NextBots).

local MP = gmodcraft.mcproxy or {}
gmodcraft.mcproxy = MP
local HB = gmodcraft.mchitbox
MP.CLASS = "gmodcraft_mcproxy"
MP.NPC_CLASS = "gmodcraft_mcproxy_npc"
local CLASS, NPC_CLASS = MP.CLASS, MP.NPC_CLASS
-- The HL2 NPCs' target for a hostile body: "bullseye" (an npc_bullseye, Not Solid, at the box centre: HL2's
-- own invisible NPC target) or "nextbot" (gmodcraft_mcproxy_npc, SOLID_NONE, eyes raised). The spike of
-- 2026-10-06 showed combine neither seeing nor fighting the NextBot; tools/spikes/hitbox_live.lua compares.
MP.NPC_MODE = MP.NPC_MODE or "bullseye"

-- Either part of a Minecraft entity's GMod body (the anim or its NPC target; targets carry gmcMcBody,
-- networked as NW2 "gmcMcBody" for the client).
function MP.IsMcBody(e)
	if not IsValid(e) then return false end
	local c = e:GetClass()
	return c == CLASS or c == NPC_CLASS or e.gmcMcBody == true
end

-- ---- the proxy entity (both realms) ----------------------------------------------------------------
local ENT = {}
ENT.Type = "anim"
ENT.Base = "base_anim"
ENT.PrintName = "Minecraft entity (proxy)"
ENT.Spawnable = false
ENT.AdminOnly = true

function ENT:SetupDataTables()
	self:NetworkVar("Int", 0, "McId")
	self:NetworkVar("Int", 1, "McCategory")
	self:NetworkVar("Int", 2, "McShape")    -- H-approx: index into mchitbox.SHAPES, 0: the collision box
	self:NetworkVar("Int", 3, "McYawQ")     -- body yaw, McEntity steps (K.McEntYawSteps)
	self:NetworkVar("Float", 0, "McWidth")  -- the collision box, blocks
	self:NetworkVar("Float", 1, "McHeight")
end

local YAW_STEPS = 1024  -- K.McEntYawSteps (the module's K isn't there on clients without it)
local YAW_DEADBAND = 8  -- steps (~2.8 degrees): smaller turns don't update the NetworkVar

-- The ray boxes for the networked shape / size (rebuilt when they change). No side effects: TestCollision
-- calls it mid-trace.
function ENT:Geom()
	local shape, w, h = self:GetMcShape(), self:GetMcWidth(), self:GetMcHeight()
	local g = self.geom
	if g and g.shape == shape and g.w == w and g.h == h then return g end
	g = HB.Build(shape, w, h)
	g.shape, g.w, g.h = shape, w, h
	self.geom = g
	return g
end

-- The surrounding bounds = the boxes' yaw-independent envelope, so the spatial partition hands rays at
-- any part of them to TestCollision. Only outside traces: SetHull, BuildBody, the client's Think.
function ENT:ApplyEnvelope()
	local g = self:Geom()
	if self.envelopeOf == g then return end
	self.envelopeOf = g
	self:SetSurroundingBounds(Vector(-g.r, -g.r, g.zlo), Vector(g.r, g.r, g.zhi))
end

-- cos / sin of the body yaw (cached per networked step).
function ENT:YawCS()
	local q = self:GetMcYawQ()
	if self.yawQ ~= q then
		self.yawQ = q
		self.yawC, self.yawS = HB.YawCS(q * 360 / YAW_STEPS)
	end
	return self.yawC, self.yawS
end

function ENT:Initialize()
	self:SetModel("models/hunter/blocks/cube025x025x025.mdl")
	-- Not SetNoDraw: an EF_NODRAW entity isn't transmitted, and clients need it (prediction bumps, client
	-- traces). ENT:Draw draws nothing instead.
	self:DrawShadow(false)
	if SERVER then
		self:SetHealth(1000000000)
		self:SetMaxHealth(1000000000)
		self:SetSaveValue("m_takedamage", 2)  -- DAMAGE_YES: bullets, melee, blasts reach EntityTakeDamage
		self:SetSolid(SOLID_BBOX)
		self:AddSolidFlags(FSOLID_CUSTOMRAYTEST)
		self:SetCollisionGroup(COLLISION_GROUP_NPC)
	end
end

if SERVER then
	function ENT:UpdateTransmitState() return TRANSMIT_PVS end
end

-- Hitbox in Source units around the feet (set from the table: MP.Sync).
function ENT:SetHull(w, h)
	local hw = w * 20
	self.hullMin, self.hullMax = Vector(-hw, -hw, 0), Vector(hw, hw, h * 40)
	self:SetCollisionBounds(self.hullMin, self.hullMax)
	if not SERVER then return end
	self:SetMcWidth(w)
	self:SetMcHeight(h)
	self:ApplyEnvelope()
	self.targetOff = MP.NPC_MODE == "bullseye" and Vector(0, 0, h * 20) or vector_origin  -- a bullseye sits at the centre
	local n = self.npc
	if IsValid(n) then
		if n.SetTargetHull then n:SetTargetHull(self.hullMin, self.hullMax) end
		n:SetPos(self:GetPos() + self.targetOff)
	end
	if self.own then self.bodyDirty = true else self:BuildBody() end  -- never under a physgun: after Give
end

-- T2: a VPhysics box of the hitbox (the physgun, gravgun, ropes and balloons hold it), motion-disabled
-- while Minecraft owns the mob. PhysicsInitBox makes it SOLID_VPHYSICS: traces and player hulls get
-- the exact AABB back (SOLID_BBOX + the custom ray test, as B1); VPhysics collides with the box.
function ENT:BuildBody()
	local lo, hi = self.hullMin, self.hullMax
	if not lo or not self:PhysicsInitBox(lo, hi) then return end
	self:SetMoveType(MOVETYPE_VPHYSICS)
	self:SetSolid(SOLID_BBOX)
	self:AddSolidFlags(FSOLID_CUSTOMRAYTEST)
	self:SetCollisionBounds(lo, hi)
	self:SetCollisionGroup(COLLISION_GROUP_NPC)
	self.envelopeOf = nil
	self:ApplyEnvelope()  -- the surrounding bounds again, after the physics init
	local phys = self:GetPhysicsObject()
	if IsValid(phys) then
		local w, h = (hi.x - lo.x) / 40, (hi.z - lo.z) / 40
		phys:SetMass(math.Clamp(w * w * h * 60, 5, 200))  -- the gravgun lifts at most physcannon_maxmass (250)
		phys:EnableMotion(false)
	end
end

-- Rays (bullets, traces, melee, the crosshair) hit the model boxes (H-approx), in both realms. Hulls
-- never come here (no FSOLID_CUSTOMBOXTEST): they hit the collision bounds, the Minecraft box.
-- Only masks that hit monsters / hitboxes (MASK_SHOT, MASK_SOLID, the physgun's...): NPC line of sight
-- (MASK_BLOCKLOS, no CONTENTS_MONSTER) passes, so NPCs see the NextBot target inside.
-- Line-of-sight queries (CONTENTS_BLOCKLOS, e.g. MASK_BLOCKLOS_AND_NPCS) pass too. The NPC target owns the
-- anim (SetOwner): HL2's AI traces skip their enemy and what it owns (PassServerEntityFilter), so an NPC's
-- sight and weapon line-of-fire checks pass the anim while its bullets still hit it.
local RAY_CONTENTS = bit.bor(CONTENTS_MONSTER or 0x2000000, CONTENTS_HITBOX or 0x40000000)
local LOS_CONTENTS = CONTENTS_BLOCKLOS or 0x40
function ENT:TestCollision(start, delta, isBox, extents, mask)
	if isBox then return end
	if mask and (bit.band(mask, RAY_CONTENTS) == 0 or bit.band(mask, LOS_CONTENTS) ~= 0) then return end
	local g = self:Geom()
	if g.n == 0 then return end
	local c, s = self:YawCS()
	local o = self:GetPos()
	local f, nx, ny, nz = HB.RayTest(g, c, s, o.x, o.y, o.z, start.x, start.y, start.z, delta.x, delta.y, delta.z)
	if not f then return end
	return { HitPos = start + delta * f, Fraction = f, Normal = Vector(nx, ny, nz) }
end

-- Headshot: a world point on (or within a unit of) a head box.
function ENT:HitHead(pos)
	local g = self:Geom()
	local c, s = self:YawCS()
	local o = self:GetPos()
	return HB.InHead(g, c, s, o.x, o.y, o.z, pos.x, pos.y, pos.z, 1)
end

if CLIENT then
	function ENT:Draw() end
	-- The networked shape / size may change any time: keep the boxes and surrounding bounds current (a compare
	-- when nothing changed).
	function ENT:Think() self:ApplyEnvelope() end
end

scripted_ents.Register(ENT, CLASS)

-- ---- the NPC target (both realms) ------------------------------------------------------------------
-- HL2 NPCs only fight NPCs and NextBots: an invisible NextBot at the body, never hit by rays (they hit the
-- anim around it), no hull of its own. Damage that reaches it anyway goes to its body (MP: EntityTakeDamage).
local NPC = {}
NPC.Type = "nextbot"
NPC.Base = "base_nextbot"
NPC.PrintName = "Minecraft entity (NPC target)"
NPC.Spawnable = false
NPC.AdminOnly = true

function NPC:Initialize()
	self:SetModel("models/hunter/blocks/cube025x025x025.mdl")
	self:SetNoDraw(true)
	self:DrawShadow(false)
	if SERVER then
		self:SetHealth(1000000000)
		self:SetMaxHealth(1000000000)
		if self.loco then self.loco:SetGravity(0) end
	end
end

-- The Minecraft box (bounds: NPCs aim at its centre), not solid.
function NPC:SetTargetHull(lo, hi)
	self:SetCollisionBounds(lo, hi)
	self:SetSolid(SOLID_NONE)
end

function NPC:RunBehaviour()
	while true do coroutine.wait(1) end  -- Minecraft moves it: no AI of its own
end

function NPC:OnKilled() end  -- never: GMod damage on it is cancelled

if CLIENT then
	function NPC:Draw() end
end

scripted_ents.Register(NPC, NPC_CLASS)

-- ---- T2: physgun / gravgun / tools (both realms: the physgun predicts its grab) ------------------
local GLOBAL_RULE = "gmodcraft_physgun_mobs"

-- The rule physgunMobs (server: as the Minecraft server reports it, off when it doesn't: older than v29).
function MP.Enabled()
	if CLIENT then return GetGlobal2Bool(GLOBAL_RULE, false) end
	local K = gmodcraft.K or {}
	local mss = gmodcraft.McServerState and gmodcraft.McServerState()
	if not (mss and mss.ruleFlags) then return false end
	return bit.band(mss.ruleFlags, K.RulesValid or 1) ~= 0 and bit.band(mss.ruleFlags, K.RulePhysgunMobs or 0) ~= 0
end

local function isProxy(e) return IsValid(e) and e:GetClass() == CLASS end

-- false: refused (rule off, or the held table is full); nil: the gamemode / addons decide.
local function allowed(_, ent)
	if IsValid(ent) and ent.gmcMcBody then return false end
	if not isProxy(ent) then return end
	if not MP.Enabled() then return false end
	if SERVER and MP.Full and MP.Full(ent) then return false end
end
hook.Add("PhysgunPickup", "gmodcraft_mcproxy", allowed)
hook.Add("CanTool", "gmodcraft_mcproxy", function(ply, tr)
	if tr and IsValid(tr.Entity) and tr.Entity.gmcMcBody then return false end
	if tr and isProxy(tr.Entity) and not MP.Enabled() then return false end
end)

-- ---- syncing (server) ------------------------------------------------------------------------------
if not SERVER then return end

local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band = bit.band
local Log = gmodcraft.Log

MP.byId = MP.byId or {}       -- MC entity id -> proxy
MP.pending = MP.pending or {} -- proxy -> { amount, kind, attacker } summed this tick
MP.stats = MP.stats or { created = 0, removed = 0, hurts = 0, pushFailed = 0 }
MP.pendingFire = MP.pendingFire or {} -- proxy -> attacker (F2: GMod fire on it this tick)
local byId, pending, stats = MP.byId, MP.pending, MP.stats
local pendingFire = MP.pendingFire
-- F2: GMod fire (entity flames: DMG_BURN every 0.2 s) sets the MC entity on fire instead of hurting it; at most
-- one fire event per body per FIRE_GAP s (Minecraft keeps it burning ~5 s per event).
local FIRE = bit.bor(DMG_BURN or 8, DMG_SLOWBURN or 2097152)
local FIRE_GAP = 0.5

function MP.IsProxy(e)
	return IsValid(e) and e:GetClass() == CLASS
end

local function hostile(p) return p:GetMcCategory() == (K.McEntHostile or 1) end

-- NPCs hate hostile proxies (and only those), through the NPC target; neutral to everything else.
local function relate(npc, p)
	if not IsValid(npc) or not npc:IsNPC() or npc.gmcMcBody or not IsValid(p) or not IsValid(p.npc) then return end
	local CB = gmodcraft.combat  -- v34: the Server page's NPC-vs-mobs rule
	npc:AddEntityRelationship(p.npc, CB and CB.NpcMobDisposition and CB.NpcMobDisposition(hostile(p)) or (hostile(p) and D_HT or D_NU), 50)
end

-- Before a proxy goes: GMod's NPC meta has no RemoveEntityRelationship, so make the NPCs neutral to its
-- target (the engine drops relationships to removed entities with the handle). Never let it break Sync.
local function unrelate(p)
	if not IsValid(p) or not IsValid(p.npc) then return end
	for _, npc in ipairs(ents.GetAll()) do
		if npc:IsNPC() and not npc.gmcMcBody then pcall(npc.AddEntityRelationship, npc, p.npc, D_NU, 0) end
	end
end

local WORLD_LIMIT = 16384

-- ---- T2 ownership --------------------------------------------------------------------------------------
-- p.own = nil: Minecraft owns it. Else GMod does: { holders = { [ply] = true } (physguns / gravguns holding
-- it now), dropPending, probe (a gravgun pull's last frame) }. Frozen / constrained are read from the body.
local VEL = 1 / 800  -- Source units per second -> MC blocks per tick (/40, /20)
local PROBE_S = 0.3   -- a gravgun pull that doesn't end in a pickup within this long gives it back

local function constrained(p)
	return constraint and constraint.HasConstraints and constraint.HasConstraints(p) or false
end

local function bad(v) return v.x ~= v.x or v.y ~= v.y or v.z ~= v.z end

-- Minecraft owns it: motion off, placed where Minecraft says (nothing done when it hasn't moved).
local function place(p, pos)
	if p.placedAt == pos then return end
	p.placedAt = pos
	p:SetPos(pos)
	if IsValid(p.npc) then p.npc:SetPos(pos + (p.targetOff or vector_origin)) end
	local phys = p:GetPhysicsObject()
	if IsValid(phys) then
		if phys:IsMotionEnabled() then phys:EnableMotion(false) end
		phys:SetPos(pos)
	end
end

local function ownedCount()
	local n = 0
	for _, q in pairs(byId) do if IsValid(q) and q.own then n = n + 1 end end
	return n
end

-- Would GMod taking p overflow HeldMcEntities (K.MaxHeldMcEntities)? Logged once per overflow.
function MP.Full(p)
	if p.own or ownedCount() < (K.MaxHeldMcEntities or 64) then
		MP.fullLogged = nil
		return false
	end
	if not MP.fullLogged then
		MP.fullLogged = true
		Log("combat", "GMod already holds %d Minecraft entities: no more", K.MaxHeldMcEntities or 64)
	end
	return true
end

-- The players holding it now (physgun / gravgun), invalid ones dropped; the first one, if any.
local function holderOf(own)
	local first
	for ply in pairs(own.holders) do
		if not IsValid(ply) then own.holders[ply] = nil elseif not first then first = ply end
	end
	return first
end

-- GMod takes it (a pickup, a gravgun pull, a constraint). False when the table is full.
function MP.Take(p, holder)
	if not isProxy(p) then return false end
	if not p.own then
		if MP.Full(p) then return false end
		p.own = { holders = {} }
		local phys = p:GetPhysicsObject()
		if IsValid(phys) then
			-- v32: the mob's facing (Minecraft's body yaw now) in the body's frame, so heldRecord turns it with the body
			local a = Angle(0, C.YawFromMc(p:GetMcYawQ() * 360 / YAW_STEPS), 0)
			p.own.face = phys:WorldToLocalVector(a:Forward())
			if not phys:IsMotionEnabled() then
				phys:EnableMotion(true)
				phys:Wake()
			end
		end
		stats.takes = (stats.takes or 0) + 1
	end
	if IsValid(holder) then p.own.holders[holder] = true end
	return true
end

-- Back to Minecraft (none of held / frozen / constrained any more, or the body goes).
function MP.Give(p)
	if not p.own then return end
	p.own = nil
	p.placedAt = nil
	if not IsValid(p) then return end
	p:SetAngles(angle_zero)
	local phys = p:GetPhysicsObject()
	if IsValid(phys) then
		if bad(phys:GetPos()) then phys:SetPos(p.lastGood or vector_origin) end  -- a NaN body: back to its last good place
		phys:SetAngles(angle_zero)
		phys:SetVelocity(vector_origin)
		phys:EnableMotion(false)
	end
	if p.bodyDirty then
		p.bodyDirty = nil
		p:BuildBody()
	end
	stats.gives = (stats.gives or 0) + 1
end

-- This tick's HeldMcEntities record of an owned proxy, or nil when it goes back to Minecraft.
local function heldRecord(p, now)
	local own = p.own
	local phys = p:GetPhysicsObject()
	if not IsValid(phys) then return nil end
	if own.probe and now - own.probe > PROBE_S then own.probe = nil end
	local holder = holderOf(own)
	local held = holder ~= nil or own.probe ~= nil
	local flags = 0
	if held or own.dropPending then flags = bit.bor(flags, K.HeldByPhysgun or 1) end
	if not held and not phys:IsMotionEnabled() then flags = bit.bor(flags, K.HeldFrozen or 2) end
	if constrained(p) then flags = bit.bor(flags, K.HeldConstrained or 4) end
	own.dropPending = nil
	if flags == 0 then return nil end
	-- feet = the box's centre minus half its height (the body may have turned)
	local h = p.hullMax and p.hullMax.z or 0
	local feet = phys:LocalToWorld(phys:GetMassCenter()) - Vector(0, 0, h / 2)
	local v = phys:GetVelocity()
	if bad(feet) or bad(v) then return nil end  -- never NaN to Minecraft
	p.lastGood = phys:GetPos()
	local x, y, z = C.ToMc(feet)
	-- v32: the facing turned with the body -> MC yaw / pitch (nil: Minecraft keeps its own)
	local yaw, pitch
	if own.face then
		local f = phys:LocalToWorldVector(own.face)
		if not bad(f) and f:LengthSqr() > 1e-6 then
			local a = f:Angle()
			yaw, pitch = C.YawToMc(a.y), math.NormalizeAngle(a.p)
		end
	end
	return { id = p:GetMcId(), flags = flags, steamId = IsValid(holder) and holder:IsPlayer() and holder:SteamID64() or nil,
		x = x, y = y, z = z, vx = v.x * VEL, vy = v.z * VEL, vz = -v.y * VEL, yaw = yaw, pitch = pitch }
end

-- 20 Hz: decide who owns each proxy, write HeldMcEntities (also when empty: Minecraft releases on silence).
function MP.WriteHeld()
	local list, now = {}, SysTime()
	for _, p in pairs(byId) do
		if IsValid(p) then
			if not p.own and constrained(p) and MP.Enabled() then MP.Take(p) end  -- a rope / balloon / weld on it
			if p.own then
				if IsValid(p.npc) then p.npc:SetPos(p:GetPos() + (p.targetOff or vector_origin)) end  -- follows GMod's body (20 Hz)
				local r = heldRecord(p, now)
				if r then list[#list + 1] = r else MP.Give(p) end
			end
		end
	end
	if gmodcraft.SetHeldMcEntities then gmodcraft.SetHeldMcEntities(list) end
	stats.held = #list
	local on = MP.Enabled()
	if GetGlobal2Bool(GLOBAL_RULE, false) ~= on then SetGlobal2Bool(GLOBAL_RULE, on) end
end

local function pickedUp(ply, ent)
	if not isProxy(ent) then return end
	if MP.Take(ent, ply) then ent.own.probe = nil end
end
hook.Add("OnPhysgunPickup", "gmodcraft_mcproxy", pickedUp)
hook.Add("GravGunOnPickedUp", "gmodcraft_mcproxy", pickedUp)
-- Only this player lets go: another one may still hold it (two physguns on one mob).
local function dropped(ply, ent)
	if not isProxy(ent) or not ent.own then return end
	ent.own.holders[ply] = nil
	ent.own.dropPending = true  -- one more record with the velocity it left the gun with (the throw)
end
hook.Add("PhysgunDrop", "gmodcraft_mcproxy", dropped)
hook.Add("GravGunOnDropped", "gmodcraft_mcproxy", dropped)

-- The gravgun picks up only what can move: a pull (secondary held) hands the body to GMod once, the
-- following frames only keep the probe alive; no pickup within PROBE_S gives it back. Merely aiming
-- (the claws' check runs every frame) doesn't.
hook.Add("GravGunPickupAllowed", "gmodcraft_mcproxy", function(ply, ent)
	local ok = allowed(ply, ent)
	if ok == nil and isProxy(ent) and IsValid(ply) and ply:KeyDown(IN_ATTACK2) then
		if not ent.own then
			if MP.Take(ent) then ent.own.probe = SysTime() end
		elseif ent.own.probe then
			ent.own.probe = SysTime()
		end
	end
	return ok
end)

-- A punt: GMod-owned bodies fly in GMod (the drop sends the velocity); Minecraft-owned ones get an impulse there.
hook.Add("GravGunPunt", "gmodcraft_mcproxy", function(ply, ent)
	local ok = allowed(ply, ent)
	if ok == nil and isProxy(ent) and not ent.own then
		local d = IsValid(ply) and ply:GetAimVector() or vector_up
		local speed, lift = 1.2, 0.3  -- MC blocks per tick (a Minecraft knockback is ~0.4)
		local sent = gmodcraft.PushHostEvent({ type = K.HostEvPuntMcEntity, requestId = ent:GetMcId(),
			steamId = IsValid(ply) and ply:SteamID64() or "0", x = d.x * speed, y = d.z * speed + lift, z = -d.y * speed })
		stats.punts = (stats.punts or 0) + 1
		Log("combat", "MC entity %d punted%s", ent:GetMcId(), sent and "" or " (not sent)")
	end
	return ok
end)

-- The body yaw in McEntity steps (the module decodes it to degrees).
local function yawQ(e)
	return math.floor((e.yaw or 0) * YAW_STEPS / 360 + 0.5) % YAW_STEPS
end

-- A body for McEntities record e (at pos, else e's MC position): the anim and its NPC target. Not listed
-- in byId (MP.Sync does that; the live spike makes unlisted ones).
local function create(e, pos)
	local p = ents.Create(CLASS)
	if not IsValid(p) then return nil end
	pos = pos or C.FromMc(e.x, e.y, e.z)
	p:SetMcId(e.id)
	p:SetMcCategory(band(e.category, 0xF))  -- the module masks it already (v31: yaw above)
	p:SetMcShape(HB.ShapeFor(e.typeHash))
	p:SetMcYawQ(yawQ(e))
	p:SetPos(pos)
	p:Spawn()
	-- Only hostile mobs get an NPC target (NPCs are neutral to the rest: no target needed).
	local n = hostile(p) and ents.Create(MP.NPC_MODE == "bullseye" and "npc_bullseye" or NPC_CLASS)
	if IsValid(n) then
		n.gmcMcBody = true
		n:SetNW2Bool("gmcMcBody", true)
		n.mcBody = p
		n:SetPos(pos)
		if MP.NPC_MODE == "bullseye" then
			n:SetKeyValue("spawnflags", "65536")  -- Not Solid: rays and NPC bullets go on to the anim around it
			n:SetKeyValue("health", "1000000000")
		end
		n:Spawn()
		if n.Activate then n:Activate() end
		p.npc = n
		p:SetOwner(n)  -- AI traces that skip their enemy skip the anim too (see TestCollision)
		p:DeleteOnRemove(n)
	end
	p:SetHull(e.width, e.height)
	for _, npc in ipairs(ents.GetAll()) do
		if npc:IsNPC() and not npc.gmcMcBody then relate(npc, p) end
	end
	stats.created = stats.created + 1
	return p
end
MP.CreateBody = create

-- One tick: match the proxies to McEntities.
function MP.Sync(list)
	local seen = {}
	for _, e in ipairs(list) do
		local pos = C.FromMc(e.x, e.y, e.z)
		local inside = math.abs(pos.x) < WORLD_LIMIT and math.abs(pos.y) < WORLD_LIMIT and math.abs(pos.z) < WORLD_LIMIT
		local owned = IsValid(byId[e.id]) and byId[e.id].own
		if band(e.flags, K.McEntDead or 1) == 0 and (inside or owned) then  -- T2: GMod's own stays while MC lists it
			seen[e.id] = true
			local p = byId[e.id]
			if IsValid(p) and p:GetMcCategory() ~= band(e.category, 0xF) then
				-- An id reused for another kind (a new Minecraft after a fast restart): a new body.
				unrelate(p)
				pending[p] = nil
				p.own = nil
				p:Remove()
				p = nil
			end
			if not IsValid(p) then
				p = create(e)
				byId[e.id] = p
			end
			if IsValid(p) and not p.own then  -- T2: GMod owns it: no placement at all, Minecraft's position ignored
				place(p, pos)
			end
			if IsValid(p) then
				local q = yawQ(e)
				local dq = math.abs(p:GetMcYawQ() - q)
				if math.min(dq, YAW_STEPS - dq) >= YAW_DEADBAND then p:SetMcYawQ(q) end  -- fewer NetworkVar updates
				if p.mcW ~= e.width or p.mcH ~= e.height then
					p.mcW, p.mcH = e.width, e.height
					p:SetHull(e.width, e.height)
				end
			end
		end
	end
	for id, p in pairs(byId) do
		if not seen[id] then
			if IsValid(p) then
				p.own = nil  -- dead / gone in Minecraft: out of the held table this tick, the physgun drops it
				unrelate(p)
				p:Remove()
			end
			byId[id] = nil
			pending[p] = nil
			stats.removed = stats.removed + 1
		end
	end
end

-- Damage on a body (the anim, or the NPC target: NPCs shooting / clawing it): summed per tick for the
-- anim, cancelled in GMod. One hit reaching both parts in the same tick (a blast) counts once.
hook.Add("EntityTakeDamage", "gmodcraft_mcproxy", function(target, dmg)
	if not IsValid(target) then return end
	local cls = target:GetClass()
	local body
	if cls == CLASS then body = target elseif target.gmcMcBody then body, cls = target.mcBody, NPC_CLASS else return end
	local amount = dmg:GetDamage()
	local att = dmg:GetAttacker()
	dmg:SetDamage(0)
	if not IsValid(body) then return true end
	if amount ~= amount or amount <= 0 or amount == math.huge or MP.IsMcBody(att) then return true end  -- NaN / inf: nothing sent
	stats.dmgIn = (stats.dmgIn or 0) + 1
	if cls == NPC_CLASS then stats.dmgViaNpc = (stats.dmgViaNpc or 0) + 1 end
	local tick, typ = engine.TickCount(), dmg:GetDamageType()
	local last = body.lastDmg
	if last and last.tick == tick and last.src ~= cls and last.att == att and last.type == typ then
		stats.dmgDupes = (stats.dmgDupes or 0) + 1
		return true
	end
	if not last then
		last = {}
		body.lastDmg = last
	end
	last.tick, last.src, last.att, last.type = tick, cls, att, typ
	if band(typ, FIRE) ~= 0 then
		local now = CurTime()
		if now < (body.gmcFireAt or 0) then return true end
		body.gmcFireAt = now + FIRE_GAP
		pendingFire[body] = att
		return true
	end
	local fromWorld = att == nil or att == NULL or (IsValid(att) and att:IsWorld()) or not IsValid(att)
	local kind = gmodcraft.combat.Route(typ, fromWorld)
	if not kind then return true end
	-- H-approx: a projectile (bullet, buckshot, sniper, airboat: combat's PROJECTILE) on a head box counts
	-- double. Source sums a shotgun blast's pellets on one entity into one damage event (one position).
	if cls == CLASS and band(typ, gmodcraft.combat.PROJECTILE or DMG_BULLET) ~= 0 and body:HitHead(dmg:GetDamagePosition()) then
		amount = amount * 2
		stats.headshots = (stats.headshots or 0) + 1
	end
	local p = pending[body]
	if not p then
		p = { amount = 0, kind = kind, attacker = att }
		pending[body] = p
	end
	p.amount = p.amount + amount
	if IsValid(att) then p.attacker = att end
	return true
end)

-- Tick: push the summed hurts, then follow the table.
function MP.Tick()
	if not gmodcraft.McEntities then return end
	for p, h in pairs(pending) do
		pending[p] = nil
		if IsValid(p) then
			local att = h.attacker
			local scale = gmodcraft.combat and gmodcraft.combat.MobDamageScale and gmodcraft.combat.MobDamageScale() or 1  -- v34 rule
			local sid = IsValid(att) and att:IsPlayer() and att:SteamID64() or "0"
			local ok = gmodcraft.PushHostEvent({
				type = K.HostEvHurtMcEntity, requestId = p:GetMcId(), code = h.kind, ent = IsValid(att) and att:EntIndex() or 0, steamId = sid,
				flags = 0, a = math.min(math.floor(h.amount * scale * 100 + 0.5), 2147483647),
			})
			stats.hurts = stats.hurts + 1
			if not ok then stats.pushFailed = stats.pushFailed + 1 end
			Log("combat", "MC entity %d hurt %.1f (kind %d) by %s%s", p:GetMcId(), h.amount, h.kind, IsValid(att) and att:GetClass() or "world",
				ok and "" or " (not sent)")
		end
	end
	for p, att in pairs(pendingFire) do
		pendingFire[p] = nil
		if IsValid(p) then
			local sid = IsValid(att) and att:IsPlayer() and att:SteamID64() or "0"
			-- a must be non-zero (Minecraft drops a zero hurt before it looks at the flags); it isn't applied.
			local ok = gmodcraft.PushHostEvent({
				type = K.HostEvHurtMcEntity, requestId = p:GetMcId(), code = K.HurtOther, ent = IsValid(att) and att:EntIndex() or 0, steamId = sid,
				flags = K.HurtFire or 4, a = 100,
			})
			stats.fires = (stats.fires or 0) + 1
			if not ok then stats.pushFailed = stats.pushFailed + 1 end
			Log("combat", "MC entity %d set on fire by %s%s", p:GetMcId(), IsValid(att) and att:GetClass() or "world", ok and "" or " (not sent)")
		end
	end
	-- Minecraft writes the table once per server tick (20 Hz): read and follow it at that rate, and
	-- only with the map's slot known (the conversion needs it, as server/actors.lua's).
	-- T2b: while GMod holds any, their positions go out every tick. Minecraft reads at 20 Hz; a write gated
	-- like the read lands every 4th GMod tick (16.5 Hz), so a held mob stood still for a tick, then jumped.
	local now = SysTime()
	if now - (MP.readAt or 0) < 0.05 then
		if (stats.held or 0) > 0 then MP.WriteHeld() end
		return
	end
	MP.readAt = now
	local L = gmodcraft.serverLink
	MP.Sync(L and L.mcAlive and C.slot.known and gmodcraft.McEntities() or {})
	MP.WriteHeld()
end

-- A new Minecraft (attach): its entity ids mean other entities. Every body goes; the table makes new ones.
function MP.OnMcAttach()
	for id, p in pairs(byId) do
		if IsValid(p) then
			p.own = nil
			unrelate(p)
			p:Remove()
		end
		byId[id] = nil
	end
	for p in pairs(pending) do pending[p] = nil end
	for p in pairs(pendingFire) do pendingFire[p] = nil end
	if gmodcraft.SetHeldMcEntities then gmodcraft.SetHeldMcEntities({}) end
end
hook.Add("Tick", "gmodcraft_mcproxy", MP.Tick)

-- A new NPC learns about the hostile proxies there are.
hook.Add("OnEntityCreated", "gmodcraft_mcproxy", function(e)
	timer.Simple(0, function()
		if not IsValid(e) or not e:IsNPC() or e.gmcMcBody then return end
		for _, p in pairs(byId) do relate(e, p) end  -- the NPC targets (relate)
	end)
end)

function MP.DebugTable()
	local n = 0
	for _, p in pairs(byId) do if IsValid(p) then n = n + 1 end end
	return { proxies = n, created = stats.created, removed = stats.removed, hurts = stats.hurts, pushFailed = stats.pushFailed,
		held = stats.held or 0, headshots = stats.headshots or 0, dmgIn = stats.dmgIn or 0, dmgViaNpc = stats.dmgViaNpc or 0,
		dmgDupes = stats.dmgDupes or 0, takes = stats.takes or 0, gives = stats.gives or 0, punts = stats.punts or 0, physgunMobs = MP.Enabled() }
end
