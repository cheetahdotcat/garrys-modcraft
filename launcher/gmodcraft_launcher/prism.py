"""The GmodCraft Prism instance: a Python port of tools/setup_prism.sh (same pins, same steps), so a
release bundle works without the repo. Keep the two in sync: change a pin in both.

Writes only $PRISM_DATA/instances/GmodCraft (via $PRISM_DATA/.gmodcraft-staging for a new one,
and $PRISM_DATA/.gmodcraft-reload for a few seconds when a running Prism must reload it) and the
Java runtime under $PRISM_DATA/java/gmodcraft-temurin-25. Never accounts.json or prismlauncher.cfg.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path

from . import platform, safeio

from .pins import PINS as _P

# All pins come from launcher/pins.json (tools/setup_prism.sh still has its own copy: backlog).
MC_VERSION = _P["minecraft"]
LOADER_VERSION = _P["fabric_loader"]
FABRIC_API_VERSION = _P["fabric_api"]["version"]
FABRIC_API_URL = _P["fabric_api"]["url"]
FABRIC_API_SHA512 = _P["fabric_api"]["sha512"]
# (prefix, url, sha512): prefix removes older versions of the same mod from mods/.
EXTRA_MODS = [(m["prefix"], m["url"], m["sha512"]) for m in _P["mods"]]
INSTANCE = "GmodCraft"
JVM_ARG = "-Dgmodcraft.startHidden=true"
PINS = {"minecraft": MC_VERSION, "fabric_loader": LOADER_VERSION, "fabric_api": FABRIC_API_VERSION}


class PrismError(Exception):
    pass


def _hash(p, algo):
    h = hashlib.new(algo)
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def default_fetch(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "garrys-modcraft-launcher"})
    with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def java_major(java):
    """Major version of a java binary (25), or None."""
    try:
        r = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        return None
    m = re.search(r'version "(\d+)', r.stderr + r.stdout)
    return int(m.group(1)) if m else None


def paths(prism_data):
    p = Path(prism_data)
    return {"final": p / "instances" / INSTANCE, "staging": p / ".gmodcraft-staging",
            "reload": p / ".gmodcraft-reload"}


def instance_game_dir(inst):
    inst = Path(inst)
    return inst / ".minecraft" if (inst / ".minecraft").is_dir() else inst / "minecraft"


def mmc_pack():
    return {
        "components": [
            {"cachedName": "Minecraft", "important": True, "uid": "net.minecraft", "version": MC_VERSION},
            {"cachedName": "Intermediary Mappings", "dependencyOnly": True, "uid": "net.fabricmc.intermediary",
             "version": MC_VERSION},
            {"cachedName": "Fabric Loader", "uid": "net.fabricmc.fabric-loader", "version": LOADER_VERSION},
        ],
        "formatVersion": 1,
    }


def _qs_unquote(v):
    """A QSettings ini string value: "..." with \\" and \\\\ escapes, or bare."""
    if len(v) >= 2 and v[0] == v[-1] == '"':
        return re.sub(r'\\(.)', r'\1', v[1:-1])
    return v


