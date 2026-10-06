#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
比较连拍帧：量出「某个区域相对稳定帧移动了多少像素」，用来判断画中画元素
（背包布娃娃 / 附魔书）到底有没有跟着界面一起动。

原理很朴素但有效：在一帧里把指定区域上下（或左右）平移，找出与稳定帧差异最小的位移。
* 底板区域量出来的是**界面本身的位移**；
* 布娃娃/附魔书区域量出来的是**它们自己的位移**。
两者接近 → 跟着界面一起动；接近 0 → 界面走了它们还留在原地（就是要抓的 bug）。

用法:
    python visualtest/compare_frames.py --burst build/visual-out/inv_open \
        --steady build/visual-out/inv_steady_00_t0407ms.png
    python visualtest/compare_frames.py --burst ... --steady ... --list-regions
"""

import argparse
import glob
import os
import sys

from PIL import Image

# 区域坐标基于 1280x720 截图（GUI scale 3 → 逻辑坐标 ×3）
REGIONS = {
    "inventory": {
        "panel": (620, 300, 900, 420),     # 背包底板中部（不放槽位的空白处）
        "preview": (100, 190, 340, 420),   # 玩家小模型所在位置
    },
    "enchant": {
        "panel": (620, 300, 900, 420),
        "preview": (560, 220, 760, 420),   # 附魔书所在位置
    },
}


def parse_box(text):
    return tuple(int(float(p)) for p in text.split(","))


def load_gray(path):
    return Image.open(path).convert("L")


def region_array(img, box):
    return img.crop(box)


def best_shift(frame_path, steady, box, axis="y", max_shift=260):
    """
    在 [-max_shift, max_shift] 里找出让 frame 的 box 区域最贴近 steady 的位移。
    返回 (位移, 残差)；位移为正表示内容在画面里**向下/向右**移动了。
    """
    steady_tile = region_array(steady, box)
    width, height = steady_tile.size
    frame = load_gray(frame_path)
    if frame.size != steady.size:
        return None

    best = (0, None)
    for shift in range(-max_shift, max_shift + 1, 1):
        if axis == "y":
            moved = (box[0], box[1] + shift, box[2], box[3] + shift)
        else:
            moved = (box[0] + shift, box[1], box[2] + shift, box[3])
        # 越界就跳过，避免把黑边当成匹配
        if moved[0] < 0 or moved[1] < 0 or moved[2] > frame.width or moved[3] > frame.height:
            continue
        tile = region_array(frame, moved)
        diff = 0
        fp = tile.tobytes()
        sp = steady_tile.tobytes()
        for i in range(0, len(sp), 7):          # 抽样，够用且快很多
            d = fp[i] - sp[i]
            diff += d if d >= 0 else -d
        if best[1] is None or diff < best[1]:
            best = (-shift, diff)                # 负号：内容下移了 shift，则要从更上方取样
    return best


def main():
    parser = argparse.ArgumentParser(description="量出画中画元素有没有跟着界面动")
    parser.add_argument("--burst", required=True, help="连拍文件前缀，例如 build/visual-out/inv_open")
    parser.add_argument("--steady", required=True, help="稳定帧（动画结束后）")
    parser.add_argument("--kind", choices=sorted(REGIONS), help="预设区域；不填则用 --preview/--panel 指定")
    parser.add_argument("--preview", type=parse_box, help="待考察元素的区域 x0,y0,x1,y1")
    parser.add_argument("--panel", type=parse_box, help="界面底板的区域 x0,y0,x1,y1")
    parser.add_argument("--axis", choices=["x", "y"], default="y")
    parser.add_argument("--list-regions", action="store_true")
    args = parser.parse_args()

    if args.list_regions:
        for kind, regions in REGIONS.items():
            print("%s:" % kind)
            for name, box in regions.items():
                print("  %-8s %s" % (name, box))
        return 0

    if args.kind:
        preview_box = REGIONS[args.kind]["preview"]
        panel_box = REGIONS[args.kind]["panel"]
    else:
        if not args.preview or not args.panel:
            sys.exit("需要 --kind，或者同时给 --preview 与 --panel")
        preview_box, panel_box = args.preview, args.panel

    if not os.path.isfile(args.steady):
        sys.exit("找不到稳定帧: %s" % args.steady)
    frames = sorted(glob.glob(args.burst + "*.png"))
    if not frames:
        sys.exit("找不到连拍帧: %s*.png" % args.burst)

    steady = load_gray(args.steady)
    print("稳定帧: %s" % os.path.basename(args.steady))
    print("连拍帧: %d 张" % len(frames))
    print("区域    底板=%s  待考察=%s  轴=%s" % (panel_box, preview_box, args.axis))
    print()
    print("  %-28s %10s %10s %s" % ("帧", "底板位移", "元素位移", "差值"))

    rows = []
    for path in frames:
        panel = best_shift(path, steady, panel_box, args.axis)
        preview = best_shift(path, steady, preview_box, args.axis)
        if panel is None or preview is None:
            print("  尺寸不一致，跳过 %s" % os.path.basename(path))
            continue
        delta = preview[0] - panel[0]
        rows.append((os.path.basename(path), panel[0], preview[0], delta))
        print("  %-28s %10d %10d %+d" % (os.path.basename(path), panel[0], preview[0], delta))

    if not rows:
        return 1

    # 判定：只看位移足够大的帧（界面确实动过），差值小就说明跟着一起动
    moving = [r for r in rows if abs(r[1]) >= 12]
    print()
    if not moving:
        print("结论：底板位移都很小，这段连拍没覆盖到动画过程，无法判定。")
        return 2
    worst = max(abs(r[3]) for r in moving)
    avg = sum(abs(r[3]) for r in moving) / len(moving)
    print("界面动过的帧: %d 张，底板位移范围 %d..%d"
          % (len(moving), min(r[1] for r in moving), max(r[1] for r in moving)))
    print("元素与底板的位移差：平均 %.1f 像素，最大 %d 像素" % (avg, worst))
    if worst <= 12:
        print("结论：元素跟着界面一起动 ✅")
        return 0
    if avg >= 30:
        print("结论：元素基本没动，界面自己走了 ❌（画中画没跟上）")
        return 3
    print("结论：元素动了但和界面不同步，需要人眼复核 ⚠️")
    return 4


if __name__ == "__main__":
    sys.exit(main())
