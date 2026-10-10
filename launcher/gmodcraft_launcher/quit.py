"""Quit Garry's Modcraft from the launcher (L2): Garry's Mod first, then Minecraft.

Only processes that clearly belong to Garry's Modcraft are touched, found in /proc (never by name alone),
owned by this user, and re-checked (same start time) right before each signal:
  * Garry's Mod: comm "gmod" / "gmod_linux64" AND its executable or command line is inside a GarrysMod folder;
  * Minecraft: a java process whose command line has -Dgmodcraft.startHidden=true (the GmodCraft instance's
    JVM argument; nothing else uses it).

Garry's Mod: first the gentle way, a quit marker <run dir>/quit-<pid> (platform.run_dir(): /dev/shm/gmodcraft
on Linux, %LOCALAPPDATA%/garrys-modcraft/run on Windows) that the client module turns
into the engine's "quit" (config.cfg gets written; module/source/quitmarker.hpp; only works in game), for
MARKER_WAIT_S; then SIGTERM. Minecraft then saves and quits by itself (fabric QuitPolicy, ~15 s after its
link went down); it gets SIGTERM only if it's still there after MC_GRACE_S and hasn't begun stopping
("Stopping!" in its latest.log). SIGKILL only when the caller asks (force), after a confirmation.

Windows (unverified on a real machine): processes come from Toolhelp32 (gmod.exe in a GarrysMod folder; the
managed Temurin's java.exe/javaw.exe for Minecraft), "SIGTERM" is `taskkill /PID` (WM_CLOSE), the hard stop is
TerminateProcess; a process is matched against pid reuse by its creation time.
"""
import os
import stat
import re
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import platform, prism, safeio

GMOD_COMMS = ("gmod", "gmod_linux64")
MARKER_DIR = platform.run_dir()
MARKER_WAIT_S = 10
GMOD_GRACE_S = 20
MC_GRACE_S = 90          # QuitPolicy's 15-30 s + saving the world


@dataclass
class Game:
    gmod: list = field(default_factory=list)
    mc: list = field(default_factory=list)
    starts: dict = field(default_factory=dict)     # pid -> start time (/proc/PID/stat field 22)

    @property
    def running(self):
        return bool(self.gmod or self.mc)


def _read(p):
    try:
        return p.read_bytes()
    except OSError:
        return b""


def start_time(proc, pid):
    """/proc/PID/stat field 22 (start time in clock ticks), or None."""
    stat = _read(Path(proc) / str(pid) / "stat").decode(errors="replace")
    close = stat.rfind(")")
    if close < 0:
        return None
    fields = stat[close + 2:].split()
    try:
        return int(fields[19])           # field 22 overall; fields after "(comm)" start at field 3
    except (IndexError, ValueError):
        return None


def _find_windows():
    g = Game()
    try:
        procs = platform.win_processes()
        for pid, name in procs:
            if name.lower() == "gmod.exe" and "garrysmod" in platform.win_image_path(pid).lower():
                g.gmod.append(pid)
                g.starts[pid] = platform.win_start_time(pid)
        for pid in platform.win_game_java_pids():
            g.mc.append(pid)
            g.starts[pid] = platform.win_start_time(pid)
    except (OSError, AttributeError, ImportError):
        pass
    g.gmod.sort()
    g.mc.sort()
    return g


def find(proc=Path("/proc"), uid=None):
    """The Garry's Modcraft processes running now (this user's only)."""
    if platform.SYSTEM == "windows":
        return _find_windows()
    uid = os.getuid() if uid is None else uid
    g = Game()
    try:
        entries = [p for p in Path(proc).iterdir() if p.name.isdigit()]
    except OSError:
        return g
    for p in entries:
        try:
            if os.stat(p).st_uid != uid:
                continue
        except OSError:
            continue
        comm = _read(p / "comm").decode(errors="replace").strip()
        pid = int(p.name)
        if comm in GMOD_COMMS:
            try:
                exe = os.readlink(p / "exe")
            except OSError:
                exe = ""
            cmd = _read(p / "cmdline").replace(b"\0", b" ").decode(errors="replace")
            if "GarrysMod" in exe or "GarrysMod" in cmd:
                g.gmod.append(pid)
                g.starts[pid] = start_time(proc, pid)
        elif comm == "java":
            if prism.JVM_ARG.encode() in _read(p / "cmdline").split(b"\0"):
                g.mc.append(pid)
                g.starts[pid] = start_time(proc, pid)
    g.gmod.sort()
    g.mc.sort()
    return g


@dataclass
class QuitState:
    phase: str          # "gmod" | "mc" | "done" | "stuck"
    note: str
    game: Game


STOPPING = re.compile(r"^\[[0-9:]+\] \[Render thread/INFO\]: Stopping!\s*$", re.M)


