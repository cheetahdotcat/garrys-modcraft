"""File writes that never follow a symlink planted at the destination or at the temp path.

Every write goes to "<name>.gmodcraft-tmp" beside the destination: a leftover entry there (file or
symlink) is unlinked first, the temp file is created with O_CREAT|O_EXCL|O_NOFOLLOW (so a link that
appears in between makes the open fail instead of writing through it), and os.replace then swaps it
in, replacing a symlink at the destination itself rather than its target.
"""
import os
import shutil
from pathlib import Path

_FLAGS = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)


def tmp_path(dst):
    dst = Path(dst)
    return dst.with_name(dst.name + ".gmodcraft-tmp")


def open_new(path, mode=0o644):
    """A new file for binary writing; fails if anything (even a dangling symlink) is at path."""
    return os.fdopen(os.open(path, _FLAGS, mode), "wb")


def _write_via_tmp(dst, fill, check=None, mode=0o644):
    dst = Path(dst)
    tmp = tmp_path(dst)
    if os.path.lexists(tmp):
        os.unlink(tmp)
    try:
        with open_new(tmp, mode) as out:
            fill(out)
        if check is not None:
            check(tmp)
        os.replace(tmp, dst)
    except BaseException:
        if os.path.lexists(tmp) and not os.path.islink(tmp):
            os.unlink(tmp)
        raise


def copy_file(src, dst, check=None):
    """Copy src's bytes to dst. check(tmp) may raise to abort before dst is replaced."""
    def fill(out):
        with open(src, "rb") as inp:
            shutil.copyfileobj(inp, out)
    _write_via_tmp(dst, fill, check)


def write_bytes(dst, data, mode=0o644):
    """mode is set at creation (the umask can only take bits away): 0o600 for secrets."""
    _write_via_tmp(dst, lambda out: out.write(data), mode=mode)


def write_text(dst, text, mode=0o644):
    write_bytes(dst, text.encode("utf-8"), mode)


def remove(path):
    """Unlink path if anything is there (a symlink itself, never its target). True if removed."""
    if os.path.lexists(path):
        os.unlink(path)
        return True
    return False
