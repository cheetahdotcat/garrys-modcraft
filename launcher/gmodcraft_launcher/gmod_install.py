"""Install / update / uninstall the native modules and the addon in a GMod folder, by copying.

Ownership: every file the launcher writes is recorded (path relative to garrysmod/ + sha256) in
installed.json in platform.config_dir(), per GMod folder, together with the folders it created.
  * A file or folder it didn't write is "foreign": the launcher refuses to touch it, unless asked to
    replace foreign files (replace_foreign=True), and then it moves it into a backup first.
  * Anything it replaces or removes that it doesn't have an identical copy of in the source is
    moved into a backup folder (platform.data_dir()/backups/<time>/garrysmod/...), never deleted.
    Its own unmodified files are simply replaced or removed.
  * Uninstall removes only recorded files and the recorded folders that are empty afterwards.
Backups live outside the GMod tree on purpose: a copy under addons/ would load as an addon.
"""
import os
import shutil
import time
import uuid
from pathlib import Path, PurePosixPath

from . import config, platform, safeio
from .bundle import sha256_file

ADDON_ROOT = "addons/gmodcraft"
MODULE_DIR = "lua/bin"


def state_path():
    return platform.config_dir() / "installed.json"


def _key(gmod_dir):
    return str(Path(gmod_dir).resolve())


def load_state_all():
    import json
    try:
        with open(state_path(), encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict) and isinstance(data.get("installs"), dict):
            return data
    except (OSError, ValueError):
        pass
    return {"format": 1, "installs": {}}


def load_state(gmod_dir):
    st = load_state_all()["installs"].get(_key(gmod_dir))
    if not st:
        st = {"files": {}, "dirs": []}
    st.setdefault("files", {})
    st.setdefault("dirs", [])
    return st


def save_state(gmod_dir, st):
    data = load_state_all()
    if st is None or (not st.get("files") and not st.get("dirs")):
        data["installs"].pop(_key(gmod_dir), None)
    else:
        data["installs"][_key(gmod_dir)] = st
    config.write_json_atomic(state_path(), data)


class Op:
    def __init__(self, kind, rel, src=None, sha=None, note=""):
        self.kind = kind    # mkdir | backup | copy | remove | rmdir
        self.rel = rel      # path relative to garrysmod/, posix
        self.src = src
        self.sha = sha
        self.note = note

    def __str__(self):
        s = {"mkdir": "create folder", "backup": "back up + move away", "copy": "copy",
             "remove": "delete", "rmdir": "remove empty folder", "forget": "forget"}[self.kind]
        tail = f"  <- {self.src}" if self.src else ""
        return f"{s:20} garrysmod/{self.rel}{tail}{('  (' + self.note + ')') if self.note else ''}"


class Plan:
    def __init__(self, action, gmod_dir):
        self.action = action
        self.gmod_dir = Path(gmod_dir)
        self.ops = []
        self.errors = []
        self.meta = {}

    @property
    def gm(self):
        return self.gmod_dir / "garrysmod"

    def describe(self):
        lines = [f"{self.action}: {len(self.ops)} file operation(s) in {self.gm}"]
        lines += ["  " + str(o) for o in self.ops]
        lines += ["  ERROR: " + e for e in self.errors]
        return "\n".join(lines)


def targets(source, arch=None):
    """{rel under garrysmod/: manifest entry} for this OS's modules and the addon."""
    arch = arch or platform.module_arch()
    out = {}
    for f in source.modules(arch):
        out[f"{MODULE_DIR}/{PurePosixPath(f['path']).name}"] = f
    for f in source.files("addon"):
        p = PurePosixPath(f["path"])
        out[str(PurePosixPath(ADDON_ROOT, *p.parts[2:]))] = f   # addon/gmodcraft/<rest>
    return out


def _sha(p):
    try:
        return None if p.is_symlink() or not p.is_file() else sha256_file(p)
    except OSError:
        return None


