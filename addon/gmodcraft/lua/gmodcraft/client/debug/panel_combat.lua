-- Debug tab: "Actors/Combat" panel (docs/DESIGN.md section 12, P4). The server link's live actor
-- table (entId, class, health fraction, flags, distance), the last Minecraft hits on GMod actors
-- (MC damage -> x scale x boss -> GMod damage, health before/after) and the last GMod hurts sent to
-- Minecraft (GMod raw -> / scale in Minecraft), the damage convars as sliders, the client link's
-- table size and the stuck arrows. Server data comes with the admin stats relay (st.combat).

local D = gmodcraft.debug
local K = gmodcraft.K or {}
local band = bit.band

local FLAGS = { { "ActorHostile", "hostile" }, { "ActorDead", "dead" }, { "ActorEssential", "essential" }, { "ActorInCombat", "combat" } }
local WEAPONS = { [0] = "unarmed", "blade", "axe", "blunt", "pierce", "arrow" }
local HURTS = { [0] = "melee", "projectile", "magic", "other" }
local ROWS_ACTORS, ROWS_LOG = 16, 8

local function flagNames(f)
	local out = {}
	for _, n in ipairs(FLAGS) do if K[n[1]] and band(f or 0, K[n[1]]) ~= 0 then out[#out + 1] = n[2] end end
	return #out > 0 and table.concat(out, ",") or "-"
end

local function slider(parent, text, convar, lo, hi, dec)
	local s = vgui.Create("DNumSlider", parent)
	s:Dock(TOP)
	s:DockMargin(8, 2, 8, 0)
	s:SetText(text)
	s:SetMinMax(lo, hi)
	s:SetDecimals(dec)
	s:SetConVar(convar)
	s:SetDark(true)
	return s
end

D.RegisterPanel("combat", {
	title = "Actors/Combat",
	order = 50,
	icon = "icon16/user_red.png",
	build = function(p)
		local scroll = vgui.Create("DScrollPanel", p)
		scroll:Dock(FILL)
		D.AddHeader(scroll, "Damage (server convars)")
		slider(scroll, "MC damage x this = GMod damage (gmodcraft_damage_scale)", "gmodcraft_damage_scale", 0.1, 20, 2)
		slider(scroll, "Boss factor: x (max health / 100) ^ this above 100 HP (gmodcraft_boss_factor, 0 = off)", "gmodcraft_boss_factor", 0, 1, 2)
		local summary = D.AddLabel(scroll)
		D.AddHeader(scroll, "Actor table (server link; client link count below)")
		local act = {}
		for i = 1, ROWS_ACTORS + 1 do act[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Last Minecraft hits on GMod actors (before -> after scaling)")
		local hits = {}
		for i = 1, ROWS_LOG do hits[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Last GMod hurts sent to Minecraft (GMod raw -> / scale in Minecraft)")
		local hurts = {}
		for i = 1, ROWS_LOG do hurts[i] = D.AddLabel(scroll) end

		local nextUpdate = 0
		p.Think = function()
			if RealTime() < nextUpdate then return end
			nextUpdate = RealTime() + 0.25
			D.RequestServerStats()
			local cb = (D.serverStats or {}).combat
			local CA = gmodcraft.clientActors or {}
			if not cb then
				summary:SetText("no server data (admins only; the server relays it with the link stats)")
				return
			end
			local s, c = cb.stats or {}, cb.cb or {}
			summary:SetText(string.format("actors %d (client link %d) | writes %d | hits %d (dropped %d, kills %d, flinches %d) | arrows %d (dropped %d, on screen %d) | hurts sent %d, dropped %d, falls %d",
				cb.count or 0, CA.count or 0, s.writes or 0, s.hits or 0, s.hitsDropped or 0, s.kills or 0, s.flinches or 0, s.arrows or 0, s.arrowsDropped or 0,
				#(CA.arrows or {}), c.forwarded or 0, c.dropped or 0, c.falls or 0))
			act[1]:SetText(string.format("  %-6s %-22s %-20s %6s %-22s %7s %s", "ent", "class", "name", "hp", "flags", "dist", "hull"))
			for i = 1, ROWS_ACTORS do
				local a = (cb.actors or {})[i]
				act[i + 1]:SetText(a and string.format("  %-6d %-22s %-20s %5.0f%% %-22s %6.1fb %.2fx%.2f", a.ent, a.class or "?", a.name or "", (a.hp or 0) * 100,
					flagNames(a.flags), a.dist or 0, a.w or 0, a.h or 0) or "")
			end
			local hl = cb.hits or {}
			for i = 1, ROWS_LOG do
				local h = hl[#hl - i + 1]
				hits[i]:SetText(h and string.format("  %s #%d by %s: %.2f MC x %.2f x %.2f = %.1f GMod (%s%s%s, kb %.2f%s) health %d -> %d",
					h.class, h.ent, h.by, h.mc, h.scale, h.boss, h.gmod, WEAPONS[h.weapon or 0] or "?", band(h.flags or 0, K.HitCritical or 1) ~= 0 and ", crit" or "",
					band(h.flags or 0, K.HitFire or 8) ~= 0 and ", fire" or "", h.d or 0, h.flinch and ", flinch" or "", h.hpBefore or 0, h.hpAfter or 0) or "")
			end
			local ul = cb.hurts or {}
			for i = 1, ROWS_LOG do
				local h = ul[#ul - i + 1]
				hurts[i]:SetText(h and string.format("  %s by %s: %.1f GMod -> %.2f MC (%s, type 0x%x, %s)", h.who, h.by, h.gmod, h.mc, HURTS[h.kind or 3] or "?",
					h.type or 0, h.why or "") or "")
			end
		end
	end,
})

-- H-approx (v31): gmodcraft_debug_mchitbox 1 draws every MC entity proxy's ray boxes (turned to the
-- body yaw; heads in red, the rest in yellow) and its Minecraft collision box (cyan).
local cvMcHitbox = CreateClientConVar("gmodcraft_debug_mchitbox", "0", false, false,
	"Garry's Modcraft: draw the Minecraft entity proxies' hit boxes (yellow, heads red) and collision boxes (cyan)")
local COL_BOX, COL_HEAD, COL_MC = Color(255, 220, 0), Color(255, 60, 60), Color(0, 220, 255)
hook.Add("PostDrawTranslucentRenderables", "gmodcraft_debug_mchitbox", function(_, skybox)
	if skybox or not cvMcHitbox:GetBool() then return end
	local MP = gmodcraft.mcproxy
	if not MP then return end
	for _, p in ipairs(ents.FindByClass(MP.CLASS)) do
		if p.Geom then
			local g = p:Geom()
			local o = p:GetPos()
			local hw, top = p:GetMcWidth() * 20, p:GetMcHeight() * 40
			render.DrawWireframeBox(o, angle_zero, Vector(-hw, -hw, 0), Vector(hw, hw, top), COL_MC, false)
			if not g.fixed then
				local ang = Angle(0, 270 - p:GetMcYawQ() * 360 / 1024, 0)
				for i = 1, g.n do
					local b = g[i]
					render.DrawWireframeBox(o, ang, Vector(b[1], b[2], b[3]), Vector(b[4], b[5], b[6]), b[7] and COL_HEAD or COL_BOX, false)
				end
			end
		end
	end
end)
