# Bedrock UI Animations

> 为《我的世界》Java 版的**容器 / 菜单界面**加上基岩版风格的**滑入滑出 + 淡入淡出**过渡动画。
> 单文件同时支持 **Fabric** 与 **NeoForge**，纯客户端，不改动存档或服务端逻辑。

A client-side mod that adds Bedrock-style slide + fade transitions to container and menu UIs.
One jar for both Fabric and NeoForge. Minecraft **26.3**.

---

## 下载与安装

1. 到 [**Releases**](https://github.com/tfgh6/bedrock-ui-animations/releases) 下载最新的
   `Bedrock-UI-Animations-x.y.z-fabric+neoforge.jar`
2. 放进 `.minecraft/mods/`（Fabric 与 NeoForge 用**同一个文件**）
3. 启动游戏 —— **不需要** Fabric API 等前置

> ⚠️ 如果装过同名的 `bedrock-ui-animations-2.0.43`，请**先删除它**再装本模组，两者会互相打架。

可选前置（装了才有额外入口，不装也完全能用）：

| 模组 | 作用 |
| --- | --- |
| [Mod Menu](https://modrinth.com/mod/modmenu) + [Cloth Config](https://modrinth.com/mod/cloth-config) | 模组列表里提供**图形化配置界面** |
| [Sodium](https://modrinth.com/mod/sodium) | 在钠的**视频设置**里多出一页「界面过渡动画」 |
| JEI / EMI / REI | 这些物品管理器的界面会被一并适配 |

## 版本号规则

每次改动 **+0.01**，按十进制进位：`1.2.9 → 1.3.0`、`1.2.8 → 1.2.9`。

## 功能

打开背包、箱子、工作台等界面时**自下而上滑入并淡入**，关闭时**向下滑出并淡出**。

与众不同的是**分层处理**：26.3 的界面分「背景层」（底板、槽位背景）与「内容层」（物品、文字、玩家模型）。
本模组把两层**一起**纳入动画，所以不会出现"物品在动、底板硬邦邦"的割裂感；同时有几样东西被**特意排除**：

| 元素 | 处理 | 原因 |
| --- | --- | --- |
| 变暗遮罩 / 背景模糊 / 菜单底衬 | 留在原地，只淡出 | 跟着滑会露出没被压暗或没被模糊的边缘（暂停菜单尤其明显） |
| 音效字幕 | 完全不动 | 字幕挂在背景层里绘制，不处理会跟着背包一起动 |
| 玩家快捷栏（HUD 那一排 + 面板里的那排槽位） | 固定为原版观感 | 这两排在关闭/切换动画期间不应变淡或位移 |
| 创造模式分类标签切换 | 物品区**原地淡入淡出** | 原版点标签是原地刷新（`selectTab → refreshCurrentTabContents`），没有界面切换 |
| 创造模式物品列表滚动 | 不做动画 | 逐格渐变在这套渲染管线里不可靠（已移除） |
| 「同界面换页」（如创造模式搜索标签 ↔ 生存背包） | 与普通界面一样做动画 | 这类**确实**会重建界面 |

关闭动画还有一个细节：**物品与文字比底板早约 8% 结束淡出**（`staggerClose`），刚好避免"底板还在、格子已经空了"的空洞，又几乎看不出先后。

## 特性一览

- **分层开关**：底板 / 遮罩 / 字幕 / 物品 / 文字 / 同类界面切换 / JEI 类叠加层
- **8 种缓动曲线**：`linear` `sine` `cubic`（默认）`quart` `quint` `expo` `circ` `back`
- **果冻回弹**：冲过静止位置再回落（默认关闭，0–100% 可调）
- **打断平滑**：动画播到一半再开关，会从当前可见状态**接着走**，不跳变
- **玩家模型**：打开时**延迟后淡入**（延迟比例可调，默认 35%），关闭时**立即隐藏**
- **物品适配**：按 26.3 的**预乘 alpha** 管线正确处理淡化，不会偏亮
- **真实环境验证**：生存背包 / 创造物品栏 / 容器界面，含 Sodium 与 Iris

## 配置

三个入口，改的是同一份文件：

- **图形界面**：模组列表 → Bedrock UI Animations → 配置（需 Mod Menu + Cloth Config）
- **Sodium 视频设置**：视频设置里的「界面过渡动画」页（需 Sodium）
- **手动编辑**：`config/ui-transitions.properties`

```properties
enabled=true                  # 总开关
durationMs=300                # 动画时长（50–2000 毫秒）
offset=120.0                  # 位移距离（像素，0 = 只淡变不位移）
curve=cubic                   # 缓动曲线
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
animateTabSwitch=true         # 分类标签切换时物品区淡入淡出
animateSameTypeSwitch=true    # 同类界面换页也做动画
hidePlayerModelOnClose=true   # 关闭界面时立即隐藏玩家模型
previewFadeDelay=35           # 打开时玩家模型延迟多久开始淡入（占动画时长 %）
overlayModsFadeOnly=true      # 装了 JEI 类模组时改为只淡变不位移
allowLookDuringClose=false    # 关闭动画期间是否允许转动视角
extraScreens=mezz.jei,dev.emi.emi,me.shedaniel.rei
excludedScreens=              # 排除某些界面
```

## 兼容性

- Minecraft **26.3**，Fabric Loader ≥ 0.16，NeoForge 26.3
- 纯客户端；与 **Sodium / Iris / Mod Menu / Cloth Config / JEI / EMI / REI** 共存（已实测）
- 采用矩阵平移 + 渲染状态颜色调制实现，**不使用局部变量捕获式注入**，对字节码校验器友好（手机端启动器如 Zalith / Pojav 也能正常加载）
- 与本仓库之外的同名模组**无任何代码关系**，是独立实现

## 从源码构建

```bash
# 需要 JDK 25（26.3 的类文件是 Java 25）与官方 26.3 客户端 jar
javac --release 21 -proc:none -cp "<客户端jar>;<依赖>" -d build/classes $(find ui-transitions/src -name '*.java')
python ui-transitions/build_jar.py   # 产出 build/ui-transitions/Bedrock-UI-Animations-<版本>-fabric+neoforge.jar
```

仓库结构：

```
ui-transitions/      模组本体（src 源码 + resources 元数据/图标）
  build_jar.py       打包脚本（含元数据自检）
  make_icon.py       图标生成脚本
visualtest/          开发用测试驱动（自动进世界、开背包、连拍截图）
verify-uit/          离线断言（状态机 / 注入目标 / 各项开关行为）
```

## 作者与许可

- 作者：**KurumiのZaphkiel**、**JiaWang-sama**
- 许可：**公共领域（[The Unlicense](LICENSE)）** —— 可随意复制、修改、商用、再发布，**无需署名或注明来源**。

欢迎提 [Issue](https://github.com/tfgh6/bedrock-ui-animations/issues) 反馈。如果某个界面该动没动、不该动却在动，请附界面名称或截图 —— 用 `extraScreens` / `excludedScreens` 可以直接调整。
