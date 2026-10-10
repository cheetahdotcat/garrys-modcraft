-- S2 (v38): gmodcraft_cube, one box of Minecraft blocks as a GMod prop (Structure Export, shared/
-- structures.lua). Registered with scripted_ents.Register like gmodcraft_blocks (shared/blockent.lua).
--
-- Size: blocks along Source x, y, z (a merged run: 1..16 each); Block: the MC block state it came
-- from (Blockify places it back). Exactly 40 u per block: PhysicsInitBox of the size, frozen when
-- spawned. Both are plain entity-table fields (the duplicator and AdvDupe2 copy them through the
-- "Data" argument) and network vars (the client draws the box).
--
-- Drawing: one IMesh per entity, flat-coloured faces shaded per side (top brightest) with a darker
-- line at every block edge, colour by block (S.BlockColor): GMod has no Minecraft textures, so
-- "textured where available" is a colour per block family, a hash tint for the rest.

local M = gmodcraft.mccube or {}
gmodcraft.mccube = M

local CLASS = "gmodcraft_cube"
local U = 40
M.MAX_EDGE = 16

local ENT = {}
ENT.Type = "anim"
ENT.Base = "base_anim"
ENT.PrintName = "Minecraft block box"
ENT.Category = "Garry's Modcraft"
ENT.Spawnable = false
ENT.AdminOnly = false
ENT.RenderGroup = RENDERGROUP_OPAQUE

function ENT:SetupDataTables()
	self:NetworkVar("Vector", 0, "BoxSize")
	self:NetworkVar("String", 0, "Block")
end

-- Size in blocks, clamped to 1..MAX_EDGE (whole numbers).
function M.ClampSize(v)
	local function c(n) return math.max(1, math.min(M.MAX_EDGE, math.floor((tonumber(n) or 1) + 0.5))) end
	return Vector(c(v and v.x), c(v and v.y), c(v and v.z))
end

function M.Bounds(size)
	local h = Vector(size.x * U / 2, size.y * U / 2, size.z * U / 2)
	return Vector(-h.x, -h.y, -h.z), h
end

function ENT:Initialize()
	local size = M.ClampSize(self.Size or (self.GetBoxSize and self:GetBoxSize()) or Vector(1, 1, 1))
	local mins, maxs = M.Bounds(size)
	if SERVER then
		self.Size = size
		self:SetBoxSize(size)
		self:SetBlock(tostring(self.Block or "minecraft:stone"))
		self:SetModel(gmodcraft.structures and gmodcraft.structures.CUBE_MODEL or "models/hunter/blocks/cube1x1x1.mdl")
		self:PhysicsInitBox(mins, maxs)
		self:SetSolid(SOLID_VPHYSICS)
		self:SetMoveType(MOVETYPE_VPHYSICS)
		self:EnableCustomCollisions(true)
		local phys = self:GetPhysicsObject()
		if IsValid(phys) then
			phys:SetMass(math.max(1, math.min(50000, size.x * size.y * size.z * 50)))
			phys:EnableMotion(false)
		end
	end
	self:SetCollisionBounds(mins, maxs)
	if CLIENT then self:SetRenderBounds(mins, maxs) end
end

if SERVER then
	-- The duplicator's / AdvDupe2's spawn function: data = the entity table (Pos, Angle, Size, Block).
	function M.Make(ply, data)
		if IsValid(ply) and gmodcraft.tools and not gmodcraft.tools.IsAdmin(ply) and not (game.SinglePlayer and game.SinglePlayer()) then
			-- anyone may paste a structure someone exported; the server's prop limit applies
			if ply.CheckLimit and not ply:CheckLimit("props") then return nil end
		end
		local ent = ents.Create(CLASS)
		if not IsValid(ent) then return nil end
		ent.Size = M.ClampSize(data.Size)
		ent.Block = tostring(data.Block or "minecraft:stone"):sub(1, 200)
		ent:SetPos(data.Pos or Vector(0, 0, 0))
		ent:SetAngles(data.Angle or Angle(0, 0, 0))
		ent:Spawn()
		ent:Activate()
		if IsValid(ply) then
			if ply.AddCount then ply:AddCount("props", ent) end
			if ply.AddCleanup then ply:AddCleanup("props", ent) end
		end
		return ent
	end
	duplicator.RegisterEntityClass(CLASS, M.Make, "Data")
end

