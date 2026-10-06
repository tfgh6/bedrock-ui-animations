# Bedrock UI Animations 1.5.0

为**容器 / 菜单界面**添加过渡动画：打开时自下而上滑入并淡入，关闭时向下滑出并淡出（基岩版手感）。
**纯客户端**，同一个 jar 同时支持 **Fabric** 与 **NeoForge**。

- 产物：**一个文件，两个加载器通用**
  - `build/ui-transitions/Bedrock-UI-Animations-1.5.0-fabric+neoforge.jar`
  - 内含两套元数据：`fabric.mod.json` + `META-INF/neoforge.mods.toml`，Fabric 与 NeoForge 各读自己那份，装同一个文件即可
  - 版本约定：**每修一次 +0.01**（本次 1.5.0；1.4.4 → 1.5.0 是"逐部位曲线 / 多点曲线 / 排除界面"三块新功能，见 5.5 节）
- 图标：jar 内两处都有 —— `assets/ui_transitions/icon.png`（128×128，8bit RGBA + 透明背景）与根目录 `icon.png`；
  Fabric 走 `fabric.mod.json` 的 `icon`，NeoForge 走 `neoforge.mods.toml` 的 `logoFile`。生成脚本：`ui-transitions/make_icon.py`
- 注意：部分启动器看到 `neoforge.mods.toml` 就会把该文件标注为 NeoForge 模组（它自己的判定顺序，与能否加载无关）；
  游戏内两个加载器都能正常加载。若确实需要启动器分类也正确，可用 `build_release.py` 拆成两份（默认不这么做）。
- 名称：显示名 **Bedrock UI Animations**；内部 modId 仍是 `ui_transitions`
  （不改 id 是为了不破坏你已有的 `config/ui-transitions.properties` 和 Sodium 选项键）
- 作者：**KurumiのZaphkiel**、**JiaWang-sama**
- 目标：Minecraft **26.3**（Fabric Loader ≥ 0.16 / NeoForge 26.3）
- 源码：`ui-transitions/src/` · 元数据：`ui-transitions/resources/` · 打包：`ui-transitions/build_jar.py`
- Gradle 工程：`ui-transitions/`（Loom 1.18 + Gradle 9.7.1 + JDK 25）
- 实机截图：`build/visual-out/`（四态对比图 `montage2.png`）
- 版本号写在三处，**必须一起改**：`resources/fabric.mod.json`、`resources/META-INF/neoforge.mods.toml`、`gradle.properties`
  （`build_jar.py` 会校验前两者与 MANIFEST 一致，漏一处直接打包失败）

---

## 1. 安装

| 加载器 | 做法 |
| --- | --- |
| Fabric | 把 jar 放进 `mods/`（`fabric.mod.json` 声明 `environment: client`） |
| NeoForge | 把同一个 jar 放进 `mods/`（`META-INF/neoforge.mods.toml` 声明 `[[mixins]]`） |

同一个 jar 两套元数据并存，两个加载器各读自己那份。
模组本体**不需要**任何前置；下面两个只是让「图形化设置界面」可用（可选）：

| 可选前置 | 作用 |
| --- | --- |
| **Mod Menu** | 在模组列表里出现「配置」按钮 |
| **Cloth Config API** | 提供配置界面本体 |

两个都没装也能正常用：直接编辑 `config/ui-transitions.properties` 即可（缺 Cloth Config 时不会崩，只是不显示配置界面）。

**自检**：日志里应该出现两行（本项目已实测出现）：

```
[UI Transitions] 配置已加载 (...) enabled=true duration=300ms offset=120px fade=true ...
[UI Transitions] 过渡动画已生效（首个界面: ChestScreen）
```

只有第一行说明 Mixin 没应用上；两行都有说明正常工作。

---

## 2. 配置

`config/ui-transitions.properties`（首次运行生成，改完重进游戏生效）：

| 键 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- |
| `enabled` | `true` | — | 总开关（关掉后完全等同原版） |
| `durationMs` | `500` | 50–5000 | 动画时长（毫秒）。原来默认 300，缓出曲线前 100ms 就冲到近 70% 不透明度，观感像"闪一下"，故调高 |
| `curve` | `cubic` | 见下 | 缓动曲线：`linear` / `sine` / `cubic` / `quart` / `quint` / `expo` / `circ` / `back` |
| `offset` | `120.0` | 0–400 | 位移像素 |
| `jelly` | `0.0` | 0–1 | **果冻回弹强度**，0 = 关闭（默认）。打开时冲过静止位置再回落，收尾精确停在原位 |
| `fade` | `true` | — | 逐元素淡入淡出总开关（关掉则只滑动） |
| `fadeDim` | `true` | — | **变暗遮罩是否随界面一起淡出**（界面淡出时世界随之变亮，动画中途不再偏黑） |
| `fadeItems` | `true` | — | 物品图标是否淡变（关掉则物品直接出现，仍随底板滑动） |
| `fadeText` | `true` | — | 文字（标题/数量等）是否淡变 |
| `openFromBottom` | `true` | — | 打开时自下而上（false = 自上而下） |
| `closeToBottom` | `true` | — | 关闭时向下滑出（false = 向上） |
| `animateAllScreens` | `false` | — | 是否给所有界面加动画 |
| `animatePanel` | `true` | — | 容器底板 / 槽位背景是否跟随动画 |
| `animateDim` | `false` | — | 变暗遮罩是否也跟着**位移**（默认静止） |
| `animateSubtitles` | `false` | — | **音效字幕是否跟随动画**（默认不动，否则打开背包时字幕会跟着动） |
| `animateSameTypeSwitch` | `false` | — | 同类界面之间的"换页"（创造模式分类标签、配方书翻页）是否也做动画；默认直接切换 |
| `overlayModsFadeOnly` | `true` | — | 装了 JEI / EMI / REI 时改为**只淡变不位移**，让它们叠在容器界面上的固定按钮留在原地 |
| `extraScreens` | `mezz.jei,dev.emi.emi,me.shedaniel.rei` | — | 额外适配的界面（类名或包名前缀，逗号分隔），默认已含 JEI / EMI / REI |
| `excludedScreens` | 空 | — | 排除的界面类名，逗号分隔 |
| `curve.<部位>.<open\|close>` | `default` | 见下 | **按部位单独配曲线**（1.5.0）。`default` = 跟随全局；写曲线 id 就是单独设。部位 id：`panel` / `dim` / `items` / `text` / `subtitles` / `tab` / `portal` |
| `curveCustom.<部位>.<open\|close>` | 空 | — | 该部位的自定义形状。贝塞尔是 `x1,y1,x2,y2`；多点曲线是 `x,y;x,y;…`（靠 `curve.<部位>` 的 id 决定怎么解析） |

