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
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(REPO, "ui-transitions", "src")
OUT = os.path.join(REPO, "build", "ui-transitions", "classes")
STUBS = os.path.join(REPO, "build", "neoforge-stubs")
# 桩类的编译产物必须和正式产物分开，否则会被打包进 jar（见 compile_stub_classes）
STUB_CLASSES = os.path.join(REPO, "build", "neoforge-stubs-classes")

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

# NeoForge 的两个核心 jar（neoforge 本体 + FML loader）从本机的 Minecraft 安装里找。
# 找到就用**真 API** 编译，找不到才退回桩类。
NEOFORGE_SEARCH_ROOTS = [
    r"D:\and\pcl2\.minecraft\libraries",
    os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "libraries"),
]
# (标签, 在 jar 里的探测类, 相对 libraries 的目录前缀)
NEOFORGE_JAR_PROBES = [
    ("NeoForge 本体", "net/neoforged/neoforge/client/gui/IConfigScreenFactory.class",
     os.path.join("net", "neoforged", "neoforge")),
    ("FML Loader", "net/neoforged/fml/ModContainer.class",
     os.path.join("net", "neoforged", "fancymodloader", "loader")),
    ("EventBus", "net/neoforged/bus/api/IEventBus.class",
     os.path.join("net", "neoforged", "bus")),
    # @Mod 注解的 dist() 用的是 net.neoforged.api.distmarker.Dist，
    # 这个类在 mergetool 的 -api 分类包里（版本 json 也把它列为运行库）
    ("Dist / 注解支持", "net/neoforged/api/distmarker/Dist.class",
     os.path.join("net", "neoforged", "mergetool")),
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

    # 优先用本机真实 NeoForge API。探测类必须选**26.3 里确实还存在**的：
    # 以前探的是 net/neoforged/fml/ModLoadingContext.class，而它早已被移除，
    # 于是永远探测失败、永远生成桩类 —— 桩类又一路放行，把真正的兼容性问题掩盖了。
    real = find_neoforge_jars()
    used_real_neoforge = len(real) == len(NEOFORGE_JAR_PROBES)
    used_stubs = False
    if real:
        for label, p in real:
            cp.append(p)
            print("NeoForge  : 使用本机真实 API -> %s" % os.path.basename(p))
    if not used_real_neoforge:
        if no_stubs:
            missing_required.append("NeoForge API (net.neoforged.*)")
        else:
            write_stubs()
            # 注意加的是**桩类的编译产物目录**，不是桩类源码目录：
            # 桩类只用来做类型检查，绝不能进正式产物（见 compile_stub_classes）。
            cp.append(STUB_CLASSES)
            used_stubs = True
    return cp, missing_required, missing_optional, used_stubs, used_real_neoforge


def _jar_has(jar, entry):
    try:
        with zipfile.ZipFile(jar) as z:
            return entry in z.namelist()
    except Exception:                                          # noqa: BLE001
        return False


def _version_key(path):
    """从 .../neoforge/26.3.0.48-beta/xxx.jar 这种路径里取出可比较的版本号。"""
    parts = path.replace("\\", "/").split("/")
    for seg in reversed(parts[:-1]):
        m = re.match(r"^(\d+)(?:\.(\d+))?(?:\.(\d+))?", seg)
        if m:
            return tuple(int(x) if x else 0 for x in m.groups())
    return (0, 0, 0)


def find_neoforge_jars():
    """
    在本机的 Minecraft 安装里找 NeoForge 的真实 API jar。

    找到就用它编译 —— 这比桩类强得多：桩类只能证明"类型对得上"，
    证明不了方法签名、包路径、以及 jar 会不会被 FML 接受。
    （1.3.0~1.4.0 的 JPMS 包冲突就是桩类一路放行、实机直接拒启动。）
    """
    found = []
    for label, probe, prefix in NEOFORGE_JAR_PROBES:
        best = None
        for root in NEOFORGE_SEARCH_ROOTS:
            base = os.path.join(root, prefix)
            if not os.path.isdir(base):
                continue
            for dirpath, _, files in os.walk(base):
                for f in files:
                    if not f.endswith(".jar"):
                        continue
                    p = os.path.join(dirpath, f)
                    if "sources" in f or "javadoc" in f:
                        continue
                    if _jar_has(p, probe):
                        key = _version_key(p)
                        if best is None or key > best[0]:
                            best = (key, p)
        if best:
            found.append((label, best[1]))
    return found


def write_stubs():
    for rel, src in NEOFORGE_STUB_FILES.items():
        path = os.path.join(STUBS, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(src.lstrip())


def compile_stub_classes(javac, release, cp):
    """
    把桩类编译到**独立目录**，绝不和正式产物混在一起。

    这里踩过一个很贵的坑：桩类曾经和正式源码输出到同一个目录，而打包脚本正是
    打包那个目录 —— 结果 jar 里带上了 net/neoforged/** 。NeoForge 用 JPMS 加载模组，
    jar 一旦"导出"了 net.neoforged.neoforge.client.gui，就会和 neoforge 模块冲突，
    FML 直接抛 ResolutionException 拒绝启动。1.3.0 到 1.4.0 的每个包都中招，
    NeoForge 侧等于从来没跑起来过，而构建日志一直只是打印一句警告。

    注意要带上 cp：桩类里引用了 Minecraft 的类型（比如 IConfigScreenFactory 里的 Screen）。
    """
    sources = []
    for root, _, files in os.walk(STUBS):
        for f in sorted(files):
            if f.endswith(".java"):
                sources.append(os.path.join(root, f))
    if not sources:
        return 0
    os.makedirs(STUB_CLASSES, exist_ok=True)
    cmd = [javac, "-J-Duser.language=en", "-J-Duser.country=US",
           "--release", release, "-proc:none", "-nowarn",
           "-encoding", "UTF-8", "-cp", os.pathsep.join(cp), "-d", STUB_CLASSES]
    cmd.extend(sources)
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print("桩类编译失败（退出码 %d）" % proc.returncode)
        print(proc.stdout.strip())
        print(proc.stderr.strip())
        return proc.returncode
    return 0


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

    cp, missing_required, missing_optional, used_stubs, used_real_neoforge = \
        collect_classpath(args.no_stubs)
    if missing_required:
        sys.exit("缺少必需的依赖: %s" % ", ".join(missing_required))
    if missing_optional:
        print("提示: 缺少可选依赖 %s（用到它的类会编译失败）" % ", ".join(missing_optional))
    if used_real_neoforge:
        print("NeoForge  : 已用真实 API 编译，签名与包路径都是真的 ✅")
    if used_stubs:
        print()
        print("!! 未找到 NeoForge 开发期 API，已生成仅供类型检查的桩类 -> %s" % STUBS)
        print("!! 桩类编译到独立目录，不会进正式产物 -> %s" % STUB_CLASSES)
        print("!! NeoForge 侧的真实 API 兼容性**没有**被验证；请在有 NeoForge 的环境里做加载测试。")
        print()
        rc = compile_stub_classes(javac, args.release, cp)
        if rc != 0:
            return rc

    sources = []
    for root, _, files in os.walk(SRC):
        for f in sorted(files):
            if f.endswith(".java"):
                sources.append(os.path.join(root, f))
    if not sources:
        sys.exit("在 %s 下没找到任何 .java" % SRC)

    os.makedirs(args.out, exist_ok=True)
    # 先清空输出目录：既保证没有陈旧 class，也顺手清掉历史版本可能留在里面的桩类
    for name in os.listdir(args.out):
        target = os.path.join(args.out, name)
        if os.path.isdir(target):
            shutil.rmtree(target, ignore_errors=True)
        else:
            os.remove(target)
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
