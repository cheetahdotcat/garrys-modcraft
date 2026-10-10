-- Hybrid mode (D-016, protocol v17), shared helpers: GMod weapon classes <-> the class hash on the
-- wire, and the item sprite category (WeaponCategory) from a SWEP's HoldType.
--  * Hash: FNV-1a 32 of the lowercased class (the module's WorldId, the same function as the map's
--    worldId; Java's Fnv.hash32). Pure Lua fallback for headless tests.
--  * Hash -> class: weapons.GetList() + list "Weapon" + the HL2 built-ins + every class a player
--    owned or was given. Two classes with one hash are a collision: logged once, and that hash
--    resolves to nothing (the item stays inert) rather than to the wrong weapon.

local HS = gmodcraft.hybridShared or {}
gmodcraft.hybridShared = HS

local K = gmodcraft.K or {}
HS.HANDS = "gmodcraft_hands"  -- the hands SWEP: never mirrored into Minecraft

local TWO32 = 4294967296

-- FNV-1a 32 in doubles without losing bits: h * 16777619 = (h << 24) + h * 0x193 (mod 2^32).
function HS.HashLua(s)
	local h = 0x811C9DC5
	s = string.lower(s)
	for i = 1, #s do
		h = bit.bxor(h, s:byte(i)) % TWO32
		h = (bit.lshift(h, 24) % TWO32 + h * 0x193) % TWO32
	end
	return h
end

function HS.Hash(class)
	if gmodcraft.WorldId and #class < 255 then return gmodcraft.WorldId(class) end
	return HS.HashLua(class)
end

-- WeaponCategory from HoldType (protocol header); tools by class.
local CAT = {
	generic = K.WeapCatGeneric or 0, pistol = K.WeapCatPistol or 1, smg = K.WeapCatSmg or 2, rifle = K.WeapCatRifle or 3,
	shotgun = K.WeapCatShotgun or 4, heavy = K.WeapCatHeavy or 5, melee = K.WeapCatMelee or 6, tool = K.WeapCatTool or 7,
}
HS.CAT = CAT
local BY_HOLDTYPE = {
	pistol = CAT.pistol, revolver = CAT.pistol, duel = CAT.pistol,
	smg = CAT.smg,
	ar2 = CAT.rifle, crossbow = CAT.rifle,
	shotgun = CAT.shotgun,
	rpg = CAT.heavy,
	melee = CAT.melee, melee2 = CAT.melee, knife = CAT.melee, fist = CAT.melee,
	physgun = CAT.tool, camera = CAT.tool, magic = CAT.tool,
}
local TOOLS = { weapon_physgun = true, gmod_tool = true, weapon_physcannon = true, gmod_camera = true }

-- HL2 / GMod built-ins (C++ weapons: no weapons.Get table, no HoldType field to read).
HS.BUILTIN = {
	weapon_physgun = { "Physics Gun", CAT.tool }, weapon_physcannon = { "Gravity Gun", CAT.tool },
	weapon_crowbar = { "Crowbar", CAT.melee }, weapon_stunstick = { "Stunstick", CAT.melee },
	weapon_pistol = { "9mm Pistol", CAT.pistol }, weapon_357 = { ".357 Magnum", CAT.pistol }, weapon_alyxgun = { "Alyx Gun", CAT.pistol },
	weapon_smg1 = { "SMG", CAT.smg }, weapon_ar2 = { "Pulse-Rifle", CAT.rifle }, weapon_crossbow = { "Crossbow", CAT.rifle },
	weapon_annabelle = { "Annabelle", CAT.shotgun }, weapon_shotgun = { "Shotgun", CAT.shotgun }, weapon_rpg = { "RPG", CAT.heavy },
	weapon_frag = { "Grenade", CAT.generic }, weapon_slam = { "S.L.A.M", CAT.generic }, weapon_bugbait = { "Bugbait", CAT.generic },
}

function HS.Category(class, holdType)
	if TOOLS[class] then return CAT.tool end
	local b = HS.BUILTIN[class]
	if b and not holdType then return b[2] end
	return BY_HOLDTYPE[string.lower(holdType or "")] or (b and b[2]) or CAT.generic
end

-- Category and print name of a class (a weapon entity, when one is at hand, knows best).
function HS.Describe(class, wep)
	local hold, name
	if wep and wep.GetHoldType then hold = wep:GetHoldType() end
	if wep and wep.GetPrintName then name = wep:GetPrintName() end
	local stored = weapons and weapons.Get and weapons.Get(class)
	if stored then
		hold = hold or stored.HoldType
		name = name or stored.PrintName
	end
	local b = HS.BUILTIN[class]
	if (not name or name == "" or string.sub(name, 1, 1) == "#") and b then name = b[1] end
	if not name or name == "" or string.sub(name, 1, 1) == "#" then name = class end
	return HS.Category(class, hold), name
end

-- ---- hash -> class ---------------------------------------------------------------------------
HS.byHash = HS.byHash or {}        -- hash -> class, or false when two classes share it
HS.collisions = HS.collisions or {} -- "a|b" -> true (logged once)
HS.known = HS.known or {}           -- class -> true

-- Adds a class to the map; returns false when it collides with another class.
function HS.Learn(class)
	if not class or class == "" then return false end
	class = string.lower(class)
	if HS.known[class] then return HS.byHash[HS.Hash(class)] == class end
	HS.known[class] = true
	local h = HS.Hash(class)
	local cur = HS.byHash[h]
	if cur == nil then
		HS.byHash[h] = class
		return true
	end
	if cur ~= class then
		local key = cur and ((cur < class) and (cur .. "|" .. class) or (class .. "|" .. cur)) or (class .. "|*")
		if not HS.collisions[key] then
			HS.collisions[key] = true
			local log = gmodcraft.Info or print
			if cur then
				log(string.format("hybrid: weapon class hash collision: %s and %s both hash to %08x; Minecraft stacks of either stay inert",
					cur, class, h))
			else
				log(string.format("hybrid: weapon class hash collision: %s also hashes to %08x (already a collision); Minecraft stacks of it stay inert",
					class, h))
			end
		end
		HS.byHash[h] = false
		return false
	end
	return true
end

-- Rebuilds from what's installed (cheap; call when the SWEP list may have changed).
function HS.Refresh()
	for c in pairs(HS.BUILTIN) do HS.Learn(c) end
	if weapons and weapons.GetList then
		for _, t in ipairs(weapons.GetList()) do
			if t.ClassName then HS.Learn(t.ClassName) end
		end
	end
	if list and list.Get then
		for c in pairs(list.Get("Weapon") or {}) do HS.Learn(c) end
	end
	HS.refreshedAt = CurTime and CurTime() or 0
end

-- The class for a hash, or nil (unknown, or a collision).
function HS.Resolve(h)
	local c = HS.byHash[h]
	if c == HS.HANDS then return nil end  -- the hands SWEP is never a Minecraft item
	return c or nil
end
