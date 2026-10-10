-- R2 (protocol v25): GMod map entities as redstone endpoints, without Wiremod. A redstone bridge
-- block (P7) linked to a map entity (the Map Link tool, admins; gmodcraft_maplink_everyone 1 =
-- everyone) either
--   in:  outputs redstone (every face, 15) while the entity is pressed / open / occupied
--        (func_button, func_rot_button, momentary_rot_button, trigger_once / trigger_multiple,
--        func_door(_rotating), prop_door_rotating, logic_relay as a short pulse), or
--   out: drives it while any face is powered (func_door / prop_door_rotating Open / Close,
--        func_button Press, func_movelinear Open / Close, light TurnOn / TurnOff, env_sprite
--        ShowSprite / HideSprite).
-- The link lives with the MC block (its block entity NBT: map, MapCreationID, targetname, kind,
-- direction) and comes back as kEvBridgeLink whenever the bridge is announced (map load, link
-- session, Minecraft restart): re-paired by MapCreationID (stable for the same .bsp).
-- Entity states come from the map's own outputs: each linked entity gets AddOutput lines aimed at
-- a point entity of ours (gmodcraft_mapio_relay, targetname "gmodcraft_mapio") whose AcceptInput
-- sees them. The server link sets kServerMapIo, so Minecraft runs bridges without Wiremod too.
-- Unlinking (or relinking elsewhere) leaves those AddOutput lines on the entity until the map
-- reloads or is cleaned up (Source has no RemoveOutput): they stay inert, since OnRelay only acts
-- for bridges still linked to that MapCreationID.

local MI = gmodcraft.mapio or {}
gmodcraft.mapio = MI

