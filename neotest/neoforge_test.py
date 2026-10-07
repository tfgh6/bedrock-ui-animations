#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NeoForge 实机验证：用机器上**真实的** NeoForge 启动一次客户端，确认模组真的能加载。

## 为什么需要这个脚本

`tools/compile.py` 在没有 NeoForge 开发期 API 时会生成"桩类"做类型检查。
但桩类是**我们自己写的**，它只能证明"类型对得上"，证明不了别的：

  · 真实 API 的方法签名/包路径是否正确；
  · 模组 jar 会不会被 FML 接受（JPMS 模块解析、包冲突、metadata 校验）；
  · 注册扩展点的时机与方式在运行时是否成立。

1.3.0 ~ 1.4.0 的每个包都因为"桩类被打进 jar 导致 JPMS 包冲突"而在 NeoForge 上
**完全无法启动**，而构建日志一路绿灯。这个脚本就是为了堵住这类问题。

## 它做什么

  1. 从版本 JSON 解析 classpath 与启动参数（按当前系统过滤 rules）；
  2. 准备一个**独立的测试游戏目录**，不碰用户的真实存档、配置与模组；
  3. 把待测 jar 与前置（Cloth Config）放进去；
  4. 启动游戏，盯 logs/latest.log；
  5. 命中预期标记 -> PASS；出现崩溃/异常 -> FAIL 并打印原因；
  6. 结束进程，默认清理测试目录（--keep 保留现场）。

## 用法

    python neotest/neoforge_test.py                      # 全自动
    python neotest/neoforge_test.py --jar <jar>          # 指定待测包
    python neotest/neoforge_test.py --keep --timeout 300 # 保留现场、放宽超时
    python neotest/neoforge_test.py --list               # 只列出找到的安装

退出码：0 = 通过，非 0 = 失败（原因会打印出来）。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# 到哪找 NeoForge 安装：先看常见启动器的 .minecraft，再让用户用 --mc 指定
MC_CANDIDATES = [
    r"D:\and\pcl2\.minecraft",
    os.path.join(os.environ.get("APPDATA", ""), ".minecraft"),
    os.path.join(os.environ.get("USERPROFILE", ""), ".minecraft"),
]

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME"),
    r"C:\Program Files\Java\zulu25.30.17-ca-jdk25.0.1-win_x64",
    r"C:\Program Files\Java\jdk-25",
    r"C:\Program Files\Java\jdk-24.0.2",
]

# 启动成功的判据：模组自己的日志，以及"绝不能出现"的东西
EXPECT_OK = [
    "已注册 NeoForge 配置入口",
]
EXPECT_ALSO = [
    "已检测到 Cloth Config",
]
EXPECT_BAD = [
    "Failed to start FML",
    "ResolutionException",
    "Mixin apply failed",
    "A potential solution has been determined",
    "Failed to create mod instance",
    "Exception in thread \"main\"",
]


def log(msg):
    print(msg, flush=True)


def find_mc_root(explicit):
    if explicit:
        if not os.path.isdir(explicit):
            sys.exit("指定的 Minecraft 目录不存在：%s" % explicit)
        return explicit
    for c in MC_CANDIDATES:
        if c and os.path.isdir(os.path.join(c, "versions")):
            return c
    sys.exit("找不到 .minecraft 目录，请用 --mc 指定")


def current_os():
    return "windows" if os.name == "nt" else ("osx" if sys.platform == "darwin" else "linux")


def current_arch():
    return "x86" if sys.maxsize <= 2 ** 32 else "x86_64"


def rules_allow(rules, features=None):
    """
    按**原版启动器语义**判断这条库/参数适不适用：

      · 没有 rules        -> 适用
      · 有 rules          -> **默认不适用**，只有匹配到一条 allow 才适用
      · rules 里的 os/arch 条件不匹配 -> 这条规则不适用，继续看下一条
      · rules 里的 features 条件      -> 我们一个 feature 都不开（demo、自定义分辨率、
        quickPlay），所以一律不适用

    这里踩过坑：早先把"os 条件不匹配"当成了"没有意见"，于是 Linux/macOS 专用的
    natives 全被算成"缺失的库"，脚本直接拒绝启动。
    """
    if not rules:
        return True
    allowed = False
    for r in rules:
        cond = r.get("os") or {}
        ok = True
        name = cond.get("name")
        if name and name != current_os():
            ok = False
        arch = cond.get("arch")
        if ok and arch and not re.search(arch, current_arch()):
            ok = False
        if ok and r.get("features"):
            ok = False
        if ok:
            allowed = (r.get("action") == "allow")
    return allowed


