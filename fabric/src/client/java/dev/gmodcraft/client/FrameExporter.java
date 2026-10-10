package dev.gmodcraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.link.ClientLink;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;

/**
 * Copies Minecraft's main render target (hand + HUD + screens on a transparent background) back
 * from the GPU and publishes it to GMod through the overlay triple buffer.
 *
 * <p>The copy is asynchronous: a frame is captured into one of a few staging buffers and shipped
 * once the GPU says the copy finished, typically a frame later.
 *
 * <p>Shipping writes the pixels in the host's final format (Proto.OV_BGRA | OV_SRGB_ENCODED): ToGL
 * keeps textures as BGRA8888 and blends the HUD pass in linear light, so every colour channel goes
 * through the sRGB encode curve (alpha untouched) and the bytes are swizzled to B, G, R, A. That
 * costs the host nothing; here it is one table lookup per channel while copying into shared
 * memory, spread over the common fork-join pool (fully transparent pixels, most of a HUD, are a
 * plain zero). A GPU pass before the readback would make it free; not done yet.
 */
public final class FrameExporter {
	private static final int STAGING = 3;
	private static final int FREE = 0;
	private static final int PENDING = 1;
	private static final int READY = 2;

	private static final Staging[] staging = new Staging[STAGING];
	private static long nextFrameId = 1;
	private static boolean loggedFormat;
	private static volatile boolean warmed;
	// Rows per conversion task.
	private static final int ROWS_PER_TASK = 64;
	// Conversion cost (render thread, wall time), for the log.
	private static long convertNs;
	private static int converted;

	private static final class Staging {
		GpuBuffer buffer;
		int width;
		int height;
		volatile int state = FREE;
		long frameId;
	}

	private FrameExporter() {
	}

	public static void capture(Minecraft minecraft) {
		shipReadyFrames();

		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture color = target.getColorTexture();
		if (color == null) {
			return;
		}
		int width = target.width;
		int height = target.height;
		if (width > Proto.MAX_OVERLAY_W || height > Proto.MAX_OVERLAY_H) {
			return;
		}
		if (!loggedFormat) {
			loggedFormat = true;
			GmodCraft.LOG.info("GmodCraft: overlay capture {}x{} format {}", width, height, color.getFormat());
		}

		Staging slot = null;
		for (Staging s : staging) {
			if (s != null && s.state == FREE) {
				slot = s;
				break;
			}
		}
		if (slot == null) {
			for (int i = 0; i < STAGING; i++) {
				if (staging[i] == null) {
					staging[i] = slot = new Staging();
					break;
				}
			}
		}
		if (slot == null) {
			return; // all staging buffers still in flight; skip this frame
		}

		long bytes = (long) width * height * 4L;
		if (slot.buffer == null || slot.width != width || slot.height != height) {
			if (slot.buffer != null) {
				slot.buffer.close();
			}
			slot.buffer = RenderSystem.getDevice().createBuffer(() -> "GmodCraft overlay readback", 9, bytes);
			slot.width = width;
			slot.height = height;
		}
		final Staging captured = slot;
		captured.state = PENDING;
		captured.frameId = nextFrameId++;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, captured.buffer, 0L, () -> captured.state = READY, 0);
	}

	/** Maps the newest finished readback and copies it into shared memory. */
	private static void shipReadyFrames() {
		Staging newest = null;
		for (Staging s : staging) {
			if (s != null && s.state == READY && (newest == null || s.frameId > newest.frameId)) {
				newest = s;
			}
		}
		if (newest == null) {
			return;
		}
		ClientLink.OverlayTarget target = ClientLink.INSTANCE.overlayTarget();
		if (target != null) {
			MemorySegment shm = target.session().seg();
			long bytes = (long) newest.width * newest.height * 4L;
			try (GpuBufferSlice.MappedView view = newest.buffer.map(true, false)) {
				MemorySegment src = MemorySegment.ofBuffer(view.data());
				long n = Math.min(bytes, src.byteSize());
				long t0 = System.nanoTime();
				convertInto(src, shm.asSlice(target.offset(), n), newest.width, (int) (n / 4L / newest.width));
				logConversion(System.nanoTime() - t0, newest.width, newest.height);
			}
			ClientLink.INSTANCE.publishOverlay(target, newest.width, newest.height, Proto.OV_BOTTOM_UP | Proto.OV_BGRA | Proto.OV_SRGB_ENCODED, newest.frameId);
		}
		// Anything older than what we just shipped is useless now. (See below for the conversion.)
		for (Staging s : staging) {
			if (s != null && s.state == READY && s.frameId <= newest.frameId) {
				s.state = FREE;
			}
		}
	}

	/**
	 * At link-up: one conversion of a window-sized synthetic frame through the same parallel path,
	 * off the render thread, so the first real frame doesn't pay for the LUT's class init, the
	 * common pool's thread start-up and the JIT. Once per game run.
	 */
	public static void warmUp(int width, int height) {
		if (warmed) {
			return;
		}
		warmed = true;
		int w = Math.max(1, Math.min(width, Proto.MAX_OVERLAY_W));
		int h = Math.max(1, Math.min(height, Proto.MAX_OVERLAY_H));
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			long t0 = System.nanoTime();
			// Native memory, like the real source (a mapped GPU buffer) and target (shared memory):
			// the JIT specialises the segment accesses by segment kind.
			try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofShared()) {
				MemorySegment src = arena.allocate((long) w * h * 4L, 4);
				MemorySegment dst = arena.allocate((long) w * h * 4L, 4);
				// Mixed pixels so convert() takes both its paths (transparent, and a table lookup).
				for (long i = 0; i < (long) w * h; i += 3) {
					src.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, i, 0x80402010 + (int) (i & 0xFF));
				}
				for (int pass = 0; pass < 5; pass++) {
					convertInto(src, dst, w, h);
				}
			}
			GmodCraft.LOG.info("GmodCraft: overlay conversion warmed up ({}x{}, 5 passes, {} pool threads): {} ms", w, h,
				java.util.concurrent.ForkJoinPool.commonPool().getPoolSize(), String.format("%.1f", (System.nanoTime() - t0) / 1e6));
		}).exceptionally(e -> {
			GmodCraft.LOG.warn("GmodCraft: overlay warm-up failed", e);
			return null;
		});
	}

	/** RGBA8 rows from the readback -> BGRA8, sRGB-encoded, into the overlay slot. */
	private static void convertInto(MemorySegment src, MemorySegment dst, int width, int rows) {
		int tasks = (rows + ROWS_PER_TASK - 1) / ROWS_PER_TASK;
		java.util.stream.IntStream.range(0, tasks).parallel().forEach(t -> {
			long from = (long) t * ROWS_PER_TASK * width;
			long to = Math.min((long) rows, (long) (t + 1) * ROWS_PER_TASK) * width;
			for (long i = from; i < to; i++) {
				dst.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, i,
					dev.gmodcraft.link.OverlayPixels.convert(src.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, i)));
			}
		});
	}

	/** The first few conversions, then the average over each 600 frames (~10 s at 60 fps). */
	private static void logConversion(long ns, int width, int height) {
		convertNs += ns;
		converted++;
		if (converted <= 3 || converted % 600 == 0) {
			GmodCraft.LOG.info("GmodCraft: overlay BGRA/sRGB conversion {}x{}: {} ms (average {} ms over {} frames)", width, height,
				String.format("%.2f", ns / 1e6), String.format("%.2f", convertNs / 1e6 / converted), converted);
		}
		if (converted % 600 == 0) {
			convertNs = 0;
			converted = 3; // keep logging every 600, not the first three again
		}
	}
}
