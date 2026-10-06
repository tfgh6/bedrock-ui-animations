#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 javac 编译 ui-transitions 的全部源码到 build/ui-transitions/classes。

README 里那条一行命令很难维护（依赖路径随机器变化、还漏了 brigadier 等），
这个脚本把"到底需要哪些 jar"固定下来，并给出可读的报错。

关于 NeoForge：本机通常没有 NeoForge 的开发期 API（net.neoforged.fml.*），
这时脚本会在 build/neoforge-stubs/ 下生成**仅供类型检查**的最小桩类，
并在输出里明确警告：NeoForge 侧的真实 API 兼容性**没有**被验证。
真正的加载期验证只能在装有 NeoForge 的环境里做。

用法：
    python tools/compile.py                     # 编译
    python tools/compile.py --release 21        # 指定 --release（默认 21，与 mixin 配置一致）
    python tools/compile.py --no-stubs          # 不用桩类；缺 NeoForge 就报错退出
"""

import argparse
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(REPO, "ui-transitions", "src")
OUT = os.path.join(REPO, "build", "ui-transitions", "classes")
STUBS = os.path.join(REPO, "build", "neoforge-stubs")

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME"),
    r"C:\Program Files\Java\zulu25.30.17-ca-jdk25.0.1-win_x64",
    r"C:\Program Files\Java\jdk-24.0.2",
    r"C:\Program Files\Java\jdk-23.0.1",
    r"C:\Program Files\Java\jdk-21.0.8",
    r"C:\Program Files\Java\jdk-21",
]

# (显示名, 相对仓库的候选路径/或绝对路径列表, 是否必需)
LIB_CANDIDATES = [
    ("Minecraft 客户端", [
        os.path.join(REPO, "build", "mc", "client-26.3.jar")], True),
    ("Mixin", [
        os.path.join(REPO, "build", "mixin-test", "sponge-mixin-0.17.4+mixin.0.8.7.jar")], True),
    ("Fabric Loader", [
        os.path.join(REPO, "build", "mixin-test", "fabric-loader-0.19.5.jar")], True),
    ("JOML", [
        os.path.join(REPO, "build", "mixin-test", "joml.jar")], True),
    ("Mod Menu", [
        os.path.join(REPO, "build", "libs", "modmenu.jar")], False),
    ("Cloth Config", [
        os.path.join(REPO, "build", "libs", "cloth-config-fabric.jar")], False),
    ("Sodium", [
        os.path.join(REPO, "build", "libs", "sodium.jar")], False),
    ("Brigadier", [
        r"D:\and\pcl2\.minecraft\libraries\com\mojang\brigadier\1.3.11\brigadier-1.3.11.jar"], False),
]

NEOFORGE_STUB_FILES = {
    "net/neoforged/api/distmarker/Dist.java": """
package net.neoforged.api.distmarker;

public enum Dist {
    CLIENT, DEDICATED_SERVER
}
""",
    "net/neoforged/fml/IExtensionPoint.java": """
package net.neoforged.fml;

public interface IExtensionPoint {
}
""",
    "net/neoforged/fml/common/Mod.java": """
package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.neoforged.api.distmarker.Dist;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();

    Dist[] dist() default {Dist.CLIENT, Dist.DEDICATED_SERVER};
}
""",
    "net/neoforged/fml/ModList.java": """
package net.neoforged.fml;

public class ModList {
    public static ModList get() {
        return new ModList();
    }

    public boolean isLoaded(String modId) {
        return false;
    }

    public java.util.Optional<ModContainer> getModContainerById(String modId) {
        return java.util.Optional.empty();
    }
}
""",
    # 旧的 ModLoadingContext 注册方式在 1.20.5 被废弃、之后被移除，
    # 桩里**故意不再提供**它 —— 免得又写出只能在桩上编译过的代码。
    "net/neoforged/fml/ModContainer.java": """
package net.neoforged.fml;

/**
 * NeoForge 会往 @Mod 构造器里注入它。
 *
 * 注册扩展点必须走这里（ModContainer.registerExtensionPoint），
 * 旧的 ModLoadingContext.get().registerExtensionPoint(...) 已被移除。
 */
