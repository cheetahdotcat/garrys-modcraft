"""Read-only server status queries (no UI): GMod A2S_INFO / A2S_RULES over UDP, Minecraft's
server-list ping over TCP.

Everything a server sends is untrusted: parsing is strict, every size is bounded, every query has a
deadline of at most 2 s, and the public functions return result objects; they never raise.

  a2s_info(host, port)   -> InfoResult   (name, map, players/max, bots, password flag, ...)
  a2s_rules(host, port)  -> RulesResult  (convars with FCVAR_NOTIFY, e.g. gmodcraft_version)
  mc_status(host, port)  -> McResult     (players online/max, version name/protocol)
  server_status(host, port, mc_port) -> ServerStatus (all three, in parallel)
"""
import json
import socket
import struct
import threading
import time
from dataclasses import dataclass, field

from . import protocol as _protocol

# The link protocol this launcher's release speaks, derived from protocol/gmodcraft_protocol.h
# kVersion (protocol.py; None when unknown). Servers announce theirs in the convar
# gmodcraft_version ("16/0.1.2"; "0/..." = the server's module isn't loaded).
PROTOCOL = _protocol.link_protocol()
VERSION_CONVAR = "gmodcraft_version"

TIMEOUT = 2.0                  # per query, upper bound
MAX_DATAGRAM = 65535
MAX_FRAGMENTS = 32
MAX_REASSEMBLED = 64 * 1024
MAX_RULES = 4096
MAX_TEXT = 128                 # strings shown in the UI are cut to this
MC_MAX_JSON = 64 * 1024

_SINGLE = b"\xff\xff\xff\xff"
_SPLIT = b"\xfe\xff\xff\xff"
A2S_INFO_REQ = _SINGLE + b"TSource Engine Query\x00"
A2S_RULES_REQ = _SINGLE + b"V"
S2C_CHALLENGE = 0x41
S2A_INFO = 0x49
S2A_RULES = 0x45


class QueryError(Exception):
    pass


# ---- results ------------------------------------------------------------------------------

@dataclass
class InfoResult:
    ok: bool = False
    error: str = ""
    name: str = ""
    map: str = ""
    folder: str = ""
    game: str = ""
    players: int = 0
    max_players: int = 0
    bots: int = 0
    password: bool = False
    vac: bool = False
    version: str = ""
    ping_ms: float = 0.0


@dataclass
class RulesResult:
    ok: bool = False
    error: str = ""
    rules: dict = field(default_factory=dict)


@dataclass
class McResult:
    ok: bool = False
    error: str = ""
    online: int = 0
    max_players: int = 0
    version: str = ""
    protocol: int = -1


@dataclass
class ServerStatus:
    info: InfoResult
    rules: RulesResult
    mc: McResult

    @property
    def online(self):
        return self.info.ok

    @property
    def gmodcraft_version(self):
        """(protocol, version) from the convar, or None when unknown (no rules, no convar, bad value)."""
        if not self.rules.ok:
            return None
        return parse_version(self.rules.rules.get(VERSION_CONVAR))

    @property
    def protocol_match(self):
        """True / False, or None when the server doesn't say, its module isn't loaded (protocol 0)
        or this launcher doesn't know its own: never blocks a join."""
        v = self.gmodcraft_version
        if v is None or v[0] == 0 or not PROTOCOL:
            return None
        return v[0] == PROTOCOL


def parse_version(value):
    """'16/0.1.2' -> (16, '0.1.2'); anything else -> None."""
    if not isinstance(value, str) or len(value) > 64:
        return None
    proto, sep, ver = value.partition("/")
    if not sep or not proto.isdigit() or len(proto) > 6:
        return None
    ver = clean_text(ver)
    return int(proto), ver


def clean_text(s, limit=MAX_TEXT):
    """Printable text only (no control characters, no bidi tricks beyond what Tk shows), cut short."""
    out = "".join(c for c in s if c.isprintable())
    return out[:limit]


# ---- byte parsing (pure, unit-tested on fixtures) ------------------------------------------

class Reader:
    def __init__(self, data, pos=0):
        self.data = bytes(data)
        self.pos = pos

    def need(self, n):
        if n < 0 or self.pos + n > len(self.data):
            raise QueryError("truncated reply")

    def byte(self):
        self.need(1)
        self.pos += 1
        return self.data[self.pos - 1]

    def short(self):
        self.need(2)
        v = struct.unpack_from("<h", self.data, self.pos)[0]
        self.pos += 2
        return v

    def long(self):
        self.need(4)
        v = struct.unpack_from("<l", self.data, self.pos)[0]
        self.pos += 4
        return v

    def cstring(self, limit=4096):
        end = self.data.find(b"\x00", self.pos, self.pos + limit + 1)
        if end < 0:
            raise QueryError("unterminated or oversize string")
        s = self.data[self.pos:end].decode("utf-8", "replace")
        self.pos = end + 1
        return s

    def left(self):
        return len(self.data) - self.pos


