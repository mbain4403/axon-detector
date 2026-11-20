#!/usr/bin/env python3
"""Generate the launcher icons into source/res/mipmap-*.

The repo ships text-only source, so the PNG icons are generated, not
committed. Design: amber radar rings + center dot on dark navy.
Requires Pillow (pip install pillow). Run from anywhere:

    python3 generate_icons.py

Then build with source/build.sh.
"""
import os
from PIL import Image, ImageDraw

S = 512
img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([16, 16, S - 16, S - 16], radius=96, fill=(13, 21, 38, 255))
cx, cy = S // 2, S // 2
amber = (245, 166, 35, 255)
for r, col, w in [(196, (245, 166, 35, 80), 15),
                  (138, (245, 166, 35, 140), 16),
                  (80, amber, 17)]:
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=w)
d.ellipse([cx - 27, cy - 27, cx + 27, cy + 27], fill=amber)

ROOT = os.path.dirname(os.path.abspath(__file__))
for name, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96),
                   ('xxhdpi', 144), ('xxxhdpi', 192)]:
    dd = os.path.join(ROOT, 'source', 'res', 'mipmap-' + name)
    os.makedirs(dd, exist_ok=True)
    small = img.resize((size, size), Image.LANCZOS)
    small.save(os.path.join(dd, 'ic_launcher.png'))
    small.save(os.path.join(dd, 'ic_launcher_round.png'))
print('icons written under source/res/')
