package dev.gmodcraft.world;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.ServerHost;
import dev.gmodcraft.link.ServerLink;
import dev.gmodcraft.slot.SlotJobs;
import dev.gmodcraft.wire.Bridges;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Physics blocks (protocol v44), the Minecraft half.
 * <ul>
 * <li>Falling blocks and primed TNT are listed in McEntities; GMod simulates them while airborne
 * (HeldMcEntities, kHeldPhysics) and gives a falling block back when it rests, which then lands the
 * Minecraft way. On the host's ground or a GMod prop it lands as a block too ({@link #hostHolds}),
 * where vanilla would drop it as an item (nothing Minecraft knows is under it).</li>
 * <li>kHostEvPullBlock: a gravgun pull detaches a block as a falling block.</li>
 * <li>kHostEvBlast: a GMod explosion breaks blocks like a Minecraft one of that power (blocks only).</li>
 * </ul>
 */
public final class PhysicsBlocks {
	/** No entity damage, no knockback: GMod's own blast already hurt and pushed everything. */
	static final ExplosionDamageCalculator BLOCKS_ONLY = new ExplosionDamageCalculator() {
		@Override
		public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
			return false;
		}

		@Override
		public float getKnockbackMultiplier(Entity entity) {
			return 0.0F;
		}
	};
	private static final PhysicsBlockRules.Budget BUDGET = new PhysicsBlockRules.Budget();
	/** True while a GMod blast explodes (ServerExplosionMixin then tells GMod nothing: it was GMod's). */
	private static boolean gmodBlast;
	private static long pulls, pullsRefused, blasts, blastsDropped, landed, rescued, refallsStopped;
	/** Cells this class placed a block in -> game time: such a block doesn't fall again for PLACED_HOLD_TICKS. */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap PLACED = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	static final long PLACED_HOLD_TICKS = 100;
	private static int fallLogs, pullLogs, waitLogs, landLogs, hostLogs;
	/** A pulled block waits this many ticks (no fall, no landing) for GMod to take it; then the vanilla fall. */
	static final int PULL_WAIT_TICKS = 10;
	/** Pulled blocks still waiting for GMod: entity -> ticks waited (weak). */
	private static final java.util.Map<Entity, int[]> WAITING = new java.util.WeakHashMap<>();
	private static int logged;

	private PhysicsBlocks() {
	}

	/** Is the explosion running now a GMod blast (kHostEvBlast)? */
	public static boolean gmodBlast() {
		return gmodBlast;
	}

	/** Once per server tick (linked): a new blast budget. */
	public static void tick() {
		BUDGET.reset();
	}

	/**
	 * Does the host's geometry (the map, a GMod prop) hold up a body whose feet are at {@code feet}, in
	 * cell {@code pos}? A falling block on it lands as a block instead of breaking into an item.
	 */
	public static boolean hostHolds(Level level, BlockPos pos, Vec3 feet) {
		SkyCollision store = SkyCollision.of(level);
		if (!store.active()) {
			return false;
		}
		if (store.supportsFromBelow(pos) || store.hasGeometry(pos.below()) && store.groundTop(pos.below()) >= 1.0F) {
			return true;
		}
		return dynamicUnder(store, feet.x - 0.49, feet.y, feet.z - 0.49, feet.x + 0.49, feet.z + 0.49);
	}

	/** Is a GMod prop's collision (the dynamic layer) right under the box's bottom at y? */
	static boolean dynamicUnder(SkyCollision store, double minX, double y, double minZ, double maxX, double maxZ) {
		if (!store.hasDynamicNear(minX, y - 0.25, minZ, maxX, y + 0.05, maxZ)) {
			return false;
		}
		List<SkyTri> tris = new ArrayList<>();
		store.trianglesNear(new AABB(minX, y - 0.25, minZ, maxX, y + 0.05, maxZ), tris);
		for (SkyTri t : tris) {
			if (t.dynamic) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A block a GMod prop holds up doesn't start to fall (FallingBlockMixin): prop collision (the dynamic layer)
	 * anywhere from half a block below the cell's bottom to half a block into the cell. A block landed on a
	 * prop sits in the cell nearest the prop's top (landReleased rounds), so the top is inside that band;
	 * the old band (0.25 below to 0.05 above the bottom) missed a crate top 0.21 into the cell and the sand
	 * fell, landed and fell again (review run).
	 */
	public static boolean propUnder(Level level, BlockPos pos) {
		SkyCollision store = SkyCollision.of(level);
		return store.active() && dynamicIn(store, pos.getX() + 0.01, pos.getY() - 0.5, pos.getZ() + 0.01, pos.getX() + 0.99, pos.getY() + 0.5, pos.getZ() + 0.99) > 0;
	}

	/** How many dynamic (GMod prop) triangles overlap the box (0 without a region lookup hit). */
	static int dynamicIn(SkyCollision store, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		if (!store.hasDynamicNear(minX, minY, minZ, maxX, maxY, maxZ)) {
			return 0;
		}
		List<SkyTri> tris = new ArrayList<>();
		store.trianglesNear(new AABB(minX, minY, minZ, maxX, maxY, maxZ), tris);
		int n = 0;
		for (SkyTri t : tris) {
			if (t.dynamic) {
				n++;
			}
		}
		return n;
	}

	/**
	 * FallingBlockMixin: ticks left of the hold on a block we placed in this cell (it doesn't fall again meanwhile;
	 * the mixin schedules its block tick again for when the hold ends), 0: none.
	 */
	public static long justPlacedRemaining(Level level, BlockPos pos) {
		if (PLACED.isEmpty()) {
			return 0;
		}
		long left = PhysicsBlockRules.holdRemaining(PLACED.getOrDefault(pos.asLong(), Long.MIN_VALUE), level.getGameTime(), PLACED_HOLD_TICKS);
		if (left > 0) {
			refallsStopped++;
		}
		return left;  // the entry stays (pruned by age in markPlaced): placedFalls still recognises ours
	}

	/** FallingBlockMixin: a block we placed starts to fall after all: say what the dynamic layer had there (diagnostics). */
	public static void placedFalls(Level level, BlockPos pos) {
		if (fallLogs >= 20 || PLACED.isEmpty() || !PLACED.containsKey(pos.asLong())) {
			return;
		}
		fallLogs++;
		SkyCollision store = SkyCollision.of(level);
		double x = pos.getX(), y = pos.getY(), z = pos.getZ();
		GmodCraft.LOG.info("GmodCraft: block placed at {} falls again: dynamic near {}, prop triangles in the support band {}, within a block around {}, static support {}",
			pos.toShortString(), store.hasDynamicNear(x - 1, y - 1, z - 1, x + 2, y + 2, z + 2), dynamicIn(store, x + 0.01, y - 0.5, z + 0.01, x + 0.99, y + 0.5, z + 0.99),
			dynamicIn(store, x - 1, y - 1, z - 1, x + 2, y + 2, z + 2), store.supportsFromBelow(pos));
	}

	private static void markPlaced(ServerLevel level, BlockPos pos) {
		if (PLACED.size() > 4096) {
			long now = level.getGameTime();
			PLACED.long2LongEntrySet().removeIf(en -> now - en.getLongValue() > PLACED_HOLD_TICKS);
		}
		PLACED.put(pos.asLong(), level.getGameTime());
	}

	/** Place the falling block's state in pos if it may go there (replaceable, no fluid, in the slot, survives). */
	private static boolean placeAt(ServerLevel level, FallingBlockEntity falling, BlockState block, BlockPos pos) {
		if (!level.isLoaded(pos) || !Bridges.inSlot(level, pos) || SlotJobs.lockedAt(pos.getX(), pos.getZ())) {
			return false;
		}
		BlockState there = level.getBlockState(pos);
		if (!there.canBeReplaced() || !there.getFluidState().isEmpty() || !block.canSurvive(level, pos)
			|| !level.setBlock(pos, block, net.minecraft.world.level.block.Block.UPDATE_ALL)) {
			return false;
		}
		if (block.getBlock() instanceof net.minecraft.world.level.block.Fallable f) {
			f.onLand(level, pos, block, there, falling);
		}
		markPlaced(level, pos);
		return true;
	}

	/**
	 * A falling block GMod once simulated is about to break into an item (FallingBlockEntityMixin): it goes to the
	 * nearest free cell instead (its own, up to two above, then the four beside it and above those). True: placed,
	 * no item.
	 */
	public static boolean rescueItem(FallingBlockEntity falling) {
		if (!(falling.level() instanceof ServerLevel level)) {
			return false;
		}
		BlockState block = falling.getBlockState();
		BlockPos c = falling.blockPosition();
		if (block.isAir() || c.getY() < level.getMinY() || c.getY() > level.getMaxY()) {
			return false;
		}
		BlockPos[] tries = { c, c.above(), c.above(2), c.north(), c.south(), c.east(), c.west(), c.north().above(), c.south().above(), c.east().above(),
			c.west().above() };
		// cells that something holds up first (no second fall), then any
		for (int pass = 0; pass < 2; pass++) {
			for (BlockPos p : tries) {
				if (pass == 0 && !heldUp(level, p)) {
					continue;
				}
				if (placeAt(level, falling, block, p)) {
					rescued++;
					GmodCraft.LOG.info("GmodCraft: falling block {} GMod simulated would have dropped as an item at {}: placed at {} instead ({} so far)",
						BuiltInRegistries.BLOCK.getKey(block.getBlock()), c.toShortString(), p.toShortString(), rescued);
					return true;
				}
			}
		}
		GmodCraft.LOG.info("GmodCraft: falling block {} GMod simulated dropped as an item at {}: no free cell around", BuiltInRegistries.BLOCK.getKey(block.getBlock()),
			c.toShortString());
		return false;
	}

	/**
	 * FallingBlockEntityMixin, tick head: a pulled block waits in place (no fall, no landing) until GMod takes it,
	 * at most PULL_WAIT_TICKS. A block pulled from a floor row landed by the vanilla fall in the tick or two before
	 * GMod's first held record arrived (review run), sometimes as an item.
	 */
	public static boolean waitForGmod(FallingBlockEntity falling) {
		if (WAITING.isEmpty()) {
			return false;
		}
		int[] waited = WAITING.get(falling);
		if (waited == null) {
			return false;
		}
		boolean taken = dev.gmodcraft.combat.HeldMcEntities.pinned(falling);
		if (taken || ++waited[0] > PULL_WAIT_TICKS || falling.isRemoved()) {
			WAITING.remove(falling);
			if (waitLogs++ < 60) {
				GmodCraft.LOG.info("GmodCraft: pulled block {} waited {} tick(s) for GMod: {}", falling.getId(), waited[0],
					taken ? "taken" : falling.isRemoved() ? "gone" : "not taken, falls on its own");
			}
			return false;
		}
		falling.needsSync = true;
		return true;
	}

	/** Would a falling block in this cell stay (a block under it, the map's ground, a GMod prop)? */
	static boolean heldUp(ServerLevel level, BlockPos p) {
		return !net.minecraft.world.level.block.FallingBlock.isFree(level.getBlockState(p.below())) || SkyCollision.of(level).supportsFromBelow(p) || propUnder(level, p);
	}

	/** FallingBlockEntityMixin: a falling block landed on host geometry (the map, a prop) where vanilla saw air below. */
	public static void landedOnHost(FallingBlockEntity falling) {
		if (hostLogs++ < 60) {
			GmodCraft.LOG.info("GmodCraft: falling block {} landed on GMod's geometry at {}", BuiltInRegistries.BLOCK.getKey(falling.getBlockState().getBlock()),
				falling.blockPosition().toShortString());
		}
	}

	/**
	 * GMod released a falling block it simulated (HeldMcEntities): if it came to rest there, it becomes the block
	 * in its cell right away. Minecraft's own fall would need the host's geometry under it, which Minecraft only
	 * has near its players (a block thrown far off, to the ground below a roof, fell into the void in a live run).
	 * False: not ours to land (the vanilla fall goes on).
	 */
	public static boolean landReleased(Entity e, int lastFlags, double vy) {
		if (!(e instanceof FallingBlockEntity falling) || !(e.level() instanceof ServerLevel level) || !PhysicsBlockRules.restedInGmod(lastFlags, vy)) {
			return false;
		}
		BlockState block = falling.getBlockState();
		// the cell nearest its feet: resting on a crate whose top is 0.21 into a cell, that cell (the block overlaps
		// the top a little); 0.7 into it, the cell above (propUnder's band holds it there)
		BlockPos pos = BlockPos.containing(falling.getX(), PhysicsBlockRules.landingY(falling.getY()), falling.getZ());
		if (block.isAir() || !placeAt(level, falling, block, pos)) {
			return false;
		}
		falling.discard();
		landed++;
		if (landLogs++ < 200) {
			GmodCraft.LOG.info("GmodCraft: falling block {} came to rest in GMod: placed at {} ({} so far)", BuiltInRegistries.BLOCK.getKey(block.getBlock()),
				pos.toShortString(), landed);
		}
		return true;
	}

	/** kHostEvPullBlock: x/y/z = the block, yaw / pitch = the puller's look (MC degrees), flags = PullFlags. */
	public static void pull(MinecraftServer server, ServerLink.HostEvent ev) {
		ServerLevel level = server.overworld();
		if (ev.worldId() != ServerHost.worldId() || !Double.isFinite(ev.x() + ev.y() + ev.z())) {
			pullsRefused++;
			return;
		}
		BlockPos pos = BlockPos.containing(ev.x() + 0.5, ev.y() + 0.5, ev.z() + 0.5);
		if (!level.isLoaded(pos)) {
			pullsRefused++;
			return;
		}
		BlockState state = level.getBlockState(pos);
		PhysicsBlockRules.PullTarget target = new PhysicsBlockRules.PullTarget(Bridges.inSlot(level, pos), SlotJobs.lockedAt(pos.getX(), pos.getZ()),
			!state.isAir() && HullWorld.isMirror(level, pos), state.isAir(), !state.getFluidState().isEmpty(), state.hasBlockEntity(),
			state.getDestroySpeed(level, pos), BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
		ServerPlayer player = ServerHost.playerBySteamId(server, ev.steamId());
		boolean admin = (ev.flags() & dev.gmodcraft.link.Proto.PULL_BY_ADMIN) != 0;
		boolean playerMay = player != null && player.level() == level && !player.isSpectator() && player.mayInteract(level, pos)
			&& !player.blockActionRestricted(level, pos, player.gameMode());
		PhysicsBlockRules.Pull verdict = PhysicsBlockRules.pull(target, admin, player != null, playerMay);
		if (verdict != PhysicsBlockRules.Pull.OK) {
			pullsRefused++;
			GmodCraft.LOG.debug("GmodCraft: gravgun pull of {} at {} refused: {}", state, pos.toShortString(), verdict);
			return;
		}
		FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, state);
		dev.gmodcraft.combat.HeldMcEntities.markForGmod(falling);
		WAITING.put(falling, new int[1]);
		Vec3 look = Vec3.directionFromRotation(Float.isFinite(ev.pitch()) ? ev.pitch() : 0.0F, Float.isFinite(ev.yaw()) ? ev.yaw() : 0.0F);
		// toward the puller (against their look), lifted a little out of its hole
		falling.setDeltaMovement(look.scale(-PhysicsBlockRules.PULL_SPEED).add(0.0, 0.1, 0.0));
		falling.needsSync = true;
		pulls++;
		if (pullLogs++ < 60) {
			GmodCraft.LOG.info("GmodCraft: gravgun pulled {} out at {} (falling block {}; {} pulls, {} refused)", BuiltInRegistries.BLOCK.getKey(state.getBlock()),
				pos.toShortString(), falling.getId(), pulls, pullsRefused);
		}
	}

	/** kHostEvBlast: x/y/z = the centre (MC coords), a = power * 100. */
	public static void blast(MinecraftServer server, ServerLink.HostEvent ev) {
		ServerLevel level = server.overworld();
		float power = PhysicsBlockRules.blastPower(ev.a());
		double x = ev.x(), y = ev.y(), z = ev.z();
		if (ev.worldId() != ServerHost.worldId() || power <= 0.0F || !Double.isFinite(x + y + z) || !Boolean.TRUE.equals(server.getGameRules().get(GameRules.TNT_EXPLODES))) {
			blastsDropped++;
			return;
		}
		BlockPos centre = BlockPos.containing(x, y, z);
		if (!level.isLoaded(centre) || !Bridges.inSlot(level, centre) || SlotJobs.lockedAt(centre.getX(), centre.getZ()) || !BUDGET.take(x, y, z)) {
			blastsDropped++;
			return;
		}
		gmodBlast = true;
		try {
			level.explode(null, null, BLOCKS_ONLY, x, y, z, power, false, Level.ExplosionInteraction.TNT);
		} finally {
			gmodBlast = false;
		}
		blasts++;
		if (logged++ < 20) {
			GmodCraft.LOG.info("GmodCraft: GMod blast at {} power {} ({} blasts, {} dropped)", centre.toShortString(), power, blasts, blastsDropped);
		}
	}

	/** For the debug dump. */
	public static String stats() {
		return "landed " + landed + ", pulls " + pulls + " (refused " + pullsRefused + "), blasts " + blasts + " (dropped " + blastsDropped + ")";
	}
}
