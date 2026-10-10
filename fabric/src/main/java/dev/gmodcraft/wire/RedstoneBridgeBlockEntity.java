package dev.gmodcraft.wire;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The six faces of a redstone bridge: what GMod drives (levels + driven mask) and what Minecraft
 * reads on the faces GMod doesn't drive. Nothing is saved: after a load every face is undriven
 * until the GMod side sends its outputs again (it does on every kEvBridgePlaced). Server thread
 * only. Face index = {@link Direction#get3DDataValue()} (protocol BridgeFace order).
 */
public final class RedstoneBridgeBlockEntity extends BlockEntity {
	private static final Direction[] FACES = new Direction[6];

	static {
		for (Direction d : Direction.values()) {
			FACES[d.get3DDataValue()] = d;
		}
	}

	private final int[] in = new int[6];
	private final int[] out = new int[6];
	private int driven;      // bit i: face i is driven by GMod
	private boolean registered;
	// R2 (v25): the linked GMod map entity, saved with the block (the only thing it saves)
	private @org.jspecify.annotations.Nullable MapLink link;
	// the link changed (or the bridge was announced again): Bridges sends kEvBridgeLink
	boolean linkDirty = true;

	public @org.jspecify.annotations.Nullable MapLink link() {
		return this.link;
	}

	void setLink(@org.jspecify.annotations.Nullable MapLink link) {
		this.link = link;
		this.linkDirty = true;
		setChanged();
	}

	@Override
	protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
		super.saveAdditional(out);
		if (this.link != null) {
			net.minecraft.world.level.storage.ValueOutput l = out.child("gmodcraft_link");
			l.putInt("world", this.link.worldId());
			l.putString("map", this.link.map());
			l.putInt("id", this.link.creationId());
			l.putString("name", this.link.name());
			l.putInt("kind", this.link.kind());
			l.putInt("mode", this.link.mode());
		}
	}

	@Override
	protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
		super.loadAdditional(in);
		this.link = in.child("gmodcraft_link").map(l -> new MapLink(l.getIntOr("world", 0), l.getStringOr("map", ""), l.getIntOr("id", -1),
			l.getStringOr("name", ""), l.getIntOr("kind", 0), l.getIntOr("mode", 0))).filter(l -> MapLink.supports(l.kind(), l.mode()) && l.creationId() >= 0)
			.orElse(null);
		this.linkDirty = true;
	}

	public RedstoneBridgeBlockEntity(BlockPos pos, BlockState state) {
		super(Bridges.BLOCK_ENTITY, pos, state);
	}

	/** Weak power on {@code face}: the driven level, else 0. */
	int output(Direction face) {
		int i = face.get3DDataValue();
		return (this.driven >> i & 1) != 0 ? this.out[i] : 0;
	}

	/** Re-reads the redstone level next to every undriven face (a driven face reads 0). */
	void readInputs() {
		if (this.level == null) {
			return;
		}
		for (int i = 0; i < 6; i++) {
			Direction f = FACES[i];
			this.in[i] = (this.driven >> i & 1) != 0 ? 0 : clamp(this.level.getSignal(this.worldPosition.relative(f), f));
		}
	}

	/** Input levels packed 4 bits per face (protocol layout). */
	int packedInputs() {
		return pack(this.in);
	}

	int drivenMask() {
		return this.driven;
	}

	int packedOutputs() {
		return pack(this.out);
	}

	/**
	 * Applies what GMod drives; returns true when the power we give changed (the caller then
	 * updates the neighbours). Faces that stop being driven are read as inputs again.
	 */
	boolean applyOutputs(int mask, int levels) {
		mask &= 0x3F;
		boolean changed = false;
		for (int i = 0; i < 6; i++) {
			boolean wasDriven = (this.driven >> i & 1) != 0;
			boolean nowDriven = (mask >> i & 1) != 0;
			int before = wasDriven ? this.out[i] : 0;
			int level = levels >>> (4 * i) & 0xF;
			this.out[i] = nowDriven ? level : 0;
			changed |= before != this.out[i] || wasDriven != nowDriven;
		}
		this.driven = mask;
		if (changed) {
			readInputs(); // a face that stopped being driven is an input again; a newly driven one reads 0
		}
		return changed;
	}

	/** Stops driving every face (GMod gone, Wiremod gone, map changed); true when anything changed. */
	boolean clearOutputs() {
		return applyOutputs(0, 0);
	}

	void serverTick(ServerLevel level) {
		if (!this.registered) {
			this.registered = true;
			readInputs();
			Bridges.loaded(this);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		if (this.registered) {
			this.registered = false;
			Bridges.unloaded(this);
		}
	}

	static int clamp(int level) {
		return Math.max(0, Math.min(15, level));
	}

	static int pack(int[] levels) {
		int p = 0;
		for (int i = 0; i < 6; i++) {
			p |= clamp(levels[i]) << (4 * i);
		}
		return p;
	}
}
