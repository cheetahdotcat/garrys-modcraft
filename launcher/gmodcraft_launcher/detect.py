"""Read-only detection: Steam libraries, GMod and its branch, Prism, and the status list."""
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

from . import gmod_install, platform, prism, vdf


class Check:
    def __init__(self, label, ok, detail=""):
        self.label = label
        self.ok = ok          # True / False / None (can't tell)
        self.detail = detail

    def __repr__(self):
        return f"Check({self.label!r}, {self.ok!r}, {self.detail!r})"


def steam_libraries(roots=None):
    """Library folders from every Steam root's libraryfolders.vdf (the root itself included)."""
    seen, out = set(), []

    def add(p):
        p = Path(p)
        try:
            key = p.resolve()
        except OSError:
            key = p
        if key not in seen and (p / "steamapps").is_dir():
            seen.add(key)
            out.append(p)

    for root in roots if roots is not None else platform.steam_roots():
        root = Path(root)
        if not (root / "steamapps").is_dir():
            continue
        add(root)
        f = root / "steamapps" / "libraryfolders.vdf"
        if f.is_file():
            try:
                for p in vdf.library_paths(vdf.load(f)):
                    add(p)
            except vdf.VdfError:
                pass
    return out


class GmodInfo:
    def __init__(self, path, branch=None, is64=None, library=None):
        self.path = Path(path)
        self.branch = branch      # Steam beta key, "" = public branch, None = unknown
        self.is64 = is64
        self.library = library


def find_gmod(libraries=None):
    override = platform.gmod_dir_override()
    if override:
        return GmodInfo(override, None, platform.gmod_is_64bit(override)) if (override / "garrysmod").is_dir() else None
    for lib in libraries if libraries is not None else steam_libraries():
        acf = Path(lib) / "steamapps" / f"appmanifest_{platform.GMOD_APPID}.acf"
        if not acf.is_file():
            continue
        try:
            st = vdf.get_ci(vdf.load(acf), "AppState") or {}
        except vdf.VdfError:
            continue
        d = Path(lib) / "steamapps" / "common" / (vdf.get_ci(st, "installdir") or "GarrysMod")
        if (d / "garrysmod").is_dir():
            beta = vdf.get_ci(vdf.get_ci(st, "UserConfig") or {}, "BetaKey") or vdf.get_ci(vdf.get_ci(st, "MountedConfig") or {}, "BetaKey") or ""
            return GmodInfo(d, beta, platform.gmod_is_64bit(d), Path(lib))
    return None


@dataclass
class PrismInstall:
    """A Prism Launcher on this machine (R1): the AppImage (any path), a distro package
    (/usr/bin/prismlauncher, e.g. pacman on Arch/CachyOS) or the flatpak. Windows: "exe", a
    prismlauncher.exe (installer or portable)."""
    kind: str            # "appimage" | "package" | "flatpak" | "exe"
    command: list        # argv without "--launch <instance>"; written to config.json "prismCommand"
    exe: Path            # what must be executable (the flatpak binary for the flatpak)
    data: Path           # Prism's data folder (instances/, accounts.json)

    def describe(self):
        return {"appimage": f"AppImage {self.exe}", "package": f"package {self.exe}",
                "flatpak": f"flatpak {platform.FLATPAK_PRISM}", "exe": f"Windows exe {self.exe}"}[self.kind]


PRISM_KINDS = ("auto", "exe") if platform.SYSTEM == "windows" else ("auto", "appimage", "package", "flatpak")


def _appimage(p):
    return PrismInstall("appimage", [str(p)], Path(p), platform.prism_data_native())


def _package(p):
    return PrismInstall("package", [str(p)], Path(p), platform.prism_data_native())


def _exe(p):
    """prismlauncher.exe: a portable one keeps its data next to itself, an installed one in %APPDATA%
    (both unverified on a real machine)."""
    data = platform.prism_data_portable(p) or platform.prism_data_default()
    return PrismInstall("exe", [str(p)], Path(p), data)


