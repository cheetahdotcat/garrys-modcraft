"""WP5: the launcher's Windows branches, run on Linux with mocks (platform.SYSTEM patched, a stub winreg,
fake process lists, no ctypes calls). What only a real Windows can show is listed in the WP5 report.
"""
import hashlib
import importlib
import io
import os
import subprocess
import sys
import types
import zipfile
from pathlib import Path
from unittest import mock

from helpers import TempEnv, write
from test_quit import FakeWorld

from gmodcraft_launcher import desktop, detect, platform, prewarm, prism, progress, quit as q


# TempEnv replaces these with fakes; the Windows tests need the real ones (captured before any setUp)
REAL = {n: getattr(platform, n) for n in ("gmod_running", "prism_running", "java_with_arg_running", "prism_exe_candidates")}


class WinEnv(TempEnv):
    """SYSTEM is "windows"; LOCALAPPDATA / APPDATA point into the temp dir."""

    def setUp(self):
        super().setUp()
        env = {"LOCALAPPDATA": str(self.tmp / "local"), "APPDATA": str(self.tmp / "roaming"),
               "ProgramFiles": str(self.tmp / "pf"), "ProgramFiles(x86)": str(self.tmp / "pf86"),
               "PRISM_DATA": "", "GMODCRAFT_STEAM_ROOT": ""}
        p = mock.patch.dict(os.environ, env)
        p.start()
        self.addCleanup(p.stop)
        s = mock.patch.object(platform, "SYSTEM", "windows")
        s.start()
        self.addCleanup(s.stop)
        for name, fn in REAL.items():
            r = mock.patch.object(platform, name, fn)
            r.start()
            self.addCleanup(r.stop)


def fake_winreg(values):
    """A winreg stand-in: HKCU\\Software\\Valve\\Steam holds `values`."""
    m = types.ModuleType("winreg")
    m.HKEY_CURRENT_USER = object()

    class Key:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    def open_key(root, sub):
        if sub != r"Software\Valve\Steam":
            raise FileNotFoundError(sub)
        return Key()

    def query(key, name):
        if name not in values:
            raise FileNotFoundError(name)
        return values[name], 1
    m.OpenKey, m.QueryValueEx = open_key, query
    return m


class ImportSafetyTest(TempEnv):
    def test_prewarm_imports_without_fcntl(self):
        try:
            with mock.patch.dict(sys.modules, {"fcntl": None}):        # "import fcntl" raises ImportError
                mod = importlib.reload(prewarm)
                self.assertIsNone(mod.fcntl)
        finally:
            importlib.reload(prewarm)
        self.assertIsNotNone(prewarm.fcntl)

    def test_quit_signals_exist_without_sigkill(self):
        self.assertEqual(platform.TERM, __import__("signal").SIGTERM)
        self.assertIn(platform.KILL, (9, getattr(__import__("signal"), "SIGKILL", 9)))

    def test_pread_fallback(self):
        f = write(self.tmp / "f.bin", b"0123456789")
        fd = os.open(f, os.O_RDONLY)
        try:
            with mock.patch.object(os, "pread", None):
                self.assertEqual(platform.pread(fd, 3, 4), b"456")
        finally:
            os.close(fd)

    def test_linux_unchanged(self):
        self.assertEqual(platform.run_dir(), Path("/dev/shm/gmodcraft"))
        self.assertEqual(platform.shm_dir(), Path("/dev/shm"))
        self.assertEqual(platform.read_open_flags() & os.O_RDONLY, os.O_RDONLY)


