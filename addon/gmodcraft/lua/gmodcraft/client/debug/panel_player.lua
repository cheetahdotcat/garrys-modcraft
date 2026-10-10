-- Debug tab: "Player" panel (docs/DESIGN.md section 12). Live McState, the GMod <-> MC position
-- error, corrections per second, the camera's tick interpolation, mouse look, and the input log.
--
-- "Corrections" = frames where the GMod player's origin jumped against the MC pose by more than
-- 8 units since the previous frame (prediction / server snaps; DESIGN.md risk R2), per second,
-- plus the server's count of GMod-side moves (SetPos) it sent Minecraft after.

local D = gmodcraft.debug
local K = gmodcraft.K or {}
local band = bit.band

local FLAGS = { "McInWorld", "McScreenOpen", "McOnGround", "McSneaking", "McSprinting", "McDead", "McSwimming", "McFlying", "McHeld" }

local function flagNames(f)
	local out = {}
	for _, n in ipairs(FLAGS) do if K[n] and band(f or 0, K[n]) ~= 0 then out[#out + 1] = n:sub(3) end end
	return #out > 0 and table.concat(out, " ") or "-"
end

-- Error / corrections tracking (every frame, also while the panel is closed: the dump uses it).
local P = { err = 0, corrections = 0, perSec = 0, windowAt = 0, windowN = 0 }
D.playerStats = P
local lastOff
hook.Add("Think", "gmodcraft_debug_player", function()
	local V, ply = gmodcraft.view, LocalPlayer()
	if not V or not V.valid or not IsValid(ply) then lastOff = nil return end
	local off = ply:GetPos() - V.feet
	P.err = off:Length()
	if lastOff and (off - lastOff):Length() > 8 then
		P.corrections = P.corrections + 1
		P.windowN = P.windowN + 1
	end
	lastOff = off
	if RealTime() - P.windowAt >= 1 then
		P.perSec, P.windowN, P.windowAt = P.windowN / math.max(RealTime() - P.windowAt, 1), 0, RealTime()
	end
end)

D.RegisterPanel("player", {
	title = "Player",
	order = 20,
	icon = "icon16/user.png",
	build = function(p)
		local scroll = vgui.Create("DScrollPanel", p)
		scroll:Dock(FILL)
		D.AddHeader(scroll, "McState (live)")
		local ms = {}
		for i = 1, 8 do ms[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Puppet")
		local pu = {}
		for i = 1, 6 do pu[i] = D.AddLabel(scroll) end
		D.AddHeader(scroll, "Input")
		D.AddCheck(scroll, "Log input events to the console (gmodcraft_debug_input)", "gmodcraft_debug_input")
		D.AddCheck(scroll, "Camera follows Minecraft's eye (gmodcraft_camera)", "gmodcraft_camera")
		D.AddCheck(scroll, "Draw Minecraft's HUD and hand (gmodcraft_overlay)", "gmodcraft_overlay")
		local inp = {}
		for i = 1, 10 do inp[i] = D.AddLabel(scroll) end

		local nextUpdate = 0
		p.Think = function()
			if RealTime() < nextUpdate then return end
			nextUpdate = RealTime() + 0.1
			D.RequestServerStats()
			local CL, V, I = gmodcraft.clientLink, gmodcraft.view, gmodcraft.input
			local M = CL.M
			local ply = LocalPlayer()
			local lines = {}
			if not CL.haveMc then
				lines[1] = "  no McState (Minecraft not attached)"
			else
				lines[1] = string.format("  seq %d frame %d | flags %s", M.seq or 0, M.frame or 0, flagNames(M.flags))
				lines[2] = string.format("  feet (%.3f, %.3f, %.3f)  eye (%.3f, %.3f, %.3f)  eye height %.3f", M.x, M.y, M.z, M.eyeX, M.eyeY, M.eyeZ, M.eyeHeight)
				lines[3] = string.format("  yaw %.2f pitch %.2f | FOV %.2f (GMod %.2f) | sensitivity %.3f | GUI scale %d", M.yaw, M.pitch, M.fov,
					gmodcraft.convert.FovFromMc(M.fov), M.sensitivity, M.guiScale)
				lines[4] = string.format("  tick: prev (%.3f, %.3f, %.3f) cur (%.3f, %.3f, %.3f), %.1f ms/tick, tick age %.1f ms", M.prevX, M.prevY, M.prevZ,
					M.curX, M.curY, M.curZ, M.tickMs, gmodcraft.MonoMs() - M.tickAtMs)
				lines[5] = string.format("  bob phase %.3f amount %.3f | camera mode %d distance %.2f | teleports %d", M.bobPhase, M.bobAmount,
					M.cameraMode, M.cameraDistance, M.teleportCount)
				local vs = V.stats
				lines[6] = string.format("  interpolation: render delay %.1f ms, t %.2f, late frames %d of %d, ticks %d", vs.renderDelay or 0, vs.t or 0,
					vs.late, vs.frames, vs.ticks)
			end
			for i, l in ipairs(ms) do l:SetText(lines[i] or "") end

			local P = D.playerStats
			local mine
			for _, pl in ipairs((D.serverStats or {}).players or {}) do if pl.steamId == ply:SteamID64() then mine = pl end end
			local pl = {}
			pl[1] = string.format("  MC player: %s | puppet: %s | camera: %s", tostring(gmodcraft.IsMcPlayer(ply)), tostring(gmodcraft.IsPuppet(ply)),
				V.lastView and "Minecraft's eye" or "GMod")
			pl[2] = V.valid and string.format("  GMod pos %s | MC feet (in GMod units) %s | error %.2f units", tostring(ply:GetPos()), tostring(V.feet), P.err)
				or "  (no MC pose)"
			pl[3] = string.format("  corrections: %.1f/s (%d in all, client) | server-side GMod moves followed: %s | reports sent %d", P.perSec, P.corrections,
				mine and tostring(mine.corrections) or "?", CL.reports)
			if mine then
				pl[4] = string.format("  server: reports accepted %d rejected %d (last: %s) | report age %s | pending %s | last ack %s",
					mine.accepted or 0, mine.rejected or 0, tostring(mine.lastReject), mine.reportAge and string.format("%.0f ms", mine.reportAge * 1000) or "-",
					mine.pending and (mine.pending.reason .. " #" .. mine.pending.id) or "-",
					mine.lastAck and string.format("#%d result %d in %.2f s", mine.lastAck.id, mine.lastAck.result, mine.lastAck.took) or "-")
				pl[5] = string.format("  MC name %s uuid %s, mapped %s, MC health %s", tostring(mine.mcName), tostring(mine.mcUuid), tostring(mine.mapped), tostring(mine.health))
			end
			for i, l in ipairs(pu) do l:SetText(pl[i] or "") end

			local il = {}
			local ls = I.lookStats
			il[1] = string.format("  look yaw %.2f pitch %.2f (MC degrees) | events %d, dropped %d | mouse: last x %s (cmd %s), %d samples",
				I.look.yaw, I.look.pitch, I.counts.events, I.counts.dropped, tostring(ls.lastX), tostring(ls.cmdX), ls.calls)
			for k = 1, 9 do il[k + 1] = I.log[#I.log - 9 + k] and ("  " .. I.log[#I.log - 9 + k]) or "" end
			for i, l in ipairs(inp) do l:SetText(il[i] or "") end
		end
	end,
})
