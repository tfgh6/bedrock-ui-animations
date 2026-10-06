#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打包 UI Transitions：把编译产物与两套加载器元数据装进同一个 jar。

产物结构：
    fabric.mod.json                    -> Fabric 加载
    ui-transitions.mixins.json         -> 两个加载器共用同一份 Mixin 配置
    META-INF/neoforge.mods.toml        -> NeoForge 加载（含 [[mixins]] 声明）
    META-INF/MANIFEST.MF
    com/uitransitions/**.class
"""

import json
import os
import sys
import zipfile

# 所有路径都相对本文件推导，不依赖仓库被放在哪里
HERE = os.path.dirname(os.path.abspath(__file__))          # <仓库>/ui-transitions
WORK = os.path.dirname(HERE)                               # <仓库>
ROOT = HERE
CLASSES = os.path.join(WORK, "build", "ui-transitions", "classes")
OUT_DIR = os.path.join(WORK, "build", "ui-transitions")
RESOURCES = os.path.join(ROOT, "resources")

ARTIFACT_STEM = "Bedrock-UI-Animations"


def validate_metadata():
    problems = []
    with open(os.path.join(RESOURCES, "fabric.mod.json"), encoding="utf-8") as fh:
        fabric = json.load(fh)
    for key in ("schemaVersion", "id", "version", "entrypoints", "mixins"):
        if key not in fabric:
            problems.append("fabric.mod.json 缺少字段: %s" % key)
    with open(os.path.join(RESOURCES, "ui-transitions.mixins.json"), encoding="utf-8") as fh:
        mixins = json.load(fh)
    for key in ("package", "client", "injectors"):
        if key not in mixins:
            problems.append("mixin 配置缺少字段: %s" % key)

    toml_path = os.path.join(RESOURCES, "META-INF", "neoforge.mods.toml")
    toml_text = open(toml_path, encoding="utf-8").read()
    try:
        import tomllib
        toml = tomllib.loads(toml_text)
        if toml.get("modLoader") != "javafml":
            problems.append("neoforge.mods.toml: modLoader 必须为 javafml")
        if "license" not in toml:
            problems.append("neoforge.mods.toml: 缺少 license（NeoForge 强制要求）")
        mods = toml.get("mods") or []
        if not mods or mods[0].get("modId") != fabric["id"]:
            problems.append("neoforge.mods.toml 的 modId 与 fabric.mod.json 的 id 不一致")
        configs = [m.get("config") for m in (toml.get("mixins") or [])]
        if "ui-transitions.mixins.json" not in configs:
            problems.append("neoforge.mods.toml 未声明 mixin 配置")
        deps = toml.get("dependencies", {}).get(fabric["id"], [])
        if not any(d.get("modId") == "neoforge" for d in deps):
            problems.append("neoforge.mods.toml 未声明 neoforge 依赖")
        print("TOML 解析: OK（tomllib）")
    except ImportError:
        if "modLoader" not in toml_text or "[[mods]]" not in toml_text or "[[mixins]]" not in toml_text:
            problems.append("neoforge.mods.toml 关键结构缺失")
        print("TOML 解析: 跳过（本机 Python 无 tomllib），仅做关键字检查")
    except Exception as exc:  # noqa: BLE001
        problems.append("neoforge.mods.toml 解析失败: %s" % exc)
    return fabric, mixins, problems


def check_entrypoints(fabric):
    """entrypoints 里写的类必须真的编出来了：写错只会静默失效，不会崩溃。"""
    missing = []
    for loader, values in (fabric.get("entrypoints") or {}).items():
        for value in values:
            class_name = value.split("::")[0].strip()
            if not class_name:
                continue
            rel = class_name.replace(".", os.sep) + ".class"
            if not os.path.isfile(os.path.join(CLASSES, rel)):
                missing.append("%s (%s)" % (class_name, loader))
    return missing


def check_sources_fresh():
    """源码比 class 新就警告：否则会把上一轮的旧 class 打进 jar。"""
    newest_src, newest_src_path = 0.0, None
    for root, _, files in os.walk(os.path.join(ROOT, "src")):
        for f in files:
            if f.endswith(".java"):
                p = os.path.join(root, f)
                m = os.path.getmtime(p)
                if m > newest_src:
                    newest_src, newest_src_path = m, p
    oldest_cls, oldest_cls_path = None, None
    for root, _, files in os.walk(CLASSES):
        for f in files:
            if f.endswith(".class"):
                p = os.path.join(root, f)
                m = os.path.getmtime(p)
                if oldest_cls is None or m < oldest_cls:
                    oldest_cls, oldest_cls_path = m, p
    if newest_src and oldest_cls and newest_src > oldest_cls:
        print("  [警告] 源码比编译产物新，可能打进旧 class：")
        print("         %s" % os.path.relpath(newest_src_path, WORK))
        print("         早于 %s" % os.path.relpath(oldest_cls_path, WORK))


def check_mixin_targets():
    """静态核对每个 Mixin 的注入目标是否真的存在于目标类里。

    mixin 配置是 defaultRequire=0，注入没命中只会静默失效；
    这道检查把"改错了描述符"变成构建期错误。
    """
    script = os.path.join(WORK, "tools", "check_mixins.py")
    if not os.path.isfile(script):
        print("Mixin 目标核对: 跳过（缺少 tools/check_mixins.py）")
        return True
    import subprocess
    client_jar = os.path.join(WORK, "build", "mc", "client-26.3.jar")
    cmd = [sys.executable, script, "--client-jar", client_jar, "--quiet"]
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    out = ((proc.stdout or "") + (proc.stderr or "")).strip()
    if proc.returncode != 0:
        print(out)
        return False
    print(out or "Mixin 目标核对: 全部命中")
    return True


def main():
    if not os.path.isdir(CLASSES):
        sys.exit("找不到编译产物目录：%s" % CLASSES)

    fabric, mixins, problems = validate_metadata()
    if problems:
        for p in problems:
            print("  [元数据问题] " + p)
        sys.exit("元数据校验失败")
    version = fabric["version"]
    print("元数据校验: 通过（id=%s version=%s）" % (fabric["id"], version))
    print("Mixin 类数: %d" % len(mixins.get("client", [])))

    # 校验 Mixin 配置里列出的类都真实存在
    missing = []
    for name in mixins.get("client", []):
        rel = name.replace(".", os.sep) + ".class"
        if not os.path.isfile(os.path.join(CLASSES, mixins["package"].replace(".", os.sep), rel)):
            missing.append(name)
    if missing:
        sys.exit("Mixin 配置里这些类不存在: %s" % missing)
    print("Mixin 类文件: 全部存在")

    missing_entrypoints = check_entrypoints(fabric)
    if missing_entrypoints:
        sys.exit("entrypoints 里的类没有编译产物: %s" % missing_entrypoints)
    print("entrypoints 类文件: 全部存在")

    check_sources_fresh()

    if not check_mixin_targets():
        sys.exit("Mixin 注入目标核对失败：某个注入点已经不存在了")

    OUT_JAR = os.path.join(OUT_DIR, "%s-%s-fabric+neoforge.jar" % (ARTIFACT_STEM, version))
    # 清单里的版本必须跟元数据一致，否则调试工具会看到两个不同版本号
    manifest = "Manifest-Version: 1.0\r\nImplementation-Version: %s\r\n\r\n" % version
    os.makedirs(OUT_DIR, exist_ok=True)
    with zipfile.ZipFile(OUT_JAR, "w", zipfile.ZIP_DEFLATED) as zout:
        zout.writestr("META-INF/MANIFEST.MF", manifest)
        # 资源目录下的一切都收进 jar（元数据、mixin 配置、图标等）
        resource_count = 0
        for root, _, files in os.walk(RESOURCES):
            for f in sorted(files):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, RESOURCES).replace(os.sep, "/")
                zout.write(full, rel)
                resource_count += 1
                print("  资源: %s" % rel)
        count = 0
        for root, _, files in os.walk(CLASSES):
            for f in sorted(files):
                if f.endswith(".class"):
                    full = os.path.join(root, f)
                    zout.write(full, os.path.relpath(full, CLASSES).replace(os.sep, "/"))
                    count += 1
        print("已写入 class 文件: %d，资源文件: %d" % (count, resource_count))

    with zipfile.ZipFile(OUT_JAR) as z:
        names = z.namelist()
    print("jar 条目数: %d" % len(names))
    print("产物: %s (%d 字节)" % (OUT_JAR, os.path.getsize(OUT_JAR)))
    # 清单里的版本与元数据一致性（启动器会读）
    import re
    mf = None
    with zipfile.ZipFile(OUT_JAR) as z:
        mf = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    found = re.search(r"Implementation-Version:\s*(\S+)", mf)
    if not found or found.group(1) != version:
        sys.exit("MANIFEST 里的 Implementation-Version 与元数据不一致: %r" % mf)
    # 元数据里声明的图标必须真的在 jar 里
    icon_ok = fabric.get("icon") in names
    print("  图标 %-34s %s" % (fabric.get("icon"), "已包含" if icon_ok else "缺失!"))
    if not icon_ok:
        sys.exit("图标路径与 fabric.mod.json 的 icon 不一致")


if __name__ == "__main__":
    main()
