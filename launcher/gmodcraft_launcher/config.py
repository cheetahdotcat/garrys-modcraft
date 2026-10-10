"""The launcher's own settings (config.json in platform.config_dir()). Only the launcher writes it."""
import json
import os
import tempfile
from pathlib import Path

from . import platform

DEFAULTS = {
    # Prism Launcher AppImage / executable chosen by the user. Key name is an interface: the GMod
    # client module will read it from this file to start Minecraft (planned in P6b).
    "prismAppImage": "",
    # R1: which Prism ("auto", "appimage", "package", "flatpak") and the argv the module starts it with
    # (["/path/Prism.AppImage"], ["/usr/bin/prismlauncher"] or ["flatpak", "run", "org.prismlauncher.PrismLauncher"];
    # derived on every save; module/source/prismcmd.hpp accepts only these shapes).
    "prismKind": "auto",
    "prismCommand": [],
    "source_kind": "bundle",  # "bundle" or "dev"
    "bundle": "",         # path to a release bundle zip
    "dev_repo": "",       # path to a repo checkout (dev mode)
    "server": "",         # the old single "Join server" address: migrated into "servers", then ""
    "servers": [],        # saved servers: [{"name", "address", "mc_port"}] (servers.py); no passwords
    "theme": "system",    # window look: "system" (follow the desktop), "light", "dark"
    "window": "",         # last window size "WxH"
    "tab": 0,             # last tab
    "maximized": False,
    "prewarm": True,      # start Minecraft (Prism) together with Garry's Mod on Play / Join (D-027)   # the window was maximised when closed
    "gl": None,           # instance env SDL_VIDEO_FORCE_EGL=1: True add (--gl), False remove (--no-gl), None leave alone
}


def path():
    return platform.config_dir() / "config.json"


def load():
    cfg = dict(DEFAULTS)
    cfg["servers"] = []   # never share the DEFAULTS lists
    cfg["prismCommand"] = []
    try:
        with open(path(), encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            cfg.update({k: v for k, v in data.items() if k in DEFAULTS})
    except (OSError, ValueError):
        pass
    return cfg


def write_json_atomic(p, data):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".tmp-", dir=p.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, sort_keys=True)
            f.write("\n")
        os.replace(tmp, p)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def save(cfg):
    from . import detect   # here: detect imports gmod_install, which imports this module
    try:
        cfg["prismCommand"] = detect.prism_command(cfg)
    except OSError:
        pass
    out = {k: cfg.get(k, v) for k, v in DEFAULTS.items()}
    if not out.get("prismCommand"):
        # No Prism found: write nothing, so the GMod module falls back to prismAppImage /
        # GMODCRAFT_PRISM / the default AppImage instead of refusing.
        out.pop("prismCommand", None)
    write_json_atomic(path(), out)
