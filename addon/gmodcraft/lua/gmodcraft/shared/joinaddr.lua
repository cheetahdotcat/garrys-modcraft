-- Which Minecraft address a client joins (R1). The server announces the address Minecraft reports
-- (McServerInfo: its LAN address, e.g. run_mc_server.sh --lan-ip), which a player outside that LAN
-- can't reach. Unless the server set an explicit address (gmodcraft_mc_address, e4mc), the client
-- keeps the announced PORT and uses the HOST it used itself to reach the GMod server
-- (game.GetIPAddress() on the client: the address it is connected through). LAN players connected
-- via the LAN address get exactly the announced address; a friend who connected through the
-- server's public address gets <public host>:<MC port> (the owner forwards that port).
-- Pure function, no GMod calls (module/test/joinaddr_test.py runs it in LuaJIT).

local JA = {}

local function ipv4(host)
	local a, b, c, d = string.match(host, "^(%d+)%.(%d+)%.(%d+)%.(%d+)$")
	if not a then return false end
	for _, n in ipairs({ a, b, c, d }) do
		if tonumber(n) > 255 then return false end
	end
	return true
end

-- host, port of "host:port" (IPv4 or a name; one colon), else nil.
local function split(addr)
	if type(addr) ~= "string" then return nil end
	local host, port = string.match(addr, "^([%w%.%-]+):(%d+)$")
	if not host then return nil end
	port = tonumber(port)
	if not port or port < 1 or port > 65535 then return nil end
	return host, port
end
JA.split = split

-- announced: the server's address string; sameHost: the server allows the host swap (true unless
-- it set an explicit address); connectedVia: game.GetIPAddress() on the client.
-- Returns the address to join and why.
function JA.Choose(announced, sameHost, connectedVia)
	if not sameHost then return announced, "the server's address" end
	local _, port = split(announced)
	if not port then return announced, "the server's address (no port to keep)" end
	local host = split(connectedVia)
	-- Only a real IPv4 the client reached the server at: loopback/any (listen server host, singleplayer),
	-- Steam relay ("p2p:..."), IPv6 and empty values keep the announced address.
	if not host or not ipv4(host) or host == "0.0.0.0" or string.sub(host, 1, 4) == "127." then
		return announced, "the server's address (no usable connect address)"
	end
	local addr = host .. ":" .. port
	if addr == announced then return announced, "the server's address" end
	return addr, "the host this client connected to, the server's Minecraft port"
end

gmodcraft.joinaddr = JA
return JA
