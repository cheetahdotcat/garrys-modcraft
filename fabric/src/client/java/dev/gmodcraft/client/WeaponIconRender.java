package dev.gmodcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.gmodcraft.link.ClientLink;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.weapon.AmmoDisplay;
import dev.gmodcraft.weapon.GmodWeapons;
import dev.gmodcraft.weapon.SwepData;
import dev.gmodcraft.weapon.WeaponIcons;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * H3 hybrid mode, Minecraft's GUI: a gmod_weapon stack is drawn with the icon GMod sent for its
 * class (v18 kColWeaponIcon, uploaded here as a DynamicTexture per hash), else with its category
 * sprite (the item model). The held weapon's slot gets an ammo bar and "clip|reserve" from
 * HostState. Render thread only (GuiGraphicsExtractor mixin).
 */
public final class WeaponIconRender {
	private record Tex(Identifier id, int w, int h) {
	}

	private static final Map<Integer, Tex> TEXTURES = new HashMap<>();
	private static final int UPLOADS_PER_FRAME = 8;

	private WeaponIconRender() {
	}

	/** Uploads icons that arrived since the last frame (a few per frame). */
	private static void pump(Minecraft mc) {
		WeaponIcons.Icon icon;
		for (int n = 0; n < UPLOADS_PER_FRAME && (icon = WeaponIcons.pollFresh()) != null; n++) {
			NativeImage image = new NativeImage(icon.w(), icon.h(), false);
			byte[] p = icon.rgba();
			for (int y = 0; y < icon.h(); y++) {
				for (int x = 0; x < icon.w(); x++) {
					int i = (y * icon.w() + x) * 4;
					int abgr = (p[i + 3] & 0xFF) << 24 | (p[i + 2] & 0xFF) << 16 | (p[i + 1] & 0xFF) << 8 | (p[i] & 0xFF);
					image.setPixelABGR(x, y, abgr);
				}
			}
			Identifier id = Identifier.fromNamespaceAndPath("gmodcraft", "weapon_icon/" + Integer.toHexString(icon.hash()));
			final int hash = icon.hash();
			mc.getTextureManager().register(id, new DynamicTexture(() -> "gmodcraft weapon icon " + Integer.toHexString(hash), image));
			if (TEXTURES.put(hash, new Tex(id, icon.w(), icon.h())) == null) {
				dev.gmodcraft.GmodCraft.LOG.info("GmodCraft: GMod weapon icon {} ({} x {}) uploaded", Integer.toHexString(hash), icon.w(), icon.h());
			}
		}
	}

	/** Draws the stack's GMod icon at (x, y), 16 x 16. False: not a weapon with an icon (draw the item). */
	public static boolean drawIcon(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
		SwepData d = GmodWeapons.data(stack);
		// v33 (P1): a gmod_prop stack shows its model's spawn icon, keyed by the model hash on the same ring
		dev.gmodcraft.prop.PropData prop = d == null ? dev.gmodcraft.prop.GmodProps.data(stack) : null;
		if ((d == null || d.weaponClass().isEmpty()) && prop == null) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		pump(mc);
		int hash = prop != null ? prop.hash() : d.hash();
		Tex t = TEXTURES.get(hash);
		if (t == null || WeaponIcons.get(hash) == null) {
			return false;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0.0F, 0.0F, 16, 16, t.w(), t.h(), t.w(), t.h());
		return true;
	}

	/** The held weapon's slot: ammo bar and "clip|reserve" (HostState, while kHostWeaponActive). */
	public static void drawAmmo(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || stack != mc.player.getMainHandItem()) {
			return;
		}
		SwepData d = GmodWeapons.data(stack);
		ClientLink.HostState hs = SkyClient.sky();
		if (d == null || (hs.hybridFlags & Proto.HOST_WEAPON_ACTIVE) == 0) {
			return;
		}
		int bar = AmmoDisplay.barPixels(hs.clip1, hs.maxClip1);
		if (bar >= 0) {
			g.fill(x + 2, y + 13, x + 2 + AmmoDisplay.BAR_WIDTH, y + 15, 0xFF000000);
			g.fill(x + 2, y + 13, x + 2 + bar, y + 14, AmmoDisplay.barColor(hs.clip1, hs.maxClip1));
		}
		String text = AmmoDisplay.text(hs.clip1, hs.ammo1);
		if (!text.isEmpty()) {
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x, y);
			pose.scale(0.5F, 0.5F);
			g.text(font, text, 1, 1, 0xFFFFFFFF, true);
			pose.popMatrix();
		}
	}
}
