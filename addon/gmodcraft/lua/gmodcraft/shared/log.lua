-- Logging per subsystem (docs/DESIGN.md section 12): one convar per subsystem and realm turns on
-- "log to console". gmodcraft.Log(sys, fmt, ...) prints only when this realm's convar for it is on;
-- gmodcraft.Info(fmt, ...) always prints.
--
-- Names (addon/gmodcraft/README.md, "Debug logging"):
--   gmodcraft_debug_<sys>     the client realm's  (CreateClientConVar: each player's own, client.vdf)
--   gmodcraft_sv_debug_<sys>  the server realm's  (CreateConVar FCVAR_ARCHIVE: server.vdf)
-- Each realm creates only its own names. One shared name didn't work on a listen server: both
-- realms live in one process with one convar list, the server realm creates its convars first, and
-- a CreateConVar of an existing name returns that convar unchanged (GMod wiki). In the P3a live run
-- `gmodcraft_debug_input 1` printed nothing in the client realm, and the gmodcraft_debug_* values
-- were archived in cfg/server.vdf only (the server's convars). Exactly why the client's reads didn't
-- follow is not established; with one owner per name the question doesn't arise (the client's
-- names are made like gmodcraft_overlay, which works). FCVAR_REPLICATED isn't the answer: it forces
-- the server's value onto every client, so on a dedicated server a player could never turn their
-- own client logging on.

-- sys -> help, and the realms that log it
local SYSTEMS = {
	link = "client/server links: attach, slot, identity, collision",
	puppet = "puppet: teleports, acks, sanity rejections, modes",
	input = "input events sent to Minecraft (very chatty)",
	view = "camera: interpolation, F5, FOV",
	launch = "starting / stopping Minecraft",
	combat = "combat: GMod hurts sent to Minecraft, deaths, respawns, explosions",
	hybrid = "hybrid mode: weapons given / taken / stripped, weapon icons sent",
	render = "rendering: entities drawn while the eye is in solid",
	carry = "moving platforms: what carries the MC player (P6i)",
}
local REALMS = {
	link = { cl = true, sv = true },
	puppet = { sv = true },
	input = { cl = true },
	view = { cl = true },
	launch = { cl = true },
	combat = { sv = true },
	hybrid = { cl = true, sv = true },
	render = { cl = true },
	carry = { cl = true },
}

gmodcraft.logSystems = SYSTEMS
gmodcraft.logRealms = REALMS

-- The convar name for a subsystem in a realm ("cl" or "sv"), or nil if that realm doesn't log it.
function gmodcraft.LogConVarName(sys, realm)
	local r = REALMS[sys]
	if not r or not r[realm] then return nil end
	return realm == "sv" and ("gmodcraft_sv_debug_" .. sys) or ("gmodcraft_debug_" .. sys)
end

local realm = SERVER and "sv" or "cl"
local cvars_ = {}
for sys, help in pairs(SYSTEMS) do
	local name = gmodcraft.LogConVarName(sys, realm)
	if name then
		if SERVER then
			cvars_[sys] = CreateConVar(name, "0", FCVAR_ARCHIVE, "Garry's Modcraft (server): log " .. help)
		else
			cvars_[sys] = CreateClientConVar(name, "0", true, false, "Garry's Modcraft (client): log " .. help)
		end
	end
end

function gmodcraft.LogOn(sys)
	local cv = cvars_[sys]
	return cv ~= nil and cv:GetBool()
end

function gmodcraft.Log(sys, fmt, ...)
	local cv = cvars_[sys]
	if not cv or not cv:GetBool() then return end
	print(string.format("[gmodcraft %s/%s] " .. fmt, realm, sys, ...))
end

function gmodcraft.Info(fmt, ...)
	print(string.format("[gmodcraft %s] " .. fmt, realm, ...))
end

-- Game-thread timings of Garry's Modcraft's per-frame / per-tick calls (ms): gmodcraft.perf[name] =
-- { n, sum, max, last }. PerfReset() starts a new window (the perf test, the debug tab).
gmodcraft.perf = gmodcraft.perf or {}
function gmodcraft.PerfAdd(name, ms)
	local p = gmodcraft.perf[name]
	if not p then
		p = { n = 0, sum = 0, max = 0, last = 0 }
		gmodcraft.perf[name] = p
	end
	p.n, p.sum, p.last = p.n + 1, p.sum + ms, ms
	if ms > p.max then p.max = ms end
end
function gmodcraft.PerfReset()
	for k in pairs(gmodcraft.perf) do gmodcraft.perf[k] = nil end
end