-- ---- colours --------------------------------------------------------------------------------------
local DYES = { white = { 234, 236, 237 }, orange = { 241, 118, 20 }, magenta = { 190, 69, 180 }, light_blue = { 58, 175, 217 }, yellow = { 249, 198, 40 },
	lime = { 112, 185, 26 }, pink = { 238, 141, 172 }, gray = { 63, 68, 72 }, light_gray = { 142, 142, 135 }, cyan = { 21, 138, 145 },
	purple = { 122, 42, 173 }, blue = { 53, 57, 157 }, brown = { 114, 72, 41 }, green = { 85, 110, 28 }, red = { 161, 39, 35 }, black = { 21, 21, 26 } }
local DYE_ORDER = { "light_blue", "light_gray", "magenta", "orange", "purple", "yellow", "white", "brown", "black", "green", "lime", "pink", "gray", "cyan", "blue", "red" }
local FAMILIES = {  -- first match wins (substring of the block id)
	{ "glass", { 175, 215, 235 } }, { "grass_block", { 95, 159, 53 } }, { "leaves", { 60, 120, 40 } }, { "moss", { 90, 110, 45 } },
	{ "dirt", { 134, 96, 67 } }, { "mud", { 60, 57, 60 } }, { "farmland", { 110, 75, 50 } }, { "sandstone", { 216, 203, 155 } }, { "sand", { 219, 207, 163 } },
	{ "gravel", { 131, 127, 126 } }, { "dark_oak", { 66, 43, 20 } }, { "spruce", { 115, 85, 49 } }, { "birch", { 196, 179, 123 } }, { "jungle", { 160, 115, 80 } },
	{ "acacia", { 168, 90, 50 } }, { "cherry", { 226, 178, 172 } }, { "mangrove", { 117, 54, 48 } }, { "bamboo", { 194, 173, 80 } }, { "crimson", { 101, 48, 70 } },
	{ "warped", { 43, 104, 99 } }, { "oak", { 162, 130, 78 } }, { "planks", { 162, 130, 78 } }, { "log", { 109, 85, 50 } },
	{ "stone_brick", { 122, 121, 122 } }, { "bricks", { 150, 97, 83 } }, { "deepslate", { 80, 80, 82 } }, { "obsidian", { 20, 18, 30 } }, { "bedrock", { 85, 85, 85 } }, { "netherrack", { 97, 38, 38 } },
	{ "quartz", { 235, 229, 222 } }, { "snow", { 249, 254, 254 } }, { "ice", { 145, 183, 253 } }, { "iron", { 220, 220, 220 } }, { "gold", { 246, 208, 61 } },
	{ "diamond", { 98, 237, 228 } }, { "emerald", { 42, 203, 87 } }, { "lapis", { 31, 67, 140 } }, { "redstone", { 175, 24, 5 } }, { "copper", { 192, 107, 79 } },
	{ "tnt", { 219, 68, 52 } }, { "glowstone", { 255, 215, 120 } }, { "water", { 63, 118, 228 } }, { "lava", { 207, 92, 20 } }, { "clay", { 160, 166, 179 } },
	{ "terracotta", { 152, 94, 67 } }, { "bone", { 229, 225, 207 } }, { "cobblestone", { 122, 122, 122 } }, { "andesite", { 136, 136, 136 } },
	{ "diorite", { 188, 188, 188 } }, { "granite", { 149, 103, 85 } }, { "stone", { 125, 125, 125 } },
}

