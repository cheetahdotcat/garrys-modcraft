#!/usr/bin/env python3
"""Build a release bundle and the launcher zipapp from a checkout's build outputs.

  launcher/build_bundle.py [--repo DIR] [--out DIR] [--version V] [--launcher-only]

Needs module/build/*.dll (module/build.sh) and fabric/build/libs/gmodcraft-<version>.jar
(cd fabric && ./gradlew build). Writes, into --out (default <repo>/dist, git-ignored):
  gmodcraft-launcher.pyz            the launcher, a single-file Python zipapp (python3 gmodcraft-launcher.pyz)
  garrys-modcraft-<version>.zip     the bundle: modules, addon, mod jar, the launcher, gmodcraft-bundle.json
"""
import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import zipapp
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from gmodcraft_launcher import bundle, protocol  # noqa: E402

ZIPAPP_MAIN = "import sys\nfrom gmodcraft_launcher.__main__ import main\nsys.exit(main())\n"


def build_zipapp(out):
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as stage:
        shutil.copytree(HERE / "gmodcraft_launcher", Path(stage) / "gmodcraft_launcher",
                        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        shutil.copyfile(HERE / "pins.json", Path(stage) / "gmodcraft_launcher" / "pins.json")
        # The link protocol, derived from the header (query.py compares servers' gmodcraft_version with it).
        kversion = protocol.parse_kversion(protocol.HEADER.read_text(encoding="utf-8"))
        if not kversion:
            raise SystemExit(f"no kVersion in {protocol.HEADER}")
        (Path(stage) / "gmodcraft_launcher" / "protocol.json").write_text(json.dumps({"kVersion": kversion}) + "\n")
        (Path(stage) / "__main__.py").write_text(ZIPAPP_MAIN)
        zipapp.create_archive(stage, out, interpreter="/usr/bin/env python3", compressed=True)
    return out


# The server owner's scripts (tools/server_setup.sh and what it runs), added to the zip outside the
# manifest: the launcher ignores them (open_bundle only unpacks manifest entries); SHA256SUMS covers
# the whole zip. From --repo, else this checkout (test repos don't have tools/).
SERVER_KIT = ["tools/server_setup.sh", "tools/run_mc_server.sh", "tools/query_server.py"]


def add_server_kit(repo, zip_path):
    with zipfile.ZipFile(zip_path, "a", zipfile.ZIP_DEFLATED) as zf:
        for rel in SERVER_KIT:
            src = next((p for p in (Path(repo) / rel, HERE.parent / rel) if p.is_file()), None)
            if src is None:
                raise bundle.BundleError(f"no {rel} for the server kit")
            info = zipfile.ZipInfo.from_file(src, rel)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if rel.endswith(".sh") else 0o100644) << 16
            zf.writestr(info, src.read_bytes())


def git_sha(repo):
    try:
        r = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=repo, capture_output=True, text=True, timeout=10)
        sha = r.stdout.strip() if r.returncode == 0 else None
        if sha:
            d = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=repo,
                               capture_output=True, text=True, timeout=10)
            if d.stdout.strip():
                sha += "-dirty"
        return sha
    except (OSError, subprocess.SubprocessError):
        return None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=str(HERE.parent))
    ap.add_argument("--out")
    ap.add_argument("--version", help="bundle version (default: the Fabric mod's version)")
    ap.add_argument("--launcher-only", action="store_true", help="only build the zipapp")
    a = ap.parse_args(argv)
    repo = Path(a.repo).resolve()
    out = Path(a.out) if a.out else repo / "dist"
    pyz = build_zipapp(out / "gmodcraft-launcher.pyz")
    print(f"launcher: {pyz}")
    if a.launcher_only:
        return 0
    try:
        m = bundle.scan_checkout(repo)   # fail early with a clear message
        version = a.version or m["version"]
        target = out / f"garrys-modcraft-{version}.zip"
        proto = out / "protocol.json"
        proto.write_text(json.dumps({"kVersion": m.get("protocol"), "version": version}) + "\n")
        m = bundle.write_bundle(repo, target, git_sha(repo),
                                extra=[(pyz, "launcher/gmodcraft-launcher.pyz", "launcher"),
                                       (proto, "protocol.json", "meta")], version=version)
        proto.unlink()
        add_server_kit(repo, target)
    except bundle.BundleError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    print(f"bundle: {target} ({len(m['files'])} files, version {m['version']}, module {m.get('module_version')}, git {m.get('git')})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
