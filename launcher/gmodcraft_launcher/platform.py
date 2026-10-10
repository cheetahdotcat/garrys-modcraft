"""Everything that differs between operating systems lives here: folders, file names, process
checks and how Steam is started. Only Linux is implemented and tested; the Windows/macOS branches
are best-effort placeholders for the later ports (D-018) and return None where they don't know.

Environment overrides (tests, unusual installs):
  GMODCRAFT_CONFIG_DIR   launcher config + install state (default ~/.config/garrys-modcraft)
  GMODCRAFT_DATA_DIR     backups and unpacked bundles   (default ~/.local/share/garrys-modcraft)
  GMODCRAFT_STEAM_ROOT   one Steam root to use instead of the usual places
  GMOD_DIR               the GarrysMod folder itself (same name as module/install.sh uses)
  PRISM_DATA             Prism Launcher's data folder (same name as tools/setup_prism.sh uses)
"""
import os
import shutil
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


# ---- Steam / GMod ---------------------------------------------------------------------------

def steam_roots():
    """Candidate Steam installs, most likely first. Missing ones are filtered by the caller."""
    if os.environ.get("GMODCRAFT_STEAM_ROOT"):
        return [Path(os.environ["GMODCRAFT_STEAM_ROOT"])]
    h = home()
    if SYSTEM == "windows":
        pf = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
        return [Path(pf) / "Steam"]
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
        exe = Path(steam_root) / "steam.exe" if steam_root else None
        if exe and exe.exists():
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

def prism_data_default():
    if os.environ.get("PRISM_DATA"):
        return Path(os.environ["PRISM_DATA"])
    h = home()
    if SYSTEM == "windows":
        return Path(os.environ.get("APPDATA", h)) / "PrismLauncher"
    if SYSTEM == "macos":
        return h / "Library" / "Application Support" / "PrismLauncher"
    native = _xdg("XDG_DATA_HOME", ".local/share") / "PrismLauncher"
    flatpak = h / ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"
    return flatpak if (not native.exists() and flatpak.exists()) else native


def prism_exe_default():
    """Where the GMod module looks for Prism unless GMODCRAFT_PRISM is set (module/source/client.cpp)."""
    if SYSTEM == "linux":
        return home() / "Documents/Software/PrismLauncher-Linux-x86_64.AppImage"
    return None


def prism_exe_candidates():
    out = []
    d = prism_exe_default()
    if d:
        out.append(d)
    w = shutil.which("prismlauncher")
    if w:
        out.append(Path(w))
    return out


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
    """The pinned Temurin 25 JRE for this OS/CPU from pins.json ("linux-x86_64", ...), or None
    (then the user supplies Java 25 via GMODCRAFT_JAVA)."""
    import platform as _std  # the stdlib module, not this file
    from .pins import PINS
    return PINS.get("temurin", {}).get(f"{SYSTEM}-{_std.machine().lower()}")


# ---- processes ------------------------------------------------------------------------------

def _proc_entries():
    proc = Path("/proc")
    for p in proc.iterdir():
        if p.name.isdigit():
            yield p


def process_running(name):
    """True/False when a process with this exact name runs; None when this OS can't tell."""
    if SYSTEM != "linux":
        return None
    for p in _proc_entries():
        try:
            if (p / "comm").read_text().strip() == name:
                return True
        except OSError:
            continue
    return False


def java_with_arg_running(arg):
    """A java process whose command line contains arg (our game: -Dgmodcraft.startHidden=true)."""
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
    return process_running("prismlauncher")


def spawn_detached(argv):
    """Start a program that outlives the launcher."""
    kw = {"stdin": subprocess.DEVNULL, "stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
    if SYSTEM == "windows":
        kw["creationflags"] = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    else:
        kw["start_new_session"] = True
    return subprocess.Popen(argv, **kw)


def running_as_root():
    return hasattr(os, "geteuid") and os.geteuid() == 0