class PathsTest(WinEnv):
    def test_run_dir_and_shm(self):
        self.assertEqual(platform.run_dir(), self.tmp / "local" / "garrys-modcraft" / "run")
        self.assertEqual(platform.shm_dir(), platform.run_dir())
        self.assertEqual(platform.module_arch(), "win64")

    def test_steam_roots_registry_first(self):
        with mock.patch.dict(sys.modules, {"winreg": fake_winreg({"SteamPath": "d:/games/steam", "SteamExe": "d:/games/steam/steam.exe"})}):
            roots = platform.steam_roots()
        self.assertEqual(roots[0], Path("d:/games/steam"))
        self.assertIn(self.tmp / "pf86" / "Steam", roots)
        with mock.patch.dict(sys.modules, {"winreg": fake_winreg({})}):          # no key: only the folders
            self.assertEqual(platform.steam_roots()[0], self.tmp / "pf86" / "Steam")
        with mock.patch.dict(sys.modules, {"winreg": None}):                      # no winreg at all
            self.assertTrue(platform.steam_roots())

    def test_steam_launch_command(self):
        steam = write(self.tmp / "st" / "steam.exe", b"")
        self.assertEqual(platform.steam_launch_command(self.tmp / "st", ["+map", "x"]),
                         [str(steam), "-applaunch", "4000", "+map", "x"])
        reg = fake_winreg({"SteamExe": str(steam)})
        with mock.patch.dict(sys.modules, {"winreg": reg}):
            self.assertEqual(platform.steam_launch_command(None, [])[0], str(steam))
        with mock.patch.dict(sys.modules, {"winreg": fake_winreg({})}):
            self.assertIsNone(platform.steam_launch_command(self.tmp / "none", []))

    def test_library_folders_vdf_with_windows_paths(self):
        root = self.tmp / "steam"
        (root / "steamapps").mkdir(parents=True)
        lib2 = self.tmp / "lib2"
        (lib2 / "steamapps").mkdir(parents=True)
        # Steam writes backslashes doubled in libraryfolders.vdf
        esc = str(lib2).replace("\\", "\\\\")
        write(root / "steamapps/libraryfolders.vdf", '"libraryfolders"\n{\n\t"0"\n\t{\n\t\t"path"\t\t"%s"\n\t}\n}\n' % esc)
        self.assertEqual(detect.steam_libraries([root]), [root, lib2])

    def test_gmod_found_in_a_library(self):
        lib = self.tmp / "lib"
        write(lib / "steamapps/appmanifest_4000.acf", '"AppState"\n{\n\t"installdir"\t"GarrysMod"\n}\n')
        (lib / "steamapps/common/GarrysMod/garrysmod").mkdir(parents=True)
        (lib / "steamapps/common/GarrysMod/bin/win64").mkdir(parents=True)
        g = detect.find_gmod([lib])
        self.assertTrue(g.is64)


