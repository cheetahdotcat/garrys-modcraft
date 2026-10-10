"""Everything that differs between operating systems lives here: folders, file names, process
checks and how Steam is started. Linux is the tested system. Windows (64-bit) is implemented
with the standard library only (winreg, ctypes: Toolhelp32, kernel32) and tested on Linux with mocks;
facts nobody has run on a real Windows yet are marked "unverified". macOS stays a placeholder and
returns None where it doesn't know.

Environment overrides (tests, unusual installs):
  GMODCRAFT_CONFIG_DIR   launcher config + install state (default ~/.config/garrys-modcraft)
  GMODCRAFT_DATA_DIR     backups and unpacked bundles   (default ~/.local/share/garrys-modcraft)
  GMODCRAFT_STEAM_ROOT   one Steam root to use instead of the usual places
  GMOD_DIR               the GarrysMod folder itself (same name as module/install.sh uses)
  PRISM_DATA             Prism Launcher's data folder (same name as tools/setup_prism.sh uses)
"""
import os
import shutil
import signal
import subprocess
import sys
from pathlib import Path

if sys.platform.startswith("linux"):
    SYSTEM = "linux"
elif sys.platform == "win32":
    SYSTEM = "windows"
elif sys.platform == "darwin":
    SYSTEM = "macos"
else:
    SYSTEM = sys.platform

APP = "garrys-modcraft"
GMOD_APPID = "4000"


def home():
    return Path.home()


def _xdg(var, default):
    v = os.environ.get(var)
    return Path(v) if v else home() / default


def _localappdata():
    return Path(os.environ.get("LOCALAPPDATA") or home() / "AppData" / "Local")


def config_dir():
    if os.environ.get("GMODCRAFT_CONFIG_DIR"):
        return Path(os.environ["GMODCRAFT_CONFIG_DIR"])
    if SYSTEM == "windows":
        return Path(os.environ.get("APPDATA", home())) / APP
    if SYSTEM == "macos":
        return home() / "Library" / "Application Support" / APP
    return _xdg("XDG_CONFIG_HOME", ".config") / APP


def data_dir():
    if os.environ.get("GMODCRAFT_DATA_DIR"):
        return Path(os.environ["GMODCRAFT_DATA_DIR"])
    if SYSTEM == "windows":
        return Path(os.environ.get("LOCALAPPDATA", home())) / APP
    if SYSTEM == "macos":
        return home() / "Library" / "Application Support" / APP / "data"
    return _xdg("XDG_DATA_HOME", ".local/share") / APP


def mod_cache_dir():
    """Shared with tools/setup_prism.sh (~/.cache/gmodcraft/mods)."""
    if SYSTEM == "windows":
        return Path(os.environ.get("LOCALAPPDATA", home())) / "gmodcraft" / "cache" / "mods"
    if SYSTEM == "macos":
        return home() / "Library" / "Caches" / "gmodcraft" / "mods"
    return _xdg("XDG_CACHE_HOME", ".cache") / "gmodcraft" / "mods"


def run_dir():
    """Where the link's discovery file and the quit markers live: /dev/shm/gmodcraft on Linux,
    %LOCALAPPDATA%\\garrys-modcraft\\run on Windows (the C++ module and the Java mod use the same)."""
    if SYSTEM == "windows":
        return _localappdata() / APP / "run"
    if SYSTEM == "macos":
        return Path("/tmp") / "gmodcraft"      # placeholder: macOS is not ported yet
    return Path("/dev/shm/gmodcraft")


def shm_dir():
    """The folder holding the link segments that the discovery file names (the launcher only reads them).
    Linux: /dev/shm. Windows: the run dir (the link is file-backed; not yet verified on Windows)."""
    return run_dir() if SYSTEM == "windows" else Path("/dev/shm")


# ---- small file helpers that differ on Windows (no pread; text mode unless O_BINARY) ---------------

def read_open_flags():
    """Flags for a read-only os.open that never follows a symlink and never blocks (O_NOFOLLOW and
    O_NONBLOCK exist on POSIX only; O_BINARY on Windows only)."""
    return os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_BINARY", 0)


