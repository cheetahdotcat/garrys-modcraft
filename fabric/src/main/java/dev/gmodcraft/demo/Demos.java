package dev.gmodcraft.demo;

import dev.gmodcraft.link.Proto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The demo builds (P7b, protocol v19 DemoKind), Minecraft side. Defined for yaw quarter 0: forward
 * is +z (south), y = 0 is the layer on the ground. No liquids, ever. The GMod parts of each demo
 * (props, a jeep, NPCs, wire entities) live in addon/.../shared/demos.lua, in the same frame.
 *
 * <p>Redstone clocks are torch inverters (self-starting: a torch is lit when placed): stone S at
 * (0,1,0) with a wall torch on its south face; the torch feeds repeater R1 east -> dust -> dust ->
 * repeater R2 west back into S (S powered -> torch off). Half period = torch 2 + 2 x 8 game ticks
 * = 18 ticks (~0.9 s), far from torch burnout (8 toggles in 60 ticks). R3 south of the torch takes
 * the clock out.
 */
public final class Demos {
	private static final String FLOOR = "minecraft:smooth_stone";
	private static final Map<String, DemoShape> BY_NAME = new LinkedHashMap<>();
	private static final Map<Integer, DemoShape> BY_KIND = new LinkedHashMap<>();

	private Demos() {
	}

	private static final class Builder {
		final int kind;
		final String name;
		final Map<String, DemoShape.Block> blocks = new LinkedHashMap<>();
		final List<DemoShape.Spawn> spawns = new ArrayList<>();
		final List<DemoShape.Fill> fills = new ArrayList<>();
		DemoShape.@Nullable Box extra;

		Builder(int kind, String name) {
			this.kind = kind;
			this.name = name;
		}

		Builder set(int x, int y, int z, String state) {
			this.blocks.put(x + "," + y + "," + z, new DemoShape.Block(x, y, z, state)); // later wins
			return this;
		}