def plan_install(source, gmod_dir, state=None, replace_foreign=False, arch=None):
    plan = Plan("install", gmod_dir)
    gm = plan.gm
    if not gm.is_dir():
        plan.errors.append(f"no garrysmod/ folder in {gmod_dir}")
        return plan
    st = state if state is not None else load_state(gmod_dir)
    owned = st["files"]
    owned_dirs = set(st["dirs"])
    want = targets(source, arch)
    if not any(r.startswith(MODULE_DIR + "/") for r in want):
        plan.errors.append(f"the source has no modules for {arch or platform.module_arch()}")
    plan.meta = {"version": source.version, "module_version": source.manifest.get("module_version"),
                 "source": source.describe()}

    def foreign(rel, what):
        if replace_foreign:
            plan.ops.append(Op("backup", rel, note=f"not installed by the launcher: {what}"))
            return True
        plan.errors.append(f"garrysmod/{rel} exists and wasn't installed by the launcher ({what}); "
                           "remove it (dev install: module/install.sh --uninstall) or allow replacing "
                           "foreign files (it is backed up first)")
        return False

    # Folders that get replaced as a whole (backed up first) or that block everything below them.
    # Never write through a symlinked folder: a dev link (addons/gmodcraft, addons/gmodcraft/lua,
    # lua/bin -> a checkout) is foreign; with replace_foreign the LINK is moved away, not its target.
    # GMod's own top folders are never ours to replace: a linked garrysmod/addons or garrysmod/lua
    # is refused outright, even with replace_foreign.
    for top in ("addons", "lua"):
        if (gm / top).is_symlink():
            plan.errors.append(f"garrysmod/{top} is a symlink ({os.readlink(gm / top)}); the launcher doesn't "
                               "install into a linked GMod folder, and won't replace it either. Make it a real "
                               "folder (or install by hand)")
    if plan.errors:
        return plan
    replaced, blocked = set(), set()
    root = gm / ADDON_ROOT
    if os.path.lexists(root) and not root.is_symlink() and root.is_dir():
        ours = ADDON_ROOT in owned_dirs or any(r.startswith(ADDON_ROOT + "/") for r in owned)
        if not ours:   # someone else's real folder
            (replaced if foreign(ADDON_ROOT, "an existing folder") else blocked).add(ADDON_ROOT)

    created = set()
    for rel in sorted(want):
        e = want[rel]
        dst = gm / rel
        anc = _ancestors(rel)
        if any(d in blocked for d in anc):
            continue
        fresh = next((d for d in anc if d in replaced), None)
        if fresh is None:
            bad = next((d for d in anc if os.path.lexists(gm / d) and ((gm / d).is_symlink() or not (gm / d).is_dir())), None)
            if bad is not None:
                what = "a symlinked folder" if (gm / bad).is_symlink() else "not a folder"
                if not foreign(bad, what):
                    blocked.add(bad)
                    continue
                replaced.add(bad)
                fresh = bad
        for d in anc:
            if d not in created and ((fresh is not None and _under(d, fresh)) or not os.path.lexists(gm / d)):
                plan.ops.append(Op("mkdir", d))
                created.add(d)
        if fresh is None and os.path.lexists(dst):
            if rel in owned:
                cur = _sha(dst)
                if cur == e["sha256"]:
                    continue  # already the right file
                if cur != owned[rel]:
                    plan.ops.append(Op("backup", rel, note="changed since the launcher installed it"))
            else:
                if dst.is_dir() and not dst.is_symlink():
                    plan.errors.append(f"garrysmod/{rel} is a folder")
                    continue
                if not foreign(rel, "a symlink" if dst.is_symlink() else "a file"):
                    continue
        plan.ops.append(Op("copy", rel, src=source.path(e), sha=e["sha256"]))

    # Files of an older version that the new one doesn't have.
    for rel in sorted(set(owned) - set(want)):
        if any(_under(rel, d) for d in replaced):
            continue   # goes into the backup with the replaced folder
        _plan_remove(plan, rel, owned[rel])
    return plan


def _ancestors(rel):
    """The folders on the way to rel, outermost first: a/b/c.lua -> [a, a/b]."""
    parts = PurePosixPath(rel).parts[:-1]
    return [str(PurePosixPath(*parts[:i])) for i in range(1, len(parts) + 1)]


