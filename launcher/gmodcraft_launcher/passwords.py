"""Server passwords: remembered per server, and handed to GMod off the process command line.

Storage: secrets.json beside config.json (platform.config_dir()), mode 0600, written through safeio
(O_NOFOLLOW temp file + atomic replace). config.json stays free of secrets: the GMod module reads it.

Hand-over: the password never goes on GMod's command line (it would show in ps). The launcher writes
garrysmod/cfg/gmodcraft_connect.cfg (0600) with
    password "<pw>"
    connect host:port
and starts GMod with +exec gmodcraft_connect. The cfg is removed again on the next launcher start
(unless GMod is running: it may not have read it yet) and before every launch without a password.
GMod itself keeps the password convar (as if typed in its console).
"""
import json
import os
from pathlib import Path

from . import platform, safeio

CONNECT_CFG = "gmodcraft_connect"          # +exec name; the file is garrysmod/cfg/<name>.cfg
DEFAULT_PORT = 27015
SECRETS_MODE = 0o600


class PasswordError(Exception):
    pass


def check(pw):
    """Refuse what could break out of the quoted console argument: quotes, ';', newlines and other
    control characters would let a password run console commands."""
    if not pw:
        raise PasswordError("empty password")
    bad = [c for c in pw if c in '";' or ord(c) < 0x20 or ord(c) == 0x7f]
    if bad:
        raise PasswordError("the password contains a character GMod's console can't take safely "
                            "(a double quote, ';', a line break or another control character)")
    return pw


def server_key(server):
    """'Host:port' as the passwords are stored: host lower-cased, the GMod default port filled in."""
    server = server.strip()
    host, sep, port = server.rpartition(":")
    if not sep:
        host, port = server, str(DEFAULT_PORT)
    try:
        return f"{host.lower()}:{int(port)}"
    except ValueError:
        return server.lower()


# ---- storage --------------------------------------------------------------------------------

def secrets_path():
    return platform.config_dir() / "secrets.json"


def _load_all():
    p = secrets_path()
    try:
        fd = os.open(p, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0))
    except OSError:
        return {}
    try:
        with os.fdopen(fd, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    srv = data.get("servers") if isinstance(data, dict) else None
    return {k: v for k, v in srv.items() if isinstance(k, str) and isinstance(v, str)} if isinstance(srv, dict) else {}


def _save_all(servers):
    p = secrets_path()
    if not servers:
        safeio.remove(p)
        return
    p.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps({"format": 1, "servers": servers}, indent=2, sort_keys=True) + "\n"
    safeio.write_text(p, text, mode=SECRETS_MODE)


def stored(server):
    """The remembered password for server, or ''."""
    if not server or not server.strip():
        return ""
    return _load_all().get(server_key(server), "")


def remember(server, pw):
    servers = _load_all()
    servers[server_key(server)] = check(pw)
    _save_all(servers)


def forget(server):
    """Drop the stored password of server (Remember switched off). True if there was one."""
    servers = _load_all()
    if servers.pop(server_key(server), None) is None:
        return False
    _save_all(servers)
    return True


def read_password_file(path):
    """The first line of path, without its line break."""
    with open(path, encoding="utf-8") as f:
        line = f.readline()
    return check(line.rstrip("\r\n"))


# ---- the connect cfg ------------------------------------------------------------------------

def cfg_path(gmod_dir):
    return Path(gmod_dir) / "garrysmod" / "cfg" / f"{CONNECT_CFG}.cfg"


def cfg_text(server, pw):
    return f'password "{check(pw)}"\nconnect {server}\n'


def _cfg_dir(gmod_dir):
    d = Path(gmod_dir) / "garrysmod" / "cfg"
    for p in (Path(gmod_dir) / "garrysmod", d):
        if p.is_symlink():
            raise PasswordError(f"{p} is a symlink; not writing a password through it")
    return d


def write_cfg(gmod_dir, server, pw):
    d = _cfg_dir(gmod_dir)
    if not d.is_dir():
        raise PasswordError(f"no folder {d}")
    safeio.write_text(cfg_path(gmod_dir), cfg_text(server, pw), mode=SECRETS_MODE)
    return cfg_path(gmod_dir)


def wipe_cfg(gmod_dir):
    """Remove the connect cfg (and a leftover temp file of it). True if one was there."""
    try:
        _cfg_dir(gmod_dir)
    except PasswordError:
        return False   # never wrote through a link, nothing of ours to remove there
    p = cfg_path(gmod_dir)
    safeio.remove(safeio.tmp_path(p))
    return safeio.remove(p)
