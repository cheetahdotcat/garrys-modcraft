package dev.gmodcraft.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * What a box held before a demo was placed, cell by cell, so clearing restores it exactly. Generic
 * over the cell value (in Minecraft: block state + block entity NBT), so it is unit-tested with
 * plain strings.
 */
public final class Snapshot<S> {
	/** Read/write access to the world's cells. */
	public interface Grid<S> {
		S get(int x, int y, int z);

		void set(int x, int y, int z, S value);
	}

	public record Cell<S>(int x, int y, int z, S value) {
	}

	private final List<Cell<S>> cells;

	public Snapshot(List<Cell<S>> cells) {
		this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
	}

	public List<Cell<S>> cells() {
		return this.cells;
	}

	/** Every cell of {@code box}, in x, y, z order. */
	public static <S> Snapshot<S> capture(Grid<S> grid, DemoShape.Box box) {
		List<Cell<S>> out = new ArrayList<>(box.volume());
		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					out.add(new Cell<>(x, y, z, grid.get(x, y, z)));
				}
			}
		}
		return new Snapshot<>(out);
	}

	/** Writes every cell back. */
	public void restore(Grid<S> grid) {
		for (Cell<S> c : this.cells) {
			grid.set(c.x(), c.y(), c.z(), c.value());
		}
	}

	/** The first cell of {@code box} that isn't {@code empty} (null: the whole box is empty). */
	public static <S> int @org.jspecify.annotations.Nullable [] firstOccupied(Grid<S> grid, DemoShape.Box box, java.util.function.Predicate<S> empty) {
		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					if (!empty.test(grid.get(x, y, z))) {
						return new int[] { x, y, z };
					}
				}
			}
		}
		return null;
	}

	/** SHA-256 (first 16 bytes, hex) of every cell of {@code box} in x, y, z order, each as text. */
	public static <S> String hash(Grid<S> grid, DemoShape.Box box, Function<S, String> text) {
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					md.update((x + "," + y + "," + z + "=" + text.apply(grid.get(x, y, z)) + "\n").getBytes(StandardCharsets.UTF_8));
				}
			}
		}
		byte[] d = md.digest();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 16; i++) {
			sb.append(String.format("%02x", d[i]));
		}
		return sb.toString();
	}
}
