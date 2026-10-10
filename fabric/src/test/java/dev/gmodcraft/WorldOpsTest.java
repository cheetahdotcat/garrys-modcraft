package dev.gmodcraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** v34 control centre: the world list, backup stamps and the scheduled world operation. */
class WorldOpsTest {
	@Test
	void stamps() {
		assertTrue(WorldOps.validStamp("20261006-153012"));
		assertFalse(WorldOps.validStamp("20261306-153012"), "month 13");
		assertFalse(WorldOps.validStamp("20260230-000000"), "30 February");
		assertFalse(WorldOps.validStamp("2026106-153012"));
		assertFalse(WorldOps.validStamp("20261006-153012/../x"));
		assertFalse(WorldOps.validStamp(null));
		WorldOps.Entry e = new WorldOps.Entry(Path.of("w"), "19700102-000130", 0);
		assertArrayEquals(new int[] { 1, 90 }, e.dayAndSecond());
	}

	@Test
	void listsBackupsNewestFirstAndSkipsOthers(@TempDir Path dir) throws Exception {
		Path world = Files.createDirectories(dir.resolve("world"));
		Files.writeString(world.resolve("level.dat"), "12345");
		Files.createDirectories(dir.resolve("world.bak-20261001-120000"));
		Files.createDirectories(dir.resolve("world.bak-20261005-080000"));
		Files.createDirectories(dir.resolve("world.bak-junk"));
		Files.createDirectories(dir.resolve("other.bak-20261005-080000"));
		Files.writeString(dir.resolve("world.bak-20261003-000000"), "a file, not a world");
		Files.createSymbolicLink(dir.resolve("world.bak-20261004-000000"), world);
		List<WorldOps.Entry> l = WorldOps.list(world.resolve("."), true);
		assertEquals(3, l.size(), l.toString());
		assertFalse(l.get(0).backup());
		assertEquals(5, l.get(0).bytes());
		assertEquals("20261005-080000", l.get(1).stamp());
		assertEquals("20261001-120000", l.get(2).stamp());
	}

	@Test
	void checksOperations(@TempDir Path dir) throws Exception {
		Path world = Files.createDirectories(dir.resolve("world"));
		Files.createDirectories(dir.resolve("world.bak-20261001-120000"));
		List<WorldOps.Entry> l = WorldOps.list(world, false);
		assertEquals(new WorldOps.Op(Proto.WORLD_OP_NEW, "flat_void_maps"), WorldOps.check(Proto.WORLD_OP_NEW, "gmodcraft:flat_void_maps", l));
		assertEquals(new WorldOps.Op(Proto.WORLD_OP_RESTORE, "20261001-120000"), WorldOps.check(Proto.WORLD_OP_RESTORE, "20261001-120000", l));
		assertThrows(IllegalArgumentException.class, () -> WorldOps.check(Proto.WORLD_OP_NEW, "underground", l));
		assertThrows(IllegalArgumentException.class, () -> WorldOps.check(Proto.WORLD_OP_NEW, "mirror; rm -rf /", l));
		assertThrows(IllegalArgumentException.class, () -> WorldOps.check(Proto.WORLD_OP_RESTORE, "20261002-120000", l), "no such backup");
		assertThrows(IllegalArgumentException.class, () -> WorldOps.check(Proto.WORLD_OP_RESTORE, "../../etc", l));
		assertThrows(IllegalArgumentException.class, () -> WorldOps.check(9, "x", l));
	}

	@Test
	void schedulesAndReadsBack(@TempDir Path dir) throws Exception {
		assertNull(WorldOps.pending(dir));
		WorldOps.schedule(dir, new WorldOps.Op(Proto.WORLD_OP_RESTORE, "20261001-120000"));
		assertEquals("restore 20261001-120000\n", Files.readString(dir.resolve(WorldOps.OP_FILE)));
		assertEquals(new WorldOps.Op(Proto.WORLD_OP_RESTORE, "20261001-120000"), WorldOps.pending(dir));
		WorldOps.schedule(dir, new WorldOps.Op(Proto.WORLD_OP_NEW, "flat_everywhere"));
		assertEquals(new WorldOps.Op(Proto.WORLD_OP_NEW, "flat_everywhere"), WorldOps.pending(dir));
		Files.writeString(dir.resolve(WorldOps.OP_FILE), "new nonsense\n");
		assertNull(WorldOps.pending(dir), "a line the script would refuse isn't shown as pending");
		WorldOps.schedule(dir, null);
		assertFalse(Files.exists(dir.resolve(WorldOps.OP_FILE)));
	}
}
