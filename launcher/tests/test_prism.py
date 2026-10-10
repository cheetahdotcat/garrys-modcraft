import hashlib
import io
import json
import re
import os
import stat
import tarfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import TempEnv, write

from gmodcraft_launcher import platform, prism

FAKE_JAVA = '#!/bin/sh\necho \'openjdk version "25.0.4.1" 2026-07-15\' >&2\n'
FAKE_JAVA_17 = '#!/bin/sh\necho \'openjdk version "17.0.9"\' >&2\n'


def sha(data, algo):
    return hashlib.new(algo, data).hexdigest()


class PrismTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.pdata = self.tmp / "prism"
        (self.pdata / "instances").mkdir(parents=True)
        write(self.pdata / "accounts.json", '{"x": "untouched"}')
        write(self.pdata / "prismlauncher.cfg", "[General]\nuntouched=1\n")
        self.jar = write(self.tmp / "gmodcraft-0.1.2.jar", b"mod jar")
        # downloads are served from memory, with pins patched to match them
        self.blobs = {prism.FABRIC_API_URL: b"fabric api"}
        mods = []
        for prefix, url, _ in prism.EXTRA_MODS:
            self.blobs[url] = prefix.encode() * 3
            mods.append((prefix, url, sha(self.blobs[url], "sha512")))
        for name, val in (("EXTRA_MODS", mods), ("FABRIC_API_SHA512", sha(self.blobs[prism.FABRIC_API_URL], "sha512"))):
            p = mock.patch.object(prism, name, val)
            p.start()
            self.addCleanup(p.stop)
        self.fetched = []
        self.java = write(self.tmp / "jdk/bin/java", FAKE_JAVA)
        self.java.chmod(0o755)
        self.log = []

    def fetch(self, url, dest):
        self.fetched.append(url)
        Path(dest).write_bytes(self.blobs[url])

    def setup(self, **kw):
        kw.setdefault("java", self.java)
        return prism.setup_instance(self.pdata, self.jar, self.log.append, fetch=self.fetch,
                                    cache_dir=self.tmp / "modcache", gradle_cache=self.tmp / "gradle", sleep=lambda s: None, **kw)

    def test_new_instance(self):
        inst = self.setup()
        self.assertEqual(inst, self.pdata / "instances/GmodCraft")
        self.assertFalse((self.pdata / ".gmodcraft-staging").exists())
        mods = sorted(p.name for p in (inst / "minecraft/mods").iterdir())
        self.assertEqual(mods, ["fabric-api-0.161.0+26.3.jar", "gmodcraft-0.1.2.jar",
                                "jei-26.3-fabric-31.9.0.57.jar", "mezz_config-26.3-fabric-0.6.8.jar"])
        pack = json.loads((inst / "mmc-pack.json").read_text())
        self.assertEqual(pack["components"][2]["version"], "0.19.5")
        cfg = prism.read_instance_cfg(inst / "instance.cfg")
        self.assertEqual(cfg["JavaPath"], str(self.java))
        self.assertEqual(cfg["JvmArgs"], "-Dgmodcraft.startHidden=true")
        self.assertEqual(cfg["OverrideEnv"], "false")
        checks = prism.check_instance(self.pdata, {"path": "x/gmodcraft-0.1.2.jar", "sha256": sha(b"mod jar", "sha256")})
        self.assertTrue(all(ok for _, ok, _ in checks), checks)
        # Prism's own files untouched
        self.assertEqual((self.pdata / "accounts.json").read_text(), '{"x": "untouched"}')
        self.assertEqual((self.pdata / "prismlauncher.cfg").read_text(), "[General]\nuntouched=1\n")
        # second run uses the cache, no downloads
        self.fetched.clear()
        self.setup()
        self.assertEqual(self.fetched, [])

    def test_update_keeps_prism_keys_and_replaces_old_jars(self):
        inst = self.pdata / "instances/GmodCraft"
        write(inst / "instance.cfg", "[General]\nname=GmodCraft\ntotalTimePlayed=99\nEnv=\"{}\"\n\n[UI]\nmods_Page\\Columns=abc\nEnv=stray\n")
        write(inst / "minecraft/mods/gmodcraft-0.1.0.jar", b"old")
        write(inst / "minecraft/mods/jei-26.3-fabric-31.0.0.1.jar", b"old jei")
        write(inst / "minecraft/mods/sodium.jar", b"user mod")
        write(inst / "minecraft/saves/world/level.dat", b"world")
        with mock.patch.object(platform, "prism_running", return_value=True):
            self.setup(gl=True)
        text = (inst / "instance.cfg").read_text()
        self.assertIn("totalTimePlayed=99", text)
        self.assertIn("[UI]\nmods_Page\\Columns=abc", text)
        self.assertNotIn("Env=stray", text)
        self.assertIn('Env="{\\"SDL_VIDEO_FORCE_EGL\\":\\"1\\"}"', text)
        mods = sorted(p.name for p in (inst / "minecraft/mods").iterdir())
        self.assertNotIn("gmodcraft-0.1.0.jar", mods)
        self.assertNotIn("jei-26.3-fabric-31.0.0.1.jar", mods)
        self.assertIn("sodium.jar", mods)
        self.assertTrue((inst / "minecraft/saves/world/level.dat").is_file())
        self.assertFalse((self.pdata / ".gmodcraft-reload").exists())

    def test_update_preserves_env_and_jvm_args(self):
        inst = self.pdata / "instances/GmodCraft"
        env = '"{\\"GMODCRAFT_LINK\\":\\"gmodcraft-client-test\\"}"'
        write(inst / "instance.cfg", f'[General]\nname=GmodCraft\nOverrideEnv=true\nEnv={env}\n'
                                     'JvmArgs="-Xss4m -Dfoo=\\"a b\\""\n')
        self.setup()   # no env option in the launcher: Env untouched, JVM args kept + ours added
        cfg = prism.read_instance_cfg(inst / "instance.cfg")
        text = (inst / "instance.cfg").read_text()
        self.assertIn(f"Env={env}\n", text)
        self.assertEqual(cfg["OverrideEnv"], "true")
        self.assertEqual(cfg["JvmArgs"], '-Xss4m -Dfoo="a b" -Dgmodcraft.startHidden=true')
        self.setup()   # idempotent: our arg isn't added twice
        self.assertEqual((inst / "instance.cfg").read_text(), text)
        self.setup(gl=True)   # the GL option merges into the existing Env
        cfg = prism.read_instance_cfg(inst / "instance.cfg")
        self.assertEqual(json.loads(cfg["Env"]),
                         {"GMODCRAFT_LINK": "gmodcraft-client-test", "SDL_VIDEO_FORCE_EGL": "1"})
        self.setup(gl=False)  # --no-gl removes only that variable
        cfg = prism.read_instance_cfg(inst / "instance.cfg")
        self.assertEqual(json.loads(cfg["Env"]), {"GMODCRAFT_LINK": "gmodcraft-client-test"})
        self.assertEqual(cfg["OverrideEnv"], "true")

    def test_no_gl_clears_last_variable(self):
        inst = self.setup(gl=True)
        self.setup(gl=False)
        cfg = prism.read_instance_cfg(inst / "instance.cfg")
        self.assertNotIn("Env", cfg)
        self.assertEqual(cfg["OverrideEnv"], "false")

    def test_pins_match_setup_prism_sh(self):
        script = Path(__file__).resolve().parents[2] / "tools/setup_prism.sh"
        if not script.is_file():
            self.skipTest("no tools/setup_prism.sh")
        text = script.read_text()

        def var(name):
            return re.search(rf"^{name}=(\S+)$", text, re.M).group(1).strip('"')
        from gmodcraft_launcher.pins import PINS
        self.assertEqual(var("MC_VERSION"), PINS["minecraft"])
        self.assertEqual(var("LOADER_VERSION"), PINS["fabric_loader"])
        self.assertEqual(var("FABRIC_API_VERSION"), PINS["fabric_api"]["version"])
        tem = PINS["temurin"]["linux-x86_64"]
        self.assertEqual((var("TEMURIN_VERSION"), var("TEMURIN_URL"), var("TEMURIN_SHA256")),
                         (tem["version"], tem["url"], tem["sha256"]))
        mods = re.findall(r'^\s*"([^|"]+)\|([^|"]+)\|([0-9a-f]+)"$', text, re.M)
        self.assertEqual(mods, [(m["prefix"], m["url"], m["sha512"]) for m in PINS["mods"]])

    def test_remove_instance(self):
        inst = self.setup()
        write(inst / "minecraft/saves/w/level.dat", b"world")
        prism.remove_instance(self.pdata, self.log.append, dry_run=True)
        self.assertTrue(inst.is_dir())
        for running in ("java_with_arg_running", "prism_running"):
            with mock.patch.object(platform, running, return_value=True):
                with self.assertRaises(prism.PrismError):
                    prism.remove_instance(self.pdata, self.log.append)
        self.assertTrue(inst.is_dir())
        prism.remove_instance(self.pdata, self.log.append)
        self.assertFalse(inst.exists())
        self.assertTrue((self.pdata / "accounts.json").exists())

    def test_pins_file(self):
        from gmodcraft_launcher.pins import PINS
        self.assertEqual(prism.MC_VERSION, PINS["minecraft"])
        self.assertIn("linux-x86_64", PINS["temurin"])
        self.assertEqual([m["prefix"] for m in PINS["mods"]], ["jei-", "mezz_config-"])

    def test_recovers_interrupted_reload(self):
        write(self.pdata / ".gmodcraft-reload/instance.cfg", "[General]\nname=GmodCraft\n")
        self.setup()
        self.assertFalse((self.pdata / ".gmodcraft-reload").exists())
        self.assertIn("recovering", "\n".join(self.log))
        write(self.pdata / ".gmodcraft-reload/instance.cfg", "x")
        with self.assertRaisesRegex(prism.PrismError, "both"):
            self.setup()

    def test_wrong_java_and_bad_download(self):
        j17 = write(self.tmp / "j17/bin/java", FAKE_JAVA_17)
        j17.chmod(0o755)
        with self.assertRaisesRegex(prism.PrismError, "not Java 25"):
            self.setup(java=j17)
        self.assertFalse((self.pdata / "instances/GmodCraft").exists())
        self.blobs[prism.EXTRA_MODS[0][1]] = b"tampered"
        with self.assertRaisesRegex(prism.PrismError, "sha512"):
            prism.setup_instance(self.pdata, self.jar, self.log.append, java=self.java, fetch=self.fetch,
                                 cache_dir=self.tmp / "other-cache", gradle_cache=self.tmp / "gradle")

    def test_temurin_download_checked_and_unpacked(self):
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tf:
            data = FAKE_JAVA.encode()
            ti = tarfile.TarInfo("jdk-25.0.4.1+1-jre/bin/java")
            ti.size, ti.mode = len(data), 0o755
            tf.addfile(ti, io.BytesIO(data))
        pin = {"version": "25-test", "url": "https://example.invalid/jre.tgz", "sha256": sha(buf.getvalue(), "sha256")}
        self.blobs[pin["url"]] = buf.getvalue()
        with mock.patch.object(platform, "temurin_pin", return_value=pin):
            inst = self.setup(java=None)
        java = self.pdata / "java/gmodcraft-temurin-25/bin/java"
        self.assertTrue(java.stat().st_mode & stat.S_IXUSR)
        self.assertEqual(prism.read_instance_cfg(inst / "instance.cfg")["JavaPath"], str(java))
        # a wrong hash is refused before unpacking
        import shutil
        shutil.rmtree(self.pdata / "java")
        with mock.patch.object(platform, "temurin_pin", return_value=dict(pin, sha256="0" * 64)):
            with self.assertRaisesRegex(prism.PrismError, "sha256"):
                self.setup(java=None)
        self.assertFalse((self.pdata / "java/gmodcraft-temurin-25").exists())

    def test_dry_run_writes_nothing(self):
        before = sorted(str(p) for p in self.tmp.rglob("*"))
        prism.setup_instance(self.pdata, self.jar, self.log.append, dry_run=True, fetch=self.fetch)
        self.assertEqual(sorted(str(p) for p in self.tmp.rglob("*")), before)
        self.assertEqual(self.fetched, [])

    def test_accounts_flags_only(self):
        acc = self.pdata / "accounts.json"
        acc.write_text(json.dumps({"formatVersion": 3, "accounts": [
            {"entitlement": {"ownsMinecraft": False}, "msa": {"token": "SECRET"}},
            {"active": True, "entitlement": {"ownsMinecraft": True}, "profile": {"name": "Steve"}, "ygg": {"token": "SECRET"}}]}))
        ok, detail = prism.read_account(self.pdata)
        self.assertTrue(ok)
        self.assertNotIn("SECRET", detail)
        self.assertNotIn("Steve", detail)
        acc.write_text(json.dumps({"accounts": [{"entitlement": {"ownsMinecraft": True}}]}))
        self.assertFalse(prism.read_account(self.pdata)[0])
        acc.unlink()
        self.assertFalse(prism.read_account(self.pdata)[0])


if __name__ == "__main__":
    unittest.main()
