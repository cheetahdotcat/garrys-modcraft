"""R1 clean install of a real release bundle into fakes: a throwaway Steam library with an x86-64 GMod tree,
a Prism data folder with an account, a fake Prism executable, temp config/data/cache dirs. No real Steam,
GMod or Prism is touched or started (the process checks are mocked by TempEnv).

  install --bundle dist/garrys-modcraft-<version>.zip  -> modules, addon and the Prism instance land in the fakes
  status                                               -> every line green
  uninstall --remove-instance --yes                    -> everything it installed is gone

Uses the newest dist/garrys-modcraft-*.zip (tools/build_release.sh) or GMODCRAFT_RELEASE_ZIP; skipped
without one. Needs Java 25 (GMODCRAFT_TEST_JAVA, else .tools/jdk25 of this or an enclosing checkout).
The pinned mods are copied from your mod cache (~/.cache/gmodcraft/mods) when it has them, else downloaded.
"""
import contextlib
import io
import json
import os
import pwd
import shutil
import unittest
from pathlib import Path

from helpers import TempEnv, write

from gmodcraft_launcher import __main__ as cli, gmod_install, platform, prism

ROOT = Path(__file__).resolve().parents[2]
ACF = '"AppState" { "appid" "4000" "installdir" "GarrysMod" "UserConfig" { "BetaKey" "x86-64" } }\n'


def release_zip():
    if os.environ.get("GMODCRAFT_RELEASE_ZIP"):
        return Path(os.environ["GMODCRAFT_RELEASE_ZIP"])
    zips = sorted((ROOT / "dist").glob("garrys-modcraft-*.zip"), key=lambda p: p.stat().st_mtime)
    return zips[-1] if zips else None


def java25():
    if os.environ.get("GMODCRAFT_TEST_JAVA"):
        return Path(os.environ["GMODCRAFT_TEST_JAVA"])
    for d in (ROOT, *ROOT.parents):
        j = d / ".tools/jdk25/bin/java"
        if j.is_file():
            return j
    return None


ZIP, JAVA = release_zip(), java25()


@unittest.skipUnless(ZIP and ZIP.is_file() and JAVA, "no dist/garrys-modcraft-*.zip (tools/build_release.sh) or no Java 25")
class ReleaseInstallTest(TempEnv):
    def setUp(self):
        super().setUp()
        steam, lib = self.tmp / "steam", self.tmp / "library"
        write(steam / "steamapps/libraryfolders.vdf", f'"libraryfolders" {{ "0" {{ "path" "{steam}" }} "1" {{ "path" "{lib}" }} }}')
        write(lib / "steamapps/appmanifest_4000.acf", ACF)
        self.gmod = lib / "steamapps/common/GarrysMod"
        for d in ("garrysmod/lua", "garrysmod/addons", "garrysmod/cfg", "bin/linux64"):
            (self.gmod / d).mkdir(parents=True)
        os.environ["GMODCRAFT_STEAM_ROOT"] = str(steam)
        os.environ["GMODCRAFT_JAVA"] = str(JAVA)
        self.pdata = Path(os.environ["PRISM_DATA"])
        write(self.pdata / "accounts.json", json.dumps({"accounts": [{"active": True, "entitlement": {"ownsMinecraft": True}}]}))
        self.prism_exe = write(self.tmp / "bin/PrismLauncher-Linux-x86_64.AppImage", "#!/bin/sh\nexit 0\n")
        os.chmod(self.prism_exe, 0o755)
        # the pinned mods from the user's real mod cache (read-only copy), so the test needs no network
        real_cache = Path(pwd.getpwuid(os.getuid()).pw_dir) / ".cache/gmodcraft/mods"
        if real_cache.is_dir():
            shutil.copytree(real_cache, platform.mod_cache_dir(), dirs_exist_ok=True)

    def test_zip_has_no_dev_lua(self):
        import zipfile
        with zipfile.ZipFile(ZIP) as z:
            dev = [n for n in z.namelist() if n.rsplit("/", 1)[-1].startswith("test") and n.endswith(".lua")]
        self.assertEqual(dev, [])

    def cli(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            rc = cli.main(list(args))
        return rc, out.getvalue()

    def test_install_status_uninstall(self):
        rc, out = self.cli("install", "--bundle", str(ZIP), "--prism-exe", str(self.prism_exe))
        self.assertEqual(rc, 0, out)
        gm = self.gmod / "garrysmod"
        for n in ("gmcl_gmodcraft_linux64.dll", "gmsv_gmodcraft_linux64.dll"):
            self.assertTrue((gm / "lua/bin" / n).is_file(), n)
        self.assertTrue((gm / "addons/gmodcraft/lua/autorun/gmodcraft_init.lua").is_file())
        inst = prism.paths(self.pdata)["final"]
        mods = sorted(p.name for p in (prism.instance_game_dir(inst) / "mods").iterdir())
        self.assertTrue(any(m.startswith("gmodcraft-") for m in mods), mods)
        self.assertTrue(any(m.startswith("fabric-api-") for m in mods), mods)
        cfg = json.loads((platform.config_dir() / "config.json").read_text())
        self.assertEqual(cfg["prismCommand"], [str(self.prism_exe)])         # what the GMod module starts

        rc, out = self.cli("status", "--bundle", str(ZIP))
        self.assertEqual(rc, 0, out)
        bad = [ln for ln in out.splitlines() if ln.startswith("[NO ]") or ln.startswith("[ ? ]")]
        self.assertEqual(bad, [], out)

        rc, out = self.cli("uninstall", "--remove-instance", "--yes")
        self.assertEqual(rc, 0, out)
        self.assertFalse((gm / "addons/gmodcraft").exists())
        self.assertFalse(any((gm / "lua/bin").glob("gm*_gmodcraft_*")) if (gm / "lua/bin").exists() else False)
        self.assertFalse(inst.exists())
        self.assertNotIn(str(self.gmod.resolve()), gmod_install.load_state_all()["installs"])
        self.assertTrue((self.pdata / "accounts.json").is_file())             # Prism's own files untouched


if __name__ == "__main__":
    unittest.main()
