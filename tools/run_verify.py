#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""编译并运行 verify-uit 下的状态机断言。

这套断言用桩类替掉 Minecraft，跑的是核心状态机的真实逻辑
（开/关动画、打断接续、各开关的取舍、物品透明度通道、PIP 透明度等）。
它**不**验证渲染效果，也**不**验证 mixin 是否命中 —— 后者交给 tools/check_mixins.py。

要点：
  * 桩类里的 gameDirectory 落到临时目录，所以不会改写仓库里的 config/；
  * 每个断言程序都以非零退出码表示失败，方便接进 CI。

用法：
    python tools/run_verify.py
    python tools/run_verify.py --keep     # 保留编译产物便于排查
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CORE = os.path.join(REPO, "ui-transitions", "src", "com", "uitransitions")
VERIFY_UIT = os.path.join(REPO, "verify-uit")
STUBS = os.path.join(VERIFY_UIT, "stubs")
OUT = os.path.join(REPO, "build", "verify-uit-classes")

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME"),
    r"C:\Program Files\Java\zulu25.30.17-ca-jdk25.0.1-win_x64",
    r"C:\Program Files\Java\jdk-24.0.2",
    r"C:\Program Files\Java\jdk-23.0.1",
    r"C:\Program Files\Java\jdk-21.0.8",
    r"C:\Program Files\Java\jdk-21",
]

PROGRAMS = ["VerifyTransitions", "VerifyAdvanced"]


def find_jdk():
    for base in JDK_CANDIDATES:
        if base and os.path.isfile(os.path.join(base, "bin", "javac.exe")):
            return base
    return None


def java_sources(root):
    out = []
    for dirpath, _, files in os.walk(root):
        for f in sorted(files):
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留编译产物")
    args = parser.parse_args()

    jdk = find_jdk()
    if not jdk:
        sys.exit("找不到 JDK")
    javac = os.path.join(jdk, "bin", "javac.exe")
    java = os.path.join(jdk, "bin", "java.exe")

    for label, path in (("verify-uit", VERIFY_UIT), ("stubs", STUBS), ("core", CORE)):
        if not os.path.isdir(path):
            sys.exit("缺少目录 %s: %s" % (label, path))

    sources = java_sources(STUBS)
    # 只取核心两个类：fabric/neoforge/sodium 那些需要真实依赖，断言用不到
    for name in ("UiTransitions.java", "TransitionConfig.java"):
        p = os.path.join(CORE, name)
        if not os.path.isfile(p):
            sys.exit("缺少核心类: %s" % p)
        sources.append(p)
    for name in PROGRAMS:
        p = os.path.join(VERIFY_UIT, name + ".java")
        if os.path.isfile(p):
            sources.append(p)

    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)

    print("编译 %d 个文件（桩类 + 核心状态机 + 断言程序）" % len(sources))
    compile_cmd = [javac, "-J-Duser.language=en", "--release", "21", "-proc:none", "-nowarn",
                   "-encoding", "UTF-8", "-d", OUT] + sources
    proc = subprocess.run(compile_cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.stdout.strip():
        print(proc.stdout.strip())
    if proc.stderr.strip():
        print(proc.stderr.strip())
    if proc.returncode != 0:
        print("断言程序编译失败")
        return proc.returncode

    # gameDirectory 指向临时目录：否则 TransitionConfig 会把 config/ 写到当前工作目录
    gamedir = tempfile.mkdtemp(prefix="uitransitions-verify-")
    failures = 0
    try:
        for program in PROGRAMS:
            if not os.path.isfile(os.path.join(OUT, program + ".class")):
                print("跳过 %s（未找到）" % program)
                continue
            print()
            print("=" * 70)
            print("运行 %s" % program)
            print("=" * 70)
            run = subprocess.run(
                [java, "-Duitransitions.gamedir=" + gamedir, "-cp", OUT, program],
                capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=gamedir)
            print((run.stdout or "").rstrip())
            if run.stderr.strip():
                print(run.stderr.strip())
            if run.returncode != 0:
                failures += 1
                print(">>> %s 失败（退出码 %d）" % (program, run.returncode))
            else:
                print(">>> %s 通过" % program)
    finally:
        shutil.rmtree(gamedir, ignore_errors=True)
        if not args.keep and os.path.isdir(OUT):
            shutil.rmtree(OUT, ignore_errors=True)

    if failures:
        print()
        print("断言失败：%d 个程序未通过" % failures)
        return 1
    print()
    print("全部断言通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
