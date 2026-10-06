#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实机可视化测试：一条命令跑完「编译模组 → 编译测试驱动 → 启动本机 26.3 Fabric → 抓帧 → 汇总」。

用途
----
模组里很多东西（渲染层级、画中画、自绘界面）在纯状态机断言里是测不到的 ——
只能真的把游戏跑起来看画面。这个脚本就是为这件事准备的，**加新功能时也用它**。

典型用法
--------
    python visualtest/visual_test.py                     # 全流程（较慢，会建世界）
    python visualtest/visual_test.py --phases curve      # 只验曲线编辑器（不用建世界，快）
    python visualtest/visual_test.py --phases curveui    # 只验曲线编辑器的交互（点列表 / 加点 / 删点）
    python visualtest/visual_test.py --phases curve,config
    python visualtest/visual_test.py --phases inventory,enchant
    python visualtest/visual_test.py --list              # 看有哪些阶段
    python visualtest/visual_test.py --skip-build        # 复用上次的 jar，只重跑
    python visualtest/visual_test.py --no-launch         # 只编译不启动（检查能不能编译过）

可选阶段
--------
    panels     合成面板的开/关动画（默认基线，含字幕探针）
    config     Cloth 图形化配置界面
    curve      曲线编辑器（渐入 / 渐出两页）
    curveui    曲线编辑器的交互：点动画列表、切多点模式、加点/删点、核对布局宽度
    world      只进世界并抓一张
    inventory  生存背包：玩家小模型（画中画）是否跟着界面动
    enchant    附魔台：附魔书（画中画）是否跟着界面动、有没有被裁
    creative   创造物品栏：分类标签切换 + 滚动逐格渐变
    sodium     Sodium 视频设置里的本模组页面

产物
----
    build/visual-out/         所有截图（连拍也归档到这里）
    build/mc-visualtest.log   游戏输出（含 [VisualTest] 日志）

