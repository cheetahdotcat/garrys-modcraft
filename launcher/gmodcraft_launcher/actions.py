"""What the buttons and the CLI do. Every function takes a log(str) callback and may block, so the
GUI runs them on a worker thread."""
import os
import re
import shutil
from pathlib import Path

from . import bundle, detect, gmod_install, passwords, platform, prewarm, prism

DEFAULT_MAP = "gm_construct"
# GMod writes its console to garrysmod/console.log (the launcher's log viewer reads it), started
# afresh each launch (-conclearlog) so it doesn't grow forever.
LAUNCH_OPTIONS = ["-condebug", "-conclearlog"]
# host or IPv4 (first char alphanumeric: "-novid" must not pass as a launch option), optional :port
ADDR_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.-]{0,252}(:\d{1,5})?$")


class ActionError(Exception):
    pass


def _clean_errors(fn):
    """File-system errors become an ActionError with a readable message (no traceback in the log)."""
    import functools

    @functools.wraps(fn)
    def wrapper(*a, **kw):
        try:
            return fn(*a, **kw)
        except OSError as e:
            raise ActionError(f"{fn.__name__}: {e}") from e
    return wrapper


@_clean_errors
def open_source(cfg, log, extract_root=None):
    """The configured bundle or dev checkout, unpacked/scanned and verified. None when unset."""
    try:
        if cfg.get("source_kind") == "dev":
            if not cfg.get("dev_repo"):
                return None
            src = bundle.open_checkout(cfg["dev_repo"])
        else:
            if not cfg.get("bundle"):
                return None
            src = bundle.open_bundle(cfg["bundle"], extract_root or platform.data_dir() / "unpacked")
    except bundle.BundleError as e:
        raise ActionError(str(e))
    pins = src.manifest.get("pins") or {}
    bad = {k: v for k, v in pins.items() if v and prism.PINS.get(k) != v}
    if bad:
        raise ActionError(f"source pins {bad} differ from this launcher's {prism.PINS}: use the launcher from the same release")
    log(f"source: {src.describe()}")
    return src


def close_source(src):
    if src is not None and src.kind == "bundle":
        shutil.rmtree(src.root, ignore_errors=True)


def _gmod(log):
    g = detect.find_gmod()
    if g is None:
        raise ActionError("Garry's Mod not found (set GMOD_DIR)")
    if g.is64 is False:
        raise ActionError("Garry's Mod is on the 32-bit branch: switch to x86-64 in Steam first")
    return g


@_clean_errors
def install(cfg, log, dry_run=False, replace_foreign=False, with_prism=True, prism_data=None, src=None):
    own = src is None
    if own:
        src = open_source(cfg, log)
    if src is None:
        raise ActionError("choose a release bundle or a dev checkout first")
    try:
        g = _gmod(log)
        plan = gmod_install.plan_install(src, g.path, replace_foreign=replace_foreign)
        try:
            gmod_install.execute(plan, log, dry_run=dry_run)
        except gmod_install.InstallError as e:
            raise ActionError(str(e))
        if with_prism:
            pdata = Path(prism_data) if prism_data else detect.prism_data_for(cfg)
            jar = src.fabric_jar()
            if jar is None:
                raise ActionError("the source has no Fabric mod jar")
            try:
                prism.setup_instance(pdata, src.path(jar), log, gl=cfg.get("gl"), dry_run=dry_run)
            except prism.PrismError as e:
                raise ActionError(str(e))
    finally:
        if own:
            close_source(src)


@_clean_errors
def uninstall(cfg, log, dry_run=False, remove_instance=False, prism_data=None):
    """remove_instance: also delete the Prism instance (and its worlds). The GUI asks first."""
    g = _gmod(log)
    p = passwords.cfg_path(g.path)
    if os.path.lexists(p):
        if dry_run:
            log(f"dry run: would remove {p}")
        elif passwords.wipe_cfg(g.path):
            log(f"removed {p}")
    plan = gmod_install.plan_uninstall(g.path)
    if not plan.ops:
        log("nothing installed by the launcher in " + str(g.path))
    else:
        try:
            gmod_install.execute(plan, log, dry_run=dry_run)
        except gmod_install.InstallError as e:
            raise ActionError(str(e))
    pdata = Path(prism_data) if prism_data else detect.prism_data_for(cfg)
    if remove_instance:
        try:
            prism.remove_instance(pdata, log, dry_run=dry_run)
        except prism.PrismError as e:
            raise ActionError(str(e))
    else:
        log("the Prism instance GmodCraft is kept (it holds your worlds)")