def _under(rel, d):
    return rel == d or rel.startswith(d + "/")


def _behind_symlink(gm, rel):
    """A folder on the way to rel (below garrysmod/) is a symlink: e.g. addons/gmodcraft was replaced
    by a dev symlink into a checkout after the launcher installed. Never delete through it."""
    parts = PurePosixPath(rel).parts[:-1]
    return any((gm / PurePosixPath(*parts[:i])).is_symlink() for i in range(1, len(parts) + 1))


def _plan_remove(plan, rel, sha):
    p = plan.gm / rel
    if _behind_symlink(plan.gm, rel):
        plan.ops.append(Op("forget", rel, note="behind a symlink now: left alone"))
    elif not os.path.lexists(p):
        plan.ops.append(Op("remove", rel, note="already gone"))
    elif p.is_dir() and not p.is_symlink():
        plan.errors.append(f"garrysmod/{rel} is now a folder; leaving it")
    elif _sha(p) == sha:
        plan.ops.append(Op("remove", rel))
    else:
        plan.ops.append(Op("backup", rel, note="changed since the launcher installed it"))
        plan.ops.append(Op("remove", rel, note="already moved to the backup"))


def plan_uninstall(gmod_dir, state=None):
    plan = Plan("uninstall", gmod_dir)
    st = state if state is not None else load_state(gmod_dir)
    for rel in sorted(st["files"]):
        _plan_remove(plan, rel, st["files"][rel])
    for d in sorted(st["dirs"], key=lambda d: -len(PurePosixPath(d).parts)):
        if _behind_symlink(plan.gm, d + "/x"):   # d itself or a parent is a symlink
            plan.ops.append(Op("forget", d, note="the folder the launcher created is a symlink now: left alone"))
        else:
            plan.ops.append(Op("rmdir", d, note="only if empty"))
    return plan


def execute(plan, log, dry_run=False, state=None, backup_root=None):
    """Run a plan. The state is saved after every step, so an interrupted run still knows its files."""
    log(plan.describe() if dry_run else f"{plan.action}: {len(plan.ops)} operation(s) in {plan.gm}")
    if plan.errors:
        raise InstallError("; ".join(plan.errors))
    if dry_run:
        log("dry run: nothing changed")
        return None
    if platform.gmod_running():
        raise InstallError("GMod is running: quit it first")
    gm = plan.gm
    st = state if state is not None else load_state(plan.gmod_dir)
    # unique per run: two runs in the same second never share a backup folder
    stamp = time.strftime("%Y%m%d-%H%M%S") + f"-{os.getpid()}-{uuid.uuid4().hex[:6]}"
    broot = Path(backup_root) if backup_root else platform.data_dir() / "backups" / stamp
    for op in plan.ops:
        try:
            _apply(op, gm, st, broot, plan, log)
        except OSError as e:
            save_state(plan.gmod_dir, st)
            raise InstallError(f"{op.kind} garrysmod/{op.rel}: {e}") from e
        save_state(plan.gmod_dir, st)
    if plan.action == "install":
        st.update(plan.meta)
        st["installed_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
        save_state(plan.gmod_dir, st)
    else:
        save_state(plan.gmod_dir, None if not st["files"] and not st["dirs"] else st)
    log(f"{plan.action} done")
    return st


def _apply(op, gm, st, broot, plan, log):
    p = gm / op.rel
    # Checked again right before writing (the tree may have changed since planning): nothing is
    # ever created, copied, moved or deleted through a symlinked folder.
    if op.kind != "forget" and _behind_symlink(gm, op.rel):
        raise InstallError(f"garrysmod/{op.rel}: a folder on the way is a symlink now; not writing through it")
    if op.kind == "mkdir":
        p.mkdir(parents=False, exist_ok=False)
        if op.rel not in st["dirs"]:
            st["dirs"].append(op.rel)
    elif op.kind == "backup":
        b = broot / "garrysmod" / op.rel
        b.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(p), str(b))
        log(f"backed up garrysmod/{op.rel} -> {b}")
        # it's no longer ours (or never was); a replaced foreign root takes owned children along
        for r in [r for r in st["files"] if r == op.rel or r.startswith(op.rel + "/")]:
            del st["files"][r]
        st["dirs"] = [d for d in st["dirs"] if not (d == op.rel or d.startswith(op.rel + "/"))]
    elif op.kind == "copy":
        st["files"][op.rel] = op.sha   # recorded first: an interrupted copy is still ours
        save_state(plan.gmod_dir, st)
        def check(tmp):
            if sha256_file(tmp) != op.sha:
                raise InstallError(f"copy of {op.src} doesn't match its sha256")
        safeio.copy_file(op.src, p, check)   # never follows a link at p or at its temp path
    elif op.kind == "remove":
        if os.path.lexists(p):
            p.unlink()
        st["files"].pop(op.rel, None)
    elif op.kind == "forget":
        st["files"].pop(op.rel, None)
        st["dirs"] = [d for d in st["dirs"] if d != op.rel]
        log(f"left garrysmod/{op.rel} alone ({op.note})")
    elif op.kind == "rmdir":
        if p.is_symlink():
            log(f"left garrysmod/{op.rel} alone: the folder the launcher created is a symlink now")
            st["dirs"] = [d for d in st["dirs"] if d != op.rel]
            return
        try:
            p.rmdir()
        except FileNotFoundError:
            pass
        except OSError:
            log(f"kept garrysmod/{op.rel}: not empty (files the launcher didn't install)")
        st["dirs"] = [d for d in st["dirs"] if d != op.rel]


