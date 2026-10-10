-- Spawnmenu tab "Garry's Modcraft", Admin group: "Server" (P8 WP3, admins). The Minecraft server's rules (edit,
-- Apply), the current map's slot, a re-anchor (dry run first) with the undo list, and the world type
-- (read only). The server checks admin rights and answers in chat (shared/serveradmin.lua).
-- v34 (control centre): a "Mobs and difficulty" section (Minecraft: difficulty, natural spawning, mob caps;
-- GMod: NPCs vs Minecraft mobs, the factor on GMod damage to mobs), and D.AdminPage for every Admin page.
-- Sections: Rules, Mobs and difficulty, (Apply), This map's slot, Re-anchor (collapsed), Combat tuning
-- (collapsed), Last answers.

local D = gmodcraft.debug
local SA = gmodcraft.serverAdmin

-- A labelled row (label + one control docked left) in parent; returns the control.
local function labelledRow(parent, label, class, labelWide, wide)
	local r = vgui.Create("DPanel", parent)
	r:Dock(TOP)
	r:DockMargin(8, 6, 8, 0)
	r:SetTall(22)
	r.Paint = function() end
	local l = vgui.Create("DLabel", r)
	l:Dock(LEFT)
	l:SetWide(labelWide or 260)
	l:SetText(label)
	l:SetDark(true)
	local c = vgui.Create(class, r)
	c:Dock(LEFT)
	c:SetWide(wide or 90)
	return c
end

