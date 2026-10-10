package dev.gmodcraft.tools;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ServerLink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * v38 (S2) kAdminStructExport: a box of Minecraft blocks as text for the GMod side (the Structure
 * Export STool turns it into a dupe). The answer (kEvAdminResult) carries the text's length; the
 * text follows in kEvStructData batches, a few per tick and never filling the event ring past
 * {@link #RESERVE} free slots. Also saves the box as a vanilla structure file
 * ({@code <world>/generated/gmodcraft/structures/export_<requestId>.nbt}, loadable with a structure
 * block or /place template gmodcraft:export_<id>). Admins only. Server thread.
 */
public final class StructExport {
	private StructExport() {
	}

	/** Event ring slots left free for every other event while a text streams. */
	static final int RESERVE = 384;
	/** Batches per tick at most (16 * 44 bytes each). */
	static final int BATCHES_PER_TICK = 24;
	/** An unsent text is dropped after this many ticks (the link is gone or stuck). */
	static final int GIVE_UP_TICKS = 20 * 60;

	public record Result(int code, int count, int flags, String message) {
	}

	/** What {@link #encode} reads: the block state at box-local x/y/z as a string, or null for air. */
	@FunctionalInterface
	public interface StateAt {
		@Nullable String at(int x, int y, int z);
	}

	public record Encoded(byte[] text, int blocks, int palette) {
	}

	private static final class Stream {
		final int requestId;
		final byte[] text;
		int offset;
		int ticks;

		Stream(int requestId, byte[] text) {
			this.requestId = requestId;
			this.text = text;
		}
	}

	private static final ArrayDeque<Stream> STREAMS = new ArrayDeque<>();

	/**
	 * The export text (see kAdminStructExport in the protocol header) of an sx * sy * sz box, or null
	 * when it would be longer than kStructTextMaxBytes.
	 */
	public static @Nullable Encoded encode(int sx, int sy, int sz, StateAt states) {
		Map<String, Integer> index = new HashMap<>();
		List<String> palette = new ArrayList<>();
		StringBuilder runs = new StringBuilder();
		int blocks = 0, run = 0, runIdx = -1;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					String st = states.at(x, y, z);
					int idx = 0;
					if (st != null && !st.isEmpty() && !st.contains("\n") && !st.contains(" ")) {
						Integer have = index.get(st);
						if (have == null) {
							palette.add(st);
							have = palette.size();
							index.put(st, have);
						}
						idx = have;
						blocks++;
					}
					if (idx == runIdx) {
						run++;
					} else {
						appendRun(runs, runIdx, run);
						runIdx = idx;
						run = 1;
					}
				}
			}
			if (runs.length() > Proto.STRUCT_TEXT_MAX_BYTES) {
				return null;
			}
		}
		appendRun(runs, runIdx, run);
		StringBuilder out = new StringBuilder();
		out.append("GMCS 1 ").append(sx).append(' ').append(sy).append(' ').append(sz).append(' ').append(palette.size()).append('\n');
		for (String p : palette) {
			out.append(p).append('\n');
		}
		out.append(runs).append('\n');
		byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
		return bytes.length > Proto.STRUCT_TEXT_MAX_BYTES ? null : new Encoded(bytes, blocks, palette.size());
	}

	private static void appendRun(StringBuilder runs, int idx, int run) {
		if (idx < 0 || run <= 0) {
			return;
		}
		if (runs.length() > 0) {
			runs.append(' ');
		}
		runs.append(idx);
		if (run > 1) {
			runs.append('*').append(run);
		}
	}

	/** Parses "x2 y2 z2" (the text slot); null when it isn't three integers. */
	static int @Nullable [] corner(@Nullable String text) {
		if (text == null) {
			return null;
		}
		String[] p = text.trim().split("\\s+");
		if (p.length != 3) {
			return null;
		}
		try {
			return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** kAdminStructExport: answers at once; the text then streams from {@link #tick}. */
	public static Result run(MinecraftServer server, ServerLink.HostEvent cmd, String text) {
		if ((cmd.flags() & Proto.ADMIN_BY_ADMIN) == 0) {
			return new Result(Proto.ADMIN_NOT_ALLOWED, 0, 0, "structure export is for admins");
		}
		int[] b = corner(text);
		if (b == null) {
			return new Result(Proto.ADMIN_MALFORMED, 0, 0, "the second corner isn't 'x y z'");
		}
		BlockPos a = BlockPos.containing(cmd.x(), cmd.y(), cmd.z());
		int x0 = Math.min(a.getX(), b[0]), y0 = Math.min(a.getY(), b[1]), z0 = Math.min(a.getZ(), b[2]);
		int sx = Math.abs(a.getX() - b[0]) + 1, sy = Math.abs(a.getY() - b[1]) + 1, sz = Math.abs(a.getZ() - b[2]) + 1;
		if (sx > Proto.STRUCT_MAX_EDGE || sy > Proto.STRUCT_MAX_EDGE || sz > Proto.STRUCT_MAX_EDGE) {
			return new Result(Proto.ADMIN_OUT_OF_RANGE, 0, 0, "the box is " + sx + "x" + sy + "x" + sz + " (at most " + Proto.STRUCT_MAX_EDGE + " per edge)");
		}
		ServerLevel level = server.overworld();
		if (y0 < level.getMinY() || y0 + sy - 1 > level.getMaxY()) {
			return new Result(Proto.ADMIN_OUT_OF_RANGE, 0, 0, "the box leaves the world's height");
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		Encoded enc = encode(sx, sy, sz, (x, y, z) -> {
			BlockState st = level.getBlockState(m.set(x0 + x, y0 + y, z0 + z));
			return st.isAir() ? null : BlockStateParser.serialize(st);
		});
		if (enc == null) {
			return new Result(Proto.ADMIN_OUT_OF_RANGE, 0, 0, "the export text would be over " + Proto.STRUCT_TEXT_MAX_BYTES + " bytes");
		}
		if (enc.blocks() == 0) {
			return new Result(Proto.ADMIN_NOTHING, 0, 0, "only air in the box");
		}
		saveNbt(server, level, new BlockPos(x0, y0, z0), new Vec3i(sx, sy, sz), "export_" + Integer.toUnsignedString(cmd.requestId()));
		STREAMS.add(new Stream(cmd.requestId(), enc.text()));
		return new Result(Proto.ADMIN_OK, enc.text().length, enc.blocks(),
			sx + "x" + sy + "x" + sz + " from " + x0 + " " + y0 + " " + z0 + ": " + enc.blocks() + " blocks, " + enc.palette() + " kinds, " + enc.text().length + " bytes");
	}

	/** The vanilla structure file (optional: a failure is only logged). */
	private static void saveNbt(MinecraftServer server, ServerLevel level, BlockPos origin, Vec3i size, String name) {
		try {
			StructureTemplate t = new StructureTemplate();
			t.fillFromWorld(level, origin, size, false, List.of(Blocks.STRUCTURE_VOID));
			CompoundTag tag = t.save(new CompoundTag());
			Path dir = server.getWorldPath(LevelResource.GENERATED_DIR).resolve("gmodcraft").resolve("structures");
			Files.createDirectories(dir);
			NbtIo.writeCompressed(tag, dir.resolve(name + ".nbt"));
		} catch (Exception | LinkageError e) {
			GmodCraft.LOG.warn("GmodCraft: structure export {}: .nbt not saved ({})", name, e.toString());
		}
	}

	/** Once per server tick: streams the pending texts (oldest first). */
	public static void tick() {
		int budget = BATCHES_PER_TICK;
		int per = Proto.STRUCT_CHUNK_BYTES * Proto.STRUCT_MAX_CHUNKS;
		while (budget > 0 && !STREAMS.isEmpty()) {
			Stream s = STREAMS.peek();
			if (++s.ticks > GIVE_UP_TICKS) {
				GmodCraft.LOG.warn("GmodCraft: structure export {} dropped: the link didn't take it", Integer.toUnsignedString(s.requestId));
				STREAMS.poll();
				continue;
			}
			while (budget > 0 && s.offset < s.text.length) {
				int len = Math.min(per, s.text.length - s.offset);
				if (!ServerLink.INSTANCE.pushStructData(s.requestId, s.text, s.offset, len, RESERVE)) {
					return; // ring busy or no link: next tick
				}
				s.offset += len;
				budget--;
			}
			if (s.offset >= s.text.length) {
				STREAMS.poll();
			}
		}
	}

	/** Pending texts (for tests / the log). */
	static int pending() {
		return STREAMS.size();
	}
}
