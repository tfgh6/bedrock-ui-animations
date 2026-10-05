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

WORK = r"D:\Program Files (x86)\deepseekHarness\Project"
NATIVES = os.path.join(WORK, "build", "mc-natives")
VISUAL_OUT = os.path.join(WORK, "build", "visual-out")
JAVA = r"C:\Program Files\Java\zulu25.30.17-ca-jdk25.0.1-win_x64\bin\java.exe"

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
    "assets_index_name": "34",
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


def rules_allow(rules):
    """简化版规则判断：只看 Windows / 排除项"""
    if not rules:
        return True
    allowed = False
    for rule in rules:
        action = rule.get("action")
        os_name = (rule.get("os") or {}).get("name")
        if os_name is None:
            allowed = action == "allow"
        elif os_name == "windows":
            allowed = action == "allow"
    return allowed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--print", action="store_true", dest="print_only")
    parser.add_argument("--extra-mod", action="append", default=[],
                        help="额外放进 mods 的 jar 路径")
    args = parser.parse_args()

    import json
    with open(VERSION_JSON, encoding="utf-8") as fh:
        version = json.load(fh)

    # ---- classpath ----
    entries = [VERSION_JAR]
    natives_jars = []
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
            natives_jars.append(local)
    classpath = os.pathsep.join(entries)

    # ---- natives ----
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
    # 注意：版本 JSON 里已经带了 --demo，不要再自己加一个（会变成两个 quick play 选项而崩溃）

    # ---- 安装额外 mod ----
    mods_dir = os.path.join(VERSION_DIR, "mods")
    for jar in args.extra_mod:
        shutil.copy2(jar, os.path.join(mods_dir, os.path.basename(jar)))
        print("已放入 mods: %s" % os.path.basename(jar))

    print("classpath 条目: %d（跳过本地缺失 %d 个，通常是非 Windows 原生库）" % (len(entries), missing))
    print("natives 解出文件: %d -> %s" % (extracted, NATIVES))
    print("gameDir: %s" % VERSION_DIR)
    print("可视化输出: %s" % VISUAL_OUT)
    print("命令长度: %d 字符" % len(" ".join(command)))

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

    log_path = os.path.join(WORK, "build", "mc-visualtest.log")
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
