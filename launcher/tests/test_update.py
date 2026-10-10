"""L4 update check: fake fetches only, never the network."""
import hashlib
import io
import os
import sys
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

from helpers import TempEnv

from gmodcraft_launcher import __version__, config, gmod_install, platform, update

V = update.parse_version


def rel(tag, prerelease=False, draft=False, assets=True):
    ver = update.strip_v(tag)
    names = [f"garrys-modcraft-{ver}.zip", "gmodcraft-launcher.pyz", "SHA256SUMS"] if assets else []
    return {"tag_name": tag, "prerelease": prerelease, "draft": draft,
            "assets": [{"name": n, "browser_download_url": f"https://github.com/{update.REPO}/releases/download/{tag}/{n}"}
                       for n in names]}


class VersionTest(unittest.TestCase):
    def test_ordering(self):
        order = ["0.4.9", "0.5.0-beta1", "0.5.0-beta2", "0.5.0-beta10", "0.5.0-rc1", "0.5.0", "v0.5.1", "0.10.0", "1.0.0"]
        keys = [V(v) for v in order]
        self.assertTrue(all(k is not None for k in keys))
        self.assertEqual(keys, sorted(keys))
        self.assertEqual(V("v0.5.0"), V("0.5.0"))
        self.assertIsNone(V("latest"))
        self.assertIsNone(V("0.5"))
        self.assertTrue(update.newer("0.5.1", "0.5.0"))
        self.assertTrue(update.newer("0.5.0", "0.5.0-beta2"))
        self.assertFalse(update.newer("0.5.0-beta2", "0.5.0"))
        self.assertFalse(update.newer("0.5.0", "0.5.0"))
        self.assertTrue(update.newer("0.5.0", None))

    def test_betas_setting(self):
        rels = [rel("v0.5.0"), rel("v0.6.0-beta1", prerelease=True), rel("v0.7.0", draft=True), rel("nightly")]
        self.assertEqual(update.pick_release(rels)["tag_name"], "v0.5.0")
        self.assertEqual(update.pick_release(rels, include_betas=True)["tag_name"], "v0.6.0-beta1")
        # a "-beta" tag not flagged as pre-release still counts as a beta
        self.assertEqual(update.pick_release([rel("v0.5.0"), rel("v0.6.0-beta1")])["tag_name"], "v0.5.0")
        self.assertIsNone(update.pick_release([rel("v0.6.0-beta1", prerelease=True)]))
        self.assertIsNone(update.pick_release([]))


