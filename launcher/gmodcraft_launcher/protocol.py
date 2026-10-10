"""The link protocol version this launcher's release speaks, derived from
protocol/gmodcraft_protocol.h kVersion (never pinned by hand):
  * zipapp: build_bundle.py writes it into gmodcraft_launcher/protocol.json;
  * checkout (dev): read from ../protocol/gmodcraft_protocol.h next to launcher/.
None when neither is there (then no server is judged a mismatch)."""
import json
import pkgutil
import re
from pathlib import Path

HEADER = Path(__file__).resolve().parents[2] / "protocol" / "gmodcraft_protocol.h"
_KVERSION = re.compile(r"\bkVersion\s*=\s*(\d+)\s*;")


def parse_kversion(text):
    m = _KVERSION.search(text)
    return int(m.group(1)) if m else None


def link_protocol():
    try:
        data = pkgutil.get_data(__package__, "protocol.json")     # inside the zipapp
    except OSError:
        data = None
    if data:
        try:
            v = json.loads(data).get("kVersion")
            if isinstance(v, int) and not isinstance(v, bool) and v > 0:
                return v
        except (ValueError, AttributeError):
            pass
    try:
        return parse_kversion(HEADER.read_text(encoding="utf-8"))
    except OSError:
        return None
