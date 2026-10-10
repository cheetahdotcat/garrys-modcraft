package dev.gmodcraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which players GMod has in noclip (v22 kHostPlayerNoclip, N1), and who is still falling out of it.
 * Pure bookkeeping: {@link Noclip} applies the changes to the players. Leaving noclip starts a
 * grace that lasts until the player first lands: a player let go in mid-air (GMod mode: Minecraft
 * only follows by teleport) takes no fall damage for that drop.
 */
public final class NoclipState {
	/** What {@link #update} decided for a player. */
	public enum Change {
		NONE, ON, OFF
	}

	private final Map<UUID, Boolean> on = new HashMap<>(); // present: noclip (true) or the grace after it (false)

	public Change update(UUID player, boolean want) {
		boolean active = this.active(player);
		if (want == active) {
			return Change.NONE;
		}
		this.on.put(player, want); // off: grace until landed
		return want ? Change.ON : Change.OFF;
	}

	/** In noclip now. */
	public boolean active(UUID player) {
		Boolean cur = this.on.get(player);
		return cur != null && cur;
	}

	/** In noclip, or falling out of it (no fall damage either way). */
	public boolean noFall(UUID player) {
		return this.on.containsKey(player);
	}

	/** The player stands on something: a grace after noclip ends here. */
	public void landed(UUID player) {
		Boolean cur = this.on.get(player);
		if (cur != null && !cur) {
			this.on.remove(player);
		}
	}

	/** Forget a player (left the server). */
	public void forget(UUID player) {
		this.on.remove(player);
	}

	/** Every player in noclip now. */
	public List<UUID> activePlayers() {
		List<UUID> out = new ArrayList<>();
		this.on.forEach((id, a) -> {
			if (a) {
				out.add(id);
			}
		});
		return out;
	}

	public void clear() {
		this.on.clear();
	}
}
