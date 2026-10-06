# Bedrock UI Animations

> 给《我的世界》Java 版的**容器 / 菜单界面**加上基岩版风格的**滑入滑出 + 淡入淡出**过渡动画。
> 纯客户端，一个 jar 同时支持 **Fabric** 与 **NeoForge**，Minecraft **26.3**。

---

## 功能

### 界面过渡

打开背包、箱子、工作台等界面时**自下而上滑入并淡入**，关闭时**向下滑出并淡出**。

底板、槽位、物品、文字是一个整体一起动，不会出现"物品在动、底板却硬邦邦"的割裂感。

- **分层独立开关** —— 底板 / 遮罩 / 字幕 / 物品 / 文字，各自可单独关掉
- **渐入与渐出分开调** —— 时长和曲线都能分别设（例如渐入 500 / 渐出 350，关闭更利落）
- **8 种缓动曲线 + 图形化自定义** —— `linear` `sine` `cubic`（默认）`quart` `quint` `expo` `circ` `back`，
  以及 **曲线编辑器**：拖两个控制点就能调形状，右边实时示范渐入 / 渐出的效果
- **果冻回弹** —— 冲过静止位置再回落，0–100% 可调（默认关闭）
- **打断平滑** —— 动画播到一半又开关，会从当前可见状态**接着走**，不跳变
- **关闭时内容提前淡出** —— 物品与文字比底板略早结束，避免出现"底板还在、格子已经空了"的空洞
- **玩家模型跟随动画** —— 背包里的小模型和界面一起滑动、一起淡（也可切回"延迟浮现 + 关闭即隐藏"的旧手感）
- **画中画内容跟随位移** —— 附魔台的附魔书、背包布娃娃、地图、旗帜预览都会跟着界面一起滑，不会留在原地
- **传送门加载单独一套** —— 穿末地门 / 地狱门时的「正在下载地形」默认只淡入淡出、不滑动，且时长更长（1500ms）

### 原地淡变（不重建界面的那些切换）

- **创造模式分类标签** —— 点标签时物品区**原地柔和淡入**，底板与标签栏纹丝不动
- **滚动物品列表** —— **逐格渐变**：越靠近进入边越淡，新物品"浮现"进来而不是硬闪。
  渐变带高度与最低透明度都可调

### 特意不跟着动的东西

- **变暗遮罩 / 背景模糊 / 菜单底衬** —— 留在原地，只淡出
  （跟着滑会露出没被压暗、没被模糊的边缘，暂停菜单尤其明显）
- **音效字幕** —— 完全不动
- **玩家快捷栏** —— 点标签 / 滚动物品列表时**不跟着淡**，固定为原版观感

### 适配与容错

- 创造模式的搜索标签、配方书翻页、以及 **JEI / EMI / REI** 的物品管理器界面一并适配
- **附魔台的附魔书**、地图、旗帜预览都会跟界面一起淡变
- 装了 JEI 这类"在界面上叠固定按钮"的模组时，自动改为**只淡变不位移**，避免那些按钮被带走（可关）
- 某个界面该动没动、不该动却在动？「界面开关」页里逐行添加即可，支持类名与前缀

---

## 下载与安装