> **按部位分曲线只在"不跟随全局"时才写进文件**，所以老配置不会被二十几行 `default` 淹没，
> 升级后行为与之前完全一致（全部跟随全局）。

装了 Mod Menu + Cloth Config 时，以上选项都有图形界面（模组列表 → UI Transitions → 配置），
分「动画 / 淡入淡出细节 / 参与动画的部分 / 方向 / 兼容性」五页，改动即时保存。

配置入口之上还有一层**入口页**（同一个「配置」按钮进的就是它），四个按钮：

| 按钮 | 作用 |
| --- | --- |
| 界面动画设置 | 上面那个 Cloth 配置页 |
| 曲线编辑器 — 渐入 / 渐出 | 三列的曲线编辑器：左图（+ 贝塞尔模式下的四个滑块）、中间实机预览、右「动画列表」 |
| 排除的界面 | 双列表：左「最近见过的界面」、右「已排除」，点一下即移动 |

---

## 本轮针对实机反馈的修改

| 反馈 | 原因 | 处理 |
| --- | --- | --- |
| **音效字幕会跟着背包一起动** | 字幕是在 `Screen.extractBackground` 结尾由 `Hud.extractDeferredSubtitles()` 绘制的，正好落在背景层动画窗口内 | 在该调用点前后**抵消位移并复位透明度**；新增独立开关 `animateSubtitles`（默认关 = 字幕完全不动） |
| **渐变动画时背景有点黑** | 界面淡出时那层变暗遮罩一直保持最深，动画中途就是"灰黑一片" | 新增 `fadeDim`（默认开）：遮罩随界面一起淡出，世界随之变亮；`animateDim` 仍单独控制遮罩是否**位移** |
| **要更多独立开关** | — | 新增 `curve` / `fadeDim` / `fadeItems` / `fadeText` / `animateSubtitles` / `extraScreens`，全部进图形界面 |
| **适配 JEI 之类物品管理器** | 这些界面不一定是 `AbstractContainerScreen` | 新增 `extraScreens`（类名/包名前缀匹配，默认 `mezz.jei,dev.emi.emi,me.shedaniel.rei`），它们即使不是容器界面也会走同一套动画；`animateAllScreens` 仍是"全都要"的总开关 |
| **打断动画** | 原来在动画中途再触发切换时，新动画从 0 重新开始，画面会跳一下 | 关闭/打开被打断时，按当前**可见透明度反解**新曲线的进度并回拨起点。因为曲线是镜像的，**位移也会精确接上**：实测打断瞬间接续 alpha=185（与打断前完全一致）、位移 32.99 = `120×(1−0.725)`，数学上恒等 |
| **加个曲线** | — | 新增 `curve`：`linear` / `sine` / `cubic`(默认) / `quart` / `quint` / `expo` / `circ` / `back`，图形界面里是带校验的输入框 |

### 第三轮反馈

| 反馈 | 原因 | 处理 |
| --- | --- | --- |
| **启动器读不到图标** | 原来的 PNG 是 24 位 RGB、**没有 Alpha**、背景全白：轻色主题里看着像空白，按 RGBA 解码的启动器还会直接读失败 | 重新生成为 **8bit RGBA + 透明背景**（从四边泛洪去白底，画面内部白色不受影响），并在 jar **根目录**再放一份同名图标 |
| **关闭动画最后几帧物品会闪** | 物品渲染状态没被登记时（例如状态在动画开始前就建立）会退回"完全不透明"，收尾那几帧就闪一下；另外非动画帧会残留上一帧的透明度 | `beginItemSubmit` 改为回退到**本帧动画透明度**；`beginBackgroundLayer` / `beginContentLayer` 进门先复位为 1.0，杜绝残留值把物品错误淡出 |
| **切换归类标签时有滑动效果** | 创造模式物品栏的 `selectTab` 内部就是 `Gui.setScreen(...)`，被当成了普通界面切换 | 新增"同类界面直接切换"规则（`animateSameTypeSwitch`，默认关）：同类的"换页"不做动画，直接切换 |
| **要能关掉果冻效果** | 曲线里的 `back` 会带回拉/过冲，看起来像果冻 | 新增 `jelly`（默认 **0 = 关闭**）：打开时冲过静止位置再回落的弹性手感，可 0–100% 调节；包络在两端为 0，收尾不会留下残移 |
| **JEI 固定位置的按钮让它留在原地** | JEI / EMI / REI 的角落按钮是画在容器界面**同一条渲染层**里的，无法按位置或归属单独摘出来 | 新增 `overlayModsFadeOnly`（默认开）：检测到这类模组时，界面**只淡变、不位移**，固定按钮就留在原地渐隐。想恢复滑动把它关掉即可（按钮会跟着滑） |

### 第四轮反馈

| 反馈 | 原因 | 处理 |
| --- | --- | --- |
| **背包里的玩家小模型要跟着一起渐变** | 它走画中画（PIP）通道：先渲到离屏贴图、再在**渲染阶段**贴回界面；`PictureInPictureRenderState.pose()` 恒为单位矩阵，既不在动画窗口里也不吃矩阵平移 | 新增 `PictureInPictureRendererMixin`：在"贴回界面"的 `blitTexture` 前后把本帧动画透明度交给渲染状态，离屏贴图随之一起渐变。注入验收 9/9 通过（该类 11295 → 12659 字节） |
| **关闭动画时不能立刻转视角** | 为了播完关闭动画，原版切屏被推迟，这期间容器界面仍是当前界面、鼠标还被它占着 | 新增 `allowLookDuringClose`（**默认 true**）：进入关闭动画时立刻 `grabMouse()` 把鼠标交还游戏，可以马上转视角 |
| **把配置菜单加到 Sodium 的视频设置里，风格要一致** | — | 用 Sodium 官方配置 API（入口 `sodium:config_api_user`）注册整页，控件全部由 Sodium 的 builder 生成，外观与 Sodium 现版本一致。实机截图确认：Sodium 视频设置左侧出现「UI Transitions 1.0.0 / 界面过渡动画」 |

