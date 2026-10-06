"""把合并包拆成 Fabric / NeoForge 两个发布 jar。

启动器是按 jar 里的加载器标记归类的：
只要看到 META-INF/neoforge.mods.toml 就会当成 NeoForge 模组（哪怕同时有 fabric.mod.json），
所以单文件"通用包"很容易被误判。拆成两个 jar 后，各自只带自己那份元数据，识别与图标显示都正常。

用法：
    python ui-transitions/build_release.py                 # 拆最近打出的那个合并包
    python ui-transitions/build_release.py --combined <jar>
"""
import argparse
import glob
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))          # <仓库>/ui-transitions
ROOT = os.path.dirname(HERE)                               # <仓库>
BUILD = os.path.join(ROOT, "build", "ui-transitions")

VARIANTS = {
    "fabric": ("fabric.mod.json", "META-INF/neoforge.mods.toml"),
    "neoforge": ("META-INF/neoforge.mods.toml", "fabric.mod.json"),
}


def find_combined():
    """build_jar.py 的产物名带版本号，所以这里按模式找，不要写死版本。"""
    pattern = os.path.join(BUILD, "*-fabric+neoforge.jar")
    found = sorted(glob.glob(pattern), key=os.path.getmtime)
    if not found:
        sys.exit("在 %s 下找不到 *-fabric+neoforge.jar，请先运行 build_jar.py" % BUILD)
    if len(found) > 1:
        print("发现多个合并包，使用最新的一个：")
        for f in found:
            print("   %s%s" % (os.path.basename(f), "  <- 使用" if f == found[-1] else ""))
    return found[-1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--combined", help="指定要拆分的合并包（默认取最新）")
    args = parser.parse_args()

    combined = args.combined or find_combined()
    if not os.path.isfile(combined):
        sys.exit("找不到合并包：%s" % combined)

    with zipfile.ZipFile(combined) as z:
        names = z.namelist()
        # 合并包必须两套元数据都在，否则拆出来的东西不完整
        for required in ("fabric.mod.json", "META-INF/neoforge.mods.toml"):
            if required not in names:
                sys.exit("%s 里缺少 %s，不是合并包" % (os.path.basename(combined), required))
        fabric_meta = json.loads(z.read("fabric.mod.json").decode("utf-8"))
        entries = {name: z.read(name) for name in names}

    version = fabric_meta["version"]
    stem = "Bedrock-UI-Animations-%s" % version
    icon = fabric_meta["icon"]
    print("拆分源: %s（版本 %s，%d 个条目）" % (os.path.basename(combined), version, len(entries)))

    os.makedirs(BUILD, exist_ok=True)
    written = []
    for loader, (keep, drop) in VARIANTS.items():
        out = os.path.join(BUILD, "%s-%s.jar" % (stem, loader))
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for entry, data in entries.items():
                if entry == drop:
                    continue
                z.writestr(entry, data)
        with zipfile.ZipFile(out) as z:
            out_names = z.namelist()
        has_icon = icon in out_names
        assert keep in out_names, out
        assert drop not in out_names, out
        assert has_icon, out
        print("  %-46s %7d 字节  保留 %-26s 图标=%s"
              % (os.path.basename(out), os.path.getsize(out), keep, has_icon))
        written.append(out)

    print("完成：%d 个发布 jar -> %s" % (len(written), BUILD))
    return 0


if __name__ == "__main__":
    sys.exit(main())
