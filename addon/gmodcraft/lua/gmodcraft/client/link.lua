-- The client link (gmcl): HostState once per frame (Minecraft paces its frames on it), McState,
-- McIdentity -> the GMod server, the slot origin from the server, the map's collision around the
-- local player on the client collision ring (and the water grid), and the puppet's position
-- report to the server once per tick. Multiplayer (P6b): the server's JoinInfo goes to Minecraft
-- (SetJoinInfo), Minecraft's join results come back from the client event ring as a notice and to
-- the server. Leaving / a map change writes nothing: a new link session's empty JoinInfo is "no
-- change" for Minecraft (it stays on the server until a JoinInfo with a new joinId says otherwise),
-- so a changelevel doesn't bounce it; on a real quit the link dies with GMod.

local CL = gmodcraft.clientLink or {}
gmodcraft.clientLink = CL
local K = gmodcraft.K or {}
local C = gmodcraft.convert
local band, bor = bit.band, bit.bor

CL.M = CL.M or {}           -- the last McState (a table the module refills)
CL.haveMc = false           -- Minecraft alive and its McState read
CL.open = false
CL.mcAlive = false
CL.mcNonce = ""
CL.epoch = CL.epoch or 1
CL.colPos = nil             -- where collision streams around (MC feet), nil until known
CL.colRegions = 0           -- regions written to the ring (this Lua state)
CL.mcAttaches = 0
CL.state = { flags = 0, worldId = 0, epoch = 1, x = 0, y = 0, z = 0, yaw = 0, pitch = 0, originX = 0, originZ = 0, w = 0, h = 0,
	hour = 12, tickMs = 15 }
CL.reports = 0

-- Is Minecraft in its world and showing us a live player?
function CL.McInWorld()
	return CL.haveMc and band(CL.M.flags or 0, K.McInWorld) ~= 0
end

function CL.McScreenOpen()
	return CL.haveMc and band(CL.M.flags or 0, K.McScreenOpen) ~= 0
end

net.Receive(gmodcraft.NET.slot, function()
	local known = net.ReadBool()
	local ox, oz = net.ReadInt(32), net.ReadInt(32)
	local worldId = net.ReadUInt(32)
	local oy = net.ReadInt(32)  -- v21: the slot's vertical offset, Source units
	local was = C.slot
	if known and (not was.known or was.ox ~= ox or was.oz ~= oz or (was.oy or 0) ~= oy) then
		CL.epoch = CL.epoch + 1  -- everything sent so far was for another origin
	end
	C.SetSlot(known, ox, oz, worldId, oy)
	gmodcraft.Log("link", "slot %s: origin (%d, %d), vertical offset %d units, world %08x", known and "known" or "unknown", ox, oz, oy, worldId)
end)

-- ---- identity -> server ------------------------------------------------------------------------
-- Sent when it changes (a new Minecraft attaching counts as a change), and once more whenever the
-- server asks (gmodcraft_identity_req, the answer to this client's hello): not on a timer.
local idKey = nil
local function sendIdentity()
	local id = gmodcraft.McIdentity()
	if not id or band(id.flags, K.IdInWorld) == 0 or id.name == "" or id.uuid == string.rep("0", 32) then return end
	local key = id.flags .. "/" .. id.uuid .. "/" .. id.name
	if key == idKey then return end
	idKey = key
	net.Start(gmodcraft.NET.identity)
	net.WriteUInt(id.flags, 32)
	net.WriteString(id.uuid)
	net.WriteString(id.name)
	net.SendToServer()
	gmodcraft.Log("link", "identity sent: %s %s flags %d", id.name, id.uuid, id.flags)
end

net.Receive(gmodcraft.NET.identityReq, function()
	idKey = nil  -- sent again as soon as there is one (now, or when Minecraft gets into its world)
	gmodcraft.Log("link", "the server asked for our Minecraft identity")
	if CL.mcAlive then sendIdentity() end
end)

-- ---- multiplayer pairing (P6b) ------------------------------------------------------------------
-- The server's JoinInfo: where our Minecraft should play, with a single-use token (never logged or
-- kept here beyond handing it to Minecraft). Applied as soon as the link is open.
CL.join = CL.join or nil          -- { joinId, address, at, result, reason, state }
local pendingJoin = nil
local function applyJoin()
	if not pendingJoin or not CL.open then return end
	local j = pendingJoin
	if gmodcraft.SetJoinInfo(j.address, j.token, j.joinId) then
		pendingJoin = nil
		CL.join = { joinId = j.joinId, address = j.address, at = RealTime() }
		gmodcraft.Info("the server's Minecraft world: %s (join %d)", j.address ~= "" and j.address or "none (own world)", j.joinId)
	end
end

