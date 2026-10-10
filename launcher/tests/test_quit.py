"""L2 Quit: finding Garry's Modcraft's processes in a fake /proc, and the quit sequence with fake processes."""
import os
import signal

from helpers import TempEnv, write

from gmodcraft_launcher import progress, quit as q


def fake_proc(root, pid, comm, cmdline=(), exe=None):
    d = root / str(pid)
    write(d / "comm", comm + "\n")
    write(d / "cmdline", "\0".join(cmdline) + ("\0" if cmdline else ""))
    if exe:
        os.symlink(exe, d / "exe")
    return d


class FindTest(TempEnv):
    def test_other_users_and_start_time(self):
        proc = self.tmp / "proc"
        d = fake_proc(proc, 100, "gmod", ["/x/GarrysMod/bin/linux64/gmod"])
        write(d / "stat", "100 (gmod) S " + " ".join(["0"] * 18) + " 4242 0\n")
        self.assertEqual(q.find(proc).starts[100], 4242)
        self.assertEqual(q.find(proc, uid=os.getuid() + 1).gmod, [])            # someone else's processes: never
        self.assertEqual(q.start_time(proc, 100), 4242)
        self.assertIsNone(q.start_time(proc, 999))

    def test_only_clearly_ours(self):
        proc = self.tmp / "proc"
        fake_proc(proc, 100, "gmod", ["/home/u/.steam/steamapps/common/GarrysMod/bin/linux64/gmod", "-steam"],
                  exe="/home/u/.steam/steamapps/common/GarrysMod/bin/linux64/gmod")
        fake_proc(proc, 101, "gmod", ["/opt/other-game/gmod"], exe="/opt/other-game/gmod")        # same name, not GMod
        fake_proc(proc, 200, "java", ["java", "-Dgmodcraft.startHidden=true", "-cp", "x"])
        fake_proc(proc, 201, "java", ["java", "-jar", "server.jar"])                              # someone else's java
        fake_proc(proc, 202, "bash", ["bash", "-Dgmodcraft.startHidden=true"])                    # not java
        (proc / "self").mkdir()
        g = q.find(proc)
        self.assertEqual((g.gmod, g.mc), ([100], [200]))
        self.assertEqual(q.find(self.tmp / "nope").running, False)


class FakeWorld:
    """Processes that go away after N polls once signalled (or never)."""

    def __init__(self, gmod=(), mc=(), gmod_after=2, mc_after=3, mc_quits_itself=True, gmod_ignores_term=False):
        self.gmod, self.mc = set(gmod), set(mc)
        self.signals = []
        self.polls = 0
        self.gmod_left = None
        self.mc_left = None
        self.gmod_after, self.mc_after = gmod_after, mc_after
        self.mc_quits_itself = mc_quits_itself
        self.gmod_ignores_term = gmod_ignores_term
        self.t = 0.0

    def finder(self):
        self.polls += 1
        if self.gmod_left is not None:
            self.gmod_left -= 1
            if self.gmod_left <= 0:
                self.gmod.clear()
        if not self.gmod and self.mc and self.mc_quits_itself:
            self.mc_left = self.mc_after if self.mc_left is None else self.mc_left - 1
            if self.mc_left <= 0:
                self.mc.clear()
        return q.Game(sorted(self.gmod), sorted(self.mc))

    def kill(self, pid, sig):
        self.signals.append((pid, sig))
        if pid in self.gmod and (sig == signal.SIGKILL or not self.gmod_ignores_term):
            self.gmod_left = 0 if sig == signal.SIGKILL else self.gmod_after
        if pid in self.mc and sig == signal.SIGTERM:
            self.mc.clear()

    def sleep(self, s):
        self.t += s

    def now(self):
        return self.t