踩到并修掉的三个坑（都曾是真实崩溃/失效）：

1. Sodium 的枚举选项要求枚举实现它的 `TextProvider` → 曲线改用整数选项 + 数值格式化；
2. Sodium 用 `Identifier` 作选项键，**不允许大写** → 键名全部改成小写下划线；
3. mixin 配置带 `package` 字段时必须写**短类名**，当时追加成了全限定名 → 变成重复前缀、整套注入全挂。

另外给 Sodium 注册整段加了异常保护：以后 API 变动只会打一行日志，不会崩游戏。



**关于"没有 UI 分层"的模组**：本模组是在屏幕的**背景层与内容层外面**各包一层，不依赖目标界面自己怎么分层——
对方把整块 UI 画在内容层也好、画在背景层也好（JEI 的面板、模组的自定义容器），都会一起动；
两层都不参与的自绘界面（例如完全自己接管渲染的界面）则自然保持原样，不会报错。

---

## 3. 实现方式（以及为什么这次不崩）


思路：**在屏幕的「背景层」与「内容层」外各包一层矩阵平移，并调制渲染状态的颜色透明度**。

26.3 的界面是分两层画的，两层都要包住，否则就会出现"物品在动、整块底板硬邦邦直接出现"：

| 层 | 内容 | 对应方法 |
| --- | --- | --- |
| 背景层 | 变暗遮罩 + **容器底板** + 槽位背景 | `extractBackground(...)`（容器界面各自重写，底板是 super 之后的 blit） |
| 内容层 | 槽内物品、标题文字等 | `extractRenderState(...)` |

| 注入点 | 作用 |
| --- | --- |
| `extractBackground(...)` 调用前后 | 压栈 → `translate(0, 位移)` → 弹栈，并设定该层透明度 → **底板跟着滑动/淡变** |
| `extractRenderState(...)` 调用前后 | 同上 → 物品与文字跟随 |
| `Screen.extractTransparentBackground(...)` 内部 | **抵消**位移、复位透明度 → 变暗遮罩保持静止（`animateDim=true` 时跳过） |
| `Gui.setScreen` HEAD（可取消） | 关闭容器时拦下原版切屏，先播滑出动画 |
| `Gui.tick` TAIL | 动画播完后补做真正的切屏 |
| `BlitRenderState` / `TiledBlitRenderState` / `ColoredRectangleRenderState` / `GuiTextRenderState` 的构造器 | 调制颜色 alpha（底板贴图、图标、文字、纯色块） |
| `GuiItemRenderState` 构造尾部 + `GuiRenderer.submitBlitFromItemAtlas` | 物品图标在图集提交阶段单独套用透明度 |

「遮罩静止」不是靠判断类型，而是靠在遮罩绘制期间把矩阵平移抵消掉：既不动原版状态、也不需要反射。

**关键差异**：所有 `@ModifyVariable` 的 `method` 都写**完整描述符**，一个重载一个处理器。
26.3 的 `BlitRenderState` 有两个构造器（姿态参数分别是 `Matrix3x2f` / `Matrix3x2fc`），
写成 `method = "<init>"` 会同时匹配两个，其中必有一个找不到目标 → Mixin 判定注入失败 → 启动崩溃。
（这正是原版 Bedrock UI Animations 在 26.3 上崩溃的原因。）

**不碰原版逻辑**：没有 `@Overwrite` / `@Redirect`，不写任何原版字段，**完全没有反射**（测试驱动里的反射只用于测试，不在模组里）。

### 独立性 / 兼容性

- **挂在所有界面的基类上**：只依赖 `Screen` 与 `Gui` 两个稳定钩子，不针对具体界面写逻辑 → 原版容器与模组新增容器都自动生效。
- **默认只动容器界面**：HUD、聊天、字幕、提示框、暂停菜单、标题界面不参与；提示框与字幕在内容层之后绘制，不会被平移。
- **动画播完后彻底退出渲染路径**：稳定状态每帧只做一次 Set 查询，不压栈、不改矩阵（见第 4 节断言 `pushCount == 0`），与 Sodium / Iris 等性能模组无叠加开销。
- **优雅降级**：`required: false` + `injectors.defaultRequire: 0`，任何目标对不上只是"没有动画"，不崩游戏；热路径全部 `try/catch`，同类错误只在日志报一次。
- **唯一的行为介入**是关闭容器时把切屏延后一个动画时长；想彻底零介入就把 `enabled=false`。

---

## 4. 离线验收（全部通过）

**① 真实 Mixin 引擎 × 官方 26.3 类**（自建 Mixin 宿主服务驱动 `MixinTransformer`）

严格模式（任一注入失败即报错）与出厂配置下，8/8 目标类全部注入成功且类体积增大：
`Screen` 32031→**35050**（含底板层、遮罩、字幕、内容层共 10 处注入）、`Gui` 25665→26900、
`BlitRenderState` 6010→7540、`TiledBlitRenderState` 7008→8581、`ColoredRectangleRenderState` 5492→7370、
`GuiTextRenderState` 2552→3732、`GuiItemRenderState` 3542→4749、`GuiRenderer` 30343→31554。

**② 状态机验证**（桩类 + 真实 JVM）：基础套件（配置读写 / 只对容器界面生效 / 打开压栈下移且接近全透明 /
结束弹栈 / 播完后不再压栈 / 关闭拦下切屏 / `tick` 补做切屏 / 物品透明度通道与复位）—— 全部通过。

**②-b 进阶套件**（本轮新增，全部通过）：

