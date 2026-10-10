"""Install sources: a release bundle (zip) or a repo checkout (dev mode).

Both have the same layout, so the rest of the launcher only sees a Source with a root folder and a
manifest:
  module/build/gm{cl,sv}_gmodcraft_<arch>.dll   native modules (one or more arches)
  addon/gmodcraft/...                            the Lua addon
  fabric/build/libs/gmodcraft-<version>.jar      the Fabric mod
  gmodcraft-bundle.json                          the manifest (bundles only; computed for checkouts)

Manifest (format 1):
  {"format": 1, "name": "garrys-modcraft", "version", "module_version", "fabric_version", "git",
   "built", "pins": {"minecraft", "fabric_loader", "fabric_api"},
   "files": [{"path", "sha256", "size", "role": module|addon|fabric_jar|launcher}]}
"""
import hashlib
import json
import re
import shutil
import tempfile
import time
import zipfile
from pathlib import Path, PurePosixPath

from . import safeio

MANIFEST = "gmodcraft-bundle.json"
FORMAT = 1
MODULE_RE = re.compile(r"^gm(cl|sv)_gmodcraft_([a-z0-9]+)\.dll$")
SKIP_NAMES = {"__pycache__", ".DS_Store", "Thumbs.db", ".gitkeep"}


class BundleError(Exception):
    pass


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_properties(p):
    out = {}
    try:
        for line in Path(p).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    except OSError:
        pass
    return out


def module_version_of(dll):
    """Best effort: the module's Version() string (e.g. 0.3.0-p3a) as stored in the binary."""
    try:
        data = Path(dll).read_bytes()
    except OSError:
        return None
    m = re.search(rb"\x00(\d+\.\d+\.\d+-[A-Za-z0-9.]+)\x00", data)
    return m.group(1).decode() if m else None


def scan_checkout(repo, git_sha=None):
    """Compute a manifest for a checkout. Raises BundleError when build outputs are missing."""
    repo = Path(repo)
    props = read_properties(repo / "fabric" / "gradle.properties")
    fver = props.get("version")
    if not fver:
        raise BundleError(f"{repo}: no fabric/gradle.properties version (not a gmod-craft checkout?)")
    files = []

    def add(rel, role):
        p = repo / rel
        files.append({"path": str(PurePosixPath(*Path(rel).parts)), "sha256": sha256_file(p),
                      "size": p.stat().st_size, "role": role})

    mod_dir = repo / "module" / "build"
    modules = sorted(p.name for p in mod_dir.glob("*.dll") if MODULE_RE.match(p.name)) if mod_dir.is_dir() else []
    if not modules:
        raise BundleError(f"no built modules in {mod_dir} (run module/build.sh)")
    for name in modules:
        add(Path("module/build") / name, "module")
    addon = repo / "addon" / "gmodcraft"
    if not (addon / "lua").is_dir():
        raise BundleError(f"{addon} has no lua/ folder")
    for p in sorted(addon.rglob("*")):
        rel = p.relative_to(repo)
        if any(part in SKIP_NAMES for part in rel.parts) or p.is_dir():
            continue
        if p.is_symlink():
            raise BundleError(f"symlink in the addon: {rel}")
        if DEV_LUA.fullmatch(rel.as_posix()):
            continue    # scripted tests: dev checkouts only (tools/release_public.sh leaves them out too)
        add(rel, "addon")
    jar = Path("fabric/build/libs") / f"gmodcraft-{fver}.jar"
    if not (repo / jar).is_file():
        raise BundleError(f"no {jar} (cd fabric && ./gradlew build)")
    add(jar, "fabric_jar")
    mver = next((v for v in (module_version_of(repo / "module/build" / n) for n in modules) if v), None)
    from . import protocol as _protocol
    try:
        kversion = _protocol.parse_kversion((repo / "protocol/gmodcraft_protocol.h").read_text(encoding="utf-8"))
    except OSError:
        kversion = None
    return {
        "format": FORMAT,
        "name": "garrys-modcraft",
        "version": fver,
        "module_version": mver,
        "fabric_version": fver,
        "protocol": kversion,     # link protocol (protocol/gmodcraft_protocol.h kVersion)
        "git": git_sha,
        "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "pins": {"minecraft": props.get("minecraft_version"), "fabric_loader": props.get("loader_version"),
                 "fabric_api": props.get("fabric_api_version")},
        "files": files,
    }


class Source:
    def __init__(self, root, manifest, kind, origin):
        self.root = Path(root)
        self.manifest = manifest
        self.kind = kind        # "bundle" or "dev"
        self.origin = str(origin)

    @property
    def version(self):
        return self.manifest.get("version")

    def files(self, role):
        return [f for f in self.manifest["files"] if f.get("role") == role]

    def path(self, entry):
        return self.root / entry["path"]

    def modules(self, arch):
        return [f for f in self.files("module") if PurePosixPath(f["path"]).name.endswith(f"_{arch}.dll")]

    def fabric_jar(self):
        jars = self.files("fabric_jar")
        return jars[0] if jars else None

    def describe(self):
        m = self.manifest
        return (f"{self.kind} {m.get('version')} (module {m.get('module_version') or '?'}, "
                f"git {m.get('git') or '?'}) from {self.origin}")


