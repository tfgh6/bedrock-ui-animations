#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
拦一类很隐蔽、但一犯就整屏空白的错误：**共用的界面代码里引用了加载器专属的类**。

## 为什么需要它

同一个 jar 同时给 Fabric 和 NeoForge 用，而 `UiTransitionsHubScreen` 这类界面类
**两个加载器都会加载**。一旦里面直接写了 `net.fabricmc.loader.api.FabricLoader`：

  · Fabric 上一切正常，所以本地测不出来；
  · NeoForge 上这个类不存在，`init()` 一执行就抛 NoClassDefFoundError，
    控件一个都没加上 —— 表现成"配置界面打开后空空如也"。

这个 bug 真实发生过（用户反馈"NeoForge 里配置打开之后没有任何选项"），
而且它不会被编译、混合注入核对、状态机断言中的任何一关拦住。

## 规则

`ui-transitions/src/com/uitransitions/` 下：

  · `fabric/`   子包 —— 允许用 net.fabricmc.*（只在 Fabric 上加载）
  · `neoforge/` 子包 —— 允许用 net.neoforged.*（只在 NeoForge 上加载）
  · 其余（含 com/uitransitions 根包与 mixin/）—— **两边都会加载，两者都不许用**

mixin/ 也要守这条：Mixin 类是两个加载器共用的。

用法：python tools/check_shared_code.py [--quiet]
退出码：0 = 干净，非 0 = 发现问题。
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(REPO, "ui-transitions", "src", "com", "uitransitions")

FORBIDDEN = [
    ("Fabric 专属", r"\bnet\.fabricmc\b"),
    ("NeoForge 专属", r"\bnet\.neoforged\b"),
]
# 只在这两个子包里才允许出现对应加载器的类
FABRIC_ONLY = os.path.join(SRC, "fabric")
NEOFORGE_ONLY = os.path.join(SRC, "neoforge")


def strip_comments(src):
    """去掉注释与字符串字面量里的内容，避免把说明文字当成真实引用。"""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            out.append('""')
            i = j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in src[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if not os.path.isdir(SRC):
        sys.exit("找不到源码目录：%s" % SRC)

    problems = []
    checked = 0
    for root, _, files in os.walk(SRC):
        in_fabric = os.path.abspath(root).startswith(os.path.abspath(FABRIC_ONLY))
        in_neoforge = os.path.abspath(root).startswith(os.path.abspath(NEOFORGE_ONLY))
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            checked += 1
            body = strip_comments(open(path, encoding="utf-8").read())
            rel = os.path.relpath(path, REPO)
            for label, pattern in FORBIDDEN:
                if label.startswith("Fabric") and in_fabric:
                    continue
                if label.startswith("NeoForge") and in_neoforge:
                    continue
                for m in re.finditer(pattern, body):
                    line = body[:m.start()].count("\n") + 1
                    problems.append((rel, line, label, m.group(0)))

    if problems:
        print("共用代码里出现了加载器专属的引用（这会让另一个加载器整屏空白）：")
        for rel, line, label, what in problems:
            print("   %s:%d  [%s] %s" % (rel, line, label, what))
        print()
        print("   fabric/ 与 neoforge/ 子包内可以用各自的加载器 API，其他地方两个都不许用。")
        return 1

    if not args.quiet:
        print("共用代码检查: 通过（%d 个文件，没有加载器专属引用）" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
