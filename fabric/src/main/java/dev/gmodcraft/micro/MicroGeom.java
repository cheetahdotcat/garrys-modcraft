package dev.gmodcraft.micro;

import java.util.List;

/**
 * Microblock geometry in eighths of a block, pure (no MC classes), so tests run without a game.
 *
 * <p>A box is 6 ints {x0, y0, z0, x1, y1, z1}, each 0..8, min &lt; max. Packed into one int, 4 bits
 * per value in that order (x0 in the low bits).
 *
 * <p>Shapes ({@link Shape}) of size s (1, 2 or 4 eighths):
 * <ul>
 * <li>FACE: 8 x 8 x s against a face (s = 1 cover, 2 panel, 4 slab);</li>
 * <li>POST: s x s x 8 along the clicked face's axis;</li>
 * <li>CORNER: s x s x s.</li>
 * </ul>
 *
 * <p>Placement ({@link #snap}): along the clicked face's axis the part starts at the hit, rounded to
 * the nearest eighth, and grows {@code len} eighths in the face's direction; if that leaves the cell
 * it goes into the neighbouring cell instead, flush against the shared face. So a cover on a full
 * block lands in the cell in front of it, and a slab on top of a bottom slab fills the same cell's
 * top half. Across the face a FACE part covers the whole face; posts and corners snap to a grid of
 * their own size under the hit point.
 */
public final class MicroGeom {
	public static final int CELL = 8;

	public enum Shape {
		FACE, POST, CORNER;

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		public static Shape byId(String id) {
			for (Shape s : values()) {
				if (s.id().equals(id)) {
					return s;
				}
			}
			return null;
		}
	}

	/** Where a part goes: {@code neighbour} = the cell next to the clicked one (across the clicked face). */
	public record Placement(boolean neighbour, int[] box) {
	}

	private MicroGeom() {
	}

	public static boolean validSize(int size) {
		return size == 1 || size == 2 || size == 4;
	}

	public static int pack(int[] b) {
		return b[0] | b[1] << 4 | b[2] << 8 | b[3] << 12 | b[4] << 16 | b[5] << 20;
	}

	public static int[] unpack(int p) {
		return new int[] { p & 0xF, p >> 4 & 0xF, p >> 8 & 0xF, p >> 12 & 0xF, p >> 16 & 0xF, p >> 20 & 0xF };
	}

	public static boolean valid(int[] b) {
		if (b == null || b.length != 6) {
			return false;
		}
		for (int a = 0; a < 3; a++) {
			if (b[a] < 0 || b[a + 3] > CELL || b[a] >= b[a + 3]) {
				return false;
			}
		}
		return true;
	}

	public static boolean overlaps(int[] a, int[] b) {
		for (int i = 0; i < 3; i++) {
			if (a[i] >= b[i + 3] || b[i] >= a[i + 3]) {
				return false;
			}
		}
		return true;
	}

	/** Whether {@code box} is free of every box in {@code boxes}. */
	public static boolean fits(List<int[]> boxes, int[] box) {
		for (int[] b : boxes) {
			if (overlaps(b, box)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether box b's face on side (axis, positive) is hidden: every eighth-cell just outside it is
	 * inside one of {@code opaque}. A face on the cell's boundary never is (the neighbour cell's
	 * culling handles those).
	 */
	public static boolean faceCovered(int[] b, int axis, boolean positive, List<int[]> opaque) {
		int plane = positive ? b[axis + 3] : b[axis];
		int layer = positive ? plane : plane - 1;
		if (layer < 0 || layer >= CELL) {
			return false;
		}
		int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
		int[] v = new int[3];
		v[axis] = layer;
		for (int i = b[a1]; i < b[a1 + 3]; i++) {
			for (int j = b[a2]; j < b[a2 + 3]; j++) {
				v[a1] = i;
				v[a2] = j;
				boolean in = false;
				for (int[] o : opaque) {
					if (o[0] <= v[0] && v[0] < o[3] && o[1] <= v[1] && v[1] < o[4] && o[2] <= v[2] && v[2] < o[5]) {
						in = true;
						break;
					}
				}
				if (!in) {
					return false;
				}
			}
		}
		return true;
	}

	public static int volume(int[] b) {
		return (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
	}

	/** The shape a box was cut as and its size, or null when it isn't one of ours: {shape, size}. */
	public static Object[] shapeOf(int[] b) {
		int[] d = { b[3] - b[0], b[4] - b[1], b[5] - b[2] };
		int full = 0, min = CELL, max = 0;
		for (int v : d) {
			if (v == CELL) {
				full++;
			}
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		if (full == 2 && validSize(min)) {
			return new Object[] { Shape.FACE, min };
		}
		if (full == 1 && validSize(min) && countOf(d, min) == 2) {
			return new Object[] { Shape.POST, min };
		}
		if (full == 0 && min == max && validSize(min)) {
			return new Object[] { Shape.CORNER, min };
		}
		return null;
	}

	private static int countOf(int[] d, int v) {
		int n = 0;
		for (int x : d) {
			if (x == v) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Where a part of {@code shape}/{@code size} goes when the player clicks a face.
	 *
	 * @param axis the clicked face's axis (0 x, 1 y, 2 z)
	 * @param positive the clicked face points along +axis
	 * @param hit the hit point in the clicked cell, 0..1 per axis
	 */
	public static Placement snap(Shape shape, int size, int axis, boolean positive, double[] hit) {
		if (!validSize(size)) {
			throw new IllegalArgumentException("size " + size);
		}
		int len = shape == Shape.POST ? CELL : size;
		int at = clamp((int) Math.round(hit[axis] * CELL), 0, CELL);
		int[] box = new int[6];
		boolean neighbour;
		if (positive) {
			neighbour = at + len > CELL;
			box[axis] = neighbour ? 0 : at;
		} else {
			neighbour = at - len < 0;
			box[axis] = neighbour ? CELL - len : at - len;
		}
		box[axis + 3] = box[axis] + len;
		for (int a = 0; a < 3; a++) {
			if (a == axis) {
				continue;
			}
			if (shape == Shape.FACE) {
				box[a] = 0;
				box[a + 3] = CELL;
			} else {
				int g = clamp((int) Math.floor(hit[a] * CELL / size) * size, 0, CELL - size);
				box[a] = g;
				box[a + 3] = g + size;
			}
		}
		return new Placement(neighbour, box);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/**
	 * What the saw makes of a piece. {@code across}: the saw sits left of the piece (cuts a face into
	 * posts, a post into corners); otherwise it sits above it (halves a face's thickness). A full block
	 * is size 8 FACE here. Returns {shape, size, count} or null. Volume is kept.
	 */
	public static int[] saw(Shape shape, int size, boolean across) {
		if (shape == Shape.FACE && size == CELL) {
			return new int[] { Shape.FACE.ordinal(), 4, 2 };
		}
		if (!validSize(size)) {
			return null;
		}
		if (!across) {
			return shape == Shape.FACE && size > 1 ? new int[] { Shape.FACE.ordinal(), size / 2, 2 } : null;
		}
		return switch (shape) {
			case FACE -> new int[] { Shape.POST.ordinal(), size, CELL / size };
			case POST -> new int[] { Shape.CORNER.ordinal(), size, CELL / size };
			case CORNER -> null;
		};
	}
}
