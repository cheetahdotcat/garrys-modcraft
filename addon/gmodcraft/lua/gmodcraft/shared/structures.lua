-- S2 (protocol v38, roadmap 0.5): Minecraft structures <-> GMod dupes. Admins only.
--
--   Structure Export (STool gmodcraft_export): two corners pick a box of MC blocks (at most
--   kStructMaxEdge per edge); kAdminStructExport asks Minecraft for it, the text comes back in
--   kEvStructData batches ("GMCS 1 sx sy sz n", n palette lines, one line of runs "i*count", cells
--   x fastest, then z, then y). Runs of the same block are merged into boxes (greedy: x, then z,
--   then y), each box one gmodcraft_cube entity (shared/mccube.lua: exact 40 u per block, its block
--   state stored, so Blockify gives the same blocks back). With AdvDupe2 installed the dupe goes
--   into the admin's AdvDupe2 clipboard (paste it with the AdvDupe2 tool) and into
--   data/advdupe2/gmodcraft_export_<id>.txt; without it (or with the tool's "spawn" ticked) the
--   cubes spawn frozen at the admin's aim point (undoable). Minecraft also saves the box as a vanilla
--   structure file: <world>/generated/gmodcraft/structures/export_<id>.nbt.
--
--   Blockify (STool gmodcraft_blockify): selected props (or the aimed contraption) are voxelized at
--   1 block = 40 u: a cell is solid when a hull of the cell shrunk by MARGIN u on each side
--   overlaps one of them (util.TraceHull, start = end, filtered to the selection: Source's own
--   collision test). The block comes from the entity (gmodcraft_cube: its stored block) or from its
--   surface property through the DigMaterial table (module mapcol kDigNames -> fabric
--   SkyDig.materialState; exact names, then a few substrings, else stone). The props are removed,
--   the blocks placed through kAdminBlockPlace (air / replaceable cells only, paced per tick), and
--   one GMod undo entry pastes the props back and breaks the placed blocks.

local S = gmodcraft.structures or {}
gmodcraft.structures = S

S.MAX_EDGE = 32               -- kStructMaxEdge (the module's K.StructMaxEdge when loaded)
S.MAX_TEXT = 262144           -- kStructTextMaxBytes
S.MAX_ENTS = 2048             -- cube entities per export (after merging)
S.MAX_BOX_EDGE = 16           -- blocks per merged box edge (bigger boxes are fine for physics, not for drawing)
S.BLOCKIFY_MAX_SCAN = 32768   -- cells tested per Blockify
S.BLOCKIFY_MAX_BLOCKS = 4096  -- blocks placed per Blockify
S.BLOCKIFY_PER_TICK = 48      -- admin commands sent per tick (2 host ring slots each)
S.MARGIN = 1                  -- u: a prop must reach this far into a cell to fill it
S.EXPORT_TIMEOUT = 30         -- s for the whole text to arrive
S.CUBE_MODEL = "models/hunter/blocks/cube1x1x1.mdl"  -- only for AdvDupe2's ghosts (the cube draws itself)

-- ---- the export text -------------------------------------------------------------------------
-- Returns { sx, sy, sz, palette = { "id", ... }, cells = { idx, ... } (sx*sy*sz, 0 = air), blocks }
-- or nil, why.
function S.Parse(text)
	if type(text) ~= "string" then return nil, "no text" end
	local lines = {}
	for line in (text .. "\n"):gmatch("([^\n]*)\n") do lines[#lines + 1] = line end
	local sx, sy, sz, n = (lines[1] or ""):match("^GMCS 1 (%d+) (%d+) (%d+) (%d+)$")
	sx, sy, sz, n = tonumber(sx), tonumber(sy), tonumber(sz), tonumber(n)
	if not sx then return nil, "not an export text" end
	if sx < 1 or sy < 1 or sz < 1 or sx > S.MAX_EDGE or sy > S.MAX_EDGE or sz > S.MAX_EDGE then return nil, "bad box size" end
	if #lines < n + 2 then return nil, "the text is cut short" end
	local palette = {}
	for i = 1, n do
		local id = lines[1 + i]
		if id == "" or id:find("%s") then return nil, "bad palette line " .. i end
		palette[i] = id
	end
	local total = sx * sy * sz
	local cells, at, blocks = {}, 0, 0
	for tok in lines[n + 2]:gmatch("%S+") do
		local idx, count = tok:match("^(%d+)%*(%d+)$")
		if not idx then idx, count = tok:match("^(%d+)$"), "1" end
		idx, count = tonumber(idx), tonumber(count)
		if not idx or not count or count < 1 or idx > n then return nil, "bad run " .. tok end
		if at + count > total then return nil, "too many cells" end
		for k = at + 1, at + count do cells[k] = idx end
		at = at + count
		if idx > 0 then blocks = blocks + count end
	end
	if at ~= total then return nil, string.format("%d of %d cells", at, total) end
	return { sx = sx, sy = sy, sz = sz, palette = palette, cells = cells, blocks = blocks }
end

-- Greedy merge into boxes of one block each: { x0, y0, z0, x1, y1, z1 (exclusive, box-local MC), block }.
function S.MergeBoxes(p, maxEdge)
	maxEdge = maxEdge or S.MAX_BOX_EDGE
	local sx, sy, sz, cells = p.sx, p.sy, p.sz, p.cells
	local used = {}
	local function at(x, y, z) return x + sx * (z + sz * y) + 1 end
	local function free(x, y, z, idx) local k = at(x, y, z) return cells[k] == idx and not used[k] end
	local boxes = {}
	for y = 0, sy - 1 do
		for z = 0, sz - 1 do
			for x = 0, sx - 1 do
				local idx = cells[at(x, y, z)]
				if idx > 0 and not used[at(x, y, z)] then
					local x1 = x + 1
					while x1 < sx and x1 - x < maxEdge and free(x1, y, z, idx) do x1 = x1 + 1 end
					local z1 = z + 1
					while z1 < sz and z1 - z < maxEdge do
						local ok = true
						for xx = x, x1 - 1 do if not free(xx, y, z1, idx) then ok = false break end end
						if not ok then break end
						z1 = z1 + 1
					end
					local y1 = y + 1
					while y1 < sy and y1 - y < maxEdge do
						local ok = true
						for zz = z, z1 - 1 do
							for xx = x, x1 - 1 do if not free(xx, y1, zz, idx) then ok = false break end end
							if not ok then break end
						end
						if not ok then break end
						y1 = y1 + 1
					end
					for yy = y, y1 - 1 do for zz = z, z1 - 1 do for xx = x, x1 - 1 do used[at(xx, yy, zz)] = true end end end
					boxes[#boxes + 1] = { x, y, z, x1, y1, z1, p.palette[idx] }
				end
			end
		end
	end
	return boxes
end

-- One box's Source centre and size (blocks along Source x, y, z) for the box's MC origin.
function S.BoxTransform(b, ox, oy, oz)
	local C = gmodcraft.convert
	local centre = C.FromMc(ox + (b[1] + b[4]) / 2, oy + (b[2] + b[5]) / 2, oz + (b[3] + b[6]) / 2)
	return centre, Vector(b[4] - b[1], b[6] - b[3], b[5] - b[2])
end

-- An AdvDupe2 / duplicator table of gmodcraft_cube entities. The head is a bottom-layer box; Z puts
-- the structure's bottom on the paste point.
function S.BuildDupe(boxes, ox, oy, oz)
	local entities, head, headBox = {}, nil, nil
	local placed = {}
	for i, b in ipairs(boxes) do
		local pos, size = S.BoxTransform(b, ox, oy, oz)
		placed[i] = { pos = pos, size = size, block = b[7] }
		if not head or b[2] < headBox[2] then head, headBox = i, b end
	end
	if not head then return nil end
	local hp = placed[head].pos
	for i, e in ipairs(placed) do
		entities[i] = {
			Class = "gmodcraft_cube", Model = S.CUBE_MODEL, Pos = e.pos, Angle = Angle(0, 0, 0),
			Size = e.size, Block = e.block, BuildDupeInfo = {},
			PhysicsObjects = { [0] = { Pos = Vector(e.pos.x - hp.x, e.pos.y - hp.y, e.pos.z - hp.z), Angle = Angle(0, 0, 0) } },
		}
	end
	return { Entities = entities, Constraints = {}, HeadEnt = { Index = head, Pos = hp, Z = placed[head].size.z * 20 },
		Description = string.format("Minecraft structure (%d boxes)", #entities) }
end

-- ---- Blockify ----------------------------------------------------------------------------------
-- Cells of the inclusive MC range r = { x0, y0, z0, x1, y1, z1 } for which solidAt(x, y, z) gives a
-- block id: { { x, y, z, block }, ... }, or nil, why (too many cells to scan or to place).
function S.Voxelize(r, solidAt, maxScan, maxBlocks)
	maxScan, maxBlocks = maxScan or S.BLOCKIFY_MAX_SCAN, maxBlocks or S.BLOCKIFY_MAX_BLOCKS
	local n = (r[4] - r[1] + 1) * (r[5] - r[2] + 1) * (r[6] - r[3] + 1)
	if n > maxScan then return nil, string.format("%d cells to scan (at most %d)", n, maxScan) end
	local out = {}
	for y = r[2], r[5] do
		for z = r[3], r[6] do
			for x = r[1], r[4] do
				local b = solidAt(x, y, z)
				if b then
					if #out >= maxBlocks then return nil, string.format("more than %d blocks", maxBlocks) end
					out[#out + 1] = { x, y, z, b }
				end
			end
		end
	end
	return out
end

-- DigMaterial -> block (fabric SkyDig.materialState; 0 / unknown: stone).
S.DIG_BLOCK = {
	[1] = "minecraft:grass_block", [2] = "minecraft:dirt", [3] = "minecraft:stone", [4] = "minecraft:cobblestone", [5] = "minecraft:snow_block",
	[6] = "minecraft:packed_ice", [7] = "minecraft:sand", [8] = "minecraft:gravel", [9] = "minecraft:mud", [10] = "minecraft:oak_log",
	[11] = "minecraft:spruce_log", [12] = "minecraft:birch_log", [13] = "minecraft:spruce_planks", [14] = "minecraft:waxed_copper_block",
	[15] = "minecraft:glass", [16] = "minecraft:moss_block", [17] = "minecraft:brown_wool", [18] = "minecraft:bone_block", [19] = "minecraft:cobweb",
	[20] = "minecraft:light_gray_concrete_powder", [21] = "minecraft:bedrock",
}
-- Surface property -> DigMaterial (module mapcol keyvalues.cpp kDigNames; the base chain isn't walked here).
S.SURFACE_DIG = {
	grass = 1, dirt = 2, mud = 9, sand = 7, gravel = 8, rock = 3, boulder = 3, stone = 3,
	concrete = 4, brick = 4, tile = 4, ceiling_tile = 4, plaster = 4, default = 4,
	snow = 5, ice = 6, gmod_ice = 6, wood = 13, carpet = 17, cloth = 17, foliage = 16, flesh = 16, watermelon = 16,
	solidmetal = 14, metal = 14, metal_box = 14, metalgrate = 14, chainlink = 14, gm_ps_metaltire = 14, glass = 15,
	plastic = 0, plastic_box = 0, plastic_barrel = 0, rubber = 0, cardboard = 0, paper = 0,
}
-- the usual derived names (wood_crate, metal_barrel, ...) by what they start with / contain
S.SURFACE_GUESS = { { "wood", 13 }, { "metal", 14 }, { "glass", 15 }, { "concrete", 4 }, { "brick", 4 }, { "rock", 3 }, { "stone", 3 },
	{ "dirt", 2 }, { "grass", 1 }, { "sand", 7 }, { "gravel", 8 }, { "snow", 5 }, { "ice", 6 }, { "flesh", 16 }, { "cloth", 17 }, { "plastic", 0 },
	{ "rubber", 0 }, { "paper", 0 }, { "cardboard", 0 }, { "tile", 4 }, { "computer", 14 }, { "canister", 14 }, { "combine", 14 }, { "chain", 14 },
	{ "slosh", 0 }, { "tire", 0 }, { "porcelain", 4 }, { "pottery", 4 }, { "ceramic", 4 }, { "bone", 18 }, { "antlion", 16 }, { "alien", 16 } }

function S.BlockForSurface(name)
	local n = string.lower(tostring(name or ""))
	local dig = S.SURFACE_DIG[n]
	if dig == nil then
		for _, g in ipairs(S.SURFACE_GUESS) do
			if n:find(g[1], 1, true) then dig = g[2] break end
		end
	end
	return S.DIG_BLOCK[dig or 0] or "minecraft:stone"
end

-- The block a GMod entity becomes (kAdminTextBytes: a long state keeps only its id).
function S.BlockForEntity(ent)
	if ent.GetClass and ent:GetClass() == "gmodcraft_cube" and ent.GetBlock then
		local b = tostring(ent:GetBlock() or "")
		if #b > 60 then b = b:match("^[^%[]+") or b end
		if b ~= "" then return b end
	end
	local phys = ent.GetPhysicsObject and ent:GetPhysicsObject()
	return S.BlockForSurface(IsValid(phys) and phys:GetMaterial() or "")
end

-- ---- client: the export box preview, the Blockify selection ---------------------------------------
if CLIENT then
	local colBox, colA = Color(80, 200, 255, 255), Color(255, 220, 60, 255)
	local function mode()
		return gmodcraft.tools and gmodcraft.tools.ActiveMode and gmodcraft.tools.ActiveMode()
	end
	-- Source box of MC cells a..b (inclusive)
	function S.CellBox(a, b)
		local C = gmodcraft.convert
		local lo = C.FromMc(math.min(a.x, b.x), math.min(a.y, b.y), math.max(a.z, b.z) + 1)
		local hi = C.FromMc(math.max(a.x, b.x) + 1, math.max(a.y, b.y) + 1, math.min(a.z, b.z))
		return lo, hi
	end
	hook.Add("PostDrawTranslucentRenderables", "gmodcraft_structures", function(depth, sky)
		if sky or mode() ~= "gmodcraft_export" or not gmodcraft.convert.slot.known then return end
		local ply = LocalPlayer()
		local a, b = ply:GetNW2Vector("gmc_export_a", nil), ply:GetNW2Vector("gmc_export_b", nil)
		local tr = ply:GetEyeTrace()
		local aim
		if tr.Hit then
			local x, y, z = gmodcraft.tools.Cell(tr.HitPos - tr.HitNormal * 20)
			aim = Vector(x, y, z)
		end
		local hasA = ply:GetNW2Bool("gmc_export_has_a", false)
		local hasB = ply:GetNW2Bool("gmc_export_has_b", false)
		local p, q = hasA and a or aim, hasB and b or aim
		if p and q then
			local lo, hi = S.CellBox(p, q)
			render.DrawWireframeBox(Vector(0, 0, 0), Angle(0, 0, 0), lo, hi, colBox, true)
		end
		if aim then
			local lo, hi = S.CellBox(aim, aim)
			render.DrawWireframeBox(Vector(0, 0, 0), Angle(0, 0, 0), lo, hi, colA, true)
		end
	end)
	hook.Add("PreDrawHalos", "gmodcraft_structures", function()
		if mode() ~= "gmodcraft_blockify" then return end
		local sel = {}
		for _, e in ipairs(ents.GetAll()) do
			if e:GetNW2Bool("gmc_blockify_sel", false) and e:GetNW2Entity("gmc_blockify_by") == LocalPlayer() then sel[#sel + 1] = e end
		end
		if #sel > 0 then halo.Add(sel, Color(80, 255, 120), 2, 2, 1, true, true) end
	end)
	return
end
if not SERVER then return end

local function T() return gmodcraft.tools end
local function K() return gmodcraft.K or {} end

S.exports = S.exports or {}   -- requestId -> { ply, total, blocks, parts = { [offset] = text }, got, at, ox, oy, oz, spawnAt }
S.jobs = S.jobs or {}         -- Blockify / undo queues: { ply, queue = { cell, ... }, code, inflight, ok, refused, why = {}, label, done }
S.stats = S.stats or { exports = 0, exportFails = 0, blockified = 0, placed = 0 }

local function tell(ply, msg) T().Tell(ply, msg) end

-- The MC cell (integers) a Source point is in.
local function cell(v) return T().Cell(v) end

-- Structure Export: a and b are MC cells { x, y, z }. Returns ok, message.
function S.Export(ply, a, b, spawnAt, spawn)
	local dx, dy, dz = math.abs(a[1] - b[1]) + 1, math.abs(a[2] - b[2]) + 1, math.abs(a[3] - b[3]) + 1
	local edge = K().StructMaxEdge or S.MAX_EDGE
	if dx > edge or dy > edge or dz > edge then
		return false, string.format("Structure Export: the box is %dx%dx%d (at most %d per edge)", dx, dy, dz, edge)
	end
	if not K().AdminStructExport then return false, "Structure Export: the gmodcraft module is too old (protocol v38 needed)" end
	local job = { ply = ply, ox = math.min(a[1], b[1]), oy = math.min(a[2], b[2]), oz = math.min(a[3], b[3]), spawnAt = spawnAt, spawn = spawn }
	local ok, msg, id = T().Send(ply, "export", K().AdminStructExport, "Structure Export", { a[1] + 0.5, a[2] + 0.5, a[3] + 0.5 }, 0, 0,
		string.format("%d %d %d", b[1], b[2], b[3]), { onResult = function(e) S.OnExportResult(job, e) end })
	if ok and id then job.id = id end
	return ok, msg
end

function S.OnExportResult(job, e)
	if not e then tell(job.ply, "Structure Export: Minecraft didn't answer (is it linked?)") return end
	if e.result ~= (K().AdminOk or 0) then
		local texts = gmodcraft.demos and gmodcraft.demos.RESULT_TEXT or {}
		local why = e.result == K().AdminOutOfRange and "the box (or its text) is too big" or e.result == K().AdminNothing and "only air in the box"
			or texts[e.result] or ("result " .. tostring(e.result))
		tell(job.ply, "Structure Export: " .. why)
		S.stats.exportFails = S.stats.exportFails + 1
		return
	end
	job.total, job.blocks, job.parts, job.got, job.at = math.floor(e.a or 0), e.flags or 0, {}, 0, CurTime()
	S.exports[e.requestId] = job
	tell(job.ply, string.format("Structure Export: %d blocks, receiving %d bytes...", job.blocks, job.total))
end

-- kEvStructData (text = the batch's slots joined). Returns true (always ours).
function S.OnData(e)
	local job = S.exports[e.requestId]
	if not job then return true end
	local off = e.flags or 0
	if e.complete == false or job.parts[off] then return true end
	local text = (e.text or ""):sub(1, math.max(0, job.total - off))
	job.parts[off] = text
	job.got = job.got + #text
	if job.got >= job.total then
		S.exports[e.requestId] = nil
		local offs = {}
		for o in pairs(job.parts) do offs[#offs + 1] = o end
		table.sort(offs)
		local buf, want = {}, 0
		for _, o in ipairs(offs) do
			if o ~= want then tell(job.ply, "Structure Export: a part of the text went missing; try again") return true end
			buf[#buf + 1] = job.parts[o]
			want = o + #job.parts[o]
		end
		S.Finish(job, table.concat(buf))
	end
	return true
end

-- Spawns one cube (the duplicator's function, shared/mccube.lua).
local function spawnCube(ply, data) return gmodcraft.mccube and gmodcraft.mccube.Make(ply, data) end

function S.Finish(job, text)
	local p, why = S.Parse(text)
	if not p then tell(job.ply, "Structure Export: " .. why) S.stats.exportFails = S.stats.exportFails + 1 return end
	local boxes = S.MergeBoxes(p)
	if #boxes > S.MAX_ENTS then
		tell(job.ply, string.format("Structure Export: %d boxes after merging (at most %d)", #boxes, S.MAX_ENTS))
		return
	end
	local dupe = S.BuildDupe(boxes, job.ox, job.oy, job.oz)
	if not dupe then tell(job.ply, "Structure Export: nothing to export") return end
	S.stats.exports = S.stats.exports + 1
	S.last = { boxes = #boxes, blocks = p.blocks, size = { p.sx, p.sy, p.sz } }
	local where = {}
	if AdvDupe2 and AdvDupe2.Encode and IsValid(job.ply) then
		local ok, err = pcall(function()
			AdvDupe2.Encode(dupe, AdvDupe2.GenerateDupeStamp(job.ply), function(data)
				file.CreateDir("advdupe2")
				local name = string.format("advdupe2/gmodcraft_export_%d.txt", job.id or 0)
				local f = file.Open(name, "wb", "DATA")
				if f then f:Write(data) f:Close() where[#where + 1] = "data/" .. name end
			end)
			job.ply.AdvDupe2 = job.ply.AdvDupe2 or {}
			if AdvDupe2.LoadDupe then
				AdvDupe2.LoadDupe(job.ply, true, table.Copy(dupe), { revision = AdvDupe2.CodecRevision or 5 }, nil)  -- the paste compares Revision
				if AdvDupe2.SendGhosts then AdvDupe2.SendGhosts(job.ply) end
				where[#where + 1] = "your AdvDupe2 clipboard"
			end
		end)
		if not ok then gmodcraft.Info("structures: AdvDupe2: %s", tostring(err)) end
	end
	if job.spawn or #where == 0 then
		S.SpawnDupe(job.ply, dupe, job.spawnAt)
		where[#where + 1] = "spawned at your aim point"
	end
	tell(job.ply, string.format("Structure Export: %dx%dx%d, %d blocks as %d cubes: %s", p.sx, p.sy, p.sz, p.blocks, #boxes, table.concat(where, ", ")))
end

-- Spawns a dupe's cubes frozen with the head at pos (the structure's bottom on it), one undo entry.
function S.SpawnDupe(ply, dupe, pos)
	pos = pos or (IsValid(ply) and ply:GetPos()) or Vector(0, 0, 0)
	local base = Vector(pos.x, pos.y, pos.z + dupe.HeadEnt.Z)
	local made = {}
	for _, e in pairs(dupe.Entities) do
		local rel = e.PhysicsObjects[0].Pos
		local data = table.Copy(e)
		data.Pos = Vector(base.x + rel.x, base.y + rel.y, base.z + rel.z)
		local ent = spawnCube(ply, data)
		if IsValid(ent) then made[#made + 1] = ent end
	end
	if #made > 0 and undo then
		undo.Create("Minecraft structure")
		for _, ent in ipairs(made) do undo.AddEntity(ent) end
		if IsValid(ply) then undo.SetPlayer(ply) end
		undo.Finish()
	end
	return made
end

-- ---- Blockify (server) ----------------------------------------------------------------------------
-- The entities Blockify may take (no players, NPCs, the world, our collision entities).
function S.Blockifiable(ent)
	if not IsValid(ent) or ent:IsPlayer() or ent:IsNPC() or ent:IsWorld() then return false end
	local c = ent:GetClass()
	if c == "gmodcraft_blocks" or c:find("^gmodcraft_mc") then return false end
	local phys = ent:GetPhysicsObject()
	return IsValid(phys)
end

-- Voxelizes and places. list: entities. Returns ok, message.
function S.Blockify(ply, list)
	local allowed = T().Allowed(ply, "blockify")
	if not allowed then return false, "Blockify: not allowed (admins only)" end
	local L = gmodcraft.serverLink
	if gmodcraft.missing or not gmodcraft.PushAdminCommand or not (L and L.mcAlive) then return false, "Blockify: no Minecraft server is linked" end
	if not gmodcraft.convert.slot.known then return false, "Blockify: the map's Minecraft area isn't known yet" end
	local set, ents = {}, {}
	for _, e in ipairs(list) do
		if S.Blockifiable(e) and not set[e] then set[e] = true ents[#ents + 1] = e end
	end
	if #ents == 0 then return false, "Blockify: nothing to blockify (props only)" end
	-- the MC cells around the props' world boxes
	local r
	for _, e in ipairs(ents) do
		local lo, hi = e:WorldSpaceAABB()
		for _, v in ipairs({ lo, hi }) do
			local x, y, z = gmodcraft.convert.ToMc(v)
			x, y, z = math.floor(x), math.floor(y), math.floor(z)
			if not r then r = { x, y, z, x, y, z } end
			r[1], r[2], r[3] = math.min(r[1], x), math.min(r[2], y), math.min(r[3], z)
			r[4], r[5], r[6] = math.max(r[4], x), math.max(r[5], y), math.max(r[6], z)
		end
	end
	local half = 20 - S.MARGIN
	local hmin, hmax = Vector(-half, -half, -half), Vector(half, half, half)
	local cells, why = S.Voxelize(r, function(x, y, z)
		local c = gmodcraft.convert.FromMc(x + 0.5, y + 0.5, z + 0.5)
		local tr = util.TraceHull({ start = c, endpos = c, mins = hmin, maxs = hmax, mask = MASK_SOLID, ignoreworld = true,
			filter = function(e) return set[e] == true end })
		if tr.Hit and IsValid(tr.Entity) and set[tr.Entity] then return S.BlockForEntity(tr.Entity) end
		return nil
	end)
	if not cells then return false, "Blockify: " .. why end
	if #cells == 0 then return false, "Blockify: the props fill no whole block cell" end
	-- keep the props for the undo, then remove them
	local copy = duplicator.CopyEnts(ents)
	for _, e in ipairs(ents) do e:Remove() end
	-- the queue is popped from its end: reversed, so the bottom layer goes first (sand / gravel land on it)
	local queue = {}
	for i = #cells, 1, -1 do queue[#queue + 1] = cells[i] end
	local job = { ply = ply, label = "Blockify", code = K().AdminBlockPlace, queue = queue, inflight = 0, ok = 0, refused = 0, why = {}, placed = {} }
	job.listed = true
	S.jobs[#S.jobs + 1] = job
	S.stats.blockified = S.stats.blockified + #ents
	if undo then
		undo.Create("Blockify")
		undo.AddFunction(function() S.UndoBlockify(ply, job, copy) end)
		if IsValid(ply) then undo.SetPlayer(ply) end
		undo.Finish()
	end
	return true, string.format("Blockify: %d prop(s) -> %d block(s), placing...", #ents, #cells)
end

-- Undo: the props come back where they were; the blocks this job placed are broken (queued).
function S.UndoBlockify(ply, job, copy)
	if copy and duplicator.Paste then duplicator.Paste(IsValid(ply) and ply or nil, copy.Entities or {}, copy.Constraints or {}) end
	job.queue = {}  -- whatever wasn't sent yet isn't placed any more
	local cells = {}
	for _, c in ipairs(job.placed) do cells[#cells + 1] = c end
	-- answers still on their way add to this job's queue (onResult below)
	job.undoJob = { ply = ply, label = "Blockify undo", code = K().AdminBlockBreak, queue = cells, inflight = 0, ok = 0, refused = 0, why = {}, placed = {} }
	job.undoJob.listed = true
	S.jobs[#S.jobs + 1] = job.undoJob
	job.undone = true
end

local function finishJob(job)
	local why = {}
	for k, n in pairs(job.why) do why[#why + 1] = string.format("%d %s", n, k) end
	local verb = job.code == K().AdminBlockBreak and "removed" or "placed"
	tell(job.ply, string.format("%s: %d block(s) %s%s", job.label, job.ok, verb, job.refused > 0 and (", " .. job.refused .. " refused (" .. table.concat(why, "; ") .. ")") or ""))
end

function S.Tick()
	local now = CurTime()
	for id, job in pairs(S.exports) do
		if now - job.at > S.EXPORT_TIMEOUT then
			S.exports[id] = nil
			tell(job.ply, "Structure Export: the text didn't arrive in time; try again")
		end
	end
	local budget = S.BLOCKIFY_PER_TICK
	for i = #S.jobs, 1, -1 do
		local job = S.jobs[i]
		while budget > 0 and #job.queue > 0 do
			local c = table.remove(job.queue)
			local ok, msg = T().Send(job.ply, "blockify", job.code, job.label, { c[1] + 0.5, c[2] + 0.5, c[3] + 0.5 }, 0, 0, c[4] or "", {
				onResult = function(e)
					job.inflight = job.inflight - 1
					if e and e.result == (K().AdminOk or 0) then
						job.ok = job.ok + 1
						if job.code == K().AdminBlockPlace then
							job.placed[#job.placed + 1] = c
							S.stats.placed = S.stats.placed + 1
							if job.undoJob then  -- undone while this was in flight: break it too
								job.undoJob.queue[#job.undoJob.queue + 1] = c
								job.undoJob.done = nil
								if not job.undoJob.listed then job.undoJob.listed = true S.jobs[#S.jobs + 1] = job.undoJob end
							end
						end
					else
						job.refused = job.refused + 1
						local texts = gmodcraft.demos and gmodcraft.demos.RESULT_TEXT or {}
						local k = e and (e.result == K().AdminNothing and "occupied" or texts[e.result] or tostring(e.result)) or "no answer"
						job.why[k] = (job.why[k] or 0) + 1
					end
					if job.inflight == 0 and #job.queue == 0 and not job.done then job.done = true finishJob(job) end
				end })
			if not ok then
				-- the link went away or the ring is full: put it back and stop this tick
				job.queue[#job.queue + 1] = c
				if msg then job.stall = (job.stall or 0) + 1 end
				if (job.stall or 0) > 200 then tell(job.ply, job.label .. ": stopped (" .. tostring(msg) .. ")") job.queue = {} end
				budget = 0
				break
			end
			job.inflight = job.inflight + 1
			budget = budget - 1
		end
		if #job.queue == 0 and (job.inflight == 0 or job.done) then
			if not job.done then job.done = true finishJob(job) end
			job.listed = false
			table.remove(S.jobs, i)
		end
	end
end

function S.DebugTable()
	return { exports = table.Count(S.exports), jobs = #S.jobs, stats = S.stats, last = S.last }
end
