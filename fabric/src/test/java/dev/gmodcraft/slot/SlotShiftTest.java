package dev.gmodcraft.slot;

import dev.gmodcraft.world.MapSlots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.world.SkyDig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlotShiftTest {
	@Test
	void blocksRoundHalfAwayFromZero() {
		assertEquals(0, SlotShift.blocksFor(0));
		assertEquals(1, SlotShift.blocksFor(37));    // 0.925 blocks
		assertEquals(0, SlotShift.blocksFor(19));    // 0.475
		assertEquals(1, SlotShift.blocksFor(20));    // 0.5
		assertEquals(-1, SlotShift.blocksFor(-20));
		assertEquals(37, SlotShift.blocksFor(37 * 40));
		assertEquals(-68, SlotShift.blocksFor(-2704));  // gm_construct's floor offset, back
	}

	private static SkyDig.DugColumn column(int world, int... ys) {
		SkyDig.DugColumn c = SkyDig.DugColumn.EMPTY;
		for (int y : ys) {
			c = with(c, world, 3, y, 5);
		}
		return c;
	}

	private static SkyDig.DugColumn with(SkyDig.DugColumn c, int world, int x, int y, int z) {
		// the same layout SkyDig uses: section y >> 4, bit x + 16z + 256(y & 15)
		int sy = y >> 4, bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
		java.util.List<SkyDig.DugSection> out = new java.util.ArrayList<>();
		boolean found = false;
		for (SkyDig.DugSection s : c.sections()) {
			if (s.world() == world && s.sectionY() == sy) {
				long[] b = s.bits().clone();
				b[bit >> 6] |= 1L << (bit & 63);
				out.add(new SkyDig.DugSection(world, sy, b));
				found = true;
			} else {
				out.add(s);
			}
		}
		if (!found) {
			long[] b = new long[64];
			b[bit >> 6] |= 1L << (bit & 63);
			out.add(new SkyDig.DugSection(world, sy, b));
		}
		return new SkyDig.DugColumn(List.copyOf(out));
	}

	@Test
	void dugCellsCrossSectionBoundaries() {
		SkyDig.DugColumn c = column(7, 14, 15, 16, -1, -16);
		SkyDig.DugColumn m = SlotShift.shiftDug(c, 7, 3, -1024, 1023);
		for (int y : new int[] { 17, 18, 19, 2, -13 }) {
			assertTrue(m.isDug(7, 3, y, 5), "dug at " + y);
		}
		for (int y : new int[] { 14, 15, 16, -1, -16 }) {
			assertFalse(m.isDug(7, 3, y, 5), "old cell " + y + " gone");
		}
		// and back: exactly the original cells
		SkyDig.DugColumn back = SlotShift.shiftDug(m, 7, -3, -1024, 1023);
		for (int y = -40; y < 40; y++) {
			assertEquals(c.isDug(7, 3, y, 5), back.isDug(7, 3, y, 5), "y " + y);
		}
		// x / z kept
		assertTrue(SlotShift.shiftDug(with(SkyDig.DugColumn.EMPTY, 7, 15, 0, 15), 7, 37, -1024, 1023).isDug(7, 15, 37, 15));
	}

	@Test
	void otherWorldsAndZeroShiftUntouched() {
		SkyDig.DugColumn c = with(column(7, 10), 9, 3, 10, 5);
		SkyDig.DugColumn m = SlotShift.shiftDug(c, 7, 100, -1024, 1023);
		assertTrue(m.isDug(9, 3, 10, 5) && !m.isDug(7, 3, 10, 5) && m.isDug(7, 3, 110, 5));
		assertSame(c, SlotShift.shiftDug(c, 7, 0, -1024, 1023));
	}

	@Test
	void cellsLeavingTheDimensionAreDropped() {
		SkyDig.DugColumn m = SlotShift.shiftDug(column(7, 1020, 10), 7, 10, -1024, 1023);
		assertTrue(m.isDug(7, 3, 20, 5));
		assertFalse(m.isDug(7, 3, 1030, 5));
		assertTrue(SlotShift.fits(10, 1013, 10, -1024, 1023));
		assertFalse(SlotShift.fits(10, 1014, 10, -1024, 1023));
		assertTrue(SlotShift.fits(1, 0, 5000, -1024, 1023));  // nothing there
	}

	@Test
	void slotRegionFilesAreFourByFour() {
		List<int[]> r = SlotShift.regionFiles(0, 0);
		assertEquals(16, r.size());
		assertEquals(-2, r.get(0)[0]);
		assertEquals(1, r.get(15)[0]);
		List<int[]> r2 = SlotShift.regionFiles(2048, -4096);
		assertEquals(2, r2.get(0)[0]);
		assertEquals(-10, r2.get(0)[1]);
		assertEquals(5, r2.get(15)[0]);
		assertEquals(-7, r2.get(15)[1]);
	}

	@Test
	void regionHeaderListsStoredChunks(@TempDir Path dir) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(8192);
		b.putInt(0, 0x00000201);            // chunk (0, 0) of the region
		b.putInt(4 * (5 + 32 * 7), 0x00000301);  // chunk (5, 7)
		Path f = dir.resolve("r.-1.2.mca");
		Files.write(f, b.array());
		List<int[]> c = SlotShift.chunksIn(f, -1, 2);
		assertEquals(2, c.size());
		assertEquals(-32, c.get(0)[0]);
		assertEquals(64, c.get(0)[1]);
		assertEquals(-27, c.get(1)[0]);
		assertEquals(71, c.get(1)[1]);
		assertTrue(SlotShift.chunksIn(dir.resolve("missing.mca"), 0, 0).isEmpty());
	}

	@Test
	void demoBoxesMoveWithTheBlocks() {
		dev.gmodcraft.demo.DemoShape.Box b = new dev.gmodcraft.demo.DemoShape.Box(1, -3, 2, 9, 4, 8);
		dev.gmodcraft.demo.DemoShape.Box m = SlotShift.shiftBox(b, 37);
		assertEquals(new dev.gmodcraft.demo.DemoShape.Box(1, 34, 2, 9, 41, 8), m);
		assertEquals(b, SlotShift.shiftBox(m, -37));
	}

	@Test
	void historyAndMissedBlocks() {
		List<dev.gmodcraft.world.MapSlots.Reanchor> h = List.of(new dev.gmodcraft.world.MapSlots.Reanchor(1, 0, 1480, 37, 0, 0),
			new dev.gmodcraft.world.MapSlots.Reanchor(2, 0, 37, 1, 1480, 0), new dev.gmodcraft.world.MapSlots.Reanchor(3, 0, -37, -1, 1517, 2));
		dev.gmodcraft.world.MapSlots.Slot s = new dev.gmodcraft.world.MapSlots.Slot("gm_x", 1, 0, 0, 1480, 0.0F, 3, null, h);
		assertEquals(37, s.blocksMoved(3));
		assertEquals(38, s.blocksMoved(2));
		assertEquals(37, SlotShift.missedBlocks(s, 0));  // logged out before all three
		assertEquals(0, SlotShift.missedBlocks(s, 1));   // the +1 and its undo cancel
		assertEquals(-1, SlotShift.missedBlocks(s, 2));
		assertEquals(0, SlotShift.missedBlocks(s, 3));
		assertEquals(0, SlotShift.missedBlocks(new dev.gmodcraft.world.MapSlots.Slot("gm_y", 2, 1, 0), 0));
	}

	// ---- the journal ------------------------------------------------------------------------------

	private static ReanchorJournal.State state() {
		ReanchorJournal.State s = new ReanchorJournal.State();
		s.id = 3;
		s.map = "gm_x";
		s.dyUnits = 37;
		s.blocks = 1;
		return s;
	}

	@Test
	void crashMidMoveRestoresTheBackup(@TempDir Path world) throws IOException {
		Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("region/r.0.0.mca"), "before");
		ReanchorJournal j = ReanchorJournal.begin(world, state(), List.of("region/r.0.0.mca", "region/r.1.0.mca"));
		assertEquals(ReanchorJournal.Phase.BACKED_UP, ReanchorJournal.read(world).phase);
		j.phase(ReanchorJournal.Phase.MOVING);
		Files.writeString(world.resolve("region/r.0.0.mca"), "half moved");
		Files.writeString(world.resolve("region/r.1.0.mca"), "a new file");
		// crash: the next start
		assertEquals(ReanchorJournal.Recovery.RESTORED, ReanchorJournal.recover(world));
		assertEquals("before", Files.readString(world.resolve("region/r.0.0.mca")));
		assertFalse(Files.exists(world.resolve("region/r.1.0.mca")), "a file that didn't exist is deleted");
		assertNull(ReanchorJournal.read(world));
		assertFalse(Files.exists(ReanchorJournal.dirOf(world)));
		assertEquals(ReanchorJournal.Recovery.NOTHING, ReanchorJournal.recover(world));
	}

	@Test
	void committedIsRestoredTooButDoneAndPreparingAreNot(@TempDir Path world) throws IOException {
		Files.writeString(world.resolve("map_slots.json"), "old");
		ReanchorJournal j = ReanchorJournal.begin(world, state(), List.of("map_slots.json"));
		j.phase(ReanchorJournal.Phase.COMMITTED);
		Files.writeString(world.resolve("map_slots.json"), "new");
		assertEquals(ReanchorJournal.Recovery.RESTORED, ReanchorJournal.recover(world));
		assertEquals("old", Files.readString(world.resolve("map_slots.json")));

		ReanchorJournal done = ReanchorJournal.begin(world, state(), List.of("map_slots.json"));
		Files.writeString(world.resolve("map_slots.json"), "moved");
		done.finish();
		assertEquals(ReanchorJournal.Recovery.NOTHING, ReanchorJournal.recover(world));
		assertEquals("moved", Files.readString(world.resolve("map_slots.json")));

		// a journal stuck in PREPARING: the world wasn't touched yet, only the journal goes
		ReanchorJournal.begin(world, state(), List.of("map_slots.json")).phase(ReanchorJournal.Phase.PREPARING);
		assertEquals(ReanchorJournal.Recovery.DROPPED, ReanchorJournal.recover(world));
		assertEquals("moved", Files.readString(world.resolve("map_slots.json")));
	}

	@Test
	void oneJobAtATimeAndAbandon(@TempDir Path world) throws IOException {
		ReanchorJournal j = ReanchorJournal.begin(world, state(), List.of("a"));
		assertThrows(IllegalStateException.class, () -> ReanchorJournal.begin(world, state(), List.of("a")));
		j.abandon();
		assertNull(ReanchorJournal.read(world));
		ReanchorJournal k = ReanchorJournal.begin(world, state(), List.of("a"));
		k.phase(ReanchorJournal.Phase.MOVING);
		assertThrows(IllegalStateException.class, k::abandon);
	}

	@Test
	void unreadableJournalIsNotGuessed(@TempDir Path world) throws IOException {
		Files.createDirectories(ReanchorJournal.dirOf(world));
		Files.writeString(ReanchorJournal.dirOf(world).resolve("journal.json"), "{\"phase\": \"NOPE\"");
		assertThrows(Exception.class, () -> ReanchorJournal.recover(world));
	}

	// ---- review findings 2 and 7 -------------------------------------------------------------------

	@Test
	void smallStepsDontDrift() {
		// two +20 steps from oy 0: one block in all, not two
		int first = SlotShift.blocksForStep(0, 0, 0, 20);
		int second = SlotShift.blocksForStep(0, 20, first, 20);
		assertEquals(1, first + second);
		// three +13 steps: round(39 / 40) = 1 block in all
		int a = SlotShift.blocksForStep(100, 100, 0, 13), b = SlotShift.blocksForStep(100, 113, a, 13), c = SlotShift.blocksForStep(100, 126, a + b, 13);
		assertEquals(1, a + b + c);
		// a whole-block step after them stays exact
		assertEquals(37, SlotShift.blocksForStep(100, 139, a + b + c, 1480));
		// and back to the start: everything moved back
		assertEquals(-(a + b + c + 37), SlotShift.blocksForStep(100, 139 + 1480, a + b + c + 37, -(39 + 1480)));
	}

	@Test
	void slotHistoryIsTheBaseline() {
		MapSlots.Slot s = new MapSlots.Slot("gm_x", 1, 0, 0, 40, 0.0F, 3, null,
			List.of(new MapSlots.Reanchor(1, 0, 20, 1, 20, 0)));
		assertEquals(0, SlotShift.blocksForStep(s, 20));  // 20 -> 60 from 20: one block in all, moved already
		assertEquals(1, SlotShift.blocksForStep(s, 40));
	}

	@Test
	void aDamagedBackupRestoresNothing(@TempDir Path world) throws IOException {
		Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("region/r.0.0.mca"), "before 0");
		Files.writeString(world.resolve("region/r.1.0.mca"), "before 1");
		ReanchorJournal j = ReanchorJournal.begin(world, state(), List.of("region/r.0.0.mca", "region/r.1.0.mca"));
		j.phase(ReanchorJournal.Phase.MOVING);
		Files.writeString(world.resolve("region/r.0.0.mca"), "moved 0");
		Files.writeString(world.resolve("region/r.1.0.mca"), "moved 1");
		Files.writeString(ReanchorJournal.dirOf(world).resolve("backup/region/r.1.0.mca"), "truncat");  // damaged second backup
		assertThrows(IOException.class, () -> ReanchorJournal.recover(world));
		assertEquals("moved 0", Files.readString(world.resolve("region/r.0.0.mca")), "nothing put back before every backup checked out");
		assertEquals(ReanchorJournal.Phase.MOVING, ReanchorJournal.read(world).phase, "the journal stays for a human to look at");
		Files.delete(ReanchorJournal.dirOf(world).resolve("backup/region/r.1.0.mca"));  // missing: refused too
		assertThrows(IOException.class, () -> ReanchorJournal.recover(world));
		assertEquals("moved 0", Files.readString(world.resolve("region/r.0.0.mca")));
	}
}
