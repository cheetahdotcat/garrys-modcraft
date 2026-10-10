-- Slot re-anchor from GMod (P8 WP2, protocol v23): the server console (or an admin) moves the current
-- map's Minecraft content up or down and changes its vertical offset, through the admin channel.
--
--   gmodcraft_slot info               the slot, its offset and re-anchor history (from Minecraft: see its log)
--   gmodcraft_slot dry <dyUnits>      check a re-anchor fits (nothing changes)
--   gmodcraft_slot reanchor <dyUnits> move it: the offset changes by exactly dy (Source units), the
--                                     Minecraft content by round(dy / 40) blocks
--   gmodcraft_slot undo <id>          move a re-anchor back
--   gmodcraft_slot hash               the slot's content hash (Minecraft's log)
--
-- Minecraft answers at once (a job id or a refusal: busy, a player in the slot, ...); the job runs
-- over ticks. Its outcome is in the Minecraft log; GMod sees the new offset when it's done (the map
-- slot line in this log, everything re-streamed).

local SR = gmodcraft.slotAdmin or {}
gmodcraft.slotAdmin = SR
SR.pending = SR.pending or {}

local RESULTS = { [0] = "accepted", [3] = "no map slot (no Minecraft server linked?)", [5] = "malformed", [6] = "Minecraft failed (see its log)",
	[7] = "not allowed (admins only)", [8] = "nothing to do", [11] = "busy: another slot job runs (or the server must restart after a failed one)",
	[12] = "out of range", [13] = "a Minecraft player is in the slot: everyone has to leave it first" }

local function say(ply, fmt, ...)
	local s = "[gmodcraft] " .. string.format(fmt, ...)
	if IsValid(ply) then ply:PrintMessage(HUD_PRINTCONSOLE, s) else print(s) end
end

-- One request: code, a, extra flags. Returns ok, message.
function SR.Send(ply, code, a, flags, label)
	local K = gmodcraft.K or {}
	if gmodcraft.missing or not gmodcraft.PushAdminCommand then return false, "the gmodcraft module isn't loaded" end
	local L = gmodcraft.serverLink
	if not (L and L.mcAlive) then return false, "no Minecraft server is linked" end
	local req = { steamId = IsValid(ply) and ply:SteamID64() or "0", requestId = gmodcraft.NextAdminRequest(), code = code, worldId = L.worldId,
		x = 0, y = 0, z = 0, a = a or 0, flags = bit.bor(K.AdminByAdmin or 4, flags or 0), name = label or "" }
	local sent, err = gmodcraft.PushAdminCommand(req)
	if not sent then return false, "couldn't send it (" .. tostring(err) .. ")" end
	SR.pending[req.requestId] = { ply = ply, label = label, at = CurTime() }
	return true, label .. ": sent"
end

-- kEvAdminResult for one of ours. Returns true when it was ours.
function SR.OnEvent(e)
	local K = gmodcraft.K or {}
	if e.type ~= K.EvAdminResult then return false end
	local p = SR.pending[e.requestId]
	if not p then return false end
	SR.pending[e.requestId] = nil
	if e.result == (K.AdminOk or 0) then
		say(p.ply, "%s: job %d started (Minecraft's log has the outcome; the new offset shows here when it's done)", p.label, e.a or 0)
	else
		say(p.ply, "%s: %s", p.label, RESULTS[e.result] or ("result " .. tostring(e.result)))
	end
	return true
end

function SR.Tick()
	local now = CurTime()
	for id, p in pairs(SR.pending) do
		if now - p.at > 15 then
			SR.pending[id] = nil
			say(p.ply, "%s: Minecraft didn't answer", p.label)
		end
	end
end

local function parseInt(s)
	local n = tonumber(s)
	if not n or n ~= math.floor(n) or math.abs(n) > 65536 then return nil end
	return n
end

concommand.Add("gmodcraft_slot", function(ply, _, args)
	if IsValid(ply) and not (game.SinglePlayer() or ply:IsSuperAdmin()) then return end
	local K = gmodcraft.K or {}
	local sub = args[1] or "info"
	local ok, msg
	if sub == "info" then
		local s = gmodcraft.serverLink and gmodcraft.serverLink.slot
		if not s then say(ply, "no map slot yet") return end
		say(ply, "slot of %s: (%d, %d), origin (%d, %d), vertical offset %d units (%.3f blocks)", game.GetMap(), s.slotX or 0, s.slotZ or 0, s.ox, s.oz, s.oy or 0,
			(s.oy or 0) / 40)
		ok, msg = SR.Send(ply, K.AdminSlotHash, 0, 0, "hash")  -- the history and the hash go to Minecraft's log
	elseif sub == "hash" then
		ok, msg = SR.Send(ply, K.AdminSlotHash, 0, 0, "hash")
	elseif sub == "reanchor" or sub == "dry" then
		local dy = parseInt(args[2])
		if not dy or dy == 0 then say(ply, "usage: gmodcraft_slot %s <dyUnits> (Source units, not 0; 40 = one block)", sub) return end
		ok, msg = SR.Send(ply, K.AdminReanchor, dy, sub == "dry" and (K.AdminDryRun or 16) or 0, sub .. " " .. dy)
	elseif sub == "undo" then
		local id = parseInt(args[2])
		if not id or id <= 0 then say(ply, "usage: gmodcraft_slot undo <id> (gmodcraft_slot info lists them)") return end
		ok, msg = SR.Send(ply, K.AdminReanchorUndo, id, 0, "undo " .. id)
	else
		say(ply, "usage: gmodcraft_slot info | hash | dry <dyUnits> | reanchor <dyUnits> | undo <id>")
		return
	end
	if msg then say(ply, "%s", msg) end
end)