function M.BlockColor(block)
	local id = string.lower(tostring(block or "")):gsub("%[.*$", ""):gsub("^minecraft:", "")
	for _, dye in ipairs(DYE_ORDER) do  -- longest first: light_blue before blue
		if id:sub(1, #dye + 1) == dye .. "_" then
			local c = DYES[dye]
			return c[1], c[2], c[3]
		end
	end
	for _, f in ipairs(FAMILIES) do
		if id:find(f[1], 1, true) then return f[2][1], f[2][2], f[2][3] end
	end
	local h = 0
	for i = 1, #id do h = (h * 31 + id:byte(i)) % 65536 end
	return 90 + h % 120, 90 + math.floor(h / 7) % 120, 90 + math.floor(h / 49) % 120
end

-- ---- client drawing -------------------------------------------------------------------------------
if CLIENT then
	local mat = CreateMaterial("gmodcraft_cube_flat", "UnlitGeneric", { ["$basetexture"] = "color/white", ["$vertexcolor"] = 1, ["$vertexalpha"] = 1 })
	-- faces: normal axis, sign, shade
	local FACES = {
		{ 3, 1, 1.0 }, { 3, -1, 0.5 }, { 1, 1, 0.8 }, { 1, -1, 0.7 }, { 2, 1, 0.75 }, { 2, -1, 0.65 },
	}
	local function quadCorners(lo, hi, axis, sign)
		local a = axis == 1 and 2 or 1
		local b = axis == 3 and 2 or 3
		local fixed = sign > 0 and hi[axis] or lo[axis]
		local function pt(u, v)
			local p = { 0, 0, 0 }
			p[axis], p[a], p[b] = fixed, u, v
			return Vector(p[1], p[2], p[3])
		end
		local q = { pt(lo[a], lo[b]), pt(hi[a], lo[b]), pt(hi[a], hi[b]), pt(lo[a], hi[b]) }
		-- outward winding
		local n = (q[2] - q[1]):Cross(q[3] - q[1])
		local want = ({ n.x, n.y, n.z })[axis]
		if (want > 0) ~= (sign > 0) then q[2], q[4] = q[4], q[2] end
		return q, a, b
	end

	function M.BuildMesh(size, block)
		local r, g, b = M.BlockColor(block)
		local mins, maxs = M.Bounds(size)
		local lo, hi = { mins.x, mins.y, mins.z }, { maxs.x, maxs.y, maxs.z }
		local sz = { size.x, size.y, size.z }
		local tris = {}
		for _, f in ipairs(FACES) do
			local q = quadCorners(lo, hi, f[1], f[2])
			local s = f[3]
			local col = { r * s, g * s, b * s }
			tris[#tris + 1] = { q[1], q[2], q[3], col }
			tris[#tris + 1] = { q[1], q[3], q[4], col }
		end
		-- block edge lines as thin dark strips on each face (0.6 u wide, 0.05 u out)
		for _, f in ipairs(FACES) do
			local axis, sign = f[1], f[2]
			local _, a, bb = quadCorners(lo, hi, axis, sign)
			local out = (sign > 0 and hi[axis] or lo[axis]) + sign * 0.05
			local col = { r * f[3] * 0.55, g * f[3] * 0.55, b * f[3] * 0.55 }
			local function strip(dir, k)
				local other = dir == a and bb or a
				local at = lo[dir] + k * U
				local p = {}
				local function v(du, dv)
					local c = { 0, 0, 0 }
					c[axis] = out
					c[dir] = math.max(lo[dir], math.min(hi[dir], at + du))
					c[other] = dv
					return Vector(c[1], c[2], c[3])
				end
				p[1], p[2], p[3], p[4] = v(-0.3, lo[other]), v(0.3, lo[other]), v(0.3, hi[other]), v(-0.3, hi[other])
				tris[#tris + 1] = { p[1], p[2], p[3], col }
				tris[#tris + 1] = { p[1], p[3], p[4], col }
				tris[#tris + 1] = { p[1], p[3], p[2], col }
				tris[#tris + 1] = { p[1], p[4], p[3], col }
			end
			for k = 0, sz[a] do strip(a, k) end
			for k = 0, sz[bb] do strip(bb, k) end
		end
		local m = Mesh(mat)
		mesh.Begin(m, MATERIAL_TRIANGLES, #tris)
		for _, t in ipairs(tris) do
			for i = 1, 3 do
				mesh.Position(t[i])
				mesh.Color(t[4][1], t[4][2], t[4][3], 255)
				mesh.AdvanceVertex()
			end
		end
		mesh.End()
		return m
	end

	function ENT:Draw()
		local size, block = self:GetBoxSize(), self:GetBlock()
		local key = string.format("%d,%d,%d|%s", size.x, size.y, size.z, block)
		if self.gmcMeshKey ~= key then
			if IsValid(self.gmcMesh) then self.gmcMesh:Destroy() end
			self.gmcMesh, self.gmcMeshKey = M.BuildMesh(M.ClampSize(size), block), key
			local mins, maxs = M.Bounds(M.ClampSize(size))
			self:SetRenderBounds(mins, maxs)
			-- the size arrives after Initialize: the client's box for movement prediction too
			self:SetCollisionBounds(mins, maxs)
			self:PhysicsInitBox(mins, maxs)
		end
		local mtx = Matrix()
		mtx:SetTranslation(self:GetPos())
		mtx:SetAngles(self:GetAngles())
		render.SetMaterial(mat)
		cam.PushModelMatrix(mtx)
		self.gmcMesh:Draw()
		cam.PopModelMatrix()
	end

	function ENT:OnRemove()
		if IsValid(self.gmcMesh) then self.gmcMesh:Destroy() end
	end
end

scripted_ents.Register(ENT, CLASS)
