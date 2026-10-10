-- The spawn menu's "Server" page (P8 WP3, protocol v24), admins only: the Minecraft server's rules
-- (config/gmodcraft.properties there; kAdminSetRules changes them live), the current map's slot
-- (origin, vertical offset, where it came from, the floor), a re-anchor (dry run first) and the
-- re-anchor history with undo (kAdminSlotHistory / WP2's kAdminReanchorUndo), and the world type
-- (read only: a new world is made offline, run_mc_server.sh --reset-world --level-type or the
-- launcher's "New world").
--
-- v34 (control centre): the Maps page (the server's maps, each one's slot via kAdminSlotInfo, changelevel),
-- the World page (the world and its backups via kAdminWorldList, a new world / restore via kAdminWorldOp,
-- scheduled for the dedicated server's next start) and the mob rules (difficulty, mobSpawning,
-- mobCapPercent on the MC side; the NPC relation and the mob damage factor are GMod convars, combat.lua).
--
-- Client: SA.Request(action, ...) -> net to the server; SA.state (the last snapshot), SA.OnState(fn);
-- SA.maps (the map list), SA.OnMaps(fn).
-- Server: checks the admin, sends the commands (SA.Push), collects the answers (SA.OnEvent from the
-- link's event loop), answers in chat and with a fresh snapshot.

local SA = gmodcraft.serverAdmin or {}
gmodcraft.serverAdmin = SA
SA.NET_REQ = "gmodcraft_srvadmin_req"
SA.NET_STATE = "gmodcraft_srvadmin_state"
SA.NET_MAPS = "gmodcraft_srvadmin_maps"
SA.DIFFICULTIES = { [0] = "peaceful", "easy", "normal", "hard" }
SA.GAME_MODES = { [0] = "survival", "creative", "adventure", "spectator" }
SA.WORLD_TYPES = { [0] = "mirror", "flat_void_maps", "flat_everywhere", "other" }
SA.WORLD_LABELS = { [0] = "Void (only the GMod maps)", "Flat ground, void under the maps", "Flat ground everywhere", "Another generator" }
-- The rule keys and their ServerRuleFlags bit (nil: not a flag).
SA.BOOL_RULES = { { "forceGamemode", "RuleForceGamemode", "Force the game mode on everyone (on join)" },
	{ "pvp", "RulePvp", "PvP (players hurt each other)" }, { "keepInventory", "RuleKeepInventory", "Keep inventory on death" },
	{ "digIntoMap", "RuleDigIntoMap", "Minecraft digs into the GMod map (mining, explosions)" },
	{ "noclipMc", "RuleNoclipMc", "GMod's noclip reaches Minecraft players" },
	{ "fireCrossover", "RuleFireCrossover", "Fire crosses between GMod and Minecraft (props catch MC fire, burning GMod things light MC blocks)" },
	{ "physgunMobs", "RulePhysgunMobs", "Physgun, gravgun and tools work on Minecraft's mobs, animals, carts and boats" },
	{ "mobSpawning", "RuleMobSpawning", "Minecraft spawns mobs naturally (monsters and animals)", "mobs" } }

function SA.IsAdmin(ply)
	if game.SinglePlayer() then return true end
	return IsValid(ply) and (ply:IsAdmin() or ply:IsSuperAdmin()) or false
end

-- A map name the Maps page may change to (no paths, no console syntax).
function SA.ValidMapName(m)
	return isstring(m) and #m > 0 and #m <= 64 and m:match("^[%w_%-%.]+$") ~= nil and not m:find("..", 1, true)
end

-- A world backup's stamp (YYYYMMDD-HHMMSS) from kAdminWorldList's day and second.
function SA.StampOf(day, sec)
	return os.date("!%Y%m%d-%H%M%S", math.floor(day) * 86400 + math.floor(sec))
end

-- "key=value" pairs packed into as few kAdminSetRules texts (<= limit bytes each) as fit. Returns
-- the texts, or nil, why.
function SA.Pack(pairs, limit)
	limit = limit or 60
	local out, cur = {}, ""
	for _, kv in ipairs(pairs) do
		local s = kv[1] .. "=" .. tostring(kv[2])
		if #s > limit then return nil, s .. " is longer than " .. limit .. " bytes" end
		if s:find(";", 1, true) then return nil, s .. " has a ';'" end
		if cur == "" then cur = s
		elseif #cur + 1 + #s <= limit then cur = cur .. ";" .. s
		else out[#out + 1] = cur cur = s end
	end
	if cur ~= "" then out[#out + 1] = cur end
	return out
end

-- The rules part of McServerState as a table (nil when the Minecraft server doesn't report them).
function SA.RulesOf(mss)
	local K = gmodcraft.K or {}
	if not mss or not mss.ruleFlags or bit.band(mss.ruleFlags, K.RulesValid or 1) == 0 then return nil end
	local r = { gamemode = SA.GAME_MODES[mss.gameMode or 0] or tostring(mss.gameMode), hostDamagePerMcDamage = mss.damageScale,
		worldType = SA.WORLD_TYPES[mss.worldType or 0] or tostring(mss.worldType), worldTypeId = mss.worldType, floorY = mss.floorY,
		difficulty = SA.DIFFICULTIES[mss.difficulty or 2] or "normal", mobCapPercent = mss.mobCapPercent or 100 }
	for _, b in ipairs(SA.BOOL_RULES) do r[b[1]] = bit.band(mss.ruleFlags, K[b[2]] or 0) ~= 0 end
	return r
end

if SERVER then
	util.AddNetworkString(SA.NET_REQ)
	util.AddNetworkString(SA.NET_STATE)
	util.AddNetworkString(SA.NET_MAPS)
	SA.pending = SA.pending or {}
	-- v34: the server's maps ({ name, source }) and their slots (name -> { slotX, slotZ, oy, anchor, current } | false: none
	-- yet), asked kAdminSlotInfo map by map (MAPS_IN_FLIGHT at once); waiters get the list when all are answered.
	SA.mapList = SA.mapList or { maps = {}, slots = {}, queue = {}, inFlight = 0, waiters = {} }
	-- v34: the world list (kAdminWorldList, entry by entry): { entries = { { index, kind, mib, stamp } }, count, pending, what, unsupported }
	SA.worlds = SA.worlds or { entries = {}, count = 0 }
	-- The re-anchor history each admin asked for (by SteamID64, "console"): { list = newest first
	-- { id, dy, undoOf, undoneBy }, count, want }. Per admin: two pages fetching at once don't mix.
	SA.histories = SA.histories or {}
	local function who(ply) return IsValid(ply) and ply:SteamID64() or "console" end
	function SA.HistoryOf(ply)
		local h = SA.histories[who(ply)]
		if not h then h = { list = {}, count = 0, want = 10 } SA.histories[who(ply)] = h end
		return h
	end
	SA.messages = SA.messages or {}
	local MAPS_IN_FLIGHT = 8
	local QUIET = { slotinfo = true, worlds = true }  -- no "didn't answer" chat for these (the page shows what came)

	local RESULTS = { [0] = "done", [3] = "no map slot (no Minecraft server linked?)", [5] = "malformed", [6] = "Minecraft failed (see its log)",
		[7] = "not allowed (admins only)", [8] = "nothing to do", [11] = "busy: another slot job runs", [12] = "out of range",
		[13] = "a Minecraft player is in the slot: everyone has to leave it first", [14] = "refused (unknown rule or bad value): nothing changed",
		[15] = "not on this Minecraft server (single player: use the launcher's New world)" }

	local function tell(ply, fmt, ...)
		local s = "[gmodcraft] " .. string.format(fmt, ...)
		table.insert(SA.messages, 1, os.date("%H:%M:%S ") .. s)
		while #SA.messages > 8 do table.remove(SA.messages) end
		if IsValid(ply) then ply:ChatPrint(s) else print(s) end
	end
	SA.Tell = tell

	-- One admin command. kind: "rules" | "reanchor" | "dry" | "undo" | "history". Returns ok, why.
	function SA.Push(ply, kind, code, a, flags, text, extra)
		local K = gmodcraft.K or {}
		if gmodcraft.missing or not gmodcraft.PushAdminCommand then return false, "the gmodcraft module isn't loaded" end
		local L = gmodcraft.serverLink
		if not (L and L.mcAlive) then return false, "no Minecraft server is linked" end
		local req = { steamId = IsValid(ply) and ply:SteamID64() or "0", requestId = gmodcraft.NextAdminRequest(), code = code, worldId = L.worldId,
			x = 0, y = 0, z = 0, a = a or 0, flags = bit.bor(K.AdminByAdmin or 4, flags or 0), name = text or "" }
		local sent, err = gmodcraft.PushAdminCommand(req)
		if not sent then return false, "couldn't send it (" .. tostring(err) .. ")" end
		SA.pending[req.requestId] = { ply = ply, kind = kind, text = text, a = a, at = CurTime(), extra = extra }
		return true
	end

	-- The history, entry by entry (kAdminSlotHistory a = 0, 1, ...), at most `max`.
	function SA.FetchHistory(ply, max)
		local K = gmodcraft.K or {}
		SA.histories[who(ply)] = { list = {}, count = 0, want = max or 10 }
		return SA.Push(ply, "history", K.AdminSlotHistory or 16, 0, 0, "", { index = 0 })
	end

	function SA.State(ply)
		local h = SA.HistoryOf(ply)
		local L = gmodcraft.serverLink or {}
		local s = L.slot
		local A = gmodcraft.anchor
		local mss = not gmodcraft.missing and gmodcraft.McServerState and gmodcraft.McServerState() or nil
		return { linked = L.mcAlive == true, map = game.GetMap(), rules = SA.RulesOf(mss), busy = L.slotBusy == true,
			slot = s and { ox = s.ox, oz = s.oz, oy = s.oy, slotX = s.slotX, slotZ = s.slotZ, anchorSource = s.anchorSource,
				anchorName = A and A.SourceName(s.anchorSource) or tostring(s.anchorSource),
				floorZ = A and A.result and A.result.floorZ or nil } or nil,
			history = h.list, historyCount = h.count, messages = SA.messages, worlds = SA.worlds,
			gmod = gmodcraft.combat and gmodcraft.combat.cvNpcVsMobs and { npcVsMobs = gmodcraft.combat.cvNpcVsMobs:GetBool(),
				mobDamage = gmodcraft.combat.MobDamageScale() } or nil }
	end

	function SA.SendState(ply)
		local json = util.TableToJSON(SA.State(ply)) or "{}"
		net.Start(SA.NET_STATE)
		net.WriteString(json)
		if IsValid(ply) then net.Send(ply) end
	end

	local function stateToAdmins()
		for _, p in ipairs(player.GetHumans()) do if SA.IsAdmin(p) then SA.SendState(p) end end
	end

	-- The server's maps: { { name, source = "workshop" | "game" } }, sorted. Workshop: found in a mounted addon.
	function SA.ListMaps()
		local workshop = {}
		if engine and engine.GetAddons then
			for _, a in ipairs(engine.GetAddons() or {}) do
				if a.mounted and a.title then
					local ok, found = pcall(file.Find, "maps/*.bsp", a.title)
					for _, f in ipairs(ok and found or {}) do workshop[f:gsub("%.bsp$", ""):lower()] = true end
				end
			end
		end
		local out, seen = {}, {}
		for _, f in ipairs(file.Find("maps/*.bsp", "GAME") or {}) do
			local m = f:gsub("%.bsp$", ""):lower()
			if not seen[m] and SA.ValidMapName(m) and not m:find("^background") then
				seen[m] = true
				out[#out + 1] = { name = m, source = workshop[m] and "workshop" or "game" }
			end
		end
		table.sort(out, function(a, b) return a.name < b.name end)
		return out
	end

	function SA.SendMaps(ply)
		local ML = SA.mapList
		local list = {}
		for _, m in ipairs(ML.maps) do
			list[#list + 1] = { name = m.name, source = m.source, slot = ML.slots[m.name] }
		end
		local data = util.Compress(util.TableToJSON({ maps = list, current = game.GetMap(), asking = ML.inFlight + #ML.queue }) or "{}") or ""
		net.Start(SA.NET_MAPS)
		net.WriteUInt(#data, 32)
		net.WriteData(data, #data)
		if IsValid(ply) then net.Send(ply) end
	end

	-- Asks Minecraft for the next maps' slots (at most MAPS_IN_FLIGHT at once).
	function SA.PumpSlots()
		local K = gmodcraft.K or {}
		local ML = SA.mapList
		while ML.inFlight < MAPS_IN_FLIGHT and #ML.queue > 0 do
			local m = table.remove(ML.queue, 1)
			local ok = SA.Push(nil, "slotinfo", K.AdminSlotInfo or 18, 0, 0, m, { map = m })
			if not ok then ML.queue = {} break end
			ML.inFlight = ML.inFlight + 1
		end
		if ML.inFlight == 0 and #ML.queue == 0 then
			for w in pairs(ML.waiters) do if IsValid(w) then SA.SendMaps(w) end end
			ML.waiters = {}
		end
	end

	function SA.FetchWorlds(ply)
		local K = gmodcraft.K or {}
		SA.worlds = { entries = {}, count = 0, asking = true }
		return SA.Push(ply, "worlds", K.AdminWorldList or 19, 0, 0, "", { index = 0 })
	end

	-- kEvAdminResult for one of ours. Returns true when it was ours.
	function SA.OnEvent(e)
		local K = gmodcraft.K or {}
		if e.type ~= K.EvAdminResult then return false end
		local p = SA.pending[e.requestId]
		if not p then return false end
		SA.pending[e.requestId] = nil
		local ok = e.result == (K.AdminOk or 0)
		if p.kind == "slotinfo" then
			local ML = SA.mapList
			ML.inFlight = math.max(0, ML.inFlight - 1)
			ML.slots[p.extra.map] = ok and { slotX = math.floor(e.a or 0), slotZ = math.floor(e.b or 0), oy = math.floor(e.c or 0),
				anchor = math.floor(e.d or 0), current = (e.flags or 0) == 1 } or false
			SA.PumpSlots()
			return true
		end
		if p.kind == "worlds" then
			local W = SA.worlds
			if e.result == (K.AdminUnsupported or 15) then
				W.unsupported, W.asking = true, false
			elseif ok then
				W.count = math.floor(e.flags or 0)
				local index = p.extra.index
				if index == 0 then
					W.pending, W.what = math.floor(e.a or 0), math.floor(e.b or -1)
					W.entries[1] = { index = 0, kind = "world", mib = math.floor(e.c or 0) }
				else
					W.entries[#W.entries + 1] = { index = index, kind = "backup", mib = math.floor(e.c or 0), stamp = SA.StampOf(e.a or 0, e.b or 0) }
				end
				if index + 1 < W.count and index + 1 < 50 then
					SA.Push(p.ply, "worlds", K.AdminWorldList or 19, index + 1, 0, "", { index = index + 1 })
					return true
				end
				W.asking = false
			else
				W.asking = false
				tell(p.ply, "world list: %s", RESULTS[e.result] or ("result " .. tostring(e.result)))
			end
			if IsValid(p.ply) then SA.SendState(p.ply) end
			return true
		end
		if p.kind == "worldop" then
			if ok then
				tell(p.ply, "%s: %s", p.extra.label, (e.a or 0) == 1 and "Minecraft restarts now and applies it (players are disconnected for a moment)"
					or "scheduled: applied when the Minecraft server starts next (tools/run_mc_server.sh)")
			else
				tell(p.ply, "%s: %s", p.extra.label, RESULTS[e.result] or ("result " .. tostring(e.result)))
			end
			timer.Simple(0.5, function() SA.FetchWorlds(p.ply) end)
			return true
		end
		if p.kind == "history" then
			local h = SA.HistoryOf(p.ply)
			h.count = e.flags or 0
			if ok then
				h.list[#h.list + 1] = { id = math.floor(e.a or 0), dy = math.floor(e.b or 0), undoOf = math.floor(e.c or 0), undoneBy = math.floor(e.d or 0) }
				local nextIndex = p.extra.index + 1
				if nextIndex < h.count and nextIndex < (h.want or 10) then
					SA.Push(p.ply, "history", K.AdminSlotHistory or 16, nextIndex, 0, "", { index = nextIndex })
					return true
				end
			end
			if IsValid(p.ply) then SA.SendState(p.ply) end
			return true
		end
		if p.kind == "rules" then
			if ok and p.extra and p.extra.staged then return true end  -- a staged part: the last text answers for the change
			if ok then tell(p.ply, "rules set: %s", p.text)
			elseif e.result == (K.AdminBadRule or 14) then tell(p.ply, "rules '%s': pair %d refused (unknown rule or bad value); nothing changed", p.text, e.a or 0)
			else tell(p.ply, "rules '%s': %s", p.text, RESULTS[e.result] or ("result " .. tostring(e.result))) end
		else
			local label = p.kind .. " " .. tostring(p.a)
			if ok then tell(p.ply, "%s: job %d started (the new offset shows when it's done; Minecraft's log has the outcome)", label, e.a or 0)
			else tell(p.ply, "%s: %s", label, RESULTS[e.result] or ("result " .. tostring(e.result))) end
		end
		timer.Simple(0.5, stateToAdmins)
		return true
	end

	function SA.Tick()
		local now = CurTime()
		for id, p in pairs(SA.pending) do
			if now - p.at > 15 then
				SA.pending[id] = nil
				if p.kind == "slotinfo" then
					SA.mapList.inFlight = math.max(0, SA.mapList.inFlight - 1)
					SA.PumpSlots()
				elseif p.kind == "worlds" then
					SA.worlds.asking = false
				end
				if not QUIET[p.kind] then tell(p.ply, "%s: Minecraft didn't answer", p.kind) end
				if IsValid(p.ply) then SA.SendState(p.ply) end  -- the page stops "asking the server..."
			end
		end
	end

	-- What a page asks for. Returns nothing; answers in chat and with a snapshot.
	function SA.Handle(ply, action, args)
		local K = gmodcraft.K or {}
		if not SA.IsAdmin(ply) then
			if IsValid(ply) then ply:ChatPrint("[gmodcraft] the Server page is for admins") end
			return
		end
		local ok, why = true, nil
		if action == "state" then
			ok, why = SA.FetchHistory(ply, 10)
			if not ok then SA.SendState(ply) end
		elseif action == "rules" then
			local list = {}
			for _, kv in ipairs(istable(args) and args or {}) do
				if istable(kv) and isstring(kv[1]) and kv[2] ~= nil then list[#list + 1] = { kv[1], tostring(kv[2]) } end
			end
			local texts, err = SA.Pack(list, K.AdminTextBytes or 60)
			if not texts then ok, why = false, err
			else
				-- several texts: one change (a = its id), all but the last staged; Minecraft applies the
				-- whole set with the last one, or nothing (kAdminStaged)
				local change = #texts > 1 and gmodcraft.NextAdminRequest() or 0
				for i, t in ipairs(texts) do
					local last = i == #texts
					ok, why = SA.Push(ply, "rules", K.AdminSetRules or 14, change, last and 0 or (K.AdminStaged or 32), t, { staged = not last })
					if not ok then break end
				end
			end
		elseif action == "reanchor" or action == "dry" then
			local dy = tonumber(args and args.dy)
			if not dy or dy ~= math.floor(dy) or dy == 0 or math.abs(dy) > 65536 then ok, why = false, "re-anchor: a whole number of Source units, not 0 (40 = one block)"
			else ok, why = SA.Push(ply, action, K.AdminReanchor or 12, dy, action == "dry" and (K.AdminDryRun or 16) or 0, action .. " " .. dy) end
		elseif action == "undo" then
			local id = tonumber(args and args.id)
			if not id or id <= 0 or id ~= math.floor(id) then ok, why = false, "undo: which re-anchor?"
			else ok, why = SA.Push(ply, "undo", K.AdminReanchorUndo or 13, id, 0, "undo " .. id) end
		elseif action == "maps" then
			local ML = SA.mapList
			ML.maps, ML.slots, ML.queue = SA.ListMaps(), {}, {}
			ML.waiters[ply] = true
			local L = gmodcraft.serverLink
			if L and L.mcAlive and not gmodcraft.missing and gmodcraft.PushAdminCommand then
				for _, m in ipairs(ML.maps) do if #m.name <= (K.AdminTextBytes or 60) then ML.queue[#ML.queue + 1] = m.name end end
			end
			SA.SendMaps(ply)  -- the names at once, the slots when they're answered
			SA.PumpSlots()
		elseif action == "changelevel" then
			local m = args and args.map
			local L = gmodcraft.serverLink or {}
			if not SA.ValidMapName(m) or not file.Exists("maps/" .. m .. ".bsp", "GAME") then ok, why = false, "change map: no such map " .. tostring(m)
			elseif L.slotBusy then ok, why = false, "change map: a re-anchor is running; wait until it's done"
			else
				tell(ply, "changing the map to %s", m)
				timer.Simple(1, function() RunConsoleCommand("changelevel", m) end)
			end
		elseif action == "worlds" then
			ok, why = SA.FetchWorlds(ply)
			if not ok then SA.worlds.asking = false SA.SendState(ply) end
		elseif action == "worldop" then
			local op, text, code = args and args.op, "", nil
			if op == "new" then
				code, text = K.WorldOpNew or 1, tostring(args.type)
				local known = false
				for i = 0, 2 do if SA.WORLD_TYPES[i] == text then known = true end end
				if not known then ok, why = false, "new world: unknown world type " .. text end
			elseif op == "restore" then
				code, text = K.WorldOpRestore or 2, tostring(args.stamp)
				if not text:match("^%d%d%d%d%d%d%d%d%-%d%d%d%d%d%d$") then ok, why = false, "restore: not a backup " .. text end
			elseif op == "cancel" then
				code = K.WorldOpCancel or 3
			else
				ok, why = false, "world: unknown operation " .. tostring(op)
			end
			if ok then
				local label = op == "cancel" and "cancel the world operation" or (op .. " " .. text)
				ok, why = SA.Push(ply, "worldop", K.AdminWorldOp or 20, code, args.now and op ~= "cancel" and (K.AdminRestartNow or 64) or 0, text,
					{ label = label })
			end
		elseif action == "gmodrules" then
			local npc, dmg = args and args.npcVsMobs, tonumber(args and args.mobDamage)
			if dmg and (dmg ~= dmg or dmg < 0 or dmg > 100) then ok, why = false, "mob damage factor: 0 .. 100"
			else
				if npc ~= nil then RunConsoleCommand("gmodcraft_npc_vs_mobs", npc and "1" or "0") end
				if dmg then RunConsoleCommand("gmodcraft_mob_damage_scale", string.format("%.2f", dmg)) end
				tell(ply, "GMod mob rules set%s%s", npc ~= nil and (npc and ": NPCs fight Minecraft mobs" or ": NPCs ignore Minecraft mobs") or "",
					dmg and string.format("; GMod damage on mobs x%.2f", dmg) or "")
				timer.Simple(0.2, function() SA.SendState(ply) end)
			end
		else
			ok, why = false, "unknown action " .. tostring(action)
		end
		if not ok then tell(ply, "%s", why or "failed") SA.SendState(ply) end
	end

	net.Receive(SA.NET_REQ, function(_, ply)
		local action = net.ReadString()
		local args = util.JSONToTable(net.ReadString() or "") or {}
		SA.Handle(ply, action, args)
	end)
else
	SA.state = SA.state or nil
	-- v34: more pages than the Server page follow the snapshots: SA.Listen(id, fn(state)) (one per id; a rebuild replaces it)
	SA.listeners = SA.listeners or {}
	function SA.Listen(id, fn) SA.listeners[id] = fn end
	function SA.Request(action, args)
		net.Start(SA.NET_REQ)
		net.WriteString(action)
		net.WriteString(util.TableToJSON(args or {}) or "{}")
		net.SendToServer()
	end
	net.Receive(SA.NET_STATE, function()
		SA.state = util.JSONToTable(net.ReadString() or "") or {}
		if SA.OnState then SA.OnState(SA.state) end
		for _, fn in pairs(SA.listeners) do pcall(fn, SA.state) end
	end)
	net.Receive(SA.NET_MAPS, function()
		local n = net.ReadUInt(32)
		local json = n > 0 and util.Decompress(net.ReadData(n) or "") or nil
		SA.maps = json and util.JSONToTable(json) or { maps = {} }
		if SA.OnMaps then SA.OnMaps(SA.maps) end
	end)
end
