package dev.gmodcraft.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A hull file (protocol/hull_format.md, version 1): one GMod map traced into blocks by the GMod
 * server's module. Every solid block becomes a "mirror" block of the hull world type. Pure Java (no
 * game classes), so it is unit-tested on its own.
 *
 * <p>Regions are 8x8x8 blocks at multiples of 8 in Minecraft block coordinates (the collision
 * stream's), already offset into the map's slot; a region's codes are indexed x + 8 * (z + 8 * y).
 * Code 0 is air, any other code is a dig material + 1.
 */
public final class HullFile {
	public static final byte[] MAGIC = "GMCHULL\0".getBytes(StandardCharsets.US_ASCII);
	public static final int VERSION = 1;
	public static final int HEADER_BYTES = 64;
	public static final int REGION = 8;
	public static final int REGION_CELLS = REGION * REGION * REGION;
	/** Highest valid code: the last dig material + 1. */
	public static final int MAX_CODE = dev.gmodcraft.link.Proto.DIG_MATERIAL_COUNT;
	/** A file bigger than this isn't read (a whole map is a few MB). */
	public static final long MAX_FILE_BYTES = 256L << 20;
	/** Flag bit 0: static props are in the trace. */
	public static final int FLAG_STATIC_PROPS = 1;

	/** {@code <map>.<hash16>.bin}: the file name the module writes. */
	private static final Pattern NAME = Pattern.compile("(.+)\\.([0-9a-f]{16})\\.bin");

	/** A file that doesn't check out (it is never used). */
	public static final class BadFile extends Exception {
		public BadFile(String message) {
			super(message);
		}
	}

	/** The map name and hash a file name gives, or null for any other name (a .tmp, a stray file). */
	public record Name(String map, long hash) {
		public static @Nullable Name parse(String fileName) {
			Matcher m = NAME.matcher(fileName);
			if (!m.matches()) {
				return null;
			}
			return new Name(m.group(1), Long.parseUnsignedLong(m.group(2), 16));
		}

		public String fileName() {
			return HullFile.fileName(this.map, this.hash);
		}
	}

	public static String fileName(String map, long hash) {
		return map + "." + hex(hash) + ".bin";
	}

	public static String hex(long hash) {
		return String.format(Locale.ROOT, "%016x", hash);
	}

	// One shared array per code for regions all of one code (deep inside rock, mostly): saves memory.
	private static final byte[][] UNIFORM = new byte[MAX_CODE + 1][];

	static {
		for (int c = 0; c <= MAX_CODE; c++) {
			UNIFORM[c] = new byte[REGION_CELLS];
			Arrays.fill(UNIFORM[c], (byte) c);
		}
	}

	public final long hash;
	public final int originX, originYUnits, originZ;
	public final int flags;
	public final int solidCount;
	/** Region key ({@link #regionKey}) to its 512 codes (shared arrays: never write to them). */
	public final Long2ObjectMap<byte[]> regions;

	private HullFile(long hash, int originX, int originYUnits, int originZ, int flags, int solidCount, Long2ObjectMap<byte[]> regions) {
		this.hash = hash;
		this.originX = originX;
		this.originYUnits = originYUnits;
		this.originZ = originZ;
		this.flags = flags;
		this.solidCount = solidCount;
		this.regions = regions;
	}

	/** The key of the region holding block (x, y, z). */
	public static long regionKey(int x, int y, int z) {
		return net.minecraft.core.BlockPos.asLong(x >> 3, y >> 3, z >> 3);
	}

	/** The slot cell (slotX, slotZ packed as a ChunkPos long) holding block column (x, z): slots are SLOT_BLOCKS wide, centred on their origin. */
	public static long slotCell(int x, int z) {
		int half = dev.gmodcraft.link.Proto.SLOT_BLOCKS / 2;
		return net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(x + half, dev.gmodcraft.link.Proto.SLOT_BLOCKS),
			Math.floorDiv(z + half, dev.gmodcraft.link.Proto.SLOT_BLOCKS));
	}

	/** Do this file's slot offsets match these (the slot's, in mapcol's frame: blocks, Source units, blocks)? */
	public boolean offsetsMatch(int ox, int oyUnits, int oz) {
		return this.originX == ox && this.originYUnits == oyUnits && this.originZ == oz;
	}

	/** The code at block (x, y, z): 0 (air) outside every region. */
	public int code(int x, int y, int z) {
		byte[] r = this.regions.get(regionKey(x, y, z));
		return r == null ? 0 : r[(x & 7) + 8 * ((z & 7) + 8 * (y & 7))];
	}

	/**
	 * Reads and checks a whole file. Anything that doesn't add up (magic, version, header size, a
	 * region off the 8-grid or twice, runs not covering exactly 512 blocks, a code past the last dig
	 * material, counts that disagree, bytes left over) is a {@link BadFile}.
	 */
	public static HullFile parse(byte[] data) throws BadFile {
		if (data.length > MAX_FILE_BYTES) {
			throw new BadFile("too big: " + data.length + " bytes");
		}
		if (data.length < HEADER_BYTES) {
			throw new BadFile("shorter than the header: " + data.length + " bytes");
		}
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[8];
		b.get(magic);
		if (!Arrays.equals(magic, MAGIC)) {
			throw new BadFile("not a hull file (magic)");
		}
		int version = b.getInt();
		if (version != VERSION) {
			throw new BadFile("version " + Integer.toUnsignedString(version) + ", this reads " + VERSION);
		}
		int headerBytes = b.getInt();
		if (headerBytes != HEADER_BYTES) {
			throw new BadFile("header bytes " + Integer.toUnsignedString(headerBytes) + ", expected " + HEADER_BYTES);
		}
		long hash = b.getLong();
		int ox = b.getInt(), oyUnits = b.getInt(), oz = b.getInt();
		long regionCount = Integer.toUnsignedLong(b.getInt());
		long solidCount = Integer.toUnsignedLong(b.getInt());
		int flags = b.getInt();
		b.position(HEADER_BYTES);
		// a region is at least 12 + 2 + 3 bytes
		if (regionCount > (data.length - HEADER_BYTES) / 17L) {
			throw new BadFile("region count " + regionCount + " doesn't fit in " + data.length + " bytes");
		}
		Long2ObjectOpenHashMap<byte[]> regions = new Long2ObjectOpenHashMap<>((int) Math.min(regionCount, 1 << 16));
		long solid = 0;
		byte[] codes = new byte[REGION_CELLS];
		for (long i = 0; i < regionCount; i++) {
			if (b.remaining() < 14) {
				throw new BadFile("region " + i + ": file ends in its header");
			}
			int rx = b.getInt(), ry = b.getInt(), rz = b.getInt();
			if ((rx & 7) != 0 || (ry & 7) != 0 || (rz & 7) != 0) {
				throw new BadFile("region " + i + " at (" + rx + ", " + ry + ", " + rz + ") isn't on the 8-block grid");
			}
			int runs = Short.toUnsignedInt(b.getShort());
			if (b.remaining() < runs * 3L) {
				throw new BadFile("region " + i + ": file ends in its runs");
			}
			int at = 0;
			int uniform = -1;
			for (int r = 0; r < runs; r++) {
				int code = Byte.toUnsignedInt(b.get());
				int len = Short.toUnsignedInt(b.getShort());
				if (code > MAX_CODE) {
					throw new BadFile("region " + i + ": code " + code + " (the last valid one is " + MAX_CODE + ")");
				}
				if (at + len > REGION_CELLS) {
					throw new BadFile("region " + i + ": runs cover more than " + REGION_CELLS + " blocks");
				}
				if (len > 0) {
					Arrays.fill(codes, at, at + len, (byte) code);
					uniform = uniform == -1 || uniform == code ? code : -2;
					if (code != 0) {
						solid += len;
					}
				}
				at += len;
			}
			if (at != REGION_CELLS) {
				throw new BadFile("region " + i + ": runs cover " + at + " blocks, not " + REGION_CELLS);
			}
			long key = regionKey(rx, ry, rz);
			if (regions.containsKey(key)) {
				throw new BadFile("region (" + rx + ", " + ry + ", " + rz + ") is in the file twice");
			}
			regions.put(key, uniform >= 0 ? UNIFORM[uniform] : codes.clone());
		}
		if (b.hasRemaining()) {
			throw new BadFile(b.remaining() + " bytes after the last region");
		}
		if (solid != solidCount) {
			throw new BadFile("header says " + solidCount + " solid blocks, the regions hold " + solid);
		}
		return new HullFile(hash, ox, oyUnits, oz, flags, (int) solid, regions);
	}

	/** Blocks a mirror block's depth rule looks up (deeper than this is "deep"). */
	public static final int DEPTH_LOOK = 6;

	/**
	 * The dig material of the mirror block at (x, y, z), or -1 for none (air). The depth rule: a block
	 * with air above keeps its own material (the map's surface); one under others of the map is what
	 * lies that deep under its material ({@code underground}: dirt under grass, then stone), its depth
	 * the solid blocks above it plus 0.5, looked up at most {@link #DEPTH_LOOK} blocks.
	 */
	public int material(int x, int y, int z, Underground underground) {
		int code = code(x, y, z);
		if (code == 0) {
			return -1;
		}
		int material = code - 1;
		int above = 0;
		while (above < DEPTH_LOOK && code(x, y + above + 1, z) != 0) {
			above++;
		}
		if (above == 0) {
			return material;
		}
		return underground.at(material, above + 0.5);
	}

	/** What lies this deep under a surface of this dig material (SkyDig.underground; a seam for tests). */
	public interface Underground {
		int at(int material, double depth);
	}

	/** Builds a file (tests, and anything that needs to make one): regions given as key -> 512 codes. */
	public static byte[] write(long hash, int ox, int oyUnits, int oz, int flags, java.util.Map<int[], byte[]> regions) {
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		int solid = 0;
		for (var e : regions.entrySet()) {
			int[] min = e.getKey();
			byte[] codes = e.getValue();
			ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(min[0]).putInt(min[1]).putInt(min[2]);
			java.io.ByteArrayOutputStream runs = new java.io.ByteArrayOutputStream();
			int count = 0;
			for (int i = 0; i < REGION_CELLS; ) {
				int j = i;
				while (j < REGION_CELLS && codes[j] == codes[i]) {
					j++;
				}
				runs.write(codes[i]);
				runs.write((j - i) & 0xFF);
				runs.write((j - i) >> 8);
				count++;
				if (codes[i] != 0) {
					solid += j - i;
				}
				i = j;
			}
			body.writeBytes(head.array());
			body.write(count & 0xFF);
			body.write(count >> 8);
			body.writeBytes(runs.toByteArray());
		}
		ByteBuffer h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		h.put(MAGIC).putInt(VERSION).putInt(HEADER_BYTES).putLong(hash).putInt(ox).putInt(oyUnits).putInt(oz).putInt(regions.size()).putInt(solid)
			.putInt(flags);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		out.writeBytes(h.array());
		out.writeBytes(body.toByteArray());
		return out.toByteArray();
	}
}