class ProcessTest(WinEnv):
    PROCS = [(4, "System"), (100, "gmod.exe"), (200, "PrismLauncher.exe"), (300, "javaw.exe"), (301, "java.exe")]

    def test_running_checks(self):
        with mock.patch.object(platform, "win_processes", return_value=self.PROCS):
            self.assertTrue(platform.process_running("GMOD.EXE"))
            self.assertTrue(platform.gmod_running())
            self.assertTrue(platform.prism_running())             # prismlauncher.exe, any case
            self.assertFalse(platform.process_running("steam.exe"))
        with mock.patch.object(platform, "win_processes", return_value=[(4, "System")]):
            self.assertFalse(platform.gmod_running())
            self.assertFalse(platform.prism_running())
        with mock.patch.object(platform, "win_processes", side_effect=OSError):
            self.assertIsNone(platform.gmod_running())

    def test_java_by_lock_or_path(self):
        jdir = platform.java_runtime_dir(platform.prism_data_default())
        images = {300: str(jdir / "bin" / "javaw.exe"), 301: r"C:\Other\bin\java.exe"}
        with mock.patch.object(platform, "win_processes", return_value=self.PROCS), \
                mock.patch.object(platform, "win_image_path", side_effect=lambda pid: images.get(pid, "")), \
                mock.patch.object(prewarm, "lock_holder", return_value=0):
            self.assertEqual(platform.win_game_java_pids(), [300])
            self.assertTrue(platform.java_with_arg_running(prism.JVM_ARG))
            images[300] = r"D:\elsewhere\javaw.exe"
            self.assertFalse(platform.java_with_arg_running(prism.JVM_ARG))
        with mock.patch.object(platform, "win_game_java_pids", return_value=[]), \
                mock.patch.object(prewarm, "lock_holder", return_value=1):
            self.assertTrue(platform.java_with_arg_running(prism.JVM_ARG))      # the lock file says Minecraft is up

    def test_spawn_detached_flags(self):
        with mock.patch.object(subprocess, "Popen") as popen:
            platform.spawn_detached(["prismlauncher.exe", "--launch", "GmodCraft"])
        kw = popen.call_args.kwargs
        self.assertEqual(kw["creationflags"], 0x00000008 | 0x00000200)   # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        self.assertNotIn("start_new_session", kw)

    def test_open_path_uses_startfile(self):
        with mock.patch.object(os, "startfile", create=True) as sf, mock.patch.object(subprocess, "Popen") as popen:
            platform.open_path(self.tmp / "console.log")
        sf.assert_called_once_with(str(self.tmp / "console.log"))
        popen.assert_not_called()

    def test_kill_process(self):
        with mock.patch.object(platform, "win_terminate") as term, mock.patch.object(subprocess, "run") as run:
            run.return_value = mock.Mock(returncode=0)
            platform.kill_process(5, platform.TERM)
            self.assertEqual(run.call_args.args[0], ["taskkill", "/PID", "5"])
            term.assert_not_called()
            platform.kill_process(5, platform.KILL)
            term.assert_called_once_with(5)
            run.return_value = mock.Mock(returncode=128)
            with self.assertRaises(ProcessLookupError):
                platform.kill_process(6, platform.TERM)

    def test_dpi_awareness_never_raises(self):
        with mock.patch.dict(sys.modules, {"ctypes": mock.Mock(windll=mock.Mock())}):
            self.assertTrue(platform.set_dpi_aware())
        with mock.patch.dict(sys.modules, {"ctypes": None}):
            self.assertFalse(platform.set_dpi_aware())


class QuitWindowsTest(WinEnv):
    def test_find(self):
        procs = [(100, "gmod.exe"), (101, "gmod.exe"), (300, "javaw.exe")]
        images = {100: r"C:\Steam\steamapps\common\GarrysMod\bin\win64\gmod.exe", 101: r"C:\Other\gmod.exe"}
        with mock.patch.object(platform, "win_processes", return_value=procs), \
                mock.patch.object(platform, "win_image_path", side_effect=lambda p: images.get(p, "")), \
                mock.patch.object(platform, "win_game_java_pids", return_value=[300]), \
                mock.patch.object(platform, "win_start_time", side_effect=lambda p: p * 10):
            g = q.find()
        self.assertEqual((g.gmod, g.mc, g.starts), ([100], [300], {100: 1000, 300: 3000}))

    def test_marker_dir_check_on_windows(self):
        d = self.tmp / "run"
        d.mkdir()
        self.assertTrue(q.private_dir(d))
        self.assertTrue(q.write_marker(100, d))
        os.symlink(d, self.tmp / "link")
        self.assertFalse(q.private_dir(self.tmp / "link"))
        self.assertFalse(q.private_dir(self.tmp / "missing"))

    def run_quit(self, w, **kw):
        log = []
        st = q.quit_game(log.append, finder=w.finder, kill=w.kill, sleep=w.sleep, now=w.now,
                         still_same=lambda pid, s: True, **kw)
        return st, log

    def test_gentle_marker_then_taskkill_then_terminate(self):
        mdir = self.tmp / "run"
        mdir.mkdir()
        w = FakeWorld(gmod=[100], mc=[200])
        real = w.finder

        def finder():                         # the module consumes the marker and GMod quits
            if (mdir / "quit-100").exists():
                w.gmod_left = 1
            return real()
        w.finder = finder
        st, _ = self.run_quit(w, marker_dir=mdir)
        self.assertEqual((st.phase, w.signals), ("done", []))             # no signal: the marker was enough
        w = FakeWorld(gmod=[100], mc=[200], gmod_ignores_term=True)       # stuck GMod: TERM (taskkill), then KILL
        st, _ = self.run_quit(w, marker_dir=mdir, force=True)
        self.assertEqual(w.signals[:2], [(100, platform.TERM), (100, platform.KILL)])
        w = FakeWorld(gmod=[100], mc=[200], gmod_ignores_term=True)
        st, _ = self.run_quit(w, marker_dir=mdir)
        self.assertEqual(st.phase, "stuck")                               # no force: never TerminateProcess
        self.assertNotIn((100, platform.KILL), w.signals)