def _qs_quote(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def update_instance_cfg(path, name, java, envs=(), unset=()):
    """Set our keys inside [General] and keep everything else Prism or the user stored there.
    Unlike setup_prism.sh, an existing instance keeps its own settings:
      * Env / OverrideEnv stay as they are unless envs (KEY=VALUE, e.g. from the launcher's GL
        option) or unset (names, --no-gl) is given; then only those variables change in Env.
      * JvmArgs are kept; -Dgmodcraft.startHidden=true is added if missing.
    """
    path = Path(path)
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else ["[General]"]
    if "[General]" not in lines:
        lines.insert(0, "[General]")
    start = lines.index("[General]") + 1
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("[")), len(lines))
    current = {}
    for l in lines[start:end]:
        if "=" in l:
            k, v = l.split("=", 1)
            current[k] = v

    jvm = _qs_unquote(current.get("JvmArgs", "")).strip()
    if JVM_ARG not in jvm.split():
        jvm = (jvm + " " + JVM_ARG).strip()
    keys = {
        "ConfigVersion": "1.3", "InstanceType": "OneSix", "name": name,
        "OverrideJavaLocation": "true", "JavaPath": str(java), "AutomaticJava": "false",
        "OverrideJavaArgs": "true", "JvmArgs": _qs_quote(jvm),
        "OverrideMemory": "true", "MinMemAlloc": "1024", "MaxMemAlloc": "4096",
    }
    drop = set()
    if envs or unset:
        try:
            pairs = json.loads(_qs_unquote(current.get("Env", ""))) if current.get("Env") else {}
            if not isinstance(pairs, dict):
                pairs = {}
        except ValueError:
            pairs = {}
        before = dict(pairs)
        for k in unset:
            pairs.pop(k, None)
        pairs.update(dict(kv.split("=", 1) for kv in envs))
        if pairs != before:
            if pairs:
                keys["Env"] = _qs_quote(json.dumps(pairs, separators=(",", ":")))
                keys["OverrideEnv"] = "true"
            else:   # the last variable went: no environment override left
                drop.add("Env")
                keys["OverrideEnv"] = "false"
    if "OverrideEnv" not in keys and "OverrideEnv" not in current:
        keys["OverrideEnv"] = "false"
    section = [l for l in lines[start:end] if l.split("=", 1)[0] not in (set(keys) | drop) and l.strip()]
    section += [f"{k}={v}" for k, v in keys.items()]
    # Env / OverrideEnv only belong in [General] (an older setup_prism.sh could append them elsewhere).
    rest = [l for l in lines[end:] if l.split("=", 1)[0] not in ("Env", "OverrideEnv")]
    out = lines[:start] + section + ([""] if rest else []) + rest
    safeio.write_text(path, "\n".join(out) + "\n")


def read_instance_cfg(path):
    out = {}
    try:
        section = None
        for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("["):
                section = line.strip()
            elif section == "[General]" and "=" in line:
                k, v = line.split("=", 1)
                out[k] = _qs_unquote(v)
    except OSError:
        pass
    return out


GL_ENV = "SDL_VIDEO_FORCE_EGL"


def setup_instance(*args, **kw):
    """Create or update the instance. Returns the instance folder. File errors become PrismError."""
    try:
        return _setup_instance(*args, **kw)
    except OSError as e:
        raise PrismError(f"setting up the Prism instance: {e}") from e


