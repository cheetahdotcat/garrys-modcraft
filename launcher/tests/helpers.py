"""Fake repo checkouts, GMod trees and Prism folders in temp dirs. Nothing here touches real installs."""
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from gmodcraft_launcher import platform  # noqa: E402


def write(p, data):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, str):
        data = data.encode()
    p.write_bytes(data)
    return p


def make_repo(root, version="0.1.2", module_tag="0.3.0-p3a", addon_files=None):
    root = Path(root)
    write(root / "fabric/gradle.properties",
          f"minecraft_version=26.3\nloader_version=0.19.5\nversion={version}\nfabric_api_version=0.161.0+26.3\n")
    for k in ("cl", "sv"):
        write(root / f"module/build/gm{k}_gmodcraft_linux64.dll", b"\x7fELF fake\x00" + module_tag.encode() + b"\x00" + k.encode())
    for rel, text in (addon_files or {"lua/autorun/gmodcraft_init.lua": "-- init\n",
                                      "lua/gmodcraft/client/link.lua": "-- link\n",
                                      "README.md": "# addon\n"}).items():
        write(root / "addon/gmodcraft" / rel, text)
    write(root / f"fabric/build/libs/gmodcraft-{version}.jar", b"PK fake jar " + version.encode())
    return root


def make_gmod(root):
    g = Path(root) / "GarrysMod"
    (g / "garrysmod/lua").mkdir(parents=True)
    (g / "garrysmod/addons").mkdir(parents=True)
    (g / "bin/linux64").mkdir(parents=True)
    return g


class TempEnv(unittest.TestCase):
    """Every test gets its own config/data dirs; process checks say nothing is running."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="gmc-test-"))
        env = {"GMODCRAFT_CONFIG_DIR": str(self.tmp / "cfg"), "GMODCRAFT_DATA_DIR": str(self.tmp / "data"),
               "PRISM_DATA": str(self.tmp / "prism"), "GMOD_DIR": "", "GMODCRAFT_STEAM_ROOT": str(self.tmp / "nosteam"),
               "XDG_CACHE_HOME": str(self.tmp / "cache"), "GMODCRAFT_JAVA": ""}
        p = mock.patch.dict(os.environ, env)
        p.start()
        self.addCleanup(p.stop)
        for name in ("gmod_running", "prism_running", "java_with_arg_running"):
            q = mock.patch.object(platform, name, return_value=False)
            q.start()
            self.addCleanup(q.stop)
        # Launch progress reads the discovery file / shm segment: never the real /dev/shm in tests.
        from gmodcraft_launcher import progress
        (self.tmp / "shm" / "gmodcraft").mkdir(parents=True)
        for name, value in (("SHM_DIR", self.tmp / "shm"), ("DISCOVERY", self.tmp / "shm" / "gmodcraft" / "client.json")):
            r = mock.patch.object(progress, name, value)
            r.start()
            self.addCleanup(r.stop)
        # Pre-warm probes the Minecraft lock and looks for Prism: never the real ones in tests (the
        # user's Minecraft may hold the real lock, and nothing may start Prism).
        from gmodcraft_launcher import prewarm, quit as quit_mod
        for obj, name, value in ((prewarm, "LOCK_PATH", self.tmp / "shm" / "gmodcraft" / "minecraft-client.lock"),
                                 (quit_mod, "MARKER_DIR", self.tmp / "shm" / "gmodcraft"),
                                 (platform, "prism_exe_candidates", lambda: []),
                                 (platform, "flatpak_prism_installed", lambda: False)):
            r = mock.patch.object(obj, name, value)
            r.start()
            self.addCleanup(r.stop)
        self.addCleanup(shutil.rmtree, self.tmp, True)
