package dev.gmodcraft.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** kHostEvSelectWeapon (protocol v45): which slot the weapon GMod switched to comes from. */
class SelectPlanTest {
	private static final int TOOL = 0xC22D7F39, PISTOL = 0xC4131651, OTHER = 0x1234;

	private static int[] inventory() {
		return new int[36]; // 9 hotbar + 27 main, all without a weapon
	}

	@Test
	void hotbarHitSelectsThatSlot() {
		int[] main = inventory();
		main[6] = TOOL;
		main[2] = PISTOL;
		assertEquals(new SelectPlan(SelectPlan.Kind.SELECT, 6), SelectPlan.plan(main, 9, 2, 0, TOOL));
	}

	@Test
	void mainInventoryIsPickedIntoTheHotbar() {
		int[] main = inventory();
		main[20] = TOOL;
		main[0] = PISTOL;
		assertEquals(new SelectPlan(SelectPlan.Kind.PICK, 20), SelectPlan.plan(main, 9, 0, 0, TOOL));
	}

	@Test
	void absentChangesNothing() {
		int[] main = inventory();
		main[0] = PISTOL;
		main[30] = OTHER;
		assertEquals(SelectPlan.NONE, SelectPlan.plan(main, 9, 0, 0, TOOL));
	}

	@Test
	void alreadyHeldChangesNothing() {
		int[] main = inventory();
		main[4] = TOOL;
		assertEquals(SelectPlan.NONE, SelectPlan.plan(main, 9, 4, 0, TOOL));
	}

	@Test
	void hotbarWinsOverTheMainInventoryAndTheOffhand() {
		int[] main = inventory();
		main[25] = TOOL;
		main[8] = TOOL;
		assertEquals(new SelectPlan(SelectPlan.Kind.SELECT, 8), SelectPlan.plan(main, 9, 0, TOOL, TOOL));
	}

	@Test
	void offhandSwapsTheHands() {
		assertEquals(new SelectPlan(SelectPlan.Kind.OFFHAND, -1), SelectPlan.plan(inventory(), 9, 0, TOOL, TOOL));
	}

	@Test
	void hashZeroMatchesNoEmptySlot() {
		assertEquals(SelectPlan.NONE, SelectPlan.plan(inventory(), 9, 0, 0, 0));
	}
}
