"""L1b: launch progress (shm header reader on fixture files, never /dev/shm), log tail/redaction/
hints, desktop entry, icon."""
import json
import os
import stat
import struct
import time
import zlib
from pathlib import Path
from unittest import mock

from helpers import TempEnv, make_gmod, write

from gmodcraft_launcher import actions, desktop, gmod_install, icon, logs, passwords, platform, progress

KV = 17


def segment(path, *, magic=progress.MAGIC, version=KV, kind=1, size=0x1000, host_hb=None, mc_hb=None,
            mc_flags=0, size_field=None):
    now = time.monotonic_ns()
    hdr = struct.pack("<IIIIQQQQQQ", magic, version, kind, 0, size if size_field is None else size_field, 0xABCD,
                      now if host_hb is None else host_hb, now if mc_hb is None else mc_hb, 7, 0)
    data = bytearray(size)
    data[:len(hdr)] = hdr
    if size >= 0x208:
        struct.pack_into("<II", data, 0x200, 2, mc_flags)
    Path(path).write_bytes(bytes(data))
    return Path(path)


class ShmReaderTest(TempEnv):
    def test_good_header_and_world_flag(self):
        p = segment(self.tmp / "gmodcraft-client-x", mc_flags=progress.MC_IN_WORLD)
        info = progress.read_link(p, expect_version=KV)
        self.assertTrue(info.ok, info.error)
        self.assertTrue(progress.fresh(info.host_age_ms) and progress.fresh(info.mc_age_ms))
        self.assertTrue(info.in_world)
        self.assertIsNone(progress.read_link(p, expect_version=KV + 1).in_world)   # other layout: not read

    def test_refusals(self):
        cases = {
            "magic": dict(magic=0x12345678),
            "kind": dict(kind=2),
            "size": dict(size_field=0x2000),
            "small": dict(size=0x20),
        }
        for what, kw in cases.items():
            with self.subTest(what):
                info = progress.read_link(segment(self.tmp / f"seg-{what}", **kw))
                self.assertFalse(info.ok)
                self.assertTrue(info.error)
        self.assertFalse(progress.read_link(self.tmp / "missing").ok)
        os.symlink(segment(self.tmp / "real"), self.tmp / "link")
        self.assertFalse(progress.read_link(self.tmp / "link").ok)              # O_NOFOLLOW

    def test_stale_heartbeats(self):
        old = time.monotonic_ns() - 9_000_000_000
        info = progress.read_link(segment(self.tmp / "s", mc_hb=old))
        self.assertTrue(progress.fresh(info.host_age_ms))
        self.assertFalse(progress.fresh(info.mc_age_ms))
        self.assertIsNone(progress.read_link(segment(self.tmp / "z", mc_hb=0)).mc_age_ms)

    def test_reader_never_writes(self):
        p = segment(self.tmp / "ro")
        os.chmod(p, 0o400)
        before = (p.read_bytes(), os.stat(p).st_mtime_ns)
        progress.read_link(p, expect_version=KV)
        self.assertEqual((p.read_bytes(), os.stat(p).st_mtime_ns), before)

    def test_discovery(self):
        d = self.tmp / "client.json"
        write(d, json.dumps({"proto": KV, "kind": "client", "shm": "gmodcraft-client-1a2b", "bytes": 4096, "nonce": "ab"}))
        self.assertEqual(progress.read_discovery(d)["shm"], "gmodcraft-client-1a2b")
        for bad in ('{"shm": "../etc/passwd"}', '{"shm": "/abs"}', '{"shm": "other-name"}', "[1]", "nope", "x" * 5000):
            write(d, bad)
            self.assertIsNone(progress.read_discovery(d), bad[:20])


