"""The launcher's icon, generated (no image files in the package): a 16x16 pixel-art grass block
with an orange GMod-like "g" spark, scaled up. png(size) gives PNG bytes (window icon, desktop entry)."""
import struct
import zlib

GRASS = (0x5d, 0xa1, 0x3c)
GRASS_D = (0x46, 0x80, 0x2c)
DIRT = (0x86, 0x5d, 0x3a)
DIRT_D = (0x6b, 0x48, 0x2b)
ORANGE = (0xf0, 0x8a, 0x24)
WHITE = (0xf5, 0xf5, 0xf5)

# 16x16 art: g grass, G dark grass, d dirt, D dark dirt, o orange, w white, . transparent
ART = [
    "................",
    ".gggGgggggGggg..",
    ".gGgggggGgggggg.",
    ".ggggGgggggGggg.",
    ".dGdgdgGdgdGdgd.",
    ".dddDddddDddddd.",
    ".ddddddoooodddd.",
    ".dDdddoowwoodDd.",
    ".ddddoowddwoddd.",
    ".dddooowdddwddd.",
    ".dDdoooowwwoDdd.",
    ".ddddoooooowddd.",
    ".dddddoowwwoddd.",
    ".ddDddddooodDdd.",
    ".dddddDddddddd..",
    "................",
]
PALETTE = {"g": GRASS, "G": GRASS_D, "d": DIRT, "D": DIRT_D, "o": ORANGE, "w": WHITE}


def pixels(size=64):
    """size x size RGBA rows (size a multiple of 16)."""
    k = max(1, size // 16)
    rows = []
    for y in range(16 * k):
        row = bytearray()
        for x in range(16 * k):
            c = ART[y // k][x // k]
            row += bytes(PALETTE[c]) + b"\xff" if c in PALETTE else b"\x00\x00\x00\x00"
        rows.append(bytes(row))
    return rows


def png(size=64):
    rows = pixels(size)
    h, w = len(rows), len(rows[0]) // 4
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