class PrewarmWindowsTest(WinEnv):
    def test_lock_holder(self):
        lock = write(self.tmp / "run" / "minecraft-client.lock", b"")
        with mock.patch.object(prewarm, "_win_lock_held", return_value=True):
            self.assertEqual(prewarm.lock_holder(lock), 1)
        with mock.patch.object(prewarm, "_win_lock_held", return_value=False):
            self.assertEqual(prewarm.lock_holder(lock), 0)
        with mock.patch.object(prewarm, "_win_lock_held", return_value=None):
            self.assertIsNone(prewarm.lock_holder(lock))
        self.assertEqual(prewarm.lock_holder(self.tmp / "none.lock"), 0)
        with mock.patch.object(prewarm, "_win_lock_held", return_value=True):
            self.assertEqual(prewarm.running_reason(lock), "Minecraft is already running")

    def test_start_spawns_prism_exe_detached(self):
        exe = write(self.tmp / "p" / "prismlauncher.exe", b"MZ")
        cfg = {"prismAppImage": str(exe), "prewarm": True}
        log = []
        with mock.patch.object(prewarm, "lock_holder", return_value=0), \
                mock.patch.object(platform, "spawn_detached") as sp, \
                mock.patch("shutil.which", return_value="/usr/bin/systemd-run"):
            self.assertTrue(prewarm.start(cfg, log.append))
        sp.assert_called_once_with([str(exe), "--launch", "GmodCraft"])      # never systemd-run