```
[OK] JEI 风格界面（extraScreens 前缀）参与；清空后不参与；排除列表优先生效
[OK] 曲线可切换：同进度位移 cubic=25.5 / linear=71.5
[OK] fadeText=false 时文字不淡变，而贴图/底板仍淡变
[OK] fadeItems=false 时物品不淡变
[OK] fadeDim=true 时遮罩随动画淡出；false 时保持最深
[OK] 字幕默认被抵消位移（层位移 118.7 → 抵消 -118.7）、且不淡出、画完复原
[OK] 打断关闭被正确拦下；接续 alpha=185（与打断前一致）、接续位移=33.0（= 120×(1−0.725)，精确接上）
[OK] 果冻关闭时位移不反向；开启后最小位移 = −5.1（确实冲过静止位置再回落）
[OK] 同类界面切换默认不压栈、不动画；打开开关后恢复动画
[OK] 未登记物品沿用本帧透明度（层内 126 / 兜底 126，不再瞬间弹回不透明）
[OK] 非动画帧兜底 = 255、且不压栈（残留透明度把物品淡出的 bug 已修）
[OK] 有 JEI 类模组时位移 = 0 且仍在淡变（固定按钮留在原地）；关掉选项后位移恢复
```

**③ 元数据校验**：`fabric.mod.json` 与 mixin 配置 JSON 解析通过；`neoforge.mods.toml` 用 `tomllib` 实际解析通过。

---

## 5. 实机验收（Fabric 26.3，真实游戏内截图）

环境：PCL2 的 `26.3-Fabric 0.19.5` 实例（MC 26.3 + Fabric Loader 0.19.5 + Fabric API 0.161.0+26.3），
Java 25，由脚本直接按版本 JSON 启动；测试用 `-Duitransitions.visualTest=true` 的截图驱动
（`visualtest/`，自动开界面 + 按时间抓帧 + 收尾退出），配置临时改为 `durationMs=2000`、`offset=200`、`animateAllScreens=true`。

**游戏内日志确认模组生效：**

```
[UI Transitions] 配置已加载 (...) enabled=true duration=2000ms offset=200px ... allScreens=true
[UI Transitions] 过渡动画已生效（首个界面: AccessibilityOnboardingScreen）
```

**抓帧结果**（四态对比图 `build/visual-out/montage2.png`）：

| 帧 | 观测到的现象 |
| --- | --- |
| `steady_*`（稳定态） | 底板不透明、居中，红/蓝条贴齐上下边缘 —— 动画结束后无残留位移、无残留透明度 |
| `open_01`（`animatePanel=true`） | **底板与内容一起**半透明并整体下移（顶红条由 y=90 下移、世界明显透过底板）→ 底板不再"硬邦邦直接出现" |
| `close_01` | 底板与内容**同步**下移 34px 并淡出 → 关闭动画两层一起走 |
| `nopanel_*`（`animatePanel=false`） | 每一帧红条都固定在 y=90..106、像素数满值（底板完全静止），而内容绿块在 1.34s 时已淡出、4.4s 时恢复 → **独立开关按预期工作** |
| `close_02` 之后 | 关闭动画（2.0s）播完才切回标题界面 → 延迟切屏按设计工作 |
| 动画结束后的帧 | 字节数完全一致，说明画面静止 —— 动画结束后确实不再介入渲染 |

**像素级客观测量**（不是肉眼判断，脚本见 `visualtest` 里的测量命令）：

| 帧 | 底板红条 | 内容绿块 |
| --- | --- | --- |
| 稳定态 | y=90..106，px=4050（原位满值） | y=270..448，px=15584（原位满值） |
| `open_01`（底板动画开） | **找不到**（已淡出/移出） | **找不到**（已淡出） |
| `close_01` | y=124..140（下移 34px） | y=304..482（同步下移 34px） |
| `nopanel_00/01`（底板动画关） | **y=90..106 始终原位满值** | 0.58s 未动；1.34s 已淡出；4.4s 恢复 |

**配置界面**：用测试驱动直接构建并打开 Cloth Config 界面，截图确认标题、四个页签
（动画 / 参与动画的部分 / 方向 / 兼容性）、各选项（启用动画、动画时长、位移距离滑块、逐元素淡入淡出）
以及 Cancel / Save & Quit 全部正常渲染。

结论：**Fabric 路径已在真实游戏里端到端验证通过**（加载 → 注入 → 底板+内容动画 → 遮罩静止 → 收尾），
新增的独立开关与图形化设置界面也都实测有效。

### NeoForge 路径（1.4.1 起已实机验证）

**2026-10-06 更新**：NeoForge 侧已经在真实环境里启动验证通过，不再是"只做结构性验证"。

验证方式见 `neotest/neoforge_test.py`：它按版本 JSON 拼出完整启动命令，
在**独立的测试游戏目录**里启动真实的 NeoForge 客户端，然后核对日志。
实测输出（NeoForge 26.3.0.48-beta / Cloth Config 26.3.159）：

```
Mod List:
    Bedrock UI Animations 1.4.1 (ui_transitions)
    Cloth Config v26.3 API 26.3.159 (cloth_config)
    Minecraft 26.3 (minecraft)
    NeoForge 26.3.0.48-beta (neoforge)

[Bedrock UI Animations] NeoForge 入口：已检测到 Cloth Config
[Bedrock UI Animations] 已注册 NeoForge 配置入口（模组列表里的配置按钮）
[UI Transitions] 配置已加载 (…/config/ui-transitions.properties) enabled=true open=500ms/cubic …
[UI Transitions] 切屏: (无) -> GenericMessageScreen
[UI Transitions] 不做动画: GenericMessageScreen —— 不是容器界面，也不在额外适配列表里
```

> 顺带说明：模组的日志走 `System.out`，**不会进 `logs/latest.log`**（那里面只有 Log4j 的输出）。
> 脚本必须另外抓进程的 stdout，否则永远等不到成功标记 —— 这点踩过。

### 两次教训（都写进了流程，不靠自觉）

**① 桩类编译通过 ≠ NeoForge 能跑。**

`tools/compile.py` 在拿不到 NeoForge 开发期 API 时会生成桩类做类型检查。
但桩类是**我们自己写的**，它只能证明"类型对得上"，证明不了方法签名、
包路径、以及 jar 会不会被 FML 接受。

