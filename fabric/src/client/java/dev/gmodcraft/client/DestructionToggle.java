package dev.gmodcraft.client;

import dev.gmodcraft.GmodCraft;
import dev.gmodcraft.world.SkyDig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * The pause menu's "GMod destruction" button (its top left corner, clear of the menu at any GUI
 * scale) and its setting, {@code destruction=} in config/gmodcraft.properties (see SkyDig.destruction).
 */
public final class DestructionToggle {
	private static final String KEY = "destruction";

	private DestructionToggle() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("gmodcraft.properties");
	}

	public static void register() {
		load();
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu() && minecraft.player != null) {
				Screens.getWidgets(screen).add(button(minecraft));
			}
		});
	}

	private static Button button(Minecraft minecraft) {
		// The host's server does all the digging, so in a friend's world it's their setting.
		boolean host = minecraft.hasSingleplayerServer();
		Button button = Button.builder(label(), b -> {
			SkyDig.destruction = !SkyDig.destruction;
			b.setMessage(label());
			save();
			GmodCraft.LOG.info("GmodCraft: GMod destruction {}", SkyDig.destruction ? "on" : "off");
		}).bounds(4, 4, 150, 20).tooltip(Tooltip.create(Component.literal(host
			? "Mining GMod's ground and rocks, and explosions, dig into GMod's world. Holes already dug stay either way."
			: "In a friend's world, their setting decides."))).build();
		button.active = host;
		return button;
	}

	private static Component label() {
		return Component.literal("GMod destruction: " + (SkyDig.destruction ? "On" : "Off"));
	}

	private static void load() {
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(file())) {
			props.load(in);
		} catch (NoSuchFileException e) {
			return;
		} catch (IOException e) {
			GmodCraft.LOG.warn("GmodCraft: couldn't read {}", file(), e);
			return;
		}
		SkyDig.destruction = !"false".equalsIgnoreCase(props.getProperty(KEY, "true").trim());
	}

	/** Rewrites only its own line, keeping the file's comments and other settings (join=). */
	private static void save() {
		Path file = file();
		try {
			// the same locked, atomic rewrite as the server rules (kAdminSetRules): one file, two threads
			dev.gmodcraft.GmodCraftConfig.writeKeys(java.util.Map.of(KEY, Boolean.toString(SkyDig.destruction)));
		} catch (IOException e) {
			GmodCraft.LOG.warn("GmodCraft: couldn't save {}", file, e);
		}
	}
}
