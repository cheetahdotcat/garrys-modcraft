package dev.gmodcraft.weapon;

import static dev.gmodcraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * GMod weapon icons (protocol v18 kColWeaponIcon, client link): decoded and validated here (no
 * Minecraft classes: unit-tested), kept per class hash for the client's GUI renderer, which uploads
 * new ones as textures. Bounded: at most {@link #MAX_ICONS} classes; a newer icon of a class replaces
 * the old one.
 */
public final class WeaponIcons {
	/** One icon: RGBA8, straight alpha, top row first. */
	public record Icon(int hash, int w, int h, byte[] rgba) {
	}

	public static final int MAX_ICONS = 512;
	private static final Map<Integer, Icon> ICONS = new ConcurrentHashMap<>();
	// Hashes whose icon arrived or changed since the renderer last looked: a set, so a host that
	// sends one class over and over keeps one entry (bounded by MAX_ICONS like ICONS).
	private static final Set<Integer> DIRTY = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger REJECTED = new AtomicInteger();

	private WeaponIcons() {
	}

	/**
	 * Decodes a kColWeaponIcon payload at {@code payload} ({@code payloadBytes} long), or null when
	 * it breaks the protocol's bounds: hash 0, w / h outside 1..kWeaponIconMaxSide, another format, or a
	 * payload size other than exactly header + w * h * 4.
	 */
	public static @Nullable Icon decode(MemorySegment s, long payload, long payloadBytes) {
		if (payloadBytes < WEAPON_ICON_HEADER_BYTES || payloadBytes > WEAPON_ICON_MAX_BYTES || payload < 0
			|| payload + payloadBytes > s.byteSize()) {
			return null;
		}
		int hash = s.get(JAVA_INT_UNALIGNED, payload + WI_HASH);
		int w = Short.toUnsignedInt(s.get(JAVA_SHORT_UNALIGNED, payload + WI_W));
		int h = Short.toUnsignedInt(s.get(JAVA_SHORT_UNALIGNED, payload + WI_H));
		int format = s.get(JAVA_INT_UNALIGNED, payload + WI_FORMAT);
		if (hash == 0 || w < 1 || h < 1 || w > WEAPON_ICON_MAX_SIDE || h > WEAPON_ICON_MAX_SIDE || format != ICON_RGBA8
			|| payloadBytes != WEAPON_ICON_HEADER_BYTES + 4L * w * h) {
			return null;
		}
		byte[] rgba = s.asSlice(payload + WEAPON_ICON_HEADER_BYTES, 4L * w * h).toArray(JAVA_BYTE);
		return new Icon(hash, w, h, rgba);
	}

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gmodcraft");
	private static final java.util.concurrent.atomic.AtomicLong WARNED_NS = new java.util.concurrent.atomic.AtomicLong();
	private static final long WARN_EVERY_NS = 10_000_000_000L;

	/** Counts a dropped icon; warns at most every 10 s (with the running total). */
	private static void reject(String why) {
		int n = REJECTED.incrementAndGet();
		long now = System.nanoTime();
		long last = WARNED_NS.get();
		if ((last == 0 || now - last > WARN_EVERY_NS) && WARNED_NS.compareAndSet(last, now)) {
			LOG.warn("GmodCraft: dropped a GMod weapon icon: {} ({} dropped so far)", why, n);
		}
	}

	/** Collision consumer thread: one kColWeaponIcon message. */
	public static void accept(MemorySegment s, long payload, long payloadBytes) {
		Icon icon = decode(s, payload, payloadBytes);
		if (icon == null) {
			reject("malformed (bounds)");
			return;
		}
		put(icon);
	}

	/** Stores an icon (bounded); true when it was taken. */
	public static boolean put(Icon icon) {
		if (!ICONS.containsKey(icon.hash()) && ICONS.size() >= MAX_ICONS) {
			reject("over the " + MAX_ICONS + "-class cap");
			return false;
		}
		ICONS.put(icon.hash(), icon);
		DIRTY.add(icon.hash());
		return true;
	}

	/** The icon of a class hash, if GMod sent one. */
	public static @Nullable Icon get(int hash) {
		return ICONS.get(hash);
	}

	/** Render thread: the next icon that arrived (or was replaced) since the last call, or null. */
	public static @Nullable Icon pollFresh() {
		Iterator<Integer> it = DIRTY.iterator();
		while (it.hasNext()) {
			Integer hash = it.next();
			it.remove();
			Icon icon = ICONS.get(hash);
			if (icon != null) {
				return icon;
			}
		}
		return null;
	}

	/** Hashes waiting for the renderer (tests, diagnostics). */
	public static int dirtyCount() {
		return DIRTY.size();
	}

	public static int count() {
		return ICONS.size();
	}

	public static int rejected() {
		return REJECTED.get();
	}

	/** A new host session: its icons come again. */
	public static void clear() {
		ICONS.clear();
		DIRTY.clear();
	}
}