1.3.0~1.4.0 的每个包都因为**桩类被打进 jar**（`net/neoforged/**`，触发 JPMS 包冲突）
而在 NeoForge 上完全无法启动，而构建日志一路绿灯 —— 只有一句"兼容性未验证"的警告飘过去。

现在：编译**优先使用本机真实 NeoForge API**（从 `libraries/` 里自动找
`neoforge-*-universal.jar` + FML loader + bus + mergetool），找不到才退回桩类，
并且会明确打印用的是哪一种。

**② 这类问题必须让构建直接失败，不能只警告。**

`build_jar.py` 新增硬闸：产物里只要出现 `com/uitransitions/` 之外的 class 就
**中止打包并退出非 0**。已用故意制造的污染验证过确实会拦下。

---

## 5.5 逐部位曲线 / 多点曲线 / 排除列表（1.5.0）

三块功能：**每个部位一条自己的渐入渐出曲线**、**鼠标拖点画曲线**、**点选式排除界面**。

### 5.5.1 做了什么

| 块 | 内容 |
| --- | --- |
| 按部位分曲线 | `TransitionConfig.Part`：底板 / 变暗遮罩 / 物品 / 文字 / 音效字幕 / 分类标签 / 传送门遮罩。每个部位 × 渐入渐出各一条曲线 id + 自定义点集（两个 `EnumMap`，不是 28 个字段）。默认全部 `default` = **跟随全局**，所以老配置行为完全不变 |
| 多点曲线 | 除了四个贝塞尔控制点，还能切到"多点"：图上点一下加点、按住拖、双击删（或 Delete），首尾固定。点集与贝塞尔控制点**共用** `*CurveCustom` 字段，靠 `curve` id 决定怎么解析 |
| 动画列表 | 曲线编辑器右列 = 全局 + 7 个部位共 8 行。点行名切编辑对象，点行首小方框在"跟随全局 / 单独设置"之间切换 |
| 排除的界面 | 独立界面（入口页第 4 个按钮）：左"最近见过的界面"、右"已排除"，点一下即移动 |

配置文件新增的键只有 `curve.<部位>.<open\|close>` 与 `curveCustom.<部位>.<open\|close>`，
而且**只写不跟随全局的那些**，老配置文件不会被 28 行 `default` 淹没。

### 5.5.2 这一轮踩到的坑（都是真实发生过的）

**① 输入框里"能点"和"算数"是两件事 —— 只灰一个按钮等于骗人。**

部位"跟随全局"时不该能改它的形状。第一版只把「多点/控制点」按钮设成 `active=false`，
**滑块、重置按钮、图上的手柄全都还能动** —— 而 `save()` 第一行就是
`if (!editable()) return;`，所以用户拖半天，点「完成」时全部无声消失。
现在四处一起灰掉，并且跟随全局时**不画手柄**：那时图上画的是全局那条曲线（灰的），
把手柄画上去会落在与曲线对不上的位置，看着能拖、其实不算数。

**② 用"另一列的位置"算自己的宽度时，赋值顺序就是正确性。**

预览宽度是 `listX - 14 - previewX` 算出来的，而那段代码一度把 `listX` 写在 `previewWidth`
**后面** —— 首次 `init()` 读到的是字段默认值 0，于是预览被 `max(110,…)` 兜成一条窄带；
`buildWidgets()` 开头又把 `widgetsDirty` 清掉，首帧不会重建，用户一进界面看到的就是窄预览，
只有切一次模式或改窗口大小才恢复。这类链式布局，顺序错了不会报错，只会难看。

**③ 别写死"够大"的阈值 —— 实机分辨率比想象的小得多。**

右列原本的条件是 `width >= 560`。实机跑起来（GUI 缩放 3 档、1280×720 窗口）
逻辑分辨率只有 **427×240**，于是动画列表**一次都没显示过**，用户根本看不到新功能。
现在门槛按"三列各自的最小宽度加起来"算，427×240 下正好放得下（图 56 / 预览 194 / 列表 128）。
同理，四个底部按钮原本按 `(width-32-24)/4` 再取 56 的下限，427 宽下会**互相压住**；
现在按下限 52 算，并把中英标签都压到四个字以内。

**④ 数值被夹到"刚好等于判据"时，功能会静默消失。**

插入点的 x 会被夹进 `[MIN_POINT_X, MAX_POINT_X]`，而插入前还要检查"与已有点至少隔开
`MIN_POINT_GAP`"。原先边界只留**一个** gap，于是夹完正好贴着端点、被判成"太近"拒绝插入 ——
**贴着图左右边缘的点击永远加不进点**。夹对了、却什么也没发生，这种最难查。
现在边界留两个 gap。这条是离线断言（喂一个越界坐标）抓出来的，肉眼绝对看不出来。

**⑤ "按旧 id 判断类型"会写出自相矛盾的配置。**

贝塞尔控制点和多点曲线共用同一个 `curveCustom` 字段，而界面允许**同一次编辑里改变类型**
（多点 ↔ 控制点）。`setPartCurveCustom` 第一版只看"这一项当前是不是 multi"：
从多点切回控制点保存时，id 仍是 `multi`、值却写成了贝塞尔格式 →
之后按 multi 解析得到空点集 → **曲线静默变成一条直线**。
现在类型**按值的格式推断**（含 `;` 就是多点，否则是四点贝塞尔），两种格式不可能混淆。

**⑥ 断言里的"预期值"也会写错 —— 而且错得比代码更像代码。**

多点编辑的离线断言第一版有三处是**测试自己错**：拿一个"先上后下"的点集去断言单调递增；
拿越界到正好夹在端点上的坐标去断言"应该加点"；以及前一轮 `excludedWorks` 留下的
`excludedSet` 缓存让被测界面被判成"不参与动画"，于是两层 alpha 都读到稳定态。
现在的写法是：取样数据自己先保证单调、夹取用真的落在内部的值、
关键断言前显式确认"这个界面确实参与动画"。

**⑦ 自己写的检查要能被证伪，否则等于没有。**