@_clean_errors
def new_world(cfg, log, world_type, dry_run=False, prism_data=None):
    """Single player's world starts over (P8 WP3): the old one is kept as a .bak, the next start
    makes one of world_type (prism.WORLD_TYPES). The GUI asks first."""
    pdata = Path(prism_data) if prism_data else detect.prism_data_for(cfg)
    try:
        return prism.new_world(pdata, world_type, log, dry_run=dry_run)
    except prism.PrismError as e:
        raise ActionError(str(e))


def check_server(server):
    server = server.strip()
    if not ADDR_RE.match(server):
        raise ActionError(f"not a server address: {server!r} (host or ip, optional :port)")
    return server


def game_args(server=None):
    if server:
        return ["+connect", check_server(server)]
    return ["+map", DEFAULT_MAP]


def connect_args():
    """With a password: GMod runs the connect cfg (password + connect) instead of +connect."""
    return ["+exec", passwords.CONNECT_CFG]


def wipe_connect_cfg(log, gmod_dir=None):
    """Remove a connect cfg with a password left from an earlier launch. Not while GMod runs: it may
    not have read it yet (it's removed on the next start or launch instead)."""
    if gmod_dir is None:
        g = detect.find_gmod()
        if g is None:
            return False
        gmod_dir = g.path
    p = passwords.cfg_path(gmod_dir)
    if not os.path.lexists(p):
        return False
    if platform.gmod_running():
        log(f"{p} is still there (Garry's Mod is running); it's removed on the next start")
        return False
    try:
        removed = passwords.wipe_cfg(gmod_dir)
    except OSError as e:
        log(f"can't remove {p}: {e}")
        return False
    if removed:
        log(f"removed the old connect cfg {p}")
    return removed


@_clean_errors
def play(cfg, log, server=None, password=None, dry_run=False, result=None):
    """password: hand it to GMod through the connect cfg (never on the command line).
    Minecraft is pre-warmed alongside (prewarm.py, setting "prewarm"); result["prewarm"] says whether."""
    if password and not server:
        raise ActionError("a password needs a server to join")
    try:
        if password:
            passwords.check(password)
    except passwords.PasswordError as e:
        raise ActionError(str(e))
    args = game_args(server)
    if password:
        args = connect_args()
    g = detect.find_gmod()
    if password and g is None:
        raise ActionError("Garry's Mod not found (set GMOD_DIR): needed to hand it the password")
    root = g.library if g else None
    argv = platform.steam_launch_command(root, LAUNCH_OPTIONS + args)
    if argv is None:
        raise ActionError("can't find Steam to start Garry's Mod")
    log("starting: " + " ".join(argv))
    if dry_run:
        if password:
            log(f"dry run: would write {passwords.cfg_path(g.path)} (password + connect {server.strip()})")
        warm = prewarm.start(cfg, log, dry_run=True)
        if result is not None:
            result["prewarm"] = warm
        log("dry run: not started")
        return argv
    if platform.gmod_running():
        raise ActionError("Garry's Mod is already running")
    if g is not None:
        wipe_connect_cfg(log, g.path)
    if password:
        try:
            p = passwords.write_cfg(g.path, check_server(server), password)
        except passwords.PasswordError as e:
            raise ActionError(str(e))
        log(f"wrote {p} (password + connect, mode 0600; removed on the next launcher start)")
    # Minecraft first: it is the slower side. Never raises; if it doesn't start, GMod's launch.lua
    # starts Minecraft at InitPostEntity as before (nobody holds the lock).
    warm = prewarm.start(cfg, log)
    if result is not None:
        result["prewarm"] = warm
    try:
        platform.spawn_detached(argv)
    except BaseException:
        if password:
            try:
                passwords.wipe_cfg(g.path)
            except OSError as e:   # never hides the spawn error
                log(f"can't remove {passwords.cfg_path(g.path)}: {e}")
        raise
    return argv
