#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
翻译完整性检查：代码里用到的每个键都必须在中英文两份语言文件里都有，
而且两份的键集合必须完全一致。

## 为什么需要它

Minecraft 对缺失的翻译键**不会报错**，它会直接把键名原样画在界面上
（"ui_transitions.config.enabled" 这种字符串就这么显示出来）。所以漏一条
在编译、注入核对、状态机断言里都看不出来，只能等用户看到那行丑字才发现。

用法：python tools/check_lang.py [--quiet]
退出码：0 = 干净，非 0 = 有问题。
"""

import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(REPO, "ui-transitions", "src")
LANG_DIR = os.path.join(REPO, "ui-transitions", "resources", "assets",
                        "ui_transitions", "lang")
LANGS = ["zh_cn.json", "en_us.json"]
PREFIX = "ui_transitions."

# 这些键在代码里是动态拼出来的，静态扫不到；列在这里免得被当成"多余的定义"。
#
# 「部位」的名字是按枚举 id 拼的：`"ui_transitions.part." + part.id()`。
# 正则只认到那个裸前缀，拼出来的完整键它看不见 —— 所以**两种都要列**：
#   · "ui_transitions.part."          —— 代码里那个拼接口
#   · "ui_transitions.part.<部位id>"  —— 真正会被显示出来的键
# 7 条一个都不能少：漏一条的症状是界面上直接印出
# "ui_transitions.part.portal" 这种原始键名，而编译、注入核对、状态机断言
# 全都看不出来（和 Sodium 那批键是同一类问题，所以用同一套机制管）。
DYNAMIC_OK = {
    "ui_transitions.part.",
    "ui_transitions.part.panel",
    "ui_transitions.part.dim",
    "ui_transitions.part.items",
    "ui_transitions.part.text",
    "ui_transitions.part.subtitles",
    "ui_transitions.part.tab",
    "ui_transitions.part.portal",
}


def strip_comments(src):
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
            out.append(src[i:j])          # 字符串要留着：键就在字符串里
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


# Sodium 那页的翻译键是**按选项键动态拼出来**的（助手内部用 "sodium." + key），
# 静态扫字符串扫不到，所以这里按同样的规则把它算出来。
SODIUM_OPTION = re.compile(r'\b(?:bool|intOption)\(\s*builder\s*,\s*"([a-z_0-9]+)"')
# tKey("xxx") 传的是**不带前缀**的键，也要算进来。
# 末尾要求紧跟 ")"，这样助手定义里的 tKey("sodium." + key) 这类拼接前缀不会被误当成键。
TKEY_CALL = re.compile(r'\btKey\(\s*"([\w.\-]+)"\s*\)')


def used_keys():
    keys = {}
    for root, _, files in os.walk(SRC):
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            p = os.path.join(root, f)
            rel = os.path.relpath(p, REPO)
            body = strip_comments(open(p, encoding="utf-8").read())
            for m in re.finditer(r'"((?:%s)[\w.\-]+)"' % re.escape(PREFIX), body):
                key = m.group(1)
                line = body[:m.start()].count("\n") + 1
                keys.setdefault(key, []).append("%s:%d" % (rel, line))
            for m in TKEY_CALL.finditer(body):
                key = PREFIX + m.group(1)
                line = body[:m.start()].count("\n") + 1
                keys.setdefault(key, []).append("%s:%d" % (rel, line))
            for m in SODIUM_OPTION.finditer(body):
                opt = m.group(1)
                line = body[:m.start()].count("\n") + 1
                for key in (PREFIX + "sodium." + opt, PREFIX + "sodium." + opt + ".tip"):
                    keys.setdefault(key, []).append("%s:%d(动态)" % (rel, line))
    return keys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if not os.path.isdir(LANG_DIR):
        sys.exit("找不到语言目录：%s" % LANG_DIR)

    tables = {}
    for name in LANGS:
        p = os.path.join(LANG_DIR, name)
        if not os.path.isfile(p):
            sys.exit("缺少语言文件：%s" % p)
        with open(p, encoding="utf-8") as fh:
            tables[name] = json.load(fh)

    used = used_keys()
    problems = []

    def dynamic(key):
        """
        动态键豁免。

        注册在 DYNAMIC_OK 里的键有**两种形态**，必须都放过，否则这个机制等于没有：
          · 完整键（"ui_transitions.part.portal"）—— 不会作为字面量出现在代码里，
            它只出现在语言文件中，所以会被第 3 关当成孤儿；
          · 裸前缀（"ui_transitions.part."）—— 它**是**字面量、能被扫到，
            但它不是一个真的键，语言文件里永远不会、也不该有它，
            所以会被第 1 关当成"缺失"。
        原先只在第 3 关用 DYNAMIC_OK，于是第二种形态无论如何都过不了。
        """
        return key in DYNAMIC_OK or any(key.startswith(known) for known in DYNAMIC_OK)

    # 1) 代码里用到、但某个语言里没有
    for key, where in sorted(used.items()):
        if dynamic(key):
            continue
        for name in LANGS:
            if key not in tables[name]:
                problems.append("键 %s 在 %s 里缺失（用于 %s）" % (key, name, where[0]))

    # 2) 两种语言的键集合必须一致
    base, other = LANGS[0], LANGS[1]
    only_base = sorted(set(tables[base]) - set(tables[other]))
    only_other = sorted(set(tables[other]) - set(tables[base]))
    for k in only_base:
        problems.append("键 %s 只在 %s 里有，%s 缺" % (k, base, other))
    for k in only_other:
        problems.append("键 %s 只在 %s 里有，%s 缺" % (k, other, base))

    # 3) 定义了但代码里没用（多半是改文案时留下的孤儿）
    for key in sorted(set(tables[base])):
        if key not in used and not dynamic(key):
            problems.append("键 %s 定义了但代码里没有用到" % key)

    # 4) 值不能是空的
    for name, table in tables.items():
        for k, v in table.items():
            if not str(v).strip():
                problems.append("键 %s 在 %s 里是空字符串" % (k, name))

    if problems:
        print("翻译检查未通过：")
        for p in problems:
            print("   " + p)
        return 1

    if not args.quiet:
        print("翻译检查: 通过（%d 个键 × %d 种语言，代码与语言文件完全对齐）"
              % (len(used), len(LANGS)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
