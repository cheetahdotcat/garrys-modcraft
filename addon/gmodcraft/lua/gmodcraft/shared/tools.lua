-- P7b-2 (protocol v20): the Garry's Modcraft STools' shared layer. The tools themselves are thin
-- STool files in lua/weapons/gmod_tool/stools/gmodcraft_*.lua (toolmenu category "Garry's
-- Modcraft"); this file holds the permission gates, the requests on the admin channel (server),
-- the result messages, the inspector's formatter and the client-side drawing (repair preview,
-- collision viewer).
--
-- Permissions: Terrain Repair and the Block Tool are for admins unless the server convars
-- gmodcraft_tool_repair_everyone / gmodcraft_tool_blocks_everyone are 1; Resync is admins only;
-- Inspector and Collision Viewer (client side, read-only) are for everyone. Admin = singleplayer,
-- IsAdmin / IsSuperAdmin, or the server console. The server decides; the Minecraft side gets
-- kAdminByAdmin / kAdminEveryone with each command and refuses commands with neither.

local T = gmodcraft.tools or {}
gmodcraft.tools = T

T.CATEGORY = "Garry's Modcraft"
T.U = 40
T.RESYNC_NEAR = 16          -- blocks around the hit point
T.RESYNC_GAP = { near = 2, whole = 10 }  -- seconds between resyncs per player
T.UNDO_DEPTH = { repair = 5, blocks = 10 }

-- Common blocks for the Block Tool's palette (no liquids, no operator blocks).
T.PALETTE = {
	"minecraft:stone", "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:smooth_stone", "minecraft:bricks",
	"minecraft:dirt", "minecraft:grass_block", "minecraft:sand", "minecraft:gravel", "minecraft:oak_planks", "minecraft:oak_log",
	"minecraft:spruce_planks", "minecraft:glass", "minecraft:white_wool", "minecraft:red_wool", "minecraft:glowstone",
	"minecraft:redstone_block", "minecraft:iron_block", "minecraft:gold_block", "minecraft:obsidian",
}
T.PALETTE_ADMIN = { "minecraft:tnt" }  -- shown to admins only

-- Non-admins (a tool opened by its _everyone convar) never place or break these (fabric BlockRules.EVERYONE_DENY).
T.EVERYONE_DENY = { bedrock = true, end_portal = true, end_gateway = true, nether_portal = true, fire = true, soul_fire = true, spawner = true,
	tnt = true, barrier = true, light = true, structure_void = true, end_portal_frame = true, reinforced_deepslate = true, trial_spawner = true, vault = true }
-- Seconds between two uses of a writing tool by a non-admin (admins: none).
T.EVERYONE_GAP = { repair = 1.0, blocks = 0.25, maplink = 0.5 }

local FLAGS = FCVAR_ARCHIVE + FCVAR_REPLICATED + FCVAR_NOTIFY
T.cvRepairEveryone = CreateConVar("gmodcraft_tool_repair_everyone", "0", FLAGS, "Garry's Modcraft: 1 = everyone may use Terrain Repair (else admins only)", 0, 1)
T.cvBlocksEveryone = CreateConVar("gmodcraft_tool_blocks_everyone", "0", FLAGS, "Garry's Modcraft: 1 = everyone may use the Block Tool (else admins only)", 0, 1)

-- tool -> true (everyone), a convar (everyone when it is 1), false (admins only)
-- S2 (v38): Structure Export and Blockify are admins only (Minecraft refuses the export without kAdminByAdmin too).
T.PERMS = { repair = T.cvRepairEveryone, blocks = T.cvBlocksEveryone, resync = false, inspect = true, colview = true, export = false, blockify = false }

-- ply == nil: the server console.
function T.IsAdmin(ply)
	if ply == nil then return SERVER == true end
	if game.SinglePlayer() then return true end
	return IsValid(ply) and (ply:IsAdmin() or ply:IsSuperAdmin()) or false
end

-- Whether ply may use tool, and the permission bits for Minecraft.
function T.Allowed(ply, tool)
	local K = gmodcraft.K or {}
	if T.IsAdmin(ply) then return true, K.AdminByAdmin or 4 end
	local p = T.PERMS[tool]
	if p == true then return true, 0 end
	if p and p.GetBool and p:GetBool() then return true, K.AdminEveryone or 8 end
	return false, 0
end

