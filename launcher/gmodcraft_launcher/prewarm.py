"""Minecraft pre-warm (D-027): on Play / Join the launcher starts the Prism instance GmodCraft in
parallel with Steam/GMod, with the same command the GMod module uses (client.cpp LaunchMc).

Handoff: Minecraft takes the fcntl lock /dev/shm/gmodcraft/minecraft-client.lock in mod init
(GmodCraftClient.onInitializeClient -> ClientLink.announceRunning), about 10 s after its JVM starts.
GMod's client/launch.lua starts Minecraft at InitPostEntity only when nobody holds that lock, so a
pre-warmed Minecraft is adopted with no marker file. If Prism fails, nobody holds the lock and GMod
starts Minecraft itself, as before.

The lock is only PROBED (F_GETLK on a read-only descriptor): never locked, created, written or
unlinked by the launcher (a trial lock could make a starting Minecraft's tryLock fail).

Windows (the file lives in %LOCALAPPDATA%\\garrys-modcraft\\run): there is no F_GETLK, so the
probe is a non-blocking SHARED LockFileEx on byte 0, released at once; it only fails while another
process holds an exclusive lock there. The holder's pid isn't available (the result is 1).
Unverified on a real machine.
"""
import os
import shutil
import struct
from pathlib import Path

try:
    import fcntl
except ImportError:      # Windows: no fcntl; the lock is probed with LockFileEx (_win_lock_held)
    fcntl = None

from . import detect, platform, prism

LOCK_PATH = platform.run_dir() / "minecraft-client.lock"
INSTANCE = prism.INSTANCE
# struct flock on Linux x86-64: short l_type, short l_whence, off_t l_start, off_t l_len, pid_t l_pid
_FLOCK = struct.Struct("hhqqi4x")


def _win_lock_held(fd):
    """True when another process holds an exclusive lock on byte 0 of the file, False when a shared
    try-lock succeeds (and is released at once), None when it can't be told."""
    import ctypes
    import msvcrt
    from ctypes import wintypes

    class OVERLAPPED(ctypes.Structure):
        _fields_ = [("Internal", ctypes.c_size_t), ("InternalHigh", ctypes.c_size_t), ("Offset", wintypes.DWORD),
                    ("OffsetHigh", wintypes.DWORD), ("hEvent", wintypes.HANDLE)]

    k = ctypes.WinDLL("kernel32", use_last_error=True)
    k.LockFileEx.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD,
                             ctypes.POINTER(OVERLAPPED)]
    k.LockFileEx.restype = wintypes.BOOL
    k.UnlockFileEx.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD,
                               ctypes.POINTER(OVERLAPPED)]
    k.UnlockFileEx.restype = wintypes.BOOL
    h = msvcrt.get_osfhandle(fd)
    ov = OVERLAPPED()
    LOCKFILE_FAIL_IMMEDIATELY = 0x1
    if k.LockFileEx(h, LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, ctypes.byref(ov)):
        k.UnlockFileEx(h, 0, 1, 0, ctypes.byref(ov))
        return False
    err = ctypes.get_last_error()
    return True if err in (32, 33) else None      # ERROR_SHARING_VIOLATION, ERROR_LOCK_VIOLATION


def lock_holder(path=None):
    """pid (> 0) of the process holding the Minecraft lock, 0 when free (or no file), None when it
    can't be told (other OS, probe failed). Windows: 1 when held (pid unknown)."""
    if platform.SYSTEM not in ("linux", "windows"):
        return None
    path = LOCK_PATH if path is None else path
    try:
        fd = os.open(path, platform.read_open_flags() | getattr(os, "O_CLOEXEC", 0))
    except FileNotFoundError:
        return 0
    except OSError:
        return None
    try:
        if platform.SYSTEM == "windows":
            held = _win_lock_held(fd)
            return None if held is None else (1 if held else 0)
        res = fcntl.fcntl(fd, fcntl.F_GETLK, _FLOCK.pack(fcntl.F_WRLCK, os.SEEK_SET, 0, 1, 0))
        l_type, _w, _s, _l, l_pid = _FLOCK.unpack(res)
        if l_type == fcntl.F_UNLCK:
            return 0
        return l_pid if l_pid > 0 else 1
    except OSError:
        return None
    finally:
        os.close(fd)          # never held a lock on it: closing releases nothing


def command(prism):
    """The module's command: systemd-run --user --collect --quiet -- <prism command> --launch GmodCraft
    (a detached user unit), or Prism directly when there's no systemd-run (always on Windows, where
    spawn_detached detaches it). prism: a detect.PrismInstall, an argv list, or a path."""
    argv = list(prism.command) if hasattr(prism, "command") else (list(prism) if isinstance(prism, (list, tuple)) else [str(prism)])
    sr = shutil.which("systemd-run") if platform.SYSTEM == "linux" else None
    base = [*argv, "--launch", INSTANCE]
    if sr:
        return [sr, "--user", "--collect", "--quiet", "--", *base]
    return base


def running_reason(lock_path=None):
    """Why not to start another Minecraft (None when nothing runs)."""
    holder = lock_holder(lock_path)
    if holder:
        return f"Minecraft is already running (pid {holder})" if holder > 1 else "Minecraft is already running"
    if platform.java_with_arg_running(prism.JVM_ARG):
        return "the GmodCraft Minecraft is already starting"
    if platform.prism_running():
        return "Prism is already open (GMod will start Minecraft through it)"
    return None


def start(cfg, log, dry_run=False, lock_path=None):
    """Start the Prism instance unless off, unknown or already running. Never raises.
    Returns True when Minecraft was started (or would be, on a dry run)."""
    if not cfg.get("prewarm", True):
        return False
    if platform.SYSTEM not in ("linux", "windows"):
        return False
    try:
        why = running_reason(lock_path)
        if why:
            log(f"pre-warm: not starting Minecraft: {why}")
            return False
        inst = detect.find_prism(cfg)
        if inst is None or not Path(inst.exe).is_file() or not platform.is_executable(inst.exe):
            log("pre-warm: Prism Launcher not found (Setup tab); GMod starts Minecraft itself")
            return False
        argv = command(inst)
        if dry_run:
            log("pre-warm (dry run): would start " + " ".join(argv))
            return True
        platform.spawn_detached(argv)
        log("pre-warm: starting Minecraft (Prism instance GmodCraft) alongside Garry's Mod")
        return True
    except Exception as e:  # noqa: BLE001  (pre-warm failing must never stop the GMod launch)
        log(f"pre-warm: couldn't start Minecraft ({type(e).__name__}: {e}); GMod starts it itself")
        return False
