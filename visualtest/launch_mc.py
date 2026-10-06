#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按 PCL2 的版本 JSON 组装并启动 Fabric 26.3 客户端（不经过启动器 GUI）。

用法:
    python launch_mc.py --print      只打印命令
    python launch_mc.py              直接启动（前台，输出到 stdout）

要点:
  * classpath = 版本 jar + 所有本地存在的库（缺的那些是 linux/macos 原生库，Windows 上本就不需要）
  * natives   = 把 *natives-windows* 的 jar 解到 build/mc-natives 并传给 -Djava.library.path
  * 额外传入可视化测试开关与 --demo（自动创建并进入演示世界）
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
import zipfile

MC_DIR = r"D:\and\pcl2\.minecraft"
VERSION_ID = os.environ.get("MC_VERSION_ID", "26.3-Fabric 0.19.5")
VERSION_DIR = os.path.join(MC_DIR, "versions", VERSION_ID)
VERSION_JSON = os.path.join(VERSION_DIR, VERSION_ID + ".json")
VERSION_JAR = os.path.join(VERSION_DIR, VERSION_ID + ".jar")
LIBRARIES = os.path.join(MC_DIR, "libraries")

# 路径都相对本文件推导（本文件在 <仓库>/visualtest/ 下）
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.dirname(HERE)
NATIVES = os.path.join(WORK, "build", "mc-natives")
VISUAL_OUT = os.path.join(WORK, "build", "visual-out")
LOG_PATH = os.path.join(WORK, "build", "mc-visualtest.log")
JAVA = os.environ.get("MC_JAVA", r"C:\Program Files\Java\zulu25.30.17-ca-jdk25.0.1-win_x64\bin\java.exe")

# 本机 JVM 的架构：x86（32 位）/ x86_64（64 位）/ aarch64
JVM_ARCH = os.environ.get("MC_JVM_ARCH") or ("aarch64" if os.environ.get("PROCESSOR_ARCHITECTURE", "").lower()
                                             in ("arm64", "aarch64") else "x86_64")

# 启动器会提供的特性开关：这里按本脚本的实际意图写死
FEATURES = {
    "is_demo_user": False,             # 不用演示模式（26.3 的 --demo 不会自动建世界）
    "has_custom_resolution": True,     # 我们确实传了 --width/--height
    "has_quick_plays_support": False,
    "is_quick_play_singleplayer": False,
    "is_quick_play_multiplayer": False,
    "is_quick_play_realms": False,
}

PLACEHOLDERS = {
    "natives_directory": NATIVES,
    "launcher_name": "dsh-visualtest",
    "launcher_version": "1.0",
    "classpath_separator": os.pathsep,
    "library_directory": LIBRARIES,
    "auth_player_name": "VisualTester",
    "version_name": VERSION_ID,
    "game_directory": VERSION_DIR,
    "assets_root": os.path.join(MC_DIR, "assets"),
    "assets_index_name": None,          # 运行时从版本 JSON 的 assetIndex.id 取，别写死
    "auth_uuid": "00000000000000000000000000000001",
    "auth_access_token": "0",
    "user_type": "legacy",
    "version_type": "release",
    "resolution_width": "1280",
    "resolution_height": "720",
}


def strip_empty_option_pairs(tokens):
    """把形如 `--flag <空值或未展开占位符>` 的参数整对丢掉，并移除 --demo。

    启动器（PCL）会把未使用的可选参数留成空占位符；若不过滤，
    游戏会认为传了 `--quickPlayXxx`（空值），从而和多出来的 quick-play 选项冲突。
    另外 26.3 的 --demo 不会自动建世界、还会限制正常建世界，这里直接去掉。
    """
    result = []
    i = 0
    while i < len(tokens):
        token = tokens[i]
        if token == "--demo":
            print("  丢弃 --demo（测试用正常世界）")
            i += 1
            continue
        if token.startswith("--") and i + 1 < len(tokens):
            value = tokens[i + 1]
            if value == "" or "${" in value:
                print("  丢弃未填值的可选参数: %s %s" % (token, value))
                i += 2
                continue
        result.append(token)
        i += 1
    return result


def restore_game_window(pid, attempts=24, interval=5.0):
    """把游戏窗口从最小化恢复并置前（否则渲染被跳过，截图拿不到内容）"""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    enum_proc = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)

    def find_windows():
        found = []

        def callback(hwnd, _):
            owner = wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
            if owner.value == pid and user32.GetWindowTextLengthW(hwnd) > 0:
                found.append(hwnd)
            return True

        user32.EnumWindows(enum_proc(callback), 0)
        return found

    for attempt in range(attempts):
        windows = find_windows()
        if windows:
            for hwnd in windows:
                user32.ShowWindow(hwnd, 9)          # SW_RESTORE
                user32.SetForegroundWindow(hwnd)
            print("  已恢复并置前游戏窗口（第 %d 次尝试，句柄 %s）" % (attempt + 1, windows))
            return True
        time.sleep(interval)
    print("  未找到游戏窗口，渲染可能被跳过")
    return False