def find_versions(mc_root):
    """找出所有 NeoForge 版本目录（按 neoforge 库判断，而不是靠目录名）。"""
    out = []
    vroot = os.path.join(mc_root, "versions")
    if not os.path.isdir(vroot):
        return out
    for name in sorted(os.listdir(vroot)):
        vdir = os.path.join(vroot, name)
        if not os.path.isdir(vdir):
            continue
        # 只认与目录同名的那个 json（版本目录里可能还有别的 json，比如启动器自己的缓存）
        jp = os.path.join(vdir, name + ".json")
        if not os.path.isfile(jp):
            cands = [f for f in os.listdir(vdir) if f.endswith(".json")]
            if not cands:
                continue
            jp = os.path.join(vdir, cands[0])
        try:
            with open(jp, encoding="utf-8") as fh:
                meta = json.load(fh)
        except Exception:
            continue
        if not isinstance(meta, dict):
            continue
        # 判据用 mainClass：26.3 的版本 json 里**不含** neoforge 本体那个库
        # （它由 FML 通过 -DlibraryDirectory + --fml.neoForgeVersion 自己定位），
        # 所以"库列表里有 neoforge"这种判据是找不到东西的。
        if not str(meta.get("mainClass", "")).startswith("net.neoforged"):
            continue
        nfver = ""
        game_args = (meta.get("arguments") or {}).get("game", [])
        for i, a in enumerate(game_args):
            if a == "--fml.neoForgeVersion" and i + 1 < len(game_args):
                nfver = game_args[i + 1]
        if not nfver:
            m = re.search(r"(\d+[\w.\-]*)$", name)
            nfver = m.group(1) if m else name
        out.append({
            "name": name,
            "dir": vdir,
            "json": jp,
            "meta": meta,
            "neoforge": nfver,
        })
    return out


def pick_version(versions, want):
    if not versions:
        return None
    if want:
        for v in versions:
            if want in v["name"] or want in v["neoforge"]:
                return v
        sys.exit("没找到匹配 %r 的 NeoForge 版本，可用：%s"
                 % (want, ", ".join(v["name"] for v in versions)))
    # 默认取"看起来最新"的：优先 26.3，其次名字里版本号最大的
    def key(v):
        m = re.search(r"(\d+)\.(\d+)", v["neoforge"] or v["name"])
        return tuple(int(x) for x in m.groups()) if m else (0, 0)
    return sorted(versions, key=key)[-1]


def find_jdk():
    for c in JDK_CANDIDATES:
        if c and os.path.isfile(os.path.join(c, "bin", "java.exe")):
            return os.path.join(c, "bin", "java.exe")
        if c and os.path.isfile(os.path.join(c, "bin", "java")):
            return os.path.join(c, "bin", "java")
    return None


def build_launch(mc_root, ver, game_dir, username, java):
    """拼出完整的 java 命令行（含可执行文件本身）。"""
    meta = ver["meta"]
    cp_entries = []
    missing = []
    for lib in meta.get("libraries", []):
        if not rules_allow(lib.get("rules")):
            continue
        art = (lib.get("downloads") or {}).get("artifact") or {}
        rel = art.get("path")
        if not rel:
            continue
        p = os.path.join(mc_root, "libraries", rel.replace("/", os.sep))
        if os.path.isfile(p):
            cp_entries.append(p)
        else:
            missing.append(rel)
    # 版本自身的 jar（PCL2 会放在版本目录里）
    ver_jar = os.path.join(ver["dir"], ver["name"] + ".jar")
    if os.path.isfile(ver_jar):
        cp_entries.append(ver_jar)
    else:
        missing.append(ver["name"] + ".jar（版本主 jar）")

    natives = os.path.join(ver["dir"], ver["name"] + "-natives")
    assets = os.path.join(mc_root, "assets")
    classpath = os.pathsep.join(cp_entries)

    subs = {
        "natives_directory": natives,
        "launcher_name": "neotest",
        "launcher_version": "1",
        "classpath": classpath,
        "library_directory": os.path.join(mc_root, "libraries"),
        "auth_player_name": username,
        "version_name": ver["name"],
        "game_directory": game_dir,
        "assets_root": assets,
        "assets_index_name": (meta.get("assetIndex") or {}).get("id") or meta.get("assets") or "34",
        "auth_uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, username)),
        "auth_access_token": "0",
        "clientid": "0",
        "auth_xuid": "0",
        "version_type": "neotest",
        "resolution_width": "854",
        "resolution_height": "480",
    }

    def expand(s):
        for k, v in subs.items():
            s = s.replace("${%s}" % k, v)
        return s

    def add_args(entries):
        for a in entries:
            if isinstance(a, str):
                cmd.append(expand(a))
            elif rules_allow(a.get("rules")):
                val = a.get("value")
                if isinstance(val, str):
                    cmd.append(expand(val))
                else:
                    cmd.extend(expand(x) for x in val)

    cmd = [java]
    # 统一 stdout/stderr 为 UTF-8：模组日志里有中文，Windows 默认 GBK 会让解析结果飘
    cmd += ["-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8"]
    add_args((meta.get("arguments") or {}).get("jvm", []))
    cmd.append(meta.get("mainClass") or "net.neoforged.fml.startup.Client")
    add_args((meta.get("arguments") or {}).get("game", []))
    return cmd, missing


