"""Log files the launcher can show (read-only), password redaction, and plain-language hints.

  GMod:      garrysmod/console.log (the launcher starts GMod with -condebug, which writes it)
  Minecraft: <Prism instance>/.minecraft/logs/latest.log
"""
import os
import re
from dataclasses import dataclass
from pathlib import Path

from . import detect, passwords, platform, prism

MAX_TAIL_BYTES = 256 * 1024
MAX_LINE = 2000


def gmod_log_path(gmod_dir=None):
    if gmod_dir is None:
        g = detect.find_gmod()
        if g is None:
            return None
        gmod_dir = g.path
    return Path(gmod_dir) / "garrysmod" / "console.log"


def mc_log_path(prism_data=None, cfg=None):
    pdata = Path(prism_data) if prism_data else detect.prism_data_for(cfg)
    return prism.instance_game_dir(prism.paths(pdata)["final"]) / "logs" / "latest.log"


def tail(path, lines=300, max_bytes=MAX_TAIL_BYTES):
    """The last `lines` lines of a text file (reading at most max_bytes from its end), or None when
    it can't be read. Never follows a symlink at the file itself."""
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    except OSError:
        return None
    try:
        size = os.fstat(fd).st_size
        start = max(0, size - max_bytes)
        data = os.pread(fd, min(size, max_bytes), start)
    except OSError:
        return None
    finally:
        os.close(fd)
    text = data.decode("utf-8", "replace")
    out = text.splitlines()
    if start > 0 and out:
        out = out[1:]                      # a cut first line
    return [ln[:MAX_LINE] for ln in out[-lines:]]


# ---- redaction --------------------------------------------------------------------------------

MIN_SECRET = 4                     # shorter known secrets aren't scrubbed (they'd blank common text)
_CVAR_PRINT = re.compile(r'(?i)"((?:sv_|rcon_)?password)"(\s*=\s*)"[^"]*"')   # Source: "password" = "x"
_PW_PATTERNS = [
    re.compile(r'(?i)\b((?:sv_|rcon_)?password)(\s+)"[^"]*"'),          # password "x" (console / cfg)
    re.compile(r"(?i)\b((?:sv_|rcon_)?password)(\s+|=)(?!\")\S+"),     # password x / password=x / +password x
]


def redact(line, secrets=()):
    """Hide passwords: any `password <value>` form, and every known secret anywhere in the line."""
    line = _CVAR_PRINT.sub(lambda m: f'"{m.group(1)}"{m.group(2)}"***"', line)
    line = _PW_PATTERNS[0].sub(lambda m: f'{m.group(1)}{m.group(2)}"***"', line)
    line = _PW_PATTERNS[1].sub(lambda m: f"{m.group(1)}{m.group(2)}***", line)
    for s in secrets:
        if s and len(s) >= MIN_SECRET:
            line = line.replace(s, "***")
    return line


def known_secrets():
    """Every stored server password (to scrub them from what the log viewer shows)."""
    try:
        return sorted(set(passwords._load_all().values()), key=len, reverse=True)
    except Exception:  # noqa: BLE001  (never keep the viewer from showing a log)
        return []


# ---- hints --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Hint:
    id: str
    text: str


HINTS = [
    (re.compile(r"(?i)protocol mismatch|gmodcraft_version.*differ"),
     Hint("protocol", "Garry's Mod and Minecraft run different Garry's Modcraft versions: update both (Install / Update) "
                      "and, for a server, ask its owner which release it runs.")),
    (re.compile(r"(?i)bad password|server requires a password|invalid password"),
     Hint("password", "Wrong or missing server password: edit the server in the list and set its password.")),
    (re.compile(r"(?i)not white-?listed"),
     Hint("whitelist", "The Minecraft server's whitelist turned you away: its owner must add you or turn the whitelist off "
                       "(Garry's Modcraft servers use join tokens instead).")),
    (re.compile(r"(?i)prism (appimage )?not found|can't find prism|prism launcher not found"),
     Hint("prism", "Prism Launcher wasn't found: choose its AppImage in the Setup tab, then Install / Update.")),
    (re.compile(r"(?i)heartbeat stopped|no heartbeat for|link timed out"),
     Hint("link", "The link between Garry's Mod and Minecraft timed out: one side stalled or closed. Restart both; "
                  "if it keeps happening, send the two logs.")),
    (re.compile(r"(?i)binary module not loaded"),
     Hint("module", "Garry's Mod didn't load the gmodcraft module: run Install / Update and make sure GMod is on the "
                    "x86-64 branch.")),
    (re.compile(r"(?i)join token"),
     Hint("token", "The Minecraft server didn't accept the join token: join through Garry's Mod again (tokens are "
                   "single-use and expire).")),
]


def hints(lines):
    """The distinct hints the lines match, in HINTS order."""
    found = set()
    for ln in lines:
        for pat, h in HINTS:
            if h.id not in found and pat.search(ln):
                found.add(h.id)
    return [h for _, h in HINTS if h.id in found]
