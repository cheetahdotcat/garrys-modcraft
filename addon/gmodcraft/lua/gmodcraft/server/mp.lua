-- Multiplayer pairing, the GMod server's side (P6b; docs/DESIGN.md 2.2).
--  * Listen server: once another (non-host) GMod player is connected, the host's Minecraft opens
--    its world to other players (kHostEvOpenToLan) and says how to reach it (McServerInfo). The
--    host alone never opens it. The host itself never gets a JoinInfo: it plays in its own world.
--  * Dedicated: the Fabric dedicated server on the server link is open from the start; its address
--    comes from McServerInfo too. gmodcraft_mc_address overrides it (NAT, port forwards).
--  * Every other client with a Minecraft (an identity it reported) gets a JoinInfo by net message,
--    to that client only: the address, a fresh joinId and a fresh single-use join token. The
--    token's SHA-256 prefix goes into the player's HostPlayers slot (server/players.lua) BEFORE the
--    token is sent; the Minecraft server checks the token against it (fabric JoinGate) and maps
--    the player to its SteamID only then. The token itself is never stored or logged here.
--  * Re-issued (new token, new joinId) when the address changes, when an unused token is about to
--    expire, after the client reports a lost connection (a few times), or when the player asks
--    (gmodcraft_mp_rejoin on its client).
--  * The client reports how each join ended (gmodcraft_join_result); the debug tab shows it.

local MP = gmodcraft.mp or {}
gmodcraft.mp = MP
local K = gmodcraft.K or {}
local band, bor = bit.band, bit.bor
local Log = gmodcraft.Log

local cvAddress = CreateConVar("gmodcraft_mc_address", "", FCVAR_ARCHIVE,
	"Garry's Modcraft: the Minecraft server address handed to players (\"host\" or \"host:port\"; empty: what Minecraft reports). For NAT / port forwards.")
local cvE4mc = CreateConVar("gmodcraft_mc_e4mc", "0", FCVAR_ARCHIVE,
	"Garry's Modcraft: listen server: hand out e4mc's relay address (when the e4mc mod is installed and ready) instead of the LAN address")
local cvOffline = CreateConVar("gmodcraft_mc_offline", "1", FCVAR_ARCHIVE,
	"Garry's Modcraft: listen server: let offline / non-Microsoft Minecraft accounts join the host's world (join tokens still gate who is who)")

MP.TOKEN_TTL = 120          -- s: the Minecraft side's default token expiry (fabric JoinGate)
MP.REISSUE_UNUSED = 100     -- s: an unused token is replaced before it expires
MP.MIN_REISSUE = 10         -- s: at most one new JoinInfo per player this often
MP.LOST_RETRIES = 3         -- automatic re-joins after kJoinLost, until the player is mapped again
MP.OPEN_RETRY = 30          -- s: a failed open-to-LAN is tried again after this
MP.RESULT_LOG_INTERVAL = 2  -- s: the window for RESULT_LOG_BURST
MP.RESULT_LOG_BURST = 4     -- "join ended" log lines per player per window (changed results only)

-- joinIds must not repeat across GMod restarts (Minecraft keeps the last one it followed): seeded
-- from the time and random, kept below 2^31.
local function seedJoinId()
	return (os.time() % 65536) * 16384 + math.random(0, 16383) + 1
end
MP.nextJoinId = MP.nextJoinId or seedJoinId()
MP.nextOpenId = MP.nextOpenId or seedJoinId()
MP.nextDevId = MP.nextDevId or seedJoinId()

local function takeId(field)
	local id = MP[field]
	MP[field] = id % 2147483646 + 1
	return id
end
function MP.NextDevRequest() return takeId("nextDevId") end

MP.open = MP.open or { pending = nil, retryAt = 0, last = nil }
MP.lastAddress = MP.lastAddress
MP.lastWhy = MP.lastWhy

function MP.Mode()
	if game.SinglePlayer() then return "singleplayer" end
	return game.IsDedicated() and "dedicated" or "listen"
end

-- Does this player play in the Minecraft world on our server link already (no JoinInfo for it)?
function MP.IsHost(ply)
	if game.SinglePlayer() then return true end
	return not game.IsDedicated() and IsValid(ply) and ply:IsListenServerHost()
end

-- The address players are handed, or nil and why not; third value: may the client swap in the host
-- it connected to (shared/joinaddr.lua)? Yes for the address Minecraft reports (often a LAN
-- address), no for an explicit gmodcraft_mc_address or an e4mc relay.
function MP.Address()
	local L = gmodcraft.serverLink
	if not L or not L.mcAlive then return nil, "no Minecraft server on the server link" end
	local info = gmodcraft.McServerInfo()
	if not info then return nil, "Minecraft hasn't said how to reach it yet" end
	if band(info.flags, K.SrvInfoOpen or 1) == 0 or info.port == 0 then return nil, "the Minecraft world isn't open to other players" end
	local override = string.Trim(cvAddress:GetString())
	if override ~= "" then
		if not override:find(":", 1, true) then override = override .. ":" .. info.port end
		return override, "gmodcraft_mc_address", false
	end
	if MP.Mode() == "listen" and cvE4mc:GetBool() and band(info.flags, K.SrvInfoE4mcReady or 0x10) ~= 0 and info.e4mc ~= "" then
		return info.e4mc, "e4mc relay", false
	end
	if info.lan ~= "" then return info.lan, "Minecraft's LAN address (clients keep the host they connected to)", true end
	return nil, "Minecraft reported no LAN address"
