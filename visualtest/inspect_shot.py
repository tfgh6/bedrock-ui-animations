#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
放大看截图里的某一块 —— 实机验证时用来判断"某个元素的边界到底在哪"。

用法:
    python visualtest/inspect_shot.py <截图> --crop x0,y0,x1,y1 [--zoom 2] [--out 目标.png]
    python visualtest/inspect_shot.py <截图> --list          # 看图片信息
    python visualtest/inspect_shot.py <截图> --edges row     # 扫出某一行上的横向边界

--crop 的坐标是**原图像素**。加 --grid 会在裁剪图上叠一层 50px 的坐标网格，
方便直接量出元素位置。
"""

import argparse
import os
import sys

from PIL import Image, ImageDraw

ZOOM_MAX = 8


def parse_crop(text):
    parts = [p.strip() for p in text.replace("，", ",").split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("--crop 需要四个数：x0,y0,x1,y1")
    return tuple(int(float(p)) for p in parts)


def show_info(img, path):
    print("文件: %s" % path)
    print("  尺寸: %dx%d  模式: %s" % (img.width, img.height, img.mode))
    colors = img.convert("RGB").getcolors(maxcolors=1 << 24)
    if colors:
        colors.sort(reverse=True)
        print("  主要颜色（前 6）:")
        for count, rgb in colors[:6]:
            print("    #%02X%02X%02X  %6d 像素 (%.1f%%)"
                  % (rgb[0], rgb[1], rgb[2], count, 100.0 * count / (img.width * img.height)))


def scan_edges(img, axis, index, threshold=24):
    """
    沿一行（或一列）扫出颜色发生明显变化的坐标 —— 用来精确定位矩形边界。
    axis=row 时 index 是 y；axis=col 时 index 是 x。
    """
    rgb = img.convert("RGB")
    if axis == "row":
        line = [rgb.getpixel((x, index)) for x in range(rgb.width)]
        label, pos = "水平扫描 y=%d" % index, lambda i: i
    else:
        line = [rgb.getpixel((index, y)) for y in range(rgb.height)]
        label, pos = "垂直扫描 x=%d" % index, lambda i: i
    print("%s（阈值 %d）:" % (label, threshold))
    edges = []
    for i in range(1, len(line)):
        a, b = line[i - 1], line[i]
        if sum(abs(a[c] - b[c]) for c in range(3)) >= threshold:
            edges.append(i)
    # 相邻的算同一处边界
    merged = []
    for e in edges:
        if not merged or e - merged[-1] > 2:
            merged.append(e)
    print("  边界坐标: %s" % (merged if merged else "（没有明显变化）"))
    for e in merged:
        print("    %4d  ->  #%02X%02X%02X" % (pos(e), *line[min(e, len(line) - 1)]))


def main():
    parser = argparse.ArgumentParser(description="放大/测量实机截图")
    parser.add_argument("image")
    parser.add_argument("--crop", type=parse_crop, help="x0,y0,x1,y1（原图像素）")
    parser.add_argument("--zoom", type=int, default=2, help="放大倍数，默认 2")
    parser.add_argument("--out", help="输出路径；默认在原图旁边生成 *_zoom.png")
    parser.add_argument("--list", action="store_true", help="只打印图片信息")
    parser.add_argument("--edges", choices=["row", "col"], help="扫边界")
    parser.add_argument("--at", type=int, default=0, help="--edges 时的扫描位置")
    parser.add_argument("--grid", type=int, default=0, help="叠网格的间距（像素），0 = 不叠")
    args = parser.parse_args()

    if not os.path.isfile(args.image):
        sys.exit("找不到文件: %s" % args.image)
    img = Image.open(args.image)
    show_info(img, args.image)

    if args.list:
        return 0

    if args.edges:
        scan_edges(img, args.edges, args.at)
        return 0

    if not args.crop:
        print("\n提示：加 --crop x0,y0,x1,y1 可以放大某一块；加 --edges row --at 400 可以扫边界")
        return 0

    x0, y0, x1, y1 = args.crop
    x0, x1 = sorted((max(0, x0), min(img.width, x1)))
    y0, y1 = sorted((max(0, y0), min(img.height, y1)))
    if x1 <= x0 or y1 <= y0:
        sys.exit("裁剪区域为空")

    tile = img.crop((x0, y0, x1, y1)).convert("RGB")
    zoom = max(1, min(ZOOM_MAX, args.zoom))
    tile = tile.resize((tile.width * zoom, tile.height * zoom), Image.NEAREST)

    if args.grid > 0:
        draw = ImageDraw.Draw(tile)
        step = args.grid * zoom
        for gx in range(0, tile.width, step):
            draw.line([(gx, 0), (gx, tile.height)], fill=(255, 0, 255), width=1)
            draw.text((gx + 2, 2), str(x0 + gx // zoom), fill=(255, 0, 255))
        for gy in range(0, tile.height, step):
            draw.line([(0, gy), (tile.width, gy)], fill=(255, 0, 255), width=1)
            draw.text((2, gy + 2), str(y0 + gy // zoom), fill=(255, 0, 255))

    out = args.out
    if not out:
        base, _ = os.path.splitext(args.image)
        out = "%s_crop_%d_%d.png" % (base, x0, y0)
    tile.save(out)
    print("\n裁剪 (%d,%d)-(%d,%d)  放大 %dx  -> %s" % (x0, y0, x1, y1, zoom, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