def pread(fd, n, offset):
    """os.pread, or seek + read where it doesn't exist (Windows)."""
    f = getattr(os, "pread", None)
    if f is not None:
        return f(fd, n, offset)
    os.lseek(fd, offset, os.SEEK_SET)
    return os.read(fd, n)


# ---- Steam / GMod ---------------------------------------------------------------------------

def _steam_reg_value(name):
    """A string value of HKCU\\Software\\Valve\\Steam, or None. Unverified on a real machine: SteamPath
    holds the install folder with forward slashes, SteamExe the path of steam.exe."""
    try:
        import winreg
    except ImportError:
        return None
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
            v, _kind = winreg.QueryValueEx(k, name)
    except OSError:
        return None
    return v if isinstance(v, str) and v else None


def steam_path_registry():
    return _steam_reg_value("SteamPath") if SYSTEM == "windows" else None


def steam_exe_registry():
    return _steam_reg_value("SteamExe") if SYSTEM == "windows" else None


def steam_roots():
    """Candidate Steam installs, most likely first. Missing ones are filtered by the caller."""
    if os.environ.get("GMODCRAFT_STEAM_ROOT"):
        return [Path(os.environ["GMODCRAFT_STEAM_ROOT"])]
    h = home()
    if SYSTEM == "windows":
        out = []
        reg = steam_path_registry()
        if reg:
            out.append(Path(reg))
        for var, default in (("ProgramFiles(x86)", r"C:\Program Files (x86)"), ("ProgramFiles", r"C:\Program Files")):
            c = Path(os.environ.get(var, default)) / "Steam"
            if c not in out:
                out.append(c)
        return out
    if SYSTEM == "macos":
        return [h / "Library" / "Application Support" / "Steam"]
    return [
        h / ".local/share/Steam",
        h / ".steam/steam",
        h / ".steam/root",
        h / ".var/app/com.valvesoftware.Steam/.local/share/Steam",  # Flatpak
        h / "snap/steam/common/.local/share/Steam",                  # Snap
    ]


def gmod_dir_override():
    v = os.environ.get("GMOD_DIR")
    return Path(v) if v else None


def module_arch():
    """GMod's binary module suffix for this OS (64-bit branch)."""
    return {"linux": "linux64", "windows": "win64", "macos": "osx64"}.get(SYSTEM, "linux64")


def module_names(arch=None):
    arch = arch or module_arch()
    return [f"gmcl_gmodcraft_{arch}.dll", f"gmsv_gmodcraft_{arch}.dll"]


def gmod_is_64bit(gmod_dir):
    """The x86-64 branch ships 64-bit binaries under bin/<linux64|win64>. macOS: unknown."""
    sub = {"linux": "linux64", "windows": "win64"}.get(SYSTEM)
    if sub is None:
        return None
    return (Path(gmod_dir) / "bin" / sub).is_dir()


def steam_launch_command(steam_root, game_args):
    """argv that asks Steam to start GMod with game_args (no shell involved)."""
    if SYSTEM == "windows":
        cands = []
        if steam_root:
            cands.append(Path(steam_root) / "steam.exe")
        cands += [Path(r) / "steam.exe" for r in steam_roots()]
        reg = steam_exe_registry()
        if reg:
            cands.append(Path(reg))
        for exe in cands:
            if exe.exists():
                return [str(exe), "-applaunch", GMOD_APPID, *game_args]
        return None
    if SYSTEM == "macos":
        return ["open", "-a", "Steam", "--args", "-applaunch", GMOD_APPID, *game_args]
    steam = shutil.which("steam")
    if steam:
        return [steam, "-applaunch", GMOD_APPID, *game_args]
    if shutil.which("flatpak"):
        return ["flatpak", "run", "com.valvesoftware.Steam", "-applaunch", GMOD_APPID, *game_args]
    return None


# ---- Prism / Java ---------------------------------------------------------------------------

PRISM_EXE_WINDOWS = "prismlauncher.exe"


