import json
import subprocess
import sys
import unittest
import zipfile
from pathlib import Path

from helpers import TempEnv, make_gmod, make_repo

import build_bundle
from gmodcraft_launcher import actions, bundle


class BundleTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.repo = make_repo(self.tmp / "repo")

    def test_manifest_of_checkout(self):
        m = bundle.scan_checkout(self.repo, "abc123")
        roles = sorted(f["role"] for f in m["files"])
        self.assertEqual(roles, ["addon"] * 3 + ["fabric_jar"] + ["module"] * 2)
        self.assertEqual(m["module_version"], "0.3.0-p3a")
        self.assertEqual(m["pins"], {"minecraft": "26.3", "fabric_loader": "0.19.5", "fabric_api": "0.161.0+26.3"})

    def test_dev_test_lua_left_out(self):
        repo = make_repo(self.tmp / "devrepo", addon_files={
            "lua/autorun/gmodcraft_init.lua": "-- init\n", "lua/gmodcraft/client/link.lua": "-- link\n",
            "lua/gmodcraft/client/test.lua": "-- t\n", "lua/gmodcraft/client/test_util.lua": "-- u\n",
            "lua/gmodcraft/client/test_p5a.lua": "-- p\n", "lua/gmodcraft/server/test.lua": "-- s\n",
            "lua/gmodcraft/server/testament.lua": "-- not a test? still test*: left out\n",
            "lua/gmodcraft/shared/latest.lua": "-- kept\n"})
        names = {f["path"] for f in bundle.scan_checkout(repo)["files"]}
        self.assertIn("addon/gmodcraft/lua/gmodcraft/client/link.lua", names)
        self.assertIn("addon/gmodcraft/lua/gmodcraft/shared/latest.lua", names)
        self.assertFalse([n for n in names if "/test" in n], names)

    def test_missing_outputs(self):
        (self.repo / "fabric/build/libs/gmodcraft-0.1.2.jar").unlink()
        with self.assertRaises(bundle.BundleError):
            bundle.scan_checkout(self.repo)

    def test_build_bundle_roundtrip(self):
        out = self.tmp / "dist"
        self.assertEqual(build_bundle.main(["--repo", str(self.repo), "--out", str(out)]), 0)
        z = out / "garrys-modcraft-0.1.2.zip"
        pyz = out / "gmodcraft-launcher.pyz"
        self.assertTrue(z.is_file() and pyz.is_file())
        with zipfile.ZipFile(z) as zf:
            names = set(zf.namelist())
        self.assertIn("module/build/gmcl_gmodcraft_linux64.dll", names)
        self.assertIn("addon/gmodcraft/lua/autorun/gmodcraft_init.lua", names)
        self.assertIn("fabric/build/libs/gmodcraft-0.1.2.jar", names)
        self.assertIn("launcher/gmodcraft-launcher.pyz", names)
        src = bundle.open_bundle(z, self.tmp / "unpack")
        try:
            self.assertEqual(src.version, "0.1.2")
            self.assertEqual(len(src.modules("linux64")), 2)
            self.assertTrue(src.path(src.fabric_jar()).is_file())
        finally:
            actions.close_source(src)
        # the zipapp runs on its own
        r = subprocess.run([sys.executable, str(pyz), "--version"], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertRegex(r.stdout.strip(), r"^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$")  # betas: 0.5.0-beta2

    def _rezip(self, z, mutate):
        with zipfile.ZipFile(z) as zf:
            items = {n: zf.read(n) for n in zf.namelist()}
        mutate(items)
        bad = self.tmp / "bad.zip"
        with zipfile.ZipFile(bad, "w") as zf:
            for n, d in items.items():
                zf.writestr(n, d)
        return bad

    def test_tampered_and_unsafe_bundles(self):
        z = self.tmp / "b.zip"
        bundle.write_bundle(self.repo, z)

        def tamper(items):
            items["addon/gmodcraft/README.md"] = b"evil"
        with self.assertRaisesRegex(bundle.BundleError, "sha256"):
            bundle.open_bundle(self._rezip(z, tamper), self.tmp / "u")

        def escape(items):
            m = json.loads(items[bundle.MANIFEST])
            m["files"][0]["path"] = "../../etc/x"
            items[bundle.MANIFEST] = json.dumps(m).encode()
        with self.assertRaisesRegex(bundle.BundleError, "unsafe"):
            bundle.open_bundle(self._rezip(z, escape), self.tmp / "u")

        for bad_path, role in (("addon/gmodcraft/../x", "addon"), (".", "addon"), ("addon/gmodcraft/", "addon"),
                               ("addon/gmodcraft/./a.lua", "addon"), ("addon/gmodcraft/a.lua", "module"),
                               ("lua/bin/gmcl_gmodcraft_linux64.dll", "module"), ("x.jar", "fabric_jar"),
                               ("addon/gmodcraft/a.lua", "other")):
            with self.subTest(path=bad_path, role=role):
                m = {"format": 1, "files": [{"path": bad_path, "sha256": "0" * 64, "role": role}]}
                with self.assertRaises(bundle.BundleError):
                    bundle.validate_manifest(m)

        def nomanifest(items):
            del items[bundle.MANIFEST]
        with self.assertRaises(bundle.BundleError):
            bundle.open_bundle(self._rezip(z, nomanifest), self.tmp / "u")
        self.assertEqual(list((self.tmp / "u").iterdir()), [])   # failed unpacks are cleaned up

    def test_install_from_bundle_without_repo(self):
        z = self.tmp / "b.zip"
        bundle.write_bundle(self.repo, z)
        gmod = make_gmod(self.tmp)
        log = []
        cfg = {"source_kind": "bundle", "bundle": str(z)}
        import os
        from unittest import mock
        with mock.patch.dict(os.environ, {"GMOD_DIR": str(gmod)}):
            actions.install(cfg, log.append, with_prism=False)
        self.assertTrue((gmod / "garrysmod/lua/bin/gmsv_gmodcraft_linux64.dll").is_file())
        self.assertEqual(list((self.tmp / "data/unpacked").iterdir()), [])   # unpacked copy removed

    def test_pin_mismatch_refused(self):
        props = self.repo / "fabric/gradle.properties"
        props.write_text(props.read_text().replace("loader_version=0.19.5", "loader_version=0.20.0"))
        with self.assertRaisesRegex(actions.ActionError, "pins"):
            actions.open_source({"source_kind": "dev", "dev_repo": str(self.repo)}, lambda m: None)


class PlayArgsTest(unittest.TestCase):
    def test_args(self):
        self.assertEqual(actions.game_args(None), ["+map", "gm_construct"])
        self.assertEqual(actions.game_args(" 192.168.133.109:27015 "), ["+connect", "192.168.133.109:27015"])
        self.assertEqual(actions.game_args("play.example.org"), ["+connect", "play.example.org"])
        for bad in ("a b", "x;quit", "+exec evil", "host:999999", "1.2.3.4:27015 +exec x"):
            with self.assertRaises(actions.ActionError, msg=bad):
                actions.game_args(bad)


if __name__ == "__main__":
    unittest.main()