def parse_info(payload):
    """An S2A_INFO payload (after the 0xFFFFFFFF header) -> InfoResult. Raises QueryError."""
    r = Reader(payload)
    if r.byte() != S2A_INFO:
        raise QueryError("not an A2S_INFO reply")
    res = InfoResult(ok=True)
    r.byte()                                   # protocol
    res.name = clean_text(r.cstring())
    res.map = clean_text(r.cstring())
    res.folder = clean_text(r.cstring())
    res.game = clean_text(r.cstring())
    r.short()                                  # appid (truncated to 16 bits)
    res.players = r.byte()
    res.max_players = r.byte()
    res.bots = r.byte()
    r.byte()                                   # server type
    r.byte()                                   # environment
    vis = r.byte()
    vac = r.byte()
    if vis not in (0, 1) or vac not in (0, 1):
        raise QueryError("bad visibility / VAC flag")
    res.password, res.vac = bool(vis), bool(vac)
    if r.left():
        res.version = clean_text(r.cstring())  # the extra data flag after it is ignored
    if res.players > 255 or res.max_players > 255:
        raise QueryError("bad player counts")
    return res


def parse_rules(payload):
    """An S2A_RULES payload -> {name: value}. Raises QueryError."""
    r = Reader(payload)
    if r.byte() != S2A_RULES:
        raise QueryError("not an A2S_RULES reply")
    n = r.short()
    if n < 0 or n > MAX_RULES:
        raise QueryError(f"bad rule count {n}")
    rules = {}
    for _ in range(n):
        k = r.cstring(256)
        v = r.cstring(1024)
        rules[k] = v
    return rules


def parse_challenge(payload):
    r = Reader(payload)
    if r.byte() != S2C_CHALLENGE:
        return None
    r.need(4)
    return r.data[1:5]


class Reassembler:
    """Source-engine split replies (0xFFFFFFFE): header id(4) total(1) number(1) size(2), then the
    fragment. Bounded: at most MAX_FRAGMENTS fragments, MAX_REASSEMBLED bytes, one id; duplicate
    or out-of-range numbers and compressed (bz2) replies are refused."""

    def __init__(self):
        self.id = None
        self.total = None
        self.parts = {}
        self.size = 0

    def add(self, packet):
        """Returns the reassembled payload (after its 0xFFFFFFFF) once complete, else None."""
        r = Reader(packet)
        if r.data[:4] != _SPLIT:
            raise QueryError("not a split packet")
        r.pos = 4
        pid = r.long() & 0xFFFFFFFF
        total = r.byte()
        number = r.byte()
        r.short()                              # max packet size; not trusted for anything
        if pid & 0x80000000:
            raise QueryError("compressed split reply refused")
        if total < 1 or total > MAX_FRAGMENTS:
            raise QueryError(f"bad fragment total {total}")
        if number >= total:
            raise QueryError(f"fragment {number} out of range (total {total})")
        if self.id is None:
            self.id, self.total = pid, total
        elif pid != self.id or total != self.total:
            raise QueryError("fragment of another reply")
        if number in self.parts:
            raise QueryError(f"duplicate fragment {number}")
        part = r.data[r.pos:]
        self.size += len(part)
        if self.size > MAX_REASSEMBLED:
            raise QueryError("split reply too large")
        self.parts[number] = part
        if len(self.parts) < self.total:
            return None
        whole = b"".join(self.parts[i] for i in range(self.total))
        if whole[:4] != _SINGLE:
            raise QueryError("reassembled reply has no header")
        return whole[4:]


# ---- UDP transport -------------------------------------------------------------------------

def _resolve(host, port, kind):
    infos = socket.getaddrinfo(host, port, socket.AF_INET, kind)
    if not infos:
        raise QueryError("can't resolve host")
    return infos[0][4]