class PrismWindowsTest(WinEnv):
    def test_candidates_and_kind(self):
        exe = write(self.tmp / "local/Programs/PrismLauncher/prismlauncher.exe", b"MZ")
        with mock.patch("shutil.which", return_value=None):
            c = platform.prism_exe_candidates()
        self.assertEqual(c[0], exe)
        self.assertIn(self.tmp / "pf" / "PrismLauncher" / "prismlauncher.exe", c)
        with mock.patch.object(platform, "prism_exe_candidates", return_value=[exe]):
            inst = detect.find_prism({})
        self.assertEqual((inst.kind, inst.command, inst.exe), ("exe", [str(exe)], exe))
        self.assertEqual(inst.data, self.tmp / "roaming" / "PrismLauncher")           # installer: %APPDATA%
        self.assertIn("Windows exe", inst.describe())

    def test_portable_prism_keeps_data_beside_exe(self):
        exe = write(self.tmp / "portable/prismlauncher.exe", b"MZ")
        write(self.tmp / "portable/portable.txt", "")
        inst = detect.find_prism({"prismAppImage": str(exe)})
        self.assertEqual((inst.kind, inst.data), ("exe", exe.parent))
        self.assertEqual(detect.prism_command({"prismAppImage": str(exe)}), [str(exe)])
        self.assertIsNone(detect.find_prism({"prismKind": "appimage"}))

    def test_gl_option_ignored_and_java_path_forward_slashes(self):
        seen = {}

        def fake_cfg(path, name, java, envs=(), unset=()):
            seen.update(envs=list(envs), unset=list(unset), java=prism._java_path_text(java))
        pdata = self.tmp / "pd"
        (pdata / "instances").mkdir(parents=True)
        jar = write(self.tmp / "gmodcraft-1.jar", b"jar")
        dl = write(self.tmp / "dl.jar", b"x")
        with mock.patch.object(prism, "update_instance_cfg", fake_cfg), \
                mock.patch.object(prism, "_cached_download", return_value=dl), \
                mock.patch.object(prism, "java_major", return_value=25):
            for gl in (True, False):
                seen.clear()
                prism.setup_instance(pdata, jar, lambda m: None, java=self.tmp / "jdk" / "bin" / "java.exe", gl=gl,
                                     gradle_cache=self.tmp / "nogradle", cache_dir=self.tmp / "c", sleep=lambda s: None)
                self.assertEqual((seen["envs"], seen["unset"]), ([], []), gl)      # SDL_VIDEO_FORCE_EGL never touched
        self.assertEqual(prism._java_path_text(Path("C:/x/bin/java.exe")), "C:/x/bin/java.exe")

    def test_temurin_pin_for_amd64(self):
        with mock.patch("platform.machine", return_value="AMD64"):
            pin = platform.temurin_pin()
        self.assertIsNotNone(pin)
        self.assertTrue(pin["url"].endswith(".zip") and "windows_hotspot" in pin["url"])
        self.assertEqual(len(pin["sha256"]), 64)
        import json
        with open(Path(__file__).resolve().parents[1] / "pins.json") as f:
            lin = json.load(f)["temurin"]
        self.assertEqual(lin["windows-x86_64"]["version"], lin["linux-x86_64"]["version"])
        with mock.patch("platform.machine", return_value="ARM64"):
            self.assertIsNone(platform.temurin_pin())

    def zip_bytes(self, entries):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            for name, data in entries.items():
                zf.writestr(name, data)
        return buf.getvalue()

    def test_temurin_zip_unpacked_and_checked(self):
        blob = self.zip_bytes({"jdk-25.0.4.1+1-jre/bin/java.exe": b"MZ", "jdk-25.0.4.1+1-jre/release": "x"})
        pin = {"version": "t", "url": "https://example.invalid/jre.zip", "sha256": hashlib.sha256(blob).hexdigest()}
        fetch = lambda url, dest: Path(dest).write_bytes(blob)       # noqa: E731
        jdir = self.tmp / "prism" / "java" / "gmodcraft-temurin-25"
        with mock.patch.object(platform, "temurin_pin", return_value=pin):
            prism._install_temurin(jdir, self.tmp, fetch, lambda m: None)
            self.assertTrue((jdir / "bin" / "java.exe").is_file())
            bad = dict(pin, sha256="0" * 64)
        with mock.patch.object(platform, "temurin_pin", return_value=bad), self.assertRaisesRegex(prism.PrismError, "sha256"):
            prism._install_temurin(self.tmp / "other", self.tmp, fetch, lambda m: None)

    def test_zip_slip_refused(self):
        for name in ("../evil.txt", "/abs.txt", "C:/win.txt", "a/../../b"):
            blob = self.zip_bytes({name: "x"})
            z = write(self.tmp / "bad.zip", blob)
            dest = self.tmp / "out"
            dest.mkdir(exist_ok=True)
            with self.assertRaises(prism.PrismError, msg=name):
                prism._extract_zip(z, dest)
        self.assertFalse((self.tmp / "evil.txt").exists())


class DesktopWindowsTest(WinEnv):
    def test_no_menu_entry_on_windows(self):
        with self.assertRaisesRegex(desktop.DesktopError, "Pin to Start"):
            desktop.install(lambda m: None)


class ProgressWindowsTest(WinEnv):
    def test_discovery_and_link_read_without_pread(self):
        import json
        seg = self.tmp / "run" / "gmodcraft-client-1"
        hdr = progress.HEADER.pack(progress.MAGIC, 1, progress.LINK_CLIENT, 0, 0x240, 7, 0, 0, 0, 0)
        write(seg, hdr + b"\0" * (0x240 - len(hdr)))
        write(self.tmp / "run" / "client.json", json.dumps({"shm": "gmodcraft-client-1"}))
        doc = progress.read_discovery(self.tmp / "run" / "client.json")
        self.assertEqual(doc["shm"], "gmodcraft-client-1")
        with mock.patch.object(os, "pread", None):
            info = progress.read_link(self.tmp / "run" / doc["shm"], expect_version=1)
        self.assertTrue(info.ok, info.error)
        self.assertEqual((info.nonce, info.in_world), (7, False))