def prism_data_default():
    if os.environ.get("PRISM_DATA"):
        return Path(os.environ["PRISM_DATA"])
    h = home()
    if SYSTEM == "windows":
        return Path(os.environ.get("APPDATA", h)) / "PrismLauncher"      # installer; unverified
    if SYSTEM == "macos":
        return h / "Library" / "Application Support" / "PrismLauncher"
    native = _xdg("XDG_DATA_HOME", ".local/share") / "PrismLauncher"
    flatpak = h / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"
    return flatpak if (not native.exists() and flatpak.exists()) else native


def prism_exe_default():
    """Where the GMod module looks for Prism unless GMODCRAFT_PRISM is set (module/source/client.cpp).
    Windows: the installer's per-user folder (unverified: %LOCALAPPDATA%\\Programs\\PrismLauncher)."""
    if SYSTEM == "linux":
        return home() / "Documents/Software/PrismLauncher-Linux-x86_64.AppImage"
    if SYSTEM == "windows":
        return _localappdata() / "Programs" / "PrismLauncher" / PRISM_EXE_WINDOWS
    return None


def prism_exe_candidates():
    out = []
    d = prism_exe_default()
    if d:
        out.append(d)
    if SYSTEM == "windows":
        for var in ("ProgramFiles", "ProgramFiles(x86)"):      # machine-wide installer; unverified
            if os.environ.get(var):
                out.append(Path(os.environ[var]) / "PrismLauncher" / PRISM_EXE_WINDOWS)
        w = shutil.which(PRISM_EXE_WINDOWS)
    else:
        w = shutil.which("prismlauncher")
    if w:
        out.append(Path(w))
    return [p for i, p in enumerate(out) if p not in out[:i]]


def prism_data_portable(exe):
    """Data folder of a portable Prism next to exe (Windows), or None when exe doesn't look portable.
    Unverified: portable mode is a marker file next to the exe (portable.txt) and/or an instances/
    folder beside it."""
    d = Path(exe).parent
    if (d / "portable.txt").is_file() or (d / "instances").is_dir():
        return d
    return None


FLATPAK_PRISM = "org.prismlauncher.PrismLauncher"


def flatpak_prism_installed():
    """Prism's flatpak (system or user installation), by its app folder; no subprocess."""
    if SYSTEM != "linux":
        return False
    for d in (Path("/var/lib/flatpak/app") / FLATPAK_PRISM, _xdg("XDG_DATA_HOME", ".local/share") / "flatpak/app" / FLATPAK_PRISM):
        if d.is_dir():
            return True
    return False


def prism_data_native():
    return _xdg("XDG_DATA_HOME", ".local/share") / "PrismLauncher"


def prism_data_flatpak():
    return home() / ".var/app" / FLATPAK_PRISM / "data/PrismLauncher"


def java_exe(java_home):
    return Path(java_home) / "bin" / ("java.exe" if SYSTEM == "windows" else "java")


def java_runtime_dir(prism_data):
    """The launcher-managed Java under Prism's java/ folder (same as tools/setup_prism.sh)."""
    return Path(prism_data) / "java" / "gmodcraft-temurin-25"


def temurin_pin():
    """The pinned Temurin 25 JRE for this OS/CPU from pins.json ("linux-x86_64", "windows-x86_64"),
    or None (then the user supplies Java 25 via GMODCRAFT_JAVA)."""
    import platform as _std  # the stdlib module, not this file
    from .pins import PINS
    m = _std.machine().lower()
    m = {"amd64": "x86_64", "x64": "x86_64"}.get(m, m)      # Windows reports AMD64
    return PINS.get("temurin", {}).get(f"{SYSTEM}-{m}")


# ---- processes ------------------------------------------------------------------------------

TERM = signal.SIGTERM
KILL = getattr(signal, "SIGKILL", 9)        # the signal module has no SIGKILL on Windows

_TH32CS_SNAPPROCESS = 0x2
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
_PROCESS_TERMINATE = 0x1
_CREATE_NO_WINDOW = 0x08000000
_DETACHED_PROCESS = 0x00000008
_CREATE_NEW_PROCESS_GROUP = 0x00000200