-- The page's controls (admins) in scroll, a fresh DScrollPanel.
local function buildAdmin(scroll)
	do
		local status = D.AddLabel(scroll, "asking the server...")

		-- rules
		local rules = D.Section(scroll, "Rules")
		D.AddNote(rules, "config/gmodcraft.properties on the Minecraft server; Apply (below) changes them at once.")
		local row = vgui.Create("DPanel", rules)
		row:Dock(TOP)
		row:DockMargin(8, 4, 8, 0)
		row:SetTall(22)
		row.Paint = function() end
		local gmLabel = vgui.Create("DLabel", row)
		gmLabel:Dock(LEFT)
		gmLabel:SetWide(110)
		gmLabel:SetText("Game mode")
		gmLabel:SetDark(true)
		local gm = vgui.Create("DComboBox", row)
		gm:Dock(LEFT)
		gm:SetWide(140)
		for i = 0, 3 do gm:AddChoice(SA.GAME_MODES[i]) end
		local checks = {}
		for _, b in ipairs(SA.BOOL_RULES) do
			if b[4] ~= "mobs" then  -- (the mobs section's checkboxes are made below, with that section)
				local c = vgui.Create("DCheckBoxLabel", rules)
				c:Dock(TOP)
				c:DockMargin(8, 6, 8, 0)
				c:SetText(b[3])
				c:SetDark(true)
				checks[b[1]] = c
			end
		end
		local dmgRow = vgui.Create("DPanel", rules)
		dmgRow:Dock(TOP)
		dmgRow:DockMargin(8, 6, 8, 0)
		dmgRow:SetTall(22)
		dmgRow.Paint = function() end
		local dmgLabel = vgui.Create("DLabel", dmgRow)
		dmgLabel:Dock(LEFT)
		dmgLabel:SetWide(260)
		dmgLabel:SetText("GMod damage per Minecraft damage point")
		dmgLabel:SetDark(true)
		local dmg = vgui.Create("DNumberWang", dmgRow)
		dmg:Dock(LEFT)
		dmg:SetWide(70)
		dmg:SetMinMax(0.1, 1000)
		dmg:SetDecimals(2)

		-- v34: mobs and difficulty
		local mobs = D.Section(scroll, "Mobs and difficulty")
		local diff = labelledRow(mobs, "Minecraft difficulty", "DComboBox", 260, 120)
		for i = 0, 3 do diff:AddChoice(SA.DIFFICULTIES[i]) end
		for _, b in ipairs(SA.BOOL_RULES) do
			if b[4] == "mobs" then
				local c = vgui.Create("DCheckBoxLabel", mobs)
				c:Dock(TOP)
				c:DockMargin(8, 6, 8, 0)
				c:SetText(b[3])
				c:SetDark(true)
				checks[b[1]] = c
			end
		end
		local cap = labelledRow(mobs, "Mob caps (% of Minecraft's; 0 = none)", "DNumberWang", 260, 70)
		cap:SetMinMax(0, (gmodcraft.K or {}).MobCapMax or 1000)
		cap:SetDecimals(0)
		local npcFight = vgui.Create("DCheckBoxLabel", mobs)
		npcFight:Dock(TOP)
		npcFight:DockMargin(8, 6, 8, 0)
		npcFight:SetText("GMod NPCs fight hostile Minecraft mobs (off: they ignore them)")
		npcFight:SetDark(true)
		local mobDmg = labelledRow(mobs, "GMod damage on Minecraft mobs x", "DNumberWang", 260, 70)
		mobDmg:SetMinMax(0, 100)
		mobDmg:SetDecimals(2)

		-- v43: Sky & light (GMod-side rules: the day-night sync, the Minecraft sky's server default)
		local sky = D.Section(scroll, "Sky & light")
		local SUN_MODES = { [0] = "Off", "Minecraft's time drives GMod (brightness, sun, shadows, fog, sky)", "StormFox2's time drives Minecraft" }
		local sunMode = labelledRow(sky, "Day and night", "DComboBox", 160, 360)
		for i = 0, 2 do sunMode:AddChoice(SUN_MODES[i]) end
		local skipMaps = labelledRow(sky, "Maps left alone (indoor maps; names, prefix*)", "DTextEntry", 260, 260)
		local night = labelledRow(sky, "Night brightness (a darkest .. m like day)", "DComboBox", 260, 60)
		for c = string.byte("a"), string.byte("m") do night:AddChoice(string.char(c)) end
		local skyDefault = vgui.Create("DCheckBoxLabel", sky)
		skyDefault:Dock(TOP)
		skyDefault:DockMargin(8, 6, 8, 0)
		skyDefault:SetText("Minecraft sky for players who haven't chosen it themselves")
		skyDefault:SetDark(true)
		local stormFox = D.AddLabel(sky, "")

		local shown = nil  -- the rules as last received (to send only what changed)
		local shownGmod = nil
		D.AddButtons(scroll, { { "Apply", function()
			if not shown and not shownGmod then return end
			local pairs_ = {}
			if shown then  -- (the Minecraft server's rules: only once it reported them)
				local mode = gm:GetSelected()
				if mode and mode ~= shown.gamemode then pairs_[#pairs_ + 1] = { "gamemode", mode } end
				for _, b in ipairs(SA.BOOL_RULES) do
					local v = checks[b[1]]:GetChecked() and true or false
					if v ~= shown[b[1]] then pairs_[#pairs_ + 1] = { b[1], tostring(v) } end
				end
				local dv = tonumber(dmg:GetValue())
				if dv and math.abs(dv - (shown.hostDamagePerMcDamage or 0)) > 1e-3 then pairs_[#pairs_ + 1] = { "hostDamagePerMcDamage", string.format("%.2f", dv) } end
				local d = diff:GetSelected()
				if d and d ~= shown.difficulty then pairs_[#pairs_ + 1] = { "difficulty", d } end
				local cv = math.floor(tonumber(cap:GetValue()) or 100)
				if cv ~= (shown.mobCapPercent or 100) then pairs_[#pairs_ + 1] = { "mobCapPercent", tostring(cv) } end
			end
			local g = {}
			if shownGmod then
				local f = npcFight:GetChecked() and true or false
				if f ~= shownGmod.npcVsMobs then g.npcVsMobs = f end
				local m = tonumber(mobDmg:GetValue())
				if m and math.abs(m - (shownGmod.mobDamage or 1)) > 1e-3 then g.mobDamage = m end
				if shownGmod.sunSync ~= nil then  -- v43
					local sel = sunMode:GetSelected()
					for i = 0, 2 do if sel == SUN_MODES[i] and i ~= shownGmod.sunSync then g.sunSync = i end end
					local sk = tostring(skipMaps:GetValue() or "")
					if sk ~= (shownGmod.sunSyncSkipMaps or "") then g.sunSyncSkipMaps = sk end
					local nl = night:GetSelected()
					if nl and nl ~= shownGmod.sunSyncNight then g.sunSyncNight = nl end
					local sd = skyDefault:GetChecked() and true or false
					if sd ~= (shownGmod.mcSkyDefault == true) then g.mcSkyDefault = sd end
				end
			end
			if #pairs_ == 0 and next(g) == nil then chat.AddText("[gmodcraft] no rule changed") return end
			if #pairs_ > 0 then SA.Request("rules", pairs_) end
			if next(g) ~= nil then SA.Request("gmodrules", g) end
		end, 100 }, { "Refresh", function() SA.Request("state") end, 80 } })
		local world = D.AddLabel(scroll, "")

		-- slot
		local slotSec = D.Section(scroll, "This map's slot")
		local slot1 = D.AddLabel(slotSec, "")
		local slot2 = D.AddLabel(slotSec, "")
		local re = D.Section(scroll, "Re-anchor (move this map's Minecraft content up or down)", true)
		D.AddNote(re, "Nobody may be in Minecraft on this map. The World page lists the worlds and backups; the Maps page shows every map's slot and offset.")
		D.AddNote(re, "In Source units: the offset changes by exactly that, the blocks by round(units / 40). Dry run first.")
		local dyRow = vgui.Create("DPanel", re)
		dyRow:Dock(TOP)
		dyRow:DockMargin(8, 4, 8, 0)
		dyRow:SetTall(22)
		dyRow.Paint = function() end
		local dy = vgui.Create("DNumberWang", dyRow)
		dy:Dock(LEFT)
		dy:SetWide(90)
		dy:SetMinMax(-65536, 65536)
		dy:SetDecimals(0)
		dy:SetValue(40)
		local function dyValue() return math.floor(tonumber(dy:GetValue()) or 0) end
		for _, def in ipairs({ { "Dry run", "dry" }, { "Re-anchor", "reanchor" } }) do
			local b = vgui.Create("DButton", dyRow)
			b:Dock(LEFT)
			b:DockMargin(6, 0, 0, 0)
			b:SetWide(90)
			b:SetText(def[1])
			b.DoClick = function()
				local v = dyValue()
				if v == 0 then return end
				if def[2] == "reanchor" then
					Derma_Query(string.format("Move this map's Minecraft content by %d units (%d blocks)?", v, math.floor(v / 40 + (v >= 0 and 0.5 or -0.5))),
						"Re-anchor", "Move it", function() SA.Request("reanchor", { dy = v }) end, "Cancel")
				else
					SA.Request("dry", { dy = v })
				end
			end
		end
		D.AddHeader(re, "Re-anchor history (newest first)")
		local list = vgui.Create("DListView", re)
		list:Dock(TOP)
		list:DockMargin(8, 4, 8, 0)
		list:SetTall(120)
		list:SetMultiSelect(false)
		list:AddColumn("Job"):SetFixedWidth(50)
		list:AddColumn("Units")
		list:AddColumn("Blocks")
		list:AddColumn("State")
		D.AddButtons(re, { { "Undo selected", function()
			local _, line = list:GetSelectedLine()
			if not (line and line.jobId) then return end
			Derma_Query("Move re-anchor " .. line.jobId .. " back?", "Undo", "Undo it", function() SA.Request("undo", { id = line.jobId }) end, "Cancel")
		end, 120 } })
		-- server convars: they take effect from here on a listen server's host; on a dedicated server
		-- set them in its console
		local tune = D.Section(scroll, "Combat tuning (server convars)", true)
		D.AddSlider(tune, "MC damage x this = GMod damage (gmodcraft_damage_scale)", "gmodcraft_damage_scale", 0.1, 20, 2)
		D.AddSlider(tune, "Boss factor: x (max health / 100) ^ this above 100 HP (gmodcraft_boss_factor, 0 = off)", "gmodcraft_boss_factor", 0, 1, 2)
		local answers = D.Section(scroll, "Last answers")
		local msgs = {}
		for i = 1, 4 do msgs[i] = D.AddLabel(answers, "") end

		local function fill(st)
			if not IsValid(status) then return end
			st = st or {}
			status:SetText(st.linked and ("linked; map " .. tostring(st.map) .. (st.busy and " (a re-anchor is running)" or "")) or "no Minecraft server linked")
			local r = st.rules
			shown = r
			if r then
				gm:ChooseOption(r.gamemode or "survival")
				for _, b in ipairs(SA.BOOL_RULES) do checks[b[1]]:SetChecked(r[b[1]] == true) end
				dmg:SetValue(r.hostDamagePerMcDamage or 5)
				diff:ChooseOption(r.difficulty or "normal")
				cap:SetValue(r.mobCapPercent or 100)
				world:SetText(string.format("World: %s (%s); new maps' floors on y %d. New worlds: the World page (dedicated server), or the launcher's New world.",
					SA.WORLD_LABELS[r.worldTypeId or 0] or tostring(r.worldType), tostring(r.worldType), r.floorY or 64))
			else
				world:SetText("The Minecraft server doesn't report its rules (older than v24, or not linked).")
			end
			shownGmod = st.gmod
			if st.gmod then
				npcFight:SetChecked(st.gmod.npcVsMobs == true)
				mobDmg:SetValue(st.gmod.mobDamage or 1)
				if st.gmod.sunSync ~= nil then  -- v43
					sunMode:ChooseOption(SUN_MODES[st.gmod.sunSync] or SUN_MODES[1])
					skipMaps:SetValue(st.gmod.sunSyncSkipMaps or "")
					night:ChooseOption(st.gmod.sunSyncNight or "c")
					skyDefault:SetChecked(st.gmod.mcSkyDefault == true)
					stormFox:SetText((st.gmod.stormFox2 and "StormFox2 is running on this server." or "StormFox2 isn't installed (needed for \"StormFox2's time drives Minecraft\").")
						.. (st.gmod.sunSyncMapSkipped and " This map is left alone." or ""))
				end
			end
			local s = st.slot
			if s then
				slot1:SetText(string.format("slot (%d, %d), origin x %d z %d blocks; vertical offset %d units (%.3f blocks)", s.slotX or 0, s.slotZ or 0, s.ox or 0, s.oz or 0,
					s.oy or 0, (s.oy or 0) / 40))
				slot2:SetText(string.format("offset from: %s%s", tostring(s.anchorName), s.floorZ and string.format("; the map's floor z %.0f", s.floorZ) or ""))
			else
				slot1:SetText("no slot yet")
				slot2:SetText("")
			end
			list:Clear()
			for _, h in ipairs(st.history or {}) do
				local state = h.undoneBy ~= 0 and ("undone by " .. h.undoneBy) or (h.undoOf ~= 0 and ("undid " .. h.undoOf) or "in effect")
				local line = list:AddLine(h.id, h.dy, math.floor(h.dy / 40 + (h.dy >= 0 and 0.5 or -0.5)), state)
				if h.undoneBy == 0 and h.undoOf == 0 then line.jobId = h.id end
			end
			for i = 1, 4 do msgs[i]:SetText((st.messages or {})[i] or "") end
		end
		SA.OnState = fill
		fill(SA.state)
		SA.Request("state")
	end
end

-- An admins-only page in p: header (optional), then build(body) once the player is an admin. The
-- spawnmenu builds before the player's usergroup arrives: admin rights are checked again while the
-- page is shown (and on "Check again"); the page is rebuilt once they are there. scrolled: build gets
-- a DScrollPanel (false: a plain panel to dock into). The tab calls this for every Admin page.
function D.AdminPage(p, header, build, scrolled)
	local holder = vgui.Create("DPanel", p)
	holder:Dock(FILL)
	holder.Paint = function() end
	local admin
	local function rebuild()
		holder:Clear()
		local body = vgui.Create(scrolled == false and "DPanel" or "DScrollPanel", holder)
		body:Dock(FILL)
		if scrolled == false then body.Paint = function() end end
		if header then D.AddHeader(body, header) end
		admin = SA.IsAdmin(LocalPlayer())
		if not admin then
			-- the server checks again; this only keeps the controls away from non-admins
			D.AddHeader(body, "Locked: this page is for server admins.")
			D.AddLabel(body, "It unlocks by itself once the server reports your admin rights.")
			D.AddButtons(body, { { "Check again", function() rebuild() end, 100 } })
			return
		end
		local ok, err = pcall(build, body)
		if not ok then D.AddLabel(body, "panel error: " .. tostring(err)) end
	end
	rebuild()
	local nextCheck = 0
	holder.Think = function(self)
		if admin or not self:IsVisible() or RealTime() < nextCheck then return end
		nextCheck = RealTime() + 1
		if SA.IsAdmin(LocalPlayer()) then rebuild() end
	end
end

D.Register{
	id = "server",
	title = "Server",
	group = "admin",
	order = 10,
	icon = "icon16/server.png",
	desc = "The Minecraft server's rules, this map's slot and its re-anchor",
	build = buildAdmin,
}
