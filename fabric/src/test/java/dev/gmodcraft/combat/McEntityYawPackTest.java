package dev.gmodcraft.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gmodcraft.link.Proto;
import org.junit.jupiter.api.Test;

/** v31: the body yaw packed above the category in McEntity::category. */
class McEntityYawPackTest {
	private static int cat(int packed) {
		return packed & Proto.MC_ENT_CATEGORY_MASK;
	}

	private static int q(int packed) {
		return (packed >>> Proto.MC_ENT_YAW_SHIFT) & (Proto.MC_ENT_YAW_STEPS - 1);
	}

	@Test
	void categoryKeptInTheLowBits() {
		for (int c = 0; c <= Proto.MC_ENT_VEHICLE; c++) {
			assertEquals(c, cat(McEntities.packCategory(c, 123.4F)));
		}
	}

	@Test
	void yawQuantizedAndWrapped() {
		assertEquals(0, q(McEntities.packCategory(Proto.MC_ENT_HOSTILE, 0.0F)));
		assertEquals(256, q(McEntities.packCategory(Proto.MC_ENT_HOSTILE, 90.0F)));
		assertEquals(768, q(McEntities.packCategory(Proto.MC_ENT_HOSTILE, -90.0F)), "negative yaws wrap");
		assertEquals(512, q(McEntities.packCategory(Proto.MC_ENT_HOSTILE, 180.0F + 720.0F)), "body yaws drift past 360");
		assertEquals(0, q(McEntities.packCategory(Proto.MC_ENT_HOSTILE, 359.9F)), "rounds up to 360 = 0, never 1024");
		int packed = McEntities.packCategory(Proto.MC_ENT_PASSIVE, 45.0F);
		assertEquals(0, packed & ~0xFFFF, "fits the u16");
		assertEquals(45.0, q(packed) * 360.0 / Proto.MC_ENT_YAW_STEPS, 360.0 / Proto.MC_ENT_YAW_STEPS);
	}
}
