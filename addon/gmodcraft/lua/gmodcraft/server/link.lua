-- The server link (gmsv): ServerState every tick, the map's slot from McServerState (forwarded
-- to clients), the MC event ring, the map's collision around every MC player on the server
-- collision ring (and each player's own water grid, v14), and the debug stats relay to admins.
-- Per-player logic is in server/players.lua, multiplayer pairing in server/mp.lua.

local L = gmodcraft.serverLink or {}
gmodcraft.serverLink = L
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band = bit.band

L.epoch = L.epoch or 1
L.open = false
L.mcAlive = false
L.mcNonce = ""
L.slot = nil         -- { ox, oz, oy, slotX, slotZ, worldId, newSlot, anchorSource } (oy: v21, Source units)
L.colRegions = 0     -- regions written to the server collision ring (this Lua state)
L.colPlayers = 0     -- MC players collision streamed around, last tick
L.worldId = C.WorldId()
L.mcAttaches = 0
L.openError = nil

-- Tell one client (or everyone) the slot.
function L.SendSlot(ply)
	net.Start(gmodcraft.NET.slot)
	net.WriteBool(L.slot ~= nil)
	net.WriteInt(L.slot and L.slot.ox or 0, 32)
	net.WriteInt(L.slot and L.slot.oz or 0, 32)
	net.WriteUInt(L.worldId, 32)
	net.WriteInt(L.slot and L.slot.oy or 0, 32)  -- v21
	if IsValid(ply) then net.Send(ply) else net.Broadcast() end
end

local function onMcAttach(nonce)
	L.mcAttaches = L.mcAttaches + 1
	L.waterSlots = {}  -- a new Minecraft starts with no grids of ours
	gmodcraft.Info("Minecraft server attached to the server link (session %s, attach %d)", nonce, L.mcAttaches)
	-- A new Minecraft has nothing of ours: new collision epoch, and every player is placed again.
	L.epoch = L.epoch + 1
	L.slot = nil
	C.SetSlot(false)
	L.SendSlot()
	gmodcraft.player.OnMcAttach()
	if gmodcraft.mcproxy and gmodcraft.mcproxy.OnMcAttach then gmodcraft.mcproxy.OnMcAttach() end  -- B1: ids are the old Minecraft's
end

local function onMcDetach()
	gmodcraft.Info("Minecraft server stopped answering on the server link (no heartbeat for %d ms)", K.HeartbeatTimeoutMs or 0)
	gmodcraft.player.OnMcDetach()
	if gmodcraft.blockcol then gmodcraft.blockcol.RemoveAll() end  -- P5a: the block store is stale until MC re-sends
end

local function updateSlot()
	local mss = gmodcraft.McServerState()
	if not mss or band(mss.flags, K.McSrvSlotValid) == 0 or mss.worldId ~= L.worldId then return end
	local s = L.slot
	local oy = mss.originY or 0
	local busy = band(mss.flags, K.McSrvSlotBusy or 0) ~= 0  -- v23: a re-anchor runs
	if busy ~= (L.slotBusy or false) then
		L.slotBusy = busy
		gmodcraft.Info("map slot for %s: %s", game.GetMap(), busy and "a re-anchor is running in Minecraft" or "re-anchor finished")
	end
	if s and s.ox == mss.originX and s.oz == mss.originZ and s.oy == oy then return end
	L.slot = { ox = mss.originX, oz = mss.originZ, oy = oy, slotX = mss.slotX, slotZ = mss.slotZ, worldId = mss.worldId,
		newSlot = band(mss.flags, K.McSrvNewSlot) ~= 0, dedicated = band(mss.flags, K.McSrvDedicated) ~= 0, anchorSource = mss.anchorSource or 0 }
	if s then L.epoch = L.epoch + 1 end  -- a different origin: what MC has is in the wrong place
	C.SetSlot(true, mss.originX, mss.originZ, mss.worldId, oy)
	gmodcraft.Info("map slot for %s: (%d, %d), origin (%d, %d), vertical offset %d units (%.3f blocks, %s)%s", game.GetMap(), mss.slotX, mss.slotZ,
		mss.originX, mss.originZ, oy, oy / 40, gmodcraft.anchor and gmodcraft.anchor.SourceName(L.slot.anchorSource) or tostring(L.slot.anchorSource),
		L.slot.newSlot and " (new slot)" or "")
	L.SendSlot()
end

hook.Add("Tick", "gmodcraft_server_link", function()
	if gmodcraft.missing then return end
	gmodcraft.mapload.Start()
	gmodcraft.mapload.Status()
	if not L.open then
		local ok, err = gmodcraft.LinkOpen()
		L.open = ok == true
		if not L.open then
			if L.openError ~= err then gmodcraft.Info("server link: %s", tostring(err)) end
			L.openError = err
			return
		end
		local st = gmodcraft.Stats()
		gmodcraft.Info("server link %s (%s)%s", st.shm, st.discovery, st.discoveryNote ~= "" and (": " .. st.discoveryNote) or "")
	end
	local tf = SysTime()
	local W = gmodcraft.wirebridge
	local flags = bit.bor(K.ServerInGame, W and W.LinkFlags() or 0)  -- v16: kServerWiremod with Wiremod
	if gmodcraft.mapio then flags = bit.bor(flags, gmodcraft.mapio.LinkFlags()) end  -- v25: kServerMapIo (bridges without Wiremod)
	-- v21: the map's floor hint (kServerAnchorReady) once the map is decoded, for a new slot's offset.
	local anchor = gmodcraft.anchor and gmodcraft.anchor.Get()
	if anchor then flags = bit.bor(flags, K.ServerAnchorReady or 0) end
	local alive, nonce = gmodcraft.Frame({ flags = flags, worldId = L.worldId, epoch = L.epoch, map = game.GetMap(), anchor = anchor })
	gmodcraft.PerfAdd("sv Frame", (SysTime() - tf) * 1000)
	if alive and (not L.mcAlive or nonce ~= L.mcNonce) then onMcAttach(nonce) end
	if not alive and L.mcAlive then onMcDetach() end
	L.mcAlive, L.mcNonce = alive, nonce
	if not alive then
		-- The players still need their mode worked out: without a Minecraft everyone is in GMod
		-- mode (weapons, HUD, model and hull back). mcWanted is kept, so whoever was in MC mode
		-- goes back to it when a Minecraft attaches again.
		gmodcraft.player.Tick()
		if gmodcraft.hybrid then gmodcraft.hybrid.Tick() end  -- v17: no sets -> hybrid ends (hands stripped, loadout policy next time)
		gmodcraft.combat.Tick()
		return
	end
	updateSlot()
	for _, e in ipairs(gmodcraft.DrainEvents()) do
		local DM, TL, SR, SA = gmodcraft.demos, gmodcraft.tools, gmodcraft.slotAdmin, gmodcraft.serverAdmin
		-- P7 bridge, P7b demo, P7b-2 tool events first (admin results go to whoever owns the request id)
		-- R2: mapio watches the bridge events (and takes kEvBridgeLink) before the Wiremod side
		if not (gmodcraft.mapio and gmodcraft.mapio.OnEvent(e)) and not (W and W.OnEvent(e)) and not (DM and DM.OnEvent(e)) and not (TL and TL.OnEvent and TL.OnEvent(e)) and not (SR and SR.OnEvent(e))
			and not (SA and SA.OnEvent and SA.OnEvent(e)) then
			gmodcraft.player.OnEvent(e)
		end
	end
	if gmodcraft.tools and gmodcraft.tools.Tick then gmodcraft.tools.Tick() end  -- P7b-2: tool requests nobody answered
	if gmodcraft.slotAdmin then gmodcraft.slotAdmin.Tick() end  -- P8 WP2: slot requests nobody answered
	if gmodcraft.serverAdmin and gmodcraft.serverAdmin.Tick then gmodcraft.serverAdmin.Tick() end  -- P8 WP3: the Server page's requests
	if W then W.Tick() end  -- P7: wire entities, at most one output event per bridge
	if gmodcraft.mapio then gmodcraft.mapio.Tick() end  -- R2: map-linked bridges without a wire entity
	if gmodcraft.demos then gmodcraft.demos.Tick() end  -- P7b: demo parts waiting to spawn / to be wired
	gmodcraft.player.Tick()
	if gmodcraft.hybrid then gmodcraft.hybrid.Tick() end  -- v17: GMod weapons <-> Minecraft weapon items
	gmodcraft.combat.Tick()  -- health mirror, delayed spawns (server/combat.lua)
	gmodcraft.mp.Tick()      -- open to LAN, JoinInfo for the other players (server/mp.lua)
	-- The map's collision around every MC player (the union of their region sets), on the server's
	-- own collision ring, and each one's water grid in its HostPlayers slot (v14: entIndex - 1).
	if L.slot then
		L.colArgs = L.colArgs or { players = {}, waters = {} }
		local a = L.colArgs
		local list, waters = a.players, a.waters
		for i = #list, 1, -1 do list[i] = nil end
		for i = #waters, 1, -1 do waters[i] = nil end
		local centres, used = {}, {}
		for _, ply in ipairs(player.GetHumans()) do
			local st = gmodcraft.player.Get(ply)
			if st and st.mcWanted and st.identity then
				local pos = ply:GetPos()
				local x, y, z = C.ToMc(pos)
				list[#list + 1] = { x = x, y = y, z = z }
				local slot = ply:EntIndex() - 1
				waters[#waters + 1] = { slot = slot, x = x, y = y, z = z }
				used[slot] = true
				centres[#centres + 1] = pos
			end
		end
		-- A slot whose player left (or stopped playing Minecraft) gets an empty grid once.
		L.waterSlots = L.waterSlots or {}
		for slot in pairs(L.waterSlots) do
			if not used[slot] then gmodcraft.ColWaterClear(slot) end
		end
		L.waterSlots = used
		a.epoch, a.worldId, a.ox, a.oz, a.oy, a.budgetMs, a.water = L.epoch, L.worldId, L.slot.ox, L.slot.oz, L.slot.oy or 0, 2.5, nil
		L.colPlayers = #list
		local tc = SysTime()
		L.colRegions = L.colRegions + gmodcraft.ColUpdate(a)
		local td = SysTime()
		gmodcraft.PerfAdd("sv ColUpdate", (td - tc) * 1000)
		-- Doors, func_brush, props near them (a few times a second; P2c).
		gmodcraft.dynamic.Update(centres)
		gmodcraft.PerfAdd("sv dynamic.Update", (SysTime() - td) * 1000)
	end
end)

-- A client that has loaded (InitPostEntity) asks for the slot; its receivers exist by then, so a
-- late joiner can't miss it. Changes are pushed to everyone (SendSlot()). The same hello asks the
-- client for its Minecraft identity once (server/players.lua).
local lastHello = {}
net.Receive(gmodcraft.NET.hello, function(_, ply)
	if not IsValid(ply) then return end
	local now = RealTime()
	if lastHello[ply] and now - lastHello[ply] < 1 then return end
	lastHello[ply] = now
	L.SendSlot(ply)
	gmodcraft.player.RequestIdentity(ply)
	if gmodcraft.props and gmodcraft.props.SendKnownIcons then gmodcraft.props.SendKnownIcons(ply) end  -- G1: prop item icons
end)
local lastColAdmin, lastReq = {}, {}
hook.Add("PlayerDisconnected", "gmodcraft_server_link", function(ply)
	lastHello[ply], lastColAdmin[ply], lastReq[ply] = nil, nil, nil
end)

hook.Add("ShutDown", "gmodcraft_server_link", function()
	if L.open then gmodcraft.LinkClose() end
	L.open = false
end)

-- ---- Collision panel actions (admins only) -------------------------------------------------------
net.Receive(gmodcraft.NET.colAdmin, function(_, ply)
	if not IsValid(ply) or not (game.SinglePlayer() or ply:IsAdmin()) or gmodcraft.missing then return end
	local now = RealTime()
	if lastColAdmin[ply] and now - lastColAdmin[ply] < 1 then return end
	lastColAdmin[ply] = now
	local what = net.ReadString()
	if what == "resend" then
		gmodcraft.ColResend()
	elseif what == "clear" then
		L.epoch = L.epoch + 1  -- ColUpdate sees the new epoch: kColClear, then everything again
	end
	gmodcraft.Info("collision %s requested by %s (server link, epoch %d)", what, ply:Nick(), L.epoch)
end)

-- ---- debug stats relay (admins only, throttled) ------------------------------------------------
net.Receive(gmodcraft.NET.statsReq, function(_, ply)
	if not IsValid(ply) or not (game.SinglePlayer() or ply:IsAdmin()) then return end
	local now = RealTime()
	if lastReq[ply] and now - lastReq[ply] < 0.4 then return end
	lastReq[ply] = now
	local st = gmodcraft.missing and { missing = true } or gmodcraft.Stats()
	st.slot = L.slot
	st.colRegions = L.colRegions
	st.colPlayers = L.colPlayers
	st.col = gmodcraft.ColStats and gmodcraft.ColStats() or nil
	st.perf = gmodcraft.perf
	if gmodcraft.ColDynInfo then
		local d = gmodcraft.ColDynInfo()
		-- the entity list is the client's to show; the server's goes trimmed (net message size)
		for i = #d.entities, 33, -1 do d.entities[i] = nil end
		st.dyn = d
		st.dynScan = gmodcraft.dynamic.stats
	end
	st.mapStatus = gmodcraft.mapload.Status()
	st.mapLoad = gmodcraft.mapload.stats
	st.epoch = L.epoch
	st.worldId = L.worldId
	st.map = game.GetMap()
	st.mcAttaches = L.mcAttaches
	st.players = gmodcraft.player.DebugTable()
	st.mp = gmodcraft.mp.DebugTable()
	if gmodcraft.actors then st.combat = gmodcraft.actors.DebugTable() end
	if gmodcraft.blockcol then st.blocks = gmodcraft.blockcol.DebugTable() end
	if gmodcraft.wirebridge and gmodcraft.wirebridge.DebugTable then st.wirebridge = gmodcraft.wirebridge.DebugTable() end
	if gmodcraft.demos and gmodcraft.demos.DebugTable then st.demos = gmodcraft.demos.DebugTable() end
	if gmodcraft.tools and gmodcraft.tools.DebugTable then st.tools = gmodcraft.tools.DebugTable() end
	if not gmodcraft.missing then st.mcPlayers = gmodcraft.McPlayers() end
	local json = util.Compress(util.TableToJSON(st))
	if not json or #json > 60000 then return end
	net.Start(gmodcraft.NET.stats)
	net.WriteUInt(#json, 16)
	net.WriteData(json, #json)
	net.Send(ply)
end)

-- ---- the 3D skybox camera for clients (P5c) ------------------------------------------------------------
-- sky_camera is a server-only point entity. Clients whose eye is inside map geometry get no 3D
-- skybox from the engine (client/blocks.lua draws it itself), so its origin and scale go out as
-- networked globals (sent to every client, joining ones too). Scale 0: the map has none.
function L.PublishSkyCamera()
	local cam = ents.FindByClass("sky_camera")[1]
	local scale, pos = 0, Vector(0, 0, 0)
	if IsValid(cam) then
		pos = cam:GetPos()
		local kv = cam:GetKeyValues() or {}
		scale = tonumber(kv.scale) or tonumber(cam:GetInternalVariable("scale")) or tonumber(cam:GetInternalVariable("m_skyboxData.scale")) or 16
	end
	-- The map's 2D sky name from worldspawn (clients fall back to sv_skyname).
	local world = game.GetWorld()
	local kvw = IsValid(world) and world:GetKeyValues() or {}
	SetGlobalString("gmodcraft_skyname", tostring(kvw.skyname or GetConVar("sv_skyname"):GetString() or ""))
	SetGlobalVector("gmodcraft_skycam_pos", pos)
	SetGlobalFloat("gmodcraft_skycam_scale", scale)
	gmodcraft.Info("sky %s; 3D skybox camera: %s", GetGlobalString("gmodcraft_skyname"), scale > 0 and string.format("%s, scale %g", tostring(pos), scale) or "none on this map")
end
hook.Add("InitPostEntity", "gmodcraft_skycam", L.PublishSkyCamera)
if game.GetWorld and IsValid(game.GetWorld()) and #ents.GetAll() > 1 then L.PublishSkyCamera() end  -- Lua refresh