net.Receive(gmodcraft.NET.joinInfo, function()
	local id = net.ReadUInt(32)
	local address = net.ReadString()
	local token = net.ReadString()
	local sameHost = net.ReadBool()
	if id == 0 or #address > 255 or #token > 127 then return end
	-- A LAN address announced by the server becomes the host we reached the server at (shared/joinaddr.lua).
	local chosen, why = gmodcraft.joinaddr.Choose(address, sameHost, game.GetIPAddress())
	if chosen ~= address then
		gmodcraft.Info("Minecraft server: %s instead of the announced %s (%s)", chosen, address, why)
	end
	pendingJoin = { joinId = id, address = chosen, token = token }
	applyJoin()
end)

local function notice(text, bad)
	gmodcraft.Info("%s", text)
	if notification and notification.AddLegacy then
		notification.AddLegacy(text, bad and NOTIFY_ERROR or NOTIFY_GENERIC, bad and 8 or 5)
	end
end

local RESULT_TEXT = {
	[0] = { "Minecraft joined the server's world.", false },
	{ "Minecraft couldn't join the server's world%s; you play in your own world. (gmodcraft_mp_rejoin tries again)", true },
	{ "Minecraft is back in its own world.", false },
	{ "Minecraft lost the server's world%s; back in your own world.", true },
	{ "The server's Minecraft address is bad%s; Minecraft stays where it is.", true },
}

-- Minecraft's join results (client event ring): a notice, and the server hears how it ended.
local function drainJoinEvents()
	local events = gmodcraft.DrainJoinEvents()
	if #events == 0 then return end
	local js = gmodcraft.JoinStatus()
	for _, e in ipairs(events) do
		if e.type == K.EvJoinResult then
			local reason = js and js.joinId == e.joinId and js.reason or ""
			local t = RESULT_TEXT[e.result]
			if t then notice(string.format(t[1], reason ~= "" and (" (" .. reason .. ")") or ""), t[2]) end
			if CL.join and CL.join.joinId == e.joinId then
				CL.join.result, CL.join.reason, CL.join.state = e.result, reason, js and js.state
			end
			net.Start(gmodcraft.NET.joinResult)
			net.WriteUInt(e.joinId, 32)
			net.WriteUInt(math.Clamp(e.result, 0, 255), 8)
			net.WriteUInt(js and math.Clamp(js.state, 0, 255) or 0, 8)
			net.WriteString(string.sub(reason, 1, 200))
			net.SendToServer()
		end
	end
end

-- Ask the server for a fresh JoinInfo (after a failed join). Harmless if a server runs it.
function CL.Rejoin()
	net.Start(gmodcraft.NET.joinRequest)
	net.SendToServer()
	gmodcraft.Info("asked the server to pair our Minecraft again")
end
concommand.Add("gmodcraft_mp_rejoin", function() CL.Rejoin() end)

-- Loaded: ask the server for the slot (it answers, and asks for our identity). Unlike a push
-- before our receivers existed, the answer can't be lost.
hook.Add("InitPostEntity", "gmodcraft_client_link", function()
	net.Start(gmodcraft.NET.hello)
	net.SendToServer()
end)

local function onMcAttach(nonce)
	CL.mcAttaches = CL.mcAttaches + 1
	gmodcraft.Info("Minecraft client attached to the client link (session %s, attach %d)", nonce, CL.mcAttaches)
	CL.epoch = CL.epoch + 1  -- a new Minecraft has nothing of ours: kColClear, then everything again
	idKey = nil
	if gmodcraft.view then gmodcraft.view.Reset() end
end

-- ---- every frame ---------------------------------------------------------------------------------
local function menuOpen()
	if gui.IsGameUIVisible() or gui.IsConsoleVisible() then return true end
	if gmodcraft.input and gmodcraft.input.PopupOpen() then return false end
	return vgui.CursorVisible()
end
CL.MenuOpen = menuOpen

