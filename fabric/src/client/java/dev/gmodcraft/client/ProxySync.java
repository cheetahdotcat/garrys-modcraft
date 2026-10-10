package dev.gmodcraft.client;

import dev.gmodcraft.combat.HostActorEntity;
import dev.gmodcraft.link.ClientLink;
import dev.gmodcraft.link.GLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Puts the client's copies of the Skyrim actor stand-ins exactly where Skyrim has the actors this
 * frame, so the crosshair and melee reach line up with what's on screen (the server copy only
 * moves once per tick and reaches the client a tick or two later).
 */
final class ProxySync {
	private static final List<GLink.Actor> ACTORS = new ArrayList<>();
	private static final Map<Integer, GLink.Actor> BY_ID = new HashMap<>();

	private ProxySync() {
	}

	static void frame(Minecraft minecraft) {
		if (minecraft.level == null || !ClientLink.INSTANCE.readActors(ACTORS)) {
			return;
		}
		BY_ID.clear();
		for (GLink.Actor a : ACTORS) {
			BY_ID.put(a.entId(), a);
		}
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (entity instanceof HostActorEntity proxy) {
				GLink.Actor a = BY_ID.get(proxy.entId());
				if (a == null) {
					continue;
				}
				proxy.setSize(a.width(), a.height());
				proxy.setPos(a.x(), a.y(), a.z());
				proxy.xo = a.x();
				proxy.yo = a.y();
				proxy.zo = a.z();
				proxy.setYRot(a.yaw());
				proxy.yRotO = a.yaw();
			}
		}
	}
}
