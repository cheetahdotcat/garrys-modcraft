-- Debug tab: "Links" panel (docs/DESIGN.md section 12). Both links: shm names, nonce, discovery
-- files, protocol, heartbeat ages, seqlock seqs, each ring's counters; multiplayer (P6b: the
-- player map, the Minecraft server address, each player's pairing state); the Minecraft process
-- with Start / Restart / Kill; the log toggles and Dump state. The server link's values come from
-- the server (admins only, throttled).

local D = gmodcraft.debug

local function fmtBytes(n)
	n = n or 0
	if n >= 1048576 then return string.format("%.1f MiB", n / 1048576) end
	if n >= 1024 then return string.format("%.1f KiB", n / 1024) end
	return string.format("%d B", n)
end

local function age(ms)
	if not ms or ms < 0 then return "never" end
	return string.format("%.0f ms", ms)
end

local JOIN_STATE = { [0] = "in its own world", "connecting", "joined" }
local JOIN_RESULT = { [0] = "joined", "failed", "left", "lost", "bad address" }

-- Lines for the Multiplayer section: this client's pairing, then (admins) the server's view.
function D.MultiplayerLines(ss)
	local out = {}
	local CL = gmodcraft.clientLink
	local j = CL.join
	local js = not gmodcraft.missing and gmodcraft.JoinStatus and gmodcraft.JoinStatus() or nil
	local id = not gmodcraft.missing and gmodcraft.McIdentity() or nil
	out[#out + 1] = string.format("  this client: %s | Minecraft %s%s, last result %s%s",
		j and string.format("join %d -> %s (%.0f s ago)", j.joinId, j.address ~= "" and j.address or "own world", RealTime() - j.at) or "no JoinInfo (singleplayer, the listen host, or not paired yet)",
		js and (JOIN_STATE[js.state] or tostring(js.state)) or "?", js and js.address ~= "" and (" on " .. js.address) or "",
		js and (JOIN_RESULT[js.result] or tostring(js.result)) or "-", js and js.reason ~= "" and (" (" .. js.reason .. ")") or "")
	out[#out + 1] = id and string.format("  Minecraft identity: %s %s flags 0x%x", id.name, id.uuid, id.flags) or "  Minecraft identity: none yet"
	local mp = ss and ss.mp
	if not mp then
		out[#out + 1] = "  server: (no answer: not an admin, or no module)"
		return out
	end
	local info = mp.info
	out[#out + 1] = string.format("  server: %s | Minecraft world %s%s%s | players get: %s (%s)%s", mp.mode,
		info and (bit.band(info.flags, 1) ~= 0 and ("open on port " .. info.port) or "closed") or "unknown",
		info and info.lan ~= "" and (", LAN " .. info.lan) or "", info and info.e4mc ~= "" and (", e4mc " .. info.e4mc) or "",
		tostring(mp.address or "nothing"), tostring(mp.why or "-"), mp.override ~= "" and (" [gmodcraft_mc_address " .. mp.override .. "]") or "")
	if mp.openPending or mp.open then
		out[#out + 1] = string.format("  open to LAN: %s", mp.openPending and ("request " .. mp.openPending .. " pending") or
			string.format("request %d -> result %d, port %d", mp.open.id, mp.open.result, mp.open.port))
	end
	local mcBySteam = {}
	for _, m in ipairs(ss.mcPlayers or {}) do mcBySteam[m.steamId] = m end
	for _, pl in ipairs(mp.players or {}) do
		local m = mcBySteam[pl.steamId]
		out[#out + 1] = string.format("  [slot %d] %s: MC %s, %s | %s", pl.slot or -1, pl.nick, tostring(pl.mcName or "-"),
			m and ("in the MC world as " .. m.name) or (pl.mapped and "mapped" or "not in the MC world"),
			pl.host and "host (plays in its own world)" or (pl.joinId and string.format("join %d token %s %.0f s ago (%s)%s, last: %s%s", pl.joinId, pl.token or "-",
				pl.age or 0, pl.why or "-", pl.used and ", used" or "", pl.result or "-", pl.reason and pl.reason ~= "" and (" (" .. pl.reason .. ")") or "") or "no JoinInfo yet"))
	end
	for _, m in ipairs(ss.mcPlayers or {}) do
		if m.steamId == "0" then out[#out + 1] = string.format("  MC player %s (%s): not mapped to a GMod player", m.name, m.uuid) end
	end
	return out
end

local function ringLine(name, h, m)
	h, m = h or {}, m or {}
	return string.format("  %-10s host: %6d msgs %9s drops %d fill %d hw %d %s/s | mc: %6d msgs %9s drops %d fill %d %s/s", name,
		h.messages or 0, fmtBytes(h.bytes), h.drops or 0, h.fill or 0, h.highWater or 0, fmtBytes(h.bytesPerSec), m.messages or 0,
		fmtBytes(m.bytes), m.drops or 0, m.fill or 0, fmtBytes(m.bytesPerSec))
end

-- Lines describing one link's Stats() table.
function D.LinkLines(st, rings, seqs)
	local out = {}
	if not st then return { "  (no data)" } end
	if st.missing then return { "  binary module not loaded" } end
	if not st.open then return { "  link not open" } end
	local ls = st.linkStats or {}
	local h, m = ls.host or {}, ls.mc or {}
	out[#out + 1] = string.format("  shm /dev/shm/%s (%s), nonce %s, protocol %d (MC side %d)", st.shm, fmtBytes(st.bytes), st.nonce,
		st.headerVersion or 0, m.protocolVersion or 0)
	out[#out + 1] = "  discovery " .. (st.discovery ~= "" and st.discovery or "(fixed name, none)") .. (st.discoveryNote ~= "" and ("  !! " .. st.discoveryNote) or "")
	if (st.gcRemoved or 0) > 0 then
		out[#out + 1] = string.format("  stale segments removed when this link was created: %d (%s)", st.gcRemoved, st.gcNote or "")
	end
	if st.overlayResets then
		out[#out + 1] = string.format("  overlay: %d frames taken, %d dropped after a new Minecraft attached (%d restarts)", st.overlayTaken or 0,
			st.overlaySkipped or 0, st.overlayResets)
	end
	out[#out + 1] = string.format("  Minecraft %s: its heartbeat changed %s ago (host's %s ago); MC session %s, attaches %d, MC sees host %s, peer-down %d/%d",
		st.mcAlive and "ALIVE" or "not attached", age(st.mcBeatAgeMs), age(st.hostBeatAgeMs), st.mcNonce, m.attachCount or 0,
		m.peerAlive and "alive" or "dead", h.peerDownCount or 0, m.peerDownCount or 0)
	out[#out + 1] = string.format("  timing: host tick %.1f ms frame %.1f ms | MC tick %.1f ms frame %.1f ms | updates host %d mc %d | overlay frames MC %d host %d",
		h.tickMs or 0, h.frameMs or 0, m.tickMs or 0, m.frameMs or 0, h.updateCount or 0, m.updateCount or 0, m.overlayFrames or 0, h.overlayFrames or 0)
	local sq = {}
	for _, k in ipairs(seqs) do sq[#sq + 1] = k .. " " .. tostring(st[k] or 0) end
	out[#out + 1] = "  seq: " .. table.concat(sq, ", ")
	for _, r in ipairs(rings) do out[#out + 1] = ringLine(r, h.rings and h.rings[r], m.rings and m.rings[r]) end
	return out
end

D.RegisterPanel("links", {
	title = "Links",
	order = 10,
	icon = "icon16/connect.png",
	build = function(p)
		local scroll = vgui.Create("DScrollPanel", p)
		scroll:Dock(FILL)
		D.AddHeader(scroll, "Client link (this GMod client <-> its Minecraft client)")
		local cl = {}
		for i = 1, 12 do cl[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Server link (GMod server <-> Minecraft server; relayed to admins)")
		local sv = {}
		for i = 1, 14 do sv[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Multiplayer (pairing: JoinInfo, join tokens, player map)")
		local mpl = {}
		for i = 1, 14 do mpl[i] = D.AddLabel(scroll) end
		D.AddButtons(scroll, {
			{ "Rejoin the server's MC world", function() gmodcraft.clientLink.Rejoin() end },
		})
		D.AddHeader(scroll, "Minecraft process")
		local mc = {}
		for i = 1, 4 do mc[i] = D.AddLabel(scroll) end
		D.AddButtons(scroll, {
			{ "Start MC", function() gmodcraft.mc.Start() end },
			{ "Restart MC", function() gmodcraft.mc.Restart() end },
			{ "Kill MC", function() gmodcraft.mc.Kill() end },
			{ "Dump state", function() D.lastDump = D.Dump() end },
		})
		D.AddHeader(scroll, "Log to console")
		-- One convar per subsystem and realm (shared/log.lua): the client's are ours; the server's
		-- can be set from here on a listen server (we are the host) or by an admin's console.
		for sys, help in SortedPairs(gmodcraft.logSystems or {}) do
			local cl, sv = gmodcraft.LogConVarName(sys, "cl"), gmodcraft.LogConVarName(sys, "sv")
			if cl then D.AddCheck(scroll, string.format("%s (client): %s  [%s]", sys, help, cl), cl) end
			if sv then
				if GetConVar(sv) then
					D.AddCheck(scroll, string.format("%s (server): %s  [%s]", sys, help, sv), sv)
				else
					-- not a listen server: the server's convar only exists there (admin console / rcon)
					D.AddLabel(scroll, string.format("%s (server): %s  [%s, server only]", sys, help, sv))
				end
			end
		end

		local function fill(labels, lines)
			for i, l in ipairs(labels) do l:SetText(lines[i] or "") end
		end
		local nextUpdate = 0
		p.Think = function()
			if RealTime() < nextUpdate then return end
			nextUpdate = RealTime() + 0.25
			D.RequestServerStats()
			local st = not gmodcraft.missing and gmodcraft.Stats() or { missing = true }
			local CL = gmodcraft.clientLink
			local lines = D.LinkLines(st, { "input", "collision", "render", "events" }, { "hostStateSeq", "mcStateSeq", "mcIdentitySeq", "joinInfoSeq", "joinStatusSeq" })
			lines[#lines + 1] = string.format("  slot %s origin (%d, %d) | collision regions sent %d | epoch %d | render msgs drained %d | torn McState reads %d",
				gmodcraft.convert.slot.known and "known" or "unknown", gmodcraft.convert.slot.ox, gmodcraft.convert.slot.oz, CL.colRegions or 0,
				CL.epoch, st.renderMessages or 0, st.mcStateTorn or 0)
			fill(cl, lines)
			local ss = D.serverStats
			local sl = D.LinkLines(ss, { "hostEvents", "collision", "events", "blocks" }, { "serverStateSeq", "mcServerStateSeq", "mcServerInfoSeq", "hostPlayersSeq", "mcPlayersSeq" })
			if ss and ss.open then
				local slot = ss.slot
				sl[#sl + 1] = string.format("  map %s world %08x epoch %d | slot %s | collision: %d regions, %d MC players | block msgs %d (clear-all %d) | MC attaches %d",
					ss.map or "?", ss.worldId or 0, ss.epoch or 0, slot and string.format("(%d, %d) origin (%d, %d)", slot.slotX, slot.slotZ, slot.ox, slot.oz) or "unknown",
					ss.colRegions or 0, ss.colPlayers or 0, ss.blockMessages or 0, ss.blockClears or 0, ss.mcAttaches or 0)
				for _, pl in ipairs(ss.players or {}) do
					sl[#sl + 1] = string.format("  player %s (%s): MC %s %s, %s, mode %s, puppet %s, pending %s, need %s",
						pl.nick, pl.steamId, tostring(pl.mcName), pl.mapped and "mapped" or "unmapped", pl.mcMode and "MC mode" or "-",
						pl.mcWanted and "MC" or "GMod", tostring(pl.puppet), pl.pending and (pl.pending.reason .. " #" .. pl.pending.id) or "-", tostring(pl.needTeleport))
				end
			end
			sl[#sl + 1] = ss and string.format("  (received %.1f s ago)", RealTime() - D.serverStatsAt) or "  (no answer: not an admin, or the server has no module)"
			fill(sv, sl)
			fill(mpl, D.MultiplayerLines(ss))
			local ps = gmodcraft.mc.Status()
			fill(mc, {
				string.format("  %s%s | lock %s", ps.running and ("RUNNING, pid " .. ps.pid) or "not running", ps.launching and " (launch helper still running)" or "", ps.lock or "?"),
				"  last action: " .. (gmodcraft.mc.lastResult or "") .. (ps.how ~= "" and ("  [" .. tostring(ps.how) .. ", exit " .. tostring(ps.launchStatus) .. "]") or ""),
				"  launch log: " .. string.sub((ps.log or ""):gsub("\n", " | "), 1, 200) .. (D.lastDump and ("   dump: data/" .. D.lastDump) or ""),
				"  Prism: prismAppImage in ~/.config/garrys-modcraft/config.json, else GMODCRAFT_PRISM, else the default (the module resolves it; a failed launch names the path on stderr)",
			})
		end
	end,
})
