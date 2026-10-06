#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把源图做成模组图标：边缘泛洪去白底 -> RGBA -> 128x128，并在 jar 根目录也放一份。

去白底只从四边泛洪，画面内部的白色（槽位高光等）不受影响。

用法：
    python ui-transitions/make_icon.py [源图路径]

源图默认取附件缓存里的那张；换图时把新图路径当参数传进来即可。
"""

import argparse
import os
import sys
from collections import deque

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))          # <仓库>/ui-transitions
RESOURCES = os.path.join(HERE, "resources")
# jar 根目录放一份，兼容只扫根目录的启动器；assets/ 下那份是 fabric.mod.json 声明的
DST_ROOT = os.path.join(RESOURCES, "icon.png")
DST = os.path.join(RESOURCES, "assets", "ui_transitions", "icon.png")

DEFAULT_SRC = (r"C:\Users\DesKtop01\.dsh\attachments\v1\objects\d0"
               r"\d08c3037daa80226b0d82ff5332747cd4d862444edcb29dc557800130df21708")

TOLERANCE = 16          # 与白色的差距小于它就当作背景
SIDE = 128
MARGIN = 0.05           # 裁剪后留 5% 边距


def flood_clear_background(im):
    """从四边泛洪，把连通的近白像素标成透明"""
    w, h = im.size
    px = im.load()
    is_bg = bytearray(w * h)
    queue = deque()
    for x in range(w):
        for y in (0, h - 1):
            queue.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            queue.append((x, y))

    while queue:
        x, y = queue.popleft()
        idx = y * w + x
        if is_bg[idx]:
            continue
        r, g, b, _ = px[x, y]
        if r < 255 - TOLERANCE or g < 255 - TOLERANCE or b < 255 - TOLERANCE:
            continue
        is_bg[idx] = 1
        if x > 0:
            queue.append((x - 1, y))
        if x < w - 1:
            queue.append((x + 1, y))
        if y > 0:
            queue.append((x, y - 1))
        if y < h - 1:
            queue.append((x, y + 1))

    for y in range(h):
        for x in range(w):
            if is_bg[y * w + x]:
                px[x, y] = (255, 255, 255, 0)
    return im


def crop_and_square(im):
    """裁掉透明外边并按最长边补成正方形（带边距）"""
    w, h = im.size
    bbox = im.getbbox()
    print("  内容范围:", bbox)
    if not bbox:
        return im
    side = max(bbox[2] - bbox[0], bbox[3] - bbox[1])
    pad = int(side * MARGIN)
    box = (max(0, bbox[0] - pad), max(0, bbox[1] - pad),
           min(w, bbox[2] + pad), min(h, bbox[3] + pad))
    im = im.crop(box)
    side = max(im.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    return canvas


def export(im, path):
    """optimize=False：避免被转成调色板 PNG，保证就是 8bit RGBA"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, "PNG", optimize=False)
    print("  输出:", path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("src", nargs="?", default=os.environ.get("UITRANSITIONS_ICON_SRC", DEFAULT_SRC),
                        help="源图路径")
    args = parser.parse_args()

    if not os.path.isfile(args.src):
        sys.exit("找不到源图：%s\n（换图时把新图路径作为参数传进来）" % args.src)

    im = Image.open(args.src).convert("RGBA")
    im = flood_clear_background(im)
    im = crop_and_square(im)
    out = im.resize((SIDE, SIDE), Image.LANCZOS)
    export(out, DST)
    export(out, DST_ROOT)
    print("  模式:", out.mode, out.size)
    if out.mode != "RGBA":
        sys.exit("输出不是 RGBA（%s），启动器可能显示异常" % out.mode)
    return 0


if __name__ == "__main__":
    sys.exit(main())
