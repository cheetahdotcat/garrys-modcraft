"""The server list in the launcher window: saved servers, live status, join.

Status is queried every STATUS_PERIOD_MS while the window is visible, off the Tk thread (a small
thread pool); results come back through the App's queue. Passwords live only in secrets.json: the
edit dialog never shows a stored one, and nothing here logs or displays one.
"""
import threading

import tkinter as tk
from tkinter import messagebox, ttk

from . import actions, config, passwords, query, servers

STATUS_PERIOD_MS = 10_000
MAX_PARALLEL = 8                 # servers queried at once
COLUMNS = (("state", "", 70), ("name", "Name", 170), ("address", "Address", 150), ("map", "Map", 110),
           ("gmod", "GMod", 60), ("mc", "MC", 60), ("proto", "Version", 80))


def badge(status):
    if status is None:
        return "…"
    v = status.gmodcraft_version
    if v is None or v[0] == 0:          # no convar / rules off / the server's module isn't loaded
        return "unknown"
    match = status.protocol_match
    if match is None:                   # this launcher doesn't know its own protocol
        return f"v{v[0]}"
    return f"v{v[0]} ok" if match else f"v{v[0]} ≠ v{query.PROTOCOL}"


def row_values(entry, status):
    if status is None:
        return ("…", entry["name"], entry["address"], "", "", "", "…")
    i, mc = status.info, status.mc
    state = "● online" if i.ok else "● offline"
    gm = f"{i.players}/{i.max_players}" if i.ok else ""
    mcp = f"{mc.online}/{mc.max_players}" if mc.ok else ("—" if i.ok else "")
    lock = " 🔒" if i.ok and i.password else ""
    return (state, entry["name"], entry["address"] + lock, i.map if i.ok else "", gm, mcp, badge(status) if i.ok else "")