public class ModContainer {
    public <T extends IExtensionPoint> void registerExtensionPoint(Class<? extends T> point, T value) {
    }
}
""",
    "net/neoforged/neoforge/client/gui/IConfigScreenFactory.java": """
package net.neoforged.neoforge.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.fml.ModContainer;

/** 函数式接口：NeoForge 自带的模组列表会为它显示一个「配置」按钮 */
@FunctionalInterface
public interface IConfigScreenFactory extends IExtensionPoint {
    Screen createScreen(ModContainer container, Screen parent);
}
""",
}


def find_jdk():
    for base in JDK_CANDIDATES:
        if base and os.path.isfile(os.path.join(base, "bin", "javac.exe")):
            return base
        if base and os.path.isfile(os.path.join(base, "bin", "javac")):
            return base
    return None


def collect_classpath(no_stubs):
    cp, missing_required, missing_optional = [], [], []
    for label, paths, required in LIB_CANDIDATES:
        found = next((p for p in paths if os.path.isfile(p)), None)
        if found:
            cp.append(found)
        elif required:
            missing_required.append(label)
        else:
            missing_optional.append(label)

    has_neoforge = any(_jar_has(j, "net/neoforged/fml/ModLoadingContext.class") for j in cp
                       if j.endswith(".jar"))
    used_stubs = False
    if not has_neoforge:
        if no_stubs:
            missing_required.append("NeoForge API (net.neoforged.fml.*)")
        else:
            write_stubs()
            cp.append(STUBS)
            used_stubs = True
    return cp, missing_required, missing_optional, used_stubs


def _jar_has(jar, entry):
    try:
        with zipfile.ZipFile(jar) as z:
            return entry in z.namelist()
    except Exception:                                          # noqa: BLE001
        return False


def write_stubs():
    for rel, src in NEOFORGE_STUB_FILES.items():
        path = os.path.join(STUBS, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(src.lstrip())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", default="21",
                        help="javac --release 的值（要与 mixins.json 的 compatibilityLevel 对齐）")
    parser.add_argument("--no-stubs", action="store_true", help="不允许生成 NeoForge 桩类")
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()

    jdk = find_jdk()
    if not jdk:
        sys.exit("找不到 JDK（试过 JAVA_HOME 和几个常见安装位置）")
    javac = os.path.join(jdk, "bin", "javac.exe")
    if not os.path.isfile(javac):
        javac = os.path.join(jdk, "bin", "javac")
    print("JDK : %s" % jdk)

    cp, missing_required, missing_optional, used_stubs = collect_classpath(args.no_stubs)
    if missing_required:
        sys.exit("缺少必需的依赖: %s" % ", ".join(missing_required))
    if missing_optional:
        print("提示: 缺少可选依赖 %s（用到它的类会编译失败）" % ", ".join(missing_optional))
    if used_stubs:
        print()
        print("!! 未找到 NeoForge 开发期 API，已生成仅供类型检查的桩类 -> %s" % STUBS)
        print("!! NeoForge 侧的真实 API 兼容性**没有**被验证；请在有 NeoForge 的环境里做加载测试。")
        print()

    sources = []
    for root, _, files in os.walk(SRC):
        for f in sorted(files):
            if f.endswith(".java"):
                sources.append(os.path.join(root, f))
    if not sources:
        sys.exit("在 %s 下没找到任何 .java" % SRC)

    os.makedirs(args.out, exist_ok=True)
    cmd = [javac, "-J-Duser.language=en", "-J-Duser.country=US",
           "--release", args.release, "-proc:none", "-nowarn",
           "-encoding", "UTF-8", "-cp", os.pathsep.join(cp), "-d", args.out]
    cmd.extend(sources)

    print("编译 %d 个源文件 -> %s" % (len(sources), os.path.relpath(args.out, REPO)))
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.stdout.strip():
        print(proc.stdout.strip())
    if proc.stderr.strip():
        print(proc.stderr.strip())
    if proc.returncode != 0:
        print("编译失败（退出码 %d）" % proc.returncode)
        return proc.returncode

    count = sum(len([f for f in files if f.endswith(".class")])
                for _, _, files in os.walk(args.out))
    print("编译成功，产出 %d 个 class 文件" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