def expand(value, classpath):
    if not isinstance(value, str):
        return None
    if value == "${classpath}":
        return classpath
    for key, replacement in PLACEHOLDERS.items():
        value = value.replace("${%s}" % key, replacement)
    return value


# 本机平台名要映射成 Mojang 的写法（Python 是 win32/darwin，Mojang 是 windows/osx）
MC_OS_NAME = {"win32": "windows", "cygwin": "windows", "darwin": "osx"}.get(sys.platform, sys.platform)


def _os_matches(condition):
    """规则的 os 条件：name / arch / version 都要看"""
    if not condition:
        return True
    name = condition.get("name")
    if name is not None and name != MC_OS_NAME:
        return False
    arch = condition.get("arch")
    if arch is not None:
        # Mojang 只用 "x86" 表示 32 位，其余按 64 位处理
        is_32bit = JVM_ARCH in ("x86", "i386", "i486", "i586", "i686")
        if arch == "x86" and not is_32bit:
            return False
        if arch != "x86" and is_32bit:
            return False
    version = condition.get("version")
    if version is not None:
        import re
        if not re.search(version, _os_version()):
            return False
    return True


def _os_version():
    if sys.platform == "win32":
        # 与启动器一致：Windows 10/11 报 "10.0"
        info = sys.getwindowsversion()
        return "10.0" if info.major >= 10 else "%d.%d" % (info.major, info.minor)
    return os.uname().release if hasattr(os, "uname") else ""


def _features_match(required):
    for key, value in (required or {}).items():
        if FEATURES.get(key, False) != value:
            return False
    return True


def rules_allow(rules):
    """按 Mojang 规则求值：逐条匹配，最后一条命中的决定结果；无规则 = 允许。

    原来只看 os.name，导致 `arch: x86` 与 `features` 条件被当成"恒真"，
    于是 32 位专用的 -Xss1M 被套到 64 位 JVM 上，arm64 的原生库也被选中。
    """
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if not _os_matches(rule.get("os")):
            continue
        if not _features_match(rule.get("features")):
            continue
        allowed = rule.get("action") == "allow"
    return allowed