def _udp_exchange(host, port, request, deadline):
    """Send request, return the payload (after 0xFFFFFFFF) of the reply, reassembling splits."""
    addr = _resolve(host, port, socket.SOCK_DGRAM)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(addr)                        # only datagrams from that address are delivered
        s.send(request)
        ra = None
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                raise QueryError("timed out")
            s.settimeout(left)
            try:
                pkt = s.recv(MAX_DATAGRAM)
            except socket.timeout:
                raise QueryError("timed out")
            if pkt[:4] == _SINGLE:
                return pkt[4:]
            if pkt[:4] == _SPLIT:
                ra = ra or Reassembler()
                whole = ra.add(pkt)
                if whole is not None:
                    return whole
                continue
            raise QueryError("unknown reply header")


def _with_challenge(host, port, request, deadline, expect):
    """Request, answering at most one challenge (append it, ask again)."""
    payload = _udp_exchange(host, port, request, deadline)
    ch = parse_challenge(payload) if payload[:1] == bytes([S2C_CHALLENGE]) else None
    if ch is not None:
        payload = _udp_exchange(host, port, request + ch, deadline)
        if payload[:1] == bytes([S2C_CHALLENGE]):
            raise QueryError("server asked for a challenge twice")
    if payload[:1] != bytes([expect]):
        raise QueryError(f"unexpected reply type 0x{payload[:1].hex() or '--'}")
    return payload


def _deadline(timeout):
    return time.monotonic() + max(0.05, min(float(timeout), TIMEOUT))


def a2s_info(host, port, timeout=TIMEOUT):
    t0 = time.monotonic()
    try:
        payload = _with_challenge(host, int(port), A2S_INFO_REQ, _deadline(timeout), S2A_INFO)
        res = parse_info(payload)
        res.ping_ms = (time.monotonic() - t0) * 1000.0
        return res
    except (QueryError, OSError, ValueError, UnicodeError) as e:
        return InfoResult(error=_msg(e))
    except Exception as e:  # never raise into the caller
        return InfoResult(error=f"internal error: {type(e).__name__}")


def a2s_rules(host, port, timeout=TIMEOUT):
    try:
        payload = _rules_exchange(host, int(port), _deadline(timeout))
        return RulesResult(ok=True, rules=parse_rules(payload))
    except (QueryError, OSError, ValueError, UnicodeError) as e:
        return RulesResult(error=_msg(e))
    except Exception as e:
        return RulesResult(error=f"internal error: {type(e).__name__}")


def _rules_exchange(host, port, deadline):
    """The documented way: ask with challenge -1; the server answers with a real one (one retry)."""
    payload = _udp_exchange(host, port, A2S_RULES_REQ + b"\xff\xff\xff\xff", deadline)
    ch = parse_challenge(payload) if payload[:1] == bytes([S2C_CHALLENGE]) else None
    if ch is not None:
        payload = _udp_exchange(host, port, A2S_RULES_REQ + ch, deadline)
        if payload[:1] == bytes([S2C_CHALLENGE]):
            raise QueryError("server asked for a challenge twice")
    if payload[:1] != bytes([S2A_RULES]):
        raise QueryError(f"unexpected reply type 0x{payload[:1].hex() or '--'}")
    return payload


# ---- Minecraft server-list ping --------------------------------------------------------------

def varint(n):
    n &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def read_varint(read_byte):
    """read_byte() -> int; at most 5 bytes. Returns a signed 32-bit value."""
    v = 0
    for i in range(5):
        b = read_byte()
        v |= (b & 0x7F) << (7 * i)
        if not b & 0x80:
            return v - (1 << 32) if v & 0x80000000 else v
    raise QueryError("VarInt too long")


def mc_handshake(host, port):
    h = host.encode("utf-8")[:255]
    body = b"\x00" + varint(-1) + varint(len(h)) + h + struct.pack(">H", port & 0xFFFF) + varint(1)
    return varint(len(body)) + body + b"\x01\x00"          # handshake, then status request


def parse_mc_response(packet):
    """The status response packet body (after its length) -> McResult. Raises QueryError."""
    r = Reader(packet)
    if read_varint(r.byte) != 0:
        raise QueryError("not a status response")
    n = read_varint(r.byte)
    if n < 0 or n > MC_MAX_JSON:
        raise QueryError("status JSON too large")
    r.need(n)
    text = r.data[r.pos:r.pos + n].decode("utf-8")
    try:
        doc = json.loads(text)
    except ValueError:
        raise QueryError("status JSON doesn't parse")
    if not isinstance(doc, dict):
        raise QueryError("status JSON isn't an object")
    res = McResult(ok=True)
    players = doc.get("players")
    if isinstance(players, dict):
        on, mx = players.get("online"), players.get("max")
        if not (isinstance(on, int) and isinstance(mx, int) and not isinstance(on, bool) and not isinstance(mx, bool)
                and 0 <= on <= 1_000_000 and 0 <= mx <= 1_000_000):
            raise QueryError("bad player counts")
        res.online, res.max_players = on, mx
    version = doc.get("version")
    if isinstance(version, dict):
        if isinstance(version.get("name"), str):
            res.version = clean_text(version["name"])
        if isinstance(version.get("protocol"), int) and not isinstance(version.get("protocol"), bool):
            res.protocol = version["protocol"]
    return res


