"""Garry's Modcraft launcher. Without a command it opens the window.

  gmodcraft-launcher.pyz [gui]
  gmodcraft-launcher.pyz status   [--bundle ZIP | --dev REPO]
  gmodcraft-launcher.pyz install  [--bundle ZIP | --dev REPO] [--dry-run] [--replace-foreign] [--no-prism] [--gl | --no-gl]
  gmodcraft-launcher.pyz uninstall [--dry-run] [--remove-instance [--yes]]
  gmodcraft-launcher.pyz install-desktop | uninstall-desktop [--dry-run]
  gmodcraft-launcher.pyz play [--server HOST:PORT [--password-file PATH | --ask-password]] [--dry-run]

play --server uses the password remembered for that server in the window (secrets.json, 0600) unless
--password-file (first line) or --ask-password (prompt) gives one. There is no --password: it would
show in ps and the shell history. The password reaches GMod through garrysmod/cfg/gmodcraft_connect.cfg
(+exec), which is removed on the next launcher start.

--bundle/--dev/--prism-exe are remembered in the config (~/.config/garrys-modcraft/config.json).
Overrides: GMOD_DIR, PRISM_DATA, GMODCRAFT_STEAM_ROOT, GMODCRAFT_CONFIG_DIR, GMODCRAFT_DATA_DIR.
"""
import argparse
import getpass
import sys

from . import __version__, actions, config, desktop, detect, passwords, platform


def _args(argv):
    ap = argparse.ArgumentParser(prog="gmodcraft-launcher", description="Garry's Modcraft launcher",
                                 allow_abbrev=False)  # "--password x" must not pass as --password-file
    ap.add_argument("--version", action="version", version=__version__)
    ap.add_argument("command", nargs="?", default="gui", choices=["gui", "status", "install", "uninstall", "play",
                                                            "install-desktop", "uninstall-desktop", "new-world"])
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--bundle", help="release bundle zip")
    src.add_argument("--dev", metavar="REPO", help="dev mode: a gmod-craft checkout with built outputs")
    ap.add_argument("--prism-exe", help="Prism Launcher AppImage / executable")
    ap.add_argument("--prism-kind", choices=["auto", "appimage", "package", "flatpak"],
                    help="which Prism: auto (default), the AppImage, the distro package (/usr/bin/prismlauncher) or the flatpak")
    ap.add_argument("--dry-run", action="store_true", help="print the planned file operations, change nothing")
    ap.add_argument("--replace-foreign", action="store_true",
                    help="back up and replace files the launcher didn't install (e.g. dev symlinks)")
    ap.add_argument("--no-prism", action="store_true", help="install: skip the Prism instance")
    gl = ap.add_mutually_exclusive_group()
    gl.add_argument("--gl", action="store_true",
                    help="add SDL_VIDEO_FORCE_EGL=1 to the instance Env (OpenGL under XWayland); remembered")
    gl.add_argument("--no-gl", action="store_true",
                    help="remove SDL_VIDEO_FORCE_EGL from the instance Env; remembered. Neither: Env untouched")
    ap.add_argument("--yes", action="store_true", help="uninstall --remove-instance: don't ask")
    ap.add_argument("--force", action="store_true",
                    help="install-desktop: also from a temp dir or a git worktree checkout")
    ap.add_argument("--server", help="play: join this server (host[:port])")
    pw = ap.add_mutually_exclusive_group()
    pw.add_argument("--password-file", metavar="PATH",
                    help="play --server: the server password is the first line of this file")
    pw.add_argument("--ask-password", action="store_true", help="play --server: ask for the server password")
    ap.add_argument("--world-type", choices=["mirror", "flat_void_maps", "flat_everywhere"], default="mirror",
                    help="new-world: the type of the single-player world made next (the old one is kept as a .bak)")
    ap.add_argument("--remove-instance", action="store_true",
                    help="uninstall: also delete the Prism instance GmodCraft, with its worlds")
    return ap.parse_args(argv)