class UrlTest(unittest.TestCase):
    def test_allowlist(self):
        ok = ["https://github.com/a/b/releases/download/v1/x.zip", "https://api.github.com/repos/a/b/releases",
              "https://objects.githubusercontent.com/x", "https://release-assets.githubusercontent.com/x?sig=1",
              "https://GitHub.com/x"]
        bad = ["http://github.com/x", "https://evil.com/x", "https://github.com.evil.com/x", "https://user@github.com/x",
               "https://github.com:8443/x", "file:///etc/passwd", "ftp://github.com/x", "https://raw.githubusercontent.com/x"]
        for u in ok:
            self.assertEqual(update.check_url(u), u)
        for u in bad:
            with self.assertRaises(update.UpdateError, msg=u):
                update.check_url(u)

    def test_redirect_checked(self):
        h = update._AllowlistRedirect()
        req = urllib.request.Request("https://github.com/x")
        with self.assertRaises(update.UpdateError):
            h.redirect_request(req, None, 302, "Found", {}, "https://evil.example/x")
        self.assertIsNotNone(h.redirect_request(req, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/y"))

    def test_size_cap(self):
        class R:
            def __init__(self, data, length=None):
                self.buf = io.BytesIO(data)
                self.headers = {"Content-Length": length} if length else {}

            def read(self, n):
                return self.buf.read(n)
        with self.assertRaises(update.UpdateError):
            update._read_capped(R(b"x", str(update.MAX_DOWNLOAD + 1)), io.BytesIO(), update.MAX_DOWNLOAD)
        with self.assertRaises(update.UpdateError):
            update._read_capped(R(b"x" * 101), io.BytesIO(), 100)
        out = io.BytesIO()
        self.assertEqual(update._read_capped(R(b"x" * 100), out, 100), 100)


class CheckTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.calls = []
        self.lines = []

    def fetch(self, releases):
        def f(url):
            self.calls.append(url)
            if isinstance(releases, Exception):
                raise releases
            return releases
        return f

    def check(self, releases, cfg=None, **kw):
        c = dict(config.DEFAULTS)
        c.update(cfg or {})
        kw.setdefault("installed", "0.5.0")
        return update.check(c, self.lines.append, fetch_json=self.fetch(releases), **kw), c

    def test_offer_and_up_to_date(self):
        offer, cfg = self.check([rel("v0.5.0"), rel("v0.5.1")], force=True, installed="0.5.0")
        self.assertEqual(self.calls, [update.API_URL])
        self.assertEqual(offer["version"], "0.5.1")
        self.assertTrue(offer["bundle"])
        self.assertEqual(offer["launcher"], update.newer("0.5.1", __version__))
        self.assertEqual(set(offer["assets"]), {"garrys-modcraft-0.5.1.zip", "gmodcraft-launcher.pyz", "SHA256SUMS"})
        self.assertGreater(cfg["updateLastCheck"], 0)
        with mock.patch.object(update, "__version__", "0.5.0"):
            offer, _ = self.check([rel("v0.5.0"), rel("v0.5.0-beta2", prerelease=True)], force=True)
        self.assertIsNone(offer)
        self.assertIn("up to date", self.lines[-1])

    def test_betas_in_check(self):
        rels = [rel("v0.5.0"), rel("v0.5.1-beta1", prerelease=True)]
        with mock.patch.object(update, "__version__", "0.5.0"):
            self.assertIsNone(self.check(rels, force=True)[0])
            self.assertEqual(self.check(rels, {"updateBetas": True}, force=True)[0]["version"], "0.5.1-beta1")

    def test_launcher_only_and_dev_source(self):
        with mock.patch.object(update, "__version__", "0.5.0"):
            offer, _ = self.check([rel("v0.6.0")], {"source_kind": "dev"}, force=True, installed="0.6.0")
            self.assertFalse(offer["bundle"])
            self.assertTrue(offer["launcher"])
            offer, _ = self.check([rel("v0.6.0")], force=True, installed="0.6.0")
            self.assertFalse(offer["bundle"])

    def test_installed_version_from_state(self):
        st = gmod_install.load_state_all()
        st["installs"] = {"/a": {"version": "0.5.0-beta2", "files": {}}, "/b": {"version": "0.5.0", "files": {}}}
        config.write_json_atomic(gmod_install.state_path(), st)
        self.assertEqual(update.installed_version(), "0.5.0")

    def test_rate_limit(self):
        now = 1_000_000_000
        _, cfg = self.check([], now=now)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(cfg["updateLastCheck"], now)
        self.assertFalse(update.due(cfg, now + update.CHECK_INTERVAL_S - 1))
        self.assertIsNone(update.check(cfg, self.lines.append, fetch_json=self.fetch([]), now=now + 3600))
        self.assertEqual(len(self.calls), 1)                      # within 6 h: no request
        update.check(cfg, self.lines.append, fetch_json=self.fetch([]), now=now + 3600, force=True)
        self.assertEqual(len(self.calls), 2)                      # the button bypasses it
        self.assertTrue(update.due(cfg, now + 3600 + update.CHECK_INTERVAL_S))
        self.assertTrue(update.due({"updateLastCheck": now + 10_000}, now))   # clock went back: check
        self.assertFalse(update.due({"updateCheck": False, "updateLastCheck": 0}, now))
        _, off = self.check([rel("v9.0.0")], {"updateCheck": False}, now=now)
        self.assertEqual(len(self.calls), 2)                      # setting off: never on start
        self.assertEqual(off["updateLastCheck"], 0)

    def test_failure_recorded_and_quiet(self):
        offer, cfg = self.check(OSError("network down"), now=5_000_000)
        self.assertIsNone(offer)
        self.assertEqual(cfg["updateLastCheck"], 5_000_000)       # no retry on every start
        self.assertIn("update check failed", self.lines[-1])

    def test_no_releases(self):
        for answer in ([], None):
            self.lines.clear()
            offer, _ = self.check(answer, force=True)
            self.assertIsNone(offer)
            self.assertIn("no releases", self.lines[-1])
        offer, _ = self.check([rel("v0.6.0-beta1", prerelease=True)], force=True)
        self.assertIsNone(offer)
        offer, _ = self.check({"message": "rate limited"}, force=True)
        self.assertIsNone(offer)

    def test_missing_assets_not_offered(self):
        offer, _ = self.check([rel("v9.0.0", assets=False)], force=True)
        self.assertIsNone(offer)
        self.assertIn("lacks the asset", self.lines[-1])

    def test_bad_asset_url_not_offered(self):
        r = rel("v9.0.0")
        r["assets"][0]["browser_download_url"] = "https://evil.example/garrys-modcraft-9.0.0.zip"
        self.assertIsNone(self.check([r], force=True)[0])
        self.assertIn("allowlist", self.lines[-1])


class ApplyTest(TempEnv):
    VER = "9.0.0"

    def setUp(self):
        super().setUp()
        self.lines = []
        self.zip_bytes = b"PK fake bundle 9.0.0"
        self.pyz_bytes = b"#!/usr/bin/env python3\nnew launcher 9.0.0\n"
        self.sums = (f"{hashlib.sha256(self.zip_bytes).hexdigest()}  garrys-modcraft-{self.VER}.zip\n"
                     f"{hashlib.sha256(self.pyz_bytes).hexdigest()}  gmodcraft-launcher.pyz\n")
        self.fetched = []

    def offer(self, launcher=True):
        r = rel("v" + self.VER)
        return {"version": self.VER, "tag": "v" + self.VER, "bundle": True, "launcher": launcher,
                "assets": update.release_assets(r)}

    def fetch_file(self, overrides=None):
        files = {f"garrys-modcraft-{self.VER}.zip": self.zip_bytes, "gmodcraft-launcher.pyz": self.pyz_bytes,
                 "SHA256SUMS": self.sums.encode()}
        files.update(overrides or {})

        def f(url, out, limit):
            name = url.rsplit("/", 1)[1]
            self.fetched.append(name)
            out.write(files[name])
        return f

    def old_pyz(self):
        p = self.tmp / "bin" / "gmodcraft-launcher.pyz"
        p.parent.mkdir(parents=True)
        p.write_bytes(b"old launcher")
        os.chmod(p, 0o755)
        return p

    def test_verified_install_and_pyz_replace(self):
        pyz = self.old_pyz()
        res = update.apply(self.offer(), self.lines.append, fetch_file=self.fetch_file(), pyz=pyz)
        self.assertEqual(res["zip"], platform.data_dir() / "releases" / f"garrys-modcraft-{self.VER}.zip")
        self.assertEqual(res["zip"].read_bytes(), self.zip_bytes)
        self.assertTrue(res["restart"])
        self.assertEqual(pyz.read_bytes(), self.pyz_bytes)
        self.assertEqual(os.stat(pyz).st_mode & 0o777, 0o755)          # exec bit kept
        self.assertEqual(sorted(p.name for p in pyz.parent.iterdir()), [pyz.name])   # no temp left
        self.assertEqual(sorted(p.name for p in res["zip"].parent.iterdir()), [res["zip"].name])
        # a second run reuses the verified zip
        self.fetched.clear()
        update.apply(self.offer(launcher=False), self.lines.append, fetch_file=self.fetch_file(), pyz=pyz)
        self.assertNotIn(f"garrys-modcraft-{self.VER}.zip", self.fetched)

    def test_running_pyz_detection(self):
        pyz = self.old_pyz()
        with mock.patch.object(sys, "argv", [str(pyz)]):
            self.assertEqual(update.running_pyz(), pyz.resolve())
            res = update.apply(self.offer(), self.lines.append, fetch_file=self.fetch_file())
        self.assertTrue(res["restart"])
        self.assertEqual(pyz.read_bytes(), self.pyz_bytes)

    def test_dev_checkout_never_replaced(self):
        here = Path(update.__file__).resolve().parents[1] / "gmodcraft_launcher" / "__main__.py"
        before = here.read_bytes()
        with mock.patch.object(sys, "argv", [str(here)]):
            self.assertIsNone(update.running_pyz())
            res = update.apply(self.offer(), self.lines.append, fetch_file=self.fetch_file())
        self.assertFalse(res["restart"])
        self.assertEqual(here.read_bytes(), before)
        self.assertTrue(any("doesn't replace itself" in line for line in self.lines))

    def test_hash_mismatch_refused(self):
        pyz = self.old_pyz()
        with self.assertRaises(update.UpdateError) as cm:
            update.apply(self.offer(), self.lines.append, pyz=pyz,
                         fetch_file=self.fetch_file({f"garrys-modcraft-{self.VER}.zip": b"tampered"}))
        self.assertIn("mismatch", str(cm.exception))
        self.assertEqual(pyz.read_bytes(), b"old launcher")
        self.assertFalse((platform.data_dir() / "releases" / f"garrys-modcraft-{self.VER}.zip").exists())
        with self.assertRaises(update.UpdateError) as cm:
            update.apply(self.offer(), self.lines.append, pyz=pyz,
                         fetch_file=self.fetch_file({"gmodcraft-launcher.pyz": b"tampered"}))
        self.assertIn("mismatch", str(cm.exception))
        self.assertEqual(pyz.read_bytes(), b"old launcher")
        self.assertEqual(sorted(p.name for p in pyz.parent.iterdir()), [pyz.name])

    def test_missing_sums_entry_refused(self):
        pyz = self.old_pyz()
        only_zip = self.sums.splitlines()[0] + "\n"
        with self.assertRaises(update.UpdateError) as cm:
            update.apply(self.offer(), self.lines.append, pyz=pyz, fetch_file=self.fetch_file({"SHA256SUMS": only_zip.encode()}))
        self.assertIn("no entry", str(cm.exception))
        self.assertEqual(pyz.read_bytes(), b"old launcher")

    def test_bad_url_refused_before_download(self):
        offer = self.offer()
        offer["assets"]["SHA256SUMS"] = "http://github.com/x/SHA256SUMS"
        with self.assertRaises(update.UpdateError):
            update.apply(offer, self.lines.append, fetch_file=self.fetch_file())
        self.assertEqual(self.fetched, [])

    def test_parse_sums(self):
        h = "a" * 64
        self.assertEqual(update.parse_sums(f"{h}  a.zip\n{h.upper()} *b.pyz\njunk\n\n"), {"a.zip": h, "b.pyz": h})


if __name__ == "__main__":
    unittest.main()
