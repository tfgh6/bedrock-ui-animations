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

WORK = r"D:\Program Files (x86)\deepseekHarness\Project"
ROOT = os.path.join(WORK, "ui-transitions")
CLASSES = os.path.join(WORK, "build", "ui-transitions", "classes")
RESOURCES = os.path.join(ROOT, "resources")
VERSION = json.load(open(os.path.join(RESOURCES, "fabric.mod.json"), encoding="utf-8"))["version"]
OUT_JAR = None
OUT_DIR = os.path.join(WORK, "build", "ui-transitions")
OUT_JAR = os.path.join(OUT_DIR, "Bedrock-UI-Animations-%s-fabric+neoforge.jar" % VERSION)

MANIFEST = "Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\n\r\n"


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


def main():
    if not os.path.isdir(CLASSES):
        sys.exit("找不到编译产物目录：%s" % CLASSES)
    fabric, mixins, problems = validate_metadata()
    if problems:
        for p in problems:
            print("  [元数据问题] " + p)
        sys.exit("元数据校验失败")
    print("元数据校验: 通过（id=%s version=%s）" % (fabric["id"], fabric["version"]))
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

    os.makedirs(OUT_DIR, exist_ok=True)
    with zipfile.ZipFile(OUT_JAR, "w", zipfile.ZIP_DEFLATED) as zout:
        zout.writestr("META-INF/MANIFEST.MF", MANIFEST)
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
    # 元数据里声明的图标必须真的在 jar 里
    icon_ok = fabric.get("icon") in names
    print("  图标 %-34s %s" % (fabric.get("icon"), "已包含" if icon_ok else "缺失!"))
    if not icon_ok:
        sys.exit("图标路径与 fabric.mod.json 的 icon 不一致")


if __name__ == "__main__":
    main()