`check_lang.py` 新增动态键豁免后，我故意从 `zh_cn.json` 里删掉一个键跑了一遍 ——
必须报错才算这个豁免没写坏（第一次改动就漏了：豁免只在"孤儿键"那一条生效，
"缺失键"那条仍然会红灯）。凡是新增/修改检查逻辑，都该这样反向验一次。

**⑧ `javac` 会把 `static final` 常量内联进调用方，改了常量不重编就还是旧值。**

排查上面第 ④ 条时，我写了个小探针打印 `MIN_POINT_X`，改了源码、重编了模组，
探针却一直打印旧值 —— 因为**探针类自己没有重编**，旧值已经被内联进它的字节码了。
误判方向差点跑到"是不是有另一份 class 在前面"。结论：验证脚本改了常量之后，
相关的 class 必须一起重编（`run_verify.py` 每次都会重编核心类，所以它是对的）。

**⑨ 截图证明不了"点得到"。**

配置界面里的曲线编辑器条目渲染完全正常、直接派发点击也能开，**真实鼠标点击却传不到**；
曲线编辑器的预览宽度算错也是只有点一下才暴露（见 ②③）。
所以实机检查里加了 `curveui` 阶段：真的派发点击、真的切模式、真的加点删点，
并把布局数字打出来核对（`预览宽=110 而可用宽度=288` 这种一眼可见的错误）。

**⑩ 测试里的行号/坐标写错，看起来和代码坏了完全一样。**

`curveui` 阶段这个检查前后返工了三轮，**三次都是测试自己错**：

| 返工 | 现象 | 真实原因 |
| --- | --- | --- |
| 1 | 「点第 3 行 → 得到 DIM」 | 断言写的是 ITEMS，而按行序第 3 行确实是 DIM —— 我把 `Part` 枚举顺序记错了 |
| 2 | 同上，改成第 2 行又得到 PANEL | 行 y 用 `(row-0.5)×22` 算，正好落到上一行 |
| 3 | 「点一下没加点」 | 上一局把曲线拖过了、图上有 5 个点，随手点的位置"离已有点太近"被**合理地**拒绝 |

教训：**几何要么从运行时读出来，要么先扫一遍打印**。现在这一阶段的日志里有
`列表几何：可见行数=6 行高=22 首行 y=74`，以及逐行扫描的 `扫行 y=106 -> PANEL ✅` ——
下一轮再出问题，看一眼日志就知道是行几何错还是功能错，不用再猜。

**⑪ 鼠标键号：26.3 的 `isValidClickButton` 判的是 `button() == 1`，不是 0。**

写"点击按钮"的检查时，我用 `new MouseButtonInfo(0, 0)` 造事件，
结果每个按钮的 `mouseClicked` 都返回 false、界面纹丝不动 ——
**看起来像"入口页三个按钮全坏了"**，实际是我们造的事件被控件判成"不是有效键"直接拒绝。
（用 `javap` 看 `AbstractWidget.isValidClickButton` 的字节码才确认：`button() == 1`。）
排查过程中先单独调了一次 `onPress`，界面立刻正常打开 —— 这一步把
"鼠标事件链路被拒"和"回调本身没生效"彻底分开了，否则很容易去改根本没错的模组代码。

**⑫ 顺带发现两个"检查自己坏了"的问题（假红与假绿各一个）。**

- **假红**：`configclick` 阶段在找 `CurveEditorEntry` —— 那个 Cloth 自绘条目**早就删掉了**
  （入口改成入口页原版按钮的原因见 `UiTransitionsHubScreen` 的注释）。
  它每次运行都 ❌，看起来像功能坏了。现在换成 `hub` 阶段：点入口页的每个按钮，
  看开出来的界面类名对不对 —— 那才是用户真正走的那条路。
- **假绿**：`visual_test.py` 的汇总只挑含"失败/警告/根因"的日志行，
  而驱动里大量判定是**打 ❌ 符号**的。于是那一次 `configclick` 明明 ❌ 了，
  汇总却打印"（没有失败/警告）"，全靠去看截图文件名才发现。
  现在汇总同时认 ❌，并打印"驱动共做出 N 条带结论的检查"，让"一条都没做"和"全都通过"不再同形。

**⑬ `AbstractWidget.setRectangle` 是 `(x, y, 宽, 高)` —— 参数顺序搞反了一次。**

新界面的双列表左半边一直**看不见**。查出来自定义列表控件的 `layout(x, y, w, h)`
里写成 `setRectangle(x, y, w, h)`、却按 `(x, y, x2, y2)` 理解，
于是位置被设成 `(宽, 高)`、尺寸被设成 `(x2-x, y2-y)`：左列表落在 `(191,128)`、**宽只有 16 像素**。
看起来就是"根本没画出来"。现在改用 `setPosition` + `setSize` 分开写，
不再有和矩形坐标混淆的机会。同一轮还修掉了：`MIN_LIST_WIDTH` 下界会把列表顶出屏幕、
标题/副标题没有按宽度截断（文字越过边框）、底部状态行压在列表与按钮上。

---

## 6. 自行构建 / 复现验证

### 5.5.3 实机验收（1.5.0，Fabric 26.3）

`visualtest/visual_test.py --phases curveui`，真实游戏 + 真实鼠标事件派发：

```
布局：界面=427x240 图=64@x16 预览=175@x94 列表x=283 显示列表=true 预览右缘到列表=14
布局检查：预览宽度正常 ✅
列表几何：可见行数=6 行高=22 首行 y=74 滚动=0
  扫行 y=106 -> PANEL（第 1 行） ✅
  扫行 y=128 -> DIM（第 2 行） ✅
  扫行 y=150 -> ITEMS（第 3 行） ✅
点第 3 行(y=150)：部位 -> ITEMS  ✅
点小方框：own false -> true  ✅（已单独设置）
切模式前：own=true multiMode=true      模式切换后 multiMode=true  ✅
清空后在图中央点一下：点数 2 -> 3  ✅
列表滚轮：返回=true 顶部行 0 -> 1
Delete 删点：点数 7 -> 6  ✅
```