end

-- ---- open to LAN (listen server) -----------------------------------------------------------------
-- Listen server: open on demand, when a GMod player other than the host is connected.
local function wantOpen()
	if MP.Mode() ~= "listen" then return false end
	local guest = false
	for _, ply in ipairs(player.GetHumans()) do
		if not MP.IsHost(ply) then guest = true break end
	end
	if not guest then return false end
	local info = gmodcraft.McServerInfo()
	return not info or band(info.flags, K.SrvInfoOpen or 1) == 0
end

local function requestOpen()
	local id = takeId("nextOpenId")
	local ok = gmodcraft.PushHostEvent({ type = K.HostEvOpenToLan, steamId = "0", requestId = id, a = 0,
		flags = cvOffline:GetBool() and K.OpenAllowOffline or 0 })
	if ok then
		MP.open.pending = { id = id, at = CurTime() }
		gmodcraft.Info("asking the host's Minecraft to open its world to other players (request %d%s)", id,
			cvOffline:GetBool() and ", offline accounts allowed" or "")
	else
		MP.open.retryAt = CurTime() + 5
	end
end

function MP.OnWorldOpened(e)
	local p = MP.open.pending
	if p and e.requestId == p.id then
		MP.open.pending = nil
		MP.open.last = { id = e.requestId, result = e.result, port = e.a, at = CurTime() }
		if e.result == K.OpenOk or e.result == K.OpenAlreadyOpen then
			gmodcraft.Info("the host's Minecraft world is open on port %d", e.a)
		else
			gmodcraft.Info("the host's Minecraft couldn't open its world (result %d); trying again in %d s", e.result, MP.OPEN_RETRY)
			MP.open.retryAt = CurTime() + MP.OPEN_RETRY
		end
	else
		Log("link", "McServerInfo changed (port %d, flags 0x%x)", e.a, e.flags)
	end
end

function MP.OnMcAttach()
	MP.open.pending, MP.open.retryAt = nil, 0
	-- A new Minecraft server: whatever the players were told points at the old one (or the same
	-- address; then their Minecraft just adopts the new id and token without reconnecting).
	for _, st in pairs(gmodcraft.player.states) do
		if st.join then st.join.stale = true end
	end
end

-- ---- JoinInfo per player ---------------------------------------------------------------------------
local function short(hash) return hash and ("#" .. hash:sub(1, 8)) or "-" end

-- New token + joinId for ply, its hash published first, then the JoinInfo to that client only.
local function issue(ply, st, address, why, sameHost)
	local token, hash = gmodcraft.NewJoinToken()
	if not token then
		gmodcraft.Info("%s: no join token (%s)", ply:Nick(), tostring(hash))
		return false
	end
	local now = CurTime()
	local j = st.join or {}
	local id = takeId("nextJoinId")
	st.join = { joinId = id, address = address, tokenHash = hash, issuedAt = now, why = why, lostRetries = j.lostRetries or 0, issues = (j.issues or 0) + 1 }
	-- The hash is in HostPlayers (this tick) before the token can reach the client and its Minecraft.
	gmodcraft.player.WriteHostPlayers(true)
	net.Start(gmodcraft.NET.joinInfo)
	net.WriteUInt(id, 32)
	net.WriteString(address)
	net.WriteString(token)
	net.WriteBool(sameHost == true)
	net.Send(ply)
	token = nil  -- luacheck: ignore (not kept)
	gmodcraft.Info("%s: join %d -> %s (token %s; %s)", ply:Nick(), id, address, short(hash), why)
	return true
end

