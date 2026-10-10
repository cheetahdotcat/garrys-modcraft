"""The launcher window (Tkinter). Actions run on one worker thread; status queries, launch progress
and the log viewer read on their own daemon threads; the UI thread only polls a queue.

Tabs: Play (single player, the server list, launch progress), Setup (source, checks, install),
Logs (GMod / Minecraft log tail with hints), Settings (look, desktop entry). An activity log sits
below the tabs. Window size, last tab and the theme are remembered in config.json.
"""
import base64
import queue
import re
import threading
import time
import traceback

import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from tkinter.scrolledtext import ScrolledText

from . import (__version__, actions, config, desktop, detect, gui_servers, icon, logs, passwords, platform,
               prism, progress, quit, servers, theme, update)

STATE_MARK = {"waiting": "○", "active": "◔", "done": "●", "slow": "◕", "skipped": "–"}
TABS = ("Play", "Setup", "Logs", "Settings")
GEOMETRY = re.compile(r"^(\d{3,4})x(\d{3,4})$")
PROGRESS_LIMIT_S = 15 * 60
WATCH_INTERVAL_S = 2.0
LOG_REFRESH_MS = 2000


class App:
    def __init__(self, root, cfg):
        self.root = root
        self.cfg = cfg
        self.q = queue.Queue()
        self.busy = False
        self._progress_gen = 0
        self._log_job = None
        self._log_reading = False
        root.title("Garry's Modcraft")
        root.minsize(720, 600)
        geo = cfg.get("window") or ""
        root.geometry(geo if GEOMETRY.match(geo) else "900x820")
        if cfg.get("maximized"):
            try:
                root.attributes("-zoomed", True)        # X11
            except tk.TclError:
                try:
                    root.state("zoomed")                # Windows / macOS
                except tk.TclError:
                    pass
        self.palette = theme.apply(root, cfg.get("theme") or "system")
        try:
            self._icon = tk.PhotoImage(data=base64.b64encode(icon.png(64)).decode("ascii"))
            root.iconphoto(True, self._icon)
        except tk.TclError:
            self._icon = None
        cfg["servers"] = servers.load(cfg)
        if servers.migrate(cfg):      # the old single "server" (its stored password is keyed the same)
            try:
                config.save(cfg)
            except OSError:
                pass
        self._build()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.after(50, self._poll)

    # ---- layout ----------------------------------------------------------------------------
    def _build(self):
        r = self.root
        pad = {"padx": 10, "pady": 4}
        top = ttk.Frame(r)
        top.pack(fill="x", padx=10, pady=(8, 2))
        if self._icon is not None:
            self._icon_small = self._icon.subsample(2, 2)
            ttk.Label(top, image=self._icon_small).pack(side="left", padx=(0, 8))
        ttk.Label(top, text="Garry's Modcraft", style="Title.TLabel").pack(side="left")
        ttk.Label(top, text=f"launcher {__version__}", style="Muted.TLabel").pack(side="right")
        # L4: "X available — Update" banner, shown by show_update_offer()
        self._offer = None
        self._checking_update = False
        self.update_bar = ttk.Frame(r)
        self.update_label = ttk.Label(self.update_bar, text="")
        self.update_label.pack(side="left")
        self.b_update = ttk.Button(self.update_bar, text="Update", style="Accent.TButton", command=self._apply_update)
        self.b_update.pack(side="left", padx=8)

        self.nb = ttk.Notebook(r)
        self.nb.pack(fill="both", expand=True, **pad)
        tabs = {}
        for name in TABS:
            f = ttk.Frame(self.nb, padding=8)
            self.nb.add(f, text=name)
            tabs[name] = f
        self.dry = tk.BooleanVar(value=False)
        self._build_play(tabs["Play"])
        self._build_setup(tabs["Setup"])
        self._build_logs(tabs["Logs"])
        self._build_settings(tabs["Settings"])
        try:
            self.nb.select(min(max(int(self.cfg.get("tab") or 0), 0), len(TABS) - 1))
        except (TypeError, ValueError, tk.TclError):
            pass
        self.nb.bind("<<NotebookTabChanged>>", self._tab_changed)
        if TABS[self.nb.index(self.nb.select())] == "Logs":
            self.root.after(200, self.refresh_log_view)     # started on the remembered Logs tab

        lg = ttk.LabelFrame(r, text="Activity")
        lg.pack(fill="x", **pad)
        self.log_box = ScrolledText(lg, height=6, state="disabled", wrap="word", font=("TkFixedFont", 9))
        self._style_text(self.log_box)
        self.log_box.pack(fill="both", expand=True, padx=4, pady=4)
        self.buttons = [self.b_install, self.b_uninstall, self.b_refresh, self.b_play, self.b_join, self.b_quit,
                        self.b_desktop, self.b_undesktop, self.b_new_world, self.b_update]

    def _style_text(self, w):
        c = self.palette
        w.configure(background=c["field"], foreground=c["fg"], insertbackground=c["fg"], highlightthickness=0,
                    selectbackground=c["sel"], selectforeground=c["selfg"])

    def _build_play(self, f):
        row = ttk.Frame(f)
        row.pack(fill="x")
        self.b_play = ttk.Button(row, text="Play singleplayer (gm_construct)", style="Accent.TButton", command=self._play)
        self.b_play.pack(side="left", pady=(0, 6))
        self.b_quit = ttk.Button(row, text="Quit game", command=self.quit_game)
        self.b_quit.pack(side="left", padx=8, pady=(0, 6))
        ttk.Checkbutton(row, text="Dry run", variable=self.dry).pack(side="right")
        nw = ttk.Frame(f)
        nw.pack(fill="x", pady=(0, 4))
        ttk.Label(nw, text="Single-player world:").pack(side="left")
        self.world_type = tk.StringVar(value=prism.WORLD_TYPE_LABELS["mirror"])
        ttk.Combobox(nw, textvariable=self.world_type, state="readonly", width=34,
                     values=[prism.WORLD_TYPE_LABELS[t] for t in prism.WORLD_TYPES]).pack(side="left", padx=6)
        self.b_new_world = ttk.Button(nw, text="New world...", command=self._new_world)
        self.b_new_world.pack(side="left")
        sv = ttk.LabelFrame(f, text="Servers (double-click to join; status every 10 s while this window is open)")
        sv.pack(fill="both", expand=True, pady=4)
        self.servers = gui_servers.ServerPanel(self, sv)
        self.b_join = self.servers.b_join
        pg = ttk.LabelFrame(f, text="Launch progress")
        pg.pack(fill="x", pady=4)
        self.progress_rows = []
        for i, (sid, label, _t, _h) in enumerate(progress.STEPS):
            mark = tk.Label(pg, text=STATE_MARK["waiting"], fg=self.palette["muted"], bg=self.palette["bg"],
                            font=("TkDefaultFont", 12))
            mark.grid(row=i, column=0, sticky="w", padx=(6, 4))
            text = ttk.Label(pg, text=label)
            text.grid(row=i, column=1, sticky="w")
            when = ttk.Label(pg, text="", style="Muted.TLabel")
            when.grid(row=i, column=2, sticky="e", padx=8)
            self.progress_rows.append((mark, text, when))
        pg.columnconfigure(1, weight=1)
        self.progress_hint = ttk.Label(pg, text="Starts after Play or Join.", style="Muted.TLabel", wraplength=760,
                                       justify="left")
        self.progress_hint.grid(row=len(progress.STEPS), column=0, columnspan=3, sticky="w", padx=6, pady=(4, 6))

    def _build_setup(self, f):
        src = ttk.LabelFrame(f, text="Source")
        src.pack(fill="x", pady=4)
        self.kind = tk.StringVar(value=self.cfg.get("source_kind") or "bundle")
        self.bundle = tk.StringVar(value=self.cfg.get("bundle", ""))
        self.repo = tk.StringVar(value=self.cfg.get("dev_repo", ""))
        self.prism_exe = tk.StringVar(value=self.cfg.get("prismAppImage", ""))
        rows = [("Release bundle", "bundle", self.bundle, self._pick_bundle),
                ("Dev checkout", "dev", self.repo, self._pick_repo)]
        for i, (text, val, var, cmd) in enumerate(rows):
            ttk.Radiobutton(src, text=text, value=val, variable=self.kind, command=self._changed).grid(row=i, column=0, sticky="w", padx=4)
            e = ttk.Entry(src, textvariable=var)
            e.grid(row=i, column=1, sticky="ew", padx=4, pady=2)
            e.bind("<FocusOut>", lambda _e: self._changed())
            ttk.Button(src, text="Browse...", command=cmd).grid(row=i, column=2, padx=4)
        ttk.Label(src, text="Prism Launcher").grid(row=2, column=0, sticky="w", padx=4)
        e = ttk.Entry(src, textvariable=self.prism_exe)
        e.grid(row=2, column=1, sticky="ew", padx=4, pady=2)
        e.bind("<FocusOut>", lambda _e: self._changed())
        ttk.Button(src, text="Browse...", command=self._pick_prism).grid(row=2, column=2, padx=4)
        ttk.Label(src, text="Prism kind").grid(row=3, column=0, sticky="w", padx=4)
        self.prism_kind = tk.StringVar(value=self.cfg.get("prismKind") or "auto")
        kind = ttk.Combobox(src, textvariable=self.prism_kind, values=detect.PRISM_KINDS, state="readonly", width=12)
        kind.grid(row=3, column=1, sticky="w", padx=4, pady=2)
        kind.bind("<<ComboboxSelected>>", lambda _e: (self._changed(), self.refresh()))
        ttk.Label(src, text="auto: the AppImage above, else pacman's prismlauncher, else the flatpak",
                  style="Muted.TLabel").grid(row=3, column=1, sticky="e", padx=4)
        src.columnconfigure(1, weight=1)

        st = ttk.LabelFrame(f, text="Status")
        st.pack(fill="x", pady=4)
        self.status_frame = ttk.Frame(st)
        self.status_frame.pack(fill="x", padx=4, pady=2)
        ttk.Label(self.status_frame, text="checking...").grid(row=0, column=0, sticky="w")

        act = ttk.Frame(f)
        act.pack(fill="x", pady=4)
        self.foreign = tk.BooleanVar(value=False)
        self.b_install = ttk.Button(act, text="Install / Update", style="Accent.TButton", command=self._install)
        self.b_uninstall = ttk.Button(act, text="Uninstall", command=self._uninstall)
        self.b_refresh = ttk.Button(act, text="Refresh", command=self.refresh)
        for b in (self.b_install, self.b_uninstall, self.b_refresh):
            b.pack(side="left", padx=(0, 6))
        ttk.Checkbutton(act, text="Dry run", variable=self.dry).pack(side="left", padx=6)
        act2 = ttk.Frame(f)
        act2.pack(fill="x")
        ttk.Checkbutton(act2, text="Replace files it didn't install (backed up)", variable=self.foreign).pack(anchor="w")
        self.rm_instance = tk.BooleanVar(value=False)
        ttk.Checkbutton(act2, text="Uninstall: also remove the Prism instance (deletes its worlds)",
                        variable=self.rm_instance).pack(anchor="w")

    def _build_logs(self, f):
        bar = ttk.Frame(f)
        bar.pack(fill="x")
        self.log_source = tk.StringVar(value="gmod")
        ttk.Radiobutton(bar, text="Garry's Mod console", value="gmod", variable=self.log_source,
                        command=self.refresh_log_view).pack(side="left")
        ttk.Radiobutton(bar, text="Minecraft (latest.log)", value="mc", variable=self.log_source,
                        command=self.refresh_log_view).pack(side="left", padx=8)
        self.log_auto = tk.BooleanVar(value=True)
        ttk.Checkbutton(bar, text="Auto-refresh", variable=self.log_auto,
                        command=self._schedule_log_tick).pack(side="left", padx=8)
        ttk.Button(bar, text="Open file", command=self._open_log).pack(side="right")
        ttk.Button(bar, text="Refresh", command=self.refresh_log_view).pack(side="right", padx=6)
        self.log_path_label = ttk.Label(f, text="", style="Muted.TLabel")
        self.log_path_label.pack(fill="x", pady=(4, 0))
        self.log_hints = ttk.Label(f, text="", wraplength=780, justify="left", foreground=self.palette["warn"])
        self.log_hints.pack(fill="x", pady=2)
        self.log_view = ScrolledText(f, height=14, state="disabled", wrap="none", font=("TkFixedFont", 9))
        self._style_text(self.log_view)
        self.log_view.pack(fill="both", expand=True)

    def _build_settings(self, f):
        look = ttk.LabelFrame(f, text="Look")
        look.pack(fill="x", pady=4)
        self.theme_var = tk.StringVar(value=self.cfg.get("theme") or "system")
        for val, text in (("system", "Follow the system"), ("light", "Light"), ("dark", "Dark")):
            ttk.Radiobutton(look, text=text, value=val, variable=self.theme_var, command=self._theme_changed).pack(side="left", padx=6, pady=4)
        ttk.Label(look, text="(takes full effect after a restart)", style="Muted.TLabel").pack(side="left", padx=6)
        pw = ttk.LabelFrame(f, text="Start-up")
        pw.pack(fill="x", pady=4)
        self.prewarm_var = tk.BooleanVar(value=bool(self.cfg.get("prewarm", True)))
        ttk.Checkbutton(pw, text="Start Minecraft together with Garry's Mod (faster; GMod adopts it)",
                        variable=self.prewarm_var, command=self._prewarm_changed).pack(anchor="w", padx=6, pady=4)
        up = ttk.LabelFrame(f, text="Updates")
        up.pack(fill="x", pady=4)
        self.update_check_var = tk.BooleanVar(value=bool(self.cfg.get("updateCheck", True)))
        self.update_betas_var = tk.BooleanVar(value=bool(self.cfg.get("updateBetas", False)))
        ttk.Checkbutton(up, text="Check for updates on start (GitHub releases, at most every 6 h)",
                        variable=self.update_check_var, command=self._update_settings_changed).pack(anchor="w", padx=6, pady=(4, 0))
        ttk.Checkbutton(up, text="Include betas (pre-releases)", variable=self.update_betas_var,
                        command=self._update_settings_changed).pack(anchor="w", padx=6)
        self.b_check_update = ttk.Button(up, text="Check for updates", command=lambda: self.start_update_check(force=True))
        self.b_check_update.pack(anchor="w", padx=6, pady=(2, 6))
        de = ttk.LabelFrame(f, text="Desktop entry")
        de.pack(fill="x", pady=4)
        ttk.Label(de, text="Adds Garry's Modcraft to your applications menu (~/.local/share/applications).",
                  style="Muted.TLabel").pack(anchor="w", padx=6, pady=(4, 2))
        bar = ttk.Frame(de)
        bar.pack(fill="x", padx=6, pady=(0, 6))
        self.b_desktop = ttk.Button(bar, text="Add to the applications menu", command=self._install_desktop)
        self.b_undesktop = ttk.Button(bar, text="Remove it", command=self._uninstall_desktop)
        self.b_desktop.pack(side="left")
        self.b_undesktop.pack(side="left", padx=6)

    # ---- config ----------------------------------------------------------------------------
    def _changed(self):
        self.cfg.update(source_kind=self.kind.get(), bundle=self.bundle.get().strip(),
                        dev_repo=self.repo.get().strip(), prismAppImage=self.prism_exe.get().strip(),
                        prismKind=self.prism_kind.get() if hasattr(self, "prism_kind") else self.cfg.get("prismKind", "auto"))
        try:
            config.save(self.cfg)
        except OSError as e:
            self.log(f"can't save the config: {e}")

    def _tab_changed(self, _e=None):
        try:
            self.cfg["tab"] = self.nb.index(self.nb.select())
        except tk.TclError:
            return
        self._changed()
        if TABS[self.cfg["tab"]] == "Logs":
            self.refresh_log_view()

    def _theme_changed(self):
        self.cfg["theme"] = self.theme_var.get()
        self.palette = theme.apply(self.root, self.cfg["theme"])
        for w in (self.log_box, self.log_view):
            self._style_text(w)
        self._changed()

    def _prewarm_changed(self):
        self.cfg["prewarm"] = bool(self.prewarm_var.get())
        self._changed()

    def _update_settings_changed(self):
        self.cfg["updateCheck"] = bool(self.update_check_var.get())
        self.cfg["updateBetas"] = bool(self.update_betas_var.get())
        self._changed()

    # ---- updates (L4, update.py) -------------------------------------------------------------
    def start_update_check(self, force=False, fetch_json=None):
        """On its own daemon thread (the network may take TIMEOUT_S); the result comes back through the
        queue. Not forced: only when the setting is on and the last check is 6 h old."""
        if self._checking_update or (not force and not update.due(self.cfg)):
            return False
        self._checking_update = True
        if force:
            self.log("--- checking for updates")
        cfg = dict(self.cfg)

        def work():
            offer = None
            try:
                offer = update.check(cfg, self.log, force=force, fetch_json=fetch_json)
            finally:
                self.q.put(("update", (offer, cfg.get("updateLastCheck"))))
        threading.Thread(target=work, daemon=True).start()
        return True

    def show_update_offer(self, offer):
        self._offer = offer
        if not offer:
            self.update_bar.pack_forget()
            return
        parts = [p for p, on in (("bundle", offer.get("bundle")), ("launcher", offer.get("launcher"))) if on]
        self.update_label.configure(text=f"Garry's Modcraft {offer['version']} available ({' + '.join(parts)}) —")
        self.update_bar.pack(fill="x", padx=10, pady=(0, 2), before=self.nb)

    def _apply_update(self, fetch_file=None):
        offer = self._offer
        if not offer:
            return False
        cfg = dict(self.cfg)

        def fn():
            try:
                res = update.apply(offer, self.log, fetch_file=fetch_file)
            except update.UpdateError as e:
                raise actions.ActionError(f"update refused: {e}")
            # The restart dialog (update_applied) only after the install finished; the new zip is selected
            # in Setup either way, so a failed install (e.g. new pins) can be retried with Install / Update.
            try:
                if offer.get("bundle"):
                    try:
                        actions.install(dict(cfg, source_kind="bundle", bundle=str(res["zip"])), self.log)
                    except actions.ActionError as e:
                        hint = "restart the launcher, then press" if res["restart"] else "the new bundle is selected in Setup: press"
                        raise actions.ActionError(f"install of {offer['version']} failed: {e} ({hint} Install / Update)")
                    self.log(f"installed {offer['version']}")
            finally:
                self.q.put(("update_applied", (str(res["zip"]), res["restart"])))
        return self.run(f"update to {offer['version']}", fn)

    def _update_applied(self, zip_path, restart, ask=None):
        if self._offer and self._offer.get("bundle"):
            self.bundle.set(zip_path)
            self.kind.set("bundle")
            self._changed()
        self.show_update_offer(None)
        if restart:
            (ask or messagebox.showinfo)("Garry's Modcraft", "The launcher was updated. Close it and start it "
                                         "again to use the new version.", parent=self.root)

    def _pick_bundle(self):
        p = filedialog.askopenfilename(title="Release bundle", filetypes=[("Bundle", "*.zip"), ("All", "*")])
        if p:
            self.bundle.set(p)
            self.kind.set("bundle")
            self._changed()
            self.refresh()

    def _pick_repo(self):
        p = filedialog.askdirectory(title="gmod-craft checkout")
        if p:
            self.repo.set(p)
            self.kind.set("dev")
            self._changed()
            self.refresh()

    def _pick_prism(self):
        p = filedialog.askopenfilename(title="Prism Launcher AppImage / executable")
        if p:
            self.prism_exe.set(p)
            self._changed()
            self.refresh()

    # ---- worker ----------------------------------------------------------------------------
    def log(self, msg):
        self.q.put(("log", str(msg)))

    def run(self, title, fn, refresh_after=True):
        """Start fn on the worker thread. False (and nothing started) while another action runs."""
        if self.busy:
            self.log("busy: wait for the current action to finish")
            return False
        self._changed()
        self.busy = True
        for b in self.buttons:
            b.state(["disabled"])
        self.log(f"--- {title}")

        def work():
            try:
                fn()
            except actions.ActionError as e:
                self.log(f"error: {e}")
            except Exception:
                self.log(traceback.format_exc())
            finally:
                self.q.put(("done", refresh_after))
        threading.Thread(target=work, daemon=True).start()
        return True

    def _poll(self):
        try:
            while True:
                kind, val = self.q.get_nowait()
                if kind == "log":
                    self.log_box.configure(state="normal")
                    self.log_box.insert("end", val + "\n")
                    self.log_box.see("end")
                    self.log_box.configure(state="disabled")
                elif kind == "status":
                    self._show_status(val)
                elif kind == "srvstatus":
                    self.servers.show_status(*val)
                elif kind == "progress_start":
                    self.start_progress(**val)
                elif kind == "progress":
                    self.show_progress(*val)
                elif kind == "logview":
                    self.show_log_view(*val)
                elif kind == "quitstate":
                    self.show_quit_state(val)
                    if val.phase in ("done", "stuck") and getattr(self, "_quit_then_close", False) and val.phase == "done":
                        self.root.after(500, self.close)
                elif kind == "update":
                    offer, last = val
                    self._checking_update = False
                    if last:
                        self.cfg["updateLastCheck"] = last
                        self._changed()
                    self.show_update_offer(offer)
                elif kind == "update_applied":
                    self._update_applied(*val)
                elif kind == "done":
                    self.busy = False
                    for b in self.buttons:
                        b.state(["!disabled"])
                    if val:
                        self.refresh()
        except queue.Empty:
            pass
        try:
            self.root.after(80, self._poll)
        except tk.TclError:
            pass

    def _show_status(self, checks):
        for w in self.status_frame.winfo_children():
            w.destroy()
        colors = {True: self.palette["ok"], False: self.palette["bad"], None: self.palette["warn"]}
        for i, c in enumerate(checks):
            tk.Label(self.status_frame, text="●", fg=colors[c.ok], bg=self.palette["bg"],
                     font=("TkDefaultFont", 12)).grid(row=i, column=0, sticky="w")
            ttk.Label(self.status_frame, text=c.label).grid(row=i, column=1, sticky="w", padx=(2, 10))
            ttk.Label(self.status_frame, text=c.detail, style="Muted.TLabel", wraplength=560).grid(row=i, column=2, sticky="w")
        self.status_frame.columnconfigure(2, weight=1)

    # ---- launch progress ---------------------------------------------------------------------
    def start_progress(self, want_world=True, probes=None, interval=1.0, on_loaded=None, prewarmed=False):
        """Follow a launch on a daemon thread (read-only probes); snapshots come back by queue.
        on_loaded runs once (on that thread) when GMod has loaded the module: by default it wipes the
        connect cfg, whose password GMod has used by then (the client module only starts in game)."""
        self._progress_gen += 1
        gen = self._progress_gen
        tracker = progress.Tracker(probes=probes, want_world=want_world, prewarmed=prewarmed)
        on_loaded = on_loaded or self._wipe_connect_cfg_now
        loaded_seen = [False]

        def loop():
            while gen == self._progress_gen:
                try:
                    snap = tracker.poll()
                except Exception as e:  # noqa: BLE001  (a probe failing must not kill the window)
                    self.log(f"progress: {type(e).__name__}: {e}")
                    return
                if not loaded_seen[0] and snap.steps[1].state == "done":
                    loaded_seen[0] = True
                    try:
                        on_loaded()
                    except Exception as e:  # noqa: BLE001
                        self.log(f"progress: {type(e).__name__}: {e}")
                self.q.put(("progress", (gen, snap)))
                if snap.gone:
                    if snap.finished:
                        return               # GMod closed, Minecraft closed (or left running: see the note)
                elif snap.finished:
                    # Ready: keep watching, slower, so the window can say when GMod closes and Minecraft follows.
                    time.sleep(max(interval, WATCH_INTERVAL_S))
                    continue
                elif snap.now - snap.started > PROGRESS_LIMIT_S:
                    return
                time.sleep(interval)
        threading.Thread(target=loop, daemon=True, name="gmc-progress").start()
        return tracker

    def _wipe_connect_cfg_now(self):
        g = detect.find_gmod()
        if g is None:
            return
        try:
            if passwords.wipe_cfg(g.path):
                self.log("removed the connect cfg (GMod has used the password)")
        except OSError as e:
            self.log(f"can't remove the connect cfg: {e}")

    def show_progress(self, gen, snap):
        if gen != self._progress_gen:
            return
        colors = {"waiting": self.palette["muted"], "active": self.palette["accent"], "done": self.palette["ok"],
                  "slow": self.palette["warn"], "skipped": self.palette["muted"]}
        hint = snap.note
        for (mark, text, when), s in zip(self.progress_rows, snap.steps):
            mark.configure(text=STATE_MARK[s.state], fg=colors[s.state])
            text.configure(text=s.label)
            if s.state == "done":
                when.configure(text=f"{s.done_at:.0f} s")
            elif s.state in ("active", "slow"):
                when.configure(text=f"{snap.now - snap.started:.0f} s" + (" (slow)" if s.state == "slow" else ""))
                if s.state == "slow":
                    hint = s.hint
            else:
                when.configure(text="")
        if snap.finished and not snap.gone:
            ready = [s.done_at for s in snap.steps if s.done_at is not None]
            hint = hint or f"Ready after {max(ready) if ready else snap.now - snap.started:.0f} s."
        self.progress_hint.configure(text=hint or "")

    # ---- logs ----------------------------------------------------------------------------
    def _log_path(self, src=None):
        """No Tk access: called from the log reader thread with src given."""
        if (src or self.log_source.get()) == "mc":
            return logs.mc_log_path(cfg=self.cfg)
        return logs.gmod_log_path()

    def refresh_log_view(self):
        """Read the chosen log's tail now (daemon thread), and keep auto-refreshing (see _log_tick)."""
        self._read_log()
        self._schedule_log_tick()

    def _schedule_log_tick(self):
        if self._log_job is not None:
            try:
                self.root.after_cancel(self._log_job)
            except tk.TclError:
                pass
            self._log_job = None
        if self.log_auto.get() and self._logs_tab_selected():
            try:
                self._log_job = self.root.after(LOG_REFRESH_MS, self._log_tick)
            except tk.TclError:
                pass

    def _log_tick(self):
        """Like ServerPanel._tick: keeps rescheduling while auto-refresh is on and the Logs tab is
        selected; only the read is skipped while the window isn't viewable (hidden, minimised)."""
        self._log_job = None
        if self.servers.visible():
            self._read_log()
        self._schedule_log_tick()

    def _read_log(self):
        if not self._log_reading:
            self._log_reading = True
            src = self.log_source.get()

            def work():
                try:
                    path = self._log_path(src)
                    lines = logs.tail(path) if path else None
                    found = logs.hints(lines or [])
                    secrets = logs.known_secrets()
                    shown = [logs.redact(ln, secrets) for ln in lines] if lines is not None else None
                    self.q.put(("logview", (src, path, shown, found)))
                finally:
                    self._log_reading = False
            threading.Thread(target=work, daemon=True, name="gmc-logview").start()

    def _logs_tab_selected(self):
        try:
            return TABS[self.nb.index(self.nb.select())] == "Logs"
        except tk.TclError:
            return False

    def show_log_view(self, src, path, lines, found):
        if src != self.log_source.get():
            return
        if path is None:
            self.log_path_label.configure(text="Garry's Mod not found" if src == "gmod" else "no Prism instance")
        elif lines is None:
            note = (" — GMod writes it when started from this launcher (-condebug)" if src == "gmod" else "")
            self.log_path_label.configure(text=f"{path}: not there yet{note}")
        else:
            self.log_path_label.configure(text=f"{path} (last {len(lines)} lines, passwords hidden)")
        self.log_hints.configure(text="\n".join("• " + h.text for h in found))
        at_end = self.log_view.yview()[1] >= 0.999
        self.log_view.configure(state="normal")
        self.log_view.delete("1.0", "end")
        if lines:
            self.log_view.insert("end", "\n".join(lines) + "\n")
        self.log_view.configure(state="disabled")
        if at_end:
            self.log_view.see("end")

    def _open_log(self):
        path = self._log_path()
        if path is None or not path.is_file():
            self.log("no log file to open yet")
            return
        try:
            platform.spawn_detached(["xdg-open", str(path)])
        except OSError as e:
            self.log(f"can't open {path}: {e}")

    # ---- actions ---------------------------------------------------------------------------
    def refresh(self):
        def fn():
            src = None
            try:
                src = actions.open_source(self.cfg, lambda m: None)
            except actions.ActionError as e:
                self.log(f"source: {e}")
            try:
                self.q.put(("status", detect.status(self.cfg, src)))
            finally:
                actions.close_source(src)
        self.run("checking", fn, refresh_after=False)

    def _install(self):
        dry, foreign = self.dry.get(), self.foreign.get()
        self.run("install / update" + (" (dry run)" if dry else ""),
                 lambda: actions.install(self.cfg, self.log, dry_run=dry, replace_foreign=foreign))

    def _new_world(self, ask=None):
        label = self.world_type.get()
        wt = next((t for t, v in prism.WORLD_TYPE_LABELS.items() if v == label), "mirror")
        dry = self.dry.get()
        ask = ask or messagebox.askyesno
        if not dry and not ask(
                "Start a new world?",
                f"Minecraft makes a new single-player world ({label.lower()}) on its next start.\n\n"
                "The current world is not deleted: it is moved aside to saves/GmodCraft.bak-<date> in the Prism "
                "instance. Your builds stay in that copy.\n\nContinue?",
                icon="warning", default="no", parent=self.root):
            self.log("new world cancelled")
            return
        self.run("new world" + (" (dry run)" if dry else ""), lambda: actions.new_world(self.cfg, self.log, wt, dry_run=dry))

    def _uninstall(self):
        dry, rm = self.dry.get(), self.rm_instance.get()
        if rm and not dry and not messagebox.askyesno(
                "Remove the Prism instance?",
                "This deletes the Prism instance GmodCraft, including all its Minecraft worlds.\n\nContinue?",
                icon="warning", default="no", parent=self.root):
            self.log("uninstall cancelled")
            return
        self.run("uninstall" + (" (dry run)" if dry else ""),
                 lambda: actions.uninstall(self.cfg, self.log, dry_run=dry, remove_instance=rm))

    def _install_desktop(self):
        dry = self.dry.get()

        def fn():
            try:
                desktop.install(self.log, dry_run=dry)
            except desktop.DesktopError as e:
                raise actions.ActionError(str(e))
        self.run("desktop entry", fn, refresh_after=False)

    def _uninstall_desktop(self):
        dry = self.dry.get()
        self.run("remove desktop entry", lambda: desktop.uninstall(self.log, dry_run=dry), refresh_after=False)

    def launched(self, want_world=True, prewarmed=False):
        """Called from the worker after a successful (non-dry) launch."""
        self.q.put(("progress_start", {"want_world": want_world, "prewarmed": prewarmed}))

    def quit_game(self, ask=None, force=False):
        """Quit Garry's Modcraft (quit.py): asks first, names what it will close, runs on a thread."""
        ask = ask or messagebox.askyesno
        g = quit.find()
        if not g.running:
            self.log("Garry's Modcraft isn't running")
            return False
        parts = ([f"Garry's Mod (pid {', '.join(map(str, g.gmod))})"] if g.gmod else []) + \
                ([f"Minecraft (pid {', '.join(map(str, g.mc))})"] if g.mc else [])
        text = ("Close " + " and ".join(parts) + "?\n\nGarry's Mod is asked to close (unsaved GMod things are lost); "
                "Minecraft saves its world and closes after it.")
        if force:
            text = "Garry's Mod didn't close. Force it to stop now (SIGKILL)?"
        if not ask("Quit Garry's Modcraft?", text, icon="warning", default="no" if force else "yes", parent=self.root):
            return False
        self._progress_gen += 1                  # the launch tracker stops; this shows the quit instead

        mc_log = logs.mc_log_path(cfg=self.cfg)

        def fn():
            # on_state gets every state, the final one included (once)
            quit.quit_game(self.log, on_state=lambda s: self.q.put(("quitstate", s)), force=force, mc_log=mc_log)
        if not self.run("quit game", fn, refresh_after=False):
            messagebox.showinfo("Busy", "The launcher is busy with another action; try Quit game again in a moment.",
                                parent=self.root)
            return False
        return True

    def show_quit_state(self, st):
        self.progress_hint.configure(text=st.note)
        if st.phase == "stuck":
            if "force" in st.note:
                self.root.after(100, self._offer_force)
            else:
                self._quit_then_close = False     # the quit ended without closing: the window stays

    def _offer_force(self):
        if not self.quit_game(force=True):
            self._quit_then_close = False

    def on_close(self, ask=None):
        """Window close: with the game running, ask whether to quit it too or only the launcher."""
        ask = ask or messagebox.askyesnocancel
        try:
            running = quit.find().running
        except Exception:  # noqa: BLE001
            running = False
        if running:
            ans = ask("Garry's Modcraft is running",
                      "Quit the game too?\n\nYes: quit Garry's Mod and Minecraft, then the launcher.\n"
                      "No: close only the launcher (the game keeps running).", icon="question", parent=self.root)
            if ans is None:
                return False
            if ans:
                self._quit_then_close = True
                if not self.quit_game(ask=lambda *a, **k: True):
                    self._quit_then_close = False
                    if not quit.find().running:
                        self.close()      # it had already gone
                    return False          # busy: the user was told; the window stays
                return True
        self.close()
        return True

    def close(self):
        if getattr(self, "_closed", False):
            return
        self._closed = True
        m = GEOMETRY.match(self.root.geometry().split("+")[0])
        state = self.root.state()
        zoomed = state == "zoomed" or self._zoomed_attr()
        if m and state in ("normal", "zoomed"):
            self.cfg["window"] = m.group(0)
            self.cfg["maximized"] = bool(zoomed)
            self._changed()
        self._progress_gen += 1
        self.servers.close()
        self.root.destroy()

    def _zoomed_attr(self):
        try:
            return bool(int(self.root.attributes("-zoomed")))
        except (tk.TclError, TypeError, ValueError):
            return False

    def _play(self):
        dry = self.dry.get()

        def fn():
            res = {}
            actions.play(self.cfg, self.log, dry_run=dry, result=res)
            if not dry:
                self.launched(prewarmed=res.get("prewarm", False))
        self.run("play", fn, refresh_after=False)


def build(cfg, root=None):
    root = root or tk.Tk()
    return App(root, cfg)


def run(cfg):
    app = build(cfg)
    app.refresh()
    app.start_update_check()
    app.root.mainloop()
    return 0
