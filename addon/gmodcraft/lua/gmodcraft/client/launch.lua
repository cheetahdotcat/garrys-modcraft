-- Starting Minecraft (docs/DESIGN.md section 11). On InitPostEntity the client checks the
-- Minecraft lock (/dev/shm/gmodcraft/minecraft-client.lock); if no Minecraft holds it, the module
-- starts the Prism instance GmodCraft. Inside Steam's pressure-vessel container the module goes
-- through steam-runtime-launch-client --alongside-steam + systemd-run --user, so Minecraft runs
-- on the host, detached from GMod. The command itself is fixed in the module; Lua only says
-- "start". The AppImage is prismAppImage in ~/.config/garrys-modcraft/config.json (the launcher
-- writes it), else env GMODCRAFT_PRISM, else ~/Documents/Software/PrismLauncher-Linux-x86_64.AppImage
-- (resolved inside the module, never exposed to Lua: it names your home directory, and clientside
-- Lua comes from the server); env GMODCRAFT_PRISM_INSTANCE overrides the instance.
--
-- Who calls McLaunch / McKill: only this file's autostart and the debug tab's Start / Restart / Kill
-- buttons (and, with -gmodcraft_dev, the gmodcraft_mc_* console commands in client/test.lua). No
-- console command in normal play: a server can run client console commands. A server's clientside
-- Lua can still call gmodcraft.McLaunch / McKill / gmodcraft.mc.* directly; that is a nuisance on
-- your own account with a fixed command line (start or stop this Prism instance), and the module
-- allows one launch attempt per 10 s.

local MC = gmodcraft.mc or {}
gmodcraft.mc = MC
local Log = gmodcraft.Log

-- Client-only and archived (FCVAR_ARCHIVE, not replicated, not userinfo): the server neither sees
-- nor sets it.
local cvAuto = CreateClientConVar("gmodcraft_autostart", "1", true, false, "Garry's Modcraft: start Minecraft when a map loads if it isn't running")

MC.lastResult = MC.lastResult or ""

function MC.Status()
	if gmodcraft.missing then return { running = false, error = "module missing" } end
	return gmodcraft.McProcess()
end

-- explicit: a person asked (the debug tab's buttons, the dev console commands). In dev runs
-- (-gmodcraft_dev) nothing else may start Minecraft: two scripted runs (2026-10-09) had the Prism
-- instance started mid-run with autostart off and InitPostEntity long past, caller not found, and it
-- took the server link and played in the player's world. The refusal logs who asked.
function MC.Start(explicit)
	if gmodcraft.missing then return false, "module missing" end
	if explicit ~= true and MC.AutostartBlocked() then
		MC.lastResult = "not started: -gmodcraft_dev (only an explicit Start in dev runs)"
		gmodcraft.Info("Minecraft start refused (-gmodcraft_dev, not explicit); caller: %s", (string.gsub(debug.traceback("", 2), "\n%s*", " | ")))
		return false, "dev run: only an explicit start"
	end
	local ok, how = gmodcraft.McLaunch()
	MC.lastResult = ok and ("started via " .. tostring(how)) or ("not started: " .. tostring(how))
	gmodcraft.Info("Minecraft %s", MC.lastResult)
	return ok, how
end

function MC.Kill()
	if gmodcraft.missing then return false, "module missing" end
	local ok, err = gmodcraft.McKill()
	MC.lastResult = ok and "SIGTERM sent to Minecraft" or ("kill: " .. tostring(err))
	gmodcraft.Info("%s", MC.lastResult)
	return ok, err
end

-- Kill, wait for the lock to go (up to 30 s), start again. The module allows one launch per 10 s
-- (a kill doesn't reset that): while the start is rate limited, keep polling until it goes through.
function MC.Restart(explicit)
	local ok = MC.Kill()
	local deadline = RealTime() + 30
	timer.Create("gmodcraft_mc_restart", 0.5, 0, function()
		local st = MC.Status()
		if not st.running then
			local started, how = MC.Start(explicit)
			if started or not tostring(how):find("rate limited", 1, true) then
				timer.Remove("gmodcraft_mc_restart")
			elseif RealTime() > deadline then
				timer.Remove("gmodcraft_mc_restart")
				MC.lastResult = "restart: still rate limited after 30 s"
				gmodcraft.Info("%s", MC.lastResult)
			end
		elseif RealTime() > deadline then
			timer.Remove("gmodcraft_mc_restart")
			MC.lastResult = "restart: Minecraft didn't exit within 30 s"
			gmodcraft.Info("%s", MC.lastResult)
		end
	end)
	return ok
end

-- Scripted and dev runs (-gmodcraft_dev) never start the Prism instance by themselves: that is the
-- player's own Minecraft and world, and a test GMod must not open it (start it with the debug tab's
-- Start MC or gmodcraft_mc_start). Asked from the module, which reads GMod's own command line.
function MC.AutostartBlocked()
	return gmodcraft.DevMode ~= nil and gmodcraft.DevMode() == true
end

hook.Add("InitPostEntity", "gmodcraft_launch", function()
	if gmodcraft.missing then return end
	if MC.AutostartBlocked() then
		MC.lastResult = "not started: -gmodcraft_dev (no autostart in dev runs)"
		gmodcraft.Info("Minecraft autostart skipped: -gmodcraft_dev (gmodcraft_autostart is %s)", cvAuto:GetString())
		return
	end
	if not cvAuto:GetBool() then return end
	local st = MC.Status()
	if st.running then
		Log("launch", "Minecraft already running (pid %d)", st.pid)
		MC.lastResult = "already running (pid " .. st.pid .. ")"
		return
	end
	MC.Start()
end)