def _password(a):
    if (a.password_file or a.ask_password) and not a.server:
        raise actions.ActionError("--password-file / --ask-password need --server")
    try:
        if a.password_file:
            return passwords.read_password_file(a.password_file)
        if a.ask_password:
            if not sys.stdin.isatty():
                raise actions.ActionError("--ask-password needs a terminal; use --password-file")
            return passwords.check(getpass.getpass(f"Password for {a.server}: "))
        return passwords.stored(a.server) if a.server else ""
    except passwords.PasswordError as e:
        raise actions.ActionError(str(e))
    except OSError as e:
        raise actions.ActionError(f"--password-file: {e}")


def main(argv=None):
    a = _args(sys.argv[1:] if argv is None else argv)
    if platform.running_as_root():
        print("don't run the launcher as root / with sudo", file=sys.stderr)
        return 2
    cfg = config.load()
    if a.bundle:
        cfg.update(source_kind="bundle", bundle=a.bundle)
    if a.dev:
        cfg.update(source_kind="dev", dev_repo=a.dev)
    if a.prism_exe:
        cfg["prismAppImage"] = a.prism_exe
    if a.prism_kind:
        cfg["prismKind"] = a.prism_kind
    if a.gl or a.no_gl:
        cfg["gl"] = bool(a.gl)
    if (a.bundle or a.dev or a.prism_exe or a.prism_kind or a.gl or a.no_gl) and not a.dry_run:
        config.save(cfg)

    # A connect cfg with a password left by an earlier launch: gone as soon as the launcher runs again.
    if not a.dry_run:
        try:
            actions.wipe_connect_cfg(print)
        except Exception as e:  # never keeps the launcher from starting
            print(f"can't check for an old connect cfg: {e}", file=sys.stderr)

    if a.command == "gui":
        from . import gui
        return gui.run(cfg)

    log = print
    try:
        if a.command == "status":
            src = actions.open_source(cfg, log)
            try:
                for c in detect.status(cfg, src):
                    mark = {True: "OK ", False: "NO ", None: " ? "}[c.ok]
                    print(f"[{mark}] {c.label}: {c.detail}")
            finally:
                actions.close_source(src)
        elif a.command == "install":
            actions.install(cfg, log, dry_run=a.dry_run, replace_foreign=a.replace_foreign, with_prism=not a.no_prism)
        elif a.command == "uninstall":
            if a.remove_instance and not a.dry_run and not a.yes:
                if not sys.stdin.isatty():
                    raise actions.ActionError("--remove-instance deletes the instance's worlds: add --yes to confirm")
                ans = input("Also delete the Prism instance GmodCraft with all its worlds? Type 'yes': ")
                if ans.strip().lower() != "yes":
                    print("cancelled")
                    return 1
            actions.uninstall(cfg, log, dry_run=a.dry_run, remove_instance=a.remove_instance)
        elif a.command == "install-desktop":
            try:
                desktop.install(log, dry_run=a.dry_run, force=a.force)
            except (desktop.DesktopError, OSError) as e:
                raise actions.ActionError(str(e))
        elif a.command == "uninstall-desktop":
            try:
                desktop.uninstall(log, dry_run=a.dry_run)
            except OSError as e:
                raise actions.ActionError(str(e))
        elif a.command == "new-world":
            if not a.dry_run and not a.yes:
                if not sys.stdin.isatty():
                    raise actions.ActionError("new-world starts the single-player world over: add --yes to confirm")
                ans = input(f"Start the single-player world over as {a.world_type}? The old one is kept as a .bak. Type 'yes': ")
                if ans.strip().lower() != "yes":
                    print("cancelled")
                    return 1
            actions.new_world(cfg, log, a.world_type, dry_run=a.dry_run)
        elif a.command == "play":
            actions.play(cfg, log, server=a.server, password=_password(a), dry_run=a.dry_run)
    except actions.ActionError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