class ServerPanel:
    def __init__(self, app, parent):
        self.app = app
        self.cfg = app.cfg
        self.status = {}                 # server key -> query.ServerStatus
        self._querying = False
        self._slots = threading.BoundedSemaphore(MAX_PARALLEL)
        self._closed = False
        self._timer = None

        frame = ttk.Frame(parent)
        frame.pack(fill="both", expand=True, padx=4, pady=(0, 4))
        self.tree = ttk.Treeview(frame, columns=[c[0] for c in COLUMNS], show="headings", height=5, selectmode="browse")
        for cid, text, width in COLUMNS:
            self.tree.heading(cid, text=text)
            self.tree.column(cid, width=width, stretch=cid in ("name", "address", "map"))
        self.tree.tag_configure("online", foreground="#2e9d3a")
        self.tree.tag_configure("offline", foreground="#c62828")
        self.tree.tag_configure("mismatch", foreground="#c99a06")
        self.tree.pack(side="left", fill="both", expand=True)
        self.tree.bind("<Double-1>", lambda e: self.join() if self.tree.identify_row(e.y) else None)
        self.tree.bind("<Return>", lambda _e: self.join())
        side = ttk.Frame(frame)
        side.pack(side="left", fill="y", padx=(6, 0))
        self.b_join = ttk.Button(side, text="Join", command=self.join)
        self.b_join.pack(fill="x")
        for text, cmd in (("Add...", self.add), ("Edit...", self.edit), ("Remove", self.remove),
                          ("Up", lambda: self.move(-1)), ("Down", lambda: self.move(1))):
            ttk.Button(side, text=text, command=cmd).pack(fill="x", pady=(2, 0))
        self.fill()
        self._timer = self.app.root.after(500, self._tick)

    # ---- list ------------------------------------------------------------------------------
    @property
    def entries(self):
        return self.cfg["servers"]

    def fill(self, select=None):
        self.tree.delete(*self.tree.get_children())
        for i, e in enumerate(self.entries):
            st = self.status.get(servers.key(e))
            self.tree.insert("", "end", iid=str(i), values=row_values(e, st), tags=self._tags(st))
        if select is not None and 0 <= select < len(self.entries):
            self.tree.selection_set(str(select))
            self.tree.see(str(select))

    def selected(self):
        sel = self.tree.selection()
        return int(sel[0]) if sel else None

    def save(self):
        try:
            config.save(self.cfg)
        except OSError as e:
            self.app.log(f"can't save the config: {e}")

    # ---- edits (dialog-free, so tests can drive them) ----------------------------------------
    def apply_edit(self, index, name, address, mc_port, password="", forget_password=False):
        """Add (index None) or change a server; password "" keeps a stored one (moved along when
        the address changes). Raises servers.ServerError / passwords.PasswordError before
        anything is changed."""
        entry = servers.make(name, address, mc_port)
        if password:
            passwords.check(password)
        lst = list(self.entries)
        old = lst[index] if index is not None else None
        servers.put(lst, entry, index)
        new_key = servers.key(entry)
        if old is not None and servers.key(old) != new_key:
            moved = passwords.stored(old["address"])
            passwords.forget(old["address"])
            if moved and not password and not forget_password:
                passwords.remember(entry["address"], moved)
            self.status.pop(servers.key(old), None)
        if forget_password:
            passwords.forget(entry["address"])
        elif password:
            passwords.remember(entry["address"], password)
        self.cfg["servers"] = lst
        self.save()
        pos = index if index is not None else len(lst) - 1
        self.fill(select=pos)
        self.query([entry])
        return pos

    def remove_at(self, index):
        lst = list(self.entries)
        old = lst.pop(index)
        try:
            passwords.forget(old["address"])
        except OSError as e:
            self.app.log(f"can't drop the stored password: {e}")
        self.status.pop(servers.key(old), None)
        self.cfg["servers"] = lst
        self.save()
        self.fill(select=min(index, len(lst) - 1))

    def move(self, delta):
        i = self.selected()
        if i is None:
            return
        lst = list(self.entries)
        j = servers.move(lst, i, delta)
        self.cfg["servers"] = lst
        self.save()
        self.fill(select=j)

    # ---- buttons -----------------------------------------------------------------------------
    def add(self):
        return ServerDialog(self, None)

    def edit(self):
        i = self.selected()
        if i is not None:
            return ServerDialog(self, i)

    def remove(self):
        i = self.selected()
        if i is None:
            return
        e = self.entries[i]
        if messagebox.askyesno("Remove server?", f"Remove {e['name']} ({e['address']}) and its saved password?",
                               parent=self.app.root):
            self.remove_at(i)

    def join(self, index=None, ask=messagebox.askyesno):
        i = self.selected() if index is None else index
        if i is None:
            self.app.log("choose a server in the list first")
            return False
        e = self.entries[i]
        st = self.status.get(servers.key(e))
        if st is not None and st.protocol_match is False:
            v = st.gmodcraft_version
            if not ask("Different version",
                       f"{e['name']} runs Garry's Modcraft protocol {v[0]} ({v[1]}); this launcher's is "
                       f"{query.PROTOCOL}. Joining will most likely fail.\n\nJoin anyway?",
                       icon="warning", default="no", parent=self.app.root):
                self.app.log("join cancelled")
                return False
        if st is not None and st.info.ok and st.info.password and not passwords.stored(e["address"]):
            self.app.log(f"{e['name']} wants a password: add it with Edit...")
        dry, address = self.app.dry.get(), e["address"]
        self.app.run(f"join {e['name']} ({address})", lambda: self._join_action(address, dry), refresh_after=False)
        return True

    def _join_action(self, address, dry):
        """Worker thread: the password is read here and only handed to actions.play (the cfg)."""
        try:
            pw = passwords.stored(address)
        except OSError as ex:
            self.app.log(f"can't read the stored passwords: {ex}")
            pw = ""
        res = {}
        actions.play(self.cfg, self.app.log, server=address, password=pw or None, dry_run=dry, result=res)
        if not dry and hasattr(self.app, "launched"):
            self.app.launched(prewarmed=res.get("prewarm", False))

    # ---- status ------------------------------------------------------------------------------
    def visible(self):
        try:
            return bool(self.app.root.winfo_viewable()) and self.app.root.state() != "iconic"
        except tk.TclError:
            return False

    def _tick(self):
        self._timer = None
        try:
            if self.visible() and not self._querying:
                self.query(list(self.entries))
        finally:
            try:
                self._timer = self.app.root.after(STATUS_PERIOD_MS, self._tick)
            except tk.TclError:
                pass

    def query(self, entries):
        """Query entries off the Tk thread, on daemon threads (at most MAX_PARALLEL at once); results
        arrive as ("srvstatus", (key, status)) messages."""
        if not entries or self._closed:
            return
        self._querying = True
        q = self.app.q
        pending = [len(entries)]
        lock = threading.Lock()

        def one(e):
            try:
                with self._slots:
                    if self._closed:
                        return
                    host, port = servers.host_port(e)
                    q.put(("srvstatus", (servers.key(e), query.server_status(host, port, e["mc_port"]))))
            except Exception:  # noqa: BLE001  (server_status never raises; a bad entry just shows nothing)
                pass
            finally:
                with lock:
                    pending[0] -= 1
                    if pending[0] == 0:
                        self._querying = False
        for e in entries:
            threading.Thread(target=one, args=(e,), daemon=True, name="gmc-query").start()

    def show_status(self, key, status):
        idx = next((i for i, e in enumerate(self.entries) if servers.key(e) == key), None)
        if idx is None:
            return   # removed meanwhile
        self.status[key] = status
        if not self.tree.exists(str(idx)):
            self.fill(select=self.selected())
            return
        self.tree.item(str(idx), values=row_values(self.entries[idx], status), tags=self._tags(status))

    @staticmethod
    def _tags(st):
        if st is None:
            return ()
        if st.info.ok and st.protocol_match is False:
            return ("mismatch",)
        return ("online" if st.info.ok else "offline",)

    def close(self):
        if self._timer is not None:
            try:
                self.app.root.after_cancel(self._timer)
            except tk.TclError:
                pass
        self._closed = True