class TrackerTest(TempEnv):
    def probes(self, **kw):
        self.state = dict(gmod=False, mc=False, doc=None, link=progress.LinkInfo())
        self.state.update(kw)
        return {"gmod_running": lambda: self.state["gmod"], "mc_running": lambda: self.state["mc"],
                "discovery": lambda: self.state["doc"], "link": lambda p, expect_version=None: self.state["link"],
                "shm_dir": self.tmp, "protocol": KV}

    def test_steps_in_order(self):
        t = progress.Tracker(started=0.0, probes=self.probes())
        states = lambda s: [x.state for x in s.steps]  # noqa: E731
        self.assertEqual(states(t.poll(now=1.0)), ["active", "waiting", "waiting", "waiting", "waiting"])
        self.state["gmod"] = True
        self.assertEqual(states(t.poll(now=5.0))[:2], ["done", "active"])
        self.state["doc"] = {"shm": "gmodcraft-client-1", "_mtime": time.time()}
        self.state["mc"] = True
        self.state["link"] = progress.LinkInfo(ok=True, version=KV, host_age_ms=10, mc_age_ms=20000)   # MC stale
        s = t.poll(now=30.0)
        self.assertEqual(states(s)[:4], ["done", "done", "done", "active"])
        self.state["link"] = progress.LinkInfo(ok=True, version=KV, host_age_ms=10, mc_age_ms=30, in_world=False)
        self.assertEqual(states(t.poll(now=40.0))[3:], ["done", "active"])
        s = t.poll(now=40.0 + 200)
        self.assertEqual(s.steps[4].state, "slow")
        self.state["link"].in_world = True
        s = t.poll(now=300.0)
        self.assertTrue(s.finished)
        self.assertEqual(s.steps[1].done_at, 30.0)

    def test_slow_hint_and_gone(self):
        t = progress.Tracker(started=0.0, probes=self.probes())
        s = t.poll(now=500.0)
        self.assertEqual(s.steps[0].state, "slow")
        self.state["gmod"] = True
        t.poll(now=501.0)
        self.state["gmod"] = False
        s = t.poll(now=502.0)
        self.assertTrue(s.gone and s.finished)

    def test_stale_discovery_and_other_protocol(self):
        t = progress.Tracker(started=0.0, probes=self.probes(gmod=True, doc={"shm": "gmodcraft-client-1", "_mtime": 1.0}))
        self.assertNotEqual(t.poll(now=2.0).steps[1].state, "done")          # left by an earlier session
        self.state["doc"] = {"shm": "gmodcraft-client-1", "_mtime": time.time()}
        self.state["link"] = progress.LinkInfo(ok=True, version=KV - 1, host_age_ms=1, mc_age_ms=1, in_world=None)
        s = t.poll(now=3.0)
        self.assertTrue(s.finished)
        self.assertEqual(s.steps[4].state, "skipped")

    def test_against_fixture_files(self):
        seg = segment(self.tmp / "gmodcraft-client-fx", mc_flags=progress.MC_IN_WORLD)
        disc = write(self.tmp / "client.json", json.dumps({"shm": seg.name}))
        probes = {"gmod_running": lambda: True, "mc_running": lambda: True,
                  "discovery": lambda: progress.read_discovery(disc), "shm_dir": self.tmp, "protocol": KV}
        s = progress.Tracker(probes=probes).poll()
        self.assertTrue(s.finished, [x.state for x in s.steps])


