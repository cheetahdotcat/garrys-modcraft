package dev.gmodcraft.prop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gmodcraft.link.Proto;
import dev.gmodcraft.weapon.Fnv;
import io.netty.buffer.ByteBuf;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * The {@code gmodcraft:prop} item component (P1, protocol v33): which GMod prop a
 * {@code gmodcraft:gmod_prop} stack stands for. Canonical on construction (lowercase model with
 * forward slashes, cut fields), so two props that look the same compare equal and stack (up to
 * kPropMaxStack). A stack without it, or with an empty model, is inert.
 *
 * @param model      GMod model path, e.g. {@code models/props_junk/wood_crate001a.mdl}, at most kPropModelMaxBytes
 * @param skin       0..65535
 * @param bodygroups one base-36 digit per body group (Entity:SetBodyGroups), at most kPropBodygroupsMaxBytes
 * @param color      RGBA, r in the top byte (0xFFFFFFFF: untinted)
 * @param material   override material ("" none), at most kPropMaterialMaxBytes
 * @param dupe       optional hash of a stored dupe, lowercase hex ("" none), at most kPropDupeMaxBytes
 */
public record PropData(String model, int skin, String bodygroups, int color, String material, String dupe) {
	public static final int WHITE = 0xFFFFFFFF;

	public PropData {
		model = path(model, Proto.PROP_MODEL_MAX_BYTES);
		skin = Math.max(0, Math.min(65535, skin));
		bodygroups = filter(bodygroups, Proto.PROP_BODYGROUPS_MAX_BYTES, "0123456789abcdefghijklmnopqrstuvwxyz");
		material = path(material, Proto.PROP_MATERIAL_MAX_BYTES);
		dupe = filter(dupe, Proto.PROP_DUPE_MAX_BYTES, "0123456789abcdef");
		// "0000" and "" are the same look: trailing zero groups go, so both stack together
		int end = bodygroups.length();
		while (end > 0 && bodygroups.charAt(end - 1) == '0') {
			end--;
		}
		bodygroups = bodygroups.substring(0, end);
	}

	public static PropData of(String model) {
		return new PropData(model, 0, "", WHITE, "", "");
	}

	public static final Codec<PropData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.optionalFieldOf("model", "").forGetter(PropData::model),
		Codec.INT.optionalFieldOf("skin", 0).forGetter(PropData::skin),
		Codec.STRING.optionalFieldOf("bodygroups", "").forGetter(PropData::bodygroups),
		Codec.INT.optionalFieldOf("color", WHITE).forGetter(PropData::color),
		Codec.STRING.optionalFieldOf("material", "").forGetter(PropData::material),
		Codec.STRING.optionalFieldOf("dupe", "").forGetter(PropData::dupe)
	).apply(i, PropData::new));

	public static final StreamCodec<ByteBuf, PropData> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.stringUtf8(Proto.PROP_MODEL_MAX_BYTES), PropData::model,
		ByteBufCodecs.VAR_INT, PropData::skin,
		ByteBufCodecs.stringUtf8(Proto.PROP_BODYGROUPS_MAX_BYTES), PropData::bodygroups,
		ByteBufCodecs.INT, PropData::color,
		ByteBufCodecs.stringUtf8(Proto.PROP_MATERIAL_MAX_BYTES), PropData::material,
		ByteBufCodecs.stringUtf8(Proto.PROP_DUPE_MAX_BYTES), PropData::dupe,
		PropData::new);

	/** The model hash (FNV-1a 32, as GMod's HS.Hash): keys the icon GMod sends (kColWeaponIcon). */
	public int hash() {
		return Fnv.hash32(this.model);
	}

	public boolean inert() {
		return this.model.isEmpty();
	}

	/** The model's file name without folder and ".mdl" (the item name). */
	public String shortName() {
		int slash = this.model.lastIndexOf('/');
		String n = this.model.substring(slash + 1);
		return n.endsWith(".mdl") ? n.substring(0, n.length() - 4) : n;
	}

	/** The prop text: "model\0material\0bodygroups\0dupe" (UTF-8 / ASCII), at most kPropTextMaxBytes. */
	public byte[] text() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (String part : new String[] { this.model, this.material, this.bodygroups, this.dupe }) {
			byte[] b = part.getBytes(StandardCharsets.US_ASCII);
			out.write(b, 0, b.length);
			out.write(0);
		}
		return out.toByteArray();
	}

	/** A kHostEvPropResult give: its text (NUL-padded) plus the numeric fields; null when the model is missing. */
	public static @Nullable PropData parse(byte[] text, int skin, int color) {
		String[] parts = new String[4];
		int at = 0;
		for (int k = 0; k < 4; k++) {
			int end = at;
			while (end < text.length && text[end] != 0) {
				end++;
			}
			parts[k] = at < text.length ? new String(text, at, end - at, StandardCharsets.ISO_8859_1) : "";
			at = end + 1;
		}
		PropData d = new PropData(parts[0], skin, parts[2], color, parts[1], parts[3]);
		return d.inert() ? null : d;
	}

	/** Lowercase, '\\' to '/', printable ASCII only (no spaces become '_'), no "..", cut to {@code max}. */
	static String path(@Nullable String s, int max) {
		if (s == null) {
			return "";
		}
		StringBuilder b = new StringBuilder(Math.min(s.length(), max));
		for (int k = 0; k < s.length() && b.length() < max; k++) {
			char c = s.charAt(k);
			if (c == '\\') {
				c = '/';
			}
			b.append(c >= 0x20 && c < 0x7F ? c : '_');
		}
		String r = b.toString().toLowerCase(Locale.ROOT);
		return r.contains("..") ? "" : r;
	}

	private static String filter(@Nullable String s, int max, String allowed) {
		if (s == null) {
			return "";
		}
		StringBuilder b = new StringBuilder();
		String l = s.toLowerCase(Locale.ROOT);
		for (int k = 0; k < l.length() && b.length() < max; k++) {
			char c = l.charAt(k);
			if (allowed.indexOf(c) >= 0) {
				b.append(c);
			}
		}
		return b.toString();
	}
}