class ServerDialog:
    """Add / edit one server. A stored password is never shown: leave the field empty to keep it."""

    def __init__(self, panel, index, show=True):
        """index None adds a server. The edited server is remembered by its key, not its row: if it
        is removed (or the list changes) meanwhile, Save refuses instead of changing another one."""
        self.panel = panel
        root = panel.app.root
        e = panel.entries[index] if index is not None else {"name": "", "address": "", "mc_port": servers.DEFAULT_MC_PORT}
        self.key = servers.key(e) if index is not None else None
        self.top = top = tk.Toplevel(root)
        if not show:
            top.withdraw()
        top.title("Edit server" if self.key is not None else "Add server")
        top.transient(root)
        top.resizable(False, False)
        self.name = tk.StringVar(value=e["name"])
        self.address = tk.StringVar(value=e["address"])
        self.mc_port = tk.StringVar(value=str(e["mc_port"]))
        self.password = tk.StringVar(value="")
        self.forget = tk.BooleanVar(value=False)
        has_pw = bool(index is not None and passwords.stored(e["address"]))
        rows = (("Name", self.name, {}), ("Address (host:port)", self.address, {}),
                ("Minecraft port", self.mc_port, {}), ("Password", self.password, {"show": "•"}))
        for i, (label, var, kw) in enumerate(rows):
            ttk.Label(top, text=label).grid(row=i, column=0, sticky="w", padx=8, pady=3)
            entry = ttk.Entry(top, textvariable=var, width=30, **kw)
            entry.grid(row=i, column=1, sticky="ew", padx=8, pady=3)
        self.password_entry = entry
        hint = "a password is saved: leave empty to keep it" if has_pw else "optional; saved for this server only"
        ttk.Label(top, text=hint, foreground="#555").grid(row=4, column=1, sticky="w", padx=8)
        if has_pw:
            ttk.Checkbutton(top, text="Forget the saved password", variable=self.forget,
                            command=self._forget_toggled).grid(row=5, column=1, sticky="w", padx=8)
        self.error = ttk.Label(top, text="", foreground="#c62828")
        self.error.grid(row=6, column=0, columnspan=2, sticky="w", padx=8)
        bar = ttk.Frame(top)
        bar.grid(row=7, column=0, columnspan=2, sticky="e", padx=8, pady=8)
        ttk.Button(bar, text="Cancel", command=top.destroy).pack(side="right")
        ttk.Button(bar, text="Save", command=self.ok).pack(side="right", padx=6)
        top.bind("<Return>", lambda _e: self.ok())
        top.bind("<Escape>", lambda _e: top.destroy())
        if show:
            try:
                top.grab_set()          # modal: the list can't change under an open dialog
            except tk.TclError:
                pass

    def _forget_toggled(self):
        if self.forget.get():
            self.password.set("")
            self.password_entry.state(["disabled"])
        else:
            self.password_entry.state(["!disabled"])

    def ok(self):
        index = None
        if self.key is not None:
            index = next((i for i, e in enumerate(self.panel.entries) if servers.key(e) == self.key), None)
            if index is None:
                self.error.configure(text="this server was removed from the list meanwhile")
                return False
        try:
            self.panel.apply_edit(index, self.name.get(), self.address.get(), self.mc_port.get(),
                                  "" if self.forget.get() else self.password.get(), self.forget.get())
        except (servers.ServerError, passwords.PasswordError) as e:
            self.error.configure(text=str(e))
            return False
        except OSError as e:
            self.error.configure(text=f"can't save: {e}")
            return False
        self.password.set("")
        self.top.destroy()
        return True