class LogsTest(TempEnv):
    def test_tail_bounded(self):
        p = write(self.tmp / "console.log", "".join(f"line {i}\n" for i in range(5000)))
        self.assertEqual(logs.tail(p, lines=3), ["line 4997", "line 4998", "line 4999"])
        t = logs.tail(p, lines=10_000, max_bytes=100)
        self.assertTrue(all(x.startswith("line ") for x in t))          # the cut first line is dropped
        self.assertLessEqual(sum(len(x) + 1 for x in t), 100)
        self.assertIsNone(logs.tail(self.tmp / "missing"))
        os.symlink(p, self.tmp / "link.log")
        self.assertIsNone(logs.tail(self.tmp / "link.log"))

    def test_redaction(self):
        cases = {
            'password "s3cret pw"': 'password "***"',
            "] password hunter2": "] password ***",
            "steam -applaunch 4000 +password hunter2 +connect x": "steam -applaunch 4000 +password *** +connect x",
            "sv_password=abc": "sv_password=***",
            'rcon_password "x"': 'rcon_password "***"',
            "Bad password.": "Bad password.",
        }
        for raw, want in cases.items():
            self.assertEqual(logs.redact(raw), want)
        self.assertEqual(logs.redact("joined with stored9pw ok", ["stored9pw"]), "joined with *** ok")
        self.assertEqual(logs.redact("a cat sat", ["a"]), "a cat sat")         # < 4 chars: not scrubbed
        for raw, want in (('"password" = "hunter2" ( def. "" )', '"password" = "***" ( def. "" )'),
                          ('"sv_password" = "x"', '"sv_password" = "***"'),
                          ('"rcon_password"="y"', '"rcon_password"="***"')):
            self.assertEqual(logs.redact(raw), want)
        passwords.remember("10.0.0.1", "zebra-42")
        self.assertIn("zebra-42", logs.known_secrets())
        self.assertNotIn("zebra-42", logs.redact("echo zebra-42", logs.known_secrets()))

    def test_hints(self):
        lines = ["[12:00] Disconnect: Bad password.",
                 "GmodCraft: /dev/shm/x: protocol mismatch (magic 52434d47 version 16 kind 1 ...)",
                 "You are not white-listed on this server!",
                 "gmodcraft: Prism AppImage not found or not executable: /x (from config)",
                 "GmodCraft: client link: GMod's heartbeat stopped (no change for 8000 ms)",
                 "[gmodcraft] binary module not loaded (err): Garry's Modcraft stays off in this realm",
                 "kicking Guest: no join token arrived within 10 s"]
        self.assertEqual([h.id for h in logs.hints(lines)],
                         ["protocol", "password", "whitelist", "prism", "link", "module", "token"])
        self.assertEqual(logs.hints(["all fine"]), [])

    def test_paths(self):
        g = make_gmod(self.tmp)
        self.assertEqual(logs.gmod_log_path(g), g / "garrysmod" / "console.log")
        self.assertEqual(logs.mc_log_path(self.tmp / "prism"),
                         self.tmp / "prism" / "instances" / "GmodCraft" / "minecraft" / "logs" / "latest.log")

    def test_launch_adds_condebug_and_never_the_password(self):
        g = make_gmod(self.tmp)
        (g / "garrysmod/cfg").mkdir()
        os.environ["GMOD_DIR"] = str(g)
        with mock.patch.object(platform, "steam_launch_command", side_effect=lambda r, a: ["steam", *a]), \
                mock.patch.object(platform, "spawn_detached"):
            argv = actions.play({}, lambda m: None, server="10.0.0.1", password="pw123")
        self.assertIn("-condebug", argv)
        self.assertEqual(argv[argv.index("-condebug") + 1], "-conclearlog")
        self.assertNotIn("pw123", " ".join(argv))


