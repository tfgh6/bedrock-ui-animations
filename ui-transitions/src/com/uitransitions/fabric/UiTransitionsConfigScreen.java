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
            super(Component.translatable("ui_transitions.config.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int width = Math.min(360, Math.max(120, this.width - 20));
            addRenderableWidget(Button.builder(
                            Component.translatable("ui_transitions.config.fallback"),
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
                .setTitle(Component.translatable("ui_transitions.config.header"))
                .setSavingRunnable(TransitionConfig::save);

        ConfigEntryBuilder entries = builder.entryBuilder();

        // ============================================================ 动画
        ConfigCategory anim = builder.getOrCreateCategory(Component.translatable("ui_transitions.config.category.animation"));

        anim.addEntry(entries.startBooleanToggle(Component.translatable("ui_transitions.config.enabled"), TransitionConfig.enabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.enabled.tip"))
                .setSaveConsumer(TransitionConfig::setEnabled)
                .build());

        anim.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.offset"),
                        Math.round(TransitionConfig.offset()), 0, 400)
                .setDefaultValue(Math.round(TransitionConfig.DEFAULT_OFFSET))
                .setTooltip(Component.translatable("ui_transitions.config.offset.tip"))
                .setSaveConsumer(value -> TransitionConfig.setOffset(value))
                .build());

        anim.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.jelly"),
                        Math.round(TransitionConfig.jelly() * 100.0F), 0, 100)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("ui_transitions.config.jelly.tip"))
                .setSaveConsumer(value -> TransitionConfig.setJelly(value / 100.0F))
                .build());

        // 用文本输入而不是下拉菜单：Cloth 的下拉菜单类会引入额外的注解依赖
        anim.addEntry(entries.startStrField(
                        Component.translatable("ui_transitions.config.curve_common"), TransitionConfig.curve().id())
                .setDefaultValue(TransitionConfig.Curve.CUBIC.id())
                .setErrorSupplier(UiTransitionsConfigScreen::curveError)
                .setTooltip(Component.translatable("ui_transitions.config.curve_common.tip1"),
                        Component.translatable("ui_transitions.config.curve_common.tip2"),
                        Component.translatable("ui_transitions.config.curve_common.tip3"),
                        Component.translatable("ui_transitions.config.curve_common.tip4"))
                .setSaveConsumer(TransitionConfig::setCurveId)
                .build());

        anim.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.open_duration"), TransitionConfig.openDurationMs(),
                        TransitionConfig.MIN_DURATION_MS, TransitionConfig.MAX_DURATION_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_DURATION_MS)
                .setTooltip(Component.translatable("ui_transitions.config.open_duration.tip1"),
                        Component.translatable("ui_transitions.config.open_duration.tip2"))
                .setSaveConsumer(TransitionConfig::setOpenDurationMs)
                .build());

        anim.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.close_duration"), TransitionConfig.closeDurationMs(),
                        TransitionConfig.MIN_DURATION_MS, TransitionConfig.MAX_DURATION_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_DURATION_MS)
                .setTooltip(Component.translatable("ui_transitions.config.close_duration.tip1"),
                        Component.translatable("ui_transitions.config.close_duration.tip2"))
                .setSaveConsumer(TransitionConfig::setCloseDurationMs)
                .build());

        anim.addEntry(entries.startStrField(
                        Component.translatable("ui_transitions.config.open_curve"), TransitionConfig.openCurve().id())
                .setDefaultValue(TransitionConfig.Curve.CUBIC.id())
                .setErrorSupplier(UiTransitionsConfigScreen::curveError)
                .setTooltip(Component.translatable("ui_transitions.config.open_curve.tip"))
                .setSaveConsumer(TransitionConfig::setOpenCurve)
                .build());

        anim.addEntry(entries.startStrField(
                        Component.translatable("ui_transitions.config.close_curve"), TransitionConfig.closeCurve().id())
                .setDefaultValue(TransitionConfig.Curve.CUBIC.id())
                .setErrorSupplier(UiTransitionsConfigScreen::curveError)
                .setTooltip(Component.translatable("ui_transitions.config.close_curve.tip"))
                .setSaveConsumer(TransitionConfig::setCloseCurve)
                .build());


        // ============================================================ 参与动画的部分
        ConfigCategory layers = builder.getOrCreateCategory(Component.translatable("ui_transitions.config.category.layers"));

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.stagger_close"), TransitionConfig.staggerClose())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.stagger_close.tip"))
                .setSaveConsumer(TransitionConfig::setStaggerClose)
                .build());

        // ---- 逐元素淡变的四个细分开关（Sodium 页也有，保持一致）----
        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.fade"), TransitionConfig.fade())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.fade.tip"))
                .setSaveConsumer(TransitionConfig::setFade)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.fade_dim"), TransitionConfig.fadeDim())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.fade_dim.tip1"),
                        Component.translatable("ui_transitions.config.fade_dim.tip2"))
                .setSaveConsumer(TransitionConfig::setFadeDim)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.fade_items"), TransitionConfig.fadeItems())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.fade_items.tip"))
                .setSaveConsumer(TransitionConfig::setFadeItems)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.fade_text"), TransitionConfig.fadeText())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.fade_text.tip"))
                .setSaveConsumer(TransitionConfig::setFadeText)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.look_during_close"), TransitionConfig.allowLookDuringClose())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.look_during_close.tip1"),
                        Component.translatable("ui_transitions.config.look_during_close.tip2"))
                .setSaveConsumer(TransitionConfig::setAllowLookDuringClose)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.animate_panel"), TransitionConfig.animatePanel())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.animate_panel.tip1"),
                        Component.translatable("ui_transitions.config.animate_panel.tip2"))
                .setSaveConsumer(TransitionConfig::setAnimatePanel)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.animate_dim"), TransitionConfig.animateDim())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("ui_transitions.config.animate_dim.tip"))
                .setSaveConsumer(TransitionConfig::setAnimateDim)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.animate_subtitles"), TransitionConfig.animateSubtitles())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("ui_transitions.config.animate_subtitles.tip"))
                .setSaveConsumer(TransitionConfig::setAnimateSubtitles)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.animate_all"), TransitionConfig.animateAllScreens())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("ui_transitions.config.animate_all.tip1"),
                        Component.translatable("ui_transitions.config.animate_all.tip2"))
                .setSaveConsumer(TransitionConfig::setAnimateAllScreens)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.overlay_fade_only"), TransitionConfig.overlayModsFadeOnly())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.overlay_fade_only.tip1"),
                        Component.translatable("ui_transitions.config.overlay_fade_only.tip2"),
                        Component.translatable("ui_transitions.config.overlay_fade_only.tip3"))
                .setSaveConsumer(TransitionConfig::setOverlayModsFadeOnly)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.tab_switch"), TransitionConfig.animateTabSwitch())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.tab_switch.tip1"),
                        Component.translatable("ui_transitions.config.tab_switch.tip2"))
                .setSaveConsumer(TransitionConfig::setAnimateTabSwitch)
                .build());

        layers.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.tab_switch_ms"),
                        TransitionConfig.tabSwitchMs(),
                        TransitionConfig.MIN_TAB_SWITCH_MS, TransitionConfig.MAX_TAB_SWITCH_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_TAB_SWITCH_MS)
                .setTooltip(Component.translatable("ui_transitions.config.tab_switch_ms.tip1"),
                        Component.translatable("ui_transitions.config.tab_switch_ms.tip2"))
                .setSaveConsumer(TransitionConfig::setTabSwitchMs)
                .build());

        layers.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.scroll_band"),
                        TransitionConfig.scrollFadeBand(), 16, 300)
                .setDefaultValue(200)
                .setTooltip(Component.translatable("ui_transitions.config.scroll_band.tip1"),
                        Component.translatable("ui_transitions.config.scroll_band.tip2"))
                .setSaveConsumer(TransitionConfig::setScrollFadeBand)
                .build());

        layers.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.scroll_min"),
                        TransitionConfig.scrollFadeMin(), 0, 100)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("ui_transitions.config.scroll_min.tip1"),
                        Component.translatable("ui_transitions.config.scroll_min.tip2"))
                .setSaveConsumer(TransitionConfig::setScrollFadeMin)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.same_type"), TransitionConfig.animateSameTypeSwitch())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.same_type.tip1"),
                        Component.translatable("ui_transitions.config.same_type.tip2"))
                .setSaveConsumer(TransitionConfig::setAnimateSameTypeSwitch)
                .build());

        layers.addEntry(entries.startStrList(
                        Component.translatable("ui_transitions.config.extra_screens"),
                        splitScreens(TransitionConfig.extraScreens()))
                .setDefaultValue(splitScreens(TransitionConfig.DEFAULT_EXTRA_SCREENS))
                .setExpanded(true)
                .setTooltip(Component.translatable("ui_transitions.config.extra_screens.tip1"),
                        Component.translatable("ui_transitions.config.extra_screens.tip2"))
                .setSaveConsumer(list -> TransitionConfig.setExtraScreens(joinScreens(list)))
                .build());

        // ============================================================ 传送门 / 维度切换
        ConfigCategory portal = builder.getOrCreateCategory(Component.translatable("ui_transitions.config.category.portal"));

        portal.addEntry(entries.startIntSlider(Component.translatable("ui_transitions.config.portal_duration"),
                        TransitionConfig.portalDurationMs(),
                        TransitionConfig.MIN_PORTAL_DURATION_MS, TransitionConfig.MAX_PORTAL_DURATION_MS)
                .setDefaultValue(TransitionConfig.DEFAULT_PORTAL_DURATION_MS)
                .setTooltip(Component.translatable("ui_transitions.config.portal_duration.tip1"),
                        Component.translatable("ui_transitions.config.portal_duration.tip2"),
                        Component.translatable("ui_transitions.config.portal_duration.tip3"))
                .setSaveConsumer(TransitionConfig::setPortalDurationMs)
                .build());

        
        portal.addEntry(entries.startTextDescription(
                Component.translatable("ui_transitions.config.portal_scope")).build());

        // ============================================================ 方向
        ConfigCategory direction = builder.getOrCreateCategory(Component.translatable("ui_transitions.config.category.direction"));

        direction.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.open_from_bottom"), TransitionConfig.openFromBottom())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.open_from_bottom.tip"))
                .setSaveConsumer(value -> {
                    if (value != TransitionConfig.openFromBottom()) {
                        TransitionConfig.toggleOpenDirection();
                    }
                })
                .build());

        direction.addEntry(entries.startBooleanToggle(
                        Component.translatable("ui_transitions.config.close_to_bottom"), TransitionConfig.closeToBottom())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("ui_transitions.config.close_to_bottom.tip"))
                .setSaveConsumer(value -> {
                    if (value != TransitionConfig.closeToBottom()) {
                        TransitionConfig.toggleCloseDirection();
                    }
                })
                .build());

        // ============================================================ 界面开关
        ConfigCategory perScreen = builder.getOrCreateCategory(Component.translatable("ui_transitions.config.category.screens"));

        perScreen.addEntry(entries.startStrList(
                        Component.translatable("ui_transitions.config.excluded_screens"),
                        splitScreens(TransitionConfig.excludedScreens()))
                .setDefaultValue(new java.util.ArrayList<String>())   // 必须可变：Cloth 会在默认值上增删
                .setExpanded(true)
                .setTooltip(Component.translatable("ui_transitions.config.excluded_screens.tip1"),
                        Component.translatable("ui_transitions.config.excluded_screens.tip2"),
                        Component.translatable("ui_transitions.config.excluded_screens.tip3"),
                        Component.translatable("ui_transitions.config.excluded_screens.tip4"))
                .setSaveConsumer(list -> TransitionConfig.setExcludedScreens(joinScreens(list)))
                .build());

        // 类名又长又难拼，而这个模组自己知道运行期见过哪些界面 ——
        // 所以另配了一个**双列表界面**（在上一层的入口页里），点一下就把界面搬进/搬出排除列表。
        //
        // 这里只放一段说明、放不了一个能点的按钮：Cloth 的 ConfigEntryBuilder 没有按钮条目
        // （只有 startStrList/startSubCategory/startTextDescription 这些）。而"自绘条目 + 自己处理点击"
        // 这条路**已经栽过一次** —— 渲染正常、直接派发点击也能开，但真实鼠标点击传不到它那儿
        // （详见 UiTransitionsHubScreen 的注释）。所以入口一律用原版按钮，摆在入口页上。
        perScreen.addEntry(entries.startTextDescription(
                Component.translatable("ui_transitions.config.seen_screens.header")
                        .append("\n").append(seenScreenHint())).build());

        perScreen.addEntry(entries.startTextDescription(Component.translatable("ui_transitions.config.seen_screens.tip")).build());

        // ============================================================ 兼容性
        ConfigCategory compat = builder.getOrCreateCategory(Component.translatable("ui_transitions.config.category.compat"));

        compat.addEntry(entries.startTextDescription(Component.translatable("ui_transitions.config.saved_tip"))
                .build());

        return builder.build();
    }

    /** 曲线文本框的校验：值必须是已知曲线 id */
    private static java.util.Optional<Component> curveError(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (TransitionConfig.Curve.byId(value).id().equals(normalized)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Component.translatable("ui_transitions.config.curve_error",
                        String.join(" / ", TransitionConfig.Curve.ids())));
    }


    /**
     * "a,b,c" -> ["a","b","c"]。
     *
     * 实现已挪到 {@link TransitionConfig#splitList}：排除列表现在还有一个**双列表界面**，
     * 两边必须用同一套切分规则。各写一份的话，只要 trim / 忽略空项的做法有一点不同，
     * 就会出现"界面上加进去了、配置里其实没写"这类很难查的问题。
     */
    private static java.util.List<String> splitScreens(String value) {
        return TransitionConfig.splitList(value);
    }

    /** ["a","b"] -> "a,b" */
    private static String joinScreens(java.util.List<String> list) {
        return TransitionConfig.joinList(list);
    }

    /** 把运行期记录下来的界面类名拼成一段提示文字 */
    private static String seenScreenHint() {
        java.util.List<String> seen = TransitionConfig.seenScreens();
        if (seen.isEmpty()) {
            return Component.translatable("ui_transitions.config.seen_screens.empty").getString();
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : seen) {
            if (shown >= 14) {
                sb.append(Component.translatable("ui_transitions.config.seen_screens.more",
                        seen.size()).getString());
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
