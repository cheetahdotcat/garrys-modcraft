"""Update check against the public GitHub releases (L4).

check():   GET api.github.com/repos/<REPO>/releases (no token, no personal data), pick the newest release
           (pre-releases only with the "updateBetas" setting) and compare it with the installed bundle
           (installed.json) and this launcher's __version__. On start at most once per CHECK_INTERVAL_S
           (the time of the last attempt is in config "updateLastCheck"); the button forces it.
apply():   downloads the release's three assets (garrys-modcraft-<ver>.zip, gmodcraft-launcher.pyz,
           SHA256SUMS) into platform.data_dir()/releases, refuses unless both sha256 match SHA256SUMS,
           replaces a running .pyz atomically when the launcher is older (never a dev checkout), and
           returns the zip for the normal install path (actions.install). Nothing downloaded is executed.

Only https to the GitHub hosts in ALLOWED_HOSTS, also after redirects; downloads capped at MAX_DOWNLOAD.
"""
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from . import __version__, gmod_install, platform, safeio

REPO = "cheetahdotcat/garrys-modcraft"
API_URL = f"https://api.github.com/repos/{REPO}/releases"
ALLOWED_HOSTS = frozenset({"github.com", "api.github.com", "objects.githubusercontent.com",
                           "release-assets.githubusercontent.com"})
TIMEOUT_S = 10
CHECK_INTERVAL_S = 6 * 3600
MAX_DOWNLOAD = 200 * 1024 * 1024
MAX_API = 4 * 1024 * 1024
LAUNCHER_ASSET = "gmodcraft-launcher.pyz"
SUMS_ASSET = "SHA256SUMS"
VERSION_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$")


class UpdateError(Exception):
    pass


def bundle_asset(version):
    return f"garrys-modcraft-{version}.zip"


# ---- versions ------------------------------------------------------------------------------
def parse_version(s):
    """Sort key: 0.5.0-beta2 < 0.5.0-rc1 < 0.5.0 < 0.5.1. None if s isn't x.y.z[-pre]."""
    m = VERSION_RE.match((s or "").strip())
    if not m:
        return None
    pre = m.group(4)
    ids = ()
    if pre:
        for part in pre.split("."):
            for tok in re.findall(r"\d+|[^\d]+", part):
                ids += ((1, int(tok), "") if tok.isdigit() else (0, 0, tok),)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), 0 if pre else 1, ids)


def strip_v(tag):
    return tag[1:] if tag[:1] in "vV" else tag


def newer(a, b):
    """True if version a is newer than b (b None / unparsable: True)."""
    ka = parse_version(a)
    if ka is None:
        return False
    kb = parse_version(b) if b else None
    return kb is None or ka > kb


# ---- network -------------------------------------------------------------------------------
def check_url(url):
    p = urllib.parse.urlsplit(url)
    if p.scheme != "https" or (p.hostname or "").lower() not in ALLOWED_HOSTS or p.username or p.password:
        raise UpdateError(f"refusing URL outside the GitHub allowlist: {url}")
    if p.port not in (None, 443):
        raise UpdateError(f"refusing URL outside the GitHub allowlist: {url}")
    return url


class _AllowlistRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _open(url, accept):
    check_url(url)
    opener = urllib.request.build_opener(_AllowlistRedirect())
    req = urllib.request.Request(url, headers={"User-Agent": f"gmodcraft-launcher/{__version__}", "Accept": accept})
    return opener.open(req, timeout=TIMEOUT_S)


def _read_capped(r, out, limit):
    length = r.headers.get("Content-Length")
    if length and length.isdigit() and int(length) > limit:
        raise UpdateError(f"download too large ({int(length)} bytes, limit {limit})")
    n = 0
    while True:
        chunk = r.read(1 << 16)
        if not chunk:
            return n
        n += len(chunk)
        if n > limit:
            raise UpdateError(f"download larger than {limit} bytes: aborted")
        out.write(chunk)


