import unittest
from unittest import mock

from helpers import TempEnv, write

from gmodcraft_launcher import detect, platform, vdf

NEW = r'''"libraryfolders"
{
	"0"
	{
		"path"		"/home/u/.local/share/Steam"
		"label"		""
		"apps"
		{
			"4000"		"7141835486"
		}
	}
	"1"
	{
		"path"		"/mnt/games/Steam Library"  // a comment
		"apps" { }
	}
}
'''
OLD = '''"LibraryFolders"
{
	"TimeNextStatsReport"		"1234"
	"ContentStatsID"		"-55"
	"1"		"D:\\\\Games\\\\Steam"
}
'''


class VdfTest(unittest.TestCase):
    def test_new_format(self):
        d = vdf.loads(NEW)
        self.assertEqual(vdf.library_paths(d), ["/home/u/.local/share/Steam", "/mnt/games/Steam Library"])
        self.assertEqual(d["libraryfolders"]["0"]["apps"]["4000"], "7141835486")
        self.assertEqual(d["libraryfolders"]["1"]["apps"], {})

    def test_old_format_and_escapes(self):
        self.assertEqual(vdf.library_paths(vdf.loads(OLD)), ["D:\\Games\\Steam"])

    def test_bare_tokens_conditionals_and_dupes(self):
        d = vdf.loads('root { key value [$WIN32] "q" "a\\"b" key second }')
        self.assertEqual(d, {"root": {"key": "second", "q": 'a"b'}})

    def test_errors(self):
        for bad in ('"a" {', '"a" "b" }', '"unterminated', '{ }'):
            with self.assertRaises(vdf.VdfError, msg=bad):
                vdf.loads(bad)


ACF = '''"AppState"
{
	"appid"		"4000"
	"installdir"		"GarrysMod"
	"UserConfig"
	{
		"language"		"english"
		"BetaKey"		"x86-64"
	}
}
'''


class DetectTest(TempEnv):
    def test_libraries_and_gmod(self):
        root = self.tmp / "steam"
        lib2 = self.tmp / "lib2"
        write(root / "steamapps/libraryfolders.vdf",
              f'"libraryfolders" {{ "0" {{ "path" "{root}" }} "1" {{ "path" "{lib2}" }} "2" {{ "path" "{self.tmp}/gone" }} }}')
        write(lib2 / "steamapps/appmanifest_4000.acf", ACF)
        (lib2 / "steamapps/common/GarrysMod/garrysmod").mkdir(parents=True)
        (lib2 / "steamapps/common/GarrysMod/bin/linux64").mkdir(parents=True)
        libs = detect.steam_libraries([root, self.tmp / "missing-root"])
        self.assertEqual(libs, [root, lib2])
        g = detect.find_gmod(libs)
        self.assertEqual(g.path, lib2 / "steamapps/common/GarrysMod")
        self.assertEqual(g.branch, "x86-64")
        self.assertTrue(g.is64)
        self.assertEqual(g.library, lib2)

    def test_gmod_dir_override(self):
        g = self.tmp / "G"
        (g / "garrysmod").mkdir(parents=True)
        with mock.patch.dict("os.environ", {"GMOD_DIR": str(g)}):
            info = detect.find_gmod([])
        self.assertEqual(info.path, g)
        self.assertFalse(info.is64)

    def test_status_without_anything(self):
        checks = detect.status({"prismAppImage": str(self.tmp / "nope")}, None, gmod=None)
        self.assertEqual(checks[0].label, "Garry's Mod")
        self.assertFalse(checks[0].ok)
        self.assertFalse(any(c.ok for c in checks))

    def test_steam_root_override(self):
        self.assertEqual(platform.steam_roots(), [self.tmp / "nosteam"])


if __name__ == "__main__":
    unittest.main()
