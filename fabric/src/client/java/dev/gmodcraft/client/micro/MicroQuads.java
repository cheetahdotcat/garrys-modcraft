package dev.gmodcraft.client.micro;

import dev.gmodcraft.micro.MicroGeom;
import dev.gmodcraft.micro.MicroPart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Baked quads of a microblock's parts, one model part per material (so a caller can tint and light
 * each material as that block). Each part box face copies the quads of the material's full-cube
 * model on that side (sprite, tint index, layer, overlays like the grass side's), cut to the box: the
 * UVs follow the material quad's own mapping, so rotated textures stay rotated. Faces on the cell's
 * boundary cull like the material's; inner faces covered by an opaque neighbouring part (or, for glass-like
 * materials that cull themselves, by a part of the same material) are left out. Each quad keeps its
 * material quad's chunk layer, so glass is cutout and stained glass / ice translucent, in Minecraft and
 * in the GMod export alike.
 * Cached per part list; the cache is dropped when the models reload.
 */
public final class MicroQuads {
	private static final int CACHE = 2048;
	private static final Direction[] DIRS = Direction.values();
	private static final Map<List<MicroPart>, List<Group>> CACHE_MAP = new LinkedHashMap<>(256, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<List<MicroPart>, List<Group>> eldest) {
			return size() > CACHE;
		}
	};
	private static Object cachedFor;

	/** The quads of all parts of one material. */
	public record Group(BlockState material, Part part) {
	}

	/** A {@link BlockStateModelPart} holding generated quads: index 0..5 = Direction (cull face), 6 = no cull face. */
	public record Part(List<BakedQuad>[] quads, Material.Baked particle, int flags, boolean ao) implements BlockStateModelPart {
		@Override
		public List<BakedQuad> getQuads(Direction dir) {
			return this.quads[dir == null ? 6 : dir.ordinal()];
		}

		@Override
		public boolean useAmbientOcclusion() {
			return this.ao;
		}

		@Override
		public Material.Baked particleMaterial() {
			return this.particle;
		}

		@Override
		public int materialFlags() {
			return this.flags;
		}
	}

	private MicroQuads() {
	}

	private static Object modelSet() {
		return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
	}

	public static BlockStateModel modelOf(BlockState state) {
		return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
	}

	public static synchronized List<Group> groups(List<MicroPart> parts) {
		Object set = modelSet();
		if (set != cachedFor) {
			CACHE_MAP.clear();
			cachedFor = set;
		}
		List<Group> g = CACHE_MAP.get(parts);
		if (g == null) {
			g = build(parts);
			CACHE_MAP.put(parts, g);
		}
		return g;
	}

	@SuppressWarnings("unchecked")
	private static List<Group> build(List<MicroPart> parts) {
		List<int[]> opaque = new ArrayList<>();
		Map<BlockState, List<int[]>> sameCovers = new LinkedHashMap<>();
		for (MicroPart p : parts) {
			if (p.material().canOcclude()) {
				opaque.add(p.boxArray());
			}
		}
		// glass, stained glass, ice: like the full blocks, faces between two parts of the same material are
		// left out (else the inner faces show through translucent parts)
		for (MicroPart p : parts) {
			if (!p.material().canOcclude()) {
				sameCovers.computeIfAbsent(p.material(), m -> new ArrayList<>(opaque)).add(p.boxArray());
			}
		}
		Map<BlockState, List<BakedQuad>[]> byMaterial = new LinkedHashMap<>();
		Map<BlockState, List<BlockStateModelPart>> templates = new LinkedHashMap<>();
		for (MicroPart p : parts) {
			List<BlockStateModelPart> tpl = templates.computeIfAbsent(p.material(), m -> {
				List<BlockStateModelPart> out = new ArrayList<>();
				modelOf(m).collectParts(RandomSource.create(42L), out);
				return out;
			});
			List<BakedQuad>[] lists = byMaterial.computeIfAbsent(p.material(), m -> {
				List<BakedQuad>[] l = new List[7];
				for (int i = 0; i < 7; i++) {
					l[i] = new ArrayList<>();
				}
				return l;
			});
			int[] b = p.boxArray();
			for (Direction d : DIRS) {
				int axis = d.getAxis().ordinal();
				boolean positive = d.getAxisDirection() == Direction.AxisDirection.POSITIVE;
				List<int[]> covers = sameCovers.containsKey(p.material()) && p.material().skipRendering(p.material(), d)
					? sameCovers.get(p.material()) : opaque;
				if (MicroGeom.faceCovered(b, axis, positive, covers)) {
					continue;
				}
				boolean boundary = positive ? b[axis + 3] == MicroGeom.CELL : b[axis] == 0;
				List<BakedQuad> sink = lists[boundary ? d.ordinal() : 6];
				for (BlockStateModelPart t : tpl) {
					for (BakedQuad q : t.getQuads(d)) {
						BakedQuad cut = cut(q, d, b);
						if (cut != null) {
							sink.add(cut);
						}
					}
				}
			}
		}
		List<Group> out = new ArrayList<>(byMaterial.size());
		for (Map.Entry<BlockState, List<BakedQuad>[]> e : byMaterial.entrySet()) {
			BlockStateModel model = modelOf(e.getKey());
			List<BakedQuad>[] lists = e.getValue();
			for (int i = 0; i < 7; i++) {
				lists[i] = List.copyOf(lists[i]);
			}
			boolean ao = true;
			for (BlockStateModelPart t : templates.get(e.getKey())) {
				ao &= t.useAmbientOcclusion();
			}
			out.add(new Group(e.getKey(), new Part(lists, model.particleMaterial(), model.materialFlags(), ao)));
		}
		return List.copyOf(out);
	}

	/** In-face texture coordinates (0..1) Minecraft gives a point on a face by default (FaceBakery's default UVs). */
	private static float faceU(Direction d, float x, float y, float z) {
		return switch (d) {
			case DOWN, UP, SOUTH -> x;
			case NORTH -> 1 - x;
			case WEST -> z;
			case EAST -> 1 - z;
		};
	}

	private static float faceV(Direction d, float x, float y, float z) {
		return switch (d) {
			case DOWN -> 1 - z;
			case UP -> z;
			default -> 1 - y;
		};
	}

	/**
	 * The template quad (a material face on side d) cut to box b: the template's mapping from default
	 * face UVs to atlas UVs (affine, fitted on three corners) applied to the box face's corners.
	 */
	static BakedQuad cut(BakedQuad t, Direction d, int[] b) {
		float[] du = new float[3], dv = new float[3], au = new float[3], av = new float[3];
		for (int k = 0; k < 3; k++) {
			Vector3fc p = t.position(k);
			du[k] = faceU(d, p.x(), p.y(), p.z());
			dv[k] = faceV(d, p.x(), p.y(), p.z());
			long uv = t.packedUV(k);
			au[k] = UVPair.unpackU(uv);
			av[k] = UVPair.unpackV(uv);
		}
		float a11 = du[1] - du[0], a12 = du[2] - du[0], a21 = dv[1] - dv[0], a22 = dv[2] - dv[0];
		float det = a11 * a22 - a12 * a21;
		TextureAtlasSprite sprite = t.materialInfo().sprite();
		// atlas = base + M * (default - default0); M solved from the template's corners 1 and 2
		float mUu, mUv, mVu, mVv;
		if (Math.abs(det) < 1e-6F) {
			// degenerate template (not a face-filling quad): plain sprite mapping
			mUu = sprite.getU1() - sprite.getU0();
			mUv = 0;
			mVu = 0;
			mVv = sprite.getV1() - sprite.getV0();
			du[0] = 0;
			dv[0] = 0;
			au[0] = sprite.getU0();
			av[0] = sprite.getV0();
		} else {
			float bu1 = au[1] - au[0], bu2 = au[2] - au[0], bv1 = av[1] - av[0], bv2 = av[2] - av[0];
			// [mUu mUv] * [a11 a12; a21 a22] = [bu1 bu2]
			mUu = (bu1 * a22 - bu2 * a21) / det;
			mUv = (bu2 * a11 - bu1 * a12) / det;
			mVu = (bv1 * a22 - bv2 * a21) / det;
			mVv = (bv2 * a11 - bv1 * a12) / det;
		}
		// corners: each template corner moved to the box's near/far side per axis, so the vertex order
		// (winding) is the material quad's own
		float s = 1.0F / MicroGeom.CELL;
		float[][] c = new float[4][3];
		for (int k = 0; k < 4; k++) {
			Vector3fc p = t.position(k);
			float[] pc = { p.x(), p.y(), p.z() };
			for (int a = 0; a < 3; a++) {
				c[k][a] = (pc[a] < 0.5F ? b[a] : b[a + 3]) * s;
			}
		}
		Vector3f[] pos = new Vector3f[4];
		long[] uvs = new long[4];
		for (int k = 0; k < 4; k++) {
			pos[k] = new Vector3f(c[k][0], c[k][1], c[k][2]);
			float fu = faceU(d, c[k][0], c[k][1], c[k][2]) - du[0];
			float fv = faceV(d, c[k][0], c[k][1], c[k][2]) - dv[0];
			uvs[k] = UVPair.pack(au[0] + mUu * fu + mUv * fv, av[0] + mVu * fu + mVv * fv);
		}
		return new BakedQuad(pos[0], pos[1], pos[2], pos[3], uvs[0], uvs[1], uvs[2], uvs[3], d, t.materialInfo());
	}
}
