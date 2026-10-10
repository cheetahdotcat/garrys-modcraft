"""Loads launcher/pins.json: every pinned version, URL and hash (Fabric, fabric-api, JEI,
mezz_config, Temurin). In the zipapp the file is packed as gmodcraft_launcher/pins.json."""
import json
import pkgutil
from pathlib import Path


def _load():
    data = None
    try:
        data = pkgutil.get_data(__package__, "pins.json")   # inside the zipapp
    except OSError:
        pass
    if data is None:
        data = (Path(__file__).resolve().parent.parent / "pins.json").read_bytes()   # checkout
    return json.loads(data)


PINS = _load()