1. 到 [**Releases**](https://github.com/tfgh6/bedrock-ui-animations/releases) 下载
   `Bedrock-UI-Animations-x.y.z-fabric+neoforge.jar`
2. 放进 `.minecraft/mods/`
3. 启动游戏 —— **不需要** Fabric API 等前置

> 如果启动器认不出合并包，Releases 里也提供拆开的 `-fabric.jar` / `-neoforge.jar`，按你的加载器选一个即可。

可选前置（装了才有额外入口，不装也完全能用）：

| 模组 | 作用 |
| --- | --- |
| [Mod Menu](https://modrinth.com/mod/modmenu) + [Cloth Config](https://modrinth.com/mod/cloth-config) | 模组列表里提供**图形化配置界面** |
| [Sodium](https://modrinth.com/mod/sodium) | 在钠的**视频设置**里多出一页「界面过渡动画」 |
| JEI / EMI / REI | 这些物品管理器的界面会被一并适配 |

---

## 配置

三个入口，改的是同一份文件：

- **图形界面** —— 模组列表 → Bedrock UI Animations → 配置（需 Mod Menu + Cloth Config）
- **Sodium 视频设置** —— 视频设置里的「界面过渡动画」页（需 Sodium）
- **手动编辑** —— `.minecraft/config/ui-transitions.properties`

```properties
enabled=true                  # 总开关
openDurationMs=500            # 渐入（打开界面）时长（50–5000 毫秒）
closeDurationMs=500           # 渐出（关闭界面）时长（50–5000 毫秒）
offset=120.0                  # 位移距离（像素，0 = 只淡变不位移）
openCurve=cubic               # 渐入专用曲线（可与通用不同）
closeCurve=cubic              # 渐出专用曲线
openCurveCustom=0.25,0.1,0.25,1.0   # 渐入的自定义控制点 x1,y1,x2,y2
closeCurveCustom=0.25,0.1,0.25,1.0  # 渐出的自定义控制点（两个方向各一份）
jelly=0.0                     # 果冻回弹强度（0 = 关闭）
fade=true                     # 逐元素淡入淡出总开关
fadeDim=true                  # 遮罩随动画淡出
fadeItems=true                # 物品图标淡变
fadeText=true                 # 文字淡变
openFromBottom=true           # 打开时自下而上
closeToBottom=true            # 关闭时向下滑出
animatePanel=true             # 容器底板参与动画
animateDim=false              # 遮罩是否也位移（默认静止）
animateSubtitles=false        # 字幕是否参与动画（默认不动）
staggerClose=true             # 关闭时内容比底板略早淡出
animateTabSwitch=true         # 分类标签切换时物品区原地淡入
tabSwitchMs=600               # 原地淡变时长：标签切换与滚动共用（50–2000 毫秒）
scrollFadeBand=200            # 滚动逐格渐变的渐变带高度（16–300 像素）
scrollFadeMin=0               # 滚动时进入边那一侧的最低透明度（0–100 %，0 = 完全淡出）
animateSameTypeSwitch=true    # 同类界面换页也做动画（默认做）
animateAllScreens=false       # 所有界面都加动画（默认只做容器界面）
overlayModsFadeOnly=true      # 装了 JEI 类模组时改为只淡变不位移
portalDurationMs=900         # 跨维度过渡的淡出时长（0–3000 毫秒，0 = 关闭该效果）
portalFadeOnly=true           # 跨维度过渡只淡入淡出、不滑动（默认开）
allowLookDuringClose=true     # 关闭动画期间是否允许转动视角
extraScreens=mezz.jei,dev.emi.emi,me.shedaniel.rei
excludedScreens=              # 不做动画的界面（按前缀匹配，可写类名或包名）
```

> **每个界面都能单独开关**：Cloth 配置界面里有「界面开关」页，
> 用列表逐行添加要关掉的界面（写类名或包名都行），
> 并且会列出**最近见过的界面**供你直接照抄。
>
> **曲线编辑器**：模组列表 → Bedrock UI Animations → 配置，会先进入口页，
> 那里有「曲线编辑器 — 渐入 / 渐出」两个按钮。
> 图上的两个黄色方块可以直接拖；右边是**背包开关动画**的预览，按你设的真实时长与曲线循环播放。
>
> **穿传送门时那个跨维度加载界面**默认只淡入淡出、不滑动，时长单独设（默认 1500ms）。
>
> 从旧版本升级时，原来的 `durationMs` / `curve` / `curveCustom`
> 会自动套用到渐入与渐出两边。

---

## 兼容性

- Minecraft **26.3**，Fabric Loader ≥ 0.16，NeoForge 26.3
- **纯客户端**；不改动存档，也不影响服务端
- 与 **Sodium / Iris / Mod Menu / Cloth Config / JEI / EMI / REI** 共存
- 与本仓库之外的同名模组**无任何代码关系**，是独立实现；两者不要同时安装

---

## 版本号规则

每次改动 **+0.01**，按十进制进位：`1.2.9 → 1.3.0`、`1.2.8 → 1.2.9`。

---

## 作者与许可

- 作者：**KurumiのZaphkiel**、**JiaWang-sama**
- 许可：**公共领域（[The Unlicense](LICENSE)）** —— 可随意复制、修改、商用、再发布，**无需署名或注明来源**

欢迎提 [Issue](https://github.com/tfgh6/bedrock-ui-animations/issues) 反馈。
如果某个界面该动没动、不该动却在动，请附界面名称或截图。