function T.Radius(v)
	v = math.floor((tonumber(v) or 3) + 0.5)
	return math.max(1, math.min(8, v))
end

-- Same rules as fabric BlockRules (the Minecraft side checks again).
local OPERATOR = { command_block = true, chain_command_block = true, repeating_command_block = true, structure_block = true, structure_void = true,
	jigsaw = true, barrier = true, test_block = true, test_instance_block = true, light = true }
local LIQUID = { water = true, lava = true, bubble_column = true }
function T.CheckBlock(state, allowLiquid, everyone)
	local s = string.lower(string.Trim(tostring(state or "")))
	if s == "" or #s > 60 then return "malformed" end
	local id, props = s:match("^([a-z0-9_.:/-]+)(.*)$")
	if not id or (props ~= "" and not props:match("^%[[a-z0-9_]+=[a-z0-9_]+[,a-z0-9_=]*%]$")) then return "malformed" end
	local path = id:match(":(.+)$") or id
	if OPERATOR[path] then return "operator" end
	if not allowLiquid and (LIQUID[path] or s:find("waterlogged=true", 1, true)) then return "liquid" end
	if everyone and T.EVERYONE_DENY[path] then return "restricted" end
	return "ok"
end

-- The key undo stacks and gaps are kept under: the SteamID64; a bot gets its own (entity index),
-- the server console "0".
function T.Key(ply)
	if not IsValid(ply) then return "0" end
	if ply.IsBot and ply:IsBot() then return tostring(1000000 + ply:EntIndex()) end
	return ply:SteamID64() or "0"
end

-- MC cell of a Source point.
function T.Cell(v)
	local x, y, z = gmodcraft.convert.ToMc(v)
	return math.floor(x), math.floor(y), math.floor(z)
end

