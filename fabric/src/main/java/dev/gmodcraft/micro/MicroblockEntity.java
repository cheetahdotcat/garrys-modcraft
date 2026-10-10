package dev.gmodcraft.micro;

import dev.gmodcraft.world.BlockDeltas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The parts of one microblock cell. The part list is immutable (replaced on every edit), so the
 * renderer can take it as its render data on any thread. The shape is the union of the parts,
 * shared between cells with the same boxes ({@link #shapeFor}): BlockDeltas caches octants per shape
 * object, so identical microblocks must reuse one.
 */
public final class MicroblockEntity extends BlockEntity {
	/** Most parts in one cell (64 eighth-cubes would already be silly). */
	public static final int MAX_PARTS = 64;
	private static final int SHAPE_CACHE = 1024;
	private static final Map<List<Integer>, VoxelShape> SHAPES = new LinkedHashMap<>(256, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<List<Integer>, VoxelShape> eldest) {
			return size() > SHAPE_CACHE;
		}
	};

	private List<MicroPart> parts = List.of();
	private VoxelShape shape = Shapes.block();
	private boolean checked;

	public MicroblockEntity(BlockPos pos, BlockState state) {
		super(Microblocks.BLOCK_ENTITY, pos, state);
	}

	public List<MicroPart> parts() {
		return this.parts;
	}

	/** The union of the parts; a full block while there are none (an empty cell turns into air on its first tick). */
	public VoxelShape shape() {
		return this.shape;
	}

	public List<int[]> boxes() {
		List<int[]> out = new ArrayList<>(this.parts.size());
		for (MicroPart p : this.parts) {
			out.add(p.boxArray());
		}
		return out;
	}

	public boolean fits(int[] box) {
		return this.parts.size() < MAX_PARTS && MicroGeom.fits(boxes(), box);
	}

	/** Adds a part (server); the caller checked {@link #fits}. */
	public void add(MicroPart part) {
		List<MicroPart> next = new ArrayList<>(this.parts);
		next.add(part);
		setParts(next);
		partsChanged();
	}

	/** Removes part {@code index} (server) and returns it. */
	public MicroPart remove(int index) {
		List<MicroPart> next = new ArrayList<>(this.parts);
		MicroPart gone = next.remove(index);
		setParts(next);
		partsChanged();
		return gone;
	}

	private void setParts(List<MicroPart> next) {
		this.parts = List.copyOf(next);
		this.shape = shapeFor(this.parts);
	}

	/** After an edit: save, tell clients (block update + our data packet), and re-send the cell's section to GMod. */
	private void partsChanged() {
		setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			BlockState state = getBlockState();
			this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_ALL);
			BlockDeltas.blockChanged(this.level, this.worldPosition);
		}
	}

	static synchronized VoxelShape shapeFor(List<MicroPart> parts) {
		if (parts.isEmpty()) {
			return Shapes.block();
		}
		List<Integer> key = new ArrayList<>(parts.size());
		for (MicroPart p : parts) {
			key.add(p.box());
		}
		key.sort(null);
		return SHAPES.computeIfAbsent(List.copyOf(key), k -> {
			VoxelShape s = Shapes.empty();
			for (MicroPart p : parts) {
				s = Shapes.or(s, Shapes.create(p.aabb()));
			}
			return s.optimize();
		});
	}

	/** Server ticker: a cell loaded (or set by a command) without parts becomes air. */
	void serverTick() {
		if (!this.checked) {
			this.checked = true;
			if (this.parts.isEmpty() && this.level != null) {
				this.level.removeBlock(this.worldPosition, false);
			}
		}
	}

	@Override
	public Object getRenderData() {
		return this.parts;
	}

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.store("parts", MicroPart.LIST_CODEC, this.parts);
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		List<MicroPart> loaded = in.read("parts", MicroPart.LIST_CODEC).orElse(List.of());
		// drop overlapping or surplus parts (hand-edited data): first come, first kept
		List<MicroPart> kept = new ArrayList<>();
		List<int[]> boxes = new ArrayList<>();
		for (MicroPart p : loaded) {
			int[] b = p.boxArray();
			if (kept.size() < MAX_PARTS && MicroGeom.fits(boxes, b) && !p.material().isAir()) {
				kept.add(p);
				boxes.add(b);
			}
		}
		setParts(kept);
		// client: a data packet changed the parts; re-mesh the cell (Minecraft's and GMod's)
		if (this.level != null && this.level.isClientSide()) {
			BlockState state = getBlockState();
			this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_IMMEDIATE);
		}
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