hook.Add("Think", "gmodcraft_client_link", function()
	if gmodcraft.missing then return end
	gmodcraft.mapload.Start()
	gmodcraft.mapload.Status()
	if not CL.open then
		local ok, err = gmodcraft.LinkOpen()
		CL.open = ok == true
		if not CL.open then
			if CL.openError ~= err then gmodcraft.Info("client link: %s", tostring(err)) end
			CL.openError = err
			return
		end
		local st = gmodcraft.Stats()
		gmodcraft.Info("client link %s (%s)%s", st.shm, st.discovery, st.discoveryNote ~= "" and (": " .. st.discoveryNote) or "")
	end
	applyJoin()
	drainJoinEvents()
	local ply = LocalPlayer()
	local s = CL.state
	local flags = 0
	if IsValid(ply) then flags = bor(flags, K.HostInGame) end
	if menuOpen() then flags = bor(flags, K.HostMenuOpen) end
	if C.slot.known then flags = bor(flags, K.HostSlotKnown) end
	s.flags = flags
	s.worldId = C.WorldId()
	s.epoch = CL.epoch
	if IsValid(ply) and C.slot.known then
		s.x, s.y, s.z = C.ToMc(ply:GetPos())
	end
	-- v30 (P6i): the moving entity under the MC player (s.carry*); it may turn the look, so first.
	if gmodcraft.carry then gmodcraft.carry.Update(s) else s.carryEnt = 0 end
	local look = gmodcraft.input and gmodcraft.input.look
	if look then s.yaw, s.pitch = look.yaw, look.pitch end
	s.originX, s.originZ, s.originY = C.slot.ox, C.slot.oz, C.slot.oy or 0
	s.w, s.h = ScrW(), ScrH()
	s.tickMs = engine.TickInterval() * 1000
	-- v17 hybrid mode: the server says whether it mirrors this player's weapons (H2 adds the ammo).
	local HC = gmodcraft.hybridClient
	if HC and IsValid(ply) then
		s.hybridFlags, s.clip1, s.maxClip1, s.ammo1, s.ammo2 = HC.HostFields(ply)  -- H2: the held weapon's ammo
	else
		s.hybridFlags, s.clip1, s.maxClip1, s.ammo1, s.ammo2 = 0, -1, -1, -1, -1
	end
	local tf = SysTime()
	local alive, nonce = gmodcraft.Frame(s)
	gmodcraft.PerfAdd("cl Frame", (SysTime() - tf) * 1000)
	if alive and (not CL.mcAlive or nonce ~= CL.mcNonce) then onMcAttach(nonce) end
	if not alive and CL.mcAlive then
		gmodcraft.Info("Minecraft client stopped answering on the client link")
		if gmodcraft.input then gmodcraft.input.ReleaseAll() end
	end
	CL.mcAlive, CL.mcNonce = alive, nonce
	CL.haveMc = alive and gmodcraft.McState(CL.M) ~= nil
	if not alive then return end
	sendIdentity()
	-- The map's collision around where Minecraft's player is (or will be put: the GMod player),
	-- and the water grid there. Needs the slot origin.
	if C.slot.known and IsValid(ply) then
		local x, y, z
		if gmodcraft.IsPuppet(ply) and CL.McInWorld() then
			x, y, z = CL.M.x, CL.M.y, CL.M.z
		else
			x, y, z = C.ToMc(ply:GetPos())
		end
		local p = CL.colPos or {}
		p.x, p.y, p.z = x, y, z
		CL.colPos = p
		CL.colArgs = CL.colArgs or { players = {} }
		local a = CL.colArgs
		a.epoch, a.worldId, a.ox, a.oz, a.oy, a.budgetMs = CL.epoch, C.WorldId(), C.slot.ox, C.slot.oz, C.slot.oy or 0, 2.5
		a.players[1], a.water = p, p
		local tc = SysTime()
		CL.colRegions = CL.colRegions + gmodcraft.ColUpdate(a)
		local td = SysTime()
		gmodcraft.PerfAdd("cl ColUpdate", (td - tc) * 1000)
		-- Doors, func_brush, props around there, from their networked state (P2c).
		CL.dynCentre = CL.dynCentre or {}
		CL.dynCentre[1] = C.FromMc(x, y, z)
		gmodcraft.dynamic.Update(CL.dynCentre)
		gmodcraft.PerfAdd("cl dynamic.Update", (SysTime() - td) * 1000)
	end
end)

-- ---- the puppet's report, once per tick (unreliable) ---------------------------------------------
hook.Add("Tick", "gmodcraft_client_report", function()
	local ply = LocalPlayer()
	if not CL.mcAlive or not IsValid(ply) or not gmodcraft.IsMcPlayer(ply) or not CL.McInWorld() or not C.slot.known then return end
	if band(CL.M.flags, K.McHeld) ~= 0 then return end  -- Minecraft is waiting for ground: nothing to report
	if ply:InVehicle() then return end  -- GMod drives the seat; Minecraft only follows it
	local pos = gmodcraft.view.PuppetPose()
	if not pos then return end
	net.Start(gmodcraft.NET.pos, true)
	net.WriteFloat(pos.x)
	net.WriteFloat(pos.y)
	net.WriteFloat(pos.z)
	net.WriteFloat(gmodcraft.view.eyeHeight or CL.M.eyeHeight)
	net.WriteUInt(band(CL.M.flags, 0xFFFF), 16)
	net.WriteUInt(CL.M.teleportCount, 32)
	net.WriteUInt(math.Clamp(CL.M.carryEnt or 0, 0, 65535), 16)  -- v30: the entity Minecraft carries the player with, 0 = none
	net.SendToServer()
	CL.reports = CL.reports + 1
end)

hook.Add("ShutDown", "gmodcraft_client_link", function()
	if CL.open and gmodcraft.input then gmodcraft.input.ReleaseAll() end
	if CL.open then gmodcraft.LinkClose() end
	CL.open = false
end)
