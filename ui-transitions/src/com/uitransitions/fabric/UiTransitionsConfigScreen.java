package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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


        // 曲线编辑器入口（点一下开新界面，图上有曲线和渐入/渐出示例）
        anim.addEntry(new CurveEditorEntry(Component.literal("▶ 打开曲线编辑器（渐入）"),
                parent, UiTransitionsCurveScreen.Target.OPEN));
        anim.addEntry(new CurveEditorEntry(Component.literal("▶ 打开曲线编辑器（渐出）"),
                parent, UiTransitionsCurveScreen.Target.CLOSE));
        anim.addEntry(entries.startTextDescription(Component.literal(
                "曲线编辑器里可以直接拖动两个控制点，右边会同步示范渐入与渐出的效果；"
                        + "点「完成」会写入并自动把该方向切到 custom。")).build());

        // ============================================================ 参与动画的部分
        ConfigCategory layers = builder.getOrCreateCategory(Component.literal("参与动画的部分"));

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("关闭时内容提前淡出"), TransitionConfig.staggerClose())
                .setDefaultValue(true)
                .setTooltip(Component.literal("关闭动画里物品与文字比底板略早结束淡出，避免出现空格子"))
                .setSaveConsumer(TransitionConfig::setStaggerClose)
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

        // ============================================================ 传送门 / 维度切换
        ConfigCategory portal = builder.getOrCreateCategory(Component.literal("传送门加载"));

        portal.addEntry(entries.startIntSlider(Component.literal("加载动画时长（毫秒）"),
                        TransitionConfig.portalDurationMs(),
                        TransitionConfig.MIN_PORTAL_DURATION_MS, TransitionConfig.MAX_PORTAL_DURATION_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_PORTAL_DURATION_MS)
                .setTooltip(Component.literal("穿过末地传送门 / 地狱门时那一下「正在下载地形」的过渡时长，"),
                        Component.literal("默认 1500 —— 比普通界面长，免得一闪而过。"),
                        Component.literal("这一项对渐入与渐出同时生效"))
                .setSaveConsumer(TransitionConfig::setPortalDurationMs)
                .build());

        portal.addEntry(entries.startBooleanToggle(
                        Component.literal("只淡入淡出（不滑动）"), TransitionConfig.portalFadeOnly())
                .setDefaultValue(true)
                .setTooltip(Component.literal("默认打开：传送门加载界面只做淡变，不做上下位移。"),
                        Component.literal("关掉 = 和普通界面一样也滑动（用上面的位移距离）"))
                .setSaveConsumer(TransitionConfig::setPortalFadeOnly)
                .build());

        portal.addEntry(entries.startTextDescription(Component.literal(
                "覆盖的界面：LevelLoadingScreen（26.3 里「正在下载地形」就是它，\n"
                        + "首次进世界与维度切换都走这个界面）以及 ProgressScreen。")).build());

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

    /** 贝塞尔参数文本框的校验 */
    private static java.util.Optional<Component> bezierError(String value) {
        return TransitionConfig.isValidBezier(value)
                ? java.util.Optional.empty()
                : java.util.Optional.of(Component.literal("格式：x1,y1,x2,y2（四个数字，逗号分隔）"));
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

    /**
     * 一行"按钮"式的配置项：点一下打开曲线编辑界面。
     *
     * Cloth Config 没有内置的按钮型条目，只能自己实现一个 AbstractConfigListEntry。
     * 为保险起见，曲线仍然保留了文本框入口（见「自定义参数」两项）——
     * 万一这个自绘条目在某些版本上表现异常，功能也不会因此不可达。
     */
    private static final class CurveEditorEntry
            extends me.shedaniel.clothconfig2.api.AbstractConfigListEntry<Object> {

        private static final int COLOR_BOX = 0xFF2B2F36;
        private static final int COLOR_BOX_HOVER = 0xFF3A4048;
        private static final int COLOR_BORDER = 0xFF6FD08C;
        private static final int COLOR_LABEL = 0xFFFFFFFF;

        private final Component label;
        private final Screen returnTo;
        private final UiTransitionsCurveScreen.Target target;
        private boolean requiresRestart;

        private CurveEditorEntry(Component label, Screen returnTo, UiTransitionsCurveScreen.Target target) {
            super(label, false);
            this.label = label;
            this.returnTo = returnTo;
            this.target = target;
        }

        @Override
        public Component getFieldName() {
            return this.label;
        }

        @Override
        public boolean isRequiresRestart() {
            return this.requiresRestart;
        }

        @Override
        public void setRequiresRestart(boolean requiresRestart) {
            this.requiresRestart = requiresRestart;
        }

        @Override
        public java.util.Optional<Object> getDefaultValue() {
            return java.util.Optional.empty();
        }

        @Override
        public Object getValue() {
            return null;
        }

        @Override
        public me.shedaniel.clothconfig2.api.AbstractConfigEntry<Object> provideReferenceEntry() {
            return this;
        }

        @Override
        public java.util.List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return java.util.Collections.emptyList();
        }

        @Override
        public java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return java.util.Collections.emptyList();
        }

        @Override
        public int getItemHeight() {
            return 24;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor extractor, int index, int y, int x,
                                       int entryWidth, int entryHeight, int mouseX, int mouseY,
                                       boolean isHovered, float delta) {
            boolean hover = mouseX >= x && mouseX <= x + entryWidth && mouseY >= y && mouseY <= y + entryHeight;
            extractor.fill(x, y, x + entryWidth, y + entryHeight, hover ? COLOR_BOX_HOVER : COLOR_BOX);
            // outline 是 (x, y, 宽, 高) —— 和 fill 的 (x0,y0,x1,y1) 不一样
            extractor.outline(x, y, entryWidth, entryHeight, COLOR_BORDER);
            extractor.centeredText(net.minecraft.client.Minecraft.getInstance().font,
                    this.label, x + entryWidth / 2, y + (entryHeight - 8) / 2, COLOR_LABEL);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0) {
                return false;
            }
            openEditor();
            return true;
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            // 键盘也能开：Tab 选中后按回车/空格（用裸键码，免得编译期依赖 lwjgl 的 GLFW 常量）
            int key = event.key();
            if (key == 257 || key == 335 || key == 32) {
                openEditor();
                return true;
            }
            return false;
        }

        /**
         * 打开编辑器。
         *
         * 用 execute 推迟一帧再切屏：点击是在 Cloth 的鼠标处理里边发生的，
         * 直接切屏等于在它的循环中途把当前界面换掉，容易被它随后的收尾逻辑覆盖掉
         * （表现就是"点了没反应"）。顺带把异常打出来，不然失败得无声无息。
         */
        private void openEditor() {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            Screen parent = this.returnTo;
            UiTransitionsCurveScreen.Target target = this.target;
            System.out.println("[UI Transitions] 曲线编辑器条目被点击 -> " + target);
            minecraft.execute(() -> {
                try {
                    minecraft.setScreenAndShow(new UiTransitionsCurveScreen(parent, target));
                    System.out.println("[UI Transitions] 已打开曲线编辑器，当前界面="
                            + (minecraft.gui.screen() == null ? "null"
                               : minecraft.gui.screen().getClass().getName()));
                } catch (Throwable t) {
                    System.out.println("[UI Transitions] 打开曲线编辑器失败: " + t);
                    t.printStackTrace();
                }
            });
        }
    }
}