def no_window_kw():
    """subprocess kwargs that keep a console window from flashing up under a windowed launcher."""
    return {"creationflags": _CREATE_NO_WINDOW} if SYSTEM == "windows" else {}


# Everything below named win_* / _kernel32 runs only on Windows (ctypes.WinDLL); all unverified on a
# real machine, mocked in the tests.

def _kernel32():
    import ctypes
    from ctypes import wintypes
    k = ctypes.WinDLL("kernel32", use_last_error=True)
    k.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
    k.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    k.Process32FirstW.restype = wintypes.BOOL
    k.Process32NextW.restype = wintypes.BOOL
    k.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    k.OpenProcess.restype = wintypes.HANDLE
    k.CloseHandle.argtypes = [wintypes.HANDLE]
    k.CloseHandle.restype = wintypes.BOOL
    k.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR,
                                             ctypes.POINTER(wintypes.DWORD)]
    k.QueryFullProcessImageNameW.restype = wintypes.BOOL
    k.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    k.GetProcessTimes.restype = wintypes.BOOL
    k.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
    k.TerminateProcess.restype = wintypes.BOOL
    return k


def win_processes():
    """[(pid, exe file name)] of every process, from a Toolhelp32 snapshot (Windows only)."""
    import ctypes
    from ctypes import wintypes

    class PROCESSENTRY32W(ctypes.Structure):
        _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD), ("th32ProcessID", wintypes.DWORD),
                    ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wintypes.DWORD),
                    ("cntThreads", wintypes.DWORD), ("th32ParentProcessID", wintypes.DWORD),
                    ("pcPriClassBase", wintypes.LONG), ("dwFlags", wintypes.DWORD),
                    ("szExeFile", wintypes.WCHAR * 260)]

    k = _kernel32()
    k.Process32FirstW.argtypes = k.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    snap = k.CreateToolhelp32Snapshot(_TH32CS_SNAPPROCESS, 0)
    if not snap or snap == ctypes.c_void_p(-1).value:      # INVALID_HANDLE_VALUE
        return []
    out = []
    try:
        e = PROCESSENTRY32W()
        e.dwSize = ctypes.sizeof(e)
        ok = k.Process32FirstW(snap, ctypes.byref(e))
        while ok:
            out.append((int(e.th32ProcessID), e.szExeFile))
            ok = k.Process32NextW(snap, ctypes.byref(e))
    finally:
        k.CloseHandle(snap)
    return out


def win_image_path(pid):
    """Full path of a process's executable (QueryFullProcessImageNameW), or "" when it can't be opened."""
    import ctypes
    from ctypes import wintypes
    k = _kernel32()
    h = k.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(32768)
        n = wintypes.DWORD(len(buf))
        return buf.value if k.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)) else ""
    finally:
        k.CloseHandle(h)


def win_start_time(pid):
    """Creation time of a process (FILETIME as an integer; tells it apart from a reused pid), or None."""
    import ctypes
    from ctypes import wintypes
    k = _kernel32()
    h = k.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return None
    try:
        t = [wintypes.FILETIME() for _ in range(4)]
        if not k.GetProcessTimes(h, *[ctypes.byref(x) for x in t]):
            return None
        return (t[0].dwHighDateTime << 32) | t[0].dwLowDateTime
    finally:
        k.CloseHandle(h)


def win_terminate(pid):
    """TerminateProcess, the hard stop. ProcessLookupError when the process can't be opened."""
    k = _kernel32()
    h = k.OpenProcess(_PROCESS_TERMINATE, False, pid)
    if not h:
        raise ProcessLookupError(pid)
    try:
        if not k.TerminateProcess(h, 1):
            raise OSError(f"TerminateProcess({pid}) failed")
    finally:
        k.CloseHandle(h)


def kill_process(pid, sig):
    """Ask a process to end (TERM) or end it (KILL). POSIX: os.kill. Windows: TERM = `taskkill /PID`
    (a WM_CLOSE to its windows; unverified for GMod and Prism), KILL = TerminateProcess."""
    if SYSTEM != "windows":
        return os.kill(pid, sig)
    if sig == KILL:
        return win_terminate(pid)
    r = subprocess.run(["taskkill", "/PID", str(pid)], capture_output=True, timeout=20, **no_window_kw())
    if r.returncode != 0:
        raise ProcessLookupError(pid)