function MP.Tick()
	if gmodcraft.missing or game.SinglePlayer() then return end
	local now = CurTime()
	if (MP.nextTick or 0) > now then return end
	MP.nextTick = now + 0.25
	local L = gmodcraft.serverLink
	if L.mcAlive and wantOpen() and not MP.open.pending and now >= MP.open.retryAt then requestOpen() end
	if MP.open.pending and now - MP.open.pending.at > 15 then
		gmodcraft.Info("open-to-LAN request %d unanswered; trying again", MP.open.pending.id)
		MP.open.pending = nil
	end
	local address, why, sameHost = MP.Address()
	if address ~= MP.lastAddress or why ~= MP.lastWhy then
		gmodcraft.Info("Minecraft server address for players: %s (%s)", tostring(address or "none"), tostring(why))
		MP.lastAddress, MP.lastWhy = address, why
	end
	if not address then return end
	for _, ply in ipairs(player.GetHumans()) do
		local st = gmodcraft.player.Get(ply)
		if st and st.identity and not MP.IsHost(ply) then
			local j = st.join
			local reason
			if not j then
				reason = "first pairing"
			elseif j.address ~= address then
				reason = "the address changed"
			elseif j.stale then
				reason = "a new Minecraft server attached"
			elseif not st.mapped and not j.usedAt and now - j.issuedAt > MP.REISSUE_UNUSED then
				reason = "the token was not used in time"
			elseif j.retryAt and now >= j.retryAt then
				reason = j.retryWhy or "retry"
			end
			if st.mapped and j and not j.usedAt then
				j.usedAt = now
				j.lostRetries = 0
			end
			if reason and (not j or now - j.issuedAt >= MP.MIN_REISSUE or j.address ~= address) then issue(ply, st, address, reason, sameHost) end
		end
	end
end

-- The client reports how its Minecraft's join ended.
net.Receive(gmodcraft.NET.joinResult, function(_, ply)
	local st = gmodcraft.player.Get(ply)
	if not st then return end
	local id, result, state = net.ReadUInt(32), net.ReadUInt(8), net.ReadUInt(8)
	local reason = string.sub(net.ReadString(), 1, 200)
	local j = st.join
	st.joinResult = { joinId = id, result = result, state = state, reason = reason, at = CurTime() }
	if not j or j.joinId ~= id then return end  -- an old join, or the client's own /join
	j.result, j.reason = result, reason
	-- The client sends these as often as it likes. A changed result is logged at once; an exact
	-- repeat (same join, same result) is not logged again. A client flipping results back and forth
	-- gets at most RESULT_LOG_BURST lines per RESULT_LOG_INTERVAL seconds.
	local now = CurTime()
	if j.loggedResult ~= result then
		if now - (st.joinLogAt or -math.huge) >= MP.RESULT_LOG_INTERVAL then st.joinLogAt, st.joinLogCount = now, 0 end
		if (st.joinLogCount or 0) < MP.RESULT_LOG_BURST then
			st.joinLogCount = (st.joinLogCount or 0) + 1
			j.loggedResult = result
			gmodcraft.Info("%s: join %d ended: %s%s", ply:Nick(), id, MP.ResultName(result), reason ~= "" and (" (" .. reason .. ")") or "")
		end
	end
	if result == K.JoinLost and (j.lostRetries or 0) < MP.LOST_RETRIES then
		-- Dropped or kicked (an expired token, the server restarting): once more with a fresh token.
		j.lostRetries = (j.lostRetries or 0) + 1
		j.retryAt, j.retryWhy = CurTime() + 5, string.format("re-join after a lost connection (%d of %d)", j.lostRetries, MP.LOST_RETRIES)
	end
end)

-- The player asks to be paired again (its client's gmodcraft_mp_rejoin).
net.Receive(gmodcraft.NET.joinRequest, function(_, ply)
	local st = gmodcraft.player.Get(ply)
	if not st or MP.IsHost(ply) then return end
	local j = st.join
	if j and CurTime() - j.issuedAt < MP.MIN_REISSUE then return end
	local address, _, sameHost = MP.Address()
	if address and st.identity then issue(ply, st, address, "the player asked", sameHost) end
end)

local NAMES = { [0] = "joined", "failed", "left", "lost", "bad address" }
function MP.ResultName(r) return NAMES[r] or ("result " .. tostring(r)) end

-- Dev command results (kEvDevCommandResult; server/test.lua's relay registers the handler).
function MP.OnDevCommandResult(e)
	if MP.devHandler then MP.devHandler(e) end
end

-- For the debug tab (relayed to admins) and the dump. Never the token, only its short hash.
function MP.DebugTable()
	local info = not gmodcraft.missing and gmodcraft.McServerInfo() or nil
	local out = { mode = MP.Mode(), address = MP.lastAddress, why = MP.lastWhy, override = cvAddress:GetString(), e4mc = cvE4mc:GetBool(),
		offline = cvOffline:GetBool(), info = info, open = MP.open.last, openPending = MP.open.pending and MP.open.pending.id or nil, players = {} }
	local now = CurTime()
	for _, ply in ipairs(player.GetHumans()) do
		local st = gmodcraft.player.Get(ply)
		if st then
			local j = st.join
			out.players[#out.players + 1] = {
				nick = ply:Nick(), steamId = ply:SteamID64(), host = MP.IsHost(ply), mcName = st.identity and st.identity.name, mapped = st.mapped,
				slot = ply:EntIndex() - 1, joinId = j and j.joinId, token = j and short(j.tokenHash), age = j and (now - j.issuedAt), why = j and j.why,
				used = j and j.usedAt ~= nil, result = st.joinResult and MP.ResultName(st.joinResult.result), reason = st.joinResult and st.joinResult.reason,
			}
		end
	end
	return out
end