-- ---- the inspector's text ---------------------------------------------------------------------
-- info: { cell = {x,y,z}, slot = {known, ox, oz}, worldId, dug (bool|nil), source, mcBlock, surface,
-- material, texture, contents, ent = {class, model, index} | nil }. Returns lines, one-line summary.
function T.FormatInspect(info)
	local lines = {}
	local c = info.cell
	if c then
		lines[#lines + 1] = string.format("MC block %d %d %d", c[1], c[2], c[3])
	else
		lines[#lines + 1] = "MC block: unknown (no map slot yet)"
	end
	lines[#lines + 1] = "MC: " .. (info.mcBlock or "no block known (the client mirror has no block states)")
	lines[#lines + 1] = "dug: " .. (info.dug == nil and "unknown" or (info.dug and "yes (GMod geometry removed for MC)" or "no"))
	lines[#lines + 1] = "collision: " .. (info.source or "none")
	lines[#lines + 1] = string.format("surface: %s, material %s, texture %s", info.surface or "?", tostring(info.material or "?"), info.texture or "?")
	lines[#lines + 1] = "contents: " .. (info.contents or "?")
	if info.ent then
		lines[#lines + 1] = string.format("entity: %s #%d %s", info.ent.class or "?", info.ent.index or -1, info.ent.model or "")
	end
	local s = info.slot or {}
	lines[#lines + 1] = string.format("slot: %s, world %08x", s.known and string.format("origin %d %d, vertical offset %d units", s.ox or 0, s.oz or 0, s.oy or 0) or "unknown",
		info.worldId or 0)
	local summary = string.format("mc=%s dug=%s src=%s surf=%s ent=%s world=%08x", c and string.format("%d,%d,%d", c[1], c[2], c[3]) or "?",
		info.dug == nil and "?" or tostring(info.dug), info.source or "none", info.surface or "?", info.ent and (info.ent.class .. "#" .. (info.ent.index or -1)) or "-",
		info.worldId or 0)
	return lines, summary
end

local CONTENT_NAMES = { { CONTENTS_SOLID, "solid" }, { CONTENTS_WINDOW, "window" }, { CONTENTS_GRATE, "grate" }, { CONTENTS_WATER, "water" },
	{ CONTENTS_SLIME, "slime" }, { CONTENTS_PLAYERCLIP, "playerclip" }, { CONTENTS_MONSTERCLIP, "monsterclip" }, { CONTENTS_MOVEABLE, "moveable" },
	{ CONTENTS_LADDER, "ladder" }, { CONTENTS_DETAIL, "detail" }, { CONTENTS_TRANSLUCENT, "translucent" } }
function T.ContentsText(mask)
	local out = {}
	for _, cn in ipairs(CONTENT_NAMES) do
		if cn[1] and bit.band(mask or 0, cn[1]) ~= 0 then out[#out + 1] = cn[2] end
	end
	return #out == 0 and "empty" or table.concat(out, "+")
end

if SERVER then
	util.AddNetworkString("gmodcraft_tool_resync")
	T.pending = T.pending or {}
	T.lastResync = T.lastResync or {}
	T.stats = T.stats or { sent = 0, refused = 0 }

	local function tell(ply, msg)
		if IsValid(ply) then ply:ChatPrint("[Garry's Modcraft] " .. msg) else gmodcraft.Info("tools: %s", msg) end
	end
	T.Tell = tell

	-- Sends a tool command (MC position in blocks). Returns ok, message (nil: wait for the answer),
	-- requestId. opts (S2): { onResult = function(e, p) end, quiet = true } — onResult takes the
	-- kEvAdminResult instead of the chat line (e = nil: Minecraft didn't answer in time).
	function T.Send(ply, tool, code, label, pos, a, extraFlags, name, opts)
		local ok, pflags = T.Allowed(ply, tool)
		if not ok then
			T.stats.refused = T.stats.refused + 1
			return false, label .. ": not allowed (admins only)"
		end
		if gmodcraft.missing or not gmodcraft.PushAdminCommand then return false, label .. ": the gmodcraft module isn't loaded" end
		local L = gmodcraft.serverLink
		if not (L and L.mcAlive) then return false, label .. ": no Minecraft server is linked" end
		local K = gmodcraft.K or {}
		-- P8 WP2 review: a re-anchor is moving this map's blocks (Minecraft refuses edits meanwhile too)
		if L.slotBusy and code ~= K.AdminResync then return false, label .. ": a re-anchor is running in Minecraft: try again when it's done" end
		if pflags == (K.AdminEveryone or 8) then
			-- a non-admin: a short gap per player and tool, and the restricted blocks
			local gap = T.EVERYONE_GAP[tool] or 0
			local key = T.Key(ply) .. ":" .. tool
			T.lastUse = T.lastUse or {}
			local now = CurTime()
			if gap > 0 and T.lastUse[key] and now - T.lastUse[key] < gap then return false, nil end  -- quietly: a held click
			T.lastUse[key] = now
			if code == K.AdminBlockPlace and T.CheckBlock(name, false, true) == "restricted" then
				return false, label .. ": only admins may place " .. tostring(name)
			end
		end
		local req = { steamId = T.Key(ply), requestId = gmodcraft.NextAdminRequest(), code = code, worldId = L.worldId,
			x = pos and pos[1] or 0, y = pos and pos[2] or 0, z = pos and pos[3] or 0, a = a or 0, flags = bit.bor(pflags, extraFlags or 0), name = name or "" }
		local sent, err = gmodcraft.PushAdminCommand(req)
		if not sent then return false, label .. ": couldn't send it (" .. tostring(err) .. ")" end
		T.pending[req.requestId] = { ply = ply, label = label, code = code, at = CurTime(), onResult = opts and opts.onResult }
		T.stats.sent = T.stats.sent + 1
		return true, nil, req.requestId
	end

	-- kEvAdminResult for one of ours -> chat. Returns true when it was ours.
	function T.OnEvent(e)
		local K = gmodcraft.K or {}
		if K.EvStructData and e.type == K.EvStructData then  -- S2 (v38): export text batches
			return gmodcraft.structures ~= nil and gmodcraft.structures.OnData(e)
		end
		if e.type ~= K.EvAdminResult then return false end
		local p = T.pending[e.requestId]
		if not p then return false end
		T.pending[e.requestId] = nil
		if p.onResult then p.onResult(e, p) return true end
		local texts = gmodcraft.demos and gmodcraft.demos.RESULT_TEXT or {}
		local n = e.a or 0
		if e.result == (K.AdminOk or 0) then
			local msg
			if p.code == K.AdminRepairRadius or p.code == K.AdminRepairColumn then
				msg = string.format("repaired %d cell(s)%s", n, (e.flags or 0) > 0 and string.format("; %d cell(s) with Minecraft blocks in them stay dug", e.flags) or "")
			elseif p.code == K.AdminRepairUndo then
				msg = string.format("repair undone: %d cell(s) dug again", n)
			elseif p.code == K.AdminResync then
				msg = string.format("re-sending %d %s", n, "Minecraft block section(s)/chunk(s) and the map collision")
			else
				msg = "done"
			end
			tell(p.ply, p.label .. ": " .. msg)
		else
			local why = texts[e.result] or ("result " .. tostring(e.result))
			if e.result == K.AdminNothing and (e.flags or 0) > 0 then why = string.format("%d dug cell(s) there hold Minecraft blocks: left dug", e.flags) end
			tell(p.ply, p.label .. ": " .. why)
		end
		return true
	end

	function T.Tick()
		local now = CurTime()
		for id, p in pairs(T.pending) do
			if now - p.at > 15 then
				T.pending[id] = nil
				if p.onResult then p.onResult(nil, p) else tell(p.ply, p.label .. ": Minecraft didn't answer (is it linked?)") end
			end
		end
		if gmodcraft.structures and gmodcraft.structures.Tick then gmodcraft.structures.Tick() end  -- S2: Blockify queues, export timeouts
	end

	-- Resync: rate-limited per player; also re-sends the map collision (server link, and the
	-- requester's client link: ColResend is all-or-nothing, near or whole).
	function T.Resync(ply, whole, pos)
		local key = T.Key(ply) .. (whole and ":whole" or ":near")
		local gap = whole and T.RESYNC_GAP.whole or T.RESYNC_GAP.near
		local now = CurTime()
		if T.lastResync[key] and now - T.lastResync[key] < gap then
			return false, string.format("Resync: wait %.0f s", gap - (now - T.lastResync[key]))
		end
		local K = gmodcraft.K or {}
		local ok, msg = T.Send(ply, "resync", K.AdminResync, "Resync", pos, whole and 0 or T.RESYNC_NEAR, 0, "")
		if not ok then return ok, msg end
		T.lastResync[key] = now
		if gmodcraft.ColResend then gmodcraft.ColResend() end
		if IsValid(ply) then
			net.Start("gmodcraft_tool_resync")
			net.Send(ply)
		end
		return true, nil
	end

	function T.DebugTable()
		return { pending = table.Count(T.pending), stats = T.stats }
	end
	return
end

-- ---- client --------------------------------------------------------------------------------------
net.Receive("gmodcraft_tool_resync", function()
	if gmodcraft.ColResend and not gmodcraft.missing then gmodcraft.ColResend() end
end)

local function activeMode()
	local ply = LocalPlayer()
	if not IsValid(ply) then return nil end
	local w = ply:GetActiveWeapon()
	if not IsValid(w) or w:GetClass() ~= "gmod_tool" then return nil end
	return w:GetMode()
end
T.ActiveMode = activeMode

-- Terrain Repair preview: the sphere (LMB) and the chunk column (RMB) at the crosshair.
local colRepair = Color(80, 255, 120, 255)
local colColumn = Color(255, 200, 60, 255)
hook.Add("PostDrawTranslucentRenderables", "gmodcraft_tools", function(depth, sky)
	if sky then return end
	local mode = activeMode()
	if mode == "gmodcraft_repair" then
		local tr = LocalPlayer():GetEyeTrace()
		if not tr.Hit then return end
		local r = T.Radius(GetConVarNumber("gmodcraft_repair_radius"))
		render.DrawWireframeSphere(tr.HitPos, r * T.U, 16, 12, colRepair, true)
		if gmodcraft.convert.slot.known then
			local x, y, z = T.Cell(tr.HitPos - tr.HitNormal * 4)
			local cx, cz = math.floor(x / 16) * 16, math.floor(z / 16) * 16
			local a = gmodcraft.convert.FromMc(cx, y - 8, cz + 16)
			local b = gmodcraft.convert.FromMc(cx + 16, y + 8, cz)
			render.DrawWireframeBox(Vector(0, 0, 0), Angle(0, 0, 0), a, b, colColumn, true)
		end
	elseif mode == "gmodcraft_colview" and T.view then
		T.view.Draw()
	end
end)

-- ---- Collision Viewer --------------------------------------------------------------------------
-- The MC-side collision near the player, rebuilt about once a second from what the client module
-- streams (gmodcraft.ColDebug, built on its worker), as wireframe: map triangles (white; terrain
-- green), GMod entities (dynamic layer, magenta), MC blocks (the server's block collision entities,
-- cyan), dug cells (orange). Bounded: radius (regions), a triangle budget, a box budget.
T.view = T.view or { meshes = {}, at = 0, tris = 0, dyn = 0, boxes = 0, dug = 0 }
local V = T.view
local lineMat = CreateMaterial("gmodcraft_tool_lines", "UnlitGeneric", { ["$basetexture"] = "color/white", ["$vertexcolor"] = 1, ["$vertexalpha"] = 1 })

local function freeMeshes()
	for _, m in ipairs(V.meshes) do if IsValid(m) then m:Destroy() end end
	V.meshes = {}
end

local function buildMeshes(lines)
	freeMeshes()
	local i, n = 1, #lines
	while i <= n do
		local count = math.min(16000, n - i + 1)
		local m = Mesh(lineMat)
		mesh.Begin(m, MATERIAL_LINES, count)
		for k = i, i + count - 1 do
			local l = lines[k]
			mesh.Position(l[1]) mesh.Color(l[3], l[4], l[5], 255) mesh.AdvanceVertex()
			mesh.Position(l[2]) mesh.Color(l[3], l[4], l[5], 255) mesh.AdvanceVertex()
		end
		mesh.End()
		V.meshes[#V.meshes + 1] = m
		i = i + count
	end
end

local function boxLines(lines, lo, hi, r, g, b)
	local c = { Vector(lo.x, lo.y, lo.z), Vector(hi.x, lo.y, lo.z), Vector(hi.x, hi.y, lo.z), Vector(lo.x, hi.y, lo.z),
		Vector(lo.x, lo.y, hi.z), Vector(hi.x, lo.y, hi.z), Vector(hi.x, hi.y, hi.z), Vector(lo.x, hi.y, hi.z) }
	for _, e in ipairs({ { 1, 2 }, { 2, 3 }, { 3, 4 }, { 4, 1 }, { 5, 6 }, { 6, 7 }, { 7, 8 }, { 8, 5 }, { 1, 5 }, { 2, 6 }, { 3, 7 }, { 4, 8 } }) do
		lines[#lines + 1] = { c[e[1]], c[e[2]], r, g, b }
	end
end

function V.Rebuild()
	V.at = RealTime()
	local C = gmodcraft.convert
	local K = gmodcraft.K or {}
	if gmodcraft.missing or not gmodcraft.ColDebug or not C.slot.known then freeMeshes() V.note = "no map slot / module" return end
	local ply = LocalPlayer()
	local x, y, z = C.ToMc(ply:GetPos())
	local regions = math.max(1, math.min(2, GetConVarNumber("gmodcraft_colview_regions")))
	local budget = math.max(500, math.min(30000, GetConVarNumber("gmodcraft_colview_tris")))
	local r = gmodcraft.ColDebug(x, y, z, regions, budget, false, V.dataId or -1)
	if r and r.tris and r.id ~= V.dataId then V.data, V.dataId = r, r.id end
	V.buildMs = r and r.buildMs or V.buildMs
	local d = V.data
	local lines = {}
	V.tris, V.dyn, V.boxes, V.dug = 0, 0, 0, 0
	if d then
		local v, fl = d.tris, d.flags
		for i = 1, math.min(#fl, budget) do
			local b = (i - 1) * 9
			local a1, a2, a3 = C.FromMc(v[b + 1], v[b + 2], v[b + 3]), C.FromMc(v[b + 4], v[b + 5], v[b + 6]), C.FromMc(v[b + 7], v[b + 8], v[b + 9])
			local cr, cg, cb = 230, 230, 230
			if bit.band(fl[i], K.TriTerrain or 8) ~= 0 then cr, cg, cb = 120, 220, 80 end
			if bit.band(fl[i], K.TriGhost or 4) ~= 0 then cr, cg, cb = 255, 60, 60 end
			lines[#lines + 1] = { a1, a2, cr, cg, cb }
			lines[#lines + 1] = { a2, a3, cr, cg, cb }
			lines[#lines + 1] = { a3, a1, cr, cg, cb }
			V.tris = V.tris + 1
		end
		local dv = d.dynTris or {}
		for b = 0, math.min(#dv, budget * 9) - 9, 9 do
			local a1, a2, a3 = Vector(dv[b + 1], dv[b + 2], dv[b + 3]), Vector(dv[b + 4], dv[b + 5], dv[b + 6]), Vector(dv[b + 7], dv[b + 8], dv[b + 9])
			lines[#lines + 1] = { a1, a2, 255, 60, 255 }
			lines[#lines + 1] = { a2, a3, 255, 60, 255 }
			lines[#lines + 1] = { a3, a1, 255, 60, 255 }
			V.dyn = V.dyn + 1
		end
	end
	-- MC blocks: the server's block collision entities near the player (their boxes)
	local reach = regions * 8 * T.U + 200
	for _, e in ipairs(ents.FindByClass("gmodcraft_blocks")) do
		if V.boxes >= 512 then break end
		if IsValid(e) and e:GetPos():DistToSqr(ply:GetPos()) < reach * reach then
			local mins, maxs = e:GetCollisionBounds()
			boxLines(lines, e:LocalToWorld(mins), e:LocalToWorld(maxs), 60, 220, 255)
			V.boxes = V.boxes + 1
		end
	end
	-- dug cells within 12 blocks
	if gmodcraft.HolesCells then
		local cx, cy, cz = math.floor(x), math.floor(y), math.floor(z)
		local cells = gmodcraft.HolesCells(C.slot.worldId, cx - 12, cy - 8, cz - 12, cx + 12, cy + 8, cz + 12) or {}
		for i = 1, math.min(#cells, 3 * 1024), 3 do
			local lo = C.FromMc(cells[i] + 0.05, cells[i + 1] + 0.05, cells[i + 2] + 0.95)
			local hi = C.FromMc(cells[i] + 0.95, cells[i + 1] + 0.95, cells[i + 2] + 0.05)
			boxLines(lines, lo, hi, 255, 150, 30)
			V.dug = V.dug + 1
		end
	end
	V.lines = #lines
	V.budget, V.regions = budget, regions
	V.note = nil
	buildMeshes(lines)
end

function V.Draw()
	if RealTime() - V.at > 1 then V.Rebuild() end
	render.SetMaterial(lineMat)
	for _, m in ipairs(V.meshes) do if IsValid(m) then m:Draw() end end
end

function V.Stop()
	freeMeshes()
	V.data, V.dataId = nil, nil
end

-- the HUD text of the viewer
function V.HudLines()
	if V.note then return { "Collision Viewer: " .. V.note } end
	return {
		string.format("Collision Viewer: radius %d region(s) (%d blocks), budget %d tris", V.regions or 0, (V.regions or 0) * 8, V.budget or 0),
		string.format("map tris %d (white, terrain green), GMod entities %d (magenta)", V.tris or 0, V.dyn or 0),
		string.format("MC block boxes %d (cyan), dug cells %d (orange), %d lines, build %.1f ms", V.boxes or 0, V.dug or 0, V.lines or 0, V.buildMs or 0),
	}
end

-- ---- Inspector (client) --------------------------------------------------------------------------
function T.Inspect(tr)
	local C = gmodcraft.convert
	local info = { slot = { known = C.slot.known, ox = C.slot.ox, oz = C.slot.oz, oy = C.slot.oy }, worldId = C.slot.worldId }
	if not tr or not tr.Hit then info.source = "none (nothing hit)" return info end
	if C.slot.known then
		local x, y, z = T.Cell(tr.HitPos - tr.HitNormal * 1)
		info.cell = { x, y, z }
		if gmodcraft.HolesCells and not gmodcraft.missing then
			local cells = gmodcraft.HolesCells(C.slot.worldId, x, y, z, x, y, z) or {}
			info.dug = #cells >= 3
		end
	end
	if tr.HitWorld then
		info.source = "static map (GMod world)"
	elseif IsValid(tr.Entity) then
		local e = tr.Entity
		info.ent = { class = e:GetClass(), model = e:GetModel() or "", index = e:EntIndex() }
		if e:GetClass() == "gmodcraft_blocks" then
			info.source = "MC block (the server's block mirror)"
			info.mcBlock = "solid Minecraft block (state unknown: the client mirror has no block states)"
		else
			info.source = "GMod entity " .. e:GetClass() .. " (dynamic layer)"
		end
	else
		info.source = "none"
	end
	info.surface = util.GetSurfacePropName(tr.SurfaceProps or 0)
	info.material = tr.MatType
	info.texture = tr.HitTexture
	info.contents = T.ContentsText(util.PointContents(tr.HitPos - tr.HitNormal * 1))
	return info
end
