#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 mixin 里的 `UiTransitions.applyAlpha(...)` 调用改成拆分后的两个入口。

背景：`UiTransitions.applyAlpha(int)` 已按用途拆成
  applyAlphaBlit(int) —— 贴图块 / 纯色块
  applyAlphaText(int) —— 文字（可被 fadeText 单独关掉）

这个改写是**一次性**的历史步骤，现在的源码已经改好了。之所以还留着，
是为了让"想回滚"有据可依。因此：

  * 默认只做 dry-run，不碰任何文件；要真的写必须显式加 --apply；
  * 找不到任何可替换处时报告"无需改写"（源码已就绪），不会静默假装成功；
  * 写回前先校验新符号真的存在于 UiTransitions.java。

用法：
    python ui-transitions/apply_patch.py            # 只报告当前状态
    python ui-transitions/apply_patch.py --apply    # 真的改写
"""

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))          # <仓库>/ui-transitions
MIXIN = os.path.join(HERE, "src", "com", "uitransitions", "mixin")
CORE = os.path.join(HERE, "src", "com", "uitransitions", "UiTransitions.java")

REPLACEMENTS = {
    "BlitRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaBlit("),
    "TiledBlitRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaBlit("),
    "ColoredRectangleRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaBlit("),
    "GuiTextRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaText("),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="真的写回文件（默认只预演）")
    args = parser.parse_args()

    if not os.path.isdir(MIXIN):
        sys.exit("找不到 mixin 源码目录: %s" % MIXIN)
    if not os.path.isfile(CORE):
        sys.exit("找不到核心类: %s" % CORE)

    # 目标符号必须存在，否则改完也编译不过
    with open(CORE, encoding="utf-8") as fh:
        core = fh.read()
    for symbol in ("applyAlphaBlit", "applyAlphaText"):
        if symbol not in core:
            sys.exit("UiTransitions.java 里没有 %s —— 核心类接口变了，本脚本需要同步更新" % symbol)

    total = 0
    pending = []
    for name, (old, new) in REPLACEMENTS.items():
        path = os.path.join(MIXIN, name)
        if not os.path.isfile(path):
            sys.exit("源文件不存在: %s" % path)
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        old_count = text.count(old)
        new_count = text.count(new)
        total += old_count
        pending.append((name, path, text, old, new, old_count))
        print("%-40s 旧写法 %d 处 / 新写法 %d 处" % (name, old_count, new_count))

    if total == 0:
        print("\n无需改写：源码已经是拆分后的新写法。")
        return 0

    if not args.apply:
        print("\n预演模式：共 %d 处将被改写，加 --apply 才会写回。" % total)
        return 0

    for name, path, text, old, new, old_count in pending:
        if not old_count:
            continue
        # newline="" 保持原有 CRLF，避免整文件行尾变动
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(text.replace(old, new))
        print("%-40s 已改写 %d 处" % (name, old_count))
    print("\n完成，共 %d 处。" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
