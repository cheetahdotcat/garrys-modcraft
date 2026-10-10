-- B1 live check (dev only: -gmodcraft_dev; Minecraft with dev commands). Server Lua (remote_sv.txt):
--   gmodcraft.b1live.Setup()    Minecraft: a husk (A, in front of player 1), a cow (B) and a husk (C) further out,
--                               all NoAI; their proxies listed
--   gmodcraft.b1live.Shoot()    a trace at A's proxy, then pistol bullets (player 1 as attacker) until A dies in MC
--   gmodcraft.b1live.Dip()      A/B proxy heights sampled every tick for 2 s
--   gmodcraft.b1live.Bump()     a player hull swept through the cow's proxy
--   gmodcraft.b1live.Combine()  an npc_combine_s near C: its enemy and C's health after 15 s
--   gmodcraft.b1live.Cleanup()
-- Results print "[b1live]".

local B = gmodcraft.b1live or {}
gmodcraft.b1live = B
local C = gmodcraft.convert
local function say(fmt, ...) print("[b1live] " .. string.format(fmt, ...)) end
local function after(sec, fn) timer.Simple(sec, function()
	local ok, err = pcall(fn)
	if not ok then say("error: %s", tostring(err)) end
end) end

local function mc(cmd)
	local id = math.random(1, 2 ^ 30)
	local ok, err = gmodcraft.PushDevCommand(id, cmd)
	say("mc /%s -> %s", cmd, ok and "sent" or tostring(err))
end

local function slot() return gmodcraft.serverLink.slot end
local function toMc(v) local s = slot() return C.ToMc(v, s.ox, s.oz, s.oy) end

local function floorAt(x, y, z)
	local tr = util.TraceLine({ start = Vector(x, y, z + 100), endpos = Vector(x, y, z - 2000), mask = MASK_SOLID_BRUSHONLY })
	return tr.HitPos
end

local function entry(id)
	for _, e in ipairs(gmodcraft.McEntities()) do if e.id == id then return e end end
end

local function proxyNear(v, maxd)
	local best, bd
	for id, p in pairs(gmodcraft.mcproxy.byId) do
		if IsValid(p) then
			local d = p:GetPos():Distance(v)
			if d < (maxd or 80) and (not bd or d < bd) then best, bd = p, d end
		end
	end
	return best
end

function B.Setup()
	local ply = player.GetAll()[1]
	local fwd = ply:GetAimVector()
	fwd.z = 0
	fwd:Normalize()
	local base = ply:GetPos()
	B.spots = {}
	for i, d in ipairs({ 200, 320, 700 }) do
		local p = base + fwd * d
		B.spots[i] = floorAt(p.x, p.y, base.z)
	end
	local names = { "husk", "cow", "husk" }
	for i, v in ipairs(B.spots) do
		local x, y, z = toMc(v)
		mc(string.format("summon minecraft:%s %.2f %.2f %.2f {NoAI:1b,PersistenceRequired:1b,Tags:[\"b1live\"]}", names[i], x, y + 0.05, z))
	end
	after(3, function()
		for i, v in ipairs(B.spots) do
			local p = proxyNear(v)
			B["p" .. i] = p
			local e = p and entry(p:GetMcId())
			say("spot %d %s: proxy %s mc id %s health %s pos %s (spot %s)", i, names[i], tostring(p), p and p:GetMcId() or "-",
				e and e.health or "-", p and tostring(p:GetPos()) or "-", tostring(v))
		end
		say("proxies %d", table.Count(gmodcraft.mcproxy.byId))
	end)
end

function B.Shoot()
	local ply = player.GetAll()[1]
	local p = B.p1
	if not IsValid(p) then say("shoot: no proxy A") return end
	local id = p:GetMcId()
	local eye = ply:EyePos()
	local target = p:GetPos() + Vector(0, 0, 40)
	local tr = util.TraceLine({ start = eye, endpos = eye + (target - eye) * 2, mask = MASK_SHOT, filter = ply })
	say("trace at A: hit %s (proxy %s) frac %.3f", tostring(tr.Entity), tostring(p), tr.Fraction)
	local n = 0
	local function shot()
		n = n + 1
		local e = entry(id)
		if not e or n > 12 then
			say("shoot: after %d shots A %s", n - 1, e and ("alive, health " .. e.health) or "gone from the table (dead in MC)")
			return
		end
		local src = ply:EyePos()
		local tgt = p:IsValid() and p:GetPos() + Vector(0, 0, 40) or target
		ply:FireBullets({ Src = src, Dir = (tgt - src):GetNormalized(), Damage = 25, Num = 1, Spread = Vector(0, 0, 0), Attacker = ply,
			Tracer = 1, AmmoType = "Pistol", Callback = function(att, btr) say("bullet %d hit %s", n, tostring(btr.Entity)) end })
		after(0.7, shot)
	end
	shot()
end

function B.Dip()
	local s = { {}, {} }
	local t = 0
	hook.Add("Tick", "gmodcraft_b1live_dip", function()
		t = t + 1
		for i = 1, 2 do
			local p = B["p" .. i]
			if IsValid(p) then
				local z = p:GetPos().z
				s[i].lo = math.min(s[i].lo or z, z)
				s[i].hi = math.max(s[i].hi or z, z)
			end
		end
		if t >= 132 then
			hook.Remove("Tick", "gmodcraft_b1live_dip")
			for i = 1, 2 do say("dip %d: z %.2f .. %.2f over %d ticks", i, s[i].lo or -1, s[i].hi or -1, t) end
		end
	end)
end

function B.Bump()
	local p = B.p2
	if not IsValid(p) then say("bump: no cow proxy") return end
	local c = p:GetPos()
	local tr = util.TraceHull({ start = c + Vector(-80, 0, 2), endpos = c + Vector(80, 0, 2), mins = Vector(-16, -16, 0), maxs = Vector(16, 16, 72),
		mask = MASK_PLAYERSOLID })
	say("bump: player hull through the cow hit %s at frac %.3f (cow proxy %s)", tostring(tr.Entity), tr.Fraction, tostring(p))
end

function B.Combine()
	local p = B.p3
	if not IsValid(p) then say("combine: no proxy C") return end
	local id = p:GetMcId()
	local pos = p:GetPos() + Vector(250, 0, 10)
	local npc = ents.Create("npc_combine_s")
	npc:SetPos(pos)
	npc:SetKeyValue("additionalequipment", "weapon_smg1")
	npc:Spawn()
	npc:Activate()
	B.npc = npc
	after(15, function()
		local e = entry(id)
		say("combine: enemy %s (proxy C %s), disposition %s, C %s", tostring(IsValid(npc) and npc:GetEnemy()), tostring(p),
			IsValid(npc) and IsValid(p) and tostring(npc:Disposition(p)) or "-", e and ("alive, health " .. e.health) or "gone from the table (dead in MC)")
	end)
end

function B.Cleanup()
	mc("kill @e[tag=b1live]")
	if IsValid(B.npc) then B.npc:Remove() end
end