def natives_arch_ok(path):
    """原生库 jar 的架构是否与本机 JVM 匹配。

    版本 JSON 里 natives-windows 与 natives-windows-arm64 的规则**完全一样**
    （都只有 os.name=windows，没有 arch 条件），所以只靠规则会把两种架构都选中；
    扁平解包到同一个目录时后写入的 arm64 会覆盖 x64，游戏只能靠 LWJGL
    自己的运行时回退解包才能起来。这里显式按架构过滤。
    """
    name = os.path.basename(path).lower()
    is_arm64 = "arm64" in name or "aarch64" in name
    if JVM_ARCH == "aarch64":
        return is_arm64
    return not is_arm64


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--print", action="store_true", dest="print_only")
    parser.add_argument("--extra-mod", action="append", default=[],
                        help="额外放进 mods 的 jar 路径")
    args = parser.parse_args()

    import json
    if not os.path.isfile(VERSION_JSON):
        sys.exit("找不到版本 JSON：%s" % VERSION_JSON)
    with open(VERSION_JSON, encoding="utf-8") as fh:
        version = json.load(fh)

    # 资源索引从版本 JSON 取，别写死：换实例时写死会静默指到不存在的索引
    PLACEHOLDERS["assets_index_name"] = (version.get("assetIndex") or {}).get("id") or ""

    # 附加 mod 先校验存在性，免得跑到一半才炸
    for jar in args.extra_mod:
        if not os.path.isfile(jar):
            sys.exit("--extra-mod 指定的文件不存在：%s" % jar)

    # ---- classpath ----
    entries = [VERSION_JAR]
    natives_jars = []
    skipped_arch = []
    missing = 0
    for lib in version.get("libraries", []):
        if not rules_allow(lib.get("rules")):
            continue
        artifact = (lib.get("downloads") or {}).get("artifact") or {}
        path = artifact.get("path")
        if not path:
            # 没有显式路径的用 maven 坐标推
            parts = lib["name"].split(":")
            group, name, ver = parts[0], parts[1], parts[2]
            classifier = parts[3] if len(parts) > 3 else None
            path = "%s/%s/%s/%s-%s%s.jar" % (group.replace(".", "/"), name, ver, name, ver,
                                             "-" + classifier if classifier else "")
        local = os.path.join(LIBRARIES, path.replace("/", os.sep))
        if not os.path.isfile(local):
            missing += 1
            continue
        entries.append(local)
        if "natives" in os.path.basename(local):
            if natives_arch_ok(local):
                natives_jars.append(local)
            else:
                skipped_arch.append(os.path.basename(local))
    classpath = os.pathsep.join(entries)
    if skipped_arch:
        print("按架构(%s)跳过 %d 个原生库: %s"
              % (JVM_ARCH, len(skipped_arch), ", ".join(sorted(skipped_arch)[:4])
                 + (" ..." if len(skipped_arch) > 4 else "")))

    # ---- 命令行 ----
    command = [JAVA]
    for item in version.get("arguments", {}).get("jvm", []):
        if isinstance(item, str):
            command.append(item)
        elif rules_allow(item.get("rules")):
            value = item.get("value")
            if isinstance(value, list):
                command.extend(value)
            elif value:
                command.append(value)
    command = [c for c in command if c]
    command = [expand(c, classpath) or c for c in command]

    # 我们的附加参数（放在最前，避免被 JSON 里的参数覆盖）
    command[1:1] = [
        "-Xmx2G",
        "-Duitransitions.visualTest=true",
        "-Duitransitions.visualTest.dir=" + VISUAL_OUT,
    ]

    command.append(version["mainClass"])
    for item in version.get("arguments", {}).get("game", []):
        if isinstance(item, str):
            command.append(item)
        elif rules_allow(item.get("rules")):
            value = item.get("value")
            if isinstance(value, list):
                command.extend(value)
            elif value:
                command.append(value)
    command = [expand(c, classpath) if isinstance(c, str) else c for c in command]
    command = strip_empty_option_pairs(command)
    # --demo 的规则是 is_demo_user=true，上面 FEATURES 里已关掉；strip 里的兜底保留

    print("classpath 条目: %d（跳过本地缺失 %d 个，通常是非 Windows 原生库）" % (len(entries), missing))
    print("待解包原生库: %d 个 -> %s" % (len(natives_jars), NATIVES))
    print("资源索引: %s" % PLACEHOLDERS["assets_index_name"])
    print("gameDir: %s" % VERSION_DIR)
    print("可视化输出: %s" % VISUAL_OUT)
    print("命令长度: %d 字符" % len(" ".join(command)))

    if args.print_only:
        # --print 必须是纯粹的预演：到此为止不删目录、不解包、不装 mod
        print("\n" + subprocess.list2cmdline(command))
        return 0

    # ---- 安装额外 mod ----
    mods_dir = os.path.join(VERSION_DIR, "mods")
    os.makedirs(mods_dir, exist_ok=True)
    for jar in args.extra_mod:
        shutil.copy2(jar, os.path.join(mods_dir, os.path.basename(jar)))
        print("已放入 mods: %s" % os.path.join(mods_dir, os.path.basename(jar)))

    # ---- natives（真正的副作用，放在打印之后）----
    if os.path.isdir(NATIVES):
        shutil.rmtree(NATIVES)
    os.makedirs(NATIVES, exist_ok=True)
    # 模板里的 -Djava.library.path 指向 <natives>/java，所以同时也放一份到那里
    java_sub = os.path.join(NATIVES, "java")
    os.makedirs(java_sub, exist_ok=True)
    extracted = 0
    for jar in natives_jars:
        with zipfile.ZipFile(jar) as z:
            for entry in z.namelist():
                if entry.endswith((".dll", ".so", ".dylib")):
                    name = os.path.basename(entry)
                    for dest_dir in (NATIVES, java_sub):
                        with z.open(entry) as src, open(os.path.join(dest_dir, name), "wb") as dst:
                            shutil.copyfileobj(src, dst)
                    extracted += 1
    print("natives 解出文件: %d -> %s" % (extracted, NATIVES))

    if args.print_only:
        print("\n" + subprocess.list2cmdline(command))
        return 0

    os.makedirs(VISUAL_OUT, exist_ok=True)
    print("启动 Minecraft ...")
    sys.stdout.flush()
    proc = subprocess.Popen(command, cwd=VERSION_DIR, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

    # 另起一个线程负责把游戏窗口恢复并置前
    import threading
    threading.Thread(target=restore_game_window, args=(proc.pid,), daemon=True).start()

    log_path = LOG_PATH
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    with open(log_path, "w", encoding="utf-8") as log:
        for line in proc.stdout:
            log.write(line)
            log.flush()
            if "[VisualTest]" in line or "Setting user" in line or "ERROR" in line.upper():
                print(line.rstrip())
    code = proc.wait()
    print("游戏退出码: %d，完整日志: %s" % (code, log_path))
    return code


if __name__ == "__main__":
    sys.exit(main())
