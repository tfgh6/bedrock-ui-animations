#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键跑完全部验证关卡。**发版前必须全绿。**

关卡（按顺序，任一步失败就停下）：

  1. compile.py        编译（优先用本机真实 NeoForge API，拿不到才用桩类）
  2. check_mixins.py   Mixin 注入目标核对（defaultRequire=0，没命中只会静默失效）
  3. run_verify.py     状态机断言（桩类 + 真实 JVM）
  4. gen_verify.py     确认 verify-uit 与模板一致（防止有人只改了生成物）
  5. build_jar.py      打包 + 元数据校验 + **产物纯净性硬闸**
  6. neoforge_test.py  实机启动真实 NeoForge（可选，--neoforge 打开）

为什么第 6 关要单独存在：前面五关全是离线的，它们能证明"类型对得上、注入命中、
状态机正确、包打得干净"，但**证明不了 FML 会不会接受这个 jar**。
1.3.0~1.4.0 就是因为桩类被打进 jar 触发 JPMS 包冲突，离线五关全绿而 NeoForge 完全无法启动。

用法：

    python tools/verify_all.py                 # 前五关
    python tools/verify_all.py --neoforge      # 连实机一起跑（会启动游戏，慢）
    python tools/verify_all.py --release 21

退出码：0 = 全绿，非 0 = 有失败。
"""

import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

PY = sys.executable


def run(title, args, cwd=REPO):
    print()
    print("=" * 70)
    print(">>> %s" % title)
    print("=" * 70)
    t0 = time.time()
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    proc = subprocess.run([PY, "-X", "utf8"] + args, cwd=cwd, env=env)
    dt = time.time() - t0
    if proc.returncode != 0:
        print()
        print("!! 失败：%s（退出码 %d，耗时 %.1fs）" % (title, proc.returncode, dt))
        return False
    print("-- 通过：%s（%.1fs）" % (title, dt))
    return True


def main():
    ap = argparse.ArgumentParser(description="跑完全部验证关卡")
    ap.add_argument("--release", default="21", help="javac --release 的值")
    ap.add_argument("--neoforge", action="store_true",
                    help="额外跑真实 NeoForge 实机启动（要装 NeoForge，且会启动游戏）")
    ap.add_argument("--keep", action="store_true", help="实机测试后保留现场")
    args = ap.parse_args()

    stages = [
        ("编译（tools/compile.py）", ["tools/compile.py", "--release", args.release]),
        ("Mixin 注入目标核对（tools/check_mixins.py）", ["tools/check_mixins.py", "--quiet"]),
        ("状态机断言（tools/run_verify.py）", ["tools/run_verify.py"]),
        ("verify-uit 与模板一致性", ["ui-transitions/gen_verify.py", "--check"]),
        ("共用代码不得引用加载器专属类（tools/check_shared_code.py）",
         ["tools/check_shared_code.py", "--quiet"]),
        ("翻译完整性（tools/check_lang.py）", ["tools/check_lang.py", "--quiet"]),
        ("打包 + 产物纯净性（ui-transitions/build_jar.py）", ["ui-transitions/build_jar.py"]),
    ]
    if args.neoforge:
        extra = ["neotest/neoforge_test.py", "--timeout", "240"]
        if args.keep:
            extra.append("--keep")
        stages.append(("真实 NeoForge 实机启动（neotest/neoforge_test.py）", extra))

    t0 = time.time()
    for title, cmd in stages:
        if not run(title, cmd):
            print()
            print("验证未通过，停在：%s" % title)
            return 1

    print()
    print("=" * 70)
    print("全部 %d 关通过（耗时 %.1fs）" % (len(stages), time.time() - t0))
    if not args.neoforge:
        print("提示：加 --neoforge 可以再跑一次真实 NeoForge 实机启动。")
    print("=" * 70)
    return 0


if __name__ == "__main__":
    sys.exit(main())