def mc_status(host, port=25565, timeout=TIMEOUT, server_name=None):
    """server_name: the name put into the handshake (host is then usually its resolved address)."""
    try:
        deadline = _deadline(timeout)
        addr = _resolve(host, int(port), socket.SOCK_STREAM)
        with socket.create_connection(addr, timeout=max(0.05, deadline - time.monotonic())) as s:
            s.sendall(mc_handshake(server_name or host, int(port)))

            def recv_exact(n):
                buf = bytearray()
                while len(buf) < n:
                    left = deadline - time.monotonic()
                    if left <= 0:
                        raise QueryError("timed out")
                    s.settimeout(left)
                    try:
                        chunk = s.recv(min(65536, n - len(buf)))
                    except socket.timeout:
                        raise QueryError("timed out")
                    if not chunk:
                        raise QueryError("connection closed mid-reply")
                    buf += chunk
                return bytes(buf)

            length = read_varint(lambda: recv_exact(1)[0])
            if length <= 0 or length > MC_MAX_JSON + 16:
                raise QueryError("status reply too large")
            return parse_mc_response(recv_exact(length))
    except (QueryError, OSError, ValueError, UnicodeError) as e:
        return McResult(error=_msg(e))
    except Exception as e:
        return McResult(error=f"internal error: {type(e).__name__}")


# ---- all of it ------------------------------------------------------------------------------

def resolve(host, timeout=TIMEOUT):
    """host -> IPv4 address string within timeout, else raises QueryError. getaddrinfo has no
    timeout of its own: it runs on one daemon thread, which a hung resolver leaves behind (one per
    call, never blocking the caller past the timeout)."""
    parts = host.split(".")
    if len(parts) == 4 and all(p.isdigit() and int(p) < 256 for p in parts):
        return host
    box = {}

    def work():
        try:
            box["ip"] = socket.getaddrinfo(host, None, socket.AF_INET, socket.SOCK_DGRAM)[0][4][0]
        except Exception as e:  # noqa: BLE001 (reported below)
            box["err"] = e
    t = threading.Thread(target=work, daemon=True, name="gmc-resolve")
    t.start()
    t.join(max(0.05, min(float(timeout), TIMEOUT)))
    if "ip" in box:
        return box["ip"]
    if "err" in box:
        raise QueryError(_msg(box["err"]))
    raise QueryError("name lookup timed out")


def server_status(host, port, mc_port=25565, timeout=TIMEOUT):
    """Resolve once (bounded), then A2S_INFO, A2S_RULES and the MC ping in parallel on the address;
    done within about 2 x timeout. Never raises."""
    try:
        ip = resolve(host, timeout)
    except QueryError as e:
        return ServerStatus(InfoResult(error=str(e)), RulesResult(error=str(e)), McResult(error=str(e)))
    except Exception as e:  # noqa: BLE001
        msg = f"internal error: {type(e).__name__}"
        return ServerStatus(InfoResult(error=msg), RulesResult(error=msg), McResult(error=msg))
    out = {}

    def run(key, fn, *a, **kw):
        out[key] = fn(*a, **kw)
    jobs = [threading.Thread(target=run, args=("info", a2s_info, ip, port, timeout), daemon=True),
            threading.Thread(target=run, args=("rules", a2s_rules, ip, port, timeout), daemon=True)]
    if mc_port:
        jobs.append(threading.Thread(target=run, args=("mc", mc_status, ip, mc_port, timeout),
                                     kwargs={"server_name": host}, daemon=True))
    for j in jobs:
        j.start()
    for j in jobs:
        j.join(TIMEOUT + 1.0)
    return ServerStatus(out.get("info") or InfoResult(error="timed out"),
                        out.get("rules") or RulesResult(error="timed out"),
                        out.get("mc") or McResult(error="no Minecraft port" if not mc_port else "timed out"))


def _msg(e):
    if isinstance(e, socket.timeout):
        return "timed out"
    if isinstance(e, ConnectionRefusedError):
        return "connection refused"
    if isinstance(e, socket.gaierror):
        return "can't resolve host"
    return clean_text(str(e) or type(e).__name__)
