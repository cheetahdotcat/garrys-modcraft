"""Minecraft pre-warm (D-027): on Play / Join the launcher starts the Prism instance GmodCraft in
parallel with Steam/GMod, with the same command the GMod module uses (client.cpp LaunchMc).

Handoff: Minecraft takes the fcntl lock /dev/shm/gmodcraft/minecraft-client.lock in mod init
(GmodCraftClient.onInitializeClient -> ClientLink.announceRunning), about 10 s after its JVM starts.
GMod's client/launch.lua starts Minecraft at InitPostEntity only when nobody holds that lock, so a
pre-warmed Minecraft is adopted with no marker file. If Prism fails, nobody holds the lock and GMod
starts Minecraft itself, as before.

The lock is only PROBED (F_GETLK on a read-only descriptor): never locked, created, written or
unlinked by the launcher (a trial lock could make a starting Minecraft's tryLock fail).
"""
import fcntl
import os
import shutil
import struct
from pathlib import Path

from . import detect, platform, prism

LOCK_PATH = Path("/dev/shm/gmodcraft/minecraft-client.lock")
INSTANCE = prism.INSTANCE
# struct flock on Linux x86-64: short l_type, short l_whence, off_t l_start, off_t l_len, pid_t l_pid
_FLOCK = struct.Struct("hhqqi4x")


def lock_holder(path=None):
    """pid (> 0) of the process holding the Minecraft lock, 0 when free (or no file), None when it
    can't be told (other OS, probe failed)."""
    if platform.SYSTEM != "linux":
        return None
    path = LOCK_PATH if path is None else path
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0) | os.O_CLOEXEC)
    except FileNotFoundError:
        return 0
    except OSError:
        return None
    try:
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
    (a detached user unit), or Prism directly when there's no systemd-run. prism: a detect.PrismInstall,
    an argv list, or a path."""
    argv = list(prism.command) if hasattr(prism, "command") else (list(prism) if isinstance(prism, (list, tuple)) else [str(prism)])
    sr = shutil.which("systemd-run")
    base = [*argv, "--launch", INSTANCE]
    if sr:
        return [sr, "--user", "--collect", "--quiet", "--", *base]
    return base


def running_reason(lock_path=None):
    """Why not to start another Minecraft (None when nothing runs)."""
    holder = lock_holder(lock_path)
    if holder:
        return f"Minecraft is already running (pid {holder})"
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
    if platform.SYSTEM != "linux":
        return False
    try:
        why = running_reason(lock_path)
        if why:
            log(f"pre-warm: not starting Minecraft: {why}")
            return False
        inst = detect.find_prism(cfg)
        if inst is None or not Path(inst.exe).is_file() or not os.access(inst.exe, os.X_OK):
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