**427×240 是这台机器上 GUI 缩放 3 档时的逻辑分辨率** —— 而且这正是**最常见**的那种配置：
原版自动缩放（`guiScale:0`）在 1280×720 窗口下会选 3 档（它取"逻辑分辨率仍不小于 320×240"的最大档位），
得到的就是 427×240。所以这不是边角情况，而是普通桌面窗口的默认观感。
正是它暴露了"列表门槛写成 560、于是新功能一次都没显示过"这个问题（见 5.5.2 ③）。

这一阶段还顺带量了字宽并**直接判定**（不只打印）：
`读数排版：单行宽=124 阈值=124 图宽=64 → 两行 ✅` —— 因为"相等也算放得下"这个等号写错，
读数曾经被截成 `P1 0.25,0.10 P2`（截图里一眼可见）。现在这种截断会直接反映在日志的判定上。

可见行数 6 < 8 行，所以"分类标签/传送门"两项必须滚动才能选到 —— 这也是加滚轮 + 上下键的原因。

**入口页与排除界面**（`--phases hub`，真派发点击）：

```
点「界面动画设置」  -> ClothConfigScreen              ✅
点「曲线编辑器 — 渐入」-> UiTransitionsCurveScreen     ✅
点「排除的界面」    -> UiTransitionsExclusionsScreen  ✅
排除界面几何: 界面=427x240 左列表=16,34 191x128 右列表=219,34 191x128
```

排除界面实测截图里左边列出 6 个"最近见过的界面"（`TitleScreen` / `ClothConfigScreen` /
三个本模组界面等），右边为空并提示"一个都没排除" —— 与配置里的实际状态一致。

### 5.5.4 这一轮仍然没解决 / 需要继续盯的

| 项 | 说明 |
| --- | --- |
| **超大物品图标只淡不滑** | `OversizedItemRendererMixin` 只接了透明度、**没有对应的位移注入**（主画中画那条路有）。所以个别大图标会跟着淡、但不跟着面板滑。没实机复现过，先记在这里 |
| **部位 + 贝塞尔模式的预览** | 已修成实时（原来只有多点模式实时），但只有实机肉眼能确认手感 |
| **排除界面的前缀删除** | 右边点一下是**整条删除**（不做"反查是哪条规则覆盖了它"）。一个包名前缀覆盖很多界面，反查出来的可能不是用户点的那条 —— 删错比删不掉更难解释。这个取舍写在类注释里 |
| **`fabric/` 里的共用界面类不受 `check_shared_code.py` 保护** | 那个脚本按**目录**豁免（`fabric/` 允许用 `net.fabricmc.*`），而 Hub / 曲线编辑器 / 排除界面 / 列表控件虽然是共用代码却都放在 `fabric/` 下。历史那次"NeoForge 上配置页空白"的 bug 放到今天仍能过闸，现在只靠类注释约定 |
| **`@ModifyVariable` 靠 ordinal 定位，工具不校验** | `check_mixins.py` 能证明"注入点存在"，证明不了"ordinal 指向的参数还是颜色"。构造器参数一旦增删换序，构建仍全绿，但会把 x0/y0 当颜色去乘 alpha —— 那不是失效，是**画错** |

---

## 6. 自行构建 / 复现验证

```powershell
# 一键跑完全部离线关卡（发版前必须全绿）
& '<python>' tools\verify_all.py
& '<python>' tools\verify_all.py --neoforge   # 连真实 NeoForge 实机启动一起跑

# 各关卡也可以单独跑：
& '<python>' tools\compile.py            # 编译到 build\ui-transitions\classes
& '<python>' ui-transitions\build_jar.py # 元数据自检 + 核对 Mixin 注入目标 + 产物纯净性 + 打包
& '<python>' ui-transitions\build_release.py   # 可选：拆成 Fabric / NeoForge 两个发布 jar

# 离线：Mixin 注入目标核对（defaultRequire=0，没命中只会静默失效）
& '<python>' tools\check_mixins.py       # 解析 class 文件，核对每个注入点是否真的成立

# 离线：状态机断言（桩类的 gameDirectory 落在临时目录，不会写到仓库里）
& '<python>' tools\run_verify.py         # 编译并运行 verify-uit 下的全部断言

# 实机：真实 NeoForge 客户端启动（需要机器上装了 NeoForge）
& '<python>' neotest\neoforge_test.py            # 全自动：找安装 + 建测试目录 + 启动 + 核对日志
& '<python>' neotest\neoforge_test.py --list     # 只列出找到的 NeoForge 安装
& '<python>' neotest\neoforge_test.py --keep     # 保留现场（测试目录与日志）
& '<python>' neotest\neoforge_test.py --mc <目录> --version <版本> --jar <包>

# 实机可视化测试（Fabric 路径，推荐用它，下面那条是它的底层）
& '<python>' visualtest\visual_test.py                  # 全流程：编译 + 打包 + 启动 + 抓帧 + 汇总
& '<python>' visualtest\visual_test.py --phases curve    # 只验曲线编辑器（不用建世界，快）
& '<python>' visualtest\visual_test.py --phases curveui  # 只验曲线编辑器的**交互**（真点击 / 加点 / 删点）
& '<python>' visualtest\visual_test.py --list           # 看有哪些阶段

# D'. 底层启动器（visual_test.py 内部就是调它）
& '<python>' visualtest\launch_mc.py `
    --extra-mod build\ui-transitions\Bedrock-UI-Animations-1.3.6-fabric.jar `
    --extra-mod build\visualtest-driver.jar
```

### 实机可视化测试（`visualtest/visual_test.py`）

渲染层级、画中画、自绘界面这些东西在状态机断言里测不到，只能真的把游戏跑起来看画面。
这个脚本就是干这个的，**加新功能时也用它**：

| 步骤 | 做什么 |
| --- | --- |
| 1/4 | 编译并打包模组（`tools/compile.py` + `ui-transitions/build_jar.py`） |
| 2/4 | 用**与启动时完全同一条 classpath**编译测试驱动并打成 jar |
| 3/4 | 按 PCL2 的版本 JSON 启动 26.3 Fabric，自动进场、按脚本操作、抓帧 |
| 4/4 | 汇总截图数量、并从游戏日志里挑出失败/警告行 |

阶段用 `--phases` 选（`--list` 可查）：