def _setup_instance(prism_data, jar, log, java=None, gl=None, envs=(), dry_run=False,
                    fetch=default_fetch, cache_dir=None, gradle_cache=None, sleep=time.sleep):
    """gl: True adds SDL_VIDEO_FORCE_EGL=1 to the instance Env, False removes it, None leaves Env alone."""
    unset = [GL_ENV] if gl is False else []
    gl = gl is True
    prism_data = Path(prism_data)
    P = paths(prism_data)
    final, staging, reload_dir = P["final"], P["staging"], P["reload"]
    envs = list(envs) + ([GL_ENV + "=1"] if gl else [])
    if not prism_data.is_dir():
        raise PrismError(f"no Prism Launcher data at {prism_data} (start Prism once first)")
    jar = Path(jar)
    if not jar.is_file():
        raise PrismError(f"mod jar {jar} not found")
    cache = Path(cache_dir) if cache_dir else platform.mod_cache_dir()
    jdir = platform.java_runtime_dir(prism_data)
    java = Path(java) if java else (Path(os.environ["GMODCRAFT_JAVA"]) if os.environ.get("GMODCRAFT_JAVA") else None)

    if dry_run:
        log(f"dry run: Prism instance {INSTANCE} in {prism_data}")
        if reload_dir.exists():
            log(f"  would recover {reload_dir} -> {final}" if not final.exists() else f"  STOP: both {final} and {reload_dir} exist")
        log(f"  {'update' if final.is_dir() else 'create (via ' + str(staging) + ')'} {final}")
        log(f"  write mmc-pack.json (Minecraft {MC_VERSION}, Fabric Loader {LOADER_VERSION}), instance.cfg [General] keys")
        log(f"  mods/: {jar.name}, fabric-api-{FABRIC_API_VERSION}.jar, " + ", ".join(Path(u).name for _, u, _ in EXTRA_MODS))
        if java is None and not platform.java_exe(jdir).exists():
            pin = platform.temurin_pin()
            log(f"  download Temurin {pin['version']} JRE into {jdir}" if pin else "  no pinned Java for this OS: set GMODCRAFT_JAVA")
        if final.is_dir() and platform.prism_running():
            log("  Prism is running: move the instance out and back so it reloads it")
        return final

    # ---- a reload an earlier run didn't finish ----
    if reload_dir.exists():
        if not final.exists():
            log(f"recovering the {INSTANCE} instance from {reload_dir} (an earlier run was interrupted)")
            reload_dir.rename(final)
        else:
            raise PrismError(f"both {final} and {reload_dir} exist: keep the one you want, delete the other, then try again")

    tmpd = Path(tempfile.mkdtemp(prefix="gmodcraft-"))
    try:
        # ---- Fabric API: the Gradle cache if it's there, else Fabric's maven; sha512-checked ----
        fapi_name = f"fabric-api-{FABRIC_API_VERSION}.jar"
        fapi = None
        gc = Path(gradle_cache) if gradle_cache else platform.home() / ".gradle/caches/modules-2/files-2.1"
        for c in sorted((gc / "net.fabricmc.fabric-api/fabric-api" / FABRIC_API_VERSION).glob(f"*/{fapi_name}")):
            if _hash(c, "sha512") == FABRIC_API_SHA512:
                fapi = c
                break
        if fapi is None:
            fapi = _cached_download(cache, fapi_name, FABRIC_API_URL, FABRIC_API_SHA512, tmpd, fetch, log)

        # ---- extra mods, cached ----
        extra = [(prefix, _cached_download(cache, Path(url).name, url, sha, tmpd, fetch, log))
                 for prefix, url, sha in EXTRA_MODS]

        # ---- Java 25 ----
        if java is None:
            java = platform.java_exe(jdir)
            if not java.exists():
                _install_temurin(jdir, tmpd, fetch, log)
        if java_major(java) != 25:
            raise PrismError(f"{java} is not Java 25")

        # ---- the instance (a new one is assembled in staging, then renamed in) ----
        if final.is_dir():
            d = final
        else:
            d = staging
            shutil.rmtree(d, ignore_errors=True)
        game = instance_game_dir(d)
        (game / "mods").mkdir(parents=True, exist_ok=True)
        safeio.write_text(d / "mmc-pack.json", json.dumps(mmc_pack(), indent=4) + "\n")
        update_instance_cfg(d / "instance.cfg", INSTANCE, java, envs, unset)

        mods = game / "mods"
        for pat in ("gmodcraft-*.jar", "fabric-api-*.jar"):
            for old in mods.glob(pat):
                old.unlink()
        safeio.copy_file(jar, mods / (jar.name if jar.name.startswith("gmodcraft-") else "gmodcraft-" + jar.name))
        safeio.copy_file(fapi, mods / fapi_name)
        for prefix, f in extra:
            for old in mods.glob(prefix + "*.jar"):
                old.unlink()
            safeio.copy_file(f, mods / f.name)

        if d != final:
            final.parent.mkdir(exist_ok=True)   # a Prism data folder without instances/ yet
            d.rename(final)  # one rename: Prism only ever sees the complete instance
        elif platform.prism_running():
            if platform.java_with_arg_running(JVM_ARG):
                log("note: GmodCraft is running; the new settings apply after Prism reloads the instance (restart Prism)")
            else:
                final.rename(reload_dir)
                try:
                    sleep(3)
                finally:
                    reload_dir.rename(final)
                sleep(2)
        log(f"Prism instance {INSTANCE} ready at {final}")
        log(f"  Minecraft {MC_VERSION}, Fabric Loader {LOADER_VERSION}, {fapi_name}, {jar.name}; Java {java}")
        return final
    finally:
        shutil.rmtree(tmpd, ignore_errors=True)


def _cached_download(cache, name, url, sha512, tmpd, fetch, log):
    cache.mkdir(parents=True, exist_ok=True)
    f = cache / name
    if f.is_file() and _hash(f, "sha512") == sha512:
        return f
    log(f"downloading {url}")
    t = tmpd / "dl.jar"
    fetch(url, t)
    if _hash(t, "sha512") != sha512:
        raise PrismError(f"{name} doesn't match its pinned sha512")
    shutil.move(str(t), str(f))
    return f