		Builder fill(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						set(x, y, z, state);
					}
				}
			}
			return this;
		}

		/** The torch-inverter clock (see the class comment) with its output repeater R3 at (0,1,2). */
		Builder clock(int r3Delay) {
			set(0, 1, 0, "minecraft:stone");
			set(0, 1, 1, "minecraft:redstone_wall_torch[facing=south,lit=true]");
			set(1, 1, 1, "minecraft:repeater[facing=west,delay=4,locked=false,powered=false]");
			set(2, 1, 1, "minecraft:redstone_wire[east=none,north=side,south=none,west=side,power=0]");
			set(2, 1, 0, "minecraft:redstone_wire[east=none,north=none,south=side,west=side,power=0]");
			set(1, 1, 0, "minecraft:repeater[facing=east,delay=4,locked=false,powered=false]");
			set(0, 1, 2, "minecraft:repeater[facing=north,delay=" + r3Delay + ",locked=false,powered=false]");
			return this;
		}

		DemoShape build() {
			return new DemoShape(this.kind, this.name, new ArrayList<>(this.blocks.values()), this.spawns, this.fills, this.extra);
		}
	}

	static {
		// Rail loop: a 7 x 7 ring on a floor, three powered rails (redstone blocks under them), a
		// detector rail with a lamp beside it, two minecarts already moving the same way round.
		Builder rails = new Builder(Proto.DEMO_RAILS, "rails").fill(-4, 0, 0, 4, 0, 8, FLOOR);
		for (int z = 3; z <= 5; z++) {
			rails.set(3, 0, z, "minecraft:redstone_block");
		}
		for (int x = -2; x <= 2; x++) {
			rails.set(x, 1, 1, "minecraft:rail[shape=east_west]").set(x, 1, 7, "minecraft:rail[shape=east_west]");
		}
		for (int z = 2; z <= 6; z++) {
			rails.set(-3, 1, z, "minecraft:rail[shape=north_south]");
			rails.set(3, 1, z, z >= 3 && z <= 5 ? "minecraft:powered_rail[shape=north_south,powered=true]" : "minecraft:rail[shape=north_south]");
		}
		rails.set(-3, 1, 1, "minecraft:rail[shape=south_east]").set(3, 1, 1, "minecraft:rail[shape=south_west]");
		rails.set(3, 1, 7, "minecraft:rail[shape=north_west]").set(-3, 1, 7, "minecraft:rail[shape=north_east]");
		rails.set(-3, 1, 4, "minecraft:detector_rail[shape=north_south,powered=false]");
		rails.set(-4, 1, 4, "minecraft:redstone_lamp[lit=false]");
		// clockwise seen from above in quarter 0: along z=1 eastwards, down x=3 southwards, ...
		rails.spawns.add(new DemoShape.Spawn("minecraft:minecart", 0.5, 1.0625, 1.5, 0.4, 0.0));
		rails.spawns.add(new DemoShape.Spawn("minecraft:minecart", 0.5, 1.0625, 7.5, -0.4, 0.0));
		register(rails.build());

		// Redstone <-> Wiremod: a clock into the bridge's north face, a lever on its west face, a
		// lamp on its east face (lit by the wire side: GMod links a Wire Button to the bridge's East
		// input, and indicators to its North (clock) and West (lever) outputs).
		Builder red = new Builder(Proto.DEMO_REDSTONE, "redstone").fill(-1, 0, 0, 2, 0, 3, FLOOR).clock(1);
		red.set(0, 1, 3, "gmodcraft:redstone_bridge");
		red.set(-1, 1, 3, "minecraft:lever[face=wall,facing=west,powered=false]");
		red.set(1, 1, 3, "minecraft:redstone_lamp[lit=false]");
		register(red.build());

		// Projectile range: the clock fires a dispenser of arrows forward (GMod: crates, a barrel and
		// a glass plate stand 8-12 blocks ahead).
		Builder range = new Builder(Proto.DEMO_RANGE, "range").fill(-1, 0, 0, 2, 0, 3, FLOOR).clock(1);
		range.set(0, 1, 3, "minecraft:dispenser[facing=south,triggered=false]");
		for (int i = 0; i < 9; i++) {
			range.fills.add(new DemoShape.Fill(0, 1, 3, "minecraft:arrow", 64));
		}
		register(range.build());

		// Vehicle ramp: up 4 blocks in half-block steps, a 4-block gap, a landing ramp back down
		// (GMod: a jeep 5 blocks before it, driving on the blocks through the 0.4 physics world; the
		// slabs reach GMod as half-height boxes, v26 kBlkShapes).
		Builder ramp = new Builder(Proto.DEMO_RAMP, "ramp");
		int[] up = { 1, 2, 3, 4, 5, 6, 7, 8 };            // z = 2..9, height in half blocks
		int[] down = { 6, 5, 4, 3, 2, 1 };                 // z = 14..19
		for (int i = 0; i < up.length; i++) {
			column(ramp, 2 + i, up[i]);
		}
		for (int i = 0; i < down.length; i++) {
			column(ramp, 14 + i, down[i]);
		}
		ramp.extra = new DemoShape.Box(-2, 0, 10, 2, 3, 13); // the gap must be free too
		register(ramp.build());

		// NPC arena: a 13 x 13 ring of stone bricks, 3 high (GMod: citizens vs a combine soldier inside).
		Builder arena = new Builder(Proto.DEMO_ARENA, "arena");
		for (int y = 0; y <= 2; y++) {
			for (int i = -6; i <= 6; i++) {
				arena.set(i, y, 1, "minecraft:stone_bricks").set(i, y, 13, "minecraft:stone_bricks");
				arena.set(-6, y, i + 7, "minecraft:stone_bricks").set(6, y, i + 7, "minecraft:stone_bricks");
			}
		}
		register(arena.build());

		// Dig wall: a 5 x 5 marker frame in the air layer in front of a GMod wall (aim at the wall):
		// dig into the wall inside the frame to test hole walls.
		Builder dig = new Builder(Proto.DEMO_DIG_WALL, "digwall");
		for (int i = -2; i <= 2; i++) {
			dig.set(i, -2, 0, "minecraft:red_wool").set(i, 2, 0, "minecraft:red_wool");
			dig.set(-2, i, 0, "minecraft:red_wool").set(2, i, 0, "minecraft:red_wool");
		}
		dig.set(-2, -2, 0, "minecraft:glowstone").set(2, -2, 0, "minecraft:glowstone");
		dig.set(-2, 2, 0, "minecraft:glowstone").set(2, 2, 0, "minecraft:glowstone");
		register(dig.build());
	}

	private static void column(Builder b, int z, int halves) {
		for (int x = -2; x <= 2; x++) {
			for (int y = 0; y < halves / 2; y++) {
				b.set(x, y, z, FLOOR);
			}
			if (halves % 2 == 1) {
				b.set(x, halves / 2, z, "minecraft:smooth_stone_slab[type=bottom,waterlogged=false]");
			}
		}
	}

	private static void register(DemoShape s) {
		if (!s.fitsLimit()) {
			throw new IllegalStateException("demo " + s.name + " is larger than the demo size limit: " + s.box);
		}
		for (DemoShape.Block b : s.blocks) {
			String st = b.state();
			if (st.contains("water") && !st.contains("waterlogged=false") || st.contains("lava")) {
				throw new IllegalStateException("demo " + s.name + " has a liquid: " + st);
			}
		}
		BY_NAME.put(s.name, s);
		BY_KIND.put(s.kind, s);
	}

	public static @Nullable DemoShape byName(String name) {
		return BY_NAME.get(name);
	}

	public static @Nullable DemoShape byKind(int kind) {
		return BY_KIND.get(kind);
	}

	public static List<DemoShape> all() {
		return Collections.unmodifiableList(new ArrayList<>(BY_NAME.values()));
	}
}
