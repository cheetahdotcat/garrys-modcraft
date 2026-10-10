package dev.gmodcraft.weapon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gmodcraft.link.Proto;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The {@code gmodcraft:swep} item component (hybrid mode, protocol v17): which GMod weapon a
 * {@code gmodcraft:gmod_weapon} stack stands for, and the clips it was put away with. A stack
 * without it is inert. Persisted in the world save and synced to clients (name, tooltip, sprite).
 *
 * @param weaponClass lowercase GMod class (e.g. {@code weapon_pistol}), at most kWeaponClassMaxBytes
 * @param printName   what GMod calls it (the item name); may be empty
 * @param category    WeaponCategory (the item sprite), 0..kWeapCategories-1
 * @param clip1       primary clip, -1 = none / unknown
 * @param clip2       secondary clip, -1 = none / unknown
 */
public record SwepData(String weaponClass, String printName, int category, int clip1, int clip2) {
	public static final int MAX_PRINT_NAME = 128;
	/** custom_model_data strings[0] per WeaponCategory: the item model picks its sprite by it. */
	public static final String[] CATEGORY_NAMES = { "generic", "pistol", "smg", "rifle", "shotgun", "heavy", "melee", "tool" };

	public SwepData {
		weaponClass = sanitizeClass(weaponClass);
		printName = printName == null ? "" : printName.length() > MAX_PRINT_NAME ? printName.substring(0, MAX_PRINT_NAME) : printName;
		category = category < 0 || category >= Proto.WEAP_CATEGORIES ? Proto.WEAP_CAT_GENERIC : category;
		clip1 = Math.max(-1, clip1);
		clip2 = Math.max(-1, clip2);
	}

	public static final Codec<SwepData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.optionalFieldOf("class", "").forGetter(SwepData::weaponClass),
		Codec.STRING.optionalFieldOf("print_name", "").forGetter(SwepData::printName),
		Codec.INT.optionalFieldOf("category", 0).forGetter(SwepData::category),
		Codec.INT.optionalFieldOf("clip1", -1).forGetter(SwepData::clip1),
		Codec.INT.optionalFieldOf("clip2", -1).forGetter(SwepData::clip2)
	).apply(i, SwepData::new));

	public static final StreamCodec<ByteBuf, SwepData> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.stringUtf8(Proto.WEAPON_CLASS_MAX_BYTES), SwepData::weaponClass,
		ByteBufCodecs.stringUtf8(MAX_PRINT_NAME * 4), SwepData::printName,
		ByteBufCodecs.VAR_INT, SwepData::category,
		ByteBufCodecs.VAR_INT, SwepData::clip1,
		ByteBufCodecs.VAR_INT, SwepData::clip2,
		SwepData::new);

	/** The class hash on the wire (McWeaponSet entries, host events). */
	public int hash() {
		return Fnv.hash32(this.weaponClass);
	}

	public SwepData withClips(int c1, int c2) {
		return new SwepData(this.weaponClass, this.printName, this.category, c1, c2);
	}

	public String categoryName() {
		return CATEGORY_NAMES[this.category];
	}

	/** Lowercased, cut to kWeaponClassMaxBytes ASCII; anything outside printable ASCII becomes '_'. */
	static String sanitizeClass(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder b = new StringBuilder(Math.min(s.length(), Proto.WEAPON_CLASS_MAX_BYTES));
		for (int k = 0; k < s.length() && b.length() < Proto.WEAPON_CLASS_MAX_BYTES; k++) {
			char c = s.charAt(k);
			b.append(c > 0x20 && c < 0x7F ? c : '_');
		}
		return b.toString().toLowerCase(Locale.ROOT);
	}
}
