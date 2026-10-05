package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 图形化配置界面（Cloth Config）。
 * 所有选项都会写回 config/ui-transitions.properties；改动立刻保存。
 */
public final class UiTransitionsConfigScreen {

    private UiTransitionsConfigScreen() {
    }

    public static Screen create(Screen parent) {
        TransitionConfig.ensureLoaded();

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
                        Component.literal("缓动曲线"), TransitionConfig.curve().id())
                .setDefaultValue(TransitionConfig.Curve.CUBIC.id())
                .setErrorSupplier(value -> TransitionConfig.Curve.byId(value).id().equals(
                        value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT))
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(Component.literal(
                                "可用值: " + String.join(" / ", TransitionConfig.Curve.ids()))))
                .setTooltip(Component.literal("影响动画手感，可选："),
                        Component.literal("linear 匀速 / sine 柔和 / cubic 默认 / quart、quint 更急"),
                        Component.literal("/ expo 极快收尾 / circ 圆弧 / back 回拉一下再走"))
                .setSaveConsumer(TransitionConfig::setCurveId)
                .build());

        anim.addEntry(entries.startIntSlider(Component.literal("动画时长（毫秒）"), TransitionConfig.durationMs(), 50, 2000)
                .setDefaultValue(TransitionConfig.DEFAULT_DURATION_MS)
                .setTooltip(Component.literal("滑入/滑出持续的时间，默认 300"))
                .setSaveConsumer(TransitionConfig::setDurationMs)
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
                .setTooltip(Component.literal("点创造模式物品栏的分类标签时，物品区从点击方向滑入并淡入。"),
                        Component.literal("底板、标签栏、玩家小模型保持不动"))
                .setSaveConsumer(TransitionConfig::setAnimateTabSwitch)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("标签切换时长（毫秒）"),
                        TransitionConfig.tabSwitchMs(), 50, 1000)
                .setDefaultValue(300)
                .setTooltip(Component.literal("换页动画持续多久，默认 220（比开关界面的 300 更利落）"))
                .setSaveConsumer(TransitionConfig::setTabSwitchMs)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("标签切换位移（像素）"),
                        TransitionConfig.tabSlide(), 0, 200)
                .setDefaultValue(0)
                .setTooltip(Component.literal("内容横向滑入的距离，0 = 只淡入不滑动"))
                .setSaveConsumer(TransitionConfig::setTabSlide)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("滚动渐变带高度（像素）"),
                        TransitionConfig.scrollFadeBand(), 16, 160)
                .setDefaultValue(90)
                .setTooltip(Component.literal("滚动时多高范围内的格子参与渐变，越大越明显，默认 90"))
                .setSaveConsumer(TransitionConfig::setScrollFadeBand)
                .build());

        layers.addEntry(entries.startIntSlider(Component.literal("滚动渐变最低透明度（%）"),
                        TransitionConfig.scrollFadeMin(), 0, 100)
                .setDefaultValue(10)
                .setTooltip(Component.literal("边缘格子最淡到什么程度，越小越明显，默认 10"))
                .setSaveConsumer(TransitionConfig::setScrollFadeMin)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("滑入方向跟随点击"), TransitionConfig.tabFollowClick())
                .setDefaultValue(true)
                .setTooltip(Component.literal("开：点靠左的标签从左滑入；关：固定从右侧滑入"))
                .setSaveConsumer(TransitionConfig::setTabFollowClick)
                .build());

        layers.addEntry(entries.startBooleanToggle(
                        Component.literal("同类界面切换也做动画"), TransitionConfig.animateSameTypeSwitch())
                .setDefaultValue(false)
                .setTooltip(Component.literal("创造模式物品栏切换分类标签、配方书翻页这类同界面换页，默认直接切换、不做动画。"),
                        Component.literal("打开后它们也会滑入滑出"))
                .setSaveConsumer(TransitionConfig::setAnimateSameTypeSwitch)
                .build());

        layers.addEntry(entries.startStrField(
                        Component.literal("额外适配的界面（类名或包名，逗号分隔）"),
                        TransitionConfig.extraScreens())
                .setDefaultValue(TransitionConfig.DEFAULT_EXTRA_SCREENS)
                .setTooltip(Component.literal("这些界面即使不是容器界面也会有过渡动画，按前缀匹配。"),
                        Component.literal("默认已含 JEI / EMI / REI 的物品管理器界面"))
                .setSaveConsumer(TransitionConfig::setExtraScreens)
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

        // ============================================================ 兼容性
        ConfigCategory compat = builder.getOrCreateCategory(Component.literal("兼容性"));

        compat.addEntry(entries.startStrField(
                        Component.literal("排除的界面（类名或包名，逗号分隔）"),
                        TransitionConfig.excludedScreens())
                .setDefaultValue("")
                .setTooltip(Component.literal("某个界面表现异常时可以把它排除，例如 com.example.FooScreen"))
                .setSaveConsumer(TransitionConfig::setExcludedScreens)
                .build());

        compat.addEntry(entries.startTextDescription(Component.literal(
                        "提示：所有选项都会写入 config/ui-transitions.properties，改动立刻保存。"))
                .build());

        return builder.build();
    }
}
