"""R1: Prism as the AppImage, a distro package (/usr/bin/prismlauncher) or the flatpak; the data folder
that goes with it; prismCommand in config.json (the shapes module/source/prismcmd.hpp accepts)."""
import json
import os
from pathlib import Path
from unittest import mock

from helpers import TempEnv, write

from gmodcraft_launcher import config, detect, platform, prewarm

FLATPAK = ["flatpak", "run", "org.prismlauncher.PrismLauncher"]
REAL_FLATPAK = platform.flatpak_prism_installed     # before TempEnv patches it


class PrismKindTest(TempEnv):
    def setUp(self):
        super().setUp()
        os.environ.pop("PRISM_DATA", None)
        os.environ["HOME"] = str(self.tmp / "home")
        os.environ["XDG_DATA_HOME"] = str(self.tmp / "home/.local/share")
        self.appimage = write(self.tmp / "home/Documents/Software/PrismLauncher-Linux-x86_64.AppImage", "#!/bin/sh\n")
        self.package = write(self.tmp / "usr/bin/prismlauncher", "#!/bin/sh\n")
        for p in (self.appimage, self.package):
            os.chmod(p, 0o755)

    def patch(self, candidates=(), flatpak=False):
        for name, val in (("prism_exe_candidates", lambda: list(candidates)), ("flatpak_prism_installed", lambda: flatpak),
                          ("prism_exe_default", lambda: self.appimage)):
            p = mock.patch.object(platform, name, val)
            p.start()
            self.addCleanup(p.stop)

    def test_appimage_package_flatpak_auto_order(self):
        self.patch([self.appimage, self.package], flatpak=True)
        inst = detect.find_prism({})
        self.assertEqual((inst.kind, inst.command), ("appimage", [str(self.appimage)]))
        self.assertEqual(inst.data, self.tmp / "home/.local/share/PrismLauncher")
        inst = detect.find_prism({"prismKind": "package"})
        self.assertEqual((inst.kind, inst.command), ("package", [str(self.package)]))
        inst = detect.find_prism({"prismKind": "flatpak"})
        self.assertEqual(inst.command, FLATPAK)
        self.assertEqual(inst.data, self.tmp / "home/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher")
        self.assertEqual(detect.prism_data_for({"prismKind": "flatpak"}), inst.data)

    def test_package_only_and_flatpak_only(self):
        self.patch([self.package])
        self.assertEqual(detect.find_prism({}).kind, "package")
        self.assertIsNone(detect.find_prism({"prismKind": "flatpak"}))
        self.patch([], flatpak=True)
        self.assertEqual(detect.find_prism({}).command, FLATPAK)

    def test_chosen_appimage_wins_and_prism_data_env(self):
        self.patch([self.package], flatpak=True)
        mine = write(self.tmp / "opt/My Prism.AppImage", "")
        self.assertEqual(detect.find_prism({"prismAppImage": str(mine)}).command, [str(mine)])
        os.environ["PRISM_DATA"] = str(self.tmp / "pd")
        self.assertEqual(detect.prism_data_for({"prismKind": "flatpak"}), self.tmp / "pd")

    def test_prism_command_saved_in_config(self):
        self.patch([self.package], flatpak=True)
        for kind, want in (("package", [str(self.package)]), ("flatpak", FLATPAK)):
            cfg = config.load()
            cfg["prismKind"] = kind
            config.save(cfg)
            data = json.loads(config.path().read_text())
            self.assertEqual(data["prismCommand"], want)
            self.assertEqual(config.load()["prismCommand"], want)
        cfg = config.load()
        cfg["prismKind"] = "appimage"     # none found: no prismCommand at all (the module falls back)
        config.save(cfg)
        self.assertNotIn("prismCommand", json.loads(config.path().read_text()))
        self.assertEqual(config.load()["prismCommand"], [])

    def test_status_and_prewarm_use_the_kind(self):
        self.patch([], flatpak=True)
        fp = write(self.tmp / "bin/flatpak", "#!/bin/sh\n")
        os.chmod(fp, 0o755)
        with mock.patch("shutil.which", side_effect=lambda n: str(fp) if n == "flatpak" else None):
            (self.tmp / "home/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher").mkdir(parents=True)
            checks = {c.label: c for c in detect.status({}, gmod=None)}
            self.assertTrue(checks["Prism Launcher"].ok, checks["Prism Launcher"].detail)
            self.assertIn("flatpak", checks["Prism Launcher"].detail)
            self.assertEqual(prewarm.command(detect.find_prism({})), [*FLATPAK, "--launch", "GmodCraft"])
            spawned = []
            with mock.patch.object(platform, "spawn_detached", side_effect=spawned.append):
                self.assertTrue(prewarm.start({}, lambda m: None))
            self.assertEqual(spawned, [[*FLATPAK, "--launch", "GmodCraft"]])

    def test_flatpak_detection_by_folder(self):
        os.environ["XDG_DATA_HOME"] = str(self.tmp / "xdg")
        (self.tmp / "xdg/flatpak/app/org.prismlauncher.PrismLauncher").mkdir(parents=True)
        self.assertTrue(REAL_FLATPAK())          # a user installation (~/.local/share/flatpak/app/...)
