-- Measured floors of known maps (P8 WP1, protocol v21): the z (Source units) of the floor that should
-- sit on Minecraft y = kAnchorFloorY (64) when the map gets its slot. Wins over the automatic detection
-- (server/anchor.lua); the operator's data/gmodcraft/map_anchors.json wins over this table.
--
-- MEASURED, never guessed: `gmodcraft_anchor_probe` in game, or headlessly with the module's own map
-- decoder (module/test/mapcol: MAPCOL_ANCHORS=<file> make test prints and writes them; the same
-- ChooseAnchor the game uses, spawn points from the BSP's entity lump). Only a slot that is NEW when
-- the map is first seen takes the offset; existing slots keep theirs.
--
-- floorZ: Source units. spawns: how many spawn points stood on it when measured. oyUnits follows as
-- 64 * 40 - floorZ (clamped by the MC server so the map's z range fits the mirror dimension).
gmodcraft.mapAnchors = {
	-- 2026-10-06, mapcol oracle decode: all 33 spawns on the main floor (brush 128's top, z -144).
	-- z range -1024 .. 15359 (skybox). oyUnits 2704.
	gm_construct = { floorZ = -144, spawns = 33 },
	-- 2026-10-06, mapcol oracle decode: all 56 spawns on the grass at z -12288. z range -16128 .. 15872.
	-- oyUnits 14848.
	gm_flatgrass = { floorZ = -12288, spawns = 56 },
	-- TODO gm_bigcity: not installed on the measuring machine; measure with gmodcraft_anchor_probe.
}