def _install_temurin(jdir, tmpd, fetch, log):
    pin = platform.temurin_pin()
    if not pin:
        raise PrismError("no pinned Java 25 download for this OS/CPU: install Java 25 and set GMODCRAFT_JAVA")
    log(f"downloading the Temurin {pin['version']} JRE into {jdir}")
    t = tmpd / "jre.tgz"
    fetch(pin["url"], t)
    if _hash(t, "sha256") != pin["sha256"]:
        raise PrismError("the downloaded JRE doesn't match its pinned sha256; not installing it")
    tmp = jdir.with_name(jdir.name + ".tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    with tarfile.open(t) as tf:
        try:   # the "data" filter refuses absolute paths, ".." and links that leave the folder
            tf.extractall(tmp, filter="data")
        except TypeError:  # Python without extraction filters
            if any(Path(m.name).is_absolute() or ".." in Path(m.name).parts for m in tf.getmembers()):
                raise PrismError("unsafe path in the JRE archive")
            tf.extractall(tmp)
    tops = list(tmp.iterdir())
    if len(tops) != 1 or not platform.java_exe(tops[0]).exists():   # like tar --strip-components=1
        shutil.rmtree(tmp, ignore_errors=True)
        raise PrismError("unexpected JRE archive layout")
    shutil.rmtree(jdir, ignore_errors=True)
    jdir.parent.mkdir(parents=True, exist_ok=True)
    tops[0].rename(jdir)
    shutil.rmtree(tmp, ignore_errors=True)


def remove_instance(prism_data, log, dry_run=False):
    """Delete instances/GmodCraft (its worlds too). Only on explicit request; the Java runtime under
    java/ and the rest of Prism are left alone."""
    final = paths(prism_data)["final"]
    if not os.path.lexists(final):
        log(f"no Prism instance at {final}")
        return
    if final.is_symlink() or not final.is_dir():
        raise PrismError(f"{final} is not a plain folder; not deleting it")
    if dry_run:
        log(f"dry run: would delete the Prism instance {final} (with its worlds)")
        return
    if platform.java_with_arg_running(JVM_ARG):
        raise PrismError("GmodCraft (Minecraft) is running: quit it first")
    if platform.prism_running():
        raise PrismError("Prism Launcher is running: quit it first (it keeps the instance in memory)")
    try:
        shutil.rmtree(final)
    except OSError as e:
        raise PrismError(f"deleting {final}: {e}") from e
    log(f"deleted the Prism instance {final}")


# P8 WP3: the single-player world's types (MirrorWorld makes a new world with the preset that
# config/gmodcraft.properties worldType names; an existing world keeps its own).
WORLD = "GmodCraft"  # the world's folder in saves/ (GmodCraft.WORLD_NAME)
WORLD_TYPES = ("mirror", "flat_void_maps", "flat_everywhere")
WORLD_TYPE_LABELS = {"mirror": "Void (only the GMod maps)", "flat_void_maps": "Flat ground, void under the maps",
                     "flat_everywhere": "Flat ground everywhere"}
_PROP_KEY_RE = re.compile(r"^\s*([^#!=:\s][^=:\s]*)\s*(?:[=:\s].*)?$")


def set_properties(text, values):
    """config/gmodcraft.properties text with these keys set: each key's lines replaced, missing keys
    appended; comments and other keys (destruction=, join=, the server rules) kept as they are."""
    lines = text.splitlines() if text else []
    done = set()
    for i, line in enumerate(lines):
        m = _PROP_KEY_RE.match(line)
        if m and m.group(1) in values:
            lines[i] = f"{m.group(1)}={values[m.group(1)]}"
            done.add(m.group(1))
    lines += [f"{k}={v}" for k, v in values.items() if k not in done]
    return "\n".join(lines) + "\n"


def new_world(prism_data, world_type, log, dry_run=False, now=None):
    """Single player's "New world": the instance's saves/GmodCraft is moved aside to
    saves/GmodCraft.bak-<timestamp> (never deleted) and config/gmodcraft.properties gets
    worldType=<world_type>, so Minecraft makes a fresh world of that type on its next start.
    Refused while the game runs. Returns the backup's path (None: there was no world)."""
    import time
    if world_type not in WORLD_TYPES:
        raise PrismError(f"unknown world type {world_type!r} ({', '.join(WORLD_TYPES)})")
    final = paths(prism_data)["final"]
    if not final.is_dir() or final.is_symlink():
        raise PrismError(f"no Prism instance at {final}: install first")
    game = instance_game_dir(final)
    saves, cfg = game / "saves", game / "config" / "gmodcraft.properties"
    world = saves / WORLD
    stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime(now))
    bak = saves / f"{WORLD}.bak-{stamp}"
    if os.path.lexists(world):
        if world.is_symlink() or not world.is_dir():
            raise PrismError(f"{world} is not a plain folder; not moving it")
        if os.path.lexists(bak):
            raise PrismError(f"{bak} exists already; try again in a second")
    if dry_run:
        log(f"dry run: would move {world} aside to {bak}" if os.path.lexists(world) else f"dry run: no world at {world}")
        log(f"dry run: would set worldType={world_type} in {cfg}")
        return None
    if platform.java_with_arg_running(JVM_ARG):
        raise PrismError("GmodCraft (Minecraft) is running: quit the game first")
    if os.path.lexists(cfg) and (cfg.is_symlink() or not cfg.is_file()):
        raise PrismError(f"{cfg} is not a plain file; not writing it")
    old = cfg.read_text(encoding="utf-8") if cfg.is_file() else "# Garry's Modcraft\n"
    moved = None
    if os.path.lexists(world):
        os.rename(world, bak)  # same folder: atomic, nothing copied, nothing deleted
        moved = bak
        log(f"world moved aside: {world} -> {bak} (delete it yourself once you don't need it)")
    else:
        log(f"no world at {world} yet")
    cfg.parent.mkdir(parents=True, exist_ok=True)
    safeio.write_text(cfg, set_properties(old, {"worldType": world_type}))
    log(f"the next start makes a new {WORLD_TYPE_LABELS[world_type].lower()} world (worldType={world_type} in {cfg})")
    return moved


