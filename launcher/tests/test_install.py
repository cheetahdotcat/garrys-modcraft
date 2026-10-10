import json
import os
import unittest
from unittest import mock

from helpers import TempEnv, make_gmod, make_repo, write

from gmodcraft_launcher import bundle, gmod_install as gi, platform


def logger(lines):
    return lambda m: lines.append(str(m))


class InstallTest(TempEnv):
    def setUp(self):
        super().setUp()
        self.repo = make_repo(self.tmp / "repo")
        self.gmod = make_gmod(self.tmp)
        self.gm = self.gmod / "garrysmod"
        self.log = []

    def install(self, **kw):
        src = bundle.open_checkout(self.repo)
        plan = gi.plan_install(src, self.gmod, **kw)
        gi.execute(plan, logger(self.log))
        return src

    def test_fresh_install_and_status(self):
        src = self.install()
        dll = self.gm / "lua/bin/gmcl_gmodcraft_linux64.dll"
        self.assertTrue(dll.is_file() and not dll.is_symlink())
        self.assertEqual(dll.read_bytes(), (self.repo / "module/build/gmcl_gmodcraft_linux64.dll").read_bytes())
        self.assertTrue((self.gm / "addons/gmodcraft/lua/autorun/gmodcraft_init.lua").is_file())
        st = gi.load_state(self.gmod)
        self.assertEqual(len(st["files"]), 5)
        self.assertIn("lua/bin", st["dirs"])          # created by us (fake tree had none)
        self.assertIn("addons/gmodcraft", st["dirs"])
        self.assertEqual(st["module_version"], "0.3.0-p3a")
        (m_ok, _), (a_ok, _) = gi.check(self.gmod, src)
        self.assertTrue(m_ok and a_ok)
        # second run: nothing to do
        plan = gi.plan_install(bundle.open_checkout(self.repo), self.gmod)
        self.assertEqual(plan.ops, [])

    def test_update_replaces_adds_removes_and_backs_up_edits(self):
        self.install()
        edited = self.gm / "addons/gmodcraft/README.md"
        edited.write_text("my notes\n")                               # user edit on an owned file
        (self.gm / "addons/gmodcraft/lua/mine.lua").write_text("x")     # user file, never ours
        os.remove(self.repo / "addon/gmodcraft/lua/gmodcraft/client/link.lua")
        write(self.repo / "addon/gmodcraft/lua/gmodcraft/client/new.lua", "-- new\n")
        write(self.repo / "module/build/gmcl_gmodcraft_linux64.dll", b"\x000.4.0-p6\x00 v2")
        src = bundle.open_checkout(self.repo)
        self.assertEqual(gi.check(self.gmod, src)[0][0], False)       # modules need an update
        self.install()
        self.assertFalse((self.gm / "addons/gmodcraft/lua/gmodcraft/client/link.lua").exists())
        self.assertTrue((self.gm / "addons/gmodcraft/lua/gmodcraft/client/new.lua").exists())
        self.assertEqual(edited.read_text(), "# addon\n")
        backups = list((self.tmp / "data/backups").rglob("README.md"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(), "my notes\n")
        self.assertTrue((self.gm / "addons/gmodcraft/lua/mine.lua").exists())
        self.assertEqual(gi.load_state(self.gmod)["module_version"], "0.4.0-p6")

    def test_refuses_foreign_files(self):
        write(self.gm / "lua/bin/gmsv_gmodcraft_linux64.dll", b"someone else's")
        src = bundle.open_checkout(self.repo)
        plan = gi.plan_install(src, self.gmod)
        self.assertTrue(plan.errors)
        with self.assertRaises(gi.InstallError):
            gi.execute(plan, logger(self.log))
        self.assertEqual((self.gm / "lua/bin/gmsv_gmodcraft_linux64.dll").read_bytes(), b"someone else's")
        self.assertFalse((self.gm / "addons/gmodcraft").exists())

    def test_dev_symlinks_refused_then_replaced_with_backup(self):
        (self.gm / "lua/bin").mkdir()
        os.symlink(self.repo / "addon/gmodcraft", self.gm / "addons/gmodcraft")
        os.symlink(self.repo / "module/build/gmcl_gmodcraft_linux64.dll", self.gm / "lua/bin/gmcl_gmodcraft_linux64.dll")
        os.symlink(self.repo / "module/build/gmsv_gmodcraft_linux64.dll", self.gm / "lua/bin/gmsv_gmodcraft_linux64.dll")
        src = bundle.open_checkout(self.repo)
        (m_ok, m_d), (a_ok, _) = gi.check(self.gmod, src)
        self.assertIsNone(m_ok, m_d)
        self.assertIsNone(a_ok)
        self.assertTrue(gi.plan_install(src, self.gmod).errors)
        self.install(replace_foreign=True)
        root = self.gm / "addons/gmodcraft"
        self.assertTrue(root.is_dir() and not root.is_symlink())
        self.assertFalse((self.gm / "lua/bin/gmcl_gmodcraft_linux64.dll").is_symlink())
        self.assertTrue((self.repo / "addon/gmodcraft/README.md").is_file())   # the checkout is untouched
        links = [p for p in (self.tmp / "data/backups").rglob("*") if p.is_symlink()]
        self.assertEqual(len(links), 3)
        self.assertNotIn("lua/bin", gi.load_state(self.gmod)["dirs"])   # existed before: not ours

    def test_uninstall_removes_only_owned(self):
        self.install()
        (self.gm / "addons/gmodcraft/lua/mine.lua").write_text("x")
        write(self.gm / "lua/bin/gmsv_other_linux64.dll", b"other module")
        plan = gi.plan_uninstall(self.gmod)
        gi.execute(plan, logger(self.log))
        self.assertFalse((self.gm / "lua/bin/gmcl_gmodcraft_linux64.dll").exists())
        self.assertTrue((self.gm / "lua/bin/gmsv_other_linux64.dll").exists())
        self.assertTrue((self.gm / "addons/gmodcraft/lua/mine.lua").exists())
        self.assertFalse((self.gm / "addons/gmodcraft/lua/autorun").exists())
        self.assertEqual(gi.load_state(self.gmod)["files"], {})
        # the folders it kept are forgotten; a second uninstall has nothing to do
        self.assertEqual(gi.plan_uninstall(self.gmod).ops, [])

    def test_uninstall_backs_up_edited_and_clears_state(self):
        self.install()
        (self.gm / "addons/gmodcraft/README.md").write_text("edited")
        gi.execute(gi.plan_uninstall(self.gmod), logger(self.log))
        self.assertFalse((self.gm / "addons").joinpath("gmodcraft").exists())
        self.assertFalse((self.gm / "lua/bin").exists())
        self.assertEqual([p.read_text() for p in (self.tmp / "data/backups").rglob("README.md")], ["edited"])
        data = json.loads((self.tmp / "cfg/installed.json").read_text())
        self.assertEqual(data["installs"], {})

    def test_never_deletes_through_a_later_dev_symlink(self):
        self.install()
        # the dev later swapped the installed addon for a symlink into the checkout (identical files)
        import shutil
        shutil.rmtree(self.gm / "addons/gmodcraft")
        os.symlink(self.repo / "addon/gmodcraft", self.gm / "addons/gmodcraft")
        gi.execute(gi.plan_uninstall(self.gmod), logger(self.log))
        self.assertTrue((self.gm / "addons/gmodcraft").is_symlink())
        self.assertEqual(sorted(p.name for p in (self.repo / "addon/gmodcraft").rglob("*") if p.is_file()),
                         ["README.md", "gmodcraft_init.lua", "link.lua"])
        self.assertFalse((self.gm / "lua/bin/gmcl_gmodcraft_linux64.dll").exists())
        self.assertEqual(gi.load_state(self.gmod)["files"], {})

    def _snapshot(self, d):
        return {str(p.relative_to(d)): (p.read_bytes() if p.is_file() else None) for p in sorted(d.rglob("*"))}

    def test_update_never_writes_through_a_symlinked_subfolder(self):
        self.install()
        import shutil
        outside = self.tmp / "outside-lua"
        shutil.copytree(self.gm / "addons/gmodcraft/lua", outside)
        shutil.rmtree(self.gm / "addons/gmodcraft/lua")
        os.symlink(outside, self.gm / "addons/gmodcraft/lua")
        before = self._snapshot(outside)
        write(self.repo / "addon/gmodcraft/lua/autorun/gmodcraft_init.lua", "-- v2\n")   # an update
        write(self.repo / "addon/gmodcraft/lua/gmodcraft/client/new.lua", "-- new\n")
        src = bundle.open_checkout(self.repo)
        plan = gi.plan_install(src, self.gmod)
        self.assertTrue(any("addons/gmodcraft/lua" in e and "symlinked" in e for e in plan.errors), plan.errors)
        with self.assertRaises(gi.InstallError):
            gi.execute(plan, logger(self.log))
        self.assertEqual(self._snapshot(outside), before)
        # replacing: the link itself goes to the backup, its target stays as it was
        self.install(replace_foreign=True)
        lua = self.gm / "addons/gmodcraft/lua"
        self.assertTrue(lua.is_dir() and not lua.is_symlink())
        self.assertEqual((lua / "autorun/gmodcraft_init.lua").read_text(), "-- v2\n")
        self.assertEqual(self._snapshot(outside), before)
        links = [p for p in (self.tmp / "data/backups").rglob("lua") if p.is_symlink()]
        self.assertEqual(len(links), 1)
        self.assertEqual(gi.check(self.gmod, src)[1][0], True)

    def test_install_never_writes_through_a_symlinked_lua_bin(self):
        outside = self.tmp / "outside-bin"
        outside.mkdir()
        os.symlink(outside, self.gm / "lua/bin")
        src = bundle.open_checkout(self.repo)
        plan = gi.plan_install(src, self.gmod)
        self.assertTrue(any("garrysmod/lua/bin" in e for e in plan.errors), plan.errors)
        with self.assertRaises(gi.InstallError):
            gi.execute(plan, logger(self.log))
        self.assertEqual(list(outside.iterdir()), [])
        self.install(replace_foreign=True)
        self.assertEqual(list(outside.iterdir()), [])
        b = self.gm / "lua/bin"
        self.assertTrue(b.is_dir() and not b.is_symlink())
        self.assertTrue((b / "gmcl_gmodcraft_linux64.dll").is_file())
        self.assertEqual([p.is_symlink() for p in (self.tmp / "data/backups").rglob("bin")], [True])

    def test_execute_rechecks_symlinks(self):
        (self.gm / "lua/bin").mkdir()
        src = bundle.open_checkout(self.repo)
        plan = gi.plan_install(src, self.gmod)
        outside = self.tmp / "late"
        outside.mkdir()
        (self.gm / "lua/bin").rmdir()
        os.symlink(outside, self.gm / "lua/bin")   # swapped for a link after planning
        with self.assertRaisesRegex(gi.InstallError, "symlink"):
            gi.execute(plan, logger(self.log))
        self.assertEqual(list(outside.iterdir()), [])

    def test_planted_tmp_link_is_not_followed(self):
        # probe F: a symlink waiting at the copy's temp path, pointing at an outside file
        (self.gm / "lua/bin").mkdir()
        victim = write(self.tmp / "victim.txt", b"precious")
        dll = self.gm / "lua/bin/gmcl_gmodcraft_linux64.dll"
        os.symlink(victim, dll.with_name(dll.name + ".gmodcraft-tmp"))
        self.install()
        self.assertEqual(victim.read_bytes(), b"precious")
        self.assertTrue(dll.is_file() and not dll.is_symlink())
        self.assertEqual(dll.read_bytes(), (self.repo / "module/build/gmcl_gmodcraft_linux64.dll").read_bytes())
        self.assertFalse(os.path.lexists(dll.with_name(dll.name + ".gmodcraft-tmp")))

    def test_safeio_refuses_a_link_raced_in(self):
        from gmodcraft_launcher import safeio
        victim = write(self.tmp / "victim.txt", b"precious")
        link = self.tmp / "x.gmodcraft-tmp"
        os.symlink(victim, link)
        with self.assertRaises(FileExistsError):
            safeio.open_new(link)
        self.assertEqual(victim.read_bytes(), b"precious")

    def test_linked_top_folders_always_refused(self):
        for top in ("addons", "lua"):
            with self.subTest(top=top):
                real = self.tmp / f"real-{top}"
                os.rename(self.gm / top, real) if (self.gm / top).exists() else real.mkdir()
                os.symlink(real, self.gm / top)
                src = bundle.open_checkout(self.repo)
                plan = gi.plan_install(src, self.gmod, replace_foreign=True)
                self.assertTrue(any(f"garrysmod/{top} is a symlink" in e for e in plan.errors), plan.errors)
                self.assertEqual(plan.ops, [])
                os.unlink(self.gm / top)
                os.rename(real, self.gm / top)

    def test_uninstall_logs_created_folder_turned_link(self):
        self.install()
        import shutil
        outside = self.tmp / "o"
        shutil.move(str(self.gm / "lua/bin"), str(outside))
        os.symlink(outside, self.gm / "lua/bin")
        gi.execute(gi.plan_uninstall(self.gmod), logger(self.log))
        self.assertTrue(any("lua/bin" in l and "symlink now" in l for l in self.log), self.log)
        self.assertEqual(len(list(outside.iterdir())), 2)   # nothing deleted through the link

    def test_backup_folders_are_unique(self):
        self.install()
        for _ in range(2):
            (self.gm / "addons/gmodcraft/README.md").write_text("edit")
            write(self.repo / "addon/gmodcraft/README.md", f"v{_}\n")
            self.install()
        self.assertEqual(len(list((self.tmp / "data/backups").iterdir())), 2)

    def test_dry_run_changes_nothing(self):
        before = sorted(str(p) for p in self.tmp.rglob("*"))
        src = bundle.open_checkout(self.repo)
        plan = gi.plan_install(src, self.gmod)
        gi.execute(plan, logger(self.log), dry_run=True)
        self.assertEqual(sorted(str(p) for p in self.tmp.rglob("*")), before)
        text = "\n".join(self.log)
        self.assertIn("copy", text)
        self.assertIn("garrysmod/lua/bin/gmcl_gmodcraft_linux64.dll", text)

    def test_refuses_while_gmod_runs(self):
        src = bundle.open_checkout(self.repo)
        with mock.patch.object(platform, "gmod_running", return_value=True):
            with self.assertRaises(gi.InstallError):
                gi.execute(gi.plan_install(src, self.gmod), logger(self.log))
        self.assertFalse((self.gm / "addons/gmodcraft").exists())


if __name__ == "__main__":
    unittest.main()