class InstallError(Exception):
    pass


def check(gmod_dir, source=None, arch=None):
    """[(ok, detail) for the modules, (ok, detail) for the addon] for the status panel.
    ok is True / False, or None when it can't be judged (dev symlinks from module/install.sh)."""
    gm = Path(gmod_dir) / "garrysmod"
    st = load_state(gmod_dir)
    owned = st["files"]
    arch = arch or platform.module_arch()
    want = targets(source, arch) if source is not None else None

    def judge(rels, label_ok):
        missing = [r for r in rels if not os.path.lexists(gm / r)]
        if missing:
            return False, "missing: " + ", ".join(PurePosixPath(r).name for r in missing[:3])
        if all((gm / r).is_symlink() for r in rels) and not any(r in owned for r in rels):
            return None, "dev symlinks (module/install.sh), not managed by the launcher"
        foreign = [r for r in rels if r not in owned]
        if foreign:
            return False, "not installed by the launcher: " + ", ".join(foreign[:2])
        changed = [r for r in rels if _sha(gm / r) != owned[r]]
        if changed:
            return False, f"{len(changed)} file(s) changed since the launcher installed them"
        return True, label_ok

    mods = [f"{MODULE_DIR}/{n}" for n in platform.module_names(arch)]
    m_ok, m_detail = judge(mods, f"version {st.get('module_version') or '?'}")
    if m_ok and want is not None and any(want.get(r, {}).get("sha256") != owned[r] for r in mods):
        m_ok, m_detail = False, (f"{st.get('module_version') or '?'} installed, source has "
                                 f"{source.manifest.get('module_version') or source.version}: update")

    addon = sorted(r for r in owned if r.startswith(ADDON_ROOT + "/"))
    root = gm / ADDON_ROOT
    if not addon:
        if root.is_symlink():
            a_ok, a_detail = None, f"dev symlink -> {os.readlink(root)} (not managed by the launcher)"
        elif root.exists():
            a_ok, a_detail = False, "a folder the launcher didn't install"
        else:
            a_ok, a_detail = False, "not installed"
    else:
        a_ok, a_detail = judge(addon, f"{len(addon)} files, {st.get('version') or '?'}")
        if a_ok and want is not None:
            w = {r: e["sha256"] for r, e in want.items() if r.startswith(ADDON_ROOT + "/")}
            if w != {r: owned[r] for r in addon}:
                a_ok, a_detail = False, f"{st.get('version') or '?'} installed, source has {source.version}: update"
    return [(m_ok, m_detail), (a_ok, a_detail)]
