#!/usr/bin/env python3
"""
Generates the launcher icon PNG.

Portal's launcher renders icons at 192-280 dp and reads a **PNG from
mipmap-xxxhdpi/**. An app shipping only an adaptive/vector icon has no visible
home-screen icon at all — it installs, it runs via adb, and a human simply cannot
find it. That was true of this app until now.

Pure stdlib (zlib + struct) because Pillow is not installed and a launcher icon is
not worth a dependency. Committed as a script so the icon is reproducible rather
than an unexplained binary in the tree.

Usage:
    python3 tools/make_icon.py
"""

import math
import struct
import zlib
from pathlib import Path

SIZE = 512
OUT = Path("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")

BG = (0x15, 0x65, 0xC0)      # Material blue 800, matches the old vector
WHITE = (0xFF, 0xFF, 0xFF)
ACCENT = (0xBB, 0xDE, 0xFB)  # blue 100


def s(v: float) -> float:
    """Scale from the original 108dp viewport to SIZE."""
    return v * SIZE / 108.0


def in_rounded_rect(x, y, x0, y0, x1, y1, r):
    if not (x0 <= x <= x1 and y0 <= y <= y1):
        return False
    for cx, cy in ((x0 + r, y0 + r), (x1 - r, y0 + r), (x0 + r, y1 - r), (x1 - r, y1 - r)):
        # Only the corner quadrants need the radius test.
        if (x < x0 + r or x > x1 - r) and (y < y0 + r or y > y1 - r):
            if abs(x - cx) <= r and abs(y - cy) <= r:
                return math.hypot(x - cx, y - cy) <= r
    return True


def in_circle(x, y, cx, cy, r):
    return math.hypot(x - cx, y - cy) <= r


def in_triangle_strip(x, y, pts):
    """Even-odd fill for a small convex polygon."""
    inside = False
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        if (y0 > y) != (y1 > y):
            xint = (x1 - x0) * (y - y0) / (y1 - y0) + x0
            if x < xint:
                inside = not inside
    return inside


def main():
    body = (s(18), s(40), s(90), s(84), s(8))
    lens_c = (s(54), s(62))
    bump = [(s(38), s(40)), (s(45), s(30)), (s(63), s(30)), (s(70), s(40))]

    rows = []
    for py in range(SIZE):
        row = bytearray()
        row.append(0)  # PNG filter type 0 for this scanline
        y = py + 0.5
        for px in range(SIZE):
            x = px + 0.5
            if in_circle(x, y, lens_c[0], lens_c[1], s(9)):
                c = WHITE                      # lens highlight
            elif in_circle(x, y, lens_c[0], lens_c[1], s(15)):
                c = BG                         # lens
            elif in_rounded_rect(x, y, *body):
                c = WHITE                      # camera body
            elif in_triangle_strip(x, y, bump):
                c = ACCENT                     # viewfinder bump
            else:
                c = BG
            row += bytes(c)
        rows.append(bytes(row))

    raw = b"".join(rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(png)
    print(f"wrote {OUT}  {SIZE}x{SIZE}  {len(png):,} bytes")


if __name__ == "__main__":
    main()