def mc_stopping(mc_log):
    """Minecraft has begun its normal shutdown: its own "[hh:mm:ss] [Render thread/INFO]: Stopping!" line
    (not chat text) in the last 64 KiB of latest.log."""
    if mc_log is None:
        return False
    try:
        with open(mc_log, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 64 * 1024))
            tail = f.read().decode(errors="replace")
    except OSError:
        return False
    return bool(STOPPING.search(tail))


def private_dir(d, uid=None):
    """The dir is a real directory (lstat: no symlink), ours, mode exactly 0700. /dev/shm is world-writable:
    a dir another user created first could have files renamed into it (module/source/privatedir.hpp).
    Windows: a real directory (no symlink or junction) under the user's own %LOCALAPPDATA%, whose ACL is the
    user's (there are no POSIX owner/mode bits to check)."""
    try:
        st = os.lstat(d)
    except OSError:
        return False
    if platform.SYSTEM == "windows":
        isjunction = getattr(os.path, "isjunction", lambda _p: False)
        return stat.S_ISDIR(st.st_mode) and not os.path.islink(d) and not isjunction(d)
    uid = os.getuid() if uid is None else uid
    return stat.S_ISDIR(st.st_mode) and st.st_uid == uid and stat.S_IMODE(st.st_mode) == 0o700


def write_marker(pid, marker_dir, uid=None):
    """The gentle-quit request for GMod pid (0600, never through a link). Returns its path or None."""
    d = Path(marker_dir)
    if not private_dir(d, uid):
        return None
    p = d / f"quit-{pid}"
    try:
        safeio.write_bytes(p, b"quit\n", mode=0o600)
        return p
    except OSError:
        return None


def quit_game(log, finder=find, kill=platform.kill_process, sleep=time.sleep, now=time.monotonic, on_state=None, force=False,
              still_same=None, mc_log=None, marker_dir=None, proc=Path("/proc")):
    """Ends Garry's Mod, then Minecraft. Returns the final QuitState (also passed to on_state with progress).
    still_same(pid, start) re-checks a process before it is signalled (default: its /proc start time)."""
    marker_dir = MARKER_DIR if marker_dir is None else marker_dir
    if platform.SYSTEM == "windows":
        still_same = still_same or (lambda pid, st: st is None or platform.win_start_time(pid) == st)
    else:
        still_same = still_same or (lambda pid, st: st is None or start_time(proc, pid) == st)

    def state(phase, note, game):
        st = QuitState(phase, note, game)
        if on_state:
            on_state(st)
        return st

    def signal_all(pids, starts, sig, what):
        for pid in pids:
            if not still_same(pid, starts.get(pid)):
                log(f"quit: pid {pid} is no longer {what}; left alone")
                continue
            try:
                kill(pid, sig)
                log(f"quit: {'asked' if sig == platform.TERM else 'forced'} {what} (pid {pid}) to "
                    f"{'close (SIGTERM)' if sig == platform.TERM else 'stop (SIGKILL)'}")
            except ProcessLookupError:
                pass
            except OSError as e:
                log(f"quit: can't signal {what} pid {pid}: {e}")

    def wait(phase, what, grace, pick):
        end = now() + grace
        while True:
            g = finder()
            if not pick(g):
                return g, True
            left = end - now()
            if left <= 0:
                return g, False
            state(phase, f"waiting for {what} to close ({left:.0f} s)", g)
            sleep(0.5)

    g = finder()
    if not g.running:
        return state("done", "Garry's Modcraft isn't running", g)
    if g.gmod:
        markers = [m for m in (write_marker(pid, marker_dir) for pid in g.gmod) if m]
        ok = False
        if markers:
            log("quit: asked Garry's Mod to quit (quit marker; it saves its settings)")
            g, ok = wait("gmod", "Garry's Mod", MARKER_WAIT_S, lambda x: x.gmod)
            for m in markers:
                safeio.remove(m)
        if not ok and g.gmod:
            signal_all(g.gmod, g.starts, platform.TERM, "Garry's Mod")
            g, ok = wait("gmod", "Garry's Mod", GMOD_GRACE_S, lambda x: x.gmod)
            if not ok:
                if not force:
                    return state("stuck", "Garry's Mod didn't close; try again to force it", g)
                signal_all(g.gmod, g.starts, platform.KILL, "Garry's Mod")
                g, _ = wait("gmod", "Garry's Mod", 5, lambda x: x.gmod)
    if g.mc:
        state("mc", "Garry's Mod is closed; Minecraft saves and closes in about 15 s", g)
        g, ok = wait("mc", "Minecraft (saving)", MC_GRACE_S, lambda x: x.mc)
        if not ok:
            if mc_stopping(mc_log):
                g, ok = wait("mc", "Minecraft (stopping)", 60, lambda x: x.mc)
            if not ok:
                signal_all(g.mc, g.starts, platform.TERM, "Minecraft")
                g, ok = wait("mc", "Minecraft", 20, lambda x: x.mc)
                if not ok:
                    return state("stuck", "Minecraft didn't close; close it from its window or Prism", g)
    return state("done", "Garry's Modcraft has closed", g)
