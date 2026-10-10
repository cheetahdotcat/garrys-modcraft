-- The puppet (docs/DESIGN.md section 4.1/4.2): Minecraft is authoritative for the player's
-- physics. GMod never moves an MC player itself: SetupMove puts the origin where Minecraft says,
-- Move returns true (no engine movement), FinishMove records where we put it (the server uses
-- that to spot a GMod-side SetPos).
-- Seated players (vehicles, chairs) are never puppeted: GMod drives the seat (server/players.lua
-- turns the puppet off too, but its flag lags a tick behind entering).
--
--  * Server: the latest validated report from the owning client (net gmodcraft_pos, see
--    server/players.lua).
--  * Owning client, multiplayer only: the live McState (gmodcraft.view.PuppetPose), so the
--    predicted origin agrees with what the camera shows. Singleplayer doesn't predict.

local HULL_MIN, HULL_MAX = Vector(-12, -12, 0), Vector(12, 12, 72)  -- MC's 0.6 x 1.8 blocks
local HULL_DUCK_MAX = Vector(12, 12, 60)                           -- MC sneaking: 1.5 blocks
local DEF_MIN, DEF_MAX, DEF_DUCK_MAX = Vector(-16, -16, 0), Vector(16, 16, 72), Vector(16, 16, 36)

-- Called in both realms when a player becomes / stops being an MC player (hull must match on the
-- client for prediction).
function gmodcraft.SetMcHull(ply, on)
	if on then
		ply:SetHull(HULL_MIN, HULL_MAX)
		ply:SetHullDuck(HULL_MIN, HULL_DUCK_MAX)
	else
		ply:SetHull(DEF_MIN, DEF_MAX)
		ply:SetHullDuck(DEF_MIN, DEF_DUCK_MAX)
	end
end

local function serverPose(ply)
	local st = gmodcraft.player and gmodcraft.player.Get(ply)
	if not st or not st.puppet or not st.report then return nil end
	return st.report.pos, st.report.vel
end

hook.Add("SetupMove", "gmodcraft_puppet", function(ply, mv, cmd)
	if ply:InVehicle() then return end
	local pos, vel
	if SERVER then
		-- Moved by GMod since the last tick (SetPos, trigger_teleport): don't overwrite it, Minecraft
		-- follows (server/players.lua).
		local PL = gmodcraft.player
		local st = PL and PL.Get(ply)
		if st and PL.CheckExternalMove(ply, st, mv:GetOrigin()) then return end
		pos, vel = serverPose(ply)
	elseif ply == LocalPlayer() and gmodcraft.IsPuppet(ply) and gmodcraft.view then
		pos, vel = gmodcraft.view.PuppetPose()
	end
	if not pos then return end
	mv:SetOrigin(pos)
	mv:SetVelocity(vel)
end)

hook.Add("Move", "gmodcraft_puppet", function(ply, mv)
	if ply:InVehicle() then return end
	if SERVER then
		local st = gmodcraft.player and gmodcraft.player.Get(ply)
		if st and st.puppet then return true end
	elseif gmodcraft.IsPuppet(ply) then
		return true
	end
end)

hook.Add("FinishMove", "gmodcraft_puppet", function(ply, mv)
	if not SERVER or ply:InVehicle() then return end
	local st = gmodcraft.player and gmodcraft.player.Get(ply)
	if st and st.puppet then st.applied = mv:GetOrigin() end
end)

-- ---- noclip (N1, protocol v22) ------------------------------------------------------------------
-- GMod's own noclip decides in both modes: its bind (V; client/input.lua lets it through in MC
-- mode), the PlayerNoClip hook (sandbox: sbox_noclip, admins). On top, gmodcraft_noclip_mc 0 refuses
-- it in MC mode. A noclipping player is MOVETYPE_NOCLIP; server/players.lua marks it
-- kHostPlayerNoclip in HostPlayers and the MC server lets its Minecraft player fly through
-- everything (survival HUD and building stay), with no fall damage.
local cvNoclipMc = CreateConVar("gmodcraft_noclip_mc", "1", FCVAR_ARCHIVE + FCVAR_REPLICATED + FCVAR_NOTIFY,
	"Garry's Modcraft: 1 = noclip works in MC mode where GMod allows noclip (0: never in MC mode)", 0, 1)

function gmodcraft.NoclipMcAllowed()
	return cvNoclipMc:GetBool()
end

-- Not seated: Source puts every seated player in MOVETYPE_NOCLIP (CBasePlayer::GetInVehicle;
-- LeaveVehicle sets WALK back). That's the seat's, not the player's noclip.
function gmodcraft.IsNoclip(ply)
	return IsValid(ply) and ply:GetMoveType() == MOVETYPE_NOCLIP and not ply:InVehicle()
end

hook.Add("PlayerNoClip", "gmodcraft_noclip", function(ply, desired)
	-- Turning it off is always allowed; on in MC mode only under the server rule (else GMod decides).
	if desired and gmodcraft.IsMcPlayer(ply) and not cvNoclipMc:GetBool() then return false end
end)

-- Clients: follow the server's MC flag with the hull (prediction in multiplayer needs the same hull).
if CLIENT then
	local hullOn = setmetatable({}, { __mode = "k" })
	hook.Add("Think", "gmodcraft_puppet_hull", function()
		for _, ply in ipairs(player.GetAll()) do
			local on = gmodcraft.IsMcPlayer(ply)
			if hullOn[ply] ~= on then
				hullOn[ply] = on
				gmodcraft.SetMcHull(ply, on)
			end
		end
	end)
end