def _flatpak():
    fp = shutil.which("flatpak") or "/usr/bin/flatpak"
    return PrismInstall("flatpak", ["flatpak", "run", platform.FLATPAK_PRISM], Path(fp), platform.prism_data_flatpak())


def find_prism(cfg):
    """The Prism to use: the configured AppImage, else by cfg["prismKind"] (auto: the default
    AppImage, then a package, then the flatpak). None when there is none."""
    cfg = cfg or {}
    kind = cfg.get("prismKind") or "auto"
    p = cfg.get("prismAppImage")
    if platform.SYSTEM == "windows":
        if p and kind in ("auto", "exe"):
            return _exe(p)
        for c in platform.prism_exe_candidates():
            if c.exists() and kind in ("auto", "exe"):
                return _exe(c)
        return None
    if p and kind in ("auto", "appimage"):
        return _appimage(p)
    found = []
    default = platform.prism_exe_default()
    for c in platform.prism_exe_candidates():
        if c.exists():
            found.append(_appimage(c) if (default and c == default) or c.name.endswith(".AppImage") else _package(c))
    if platform.flatpak_prism_installed():
        found.append(_flatpak())
    for f in found:
        if kind in ("auto", f.kind):
            return f
    return None


def find_prism_exe(cfg):
    """The configured Prism executable, else the first candidate that exists (the flatpak binary for the flatpak)."""
    inst = find_prism(cfg)
    return inst.exe if inst else None


def prism_command(cfg):
    """argv the GMod module and the pre-warm start Prism with (without "--launch <instance>"), or []."""
    inst = find_prism(cfg)
    return list(inst.command) if inst else []


def prism_data_for(cfg=None):
    """Prism's data folder: PRISM_DATA, else the chosen Prism's (the flatpak keeps its own), else the default."""
    if os.environ.get("PRISM_DATA"):
        return Path(os.environ["PRISM_DATA"])
    inst = find_prism(cfg) if cfg is not None else None
    return inst.data if inst else platform.prism_data_default()


def status(cfg, source=None, gmod=None, prism_data=None):
    """The status panel's checks, in display order."""
    checks = []
    gmod = gmod if gmod is not None else find_gmod()
    if gmod is None:
        checks.append(Check("Garry's Mod", False, "not found in any Steam library (set GMOD_DIR)"))
    else:
        branch = gmod.branch if gmod.branch is not None else "?"
        if gmod.is64 is False:
            checks.append(Check("Garry's Mod", False, f"{gmod.path}: 32-bit; switch to the x86-64 branch in Steam (Properties > Betas)"))
        else:
            checks.append(Check("Garry's Mod", True if gmod.is64 else None, f"{gmod.path} (branch {branch or 'public'}{', 64-bit' if gmod.is64 else ''})"))
        (m_ok, m_d), (a_ok, a_d) = gmod_install.check(gmod.path, source)
        checks.append(Check("Native modules", m_ok, m_d))
        checks.append(Check("Addon", a_ok, a_d))

    inst = find_prism(cfg)
    pdata = Path(prism_data) if prism_data else prism_data_for(cfg)
    if inst and inst.exe.exists() and platform.is_executable(inst.exe):
        # The GMod module starts what config.json's prismCommand says (written by the launcher).
        detail = inst.describe()
        checks.append(Check("Prism Launcher", True if pdata.is_dir() else False,
                            detail if pdata.is_dir() else f"{detail}; no data folder {pdata} (start Prism once)"))
    else:
        checks.append(Check("Prism Launcher", False, f"{inst.describe()}: not executable" if inst else
                            "not found: choose prismlauncher.exe, or install Prism Launcher" if platform.SYSTEM == "windows" else
                            "not found: choose the AppImage, or install prismlauncher (pacman) or its flatpak"))

    if pdata.is_dir():
        jar = source.fabric_jar() if source is not None else None
        for label, ok, detail in prism.check_instance(pdata, jar):
            checks.append(Check(label, ok, detail))
        ok, detail = prism.read_account(pdata)
        checks.append(Check("Minecraft account", ok, detail))
    return checks