def _proc_entries():
    proc = Path("/proc")
    for p in proc.iterdir():
        if p.name.isdigit():
            yield p


def process_running(name):
    """True/False when a process with this exact name runs; None when this OS can't tell.
    Windows: the exe file name, case-insensitive."""
    if SYSTEM == "windows":
        try:
            want = name.lower()
            return any(n.lower() == want for _pid, n in win_processes())
        except (OSError, AttributeError, ImportError):
            return None
    if SYSTEM != "linux":
        return None
    for p in _proc_entries():
        try:
            if (p / "comm").read_text().strip() == name:
                return True
        except OSError:
            continue
    return False


def _norm_win(p):
    return str(p).replace("/", "\\").rstrip("\\").lower()


def win_game_java_pids():
    """pids of java.exe / javaw.exe running the launcher-managed Temurin. Windows can't read another
    process's command line without poking its PEB, so the executable's path stands in for the
    -Dgmodcraft.startHidden=true check. A Java the user supplies via GMODCRAFT_JAVA isn't found this way."""
    from . import config, detect
    jdir = _norm_win(java_runtime_dir(detect.prism_data_for(config.load()))) + "\\"
    return [pid for pid, name in win_processes()
            if name.lower() in ("java.exe", "javaw.exe") and _norm_win(win_image_path(pid)).startswith(jdir)]


def java_with_arg_running(arg):
    """A java process whose command line contains arg (our game: -Dgmodcraft.startHidden=true).
    Windows has no command line to read: the Minecraft lock file (prewarm.lock_holder) or the managed
    Java's path stands in."""
    if SYSTEM == "windows":
        try:
            from . import prewarm
            if prewarm.lock_holder():
                return True
            return bool(win_game_java_pids())
        except (OSError, AttributeError, ImportError):
            return None
    if SYSTEM != "linux":
        return None
    for p in _proc_entries():
        try:
            if (p / "comm").read_text().strip() != "java":
                continue
            if arg.encode() in (p / "cmdline").read_bytes().split(b"\0"):
                return True
        except OSError:
            continue
    return False


def gmod_running():
    return process_running({"linux": "gmod", "windows": "gmod.exe", "macos": "gmod"}.get(SYSTEM, "gmod"))


def prism_running():
    return process_running(PRISM_EXE_WINDOWS if SYSTEM == "windows" else "prismlauncher")


def spawn_detached(argv):
    """Start a program that outlives the launcher."""
    kw = {"stdin": subprocess.DEVNULL, "stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
    if SYSTEM == "windows":
        kw["creationflags"] = _DETACHED_PROCESS | _CREATE_NEW_PROCESS_GROUP
        kw["close_fds"] = True
    else:
        kw["start_new_session"] = True
    return subprocess.Popen(argv, **kw)


def open_path(path):
    """Open a file with the desktop's default program (the log file)."""
    if SYSTEM == "windows":
        os.startfile(str(path))      # noqa: S606  (Windows only; a path we picked, no shell)
    elif SYSTEM == "macos":
        spawn_detached(["open", str(path)])
    else:
        spawn_detached(["xdg-open", str(path)])


def set_dpi_aware():
    """Windows: tell the OS we handle DPI, so Tk isn't blurry on high-DPI screens. Never raises."""
    if SYSTEM != "windows":
        return False
    try:
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)       # per-monitor (Windows 8.1+)
        except (AttributeError, OSError):
            ctypes.windll.user32.SetProcessDPIAware()
        return True
    except Exception:  # noqa: BLE001
        return False


def is_executable(path):
    """A file we can run. Windows has no execute bit: a file is enough (os.access says so too)."""
    p = Path(path)
    return p.is_file() if SYSTEM == "windows" else os.access(p, os.X_OK)


def running_as_root():
    return hasattr(os, "geteuid") and os.geteuid() == 0


def current_uid():
    """The POSIX user id, or None on Windows."""
    return os.getuid() if hasattr(os, "getuid") else None
