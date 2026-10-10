"""P8 WP3: the single-player "New world" (temp Prism folders only; never deletes a world)."""
import os
import unittest
from unittest import mock

from helpers import TempEnv, write

from gmodcraft_launcher import actions, platform, prism


class NewWorldTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.pdata = self.tmp / "prism"
        self.game = self.pdata / "instances" / prism.INSTANCE / "minecraft"
        self.world = self.game / "saves" / prism.WORLD
        write(self.world / "level.dat", b"old world")
        write(self.world / "region" / "r.0.0.mca", b"builds")
        self.cfg = write(self.game / "config" / "gmodcraft.properties",
                         "# Garry's Modcraft\ndestruction=false\njoin=\nworldType=mirror\npvp=false\n")
        p = mock.patch.object(platform, "java_with_arg_running", return_value=False)
        p.start()
        self.addCleanup(p.stop)
        self.logs = []

    def test_moves_aside_and_writes_the_type(self):
        bak = prism.new_world(self.pdata, "flat_void_maps", self.logs.append, now=1_800_000_000)
        self.assertFalse(os.path.lexists(self.world))
        self.assertTrue(bak.name.startswith(prism.WORLD + ".bak-"))
        self.assertEqual((bak / "region" / "r.0.0.mca").read_bytes(), b"builds")
        self.assertEqual((bak / "level.dat").read_bytes(), b"old world")
        text = self.cfg.read_text()
        self.assertIn("worldType=flat_void_maps\n", text)
        self.assertNotIn("worldType=mirror", text)
        for keep in ("# Garry's Modcraft", "destruction=false", "join=", "pvp=false"):
            self.assertIn(keep, text)

    def test_never_overwrites_a_backup(self):
        bak = prism.new_world(self.pdata, "flat_everywhere", self.logs.append, now=1_800_000_000)
        write(self.world / "level.dat", b"second world")
        with self.assertRaises(prism.PrismError):
            prism.new_world(self.pdata, "mirror", self.logs.append, now=1_800_000_000)  # same second: same .bak name
        self.assertEqual((self.world / "level.dat").read_bytes(), b"second world")
        self.assertEqual((bak / "level.dat").read_bytes(), b"old world")
        bak2 = prism.new_world(self.pdata, "mirror", self.logs.append, now=1_800_000_001)
        self.assertEqual((bak2 / "level.dat").read_bytes(), b"second world")
        self.assertEqual(len([d for d in os.listdir(self.game / "saves") if d.startswith(prism.WORLD + ".bak-")]), 2)

    def test_no_world_yet_only_writes_the_type(self):
        os.rename(self.world, self.tmp / "elsewhere")
        self.cfg.unlink()
        self.assertIsNone(prism.new_world(self.pdata, "flat_everywhere", self.logs.append))
        self.assertEqual(self.cfg.read_text(), "# Garry's Modcraft\nworldType=flat_everywhere\n")

    def test_refusals_change_nothing(self):
        with self.assertRaises(prism.PrismError):
            prism.new_world(self.pdata, "no_such_type", self.logs.append)
        with mock.patch.object(platform, "java_with_arg_running", return_value=True):
            with self.assertRaises(prism.PrismError):
                prism.new_world(self.pdata, "flat_void_maps", self.logs.append)
        link = self.game / "saves" / "linked"
        os.rename(self.world, link)
        os.symlink(link, self.world)
        with self.assertRaises(prism.PrismError):
            prism.new_world(self.pdata, "flat_void_maps", self.logs.append)
        self.assertTrue(self.world.is_symlink())
        self.assertIn("worldType=mirror", self.cfg.read_text())
        with self.assertRaises(prism.PrismError):
            prism.new_world(self.tmp / "no-prism", "mirror", self.logs.append)

    def test_dry_run(self):
        self.assertIsNone(prism.new_world(self.pdata, "flat_void_maps", self.logs.append, dry_run=True))
        self.assertTrue(self.world.is_dir())
        self.assertIn("worldType=mirror", self.cfg.read_text())
        self.assertTrue(any("dry run" in m for m in self.logs))

    def test_action_wraps_errors(self):
        with self.assertRaises(actions.ActionError):
            actions.new_world({}, self.logs.append, "bogus", prism_data=self.pdata)
        actions.new_world({}, self.logs.append, "flat_void_maps", prism_data=self.pdata)
        self.assertFalse(os.path.lexists(self.world))

    def test_set_properties(self):
        self.assertEqual(prism.set_properties("", {"worldType": "mirror"}), "worldType=mirror\n")
        self.assertEqual(prism.set_properties("a=1\nworldType : x\n#worldType=y\n", {"worldType": "flat_everywhere"}),
                         "a=1\nworldType=flat_everywhere\n#worldType=y\n")


if __name__ == "__main__":
    unittest.main()