def open_checkout(repo):
    return Source(repo, scan_checkout(repo), "dev", repo)


def _safe_member(name):
    raw = name.split("/")
    p = PurePosixPath(name)
    if (not name or p.is_absolute() or "\\" in name or ":" in name
            or any(part in ("", ".", "..") for part in raw)):   # also "." and bare dirs ("a/")
        raise BundleError(f"unsafe path in bundle: {name!r}")
    return p


# What each role's path must look like: a manifest can't place a "module" in the addon or vice versa.
# The addon's scripted tests (loaded only with -gmodcraft_dev): never in a bundle.
DEV_LUA = re.compile(r"addon/gmodcraft/lua/gmodcraft/(client|server)/test[A-Za-z0-9_]*\.lua")

ROLE_PATHS = {
    "module": re.compile(r"module/build/gm(cl|sv)_gmodcraft_[a-z0-9]+\.dll"),
    "addon": re.compile(r"addon/gmodcraft/[^/]+(/[^/]+)*"),
    "fabric_jar": re.compile(r"fabric/build/libs/gmodcraft-[A-Za-z0-9.+_-]+\.jar"),
    "launcher": re.compile(r"launcher/[A-Za-z0-9._-]+\.pyz"),
    "meta": re.compile(r"protocol\.json"),        # R1: {"kVersion", "version"}
}


def validate_manifest(m):
    if not isinstance(m, dict) or m.get("format") != FORMAT or not isinstance(m.get("files"), list):
        raise BundleError("not a Garry's Modcraft bundle manifest (format 1)")
    seen = set()
    for f in m["files"]:
        if not isinstance(f, dict) or not isinstance(f.get("path"), str) or not re.fullmatch(r"[0-9a-f]{64}", str(f.get("sha256"))):
            raise BundleError(f"bad manifest entry: {f!r}")
        _safe_member(f["path"])
        shape = ROLE_PATHS.get(f.get("role"))
        if shape is None or not shape.fullmatch(f["path"]):
            raise BundleError(f"manifest entry {f['path']!r} doesn't fit its role {f.get('role')!r}")
        if f["path"] in seen:
            raise BundleError(f"{f['path']} is listed twice")
        seen.add(f["path"])
    if len([f for f in m["files"] if f["role"] == "fabric_jar"]) > 1:
        raise BundleError("more than one Fabric mod jar")


def open_bundle(zip_path, extract_root=None):
    """Unpack a bundle into a fresh folder and check every file against the manifest's sha256."""
    zip_path = Path(zip_path)
    try:
        zf = zipfile.ZipFile(zip_path)
    except (OSError, zipfile.BadZipFile) as e:
        raise BundleError(f"{zip_path}: {e}")
    with zf:
        try:
            m = json.loads(zf.read(MANIFEST))
        except KeyError:
            raise BundleError(f"{zip_path}: no {MANIFEST} inside")
        validate_manifest(m)
        names = set(zf.namelist())
        if extract_root:
            Path(extract_root).mkdir(parents=True, exist_ok=True)
        dest = Path(tempfile.mkdtemp(prefix="bundle-", dir=extract_root))
        try:
            for f in m["files"]:
                if f["path"] not in names:
                    raise BundleError(f"{f['path']} is in the manifest but not in the zip")
                info = zf.getinfo(f["path"])
                if (info.external_attr >> 16) & 0o170000 == 0o120000:
                    raise BundleError(f"symlink in bundle: {f['path']}")
                out = dest / _safe_member(f["path"])
                out.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as src, safeio.open_new(out) as dst:
                    shutil.copyfileobj(src, dst)
                if sha256_file(out) != f["sha256"]:
                    raise BundleError(f"{f['path']} doesn't match its sha256 in the manifest")
        except BaseException:
            shutil.rmtree(dest, ignore_errors=True)
            raise
    return Source(dest, m, "bundle", zip_path)


def write_bundle(repo, out_zip, git_sha=None, extra=(), version=None):
    """Zip a checkout's build outputs + manifest. extra: [(src_path, arcname, role)]."""
    repo = Path(repo)
    m = scan_checkout(repo, git_sha)
    if version:
        m["version"] = version
    for src, arc, role in extra:
        m["files"].append({"path": arc, "sha256": sha256_file(src), "size": Path(src).stat().st_size, "role": role})
    out_zip = Path(out_zip)
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_zip.with_name(out_zip.name + ".tmp")
    srcs = {arc: src for src, arc, _ in extra}
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(MANIFEST, json.dumps(m, indent=2, sort_keys=True) + "\n")
        for f in m["files"]:
            zf.write(srcs.get(f["path"], repo / f["path"]), f["path"])
    tmp.replace(out_zip)
    return m
