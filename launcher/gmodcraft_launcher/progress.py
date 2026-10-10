"""Launch progress after Play / Join, from read-only signals (no UI here):

  1. gmod      Steam/GMod starting: a gmod process is seen
  2. loaded    GMod loaded the module: a fresh client.json in the run dir (platform.run_dir())
  3. mc        Minecraft starting: Prism or our java (JVM arg -Dgmodcraft.startHidden=true)
  4. linked    both heartbeats fresh in the client link's LinkHeader
  5. world     Minecraft in a world: McState flag kMcInWorld (only when the link's protocol is ours)

Shared memory is only ever READ: the discovery JSON is read with O_NOFOLLOW, the segment it names
is opened O_RDONLY|O_NOFOLLOW and read with pread (no mmap, no create, no unlink, no write). The
segment's magic, kind and size are validated before anything in it is believed.
"""
import json
import os
import re
import stat
import struct
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import platform, prewarm, prism, protocol

SHM_DIR = platform.shm_dir()
DISCOVERY = platform.run_dir() / "client.json"

# protocol/gmodcraft_protocol.h: LinkHeader is FROZEN across versions (offsets 0x00..0x3F).
MAGIC = 0x52434D47
LINK_CLIENT = 1
HEADER = struct.Struct("<IIIIQQQQQQ")          # magic version kind res0 bytes nonce hostHb mcHb mcNonce res1
HEADER_BYTES = 0x40
HEARTBEAT_TIMEOUT_NS = 8000 * 1_000_000        # kHeartbeatTimeoutMs
MC_QUIT_AFTER_GMOD_S = 15                      # fabric QuitPolicy.GONE_CLEAN_MS (counted from the link going down)
MC_WATCH_AFTER_GONE_S = 60
# Not frozen: read only when the segment's version equals ours.
OFF_MC_STATE_FLAGS = 0x200 + 4                 # kClOffMcState + McState.flags
MC_IN_WORLD = 0x1
MAX_DISCOVERY_BYTES = 4096
SHM_NAME = re.compile(r"^gmodcraft-[A-Za-z0-9._-]{1,180}$")

STEPS = (
    ("gmod", "Steam starts Garry's Mod", 90,
     "Garry's Mod hasn't started yet: Steam may be updating it or showing a dialog. Look at the Steam window."),
    ("loaded", "Garry's Mod loads Garry's Modcraft", 180,
     "GMod runs but the gmodcraft module hasn't opened its link: open the GMod log (Logs tab) and look for "
     "'binary module not loaded' (then reinstall with Install / Update). Joining a server: the module only "
     "starts once GMod is on the map, so a refused connection (password, version) also stops here: see the hints "
     "in the Logs tab."),
    ("mc", "Minecraft starts (Prism)", 120,
     "Minecraft hasn't started: check that Prism is set up (Setup tab) and look at Prism's window or the Minecraft log."),
    ("linked", "Minecraft links to Garry's Mod", 240,
     "Minecraft runs but hasn't linked: the first start can take a few minutes; otherwise look at the Minecraft log "
     "for 'protocol mismatch' (update both sides)."),
    ("world", "Minecraft enters the world", 120,
     "Linked, but Minecraft isn't in a world yet: a join may be waiting on the server (password, version, whitelist): "
     "see the hints in the Logs tab."),
)
STEP_IDS = [s[0] for s in STEPS]


@dataclass
class LinkInfo:
    ok: bool = False
    error: str = ""
    version: int = 0
    nonce: int = 0
    host_age_ms: float = None
    mc_age_ms: float = None
    in_world: bool = None        # None: not readable (other protocol version)


def read_discovery(path=DISCOVERY):
    """The client discovery JSON as a dict (bounded, O_NOFOLLOW), or None."""
    try:
        fd = os.open(path, platform.read_open_flags())
    except OSError:
        return None
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_size > MAX_DISCOVERY_BYTES:
            return None
        data = os.read(fd, MAX_DISCOVERY_BYTES)
    except OSError:
        return None
    finally:
        os.close(fd)
    try:
        doc = json.loads(data.decode("utf-8"))
    except (ValueError, UnicodeError):
        return None
    if not isinstance(doc, dict) or not isinstance(doc.get("shm"), str) or not SHM_NAME.match(doc["shm"]):
        return None
    doc["_mtime"] = st.st_mtime
    return doc


def read_link(shm_path, now_ns=None, expect_version=None):
    """Read the LinkHeader (and, for our own protocol version, McState.flags) of a client link
    segment, read-only. Never raises."""
    now_ns = time.monotonic_ns() if now_ns is None else now_ns
    try:
        fd = os.open(shm_path, platform.read_open_flags())
    except OSError as e:
        return LinkInfo(error=f"can't open the link: {e.strerror}")
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            return LinkInfo(error="the link isn't a regular file")
        if st.st_size < HEADER_BYTES:
            return LinkInfo(error="the link is too small")
        raw = platform.pread(fd, HEADER_BYTES, 0)
        if len(raw) < HEADER_BYTES:
            return LinkInfo(error="short read")
        magic, version, kind, _r0, size, nonce, host_hb, mc_hb, _mn, _r1 = HEADER.unpack(raw)
        if magic != MAGIC:
            return LinkInfo(error="bad magic (not a Garry's Modcraft link, or still being set up)")
        if kind != LINK_CLIENT:
            return LinkInfo(error=f"not a client link (kind {kind})")
        if size != st.st_size:
            return LinkInfo(error=f"size mismatch ({size} in the header, {st.st_size} on disk)")
        info = LinkInfo(ok=True, version=version, nonce=nonce)
        info.host_age_ms = (now_ns - host_hb) / 1e6 if host_hb else None
        info.mc_age_ms = (now_ns - mc_hb) / 1e6 if mc_hb else None
        if expect_version and version == expect_version and st.st_size >= OFF_MC_STATE_FLAGS + 4:
            b = platform.pread(fd, 4, OFF_MC_STATE_FLAGS)
            if len(b) == 4:
                info.in_world = bool(struct.unpack("<I", b)[0] & MC_IN_WORLD)
        return info
    except OSError as e:
        return LinkInfo(error=f"can't read the link: {e.strerror}")
    finally:
        os.close(fd)


