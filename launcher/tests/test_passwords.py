import json
import os
import stat
import unittest
from unittest import mock

from helpers import TempEnv, make_gmod, write

from gmodcraft_launcher import __main__ as cli, actions, passwords, platform

SERVER = "192.168.133.109:27015"


class PasswordStoreTest(TempEnv):
    def test_stored_file_is_0600_and_per_server(self):
        passwords.remember(SERVER, "s3cret pw")
        passwords.remember("play.example.org", "other")
        p = passwords.secrets_path()
        self.assertEqual(stat.S_IMODE(os.lstat(p).st_mode), 0o600)
        data = json.loads(p.read_text())
        self.assertEqual(data["servers"], {SERVER: "s3cret pw", "play.example.org:27015": "other"})
        self.assertEqual(passwords.stored(" 192.168.133.109:27015 "), "s3cret pw")
        self.assertEqual(passwords.stored("PLAY.example.org:27015"), "other")
        self.assertEqual(passwords.stored("10.0.0.1"), "")
        # config.json never holds it
        self.assertFalse(platform.config_dir().joinpath("config.json").exists())

    def test_umask_cant_widen_and_existing_mode_is_reset(self):
        p = passwords.secrets_path()
        write(p, '{"servers": {}}')
        os.chmod(p, 0o644)
        old = os.umask(0)
        try:
            passwords.remember(SERVER, "pw")
        finally:
            os.umask(old)
        self.assertEqual(stat.S_IMODE(os.lstat(p).st_mode), 0o600)

    def test_never_writes_through_a_symlink(self):
        p = passwords.secrets_path()
        target = self.tmp / "elsewhere.json"
        write(target, "not yours\n")
        p.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(target, p)                                  # planted at the destination
        os.symlink(self.tmp / "tmp-target", passwords.safeio.tmp_path(p))   # and at the temp path
        passwords.remember(SERVER, "pw")
        self.assertEqual(target.read_text(), "not yours\n")
        self.assertFalse((self.tmp / "tmp-target").exists())
        self.assertFalse(p.is_symlink())
        self.assertEqual(stat.S_IMODE(os.lstat(p).st_mode), 0o600)
        self.assertEqual(passwords.stored(SERVER), "pw")

    def test_reading_doesnt_follow_a_symlink(self):
        p = passwords.secrets_path()
        target = write(self.tmp / "elsewhere.json", json.dumps({"servers": {SERVER: "planted"}}))
        p.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(target, p)
        self.assertEqual(passwords.stored(SERVER), "")

    def test_remember_off_removes_the_entry(self):
        passwords.remember(SERVER, "pw")
        passwords.remember("other.host", "pw2")
        self.assertTrue(passwords.forget("192.168.133.109"  ":27015"))
        self.assertEqual(passwords.stored(SERVER), "")
        self.assertEqual(passwords.stored("other.host"), "pw2")
        self.assertTrue(passwords.forget("other.host:27015"))
        self.assertFalse(passwords.secrets_path().exists())    # last one gone: no file left
        self.assertFalse(passwords.forget(SERVER))

    def test_injection_refused(self):
        for bad in ['pw"; rcon_password x', "pw;quit", "pw\nconnect evil", "pw\rx", "pw\tx", "pw\x00", "pw\x1b[", "pw\x7f", ""]:
            with self.subTest(bad=bad), self.assertRaises(passwords.PasswordError):
                passwords.check(bad)
            if bad:
                with self.subTest(store=bad), self.assertRaises(passwords.PasswordError):
                    passwords.remember(SERVER, bad)
        self.assertFalse(passwords.secrets_path().exists())
        for ok in ["simple", "with space", "ümlaut-€", "a'b\\c//d$e{f}"]:
            self.assertEqual(passwords.check(ok), ok)

    def test_password_file_first_line(self):
        f = write(self.tmp / "pw.txt", "hunter2\r\nsecond line\n")
        self.assertEqual(passwords.read_password_file(f), "hunter2")
        write(f, 'evil"\n')
        with self.assertRaises(passwords.PasswordError):
            passwords.read_password_file(f)


class ConnectCfgTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.gmod = make_gmod(self.tmp)
        (self.gmod / "garrysmod/cfg").mkdir()
        os.environ["GMOD_DIR"] = str(self.gmod)
        self.cfg = self.gmod / "garrysmod/cfg/gmodcraft_connect.cfg"
        self.log = []
        steam = mock.patch.object(platform, "steam_launch_command", side_effect=lambda root, args: ["steam", "-applaunch", "4000", *args])
        steam.start()
        self.addCleanup(steam.stop)
        self.spawned = []
        spawn = mock.patch.object(platform, "spawn_detached", side_effect=lambda argv: self.spawned.append(argv))
        spawn.start()
        self.addCleanup(spawn.stop)

    def play(self, **kw):
        return actions.play({}, self.log.append, **kw)

    def test_launch_with_password_uses_the_cfg(self):
        argv = self.play(server=" " + SERVER, password="s3cret pw")
        self.assertEqual(argv, ["steam", "-applaunch", "4000", "-condebug", "-conclearlog", "+exec", "gmodcraft_connect"])
        self.assertEqual(self.spawned, [argv])
        self.assertNotIn("s3cret", " ".join(argv) + "\n".join(self.log))
        self.assertEqual(self.cfg.read_text(), f'password "s3cret pw"\nconnect {SERVER}\n')
        self.assertEqual(stat.S_IMODE(os.lstat(self.cfg).st_mode), 0o600)

    def test_launch_without_password_wipes_the_cfg(self):
        self.play(server=SERVER, password="pw")
        self.assertTrue(self.cfg.exists())
        argv = self.play(server=SERVER)
        self.assertEqual(argv[-2:], ["+connect", SERVER])
        self.assertFalse(self.cfg.exists())
        self.play(server=SERVER, password="pw")
        self.assertEqual(self.play()[-2:], ["+map", "gm_construct"])
        self.assertFalse(self.cfg.exists())

    def test_injection_refused_before_anything_is_written(self):
        with self.assertRaises(actions.ActionError):
            self.play(server=SERVER, password='x"\nquit')
        with self.assertRaises(actions.ActionError):
            self.play(server="-novid", password="pw")
        with self.assertRaises(actions.ActionError):
            self.play(server=None, password="pw")
        self.assertFalse(self.cfg.exists())
        self.assertEqual(self.spawned, [])

    def test_failed_spawn_error_not_masked_by_cleanup(self):
        with mock.patch.object(platform, "spawn_detached", side_effect=OSError("steam gone")), \
                mock.patch.object(passwords, "wipe_cfg", side_effect=OSError("cleanup failed")):
            with self.assertRaises(actions.ActionError) as cm:
                self.play(server=SERVER, password="pw")
        self.assertIn("steam gone", str(cm.exception))
        self.assertTrue(any("can't remove" in m for m in self.log))

    def test_dry_run_writes_nothing(self):
        argv = self.play(server=SERVER, password="pw", dry_run=True)
        self.assertEqual(argv[-2:], ["+exec", "gmodcraft_connect"])
        self.assertFalse(self.cfg.exists())
        self.assertEqual(self.spawned, [])

    def test_cfg_not_written_through_a_symlink(self):
        os.symlink(self.tmp / "planted", self.cfg)
        self.play(server=SERVER, password="pw")
        self.assertFalse((self.tmp / "planted").exists())
        self.assertFalse(self.cfg.is_symlink())
        # a linked cfg folder is refused outright
        os.remove(self.cfg)
        (self.gmod / "garrysmod/cfg").rmdir()
        (self.tmp / "realcfg").mkdir()
        os.symlink(self.tmp / "realcfg", self.gmod / "garrysmod/cfg")
        with self.assertRaises(actions.ActionError):
            self.play(server=SERVER, password="pw")
        self.assertEqual(list((self.tmp / "realcfg").iterdir()), [])

    def test_stale_cfg_removed_on_launcher_start(self):
        write(self.cfg, 'password "old"\nconnect 1.2.3.4\n')
        with mock.patch.object(platform, "gmod_running", return_value=True):
            self.assertFalse(actions.wipe_connect_cfg(self.log.append))   # GMod may not have read it yet
        self.assertTrue(self.cfg.exists())
        self.assertEqual(cli.main(["play", "--dry-run"]), 0)   # a dry run changes nothing
        self.assertTrue(self.cfg.exists())
        with mock.patch.object(cli, "print", create=True):
            self.assertEqual(cli.main(["status"]), 0)
        self.assertFalse(self.cfg.exists())

    def test_uninstall_removes_the_cfg(self):
        write(self.cfg, 'password "old"\n')
        actions.uninstall({}, self.log.append)
        self.assertFalse(self.cfg.exists())

    def test_cli_password_sources(self):
        f = write(self.tmp / "pw.txt", "fromfile\n")
        self.assertEqual(cli.main(["play", "--server", SERVER, "--password-file", str(f)]), 0)
        self.assertEqual(self.cfg.read_text(), f'password "fromfile"\nconnect {SERVER}\n')
        self.assertEqual(passwords.stored(SERVER), "")              # the CLI doesn't store it
        passwords.remember(SERVER, "remembered")
        self.assertEqual(cli.main(["play", "--server", SERVER]), 0)
        self.assertIn('password "remembered"', self.cfg.read_text())
        with mock.patch("getpass.getpass", return_value="typed"), mock.patch("sys.stdin.isatty", return_value=True):
            self.assertEqual(cli.main(["play", "--server", SERVER, "--ask-password"]), 0)
        self.assertIn('password "typed"', self.cfg.read_text())
        with self.assertRaises(SystemExit):
            cli.main(["play", "--server", SERVER, "--password", "x"])   # no plain --password option
        write(f, "bad;pw\n")
        self.assertEqual(cli.main(["play", "--server", SERVER, "--password-file", str(f)]), 1)


if __name__ == "__main__":
    unittest.main()