说明：截图是在游戏内用 Screenshot.grab 直接抓帧，像素级准确、不掉帧，
比外部录像更适合"看某个元素有没有被裁掉"这类判断。
若要人工复核整体观感，也可以自己开 OBS 录一段 —— 但逐帧结论以本脚本的截图为准。
"""

import argparse
import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LAUNCH = os.path.join(HERE, "launch_mc.py")
VERSION_DIR = os.path.join(r"D:\and\pcl2\.minecraft", "versions",
                           os.environ.get("MC_VERSION_ID", "26.3-Fabric 0.19.5"))
OUT_DIR = os.path.join(ROOT, "build", "visual-out")
DRIVER_CLASSES = os.path.join(ROOT, "build", "visualtest-classes")
DRIVER_JAR = os.path.join(ROOT, "build", "visualtest-driver.jar")
LOG_PATH = os.path.join(ROOT, "build", "mc-visualtest.log")

JDK = os.environ.get("MC_JDK", r"C:\Program Files\Java\zulu25.30.17-ca-jdk25.0.1-win_x64")
JAVAC = os.path.join(JDK, "bin", "javac.exe")
JAR = os.path.join(JDK, "bin", "jar.exe")

PHASES = {
    "panels": "合成面板的开/关动画（基线，含字幕探针）",
    "config": "Cloth 图形化配置界面",
    "configclick": "配置界面里「打开曲线编辑器」入口能不能点开",
    "curve": "曲线编辑器（渐入 / 渐出）",
    "curveui": "曲线编辑器的交互：动画列表 / 多点加点删点 / 布局宽度核对",
    "world": "只进世界并抓一张",
    "inventory": "生存背包：玩家小模型（画中画）",
    "enchant": "附魔台：附魔书（画中画）",
    "creative": "创造物品栏：标签切换 + 滚动渐变",
    "sodium": "Sodium 视频设置里的本模组页面",
}

WORLD_PHASES = {"world", "inventory", "enchant", "creative"}

# 子进程一律用 UTF-8：这套脚本和它调用的工具都会打印中文，
# 而 Windows 上 Python 默认按 GBK 编码 stdout，直接跑会 UnicodeEncodeError。
PY = [sys.executable, "-X", "utf8"]
CHILD_ENV = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")


def run(cmd, **kwargs):
    """跑一条命令，实时透传输出，返回退出码。"""
    printable = cmd if isinstance(cmd, str) else subprocess.list2cmdline(cmd)
    print("  $ %s" % printable)
    kwargs.setdefault("env", CHILD_ENV)
    return subprocess.call(cmd, **kwargs)


def step(title):
    print("\n" + "=" * 66)
    print("== " + title)
    print("=" * 66)


def add_to_jar(jar_path, source_dir):
    """把目录内容打进 jar（保持相对路径）"""
    import glob
    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in glob.glob(os.path.join(source_dir, "**", "*"), recursive=True):
            if os.path.isfile(path):
                zf.write(path, os.path.relpath(path, source_dir))


def build_mod():
    step("1/4 编译并打包模组")
    if run(PY + [os.path.join(ROOT, "tools", "compile.py")]) != 0:
        return False
    if run(PY + [os.path.join(ROOT, "ui-transitions", "build_jar.py")]) != 0:
        return False
    return True


def get_classpath():
    """复用 launch_mc.py 的 classpath 解析：编译驱动与真正启动必须是同一条"""
    result = subprocess.run(PY + [LAUNCH, "--print-classpath"],
                            capture_output=True, text=True, encoding="utf-8", env=CHILD_ENV)
    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr, file=sys.stderr)
        return None
    # 只取最后一行：万一有别的诊断信息混进 stdout，也不会污染 classpath
    # （多一行换行就会让 -cp 整体失效，表现为"MC 的类全都找不到"）
    lines = [ln.strip() for ln in result.stdout.splitlines() if ln.strip()]
    if not lines:
        print("--print-classpath 没有输出", file=sys.stderr)
        return None
    return lines[-1]


def build_driver():
    step("2/4 编译测试驱动")
    classpath = get_classpath()
    if not classpath:
        print("拿不到 classpath，无法编译驱动")
        return None
    print("  classpath 条目: %d" % len(classpath.split(os.pathsep)))

    shutil.rmtree(DRIVER_CLASSES, ignore_errors=True)
    os.makedirs(DRIVER_CLASSES, exist_ok=True)
    sources = []
    for base, _dirs, files in os.walk(os.path.join(HERE, "src")):
        for name in files:
            if name.endswith(".java"):
                sources.append(os.path.join(base, name))
    if not sources:
        print("找不到测试驱动源码")
        return None

    # NeoForge 那个薄入口需要 neoforge 的注解类；本机没有它的 API，跳过它只编 Fabric 侧
    sources = [s for s in sources if not s.endswith("VisualTestNeoForge.java")]
    print("  源文件: %d 个（跳过 NeoForge 入口）" % len(sources))

    code = run([JAVAC, "-J-Duser.language=en", "--release", "21", "-proc:none", "-nowarn",
                "-encoding", "UTF-8", "-cp", classpath, "-d", DRIVER_CLASSES] + sources)
    if code != 0:
        return None

    # 资源 + 入口
    stage = os.path.join(ROOT, "build", "visualtest-stage")
    shutil.rmtree(stage, ignore_errors=True)
    shutil.copytree(DRIVER_CLASSES, stage)
    res = os.path.join(HERE, "resources")
    if os.path.isdir(res):
        shutil.copytree(res, stage, dirs_exist_ok=True)
    if os.path.isfile(DRIVER_JAR):
        os.remove(DRIVER_JAR)
    add_to_jar(DRIVER_JAR, stage)
    print("  驱动 jar: %s (%d 字节)" % (DRIVER_JAR, os.path.getsize(DRIVER_JAR)))
    return DRIVER_JAR


def find_mod_jars():
    """
    找出本次要装进游戏的模组 jar。

    不能只按文件名排序取第一个 —— build/ui-transitions/ 里会堆着历史版本的 jar，
    那样会测到旧版本（曾经就踩过一次：拿 1.3.0 去测 1.3.6 的功能）。
    这里以 fabric.mod.json 里的 version 为准精确匹配。
    """
    out = os.path.join(ROOT, "build", "ui-transitions")
    if not os.path.isdir(out):
        return []
    names = sorted(os.listdir(out))

    version = None
    mod_json = os.path.join(ROOT, "ui-transitions", "resources", "fabric.mod.json")
    if os.path.isfile(mod_json):
        import json
        try:
            with open(mod_json, encoding="utf-8") as fh:
                version = json.load(fh).get("version")
        except Exception as exc:
            print("  读取 fabric.mod.json 失败: %s" % exc)

    if not version:
        print("  警告：读不到版本号，按修改时间取最新的 jar")
        jars = [os.path.join(out, n) for n in names if n.endswith(".jar")]
        jars.sort(key=os.path.getmtime, reverse=True)
        return jars[:1]

    # 优先合并包：build_jar.py 每次都会重写它，而拆分包只有 build_release.py 才重写。
    # 顺序反了的话，改完代码去测，跑的其实是上一次的拆分包 —— 画面纹丝不动，白折腾一轮。
    for suffix in ("-fabric+neoforge.jar", "-fabric.jar"):
        candidate = os.path.join(out, "Bedrock-UI-Animations-%s%s" % (version, suffix))
        if os.path.isfile(candidate):
            return [candidate]
    print("  警告：build/ui-transitions/ 里没有 %s 的 jar" % version)
    return []


def purge_installed_mods(mods_dir):
    """
    清掉实例 mods/ 里**我们自己的**旧 jar。

    这是最阴的一个坑：mods/ 里留着几小时前的 1.2.9，新 jar 又拷进去，
    两个同名模组一起被加载 —— 现象是"代码改了、实机画面纹丝不动"，
    很容易误判成"改动没生效"然后去乱改代码。
    只删本项目的 jar，不动 cloth-config / fabric-api / sodium 那些。
    """
    if not os.path.isdir(mods_dir):
        return []
    prefixes = ("bedrock-ui-animations-", "ui-transitions-", "uitransitions-")
    removed = []
    for name in os.listdir(mods_dir):
        lower = name.lower()
        if lower.endswith(".jar") and lower.startswith(prefixes):
            try:
                os.remove(os.path.join(mods_dir, name))
                removed.append(name)
            except OSError as exc:
                print("  删不掉 %s: %s" % (name, exc))
    return removed


def launch(mod_jar, driver_jar, phases):
    step("3/4 启动游戏（会自动进世界，请勿手动操作鼠标键盘）")
    # 先把实例里我们自己的旧 jar 清掉，避免新旧两个同名模组一起被加载
    mods_dir = os.path.join(VERSION_DIR, "mods")
    removed = purge_installed_mods(mods_dir)
    if removed:
        print("  已清理 mods/ 里的旧版本: %s" % ", ".join(removed))
    args = PY + [LAUNCH,
                 "--extra-mod", mod_jar,
                 "--extra-mod", driver_jar]
    if phases:
        # 必须写成 --jvm-arg=值：argparse 会把以 - 开头的值当成新的选项，
        # 空格分隔的写法会直接报 "expected one argument"（踩过一次：游戏根本没启动）
        args.append("--jvm-arg=-Duitransitions.visualTest.phases=" + ",".join(phases))
    print("  阶段: %s" % (",".join(phases) if phases else "all"))
    print("  日志: %s" % LOG_PATH)
    with open(LOG_PATH, "wb") as log:
        return subprocess.call(args, stdout=log, stderr=subprocess.STDOUT)


def summarize(phases):
    step("4/4 截图汇总")
    if not os.path.isdir(OUT_DIR):
        print("  输出目录不存在: %s" % OUT_DIR)
        return
    groups = {}
    for name in sorted(os.listdir(OUT_DIR)):
        if not name.endswith(".png"):
            continue
        prefix = name.rsplit("_", 1)[0]
        groups.setdefault(prefix, []).append(name)
    if not groups:
        print("  没有截图")
        return
    print("  输出目录: %s" % OUT_DIR)
    for prefix in sorted(groups):
        files = groups[prefix]
        size = sum(os.path.getsize(os.path.join(OUT_DIR, f)) for f in files)
        print("    %-22s %2d 张  %6.1f KB   例: %s" % (prefix, len(files), size / 1024.0, files[0]))

    # 从日志里挑出失败与关键结论，省得人工翻
    if os.path.isfile(LOG_PATH):
        interesting = []
        with open(LOG_PATH, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if "[VisualTest]" in line and ("失败" in line or "警告" in line or "根因" in line):
                    interesting.append(line.strip())
        print("\n  测试日志里值得注意的行:")
        if interesting:
            for line in interesting[:25]:
                print("    " + line)
        else:
            print("    （没有失败/警告）")


def main():
    parser = argparse.ArgumentParser(description="实机可视化测试（加新功能时也用它）")
    parser.add_argument("--phases", default="",
                        help="逗号分隔的阶段；留空=全部。用 --list 看可选值")
    parser.add_argument("--list", action="store_true", help="列出所有阶段后退出")
    parser.add_argument("--skip-build", action="store_true", help="跳过编译，复用已有 jar")
    parser.add_argument("--no-launch", action="store_true", help="只编译，不启动游戏")
    parser.add_argument("--keep", action="store_true",
                        help="保留 build/visual-out 里上一轮的截图（默认会先清空，避免新旧混淆）")
    args = parser.parse_args()

    if args.list:
        print("可选阶段（--phases a,b）:")
        for name, desc in PHASES.items():
            tag = "  [需进世界]" if name in WORLD_PHASES else ""
            print("  %-10s %s%s" % (name, desc, tag))
        return 0

    phases = [p.strip() for p in args.phases.split(",") if p.strip()]
    bad = [p for p in phases if p not in PHASES]
    if bad:
        sys.exit("未知阶段: %s（用 --list 查看）" % ", ".join(bad))

    print("Bedrock UI Animations —— 实机可视化测试")
    print("仓库: %s" % ROOT)
    print("阶段: %s" % (",".join(phases) if phases else "全部"))

    os.makedirs(OUT_DIR, exist_ok=True)

    driver_jar = os.path.join(ROOT, "build", "visualtest-driver.jar")
    mod_jars = find_mod_jars()

    if not args.skip_build:
        if not build_mod():
            sys.exit("模组编译/打包失败")
        driver_jar = build_driver()
        if not driver_jar:
            sys.exit("测试驱动编译失败")
        mod_jars = find_mod_jars()

    if not mod_jars:
        sys.exit("找不到模组 jar（build/ui-transitions/*-fabric.jar），请先编译")
    mod_jar = mod_jars[0]
    print("\n使用模组: %s" % mod_jar)
    if not os.path.isfile(driver_jar):
        sys.exit("找不到测试驱动 jar: %s（先不要加 --skip-build）" % driver_jar)

    if args.no_launch:
        print("\n--no-launch：到此为止")
        return 0

    # 清空输出目录：否则上一轮的截图会和这一轮混在一起，
    # 汇总时根本分不清哪张是刚拍的（踩过一次：看着"每段 2 张"，其实一半是旧的）
    if not args.keep:
        removed = 0
        if os.path.isdir(OUT_DIR):
            for name in os.listdir(OUT_DIR):
                if name.endswith(".png"):
                    try:
                        os.remove(os.path.join(OUT_DIR, name))
                        removed += 1
                    except OSError:
                        pass
        print("\n已清空上一轮的截图: %d 张" % removed)

    code = launch(mod_jar, driver_jar, phases)
    print("\n游戏退出码: %s" % code)
    if code != 0:
        print("游戏没有正常跑完 —— 请看 %s" % LOG_PATH, file=sys.stderr)
        with open(LOG_PATH, encoding="utf-8", errors="replace") as fh:
            tail = fh.read().splitlines()[-25:]
        for line in tail:
            print("    " + line, file=sys.stderr)
        return code
    summarize(phases)
    print("\n完成。截图在 %s" % OUT_DIR)
    return 0


if __name__ == "__main__":
    sys.exit(main())