def fresh(age_ms):
    return age_ms is not None and 0 <= age_ms < HEARTBEAT_TIMEOUT_NS / 1e6


@dataclass
class Step:
    id: str
    label: str
    timeout_s: int
    hint: str
    state: str = "waiting"       # waiting | active | done | slow | skipped
    done_at: float = None


@dataclass
class Snapshot:
    started: float
    now: float
    steps: list = field(default_factory=list)
    finished: bool = False
    gone: bool = False           # GMod went away after it had started
    mc_closing: bool = False     # ... and Minecraft is still closing by itself (fabric QuitPolicy)
    note: str = ""


class Tracker:
    """Call poll() about once a second; it returns a Snapshot. Probes are injectable for tests."""

    def __init__(self, started=None, probes=None, want_world=True, prewarmed=False):
        self.started = time.monotonic() if started is None else started
        self.wall_started = time.time()
        self.steps = [Step(*s) for s in STEPS]
        if not want_world:
            self.steps[-1].state = "skipped"
        if prewarmed:
            self.step("mc").label = "Minecraft starts (pre-warmed by the launcher)"
        p = probes or {}
        self.gmod_running = p.get("gmod_running", platform.gmod_running)
        self.mc_running = p.get("mc_running", lambda: bool(prewarm.lock_holder() or platform.java_with_arg_running(prism.JVM_ARG)
                                                           or platform.prism_running()))
        self.discovery = p.get("discovery", lambda: read_discovery(DISCOVERY))   # module globals: tests redirect them
        self.link = p.get("link", read_link)
        self.shm_dir = Path(p.get("shm_dir", SHM_DIR))
        self.protocol = p.get("protocol", protocol.link_protocol())
        self.seen_gmod = False
        self.gone_at = None
        self.last_linked = None      # last poll with both heartbeats fresh

    def step(self, sid):
        return self.steps[STEP_IDS.index(sid)]

    def _done(self, sid, now):
        s = self.step(sid)
        if s.state not in ("done", "skipped"):
            s.state, s.done_at = "done", now - self.started

    def poll(self, now=None):
        now = time.monotonic() if now is None else now
        snap = Snapshot(self.started, now)
        running = self.gmod_running()
        mc_up = bool(self.mc_running())
        if running:
            self.seen_gmod = True
            self.gone_at = None
            self._done("gmod", now)
        elif self.seen_gmod:
            snap.gone = True
            if self.gone_at is None:
                self.gone_at = now
            if mc_up and now - self.gone_at < MC_WATCH_AFTER_GONE_S:
                # Minecraft counts from its link going down (heartbeat timeout after the last fresh beat),
                # and only quits once GMod's process is gone (checked every second).
                link_down = (self.last_linked + HEARTBEAT_TIMEOUT_NS / 1e9) if self.last_linked is not None else self.gone_at
                left = max(0, max(link_down + MC_QUIT_AFTER_GMOD_S, self.gone_at + 1) - now)
                snap.note = (f"Garry's Mod has closed; Minecraft saves and closes in about {left:.0f} s" if left > 0
                             else "Garry's Mod has closed; Minecraft is saving and closing")
                snap.mc_closing = True
            elif mc_up:
                snap.note = "Garry's Mod has closed, but Minecraft is still running: use Quit game"
            else:
                snap.note = "Garry's Mod has closed; Minecraft has closed too."
        # Minecraft on its own: with pre-warm it can be up before GMod is.
        if mc_up:
            self._done("mc", now)
        doc = self.discovery()
        # A discovery file left by an earlier session (crash) doesn't count: written after we started.
        if doc is not None and running and doc.get("_mtime", 0) >= self.wall_started - 2:
            self._done("loaded", now)
            info = self.link(self.shm_dir / doc["shm"], expect_version=self.protocol)
            if info.ok and fresh(info.host_age_ms) and fresh(info.mc_age_ms):
                self.last_linked = now
                self._done("mc", now)
                self._done("linked", now)
                w = self.step("world")
                if info.in_world:
                    self._done("world", now)
                elif info.in_world is None and w.state != "done":
                    w.state = "skipped"                 # another protocol version: can't tell
                    snap.note = (f"link protocol {info.version} (this launcher's: {self.protocol}): world step skipped"
                                 if self.protocol else
                                 f"link protocol {info.version}; this launcher doesn't know its own: world step skipped")
        # the first step not done is active (or slow once past its timeout)
        for s in self.steps:
            if s.state in ("done", "skipped"):
                continue
            s.state = "slow" if now - self.started > s.timeout_s else "active"
            break
        snap.steps = [Step(s.id, s.label, s.timeout_s, s.hint, s.state, s.done_at) for s in self.steps]
        snap.finished = (all(s.state in ("done", "skipped") for s in self.steps) and not snap.gone) or \
            (snap.gone and not snap.mc_closing)
        return snap
