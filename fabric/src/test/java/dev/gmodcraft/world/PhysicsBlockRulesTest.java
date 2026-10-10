package dev.gmodcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gmodcraft.link.Proto;
import dev.gmodcraft.world.PhysicsBlockRules.Pull;
import dev.gmodcraft.world.PhysicsBlockRules.PullTarget;
import org.junit.jupiter.api.Test;

class PhysicsBlockRulesTest {
	private static PullTarget dirt() {
		return new PullTarget(true, false, false, false, false, false, 0.5F, "dirt");
	}

	@Test
	void aPlayerMayPullWhatTheyMayBreak() {
		assertEquals(Pull.OK, PhysicsBlockRules.pull(dirt(), false, true, true));
		assertEquals(Pull.NO_PERMISSION, PhysicsBlockRules.pull(dirt(), false, true, false), "spawn protection / adventure mode");
		assertEquals(Pull.NO_PERMISSION, PhysicsBlockRules.pull(dirt(), false, false, false), "no Minecraft player: admins only");
		assertEquals(Pull.OK, PhysicsBlockRules.pull(dirt(), true, false, false));
	}

	@Test
	void mirrorBlocksNeverComeLoose() {
		PullTarget mirror = new PullTarget(true, false, true, false, false, false, 0.5F, "stone");
		assertEquals(Pull.MIRROR, PhysicsBlockRules.pull(mirror, true, true, true), "not even for admins");
	}

	@Test
	void refusals() {
		assertEquals(Pull.OUTSIDE_SLOT, PhysicsBlockRules.pull(new PullTarget(false, false, false, false, false, false, 0.5F, "dirt"), true, true, true));
		assertEquals(Pull.LOCKED, PhysicsBlockRules.pull(new PullTarget(true, true, false, false, false, false, 0.5F, "dirt"), true, true, true));
		assertEquals(Pull.AIR, PhysicsBlockRules.pull(new PullTarget(true, false, false, true, false, false, 0.0F, "air"), true, true, true));
		assertEquals(Pull.LIQUID, PhysicsBlockRules.pull(new PullTarget(true, false, false, false, true, false, 100.0F, "water"), true, true, true));
		assertEquals(Pull.BLOCK_ENTITY, PhysicsBlockRules.pull(new PullTarget(true, false, false, false, false, true, 2.5F, "chest"), true, true, true));
		assertEquals(Pull.UNBREAKABLE, PhysicsBlockRules.pull(new PullTarget(true, false, false, false, false, false, -1.0F, "bedrock"), true, true, true));
		assertEquals(Pull.UNBREAKABLE, PhysicsBlockRules.pull(new PullTarget(true, false, false, false, false, false, Float.NaN, "odd"), true, true, true));
		assertEquals(Pull.ADMIN_ONLY, PhysicsBlockRules.pull(new PullTarget(true, false, false, false, false, false, 0.0F, "tnt"), false, true, true));
		assertEquals(Pull.OK, PhysicsBlockRules.pull(new PullTarget(true, false, false, false, false, false, 0.0F, "tnt"), true, true, true));
	}

	@Test
	void onlyARestingPhysicsBlockLandsInPlace() {
		assertTrue(PhysicsBlockRules.restedInGmod(Proto.HELD_PHYSICS, -0.001));
		assertFalse(PhysicsBlockRules.restedInGmod(Proto.HELD_PHYSICS, -0.5), "given back while still falling (age limit)");
		assertFalse(PhysicsBlockRules.restedInGmod(Proto.HELD_BY_PHYSGUN, 0.0), "a physgun drop flies on in Minecraft's hands");
		assertFalse(PhysicsBlockRules.restedInGmod(Proto.HELD_FROZEN, 0.0));
	}

	@Test
	void landingCellIsTheNearestToTheFeet() {
		assertEquals(70.5, PhysicsBlockRules.landingY(70.21), 1e-9, "crate top 0.21 into cell 70: cell 70");
		assertEquals(71.5, PhysicsBlockRules.landingY(70.7), 1e-9, "0.7 into it: the cell above");
		assertEquals(64.5, PhysicsBlockRules.landingY(64.0), 1e-9, "on a block: the cell above it");
		assertEquals(64.5, PhysicsBlockRules.landingY(63.98), 1e-9, "a hair under a block top: still the cell above");
	}

	@Test
	void holdRemainingSchedulesTheRecheck() {
		assertEquals(0, PhysicsBlockRules.holdRemaining(Long.MIN_VALUE, 500, 100), "never placed: no hold");
		assertEquals(100, PhysicsBlockRules.holdRemaining(500, 500, 100), "just placed: the whole hold");
		assertEquals(40, PhysicsBlockRules.holdRemaining(500, 560, 100));
		assertEquals(0, PhysicsBlockRules.holdRemaining(500, 600, 100), "over: the vanilla check runs");
		assertEquals(0, PhysicsBlockRules.holdRemaining(500, 9000, 100));
		assertEquals(100, PhysicsBlockRules.holdRemaining(500, 400, 100), "clock went back (new world time): a full hold, never negative");
	}

	@Test
	void blastPowerIsClamped() {
		assertEquals(3.2F, PhysicsBlockRules.blastPower(320), 1e-6F);
		assertEquals(Proto.BLAST_MAX_POWER, PhysicsBlockRules.blastPower(100000), 1e-6F);
		assertEquals(0.0F, PhysicsBlockRules.blastPower(0));
		assertEquals(0.0F, PhysicsBlockRules.blastPower(-50));
	}

	@Test
	void budgetMergesCloseBlastsAndCapsATick() {
		PhysicsBlockRules.Budget b = new PhysicsBlockRules.Budget();
		assertTrue(b.take(0, 64, 0));
		assertFalse(b.take(0.5, 64, 0.5), "within a block of the first: one explosion");
		for (int i = 1; i < Proto.BLASTS_PER_TICK; i++) {
			assertTrue(b.take(i * 10, 64, 0));
		}
		assertFalse(b.take(100, 64, 0), "budget spent");
		b.reset();
		assertTrue(b.take(100, 64, 0));
	}
}
