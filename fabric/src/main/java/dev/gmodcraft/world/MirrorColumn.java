package dev.gmodcraft.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The hull world type's mirror blocks of one chunk: per section, bit x + 16z + 256y is set for a
 * block the generator (or the late fill) made from the map's hull file and nothing has changed since.
 * GMod draws the map there itself, so such a block is not sent to GMod, and it has no Minecraft
 * collision (the host's collision covers that space). Immutable: a change makes a new column (so the
 * chunk attachment syncs). Pure (unit-tested without a game).
 */
public record MirrorColumn(List<Section> sections) {
	/** Has this game seen any mirror block (made, loaded or synced)? Until then every lookup is skipped. */
	public static volatile boolean seen;

	public MirrorColumn {
		if (!sections.isEmpty() && !seen) {
			seen = true;
		}
	}

	public static final MirrorColumn EMPTY = new MirrorColumn(List.of());

	/** One section's bits (64 longs). */
	public record Section(int sectionY, long[] bits) {
		static final Codec<Section> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("y").forGetter(Section::sectionY),
			Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream).fieldOf("bits").forGetter(Section::bits)
		).apply(i, Section::new));
	}

	public static final Codec<MirrorColumn> CODEC = Section.CODEC.listOf().xmap(MirrorColumn::new, MirrorColumn::sections);

	public static final StreamCodec<ByteBuf, MirrorColumn> STREAM_CODEC = new StreamCodec<>() {
		@Override
		public MirrorColumn decode(ByteBuf buf) {
			int n = ByteBufCodecs.VAR_INT.decode(buf);
			List<Section> sections = new ArrayList<>(Math.min(n, 256));
			for (int i = 0; i < n; i++) {
				int y = ByteBufCodecs.VAR_INT.decode(buf);
				long[] bits = new long[64];
				for (int k = 0; k < 64; k++) {
					bits[k] = buf.readLong();
				}
				sections.add(new Section(y, bits));
			}
			return new MirrorColumn(List.copyOf(sections));
		}

		@Override
		public void encode(ByteBuf buf, MirrorColumn column) {
			ByteBufCodecs.VAR_INT.encode(buf, column.sections.size());
			for (Section s : column.sections) {
				ByteBufCodecs.VAR_INT.encode(buf, s.sectionY);
				for (int k = 0; k < 64; k++) {
					buf.writeLong(s.bits.length == 64 ? s.bits[k] : 0L);
				}
			}
		}
	};

	static int bit(int x, int y, int z) {
		return (x & 15) + 16 * (z & 15) + 256 * (y & 15);
	}

	/** The bits of one section, or null when it has none. */
	public long @org.jspecify.annotations.Nullable [] bits(int sectionY) {
		for (Section s : this.sections) {
			if (s.sectionY == sectionY && s.bits.length == 64) {
				return s.bits;
			}
		}
		return null;
	}

	public boolean isMirror(int x, int y, int z) {
		long[] bits = bits(y >> 4);
		int bit = bit(x, y, z);
		return bits != null && ((bits[bit >> 6] >>> (bit & 63)) & 1L) != 0;
	}

	/** This column without block (x, y, z) (a section left empty is dropped). */
	public MirrorColumn without(int x, int y, int z) {
		int sy = y >> 4, bit = bit(x, y, z);
		List<Section> out = new ArrayList<>(this.sections.size());
		for (Section s : this.sections) {
			if (s.sectionY == sy && s.bits.length == 64) {
				long[] bits = s.bits.clone();
				bits[bit >> 6] &= ~(1L << (bit & 63));
				if (Arrays.stream(bits).anyMatch(b -> b != 0)) {
					out.add(new Section(sy, bits));
				}
			} else {
				out.add(s);
			}
		}
		return new MirrorColumn(List.copyOf(out));
	}

	/** This column with the bits of {@code add} (by section y, each 64 longs) set too. */
	public MirrorColumn with(java.util.Map<Integer, long[]> add) {
		java.util.TreeMap<Integer, long[]> merged = new java.util.TreeMap<>();
		for (Section s : this.sections) {
			if (s.bits.length == 64) {
				merged.put(s.sectionY, s.bits.clone());
			}
		}
		for (var e : add.entrySet()) {
			long[] bits = merged.computeIfAbsent(e.getKey(), k -> new long[64]);
			for (int k = 0; k < 64; k++) {
				bits[k] |= e.getValue()[k];
			}
		}
		List<Section> out = new ArrayList<>(merged.size());
		for (var e : merged.entrySet()) {
			if (Arrays.stream(e.getValue()).anyMatch(b -> b != 0)) {
				out.add(new Section(e.getKey(), e.getValue()));
			}
		}
		return new MirrorColumn(List.copyOf(out));
	}

	/** Sets block (x, y, z)'s bit in a section bit array (for building a {@link #with} argument). */
	public static void set(java.util.Map<Integer, long[]> bySection, int x, int y, int z) {
		int bit = bit(x, y, z);
		bySection.computeIfAbsent(y >> 4, k -> new long[64])[bit >> 6] |= 1L << (bit & 63);
	}

	/** How many mirror blocks the column has. */
	public int count() {
		int n = 0;
		for (Section s : this.sections) {
			for (long b : s.bits) {
				n += Long.bitCount(b);
			}
		}
		return n;
	}
}
