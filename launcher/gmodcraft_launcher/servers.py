"""The saved server list (config.json key "servers"; passwords stay in secrets.json, see passwords.py).

An entry is {"name": str, "address": "host[:port]", "mc_port": int}. Entries are validated on load
and on edit; the list is keyed by passwords.server_key(address), so a password follows its server.
"""
from . import actions, passwords

MAX_SERVERS = 50
MAX_NAME = 64
DEFAULT_MC_PORT = 25565


class ServerError(Exception):
    pass


def make(name, address, mc_port=DEFAULT_MC_PORT):
    """A checked entry. Raises ServerError."""
    address = (address or "").strip()
    try:
        address = actions.check_server(address)
    except actions.ActionError as e:
        raise ServerError(str(e))
    name = "".join(c for c in (name or "").strip() if c.isprintable())[:MAX_NAME] or address
    if mc_port in (None, ""):
        mc_port = DEFAULT_MC_PORT
    try:
        mc_port = int(str(mc_port).strip())
    except ValueError:
        raise ServerError(f"not a Minecraft port: {mc_port!r}")
    if not 1 <= mc_port <= 65535:
        raise ServerError(f"not a Minecraft port: {mc_port}")
    return {"name": name, "address": address, "mc_port": mc_port}


def key(entry):
    return passwords.server_key(entry["address"])


def host_port(entry):
    """(host, GMod port) for the queries."""
    k = key(entry)
    host, _, port = k.rpartition(":")
    return host, int(port)


def load(cfg):
    """The valid entries of cfg["servers"], deduplicated, at most MAX_SERVERS."""
    out, seen = [], set()
    raw = cfg.get("servers")
    for e in raw if isinstance(raw, list) else []:
        if not isinstance(e, dict):
            continue
        try:
            entry = make(e.get("name"), e.get("address"), e.get("mc_port", DEFAULT_MC_PORT))
        except (ServerError, TypeError, AttributeError):
            continue
        if key(entry) in seen:
            continue
        seen.add(key(entry))
        out.append(entry)
        if len(out) >= MAX_SERVERS:
            break
    return out


def migrate(cfg):
    """The old single "server" becomes the first list entry (its stored password is keyed the same
    way, so it carries over). Returns True when cfg changed."""
    old = (cfg.get("server") or "").strip()
    if not old:
        return False
    lst = load(cfg)
    try:
        entry = make(old, old)
    except ServerError:
        cfg["server"] = ""
        try:
            passwords.forget(old)       # an orphan: no list entry can ever use it
        except OSError:
            pass
        return True
    if all(key(e) != key(entry) for e in lst):
        lst.insert(0, entry)
    cfg["servers"] = lst[:MAX_SERVERS]
    cfg["server"] = ""
    return True


def put(lst, entry, index=None):
    """Add entry, or replace the one at index (an edit). Refuses a second entry for one server."""
    for i, e in enumerate(lst):
        if key(e) == key(entry) and i != index:
            raise ServerError(f"{entry['address']} is in the list already ({e['name']})")
    if index is None:
        if len(lst) >= MAX_SERVERS:
            raise ServerError(f"at most {MAX_SERVERS} servers")
        lst.append(entry)
    else:
        lst[index] = entry
    return lst


def move(lst, index, delta):
    j = index + delta
    if 0 <= index < len(lst) and 0 <= j < len(lst):
        lst[index], lst[j] = lst[j], lst[index]
        return j
    return index
