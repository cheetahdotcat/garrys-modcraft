package dev.gmodcraft.combat;

import dev.gmodcraft.link.Proto;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * An invisible stand-in for one GMod NPC (host actor), so Minecraft's own combat (swords, crits,
 * sweeps, enchantments, attack cooldown, bows, tridents) can target and hit GMod NPCs. What it
 * receives is collected into one hit per tick and forwarded to the real NPC over the server link;
 * its own health never drops.
 */
public class HostActorEntity extends LivingEntity {
	private static final EntityDataAccessor<Integer> ENT_ID = SynchedEntityData.defineId(HostActorEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(HostActorEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(HostActorEntity.class, EntityDataSerializers.FLOAT);

	// This tick's hit, flushed to the host by SkyCombat after all attacks for the tick have landed
	// (Player.attack adds its sprint/enchantment knockback after hurtServer returns).
	private float pendingDamage;
	private int pendingFlags;
	private int pendingWeapon;
	private double pushX, pushZ;
	private float pushStrength;
	private boolean hitThisTick;
	// The player who landed this tick's (last) hit, reported with it so the host can credit them.
	private net.minecraft.server.level.@Nullable ServerPlayer pendingPlayer;
	// Where block effects were last applied: the host moves us between ticks, so this tick's
	// movement for Minecraft's block checks runs from here to where we are now.
	private @Nullable Vec3 lastBlockCheck;

	// A host move longer than this (a teleport, a respawn) isn't swept through for block effects.
	private static final double MAX_BLOCK_SWEEP_SQR = 4.0 * 4.0;
	// How far below the feet a block may hold us up and still count as standing on it.
	private static final double GROUND_PROBE = 0.1;

	public HostActorEntity(EntityType<? extends HostActorEntity> type, Level level) {
		super(type, level);
		this.setNoGravity(true);
		this.noPhysics = true;
		this.setInvisible(true);
		this.setSilent(true);
	}

	public int entId() {
		return this.entityData.get(ENT_ID);
	}

	public void setEntId(int entId) {
		this.entityData.set(ENT_ID, entId);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ENT_ID, 0);
		builder.define(WIDTH, 0.6F);
		builder.define(HEIGHT, 1.8F);
	}

	public void setSize(float width, float height) {
		if (Math.abs(this.entityData.get(WIDTH) - width) > 0.01F || Math.abs(this.entityData.get(HEIGHT) - height) > 0.01F) {
			this.entityData.set(WIDTH, width);
			this.entityData.set(HEIGHT, height);
			this.refreshDimensions();
		}
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (WIDTH.equals(accessor) || HEIGHT.equals(accessor)) {
			this.refreshDimensions();
		}
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		return EntityDimensions.scalable(this.entityData.get(WIDTH), this.entityData.get(HEIGHT));
	}

	@Override
	protected void actuallyHurt(ServerLevel level, DamageSource source, float dmg) {
		// Minecraft has applied everything (crit, sharpness, strength, cooldown, invulnerability
		// frames). Hand the result to the host instead of lowering our own health.
		if (this.isInvulnerableTo(level, source) || dmg <= 0.0F) {
			return;
		}
		this.pendingDamage += dmg;
		if (source.getDirectEntity() instanceof Projectile) {
			this.pendingFlags |= Proto.HIT_PROJECTILE;
		}
		this.pendingWeapon = weaponClass(source);
		// Burning, fire and lava set the NPC alight on the host; a magma floor (hot_floor, also in
		// IS_FIRE) only hurts.
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) && !source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR)) {
			this.pendingFlags |= Proto.HIT_FIRE;
		}
		this.hitThisTick = true;
		if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
			this.pendingPlayer = player;
		}
		this.getCombatTracker().recordDamage(source, dmg);
	}

	@Override
	public void knockback(double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect) {
		// The host owns this actor's position. Remember the strongest push for the host's stagger:
		// Minecraft pushes towards -(xd, zd).
		double len = Math.sqrt(xd * xd + zd * zd);
		if (len > 1e-6 && power > this.pushStrength) {
			this.pushStrength = (float) power;
			this.pushX = -xd / len;
			this.pushZ = -zd / len;
		}
		this.hitThisTick = true;
	}

	/** Player.crit() was called on us this tick. */
	public void markCritical() {
		this.pendingFlags |= Proto.HIT_CRITICAL;
	}

	/** Which kind of weapon impact this hit should look and sound like. */
	private static int weaponClass(DamageSource source) {
		if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident) {
			return Proto.WEAPON_PIERCE;
		}
		if (source.getDirectEntity() instanceof Projectile) {
			return Proto.WEAPON_ARROW;
		}
		ItemStack weapon = source.getWeaponItem();
		if (weapon == null && source.getEntity() instanceof LivingEntity attacker) {
			weapon = attacker.getMainHandItem();
		}
		if (weapon == null || weapon.isEmpty()) {
			return Proto.WEAPON_UNARMED;
		}
		if (weapon.is(ItemTags.SWORDS)) {
			return Proto.WEAPON_BLADE;
		}
		if (weapon.is(ItemTags.AXES)) {
			return Proto.WEAPON_AXE;
		}
		if (weapon.is(Items.TRIDENT)) {
			return Proto.WEAPON_PIERCE;
		}
		return Proto.WEAPON_BLUNT;
	}

	/** The player behind this tick's hit (call before takeHit, which clears it), or null. */
	public net.minecraft.server.level.@Nullable ServerPlayer hitter() {
		return this.pendingPlayer;
	}

	/** Returns this tick's hit (damage, flags, push, weapon) and clears it; null if nothing hit us. */
	public float[] takeHit() {
		if (!this.hitThisTick) {
			return null;
		}
		float[] hit = { this.pendingDamage, (float) this.pushX, (float) this.pushZ, this.pushStrength, Float.intBitsToFloat(this.pendingFlags),
			Float.intBitsToFloat(this.pendingWeapon) };
		this.pendingDamage = 0.0F;
		this.pendingFlags = 0;
		this.pushX = this.pushZ = 0.0;
		this.pushStrength = 0.0F;
		this.hitThisTick = false;
		this.pendingPlayer = null;
		return hit;
	}

	@Override
	public void tick() {
		// Position and rotation come from the host (SkyCombat); keep hurt timers and fire ticking.
		this.baseTick();
		if (this.level() instanceof ServerLevel level && !this.isRemoved()) {
			this.applyBlockEffects(level);
		}
		this.setHealth(this.getMaxHealth());
	}

	/**
	 * Minecraft's block effects for the host's move this tick: pressure plates, tripwires, lava,
	 * fire, cactus, berry bushes, water putting fire out (Entity.applyEffectsFromBlocks), and with
	 * onGround the block underfoot's stepOn (magma). We never move ourselves (noPhysics), so
	 * Entity.move() never sets onGround or runs these.
	 */
	private void applyBlockEffects(ServerLevel level) {
		Vec3 cur = this.position();
		Vec3 last = this.lastBlockCheck;
		if (last == null || last.distanceToSqr(cur) > MAX_BLOCK_SWEEP_SQR) {
			last = cur;
		}
		this.setOnGround(this.standsOnBlock(level));
		this.applyEffectsFromBlocks(last, cur);
		this.lastBlockCheck = cur;
	}

	/** A block (or the host's ground) holds up our feet: the thin slab just under them collides. */
	private boolean standsOnBlock(ServerLevel level) {
		AABB box = this.getBoundingBox();
		AABB feet = new AABB(box.minX + 1.0E-3, box.minY - GROUND_PROBE, box.minZ + 1.0E-3, box.maxX - 1.0E-3, box.minY, box.maxZ - 1.0E-3);
		return !level.noBlockCollision(this, feet);
	}

	@Override
	protected boolean isAffectedByBlocks() {
		// Vanilla skips noPhysics entities; we keep noPhysics (the host moves us) but want the effects.
		return !this.isRemoved();
	}

	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return false; // the host owns where the NPC is: a portal must not carry its stand-in away
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(Entity entity) {
	}

	/**
	 * Solid, like a boat (P6i): Minecraft players and mobs bump into the NPC instead of walking
	 * through it. Never pushed (the host owns the position) and never pushing; nothing goes back to
	 * GMod from here, so the NPC's own movement is unaffected. An overlap (the host moving the NPC
	 * into a player) doesn't trap: Minecraft's box collision ignores a box it already overlaps.
	 * <p>Not for server-side players: their moves are the client's, replayed by the server against
	 * this copy, which lags the client's (ProxySync puts the client's where GMod has the NPC this
	 * frame). A replay that bumped into the lagging copy would read as "moved wrongly" and snap the
	 * player back; the client's own collision already kept it out.
	 */
	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return !(other instanceof net.minecraft.server.level.ServerPlayer) && !(other instanceof HostActorEntity);
	}

	@Override
	public boolean shouldShowName() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected @Nullable SoundEvent getHurtSound(DamageSource source) {
		return null; // the host plays the NPC's own pain sounds
	}

	@Override
	protected @Nullable SoundEvent getDeathSound() {
		return null;
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}
}