def default_fetch_json(url):
    """The parsed JSON at url; None on HTTP 404 (no releases / no repo)."""
    import io
    try:
        with _open(url, "application/vnd.github+json") as r:
            buf = io.BytesIO()
            _read_capped(r, buf, MAX_API)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    return json.loads(buf.getvalue().decode("utf-8"))


def default_fetch_file(url, out, limit=MAX_DOWNLOAD):
    """Stream url into the binary file object out (at most limit bytes)."""
    with _open(url, "application/octet-stream") as r:
        _read_capped(r, out, limit)


# ---- check ---------------------------------------------------------------------------------
def installed_version():
    """The newest bundle version the launcher installed into any GMod folder (installed.json), or None."""
    best = None
    for st in gmod_install.load_state_all()["installs"].values():
        v = st.get("version") if isinstance(st, dict) else None
        if v and parse_version(v) and (best is None or parse_version(v) > parse_version(best)):
            best = v
    return best


def pick_release(releases, include_betas=False):
    """The newest usable release dict, or None. Drafts never; pre-releases only with include_betas."""
    best, best_key = None, None
    for rel in releases or ():
        if not isinstance(rel, dict) or rel.get("draft"):
            continue
        tag = str(rel.get("tag_name") or "")
        key = parse_version(tag)
        if key is None:
            continue
        if (rel.get("prerelease") or "-" in tag) and not include_betas:
            continue
        if best_key is None or key > best_key:
            best, best_key = rel, key
    return best


def release_assets(rel):
    """{name: browser_download_url} of the three assets; UpdateError if one is missing."""
    version = strip_v(rel["tag_name"])
    have = {a.get("name"): a.get("browser_download_url") for a in rel.get("assets") or () if isinstance(a, dict)}
    want = (bundle_asset(version), LAUNCHER_ASSET, SUMS_ASSET)
    missing = [n for n in want if not have.get(n)]
    if missing:
        raise UpdateError(f"release {rel['tag_name']} lacks the asset(s) {', '.join(missing)}")
    return {n: check_url(have[n]) for n in want}


def due(cfg, now=None):
    if not cfg.get("updateCheck", True):
        return False
    now = time.time() if now is None else now
    try:
        last = float(cfg.get("updateLastCheck") or 0)
    except (TypeError, ValueError):
        last = 0
    return not (0 <= now - last < CHECK_INTERVAL_S)


def check(cfg, log, force=False, fetch_json=None, now=None, installed=None):
    """An offer dict {"version", "tag", "assets", "bundle", "launcher"} (bundle / launcher: that part is
    older than the release) or None. Never raises: failures are logged. Sets cfg["updateLastCheck"] on
    every attempt (the caller saves the config). Not forced: only when due()."""
    now = time.time() if now is None else now
    if not force and not due(cfg, now):
        return None
    cfg["updateLastCheck"] = int(now)
    try:
        releases = (fetch_json or default_fetch_json)(API_URL)
        if releases is None or releases == []:
            log("update check: no releases published yet")
            return None
        if not isinstance(releases, list):
            raise UpdateError("unexpected answer from the releases API")
        rel = pick_release(releases, bool(cfg.get("updateBetas")))
        if rel is None:
            log("update check: no " + ("" if cfg.get("updateBetas") else "stable ") + "release yet")
            return None
        version = strip_v(rel["tag_name"])
        inst = installed_version() if installed is None else (installed or None)
        offer = {"version": version, "tag": rel["tag_name"],
                 "bundle": cfg.get("source_kind") != "dev" and newer(version, inst),
                 "launcher": newer(version, __version__)}
        if not (offer["bundle"] or offer["launcher"]):
            log(f"update check: up to date ({version} is the newest)")
            return None
        offer["assets"] = release_assets(rel)
        log(f"update check: {version} available (installed bundle {inst or 'none'}, launcher {__version__})")
        return offer
    except Exception as e:   # noqa: BLE001  never breaks the launcher; log only
        log(f"update check failed: {e}")
        return None


