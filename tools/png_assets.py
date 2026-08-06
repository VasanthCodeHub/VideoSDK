#!/usr/bin/env python3
"""Process icon PNGs: recolor black glyphs to white and emit density buckets.

Usage:
    python3 tools/png_assets.py <module_res_dir>

For every ic_*.png in <res_dir>/drawable:
  - recolors non-transparent pixels to white (alpha preserved)
  - resizes to density buckets (mdpi..xxxhdpi) at per-icon dp sizes
  - removes the source PNG from drawable/
"""

import os
import struct
import sys
import zlib

DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]

DP_SIZES = {
    "ic_groups": 20,
    "ic_flip_camera": 20,
    "ic_shield_lock": 18,
    "ic_push_pin": 14,
    "ic_account_circle": 52,
    "ic_copy": 52,
    "ic_share": 52,
    "ic_play": 52,
    "ic_videocam": 28,
    "ic_keyboard": 24,
    "ic_video_call": 24,
}
DEFAULT_DP = 24

FACTORS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def decode_png(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{path}: not a PNG"

    pos = 8
    width = height = -1
    chans = 0
    idat = []
    palette = None
    trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        ctype = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        if ctype == b"IHDR":
            width, height, bitdepth, colortype, _, _, interlace = struct.unpack(">IIBBBBB", body[:13])
            assert bitdepth == 8, f"{path}: bit depth {bitdepth} unsupported"
            assert colortype in (2, 3, 4, 6), f"{path}: colortype {colortype} unsupported"
            assert interlace == 0, f"{path}: interlaced unsupported"
            chans = {2: 3, 3: 1, 4: 2, 6: 4}[colortype]
        elif ctype == b"PLTE":
            palette = [(body[i], body[i + 1], body[i + 2]) for i in range(0, len(body), 3)]
        elif ctype == b"tRNS":
            trns = list(body)
        elif ctype == b"IDAT":
            idat.append(body)
        pos += 12 + length

    raw = zlib.decompress(b"".join(idat))
    stride = width * chans
    rows = []
    idx = 0
    prev = bytearray(stride)
    for _ in range(height):
        ftype = raw[idx]
        idx += 1
        line = bytearray(raw[idx : idx + stride])
        idx += stride
        if ftype == 1:
            for j in range(chans, stride):
                line[j] = (line[j] + line[j - chans]) & 0xFF
        elif ftype == 2:
            for j in range(stride):
                line[j] = (line[j] + prev[j]) & 0xFF
        elif ftype == 3:
            for j in range(stride):
                a = line[j - chans] if j >= chans else 0
                b = prev[j]
                line[j] = (line[j] + ((a + b) >> 1)) & 0xFF
        elif ftype == 4:
            for j in range(stride):
                a = line[j - chans] if j >= chans else 0
                b = prev[j]
                c = prev[j - chans] if j >= chans else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[j] = (line[j] + pr) & 0xFF
        prev = line
        if palette is not None:
            rgba = bytearray(width * 4)
            for j in range(width):
                idx = line[j]
                r, g, b = palette[idx]
                a = trns[idx] if (trns and idx < len(trns)) else 255
                rgba[j * 4 : j * 4 + 4] = bytes((r, g, b, a))
            line = rgba
            chans = 4
        rows.append(bytes(line))
    return width, height, rows, chans


def to_rgba(width, height, rows, chans):
    px = []
    for row in rows:
        if chans == 3:
            for j in range(0, len(row), 3):
                px.append((row[j], row[j + 1], row[j + 2], 255))
        elif chans == 2:
            for j in range(0, len(row), 2):
                px.append((row[j], row[j], row[j], row[j + 1]))
        else:
            for j in range(0, len(row), 4):
                px.append((row[j], row[j + 1], row[j + 2], row[j + 3]))
    return px


def recolor_white(px):
    return [(255, 255, 255, a) for _, _, _, a in px]


def area_resize(px, src_w, src_h, dst_w, dst_h):
    if dst_w == src_w and dst_h == src_h:
        return px
    out = []
    for y in range(dst_h):
        y0 = y * src_h // dst_h
        y1 = max(y0 + 1, ((y + 1) * src_h + dst_h - 1) // dst_h)
        for x in range(dst_w):
            x0 = x * src_w // dst_w
            x1 = max(x0 + 1, ((x + 1) * src_w + dst_w - 1) // dst_w)
            r = g = b = a = n = 0
            for yy in range(y0, y1):
                row = yy * src_w
                for xx in range(x0, x1):
                    pr, pg, pb, pa = px[row + xx]
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
                    n += 1
            if a == 0:
                out.append((255, 255, 255, 0))
            else:
                out.append((r // a, g // a, b // a, a // n))
    return out


def encode_png(path, width, height, rgba):
    def chunk(ctype, body):
        c = struct.pack(">I", len(body)) + ctype + body
        return c + struct.pack(">I", zlib.crc32(ctype + body) & 0xFFFFFFFF)

    raw = bytearray()
    flat = [v for t in rgba for v in t]
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(flat[y * stride : (y + 1) * stride])
    data = b"\x89PNG\r\n\x1a\n"
    data += chunk(
        b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    )
    data += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    data += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)


def dp_for(name):
    return DP_SIZES.get(name, DEFAULT_DP)


def main(res_dir):
    drawable = os.path.join(res_dir, "drawable")
    if not os.path.isdir(drawable):
        print(f"no drawable dir: {drawable}")
        return
    icons = sorted(f for f in os.listdir(drawable) if f.startswith("ic_") and f.endswith(".png"))
    if not icons:
        print(f"no ic_*.png found in {drawable}")
        return
    for icon in icons:
        path = os.path.join(drawable, icon)
        name = os.path.splitext(icon)[0]
        w, h, rows, chans = decode_png(path)
        rgba = recolor_white(to_rgba(w, h, rows, chans))
        dp = dp_for(name)
        for density in DENSITIES:
            dst = os.path.join(res_dir, "drawable-" + density)
            os.makedirs(dst, exist_ok=True)
            size = max(1, round(dp * FACTORS[density]))
            out = area_resize(rgba, w, h, size, size)
            enc = os.path.abspath(os.path.join(dst, icon))
            encode_png(enc, size, size, out)
        os.remove(path)
        print(f"ok  {icon}: {w}x{h}px -> 24dp-base, {DENSITIES} buckets")


if __name__ == "__main__":
    res_dir = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
    main(os.path.abspath(res_dir))