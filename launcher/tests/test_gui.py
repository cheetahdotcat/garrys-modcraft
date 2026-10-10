"""Headless-ish smoke test: build the window withdrawn (needs a display), drive the server list, close."""
import os
import unittest
from unittest import mock

from helpers import TempEnv

try:
    import tkinter as tk
except ImportError:  # pragma: no cover
    tk = None


@unittest.skipUnless(tk and (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")), "no display / no tkinter")
class GuiSmokeTest(TempEnv):
    def setUp(self):
        super().setUp()
        from gmodcraft_launcher import gui, passwords, query
        self.gui, self.passwords, self.query = gui, passwords, query
        self.root = tk.Tk()
        self.root.withdraw()
        self.addCleanup(self.close)

    def close(self):
        try:
            self.app.close()
        except Exception:
            self.root.destroy()

    def build(self, cfg):
        base = {"source_kind": "bundle", "bundle": "", "dev_repo": "", "prismAppImage": "", "server": "", "servers": []}
        base.update(cfg)
        self.app = self.gui.build(base, self.root)
        return self.app

    def test_build_window(self):
        from gmodcraft_launcher import detect
        app = self.build({})
        app._show_status([detect.Check("Garry's Mod", True, "/x"), detect.Check("Addon", False, "not installed"),
                          detect.Check("Prism Launcher", None, "?")])
        app.log("hello")   # from any thread: queued, shown by the UI thread's poll
        app._poll()
        self.root.update()
        self.assertEqual(len(app.status_frame.winfo_children()), 9)
        self.assertIn("hello", app.log_box.get("1.0", "end"))
        self.assertFalse(app.servers.visible())    # withdrawn: no status queries

    def test_migration_and_list_edits(self):
        pw = self.passwords
        pw.remember("10.0.0.5:27015", "s3cret")
        app = self.build({"server": "10.0.0.5"})
        p = app.servers
        self.assertEqual([e["address"] for e in p.entries], ["10.0.0.5"])
        self.assertEqual(app.cfg["server"], "")
        with mock.patch.object(p, "query"):
            p.apply_edit(None, "Second", "10.0.0.6:27016", "25570", "other")
            self.assertEqual(pw.stored("10.0.0.6:27016"), "other")
            # edit: new address, password left empty -> the stored one moves along
            p.apply_edit(0, "First", "10.0.0.7", "", "")
            self.assertEqual(pw.stored("10.0.0.7"), "s3cret")
            self.assertEqual(pw.stored("10.0.0.5"), "")
            with self.assertRaises(pw.PasswordError):
                p.apply_edit(0, "First", "10.0.0.7", "", 'x"y')
            with self.assertRaises(Exception):
                p.apply_edit(None, "Dup", "10.0.0.7:27015", "", "")
            p.apply_edit(0, "First", "10.0.0.7", "", "", forget_password=True)
            self.assertEqual(pw.stored("10.0.0.7"), "")
        self.assertEqual([e["name"] for e in p.entries], ["First", "Second"])
        p.tree.selection_set("1")
        p.move(-1)
        self.assertEqual([e["name"] for e in p.entries], ["Second", "First"])
        p.remove_at(0)
        self.assertEqual(pw.stored("10.0.0.6:27016"), "")
        self.assertEqual([e["name"] for e in p.entries], ["First"])
        # nothing secret in the config or the window
        from gmodcraft_launcher import config
        text = config.path().read_text()
        self.assertNotIn("s3cret", text)
        self.assertNotIn("other", text)
        rows = " ".join(" ".join(map(str, p.tree.item(i, "values"))) for i in p.tree.get_children())
        self.assertNotIn("s3cret", rows)

    def two_servers(self):
        pw = self.passwords
        pw.remember("10.0.0.1", "pwA")
        pw.remember("10.0.0.2", "pwB")
        app = self.build({"servers": [{"name": "A", "address": "10.0.0.1", "mc_port": 25565},
                                      {"name": "B", "address": "10.0.0.2", "mc_port": 25565}]})
        return app.servers

    def test_dialog_rekeys_on_save_and_refuses_a_removed_server(self):
        from gmodcraft_launcher import gui_servers
        p = self.two_servers()
        pw = self.passwords
        with mock.patch.object(p, "query"):
            d = gui_servers.ServerDialog(p, 0, show=False)      # Edit A ...
            p.remove_at(0)                                      # ... A removed meanwhile
            d.name.set("renamed")
            self.assertFalse(d.ok())
            self.assertIn("removed", d.error.cget("text"))
            self.assertEqual([(e["name"], e["address"]) for e in p.entries], [("B", "10.0.0.2")])
            self.assertEqual(pw.stored("10.0.0.2"), "pwB")      # B's password stays B's
            d.top.destroy()
            d = gui_servers.ServerDialog(p, 0, show=False)      # empty list afterwards: no IndexError
            p.remove_at(0)
            self.assertFalse(d.ok())
            d.top.destroy()
            self.assertEqual(p.entries, [])

    def test_dialog_follows_its_server_when_rows_move(self):
        from gmodcraft_launcher import gui_servers
        p = self.two_servers()
        with mock.patch.object(p, "query"):
            d = gui_servers.ServerDialog(p, 0, show=False)      # Edit A at row 0
            p.tree.selection_set("0")
            p.move(1)                                           # A is row 1 now
            d.name.set("A2")
            self.assertTrue(d.ok())
        self.assertEqual([e["name"] for e in p.entries], ["B", "A2"])

    def test_forget_disables_the_password_field(self):
        from gmodcraft_launcher import gui_servers
        p = self.two_servers()
        with mock.patch.object(p, "query"):
            d = gui_servers.ServerDialog(p, 1, show=False)
            d.password.set("typed")
            d.forget.set(True)
            d._forget_toggled()
            self.assertTrue(d.password_entry.instate(["disabled"]))
            self.assertEqual(d.password.get(), "")
            self.assertTrue(d.ok())
        self.assertEqual(self.passwords.stored("10.0.0.2"), "")

    def pump(self, until, seconds=3.0):
        import time
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            self.app._poll()
            self.root.update()
            if until():
                return True
            time.sleep(0.02)
        return False

    def test_tabs_progress_logs_theme_and_window(self):
        from gmodcraft_launcher import config, gui, logs, progress
        app = self.build({"tab": 2, "theme": "dark", "window": "1000x700"})
        self.assertEqual([app.nb.tab(i, "text") for i in range(app.nb.index("end"))], list(gui.TABS))
        self.assertEqual(app.nb.index(app.nb.select()), 2)
        self.assertEqual(app.palette["name"], "dark")
        # progress from injected probes (no processes, no shm)
        probes = {"gmod_running": lambda: True, "mc_running": lambda: False, "discovery": lambda: None,
                  "protocol": 17}
        app.start_progress(probes=probes, interval=0.05)
        self.assertTrue(self.pump(lambda: app.progress_rows[0][0].cget("text") == gui.STATE_MARK["done"]))
        self.assertEqual(app.progress_rows[1][0].cget("text"), gui.STATE_MARK["active"])
        app._progress_gen += 1                                   # stop the tracker thread
        # log viewer on a fixture file: passwords hidden, hints shown
        p = self.tmp / "console.log"
        p.write_text('exec gmodcraft_connect\npassword "s3cret"\nDisconnect: Bad password.\n')
        self.passwords.remember("10.0.0.9", "s3cret")
        with mock.patch.object(logs, "gmod_log_path", return_value=p):
            app.log_auto.set(False)
            app.refresh_log_view()
            self.assertTrue(self.pump(lambda: "Bad password" in app.log_view.get("1.0", "end")))
        shown = app.log_view.get("1.0", "end")
        self.assertNotIn("s3cret", shown)
        self.assertIn("server password", app.log_hints.cget("text"))
        self.assertIn("passwords hidden", app.log_path_label.cget("text"))
        # theme toggle is saved
        app.theme_var.set("light")
        app._theme_changed()
        self.assertEqual(config.load()["theme"], "light")
        self.assertEqual(app.palette["name"], "light")
        # last tab is saved on change
        app.nb.select(3)
        app._tab_changed()
        self.assertEqual(config.load()["tab"], 3)
        self.assertIsNotNone(app.b_desktop)
        self.assertTrue(app.prewarm_var.get())                 # pre-warm on by default; the toggle is saved
        app.prewarm_var.set(False)
        app._prewarm_changed()
        self.assertFalse(config.load()["prewarm"])
        _ = progress  # imported for the probes' contract

    def test_log_auto_refresh_survives_hiding(self):
        app = self.build({})
        reads = []
        app._read_log = lambda: reads.append(1)
        app.nb.select(2)
        app._tab_changed()
        visible = [False]
        with mock.patch.object(app.servers, "visible", side_effect=lambda: visible[0]):
            n = len(reads)
            app._log_tick()                            # hidden / minimised: no read, still scheduled
            self.assertEqual(len(reads), n)
            self.assertIsNotNone(app._log_job)
            visible[0] = True                          # shown again: reads resume
            app._log_tick()
            self.assertEqual(len(reads), n + 1)
            self.assertIsNotNone(app._log_job)
        self.root.deiconify()                          # and through a real withdraw / deiconify
        self.root.update()
        self.assertTrue(app.servers.visible())
        self.root.withdraw()
        self.root.update()
        self.assertFalse(app.servers.visible())
        app.nb.select(0)
        app._tab_changed()
        app._log_tick()                                # another tab: stops
        self.assertIsNone(app._log_job)

    def test_connect_cfg_wiped_once_loaded(self):
        import json
        import time
        from gmodcraft_launcher import detect, progress
        g = self.tmp / "G"
        (g / "garrysmod" / "cfg").mkdir(parents=True)
        cfgf = g / "garrysmod" / "cfg" / "gmodcraft_connect.cfg"
        cfgf.write_text('password "pw"\nconnect 10.0.0.1\n')
        app = self.build({})
        progress.DISCOVERY.write_text(json.dumps({"shm": "gmodcraft-client-t"}))
        probes = {"gmod_running": lambda: True, "mc_running": lambda: False, "protocol": 17}
        with mock.patch.object(detect, "find_gmod", return_value=detect.GmodInfo(g, None, True)):
            app.start_progress(probes=probes, interval=0.05)
            deadline = time.monotonic() + 3
            while cfgf.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
        app._progress_gen += 1
        self.assertFalse(cfgf.exists())

    def test_maximized_remembered(self):
        from gmodcraft_launcher import config
        app = self.build({})
        with mock.patch.object(self.root, "geometry", return_value="1920x1040+0+0"), \
                mock.patch.object(self.root, "state", return_value="normal"), \
                mock.patch.object(app, "_zoomed_attr", return_value=True):
            app.close()
        c = config.load()
        self.assertEqual((c["window"], c["maximized"]), ("1920x1040", True))

    def test_quit_button_and_close_prompt(self):
        from gmodcraft_launcher import quit as q
        app = self.build({})
        self.assertEqual(str(app.b_quit.cget("text")), "Quit game")
        running = q.Game(gmod=[100], mc=[200])
        calls = []
        app.run = lambda title, fn, refresh_after=True: (fn(), True)[1]      # synchronously

        def fake_quit(log, **kw):
            calls.append(kw)
            st = q.QuitState("done", "Garry's Modcraft has closed", q.Game())
            kw["on_state"](st)
            return st
        with mock.patch.object(q, "find", return_value=running), \
                mock.patch.object(q, "quit_game", side_effect=fake_quit):
            asked = []
            self.assertFalse(app.quit_game(ask=lambda *a, **k: asked.append(a[1]) or False))   # "no": nothing happens
            self.assertIn("pid 100", asked[0])
            self.assertIn("pid 200", asked[0])
            self.assertEqual(calls, [])
            self.assertTrue(app.quit_game(ask=lambda *a, **k: True))
            self.assertEqual(len(calls), 1)
            # window close while running: Cancel keeps everything, "No" closes only the launcher
            self.assertFalse(app.on_close(ask=lambda *a, **k: None))
            self.assertEqual(len(calls), 1)
            with mock.patch.object(app, "close") as close:
                app.on_close(ask=lambda *a, **k: False)
                close.assert_called_once()
            self.assertEqual(len(calls), 1)                          # the game was left alone
        states = []
        while not app.q.empty():
            k, v = app.q.get_nowait()
            if k == "quitstate":
                states.append(v)
        self.assertEqual(len(states), 1)                             # the final state reaches the UI once
        app.show_quit_state(states[0])
        self.assertIn("has closed", app.progress_hint.cget("text"))
        # busy: the quit isn't started and the user is told; a close-with-quit keeps the window
        app.run = lambda title, fn, refresh_after=True: False
        with mock.patch.object(q, "find", return_value=running), mock.patch("tkinter.messagebox.showinfo") as info, \
                mock.patch.object(app, "close") as close:
            self.assertFalse(app.quit_game(ask=lambda *a, **k: True))
            info.assert_called_once()
            self.assertFalse(app.on_close(ask=lambda *a, **k: True))
            close.assert_not_called()
            self.assertFalse(app._quit_then_close)
        # stuck without a force offer resets the close-after-quit flag
        app._quit_then_close = True
        app.show_quit_state(q.QuitState("stuck", "Minecraft didn't close; close it from its window or Prism", q.Game()))
        self.assertFalse(app._quit_then_close)
        with mock.patch.object(q, "find", return_value=q.Game()), mock.patch.object(app, "close") as close:
            app.on_close(ask=lambda *a, **k: self.fail("no prompt when nothing runs"))
            close.assert_called_once()

    def test_window_size_saved_on_close(self):
        from gmodcraft_launcher import config
        app = self.build({})
        with mock.patch.object(self.root, "geometry", return_value="950x720+10+10"), \
                mock.patch.object(self.root, "state", return_value="normal"):
            app.close()
        self.assertEqual(config.load()["window"], "950x720")

    def status(self, version=None, rules_ok=True, map_="gm_flatgrass"):
        q = self.query
        rules = {q.VERSION_CONVAR: version} if version else {}
        return q.ServerStatus(q.InfoResult(ok=True, map=map_, players=1, max_players=4, password=True),
                              q.RulesResult(ok=rules_ok, rules=rules), q.McResult(ok=True, online=0, max_players=20))

    def test_status_rows_and_join(self):
        from gmodcraft_launcher import actions, servers
        self.passwords.remember("10.0.0.5", "pw")
        app = self.build({"servers": [{"name": "Box", "address": "10.0.0.5", "mc_port": 25565}]})
        p = app.servers
        key = servers.key(p.entries[0])
        app.launched = lambda **kw: None                       # no progress tracker in this test
        p.show_status(key, self.status())                      # no convar: unknown, never asks
        vals = p.tree.item("0", "values")
        self.assertIn("unknown", vals)
        self.assertIn("gm_flatgrass", vals)
        played = []
        app.run = lambda title, fn, refresh_after=True: fn()   # synchronously, no worker thread
        with mock.patch.object(actions, "play", side_effect=lambda cfg, log, **kw: played.append(kw)):
            ask = mock.Mock(return_value=False)
            self.assertTrue(p.join(0, ask=ask))
            ask.assert_not_called()
            self.assertEqual(played[-1]["server"], "10.0.0.5")
            self.assertEqual(played[-1]["password"], "pw")      # from secrets, only into actions.play
            p.show_status(key, self.status(f"{self.query.PROTOCOL}/0.1.2"))
            self.assertIn(f"v{self.query.PROTOCOL} ok", p.tree.item("0", "values"))
            p.join(0, ask=ask)
            ask.assert_not_called()
            p.show_status(key, self.status("15/0.0.9"))         # known mismatch: asks, "no" cancels
            self.assertIn("mismatch", p.tree.item("0", "tags"))
            n = len(played)
            self.assertFalse(p.join(0, ask=ask))
            ask.assert_called_once()
            self.assertEqual(len(played), n)
            self.assertTrue(p.join(0, ask=mock.Mock(return_value=True)))
            self.assertEqual(len(played), n + 1)
            p.show_status(key, self.status(rules_ok=False))      # rules off: unknown again
            self.assertIn("unknown", p.tree.item("0", "values"))
        p.show_status("gone:1", self.status())                  # a removed server's late result is dropped
        app.log("x")
        app._poll()
        self.assertNotIn("pw\n", app.log_box.get("1.0", "end"))

    def test_update_banner_and_apply(self):
        """L4: a check (fake fetch) shows the banner; Update installs the verified zip through actions.install."""
        import hashlib
        from gmodcraft_launcher import actions, config, update
        zb, pb = b"zip", b"pyz"
        sums = f"{hashlib.sha256(zb).hexdigest()}  garrys-modcraft-9.0.0.zip\n{hashlib.sha256(pb).hexdigest()}  gmodcraft-launcher.pyz\n"
        url = f"https://github.com/{update.REPO}/releases/download/v9.0.0/"
        names = ["garrys-modcraft-9.0.0.zip", "gmodcraft-launcher.pyz", "SHA256SUMS"]
        rels = [{"tag_name": "v9.0.0", "assets": [{"name": n, "browser_download_url": url + n} for n in names]}]
        app = self.build({})
        self.assertFalse(app.update_bar.winfo_ismapped())
        self.assertTrue(app.start_update_check(force=True, fetch_json=lambda u: rels))
        self.assertTrue(self.pump(lambda: app._offer is not None))
        self.assertIn("9.0.0 available", app.update_label.cget("text"))
        self.assertGreater(config.load()["updateLastCheck"], 0)
        files = {"garrys-modcraft-9.0.0.zip": zb, "gmodcraft-launcher.pyz": pb, "SHA256SUMS": sums.encode()}
        installed = []
        with mock.patch.object(actions, "install", lambda cfg, log, **kw: installed.append(cfg["bundle"])), \
                mock.patch.object(update, "running_pyz", lambda: None):
            self.assertTrue(app._apply_update(fetch_file=lambda u, out, limit: out.write(files[u.rsplit("/", 1)[1]])))
            self.assertTrue(self.pump(lambda: not app.busy))
        self.assertEqual(len(installed), 1)
        self.assertTrue(installed[0].endswith("garrys-modcraft-9.0.0.zip"))
        self.assertEqual(app.bundle.get(), installed[0])
        self.assertIsNone(app._offer)
        self.assertEqual(config.load()["bundle"], installed[0])


if __name__ == "__main__":
    unittest.main()