MI.RELAY_CLASS = "gmodcraft_mapio_relay"
MI.RELAY_NAME = "gmodcraft_mapio"
MI.PULSE = { relay = 0.5, trigger_once = 1.0 }
-- An "on" lasts at least this long (s): a func_button with a tiny wait (gm_construct's: 0.01 s) fires
-- OnOut right after OnPressed, before our next tick could send anything.
MI.MIN_ON = 0.5

-- class -> kind name
MI.CLASSES = {
	func_button = "button", func_rot_button = "button", momentary_rot_button = "momentary",
	trigger_once = "trigger", trigger_multiple = "trigger",
	func_door = "door", func_door_rotating = "door", prop_door_rotating = "door",
	func_movelinear = "movelinear", logic_relay = "relay",
	light = "light", light_spot = "light", light_dynamic = "light", env_sprite = "sprite",
}
MI.KIND_ID = { button = 1, momentary = 2, trigger = 3, door = 4, movelinear = 5, relay = 6, light = 7, sprite = 8 }
MI.KIND_NAME = {}
for n, id in pairs(MI.KIND_ID) do MI.KIND_NAME[id] = n end
MI.MODE_ID = { ["in"] = 1, out = 2 }
-- which kinds go which way (fabric MapLink.supports)
MI.SUPPORTS = {
	["in"] = { button = true, momentary = true, trigger = true, door = true, relay = true },
	out = { button = true, door = true, movelinear = true, light = true, sprite = true },
}
-- the direction when the tool is on "auto"
MI.AUTO = { button = "in", momentary = "in", trigger = "in", relay = "in", door = "out", movelinear = "out", light = "out", sprite = "out" }

-- in: the entity's outputs that switch the bridge on / off (true / false / "pulse")
MI.OUTPUTS = {
	button = { OnPressed = true, OnIn = true, OnOut = false },
	momentary = { OnPressed = true, OnUnpressed = false },
	trigger = { OnStartTouch = true, OnEndTouchAll = false },
	door = { OnOpen = true, OnFullyClosed = false },
	relay = { OnTrigger = "pulse" },
}
-- out: the inputs fired when the bridge's power turns on / off (nil: nothing)
MI.INPUTS = {
	button = { on = "Press" },
	door = { on = "Open", off = "Close" },
	movelinear = { on = "Open", off = "Close" },
	light = { on = "TurnOn", off = "TurnOff" },
	sprite = { on = "ShowSprite", off = "HideSprite" },
}

function MI.KindOf(ent)
	return IsValid(ent) and MI.CLASSES[ent:GetClass()] or nil
end

-- tool mode ("auto" / "in" / "out") -> the direction for this kind, or nil when it can't go that way
function MI.ModeFor(kind, want)
	local m = (want == "in" or want == "out") and want or MI.AUTO[kind]
	return m and MI.SUPPORTS[m][kind] and m or nil
end

-- kAdminBridgeLink's text slot
function MI.LinkText(kind, mode, name)
	name = tostring(name or ""):gsub("[^%w_.%*%-]", "")
	return string.format("%s %s %s", kind, mode, name:sub(1, 48))
end

-- The AddOutput lines that route an entity's outputs to our relay.
function MI.OutputLines(kind, id)
	local out = {}
	for output, v in SortedPairs(MI.OUTPUTS[kind] or {}) do
		local what = v == "pulse" and "pulse" or (v and "on" or "off")
		out[#out + 1] = string.format("%s %s:MapIo:%d_%s:0:-1", output, MI.RELAY_NAME, id, what)
	end
	return out
end

local PACK = function(levels)
	local p = 0
	for f = 0, 5 do p = p + (levels[f + 1] or 0) * 2 ^ (4 * f) end
	return p
end

-- What an in-link drives: every face, 15 while on, 0 while off.
function MI.Drive(b)
	if not (b.link and b.link.mode == "in") then return 0, 0 end
	local lv = b.on and 15 or 0
	return 0x3F, PACK({ lv, lv, lv, lv, lv, lv })
end

-- Merges a wire entity's outputs (P7) with the link's: the wire keeps the faces it drives.
function MI.Merge(key, mask, levels)
	local b = MI.bridges and MI.bridges[key]
	if not b then return mask, levels end
	local m2, l2 = MI.Drive(b)
	if m2 == 0 then return mask, levels end
	local outMask, outLevels = mask, 0
	for f = 0, 5 do
		local bitv = 2 ^ f
		local wire = math.floor(mask / bitv) % 2 == 1
		local lv = wire and (math.floor(levels / 2 ^ (4 * f)) % 16) or (math.floor(l2 / 2 ^ (4 * f)) % 16)
		if not wire then outMask = outMask + bitv end
		outLevels = outLevels + lv * 2 ^ (4 * f)
	end
	return outMask, outLevels
end

-- shared (FCVAR_REPLICATED): clients see the server's value
local FLAGS = FCVAR_ARCHIVE + FCVAR_REPLICATED + FCVAR_NOTIFY
MI.cvEveryone = CreateConVar("gmodcraft_maplink_everyone", "0", FLAGS, "Garry's Modcraft: 1 = everyone may use the Map Link tool (else admins only)", 0, 1)
if gmodcraft.tools and gmodcraft.tools.PERMS then gmodcraft.tools.PERMS.maplink = MI.cvEveryone end

if CLIENT then return end

-- ---- server --------------------------------------------------------------------------------------

MI.bridges = MI.bridges or {}   -- key -> { key, x, y, z, levels, link = { id, kind, mode } | nil, ent, on, powered, sentMask, sentLevels }
MI.stats = MI.stats or { links = 0, stale = 0, fired = 0, relayed = 0, pushed = 0 }

local function K() return gmodcraft.K or {} end
local function key(x, y, z) return string.format("%d,%d,%d", x, y, z) end
MI.Key = key

function MI.LinkFlags()
	return K().ServerMapIo or 0
end

-- The point entity the map's outputs are aimed at.
do
	local ENT = { Type = "point", Base = "base_point" }
	function ENT:AcceptInput(name, activator, caller, data)
		if name == "MapIo" then MI.OnRelay(tostring(data or ""), caller) return true end
		return false
	end
	if scripted_ents then scripted_ents.Register(ENT, MI.RELAY_CLASS) end
end

function MI.Relay()
	if IsValid(MI.relay) then return MI.relay end
	local r = ents.Create(MI.RELAY_CLASS)
	if not IsValid(r) then return nil end
	r:SetName(MI.RELAY_NAME)
	r:Spawn()
	MI.relay = r
	return r
end

-- "<creationId>_<on|off|pulse>" from the relay
function MI.OnRelay(data)
	local id, what = data:match("^(%-?%d+)_(%a+)$")
	id = tonumber(id)
	if not id then return end
	MI.stats.relayed = MI.stats.relayed + 1
	for _, b in pairs(MI.bridges) do
		if b.link and b.link.mode == "in" and b.link.id == id then
			if what == "pulse" then
				b.on = true
				b.offAt = CurTime() + (MI.PULSE[b.link.kind] or MI.PULSE.relay)
			elseif what == "on" then
				b.on, b.offAt = true, nil
				b.holdUntil = CurTime() + MI.MIN_ON
				-- trigger_once removes itself after firing: its "on" is a pulse too
				if IsValid(b.ent) and b.ent:GetClass() == "trigger_once" then b.offAt = CurTime() + MI.PULSE.trigger_once end
			elseif b.holdUntil and CurTime() < b.holdUntil then
				b.offAt = b.holdUntil  -- off once the minimum "on" is over
			else
				b.on = false
			end
		end
	end
end

-- The entity's state right now (in-links to doors; others start off).
local function initialOn(ent, kind)
	if kind ~= "door" or not IsValid(ent) or not ent.GetInternalVariable then return false end
	local ok, v = pcall(ent.GetInternalVariable, ent, ent:GetClass() == "prop_door_rotating" and "m_eDoorState" or "m_toggle_state")
	if not ok or v == nil then return false end
	if ent:GetClass() == "prop_door_rotating" then return v ~= 0 end
	return v ~= 1  -- func_door: 1 = closed (at bottom)
end

-- Resolves a bridge's link to the map entity and hooks its outputs (once per entity and map load).
local function resolve(b)
	local l = b.link
	if not l then b.ent = nil return end
	local ent = ents.GetMapCreatedEntity and ents.GetMapCreatedEntity(l.id) or NULL
	if not IsValid(ent) or MI.KindOf(ent) ~= l.kind then
		b.ent = nil
		if not b.staleLogged then
			MI.stats.stale = MI.stats.stale + 1
			gmodcraft.Info("map link of bridge %s: map entity %d (%s) %s", b.key, l.id, l.kind, IsValid(ent) and ("is a " .. ent:GetClass() .. " now") or "not found on this map")
		end
		b.staleLogged = true
		return
	end
	b.ent = ent
	if l.mode == "in" then
		if not ent.gmodcraftMapIo and MI.Relay() then
			for _, line in ipairs(MI.OutputLines(l.kind, l.id)) do ent:Fire("AddOutput", line) end
			ent.gmodcraftMapIo = true
		end
		b.on = initialOn(ent, l.kind)
	end
end

-- out: the bridge's power changed (or is first known): fire the entity's input.
local function applyOut(b)
	local l = b.link
	if not (l and l.mode == "out") then return end
	local powered = false
	for f = 1, 6 do if (b.levels and b.levels[f] or 0) > 0 then powered = true end end
	if not IsValid(b.ent) then return end  -- not recorded: applied once the entity resolves
	if powered == b.powered then return end
	local first = b.powered == nil
	b.powered = powered
	local inp = MI.INPUTS[l.kind] or {}
	local name = powered and inp.on or inp.off
	if name and not (first and l.kind == "button") then  -- a button is pressed on a change, not on (re)load
		b.ent:Fire(name, "", 0)
		MI.stats.fired = MI.stats.fired + 1
	end
end

-- MC events: watches the bridge events (W still gets them), takes kEvBridgeLink.
function MI.OnEvent(e)
	local k = K()
	local t = e.type
	if t == nil or (t ~= k.EvBridgePlaced and t ~= k.EvBridgeRemoved and t ~= k.EvBridgeInputs and t ~= k.EvBridgeLink) then return false end
	local L = gmodcraft.serverLink
	if e.worldId ~= (L and L.worldId) then return t == k.EvBridgeLink end
	local kk = key(e.x, e.y, e.z)
	local b = MI.bridges[kk]
	if t == k.EvBridgeRemoved then
		if b and b.link and b.link.mode == "out" and IsValid(b.ent) then
			local inp = MI.INPUTS[b.link.kind] or {}
			if inp.off and b.powered then b.ent:Fire(inp.off, "", 0) end
		end
		MI.bridges[kk] = nil
		return false
	end
	if not b then
		b = { key = kk, x = e.x, y = e.y, z = e.z }
		MI.bridges[kk] = b
	end
	if t == k.EvBridgePlaced then
		b.levels = e.levels
		b.sentMask, b.sentLevels = nil, nil
		return false
	end
	if t == k.EvBridgeInputs then
		b.levels = e.levels
		applyOut(b)
		return false
	end
	-- kEvBridgeLink
	local was = b.link
	if e.linked then
		local kind = MI.KIND_NAME[e.kind]
		local mode = e.mode == (k.LinkOut or 2) and "out" or "in"
		b.link = kind and { id = e.creationId, kind = kind, mode = mode } or nil
	else
		b.link = nil
	end
	b.staleLogged, b.powered, b.on, b.offAt = nil, nil, false, nil
	b.sentMask, b.sentLevels = nil, nil
	if b.link then
		MI.stats.links = MI.stats.links + 1
		resolve(b)
		applyOut(b)
	elseif was then
		b.release = true  -- send "drive nothing" once
	end
	return true
end

local function wireOwns(kk)
	local W = gmodcraft.wirebridge
	return W and W.Present and W.Present() and W.bridges and W.bridges[kk] and IsValid(W.bridges[kk].ent)
end

-- Once per server tick (link alive): pulses end, stale links retried, in-links' outputs sent on change.
function MI.Tick()
	local k = K()
	local L = gmodcraft.serverLink
	local now = CurTime()
	for kk, b in pairs(MI.bridges) do
		if b.offAt and now >= b.offAt then b.on, b.offAt = false, nil end
		if b.link and not IsValid(b.ent) and (not b.retryAt or now >= b.retryAt) then
			b.retryAt = now + 5
			resolve(b)
			applyOut(b)
		end
		if not wireOwns(kk) and ((b.link and b.link.mode == "in") or b.release) then
			local mask, levels = MI.Drive(b)
			if b.release then mask, levels = 0, 0 end
			if mask ~= b.sentMask or levels ~= b.sentLevels then
				if gmodcraft.PushHostEvent({ type = k.HostEvBridgeOutputs, worldId = L and L.worldId or 0, x = b.x, y = b.y, z = b.z, code = mask, flags = levels }) then
					b.sentMask, b.sentLevels = mask, levels
					b.release = nil
					MI.stats.pushed = MI.stats.pushed + 1
				end
			end
		end
	end
end

-- game.CleanUpMap re-creates the map's entities (outputs lost) and removes our relay.
hook.Add("PostCleanupMap", "gmodcraft_mapio", function()
	MI.relay = nil
	for _, b in pairs(MI.bridges) do b.ent, b.retryAt, b.staleLogged, b.powered, b.on, b.offAt = nil, nil, nil, nil, false, nil end
end)

-- The Map Link tool's selection: player -> { id, kind, name, class }.
MI.selection = MI.selection or {}

-- Selects a map entity for ply's next bridge click. -> ok, message
function MI.Select(ply, e)
	local kind = MI.KindOf(e)
	local id = IsValid(e) and e.MapCreationID and e:MapCreationID() or -1
	if not kind or id < 0 then return false, "Map Link: that is neither a map door / button / trigger / relay / light nor a redstone bridge block" end
	MI.selection[ply] = { id = id, kind = kind, name = e:GetName() or "", class = e:GetClass() }
	return true, string.format("Map Link: selected %s '%s' (#%d); now click the redstone bridge block", e:GetClass(), e:GetName() or "", id)
end

-- Entities without a hull (logic_relay) or that traces pass through: select by targetname.
concommand.Add("gmodcraft_maplink_select", function(ply, _, args)
	local T = gmodcraft.tools
	local tell = T and T.Tell or function(_, m) print(m) end
	if not (T and T.Allowed(IsValid(ply) and ply or nil, "maplink")) then tell(ply, "Map Link: not allowed (admins only)") return end
	if not IsValid(ply) then print("Map Link: run it as a player (the selection is per player)") return end
	local name = args[1] or ""
	local found
	for _, e in ipairs(ents.FindByName(name)) do
		if MI.KindOf(e) and e:MapCreationID() >= 0 then found = e break end
	end
	if not found then tell(ply, "Map Link: no linkable map entity named '" .. name .. "'") return end
	local _, msg = MI.Select(ply, found)
	tell(ply, msg)
end, nil, "Garry's Modcraft: select a map entity by targetname for the Map Link tool (e.g. a logic_relay)")

function MI.DebugTable()
	local n, linked = 0, 0
	for _, b in pairs(MI.bridges) do
		n = n + 1
		if b.link then linked = linked + 1 end
	end
	return { bridges = n, linked = linked, stats = MI.stats }
end
