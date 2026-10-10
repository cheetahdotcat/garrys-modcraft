"""Desktop entry (Linux, freedesktop): ~/.local/share/applications/garrys-modcraft.desktop and its
icon ~/.local/share/icons/hicolor/64x64/apps/garrys-modcraft.png, written through safeio and recorded
(path + sha256) under "desktop" in installed.json; uninstall removes only recorded, unchanged files.

The Exec line only ever holds the Python interpreter and the launcher's own path: no options, no
server, no password.
"""
import hashlib
import os
import sys
from pathlib import Path

from . import config, gmod_install, icon, platform, safeio

NAME = "garrys-modcraft"
TEMP_ROOTS = ("/tmp",)       # a menu entry must not run a launcher from here


class DesktopError(Exception):
    pass


def data_home():
    return platform._xdg("XDG_DATA_HOME", ".local/share")


def entry_path():
    return data_home() / "applications" / f"{NAME}.desktop"


def icon_path():
    return data_home() / "icons" / "hicolor" / "64x64" / "apps" / f"{NAME}.png"


def _quote(arg):
    """Desktop Entry Exec quoting: always quoted; \\, ", ` and $ backslash-escaped, then the string
    escaping (backslash doubled) on top, as the spec says: a literal backslash is four, a literal $
    is \\\\$. % doubled. Control characters refused."""
    if any(ord(c) < 0x20 or ord(c) == 0x7f for c in arg):
        raise DesktopError(f"can't put {arg!r} into a desktop entry")
    for ch in ("\\", '"', "`", "$"):
        arg = arg.replace(ch, "\\" + ch)
    arg = arg.replace("\\", "\\\\")
    return f'"{arg}"'.replace("%", "%%")


def checkout_problem(workdir):
    """Why a checkout is a bad home for a menu entry (None if fine): a temp dir, or a git worktree
    (its .git is a file), which goes away after a merge."""
    w = Path(workdir).resolve()
    for t in TEMP_ROOTS:
        if str(w) == t or str(w).startswith(t.rstrip("/") + "/"):
            return f"{w} is under {t}"
    for d in (w, *w.parents):
        g = d / ".git"
        if g.is_file():
            return f"{d} is a git worktree (it goes away after a merge)"
        if g.is_dir():
            return None
    return None


def launcher_command():
    """argv that starts this launcher: python3 <the .pyz>, else python3 -m gmodcraft_launcher (with
    the checkout's launcher/ as working directory)."""
    exe = sys.executable or "/usr/bin/python3"
    main = Path(sys.argv[0]).resolve() if sys.argv and sys.argv[0] else None
    if main and main.suffix == ".pyz" and main.is_file():
        return [exe, str(main)], None
    pkg_parent = Path(__file__).resolve().parent.parent
    return [exe, "-m", "gmodcraft_launcher"], pkg_parent


def entry_text(argv, workdir=None, icon_file=None):
    lines = [
        "[Desktop Entry]",
        "Type=Application",
        "Version=1.0",
        "Name=Garry's Modcraft",
        "Comment=Install, update and start Garry's Modcraft (Garry's Mod + Minecraft)",
        "Exec=" + " ".join(_quote(a) for a in argv),
        f"Icon={icon_file or NAME}",
        "Terminal=false",
        "Categories=Game;",
        "StartupNotify=true",
    ]
    if workdir:
        if any(ord(c) < 0x20 for c in str(workdir)):
            raise DesktopError("bad working directory")
        lines.append(f"Path={workdir}")
    return "\n".join(lines) + "\n"


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _state():
    data = gmod_install.load_state_all()
    d = data.get("desktop")
    return data, (d if isinstance(d, dict) else {})


def _save(data, files):
    if files:
        data["desktop"] = files
    else:
        data.pop("desktop", None)
    config.write_json_atomic(gmod_install.state_path(), data)


def install(log, dry_run=False, argv=None, workdir=None, force=False):
    if platform.SYSTEM != "linux":
        raise DesktopError("desktop entries are for Linux desktops")
    if argv is None:
        argv, workdir = launcher_command()
    if workdir:
        log(f"note: the menu entry runs the launcher from this checkout: {workdir}")
        why = checkout_problem(workdir)
        if why and not force:
            raise DesktopError(f"not adding a menu entry that runs from here: {why}. Use the release .pyz or the "
                               "main checkout (or --force)")
    icon_bytes = icon.png(64)
    ipath = icon_path()
    text = entry_text(argv, workdir, str(ipath)).encode("utf-8")
    epath = entry_path()
    if dry_run:
        log(f"dry run: would write {epath} and {ipath}")
        return epath
    data, files = _state()
    for p, blob in ((ipath, icon_bytes), (epath, text)):
        p.parent.mkdir(parents=True, exist_ok=True)
        if os.path.lexists(p) and str(p) not in files:
            raise DesktopError(f"{p} exists and wasn't written by the launcher; remove it first")
        files[str(p)] = _sha(blob)            # recorded first: an interrupted write is still ours
        _save(data, files)
        safeio.write_bytes(p, blob)
        log(f"wrote {p}")
    return epath


def uninstall(log, dry_run=False):
    data, files = _state()
    if not files:
        log("no desktop entry installed by the launcher")
        return
    for p, sha in sorted(files.items()):
        path = Path(p)
        if dry_run:
            log(f"dry run: would remove {path}")
            continue
        if not os.path.lexists(path):
            pass
        elif path.is_symlink() or not path.is_file():
            log(f"left {path} alone: not a file the launcher wrote")
        elif _sha(path.read_bytes()) != sha:
            log(f"left {path} alone: changed since the launcher wrote it")
        else:
            path.unlink()
            log(f"removed {path}")
        files.pop(p, None)
        _save(data, files)


def installed():
    return bool(_state()[1])
