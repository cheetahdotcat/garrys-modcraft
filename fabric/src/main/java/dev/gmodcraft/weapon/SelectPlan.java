package dev.gmodcraft.weapon;

/**
 * kHostEvSelectWeapon (protocol v45): where a player's stack of the class GMod switched to is, and
 * what puts it into the main hand. Pure (no Minecraft types), so the choice is unit-tested; {@link
 * GmodWeapons} carries it out with vanilla's pick-item steps.
 *
 * @param kind what to do
 * @param slot the main inventory slot for {@link Kind#SELECT} / {@link Kind#PICK}, else -1
 */
public record SelectPlan(Kind kind, int slot) {
	public enum Kind {
		/** Not in the main inventory or the offhand, or already in the main hand: nothing changes. */
		NONE,
		/** In the hotbar: select that slot. */
		SELECT,
		/** Elsewhere in the main inventory: Inventory.pickSlot (into a free, else the selected, hotbar slot). */
		PICK,
		/** In the offhand: the hands swap. */
		OFFHAND,
	}

	public static final SelectPlan NONE = new SelectPlan(Kind.NONE, -1);

	/**
	 * @param main     class hash of the weapon stack in each main inventory slot (hotbar first), 0 = none
	 * @param hotbar   how many of {@code main} are the hotbar
	 * @param selected the selected hotbar slot
	 * @param offhand  class hash of the weapon stack in the offhand, 0 = none
	 * @param hash     the class GMod switched to (0 matches nothing)
	 */
	public static SelectPlan plan(int[] main, int hotbar, int selected, int offhand, int hash) {
		if (hash == 0) {
			return NONE;
		}
		if (selected >= 0 && selected < main.length && main[selected] == hash) {
			return NONE;
		}
		for (int i = 0; i < main.length; i++) {
			if (main[i] == hash) {
				return new SelectPlan(i < hotbar ? Kind.SELECT : Kind.PICK, i);
			}
		}
		return offhand == hash ? new SelectPlan(Kind.OFFHAND, -1) : NONE;
	}
}