def check_instance(prism_data, expected_jar=None):
    """List of (label, ok, detail) for the status panel. expected_jar: manifest entry (path, sha256)."""
    out = []
    final = paths(prism_data)["final"]
    if not final.is_dir():
        return [("Instance GmodCraft", False, f"not set up ({final})")]
    try:
        comps = {c.get("uid"): c.get("version") for c in json.loads((final / "mmc-pack.json").read_text())["components"]}
    except (OSError, ValueError, KeyError, TypeError):
        comps = {}
    pack_ok = comps.get("net.minecraft") == MC_VERSION and comps.get("net.fabricmc.fabric-loader") == LOADER_VERSION
    out.append(("Instance GmodCraft", pack_ok,
                f"Minecraft {comps.get('net.minecraft')}, Fabric Loader {comps.get('net.fabricmc.fabric-loader')}"
                + ("" if pack_ok else f" (want {MC_VERSION} / {LOADER_VERSION})")))
    mods = instance_game_dir(final) / "mods"
    jars = sorted(mods.glob("gmodcraft-*.jar"))
    if not jars:
        out.append(("  GmodCraft mod jar", False, "missing"))
    elif expected_jar is not None:
        ok = any(_hash(j, "sha256") == expected_jar["sha256"] for j in jars)
        out.append(("  GmodCraft mod jar", ok, ", ".join(j.name for j in jars) + ("" if ok else f" (source has {Path(expected_jar['path']).name}: update)")))
    else:
        out.append(("  GmodCraft mod jar", True, ", ".join(j.name for j in jars)))
    fapi = mods / f"fabric-api-{FABRIC_API_VERSION}.jar"
    out.append(("  fabric-api", fapi.is_file(), fapi.name if fapi.is_file() else f"want {fapi.name}"))
    for prefix, url, sha in EXTRA_MODS:
        f = mods / Path(url).name
        label = "  JEI" if prefix == "jei-" else "  " + prefix.rstrip("-")
        out.append((label, f.is_file() and _hash(f, "sha512") == sha, f.name if f.is_file() else "missing"))
    cfg = read_instance_cfg(final / "instance.cfg")
    jp = cfg.get("JavaPath")
    major = java_major(jp) if jp else None
    out.append(("  Java 25", major == 25, f"{jp} (Java {major})" if jp else "no JavaPath in instance.cfg"))
    return out


def read_account(prism_data):
    """(ok, detail) from accounts.json: only the active / ownsMinecraft flags, never tokens or names."""
    p = Path(prism_data) / "accounts.json"
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return False, "no accounts.json: add a Microsoft account in Prism"
    except (OSError, ValueError):
        return False, "accounts.json unreadable"
    accts = data.get("accounts") if isinstance(data, dict) else None
    if not isinstance(accts, list) or not accts:
        return False, "no account: add a Microsoft account in Prism"
    active = [a for a in accts if isinstance(a, dict) and a.get("active") is True]
    if not active:
        return False, f"{len(accts)} account(s), none active: pick one in Prism"
    ent = active[0].get("entitlement") if isinstance(active[0].get("entitlement"), dict) else {}
    if ent.get("ownsMinecraft") is True:
        return True, "active account owns Minecraft"
    return False, "active account doesn't own Minecraft (or Prism hasn't checked yet)"