class DesktopTest(TempEnv):
    def setUp(self):
        super().setUp()
        os.environ["XDG_DATA_HOME"] = str(self.tmp / "xdg")
        self.log = []

    def test_entry_text_and_quoting(self):
        t = desktop.entry_text(["/usr/bin/python3", "/home/u/My Games/gmodcraft-launcher.pyz"])
        self.assertIn('Exec="/usr/bin/python3" "/home/u/My Games/gmodcraft-launcher.pyz"\n', t)
        self.assertIn("Type=Application\n", t)
        self.assertIn("Categories=Game;\n", t)
        # spec: quoting escapes, then string escaping doubles every backslash (literal \ -> four)
        self.assertEqual(desktop._quote('a"b$c`d\\e%'), '"a\\\\"b\\\\$c\\\\`d\\\\\\\\e%%"')
        self.assertEqual(desktop._quote("/opt/My Games/l.pyz"), '"/opt/My Games/l.pyz"')
        with self.assertRaises(desktop.DesktopError):
            desktop.entry_text(["x\ny"])

    def test_install_records_and_uninstall_removes(self):
        passwords.remember("10.0.0.1", "never-in-entry")
        with mock.patch.object(platform, "SYSTEM", "linux"):
            e = desktop.install(self.log.append, argv=["/usr/bin/python3", "/opt/l.pyz"])
        text = e.read_text()
        self.assertNotIn("never-in-entry", text)
        self.assertNotIn("password", text.lower())
        exec_line = next(ln for ln in text.splitlines() if ln.startswith("Exec="))
        self.assertEqual(exec_line, 'Exec="/usr/bin/python3" "/opt/l.pyz"')
        ipath = desktop.icon_path()
        self.assertEqual(ipath.read_bytes()[:8], b"\x89PNG\r\n\x1a\n")
        self.assertIn(f"Icon={ipath}", text)
        recorded = gmod_install.load_state_all()["desktop"]
        self.assertEqual(set(recorded), {str(e), str(ipath)})
        self.assertTrue(desktop.installed())
        desktop.uninstall(self.log.append)
        self.assertFalse(e.exists() or ipath.exists())
        self.assertNotIn("desktop", gmod_install.load_state_all())

    def test_foreign_and_changed_files_are_left_alone(self):
        write(desktop.entry_path(), "[Desktop Entry]\nName=mine\n")
        with mock.patch.object(platform, "SYSTEM", "linux"), self.assertRaises(desktop.DesktopError):
            desktop.install(self.log.append, argv=["/usr/bin/python3", "/opt/l.pyz"])
        self.assertIn("Name=mine", desktop.entry_path().read_text())
        os.remove(desktop.entry_path())
        with mock.patch.object(platform, "SYSTEM", "linux"):
            e = desktop.install(self.log.append, argv=["/usr/bin/python3", "/opt/l.pyz"])
        e.write_text(e.read_text() + "X-Edited=1\n")
        desktop.uninstall(self.log.append)
        self.assertTrue(e.exists())                                  # user-edited: kept
        self.assertFalse(desktop.icon_path().exists())

    def test_cli(self):
        from gmodcraft_launcher import __main__ as cli
        with mock.patch.object(platform, "SYSTEM", "linux"), mock.patch.object(cli, "print", create=True):
            self.assertEqual(cli.main(["install-desktop", "--force"]), 0)   # this checkout is a worktree / temp
            self.assertTrue(desktop.entry_path().is_file())
            self.assertEqual(cli.main(["uninstall-desktop"]), 0)
        self.assertFalse(desktop.entry_path().exists())

    def test_checkout_warning_and_refusal(self):
        repo = self.tmp / "repo"
        (repo / ".git").mkdir(parents=True)
        (repo / "launcher").mkdir()
        self.assertIn("/tmp", desktop.checkout_problem("/tmp/somewhere") or "")
        roots = mock.patch.object(desktop, "TEMP_ROOTS", ())        # the test dirs live under /tmp
        roots.start()
        self.addCleanup(roots.stop)
        self.assertIsNone(desktop.checkout_problem(repo / "launcher"))
        wt = self.tmp / "wt"
        (wt / "launcher").mkdir(parents=True)
        (wt / ".git").write_text("gitdir: /x/.git/worktrees/wt\n")
        self.assertIn("worktree", desktop.checkout_problem(wt / "launcher"))
        argv = ["/usr/bin/python3", "-m", "gmodcraft_launcher"]
        with mock.patch.object(platform, "SYSTEM", "linux"):
            with self.assertRaises(desktop.DesktopError):
                desktop.install(self.log.append, argv=argv, workdir=wt / "launcher")
            self.assertFalse(desktop.entry_path().exists())
            desktop.install(self.log.append, argv=argv, workdir=wt / "launcher", force=True)
            self.assertIn(f"Path={wt / 'launcher'}", desktop.entry_path().read_text())
            self.assertTrue(any("runs the launcher from this checkout" in m for m in self.log))
            desktop.uninstall(self.log.append)
            desktop.install(self.log.append, argv=argv, workdir=repo / "launcher")    # a main checkout: fine

    def test_default_command_has_no_options(self):
        argv, _wd = desktop.launcher_command()
        self.assertTrue(all("pass" not in a.lower() for a in argv))
        self.assertFalse(any(a.startswith("--") for a in argv))


class IconTest(TempEnv):
    def test_png_is_valid(self):
        data = icon.png(64)
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        w, h = struct.unpack(">II", data[16:24])
        self.assertEqual((w, h), (64, 64))
        idat = data[data.index(b"IDAT") + 4:data.index(b"IEND") - 8]
        self.assertEqual(len(zlib.decompress(idat)), 64 * (1 + 64 * 4))
        self.assertTrue(all(len(r) == 16 for r in icon.ART))
        self.assertTrue(stat.S_IMODE(0o644))
