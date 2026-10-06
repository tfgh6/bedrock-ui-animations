package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 图形化配置界面（Cloth Config）。
 * 所有选项都会写回 config/ui-transitions.properties；改动立刻保存。
 */
public final class UiTransitionsConfigScreen {

    private UiTransitionsConfigScreen() {
    }

    /**
     * 构建配置界面。
     *
     * 整段包在 try/catch 里兜底：Cloth Config 的控件很多，万一某个控件在当前版本上
     * 行为不符，用户点"配置"时应该看到一个能返回的提示页，而不是直接崩在界面上。
     * （配置文件本身照常可用，手动编辑不受影响。）
     */
    public static Screen create(Screen parent) {
        TransitionConfig.ensureLoaded();
        try {
            return build(parent);
        } catch (Throwable t) {
            System.err.println("[UI Transitions] 配置界面构建失败，已回退到提示页（不影响游戏）: " + t);
            t.printStackTrace();
            return new FallbackScreen(parent);
        }
    }

    /** 兜底页：不做任何自定义绘制，提示直接写在按钮文字上 */
    private static final class FallbackScreen extends Screen {

        private final Screen parent;

        private FallbackScreen(Screen parent) {
            super(Component.literal("UI Transitions"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int width = Math.min(360, Math.max(120, this.width - 20));
            addRenderableWidget(Button.builder(
                            Component.literal("配置界面构建失败，点此返回；可直接编辑 config/ui-transitions.properties"),
                            // 26.3 的 Minecraft 没有 setScreen，只有 setScreenAndShow
                            button -> this.minecraft.setScreenAndShow(this.parent))
                    .bounds((this.width - width) / 2, this.height / 2 - 10, width, 20)
                    .build());
        }

        @Override
        public void onClose() {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    private static Screen build(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("UI Transitions 界面过渡动画"))
                .setSavingRunnable(TransitionConfig::save);

        ConfigEntryBuilder entries = builder.entryBuilder();

        // ============================================================ 动画
        ConfigCategory anim = builder.getOrCreateCategory(Component.literal("动画"));

        anim.addEntry(entries.startBooleanToggle(Component.literal("启用动画"), TransitionConfig.enabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("总开关。关闭后完全等同原版界面"))
                .setSaveConsumer(TransitionConfig::setEnabled)
                .build());

        // 用文本输入而不是下拉菜单：Cloth 的下拉菜单类会引入额外的注解依赖
        anim.addEntry(entries.startStrField(
                        Component.literal("缓动曲线（通用）"), TransitionConfig.curve().id())
                .setDefaultValue(TransitionConfig.Curve.CUBIC.id())
                .setErrorSupplier(UiTransitionsConfigScreen::curveError)
                .setTooltip(Component.literal("影响动画手感，可选："),
                        Component.literal("linear 匀速 / sine 柔和 / cubic 默认 / quart、quint 更急"),
                        Component.literal("/ expo 极快收尾 / circ 圆弧 / back 回拉一下再走"),
                        Component.literal("/ custom 自定义（用下面的「自定义曲线参数」）"))
                .setSaveConsumer(TransitionConfig::setCurveId)
                .build());

        anim.addEntry(entries.startIntSlider(Component.literal("渐入时长（毫秒）"), TransitionConfig.openDurationMs(),
                        TransitionConfig.MIN_DURATION_MS, TransitionConfig.MAX_DURATION_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_DURATION_MS)
                .setTooltip(Component.literal("打开界面时的动画时长，默认 500；太短会看起来像闪一下"),
                        Component.literal("觉得拖沓就往小调，觉得一闪而过就往大调"))
                .setSaveConsumer(TransitionConfig::setOpenDurationMs)
                .build());

        anim.addEntry(entries.startIntSlider(Component.literal("渐出时长（毫秒）"), TransitionConfig.closeDurationMs(),
                        TransitionConfig.MIN_DURATION_MS, TransitionConfig.MAX_DURATION_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_DURATION_MS)
                .setTooltip(Component.literal("关闭界面时的动画时长，默认 500。"),
                        Component.literal("很多人喜欢让关闭比打开更快一点，比如渐入 500 / 渐出 350"))
                .setSaveConsumer(TransitionConfig::setCloseDurationMs)
                .build());

        anim.addEntry(entries.startStrField(
                        Component.literal("渐入曲线（可单独设）"), TransitionConfig.openCurve().id())
                .setDefaultValue(TransitionConfig.Curve.CUBIC.id())
                .setErrorSupplier(UiTransitionsConfigScreen::curveError)
                .setTooltip(Component.literal("打开界面时用的曲线；想和渐出不一样就改这里"))
                .setSaveConsumer(TransitionConfig::setOpenCurve)
                .build());

        anim.addEntry(entries.startStrField(
                        Component.literal("渐出曲线（可单独设）"), TransitionConfig.closeCurve().id())
                .setDefaultValue(TransitionConfig.Curve.CUBIC.id())
                .setErrorSupplier(UiTransitionsConfigScreen::curveError)
                .setTooltip(Component.literal("关闭界面时用的曲线；想和渐入不一样就改这里"))
                .setSaveConsumer(TransitionConfig::setCloseCurve)
                .build());

        anim.addEntry(entries.startStrField(
                        Component.literal("自定义曲线参数"), TransitionConfig.curveCustom())
                .setDefaultValue(TransitionConfig.DEFAULT_CUSTOM_BEZIER)
                .setErrorSupplier(value -> TransitionConfig.isValidBezier(value)
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(Component.literal("格式：x1,y1,x2,y2（四个数字，逗号分隔）")))
                .setTooltip(Component.literal("把上面任意一条曲线写成 custom 就会用这四个数。"),
                        Component.literal("和 CSS 的 cubic-bezier(x1,y1,x2,y2) 是同一套："),
                        Component.literal("0.25,0.1,0.25,1 ≈ 默认手感；0,0,1,1 = 匀速；"),
                        Component.literal("0.34,1.56,0.64,1 = 带一点回弹；y 可以超过 1 做过冲"))
                .setSaveConsumer(TransitionConfig::setCurveCustom)
                .build());

        anim.addEntry(entries.startIntSlider(Component.literal("位移距离（像素）"), Math.round(TransitionConfig.offset()), 0, 400)
                .setDefaultValue(Math.round(TransitionConfig.DEFAULT_OFFSET))
                .setTooltip(Component.literal("界面滑动多少像素，默认 120；0 = 只淡入淡出"))
                .setSaveConsumer(value -> TransitionConfig.setOffset(value))
                .build());

        anim.addEntry(entries.startIntSlider(Component.literal("果冻回弹强度（%）"),
                        Math.round(TransitionConfig.jelly() * 100.0F), 0, 100)
                .setDefaultValue(0)
                .setTooltip(Component.literal("打开时冲过静止位置再回落的弹性手感，0 = 关闭（默认）。"),
                        Component.literal("觉得打开动画像果冻，就把这里保持 0"))
                .setSaveConsumer(value -> TransitionConfig.setJelly(value / 100.0F))
                .build());

        anim.addEntry(entries.startBooleanToggle(Component.literal("逐元素淡入淡出"), TransitionConfig.fade())
                .setDefaultValue(true)
                .setTooltip(Component.literal("总开关。关闭后只滑动、不改变透明度"))
                .setSaveConsumer(TransitionConfig::setFade)
                .build());

        // ============================================================ 淡入淡出细节
        ConfigCategory fadeCat = builder.getOrCreateCategory(Component.literal("淡入淡出细节"));

        fadeCat.addEntry(entries.startBooleanToggle(
                        Component.literal("遮罩随动画一起淡出"), TransitionConfig.fadeDim())
                .setDefaultValue(true)
                .setTooltip(Component.literal("界面淡出时，那层变暗的遮罩也一起变淡，世界随之变亮。"),
                        Component.literal("关掉的话遮罩全程保持最深，动画中途会显得偏黑"))
                .setSaveConsumer(TransitionConfig::setFadeDim)
                .build());

        fadeCat.addEntry(entries.startBooleanToggle(
                        Component.literal("物品图标淡入淡出"), TransitionConfig.fadeItems())
                .setDefaultValue(true)
                .setTooltip(Component.literal("背包/箱子里的物品图标是否一起淡变；关掉则物品直接出现，但仍随底板滑动"))
                .setSaveConsumer(TransitionConfig::setFadeItems)
                .build());

        fadeCat.addEntry(entries.startBooleanToggle(
                        Component.literal("文字淡入淡出"), TransitionConfig.fadeText())
                .setDefaultValue(true)
                .setTooltip(Component.literal("标题、数量等文字是否一起淡变"))
                .setSaveConsumer(TransitionConfig::setFadeText)
                .build());

        // ============================================================ 参与动画的部分
        ConfigCategory layers = builder.getOrCreateCategory(Component.literal("参与动画的部分"));

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("玩家模型跟随界面动画"), TransitionConfig.playerModelFollowsAnimation())
                .setDefaultValue(true)
                .setTooltip(Component.literal("打开（默认）：背包里的小模型和界面一起淡，不搞特殊。"),
                        Component.literal("关掉：恢复旧行为 —— 打开时延迟一会儿才浮现、关闭时立刻消失。"),
                        Component.literal("（附魔台的附魔书、地图、旗帜预览一直都是跟随动画的）"))
                .setSaveConsumer(TransitionConfig::setPlayerModelFollowsAnimation)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("关闭时内容提前淡出"), TransitionConfig.staggerClose())
                .setDefaultValue(true)
                .setTooltip(Component.literal("关闭动画里物品与文字比底板略早结束淡出，避免出现空格子"))
                .setSaveConsumer(TransitionConfig::setStaggerClose)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("玩家模型延迟淡入（%）"),
                        TransitionConfig.previewFadeDelay(), 0, 100)
                .setDefaultValue(35)
                .setTooltip(Component.literal("仅在关掉「玩家模型跟随界面动画」时才有意义："),
                        Component.literal("打开界面时玩家模型等待多久才开始淡入（占动画时长百分比）"))
                .setSaveConsumer(TransitionConfig::setPreviewFadeDelay)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("容器底板跟随动画"), TransitionConfig.animatePanel())
                .setDefaultValue(true)
                .setTooltip(Component.literal("背包/箱子的整块底板与槽位背景是否一起滑动淡变。"),
                        Component.literal("关闭 = 只有槽内的物品与文字动，底板直接出现"))
                .setSaveConsumer(TransitionConfig::setAnimatePanel)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("变暗遮罩跟随位移"), TransitionConfig.animateDim())
                .setDefaultValue(false)
                .setTooltip(Component.literal("那层变暗遮罩是否也跟着上下滑。默认关闭（保持静止），贴近基岩版观感"))
                .setSaveConsumer(TransitionConfig::setAnimateDim)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("音效字幕跟随动画"), TransitionConfig.animateSubtitles())
                .setDefaultValue(false)
                .setTooltip(Component.literal("字幕是在背景层里顺带绘制的，默认不参与动画（否则打开背包时字幕会跟着动）"))
                .setSaveConsumer(TransitionConfig::setAnimateSubtitles)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("所有界面都加动画"), TransitionConfig.animateAllScreens())
                .setDefaultValue(false)
                .setTooltip(Component.literal("默认只对容器界面 + 下面列出的额外界面生效；"),
                        Component.literal("打开后标题界面、选项界面等所有界面都会有过渡动画"))
                .setSaveConsumer(TransitionConfig::setAnimateAllScreens)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("装了 JEI 类模组时只淡变不位移"), TransitionConfig.overlayModsFadeOnly())
                .setDefaultValue(true)
                .setTooltip(Component.literal("JEI / EMI / REI 会在容器界面上叠一层固定位置的按钮，"),
                        Component.literal("它们和底板在同一条渲染层里，只能靠整个界面不滑动来让它们留在原地。"),
                        Component.literal("关掉 = 恢复滑动（那些按钮会跟着滑）"))
                .setSaveConsumer(TransitionConfig::setOverlayModsFadeOnly)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("分类标签切换动画（创造模式）"), TransitionConfig.animateTabSwitch())
                .setDefaultValue(true)
                .setTooltip(Component.literal("点创造模式物品栏的分类标签时，物品区原地淡入（不做位移）。"),
                        Component.literal("底板、标签栏、快捷栏、玩家小模型都保持不动"))
                .setSaveConsumer(TransitionConfig::setAnimateTabSwitch)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("标签切换时长（毫秒）"),
                        TransitionConfig.tabSwitchMs(),
                        TransitionConfig.MIN_TAB_SWITCH_MS, TransitionConfig.MAX_TAB_SWITCH_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_TAB_SWITCH_MS)
                .setTooltip(Component.literal("点分类标签 / 滚动物品列表时的原地淡变时长，默认 600。"),
                        Component.literal("这类淡变没有位移，太短会显得一闪而过"))
                .setSaveConsumer(TransitionConfig::setTabSwitchMs)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("滚动渐变带高度（像素）"),
                        TransitionConfig.scrollFadeBand(), 16, 300)
                .setDefaultValue(200)
                .setTooltip(Component.literal("滚动物品列表时，多高范围内的格子参与逐格渐变。"),
                        Component.literal("越大越明显；只想轻微提示就往小调"))
                .setSaveConsumer(TransitionConfig::setScrollFadeBand)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("滚动渐变最低透明度（%）"),
                        TransitionConfig.scrollFadeMin(), 0, 100)
                .setDefaultValue(0)
                .setTooltip(Component.literal("滚动时刚进入视野那一侧最淡到什么程度。"),
                        Component.literal("0 = 完全淡出（默认）；调高会含蓄一些"))
                .setSaveConsumer(TransitionConfig::setScrollFadeMin)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("关闭界面时隐藏玩家模型"), TransitionConfig.hidePlayerModelOnClose())
                .setDefaultValue(true)
                .setTooltip(Component.literal("关闭背包时，界面里的玩家小模型直接不画（不再跟着淡出）"))
                .setSaveConsumer(TransitionConfig::setHidePlayerModelOnClose)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("同类界面切换也做动画"), TransitionConfig.animateSameTypeSwitch())
                .setDefaultValue(true)
                .setTooltip(Component.literal("创造模式物品栏切换分类标签、配方书翻页这类同界面换页，"),
                        Component.literal("默认也会做过渡动画；关掉则直接切换"))
                .setSaveConsumer(TransitionConfig::setAnimateSameTypeSwitch)
                .build());

        layers.addEntry(entries.startStrList(
                        Component.literal("额外适配的界面（列表）"),
                        splitScreens(TransitionConfig.extraScreens()))
                .setDefaultValue(splitScreens(TransitionConfig.DEFAULT_EXTRA_SCREENS))
                .setExpanded(true)
                .setTooltip(Component.literal("这些界面即使不是容器界面也会有过渡动画，按前缀匹配。"),
                        Component.literal("点 + 添加一行，填类名或包名；默认已含 JEI / EMI / REI"))
                .setSaveConsumer(list -> TransitionConfig.setExtraScreens(joinScreens(list)))
                .build());

        // ============================================================ 方向
        ConfigCategory direction = builder.getOrCreateCategory(Component.literal("方向"));

        direction.addEntry(entries.startBooleanToggle(
                        Component.literal("打开时自下而上滑入"), TransitionConfig.openFromBottom())
                .setDefaultValue(true)
                .setTooltip(Component.literal("关闭则改为自上而下滑入"))
                .setSaveConsumer(value -> {
                    if (value != TransitionConfig.openFromBottom()) {
                        TransitionConfig.toggleOpenDirection();
                    }
                })
                .build());

        direction.addEntry(entries.startBooleanToggle(
                        Component.literal("关闭时向下滑出"), TransitionConfig.closeToBottom())
                .setDefaultValue(true)
                .setTooltip(Component.literal("关闭则改为向上滑出"))
                .setSaveConsumer(value -> {
                    if (value != TransitionConfig.closeToBottom()) {
                        TransitionConfig.toggleCloseDirection();
                    }
                })
                .build());

        // ============================================================ 界面开关
        ConfigCategory perScreen = builder.getOrCreateCategory(Component.literal("界面开关"));

        perScreen.addEntry(entries.startStrList(
                        Component.literal("不做动画的界面（列表）"),
                        splitScreens(TransitionConfig.excludedScreens()))
                .setDefaultValue(new java.util.ArrayList<String>())   // 必须可变：Cloth 会在默认值上增删
                .setExpanded(true)
                .setTooltip(Component.literal("想让哪个界面恢复成原版，就在这里加一行它的类名或包名。"),
                        Component.literal("按前缀匹配：写 com.example 就能整包关掉，"),
                        Component.literal("写完整类名就只关那一个界面。"),
                        Component.literal("下面列出了最近见过的界面类名，照着填即可。"))
                .setSaveConsumer(list -> TransitionConfig.setExcludedScreens(joinScreens(list)))
                .build());

        perScreen.addEntry(entries.startTextDescription(
                Component.literal("最近见过的界面（可直接复制到上面）：\n"
                        + seenScreenHint())).build());

        perScreen.addEntry(entries.startTextDescription(Component.literal(
                "提示：只有装了动画的界面才会出现在这个列表里；"
                        + "打开过某个界面之后回到这里，它就会被记下来。")).build());

        // ============================================================ 兼容性
        ConfigCategory compat = builder.getOrCreateCategory(Component.literal("兼容性"));

        compat.addEntry(entries.startTextDescription(Component.literal(
                        "提示：所有选项都会写入 config/ui-transitions.properties，改动立刻保存。"))
                .build());

        return builder.build();
    }

    /** 曲线文本框的校验：值必须是已知曲线 id */
    private static java.util.Optional<Component> curveError(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (TransitionConfig.Curve.byId(value).id().equals(normalized)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Component.literal(
                "可用值: " + String.join(" / ", TransitionConfig.Curve.ids())));
    }

    /** "a,b,c" -> ["a","b","c"]（配置文件里是逗号分隔的字符串，界面用列表更好操作） */
    private static java.util.List<String> splitScreens(String value) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (value != null) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    /** ["a","b"] -> "a,b" */
    private static String joinScreens(java.util.List<String> list) {
        if (list == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : list) {
            String trimmed = item == null ? "" : item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    /** 把运行期记录下来的界面类名拼成一段提示文字 */
    private static String seenScreenHint() {
        java.util.List<String> seen = TransitionConfig.seenScreens();
        if (seen.isEmpty()) {
            return "（还没记录到：先打开几个界面，再回来这里）";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : seen) {
            if (shown >= 14) {
                sb.append("… 共 ").append(seen.size()).append(" 个");
                break;
            }
            if (shown > 0) {
                sb.append('\n');
            }
            sb.append(name);
            shown++;
        }
        return sb.toString();
    }
}
