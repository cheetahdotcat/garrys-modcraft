package dev.gmodcraft.client;

import dev.gmodcraft.combat.SkyCombat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;

public final class GmodCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		dev.gmodcraft.link.ClientLink.announceRunning();
		DestructionToggle.register();
		dev.gmodcraft.client.micro.MicroModel.register(); // 0.5: microblock model (parts from the block entity)
		HeldSmoothing.register(); // T2b: mobs GMod holds follow their GMod body without the 3-tick buffer
		// Multiplayer without editing files: the host opens their world to LAN (O, Open to LAN) and
		// e4mc gives them a link; friends type /join <link> in chat, and /leave to come back.
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("join")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("link", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(c -> {
						String link = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "link");
						c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Joining " + link.trim() + "..."));
						// After the chat screen has closed: this leaves the current world.
						net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.joinFriend(net.minecraft.client.Minecraft.getInstance(), link));
						return 1;
					})));
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("leave").executes(c -> {
				net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.leaveFriend(net.minecraft.client.Minecraft.getInstance()));
				return 1;
			}));
		});
		ClientTickEvents.START_CLIENT_TICK.register(SkyClient::clientTickStart);
		ClientTickEvents.END_CLIENT_TICK.register(SkyClient::clientTick);
		// Multiplayer testing on one PC: GMODCRAFT_LAN_PORT opens the world to LAN on that port as soon
		// as it's loaded, and GMODCRAFT_LAN_OFFLINE lets offline (dev) clients join it.
		// The GMod server (kHostEvOpenToLan on the server link) opens a listen-server host's world.
		// Publishing touches client state, so it runs here, on the client thread.
		dev.gmodcraft.ServerHost.setLanOpener((server, port, allowOffline, done) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
			boolean auth = server.usesAuthentication();
			boolean ok;
			try {
				if (allowOffline) {
					server.setUsesAuthentication(false);
				}
				int p = port > 0 ? port : net.minecraft.util.HttpUtil.getAvailablePort();
				ok = server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, p);
			} catch (RuntimeException e) {
				dev.gmodcraft.GmodCraft.LOG.warn("GmodCraft: opening the world to LAN failed", e);
				ok = false;
			}
			if (!ok) {
				server.setUsesAuthentication(auth);
			}
			done.accept(ok);
		}));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> MirrorWorld.onJoinedWorld(minecraft));
		// GMod's noclip (v22, N1): the server says when; a new connection starts without it.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.gmodcraft.net.SkyNet.Noclip.TYPE,
			(payload, context) -> context.client().execute(() -> {
				dev.gmodcraft.Noclip.clientLocal = payload.on();
				dev.gmodcraft.GmodCraft.LOG.info("GmodCraft: noclip {} (the server says)", payload.on() ? "on" : "off");
			}));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> dev.gmodcraft.Noclip.clientLocal = false);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> dev.gmodcraft.Noclip.clientLocal = false);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
			String port = System.getenv("GMODCRAFT_LAN_PORT");
			var server = minecraft.getSingleplayerServer();
			if (port == null || port.isBlank() || server == null || server.isPublished()) {
				return;
			}
			minecraft.execute(() -> {
				if (System.getenv("GMODCRAFT_LAN_OFFLINE") != null) {
					server.setUsesAuthentication(false);
				}
				boolean ok = server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, Integer.parseInt(port.trim()));
				dev.gmodcraft.GmodCraft.LOG.info("GmodCraft: world opened to LAN on port {} ({}{})", port.trim(), ok ? "ok" : "FAILED",
					System.getenv("GMODCRAFT_LAN_OFFLINE") != null ? ", offline logins allowed" : "");
			});
		});
		// GMod draws the real NPC; its Minecraft stand-in is only a hitbox.
		EntityRendererRegistry.register(SkyCombat.HOST_ACTOR, NoopRenderer::new);
		// (The smooth-collider predicate is installed by GmodCraft.onInitialize, per side.)
	}
}