class QuitTest(TempEnv):
    def run_quit(self, w, **kw):
        log, states = [], []
        kw.setdefault("marker_dir", self.tmp / "no-marker-dir")      # no gentle path unless a test asks
        st = q.quit_game(log.append, finder=w.finder, kill=w.kill, sleep=w.sleep, now=w.now, on_state=states.append, **kw)
        return st, log, states

    def test_marker_dir_must_be_private(self):
        d = self.tmp / "mdir"
        d.mkdir(mode=0o700)
        os.chmod(d, 0o700)
        self.assertTrue(q.write_marker(100, d))
        os.chmod(d, 0o755)
        self.assertIsNone(q.write_marker(101, d))                      # mode 0755
        os.chmod(d, 0o700)
        self.assertIsNone(q.write_marker(102, d, uid=os.getuid() + 1)) # someone else's dir
        os.symlink(d, self.tmp / "linked")
        self.assertIsNone(q.write_marker(103, self.tmp / "linked"))    # symlinked dir
        self.assertFalse(q.private_dir(self.tmp / "missing"))

    def test_stopping_line_format(self):
        log = write(self.tmp / "l.log", "[10:00:00] [Server thread/INFO]: <Bob> Stopping!\n")
        self.assertFalse(q.mc_stopping(log))                          # chat text doesn't count
        write(log, "[10:00:00] [Render thread/INFO]: Stopping!\n")
        self.assertTrue(q.mc_stopping(log))

    def test_gentle_quit_marker_first(self):
        mdir = self.tmp / "shm" / "gmodcraft"
        os.chmod(mdir, 0o700)
        w = FakeWorld(gmod=[100], mc=[200])
        seen = []
        real_finder = w.finder

        def finder():                      # the module consumes the marker and GMod quits
            m = mdir / "quit-100"
            if m.exists():
                seen.append(oct(m.stat().st_mode & 0o777))
                w.gmod_left = 1
            return real_finder()
        w.finder = finder
        st, log, _ = self.run_quit(w, marker_dir=mdir)
        self.assertEqual(st.phase, "done")
        self.assertEqual(w.signals, [])                     # no SIGTERM: GMod quit through the marker
        self.assertEqual(seen[0], "0o600")
        self.assertFalse((mdir / "quit-100").exists())      # removed afterwards
        w = FakeWorld(gmod=[100], mc=[200])                  # marker ignored (out of game): SIGTERM after the wait
        st, _, _ = self.run_quit(w, marker_dir=mdir)
        self.assertEqual(w.signals[0], (100, signal.SIGTERM))
        self.assertGreaterEqual(w.t, q.MARKER_WAIT_S)
        self.assertFalse((mdir / "quit-100").exists())

    def test_reused_pid_not_signalled(self):
        w = FakeWorld(gmod=[100], mc=[200], mc_quits_itself=False)
        st, log, _ = self.run_quit(w, still_same=lambda pid, start: pid != 200)
        self.assertNotIn((200, signal.SIGTERM), w.signals)
        self.assertTrue(any("no longer" in m for m in log))

    def test_stopping_minecraft_not_signalled(self):
        mclog = write(self.tmp / "latest.log", "[10:00:00] [Render thread/INFO]: Stopping!\n")
        w = FakeWorld(gmod=[100], mc=[200], mc_quits_itself=False)
        orig = w.finder

        def finder():                      # Minecraft is saving: gone some polls after the grace
            g = orig()
            if w.t > q.MC_GRACE_S + 30:
                w.mc.clear()
                g = q.Game(sorted(w.gmod), [])
            return g
        w.finder = finder
        st, _, _ = self.run_quit(w, mc_log=mclog)
        self.assertEqual(st.phase, "done")
        self.assertNotIn((200, signal.SIGTERM), w.signals)
        self.assertEqual(q.MC_GRACE_S, 90)

    def test_gmod_then_mc_quits_by_itself(self):
        w = FakeWorld(gmod=[100], mc=[200])
        st, log, states = self.run_quit(w)
        self.assertEqual(st.phase, "done")
        self.assertEqual(w.signals, [(100, signal.SIGTERM)])          # Minecraft never signalled: it quits itself
        self.assertTrue(any("saves and closes" in s.note for s in states))

    def test_mc_signalled_when_it_lingers(self):
        w = FakeWorld(gmod=[100], mc=[200], mc_quits_itself=False)
        st, _, _ = self.run_quit(w)
        self.assertEqual(st.phase, "done")
        self.assertEqual(w.signals, [(100, signal.SIGTERM), (200, signal.SIGTERM)])

    def test_stuck_gmod_needs_force(self):
        w = FakeWorld(gmod=[100], mc=[200], gmod_ignores_term=True)
        st, _, _ = self.run_quit(w)
        self.assertEqual(st.phase, "stuck")
        self.assertNotIn((100, signal.SIGKILL), w.signals)
        st, _, _ = self.run_quit(w, force=True)
        self.assertIn((100, signal.SIGKILL), w.signals)
        self.assertEqual(st.phase, "done")

    def test_nothing_running_and_mc_only(self):
        st, _, _ = self.run_quit(FakeWorld())
        self.assertEqual(st.phase, "done")
        w = FakeWorld(mc=[200], mc_quits_itself=False)                   # GMod already gone, MC lingering
        st, _, _ = self.run_quit(w)
        self.assertEqual(w.signals, [(200, signal.SIGTERM)])


class ProgressLinkDownTest(TempEnv):
    def test_countdown_counts_from_link_down(self):
        import time
        st = {"gmod": True, "doc": {"shm": "gmodcraft-client-x", "_mtime": time.time()}}
        info = progress.LinkInfo(ok=True, version=17, host_age_ms=1, mc_age_ms=1, in_world=True)
        t = progress.Tracker(started=0.0, probes={"gmod_running": lambda: st["gmod"], "mc_running": lambda: True,
                                                  "discovery": lambda: st["doc"], "link": lambda p, expect_version=None: info,
                                                  "protocol": 17})
        t.poll(now=100.0)                      # linked at 100
        st["gmod"], st["doc"] = False, None   # GMod gone at 130; link down ~108 (8 s timeout) -> quit due ~123, i.e. now+1
        s = t.poll(now=130.0)
        self.assertIn("about 1 s", s.note)


class ProgressCountdownTest(TempEnv):
    def test_mc_closing_countdown_after_gmod_exit(self):
        st = {"gmod": True, "mc": True}
        t = progress.Tracker(started=0.0, probes={"gmod_running": lambda: st["gmod"], "mc_running": lambda: st["mc"],
                                                  "discovery": lambda: None, "protocol": 17})
        t.poll(now=1.0)
        st["gmod"] = False
        s = t.poll(now=10.0)
        self.assertTrue(s.gone)
        self.assertFalse(s.finished)                                      # still watching Minecraft
        self.assertIn("Minecraft saves and closes in about 15 s", s.note)
        s = t.poll(now=20.0)
        self.assertIn("about 5 s", s.note)
        st["mc"] = False
        s = t.poll(now=24.0)
        self.assertTrue(s.finished)
        self.assertIn("Minecraft has closed too", s.note)
