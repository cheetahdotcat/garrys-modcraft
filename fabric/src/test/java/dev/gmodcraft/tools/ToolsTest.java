package dev.gmodcraft.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolsTest {
	/** Dug bits and blocks as strings ("air" where unset). */
	static final class World implements TerrainRepair.Access<String> {
		final Set<String> dug = new HashSet<>();
		final Map<String, String> blocks = new HashMap<>();

		static String k(int x, int y, int z) {
			return x + "," + y + "," + z;
		}

		public boolean isDug(int x, int y, int z) {
			return this.dug.contains(k(x, y, z));
		}

		public void setDug(int x, int y, int z, boolean d) {
			if (d) {
				this.dug.add(k(x, y, z));
			} else {
				this.dug.remove(k(x, y, z));
			}
		}

		public String block(int x, int y, int z) {
			return this.blocks.getOrDefault(k(x, y, z), "air");
		}

		public void setBlock(int x, int y, int z, String v) {
			if ("air".equals(v)) {
				this.blocks.remove(k(x, y, z));
			} else {
				this.blocks.put(k(x, y, z), v);
			}
		}
	}

	static boolean terrain(String s) {
		return s.equals("dirt") || s.equals("stone") || s.equals("iron_ore");
	}

	@Test
	void sphereCells() {
		assertEquals(1, TerrainRepair.sphere(0.5, 0.5, 0.5, 0.4).size()); // just the centre cell
		assertEquals(7, TerrainRepair.sphere(0.5, 0.5, 0.5, 1.0).size()); // centre + 6 faces
		List<int[]> r8 = TerrainRepair.sphere(10.5, 64.5, -3.5, 8);
		for (int[] c : r8) {
			double dx = c[0] + 0.5 - 10.5, dy = c[1] + 0.5 - 64.5, dz = c[2] + 0.5 - -3.5;
			assertTrue(dx * dx + dy * dy + dz * dz <= 64.0);
		}
		assertTrue(r8.size() > 2000 && r8.size() < 2200, "r8 " + r8.size()); // ~4/3 pi 8^3 = 2145
	}

	@Test
	void repairKeepsBuildsAndUndoes() {
		World w = new World();
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) {
				w.setDug(x, 5, z, true);
			}
		}
		w.setDug(50, 5, 50, true);        // far away: not in the sphere
		w.setBlock(1, 5, 1, "dirt");       // terrain digging revealed: removed with the cell
		w.setBlock(2, 5, 2, "oak_planks"); // someone built here: the cell stays dug
		TerrainRepair.Result<String> r = TerrainRepair.repair(w, TerrainRepair.sphere(1.5, 5.5, 1.5, 3), "air", "air"::equals, ToolsTest::terrain);
		assertEquals(8, r.restored().size());
		assertEquals(1, r.removed().size());
		assertEquals(1, r.skipped());
		assertFalse(w.isDug(1, 5, 1));
		assertEquals("air", w.block(1, 5, 1));
		assertTrue(w.isDug(2, 5, 2));
		assertEquals("oak_planks", w.block(2, 5, 2));
		assertTrue(w.isDug(50, 5, 50));
		int n = TerrainRepair.undo(w, r, "air"::equals);
		assertEquals(8, n);
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) {
				assertTrue(w.isDug(x, 5, z));
			}
		}
		assertEquals("dirt", w.block(1, 5, 1));
		assertEquals("oak_planks", w.block(2, 5, 2));
	}

	@Test
	void undoLeavesNewBlocksAlone() {
		World w = new World();
		w.setDug(0, 0, 0, true);
		w.setBlock(0, 0, 0, "stone");
		TerrainRepair.Result<String> r = TerrainRepair.repair(w, List.of(new int[] { 0, 0, 0 }), "air", "air"::equals, ToolsTest::terrain);
		w.setBlock(0, 0, 0, "glass"); // someone placed a block after the repair
		TerrainRepair.undo(w, r, "air"::equals);
		assertEquals("glass", w.block(0, 0, 0));
		assertTrue(w.isDug(0, 0, 0));
	}

	@Test
	void undoStacksAreBoundedPerPlayer() {
		UndoStacks<Integer> s = new UndoStacks<>(5);
		for (int i = 1; i <= 7; i++) {
			s.push(1L, i);
		}
		s.push(2L, 100);
		assertEquals(5, s.size(1L));
		assertEquals(7, s.pop(1L));
		assertEquals(6, s.pop(1L));
		assertEquals(100, s.pop(2L));
		assertNull(s.pop(2L));
		s.pop(1L);
		s.pop(1L);
		assertEquals(3, s.pop(1L)); // 1 and 2 fell off
		assertNull(s.pop(1L));
		assertNull(s.pop(99L));
	}

	@Test
	void blockRules() {
		assertEquals(BlockRules.Verdict.OK, BlockRules.check("minecraft:stone", false));
		assertEquals(BlockRules.Verdict.OK, BlockRules.check("oak_stairs[facing=east,half=top]", false));
		assertEquals(BlockRules.Verdict.OK, BlockRules.check("  Minecraft:Glass ", false));
		assertEquals(BlockRules.Verdict.LIQUID, BlockRules.check("minecraft:water", false));
		assertEquals(BlockRules.Verdict.LIQUID, BlockRules.check("lava", false));
		assertEquals(BlockRules.Verdict.LIQUID, BlockRules.check("minecraft:bubble_column", false));
		assertEquals(BlockRules.Verdict.LIQUID, BlockRules.check("minecraft:oak_slab[waterlogged=true]", false));
		assertEquals(BlockRules.Verdict.OK, BlockRules.check("minecraft:water", true));
		assertEquals(BlockRules.Verdict.OK, BlockRules.check("minecraft:oak_slab[waterlogged=true]", true));
		assertEquals(BlockRules.Verdict.OPERATOR, BlockRules.check("minecraft:command_block", true));
		assertEquals(BlockRules.Verdict.OPERATOR, BlockRules.check("structure_block", false));
		assertEquals(BlockRules.Verdict.OPERATOR, BlockRules.check("minecraft:barrier", false));
		assertEquals(BlockRules.Verdict.MALFORMED, BlockRules.check("", false));
		assertEquals(BlockRules.Verdict.MALFORMED, BlockRules.check("stone; say hi", false));
		assertEquals(BlockRules.Verdict.MALFORMED, BlockRules.check("stone[facing]", false));
		assertEquals(BlockRules.Verdict.MALFORMED, BlockRules.check("x".repeat(61), false));
		// tools open to everyone: non-admins don't get these
		for (String s : new String[] { "minecraft:tnt", "bedrock", "minecraft:end_portal", "end_gateway", "nether_portal", "fire", "soul_fire", "minecraft:spawner" }) {
			assertEquals(BlockRules.Verdict.RESTRICTED, BlockRules.check(s, false, true), s);
			assertEquals(BlockRules.Verdict.OK, BlockRules.check(s, false, false), s);
		}
		assertEquals(BlockRules.Verdict.OK, BlockRules.check("minecraft:stone", false, true));
		assertEquals(BlockRules.Verdict.OPERATOR, BlockRules.check("minecraft:barrier", false, true));
		assertFalse(BlockRules.everyoneMayBreak("bedrock"));
		assertFalse(BlockRules.everyoneMayBreak("spawner"));
		assertFalse(BlockRules.everyoneMayBreak("command_block"));
		assertTrue(BlockRules.everyoneMayBreak("stone"));
	}

	@Test
	void toolCommandIds() {
		assertTrue(ToolWorld.handles(Proto.ADMIN_REPAIR_RADIUS));
		assertTrue(ToolWorld.handles(Proto.ADMIN_RESYNC));
		assertFalse(ToolWorld.handles(Proto.ADMIN_DEMO_PLACE));
		assertFalse(ToolWorld.handles(Proto.ADMIN_DEMO_ANNOUNCE));
		assertTrue(Proto.VERSION >= 20);  // v20 brought the tools
	}
}
