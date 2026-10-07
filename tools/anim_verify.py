#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
动画数学层验证（`tools/anim-verify/MathLayerVerify.java`）。

## 为什么单独一关，而不是并进 run_verify.py

`MathLayerVerify` 的**全部价值**在于"对照物是旧实现的真实行为" —— 它把
`com.uitransitions.anim` 的曲线数学与 `TransitionConfig.Curve` **逐位比较**
（`Float.floatToIntBits`，不是近似）。所以它必须有 `TransitionConfig` 在 classpath 上。

而 `tools/run_verify.py` 是**故意无 classpath** 的（它用桩类替换 MC 类型）。
两者需求正好相反，硬并进去会让那一关失去"纯离线、无依赖"的性质。

本关的定位：**第 1 步"行为零变化"的守门人**。没有它，那份证据只能手动跑 ——
等于"我最想要的那份证据，恰好没有闸看着"。

## 它需要什么

  · `ui-transitions/src/com/uitransitions/anim/**`（被验对象，零 MC 依赖）
  · `ui-transitions/src/com/uitransitions/TransitionConfig.java`（对照物 = 旧实现）
  · `build/mc/client-26.3.jar`（`TransitionConfig` 依赖 MC 类型：Component / Curve 等）

退出码：0 = 通过，非 0 = 有断言失败或编译失败。
"""

import argparse
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# 子进程的输出编码：不设的话 javac/java 的中文提示会按系统 ANSI（GBK）出来，
# 在这个控制台上显示成乱码。tools/verify_all.py 也是这么做的。
CHILD_ENV = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")

SRC = os.path.join(REPO, "ui-transitions", "src", "com", "uitransitions")
ANIM = os.path.join(SRC, "anim")
VERIFY = os.path.join(HERE, "anim-verify")
OUT = os.path.join(REPO, "build", "anim-gate-classes")

MC_JAR_CANDIDATES = [
    os.path.join(REPO, "build", "mc", "client-26.3.jar"),
    os.path.join(REPO, "build", "mc", "client.jar"),
]

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME"),
    r"C:\Program Files\Java\zulu25.30.17-ca-jdk25.0.1-win_x64",
    r"C:\Program Files\Java\jdk-24.0.2",
    r"C:\Program Files\Java\jdk-23.0.1",
    r"C:\Program Files\Java\jdk-21.0.8",
    r"C:\Program Files\Java\jdk-21",
]


def find_jdk():
    for base in JDK_CANDIDATES:
        if base and os.path.isfile(os.path.join(base, "bin", "javac.exe")):
            return base
    return None


def find_mc_jar():
    for path in MC_JAR_CANDIDATES:
        if os.path.isfile(path):
            return path
    return None


def java_sources(root):
    out = []
    for dirpath, _, files in os.walk(root):
        for f in sorted(files):
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return out


def main():
    parser = argparse.ArgumentParser(description="动画数学层验证（与旧实现逐位对照）")
    parser.add_argument("--keep", action="store_true", help="保留编译产物")
    args = parser.parse_args()

    jdk = find_jdk()
    if not jdk:
        sys.exit("找不到 JDK")
    javac = os.path.join(jdk, "bin", "javac.exe")
    java = os.path.join(jdk, "bin", "java.exe")

    if not os.path.isdir(ANIM):
        sys.exit("缺少目录: %s（动画数学层还没落地？）" % ANIM)

    mc_jar = find_mc_jar()
    if not mc_jar:
        # 不是"跳过"：这一关的意义就是拿真实 Curve 当对照物，没有对照物就等于自己跟自己比。
        # 那种断言比没有更糟 —— 它会绿灯，而人以为验证过了。
        sys.exit("找不到 MC 客户端 jar（%s）。先跑 tools/compile.py 或让它下载。"
                 % " 或 ".join(MC_JAR_CANDIDATES))

    sources = java_sources(ANIM)
    if not sources:
        sys.exit("anim/ 下没有源文件")
    # 对照物：旧实现本身
    sources.append(os.path.join(SRC, "TransitionConfig.java"))
    verify = java_sources(VERIFY)
    if not verify:
        sys.exit("缺少验证台: %s" % VERIFY)
    sources += verify

    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)

    print("编译 %d 个文件（anim/ + TransitionConfig + 验证台），对照物=%s"
          % (len(sources), os.path.basename(mc_jar)))
    compile_cmd = [javac, "-J-Duser.language=en", "--release", "21", "-proc:none", "-nowarn",
                   "-encoding", "UTF-8", "-cp", mc_jar, "-d", OUT] + sources
    proc = subprocess.run(compile_cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=CHILD_ENV)
    if proc.stdout.strip():
        print(proc.stdout.strip())
    if proc.stderr.strip():
        print(proc.stderr.strip())
    if proc.returncode != 0:
        sys.exit("编译失败（退出码 %d）" % proc.returncode)

    # 跑验证台。
    #
    # **这里是一串程序，不是一个**：每个验证台守着一块，跑漏一个就等于那块没闸 ——
    # `ColorMathVerify` 就曾经是"写着、绿着、但没人跑"的状态（编译清单里有它、
    # 运行清单里没有）。新增验证台时只要往 PROGRAMS 加一行。
    PROGRAMS = [
        ("动画数学层（与旧实现逐位一致）",
         "com.uitransitions.anim.MathLayerVerify"),
        ("颜色数学与通道（与旧 modulate 逐位等价 + 预乘是显式参数）",
         "com.uitransitions.anim.ColorMathVerify"),
    ]
    classpath = os.pathsep.join([OUT, mc_jar])
    failed = []
    for label, main_class in PROGRAMS:
        print()
        print("-- %s" % label)
        run_cmd = [java, "-Duser.language=en", "-Dfile.encoding=UTF-8",
                   "-cp", classpath, main_class]
        proc = subprocess.run(run_cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", env=CHILD_ENV)
        if proc.stdout.strip():
            print(proc.stdout.strip())
        if proc.stderr.strip():
            print(proc.stderr.strip(), file=sys.stderr)
        if proc.returncode != 0:
            failed.append("%s（退出码 %d）" % (label, proc.returncode))

    if failed:
        sys.exit("验证台未通过：" + "；".join(failed))

    if not args.keep and os.path.isdir(OUT):
        shutil.rmtree(OUT, ignore_errors=True)
    print("\n%d 个验证台全部通过" % len(PROGRAMS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
