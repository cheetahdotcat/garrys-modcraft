"""L1c pre-warm, with fakes only: the lock is a temp file (TempEnv redirects prewarm.LOCK_PATH),
Prism is a fake executable that is never run, spawn_detached is mocked."""
import os
import subprocess
import sys
import time
from unittest import mock

from helpers import TempEnv, make_gmod, write

from gmodcraft_launcher import actions, prewarm, platform, progress

HOLD = ("import fcntl, sys, time\n"
        "f = open(sys.argv[1], 'r+')\n"
        "fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 0)\n"
        "print('locked', flush=True)\n"
        "time.sleep(30)\n")


class LockProbeTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.lock = prewarm.LOCK_PATH
        self.assertTrue(str(self.lock).startswith(str(self.tmp)))     # never the real /dev/shm

    def test_missing_and_free(self):
        self.assertEqual(prewarm.lock_holder(), 0)
        write(self.lock, "")
        self.assertEqual(prewarm.lock_holder(), 0)

    def test_held_by_another_process_then_free(self):
        write(self.lock, "")
        p = subprocess.Popen([sys.executable, "-c", HOLD, str(self.lock)], stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(p.stdout.readline().strip(), "locked")
            self.assertEqual(prewarm.lock_holder(), p.pid)          # struct flock layout is right
            self.assertIn(str(p.pid), prewarm.running_reason())
            # probing never takes the lock: the holder keeps it, and the file is unchanged
            self.assertEqual(prewarm.lock_holder(), p.pid)
        finally:
            p.kill()
            p.wait()
        deadline = time.monotonic() + 2
        while prewarm.lock_holder() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(prewarm.lock_holder(), 0)
        self.assertEqual(self.lock.read_text(), "")

    def test_symlink_not_followed(self):
        real = write(self.tmp / "real.lock", "")
        self.lock.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(real, self.lock)
        self.assertIsNone(prewarm.lock_holder())


class StartTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.prism = write(self.tmp / "Prism.AppImage", "#!/bin/sh\nexit 0\n")
        os.chmod(self.prism, 0o755)
        self.cfg = {"prismAppImage": str(self.prism)}
        self.log = []
        self.spawned = []
        p = mock.patch.object(platform, "spawn_detached", side_effect=lambda argv: self.spawned.append(argv))
        p.start()
        self.addCleanup(p.stop)

    def test_starts_with_the_modules_command(self):
        with mock.patch("shutil.which", side_effect=lambda n: "/usr/bin/systemd-run" if n == "systemd-run" else None):
            self.assertTrue(prewarm.start(self.cfg, self.log.append))
        self.assertEqual(self.spawned, [["/usr/bin/systemd-run", "--user", "--collect", "--quiet", "--",
                                         str(self.prism), "--launch", "GmodCraft"]])
        with mock.patch("shutil.which", return_value=None):
            self.assertEqual(prewarm.command(self.prism), [str(self.prism), "--launch", "GmodCraft"])

    def test_never_twice(self):
        with mock.patch.object(prewarm, "lock_holder", return_value=4242):
            self.assertFalse(prewarm.start(self.cfg, self.log.append))
        with mock.patch.object(platform, "java_with_arg_running", return_value=True):
            self.assertFalse(prewarm.start(self.cfg, self.log.append))
        with mock.patch.object(platform, "prism_running", return_value=True):
            self.assertFalse(prewarm.start(self.cfg, self.log.append))
        self.assertEqual(self.spawned, [])
        self.assertTrue(any("4242" in m for m in self.log))

    def test_off_missing_prism_and_failure_never_raise(self):
        self.assertFalse(prewarm.start(dict(self.cfg, prewarm=False), self.log.append))
        self.assertFalse(prewarm.start({"prismAppImage": str(self.tmp / "nope")}, self.log.append))
        self.assertFalse(prewarm.start({}, self.log.append))                    # no Prism anywhere
        with mock.patch.object(platform, "spawn_detached", side_effect=OSError("boom")):
            self.assertFalse(prewarm.start(self.cfg, self.log.append))
        self.assertTrue(any("GMod starts it itself" in m for m in self.log))
        self.assertEqual(self.spawned, [])
        self.assertTrue(prewarm.start(self.cfg, self.log.append, dry_run=True))
        self.assertEqual(self.spawned, [])

    def test_play_prewarms_before_steam_and_falls_back(self):
        g = make_gmod(self.tmp)
        os.environ["GMOD_DIR"] = str(g)
        steam = mock.patch.object(platform, "steam_launch_command", side_effect=lambda r, a: ["steam", *a])
        steam.start()
        self.addCleanup(steam.stop)
        res = {}
        argv = actions.play(self.cfg, self.log.append, result=res)
        self.assertTrue(res["prewarm"])
        self.assertEqual(len(self.spawned), 2)
        self.assertIn("--launch", self.spawned[0])            # Minecraft first
        self.assertEqual(self.spawned[1], argv)               # then Steam
        self.spawned.clear()
        res = {}
        with mock.patch.object(platform, "spawn_detached",
                                  side_effect=lambda a: (_ for _ in ()).throw(OSError("no")) if "--launch" in a
                                  else self.spawned.append(a)):
            argv = actions.play(self.cfg, self.log.append, result=res)
        self.assertFalse(res["prewarm"])                      # Prism failed ...
        self.assertEqual(self.spawned, [argv])                # ... GMod still starts (and starts MC itself)
        res = {}
        actions.play(dict(self.cfg, prewarm=False), self.log.append, result=res)
        self.assertFalse(res["prewarm"])


class ProgressTest(TempEnv):
    def test_step3_from_the_lock_before_gmod(self):
        write(prewarm.LOCK_PATH, "")
        p = subprocess.Popen([sys.executable, "-c", HOLD, str(prewarm.LOCK_PATH)], stdout=subprocess.PIPE, text=True)
        try:
            p.stdout.readline()
            t = progress.Tracker(started=0.0, prewarmed=True,
                                 probes={"gmod_running": lambda: False, "discovery": lambda: None, "protocol": 17})
            s = t.poll(now=3.0)
        finally:
            p.kill()
            p.wait()
        self.assertEqual(s.steps[2].state, "done")            # pre-warmed MC is up before GMod
        self.assertEqual(s.steps[0].state, "active")
        self.assertIn("pre-warmed", s.steps[2].label)