def tail(path, n=40):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return "".join(fh.readlines()[-n:])
    except Exception:
        return ""


def main():
    ap = argparse.ArgumentParser(description="用真实 NeoForge 启动一次，验证模组能否加载")
    ap.add_argument("--mc", help="Minecraft 根目录（含 versions/ 与 libraries/）")
    ap.add_argument("--version", help="NeoForge 版本目录名或版本号片段")
    ap.add_argument("--jar", help="待测模组 jar（默认取 build/ui-transitions 里版本号最大的）")
    ap.add_argument("--cloth", help="Cloth Config 的 neoforge jar（默认从源实例 mods 里找）")
    ap.add_argument("--game-dir", help="测试用游戏目录（默认 build/neotest/game）")
    ap.add_argument("--timeout", type=int, default=240, help="最长等待秒数（默认 240）")
    ap.add_argument("--keep", action="store_true", help="结束后保留测试目录与日志")
    ap.add_argument("--list", action="store_true", help="只列出找到的 NeoForge 安装")
    args = ap.parse_args()

    mc_root = find_mc_root(args.mc)
    versions = find_versions(mc_root)
    if args.list:
        log("Minecraft 根目录: %s" % mc_root)
        for v in versions:
            log("  %-38s neoforge=%s" % (v["name"], v["neoforge"]))
        return 0
    ver = pick_version(versions, args.version)
    if not ver:
        sys.exit("在 %s 下没找到任何 NeoForge 版本" % mc_root)
    log("Minecraft : %s" % mc_root)
    log("版本      : %s (neoforge %s)" % (ver["name"], ver["neoforge"]))

    java = find_jdk()
    if not java:
        sys.exit("找不到 JDK（试过 JAVA_HOME 和几个常见位置）")
    log("Java      : %s" % java)

    # 待测 jar：默认挑 build/ui-transitions 里版本号最大的那个合并包
    jar = args.jar
    if not jar:
        cands = []
        d = os.path.join(REPO, "build", "ui-transitions")
        if os.path.isdir(d):
            for f in os.listdir(d):
                m = re.match(r"Bedrock-UI-Animations-([\d.]+)-fabric\+neoforge\.jar$", f)
                if m:
                    cands.append((tuple(int(x) for x in m.group(1).split(".")), os.path.join(d, f)))
        if not cands:
            sys.exit("在 build/ui-transitions 里找不到合并包，请先 build_jar.py 或用 --jar 指定")
        jar = sorted(cands)[-1][1]
    if not os.path.isfile(jar):
        sys.exit("待测 jar 不存在：%s" % jar)
    log("待测模组  : %s (%d 字节)" % (os.path.basename(jar), os.path.getsize(jar)))

    # ---- 先做一道静态检查：jar 里绝不能有非本模组的 class（JPMS 包冲突的根因）----
    import zipfile
    with zipfile.ZipFile(jar) as z:
        foreign = [n for n in z.namelist()
                   if n.endswith(".class") and not n.startswith("com/uitransitions/")]
    if foreign:
        log("")
        log("!! 待测 jar 里混入了非本模组的 class（%d 个）：" % len(foreign))
        for n in foreign[:8]:
            log("     " + n)
        log("!! 这种包在 NeoForge 上会因 JPMS 包冲突直接启动失败，不必浪费一次实机测试。")
        return 2
    log("静态检查  : jar 内 class 全部属于 com/uitransitions ✅")

    # Cloth Config 是硬前置，测试环境里必须有
    cloth = args.cloth
    if not cloth:
        src_mods = os.path.join(ver["dir"], "mods")
        if os.path.isdir(src_mods):
            for f in sorted(os.listdir(src_mods)):
                if f.lower().startswith("cloth-config") and f.endswith(".jar"):
                    cloth = os.path.join(src_mods, f)
                    break
    if not cloth or not os.path.isfile(cloth):
        sys.exit("找不到 Cloth Config 的 neoforge jar（它是硬前置），请用 --cloth 指定")
    log("Cloth     : %s" % os.path.basename(cloth))

    game_dir = args.game_dir or os.path.join(REPO, "build", "neotest", "game")
    if os.path.isdir(game_dir):
        shutil.rmtree(game_dir, ignore_errors=True)
    mods_dir = os.path.join(game_dir, "mods")
    os.makedirs(mods_dir, exist_ok=True)
    shutil.copy2(jar, mods_dir)
    shutil.copy2(cloth, mods_dir)
    # options.txt：跳过首次启动的引导，窗口小一点
    with open(os.path.join(game_dir, "options.txt"), "w", encoding="utf-8") as fh:
        fh.write("onboardAccessibility:false\npauseOnLostFocus:false\n")
    log("测试目录  : %s" % game_dir)

    cmd, missing = build_launch(mc_root, ver, game_dir, "NeoTest", java)
    if missing:
        log("")
        log("!! 缺少 %d 个运行库（可能是这个安装本来就不完整）：" % len(missing))
        for m in missing[:10]:
            log("     " + m)
        log("!! 先让启动器把这个版本补全，再来跑实机测试。")
        return 2

    logfile = os.path.join(game_dir, "logs", "latest.log")
    # 关键：模组用 System.out.println 打的日志**不进** latest.log（那里面只有 Log4j 的输出），
    # 所以必须同时抓进程的 stdout，否则永远等不到成功标记。
    console = os.path.join(game_dir, "console.log")
    log("")
    log("启动游戏（最长等 %d 秒）……" % args.timeout)
    cout = open(console, "wb")
    proc = subprocess.Popen(cmd, cwd=game_dir, stdout=cout, stderr=subprocess.STDOUT)

    ok_hit = None
    also_hit = False
    bad_hit = None
    deadline = time.time() + args.timeout
    try:
        while time.time() < deadline:
            if proc.poll() is not None and not os.path.isfile(logfile):
                break
            txt = ""
            for src in (console, logfile):
                if os.path.isfile(src):
                    txt += tail(src, 400) + "\\n"
            if txt:
                if bad_hit is None:
                    for b in EXPECT_BAD:
                        if b in txt:
                            bad_hit = b
                            break
                if ok_hit is None:
                    for k in EXPECT_OK:
                        if k in txt:
                            ok_hit = k
                            break
                if not also_hit:
                    also_hit = all(x in txt for x in EXPECT_ALSO)
                if ok_hit and also_hit:
                    # 再等几秒，让可能的后续异常也落进日志
                    time.sleep(6)
                    break
            if bad_hit:
                time.sleep(2)
                break
            time.sleep(2)
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=25)
        except Exception:
            try:
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                               capture_output=True)
            except Exception:
                pass

    log("")
    log("=" * 66)
    if bad_hit:
        log("结果: FAIL —— 日志里出现了致命错误：%s" % bad_hit)
        log("=" * 66)
        log("日志尾部：")
        for line in (tail(console, 45) or tail(logfile, 45)).splitlines():
            log("  " + line)
        if not args.keep:
            shutil.rmtree(game_dir, ignore_errors=True)
        return 3

    if ok_hit:
        log("结果: PASS —— 模组在真实 NeoForge %s 上加载成功" % ver["neoforge"])
        log("      命中: %s" % ok_hit)
        log("      前置: %s" % ("已检测到 Cloth Config" if also_hit else "（没看到前置检测日志，请留意）"))
        log("=" * 66)
        if not args.keep:
            shutil.rmtree(game_dir, ignore_errors=True)
        else:
            log("现场保留在 %s" % game_dir)
        return 0

    log("结果: FAIL —— %d 秒内没等到模组注册成功的日志" % args.timeout)
    log("=" * 66)
    log("日志尾部：")
    for line in tail(logfile, 45).splitlines():
        log("  " + line)
    if not args.keep:
        shutil.rmtree(game_dir, ignore_errors=True)
    else:
        log("现场保留在 %s" % game_dir)
    return 4


if __name__ == "__main__":
    sys.exit(main())