# ---- apply ---------------------------------------------------------------------------------
def parse_sums(text):
    """{file name: sha256 hex} from sha256sum output ("<hash>  <name>" or "<hash> *<name>")."""
    out = {}
    for line in text.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", parts[0]):
            continue
        out[parts[1].strip().lstrip("*")] = parts[0].lower()
    return out


def _sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _verifier(name, sums):
    want = sums.get(name)
    if not want:
        raise UpdateError(f"SHA256SUMS has no entry for {name}: refusing")

    def check(tmp):
        got = _sha256(tmp)
        if got != want:
            raise UpdateError(f"sha256 mismatch for {name} (got {got}, SHA256SUMS says {want}): refusing")
    return check


def _download(fetch_file, url, dst, check, log, mode=0o644):
    log(f"downloading {url}")
    safeio._write_via_tmp(dst, lambda out: fetch_file(url, out, MAX_DOWNLOAD), check, mode=mode)


def running_pyz():
    """The .pyz this launcher runs from, or None (a checkout: python3 -m gmodcraft_launcher)."""
    main = Path(sys.argv[0]).resolve() if sys.argv and sys.argv[0] else None
    if main and main.suffix == ".pyz" and main.is_file():
        return main
    return None


def releases_dir():
    return platform.data_dir() / "releases"


def apply(offer, log, fetch_file=None, pyz=None):
    """Download and verify the offer's assets; replace the running .pyz if offer["launcher"].
    Returns {"zip": Path to the verified bundle, "restart": True if the launcher file was replaced}.
    Raises UpdateError (nothing replaced) on any download / allowlist / hash problem."""
    fetch_file = fetch_file or default_fetch_file
    version, assets = offer["version"], offer["assets"]
    for url in assets.values():
        check_url(url)
    d = releases_dir()
    d.mkdir(parents=True, exist_ok=True)
    try:
        sums_path = d / f"{SUMS_ASSET}-{version}"
        safeio._write_via_tmp(sums_path, lambda out: fetch_file(assets[SUMS_ASSET], out, MAX_API))
        sums = parse_sums(sums_path.read_text(encoding="utf-8", errors="replace"))
        safeio.remove(sums_path)
        zip_name = bundle_asset(version)
        zcheck = _verifier(zip_name, sums)
        pcheck = _verifier(LAUNCHER_ASSET, sums)
        zip_path = d / zip_name
        if zip_path.is_file() and not zip_path.is_symlink() and _sha256(zip_path) == sums[zip_name]:
            log(f"{zip_path} already downloaded and verified")
        else:
            _download(fetch_file, assets[zip_name], zip_path, zcheck, log)
        new_pyz = d / f"gmodcraft-launcher-{version}.pyz"
        _download(fetch_file, assets[LAUNCHER_ASSET], new_pyz, pcheck, log)
    except OSError as e:
        raise UpdateError(f"download failed: {e}") from e
    except urllib.error.URLError as e:
        raise UpdateError(f"download failed: {e}") from e
    log(f"verified {zip_name} and {LAUNCHER_ASSET} against SHA256SUMS")
    restart = False
    try:
        if offer.get("launcher"):
            target = running_pyz() if pyz is None else Path(pyz)
            if target is None:
                log("running from a checkout: the launcher doesn't replace itself (update the checkout instead)")
            else:
                mode = os.stat(target).st_mode & 0o777
                safeio._write_via_tmp(target, lambda out: out.write(new_pyz.read_bytes()), pcheck, mode=mode)
                os.chmod(target, mode)    # the umask may have dropped bits at creation
                log(f"replaced {target} with launcher {version}: restart the launcher")
                restart = True
    except OSError as e:
        raise UpdateError(f"can't replace the launcher: {e}") from e
    finally:
        safeio.remove(new_pyz)
    for old in d.glob("garrys-modcraft-*.zip"):    # only this folder's older downloads
        if old.name != zip_name:
            try:
                safeio.remove(old)
            except OSError:
                pass
    return {"zip": zip_path, "restart": restart}
