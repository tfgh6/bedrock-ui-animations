"""把合并包拆成 Fabric / NeoForge 两个发布 jar。

启动器是按 jar 里的加载器标记归类的：
只要看到 META-INF/neoforge.mods.toml 就会当成 NeoForge 模组（哪怕同时有 fabric.mod.json），
所以单文件"通用包"必然被误判。拆成两个 jar 后，各自只带自己那份元数据，识别与图标显示都正常。
"""
import json
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, "build", "ui-transitions")
COMBINED = os.path.join(BUILD, "UI-Transitions-1.0.0-fabric+neoforge.jar")

with zipfile.ZipFile(COMBINED) as z:
    fabric_meta = json.loads(z.read("fabric.mod.json").decode("utf-8"))
    entries = {name: z.read(name) for name in z.namelist()}

version = fabric_meta["version"]
name = "Bedrock-UI-Animations-%s" % version

VARIANTS = {
    "fabric": ("fabric.mod.json", "META-INF/neoforge.mods.toml"),
    "neoforge": ("META-INF/neoforge.mods.toml", "fabric.mod.json"),
}

for loader, (keep, drop) in VARIANTS.items():
    out = os.path.join(BUILD, "%s-%s.jar" % (name, loader))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for entry, data in entries.items():
            if entry == drop:
                continue
            z.writestr(entry, data)
    with zipfile.ZipFile(out) as z:
        names = z.namelist()
    has_icon = fabric_meta["icon"] in names
    print("  %-42s %7d 字节  保留 %-28s 图标=%s"
          % (os.path.basename(out), os.path.getsize(out), keep, has_icon))
    assert keep in names and drop not in names and has_icon, out
