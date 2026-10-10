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

function MC.Start()
	if gmodcraft.missing then return false, "module missing" end
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
function MC.Restart()
	local ok = MC.Kill()
	local deadline = RealTime() + 30
	timer.Create("gmodcraft_mc_restart", 0.5, 0, function()
		local st = MC.Status()
		if not st.running then
			local started, how = MC.Start()
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

hook.Add("InitPostEntity", "gmodcraft_launch", function()
	if gmodcraft.missing or not cvAuto:GetBool() then return end
	local st = MC.Status()
	if st.running then
		Log("launch", "Minecraft already running (pid %d)", st.pid)
		MC.lastResult = "already running (pid " .. st.pid .. ")"
		return
	end
	MC.Start()
end)
