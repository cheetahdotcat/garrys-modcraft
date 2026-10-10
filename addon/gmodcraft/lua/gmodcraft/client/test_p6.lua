-- Scripted P6 run (client), prepared for the client <-> remote server end-to-end test (P6b; not run
-- yet). Dev only (-gmodcraft_dev on this client, and on the server for its dev relay). Starts with
-- gmodcraft_test_p6, or when data/gmodcraft/p6test.txt exists at InitPostEntity.
--
-- Setup: this GMod client joins a remote GMod server (dedicated: srcds + the Fabric dedicated
-- server from tools/run_mc_server.sh, or someone else's listen server) as an admin; the Minecraft
-- server runs with GMODCRAFT_DEV_COMMANDS=1. This client's own Minecraft (Prism) is running.
--
--   link      protocol K.Version on both halves of the client link
--   pairing   the server sends this client a JoinInfo (never to a listen host); Minecraft joins that
--             address (kEvJoinResult OK, JoinStatus joined, its identity no longer its own world)
--   mapping   the server's Minecraft maps our player (join token verified): we become the puppet,
--             the server's stats list us as mapped, and the Minecraft server sees us (dev command)
--   water     the server writes a water grid for our slot (entIndex - 1)
--   rejoin    gmodcraft_mp_rejoin: a new joinId and token; Minecraft stays on the same server, adopts
--             them (kJoinOk for the new id) and stays mapped
-- Changes nothing in the Minecraft world. Logs "[gmodcraft-test]" PASS/FAIL lines.

local T = {}
gmodcraft.testP6 = T
local K = gmodcraft.K or {}
local band = bit.band

local function log(fmt, ...) print("[gmodcraft-test] " .. string.format(fmt, ...)) end
local results = {}
local function check(name, ok, detail)
	results[#results + 1] = { name, ok }
	log("%s %s%s", ok and "PASS" or "FAIL", name, detail and (": " .. detail) or "")
end
local function waitUntil(fn, timeout)
	local deadline = RealTime() + timeout
	while RealTime() < deadline do
		if fn() then return true end
		coroutine.yield()
	end
	return false
end
local function serverStats(maxAge)
	local D = gmodcraft.debug
	local asked = RealTime()
	D.RequestServerStats()
	waitUntil(function() return D.serverStats ~= nil and D.serverStatsAt >= asked end, maxAge or 3)
	return D.serverStats
end
local function myServerRow(ss)
	for _, p in ipairs(ss and ss.mp and ss.mp.players or {}) do
		if p.steamId == LocalPlayer():SteamID64() then return p end
	end
end

local function run()
	local ply = LocalPlayer()
	local CL = gmodcraft.clientLink
	local TU = gmodcraft.test
	log("P6 run: %s on %s (%s)", ply:Nick(), game.GetMap(), game.SinglePlayer() and "singleplayer" or "multiplayer")
	local up = waitUntil(function() return CL.mcAlive end, 120)
	check("this client's Minecraft is on the client link", up)
	if not up then log("P6TEST DONE (aborted)") return end
	local st = gmodcraft.Stats()
	check("client link: protocol " .. (gmodcraft.K.Version or 0) .. " on both halves", st.headerVersion == gmodcraft.K.Version and st.linkStats.mc.protocolVersion == gmodcraft.K.Version,
		string.format("host %d mc %d", st.headerVersion, st.linkStats.mc.protocolVersion))

	local ss = serverStats()
	local mode = ss and ss.mp and ss.mp.mode or "?"
	local row = myServerRow(ss)
	log("server: mode %s, address %s (%s)", mode, tostring(ss and ss.mp and ss.mp.address), tostring(ss and ss.mp and ss.mp.why))
	if row and row.host then
		check("this client is the listen host: no JoinInfo for it (plays in its own world)", CL.join == nil, CL.join and ("join " .. CL.join.joinId) or nil)
		log("P6TEST DONE (host: run it on another client)")
		return
	end
	check("server stats reachable (admin) with the multiplayer table", ss ~= nil and ss.mp ~= nil, ss and "no mp table" or "no answer: not an admin?")

	-- pairing
	local got = waitUntil(function() return CL.join ~= nil and CL.join.address ~= "" end, 60)
	check("JoinInfo from the server (address, join token)", got, CL.join and string.format("join %d -> %s", CL.join.joinId, CL.join.address) or "none in 60 s")
	if not got then log("P6TEST DONE (aborted)") return end
	local joinId = CL.join.joinId
	local joined = waitUntil(function()
		local js = gmodcraft.JoinStatus()
		return js ~= nil and js.joinId == joinId and js.state == K.JoinStateJoined
	end, 90)
	local js = gmodcraft.JoinStatus()
	check("Minecraft joined the server's world (JoinStatus joined, kJoinOk)", joined and CL.join.result == K.JoinOk,
		js and string.format("state %d result %d join %d %s %s", js.state, js.result, js.joinId, js.address, js.reason) or "no JoinStatus")
	local id = gmodcraft.McIdentity()
	check("Minecraft identity: in a world, not its own integrated server", id ~= nil and band(id.flags, K.IdInWorld) ~= 0 and band(id.flags, K.IdIntegratedServer) == 0,
		id and string.format("%s flags 0x%x", id.name, id.flags) or "none")

	-- mapping (the join token checked out on the Minecraft server)
	local mapped = waitUntil(function()
		local s2 = serverStats(2)
		local r = myServerRow(s2)
		return r ~= nil and r.mapped and r.used
	end, 30)
	row = myServerRow(gmodcraft.debug.serverStats)
	check("the Minecraft server mapped us (token used, McPlayers mapped)", mapped, row and string.format("mapped %s used %s token %s", tostring(row.mapped),
		tostring(row.used), tostring(row.token)) or "no row")
	local puppet = waitUntil(function() return gmodcraft.IsPuppet(ply) end, 30)
	check("puppet active (Minecraft drives the GMod player)", puppet)
	local name = TU.McName()
	local seen, r = TU.McTest("if entity " .. tostring(name))
	check("the Minecraft server sees our player (dev command)", seen, r and r.output or "no answer (GMODCRAFT_DEV_COMMANDS=1 on the MC server?)")

	-- water: the server writes our slot's grid
	local s3 = serverStats()
	local col = s3 and s3.col
	check("the server writes per-player water grids", col ~= nil and (col.waterWrites or 0) > 0, col and string.format("writes %d, last slot %s (ours %d)",
		col.waterWrites or 0, tostring(col.waterLastSlot), ply:EntIndex() - 1) or "no collision stats")

	-- rejoin: a fresh token and joinId; Minecraft is already there and only adopts them
	local before = CL.join.joinId
	CL.Rejoin()  -- what gmodcraft_mp_rejoin does
	local again = waitUntil(function() return CL.join and CL.join.joinId ~= before and CL.join.result == K.JoinOk end, 30)
	check("rejoin: a new JoinInfo, Minecraft adopts it on the same server (kJoinOk)", again, CL.join and string.format("join %d result %s", CL.join.joinId,
		tostring(CL.join.result)) or "none")
	local still = waitUntil(function()
		local r2 = myServerRow(serverStats(2))
		return r2 ~= nil and r2.mapped and r2.joinId == CL.join.joinId and r2.used
	end, 20)
	check("still mapped after the rejoin (the server lists the new join as used)", still)

	local nf = 0
	for _, x in ipairs(results) do if not x[2] then nf = nf + 1 end end
	log("P6TEST DONE: %d checks, %d failed", #results, nf)
end

local co
function T.Start()
	if co then return end
	results = {}
	co = coroutine.create(function()
		local ok, err = pcall(run)
		if not ok then
			log("ERROR %s", tostring(err))
			log("P6TEST DONE (error)")
		end
		gmodcraft.test.RestoreConVars("end of run")
	end)
	hook.Add("Think", "gmodcraft_test_p6", function()
		if not co or coroutine.status(co) == "dead" then
			hook.Remove("Think", "gmodcraft_test_p6")
			co = nil
			return
		end
		local ok, err = coroutine.resume(co)
		if not ok then log("ERROR %s", tostring(err)) end
	end)
end

concommand.Add("gmodcraft_test_p6", T.Start)
hook.Add("InitPostEntity", "gmodcraft_test_p6", function()
	if file.Exists("gmodcraft/p6test.txt", "DATA") then
		log("p6test.txt present: starting in 10 s")
		timer.Simple(10, T.Start)
	end
end)