| 阶段 | 覆盖 | 需要进世界 |
| --- | --- | --- |
| `panels` | 合成面板开/关动画、稳定态、`animatePanel=false` 对照、字幕探针 | 否 |
| `config` | Cloth 图形化配置界面 | 否 |
| `hub` | 入口页的按钮能不能把各自的界面打开（真派发点击；替代了已失效的 `configclick`） | 否 |
| `curve` | 曲线编辑器（渐入 / 渐出两页） | 否 |
| `curveui` | 曲线编辑器的**交互**：核对布局宽度、点动画列表换编辑对象、切「跟随/单独设置」、切多点模式、图上加点、滚轮翻页、Delete 删点 | 否 |
| `world` | 只进世界并抓一张 | 是 |
| `inventory` | 生存背包：玩家小模型（画中画）是否跟着界面动 | 是 |
| `enchant` | 附魔台：附魔书（画中画）是否跟着动、有没有被裁 | 是 |
| `creative` | 创造物品栏：分类标签切换 + 滚动逐格渐变 | 是 |
| `sodium` | Sodium 视频设置里的本模组页面 | 否 |

产物：截图在 `build/visual-out/`（连拍也归档到这里），游戏输出在 `build/mc-visualtest.log`。

几个刻意的设计，都是踩过坑之后定下来的：

* **截图在游戏内用 `Screenshot.grab` 直接抓帧**，不靠外部录像 —— 像素级准确、不掉帧、
  不受窗口遮挡影响。要判断"某个元素有没有被裁掉"这类问题，录像反而不如它。
  想看整体观感可以另外开 OBS 录，但逐帧结论以这里的截图为准。
* **`--print-classpath` 的 stdout 必须只有 classpath 一行**（诊断信息走 stderr）。
  多一个换行就会让 `-cp` 整体失效，症状是"MC 的类全都找不到"，很难往这上面想。
* **按 `fabric.mod.json` 的版本号精确挑 jar**，不按文件名排序取第一个 ——
  `build/ui-transitions/` 里会堆着历史版本，排序会拿到旧的那一个。
* **子进程一律 `-X utf8` + `PYTHONIOENCODING=utf-8`**：这些脚本都打印中文，
  Windows 上 Python 默认按 GBK 编码 stdout，不加会直接 `UnicodeEncodeError`。

B 这一步针对的是本项目最容易踩的坑：mixin 配置写的是 `defaultRequire: 0`（为了跨版本优雅降级），
代价是描述符写错、目标方法改名、调用点被删都**不会有任何报错**，只表现为"某个功能不见了"。
`tools/check_mixins.py` 自己解析 class 文件常量池与字节码，核对三件事：
目标类存在、`method = ...` 所指方法存在、以及被注入的方法体里确实有 `@At(target = ...)` 那条调用指令。
`build_jar.py` 每次打包也会自动跑一遍这个检查。

`visualtest/launch_mc.py` 直接按 PCL2 的版本 JSON 组命令启动（自动展开占位符、按架构抽取 Windows natives、
把游戏窗口从最小化恢复），不经过启动器 GUI。`--print` 是纯粹的预演，不会写任何文件。

---

## 7. 已知限制

1. **仅在 MC 26.3 上验证**。26.3 之后若原版改动 GUI 渲染管线（渲染状态类构造器签名），相关注入会失效；
   因为做了优雅降级，表现是"没有动画"而不是崩溃，日志里会有 Mixin 告警。
2. ~~**NeoForge 未实机启动过**~~ —— **已在 1.4.1 起实机验证，1.5.0 再次确认**。
   本机现在有 26.3 的 NeoForge 实例（26.3.0.48-beta + Cloth Config 26.3.159），
   `neotest/neoforge_test.py` 会真的启动客户端并核对日志。
   最新一次结果：`结果: PASS —— 模组在真实 NeoForge 26.3.0.48-beta 上加载成功`，
   命中标记 `已注册 NeoForge 配置入口`。跑法：`tools/verify_all.py --neoforge`。
   注意**只证明"能加载、配置入口注册成功"**，不验画面。
3. **哪些是"看图验证"、哪些是"逻辑验证"**，说清楚免得误会：
   - **看图验证**（实机截图）：底板与内容一起滑动/淡变、关闭动画、动画结束后静止、`animatePanel=false` 时底板静止、配置界面各选项。
   - **逻辑验证**（离线断言 + 注入验收）：音效字幕抵消、`fadeDim` 遮罩淡出、各类独立开关、曲线切换、打断接续。
     其中字幕与遮罩这两条只对**游戏内界面**生效：
     ① 字幕必须真的有一条在显示（本机测试环境无世界、探针字幕不会绘制）；
     ② 变暗遮罩只在 `isInGameUi()` 为真时绘制，而本机的测试界面不是游戏内界面（它走的是全景+模糊那一条）。
     这两处在你的手机上打开背包时应能直接验证；若字幕仍跟着动，请把界面名与配置发我。
4. **JEI / REI / EMI 只做了逻辑验证**：按类名/包名前缀匹配（默认 `mezz.jei,dev.emi.emi,me.shedaniel.rei`），
   本机没有安装这些模组，无法实测它们的界面。若某个界面没跟上或不该动，用 `extraScreens` / `excludedScreens` 增删即可，把类名发我我也可以内置默认值。
5. **图形配置入口分三处**：Fabric 侧用 Mod Menu + Cloth Config；Sodium 侧用其官方配置 API 注册整页；
   NeoForge 侧由 `UiTransitionsNeoForge` 通过 `IConfigScreenFactory` 注册同一个入口页。
   三处指向的都是**同一个入口页**（`UiTransitionsHubScreen`），所以曲线编辑器与排除界面两边都能进。
   NeoForge 侧的注册已实机确认（见第 2 条）；但**按钮点开之后的画面没有在 NeoForge 上逐一看过** ——
   Mixin 与动画本身与加载器无关，两个加载器共用同一份实现。
6. **关闭动画依赖"拦下切屏再补做"**：若玩家在动画进行中退出世界/切服务器，最坏情况是个别界面状态残留；
   动画仅 300ms，实际几乎遇不到。
7. 创造模式物品栏、村民交易等也属于容器界面，会一起参与动画；不想要就用 `excludedScreens` 排除。
