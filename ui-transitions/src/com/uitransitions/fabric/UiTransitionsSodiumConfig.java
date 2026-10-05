package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 把本模组的配置页注册进 Sodium 的视频设置界面。
 *
 * 用的是 Sodium 自己的 ConfigBuilder / OptionPageBuilder 等构建器，
 * 所以外观（分组、滑块、开关、颜色主题、图标）与 Sodium 现版本完全一致，不需要自己画控件。
 *
 * 入口由 Sodium 通过 {@code sodium:config_api_user} 主动查询，
 * 因此没装 Sodium 时这个类根本不会被加载，也就不会有任何兼容性风险。
 */
@ConfigEntryPointForge("ui_transitions")
public final class UiTransitionsSodiumConfig implements ConfigEntryPoint {

    private static final String MOD_ID = "ui_transitions";

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static Component tr(String text) {
        return Component.literal(text);
    }

    private static String curveName(int index) {
        String[] ids = TransitionConfig.Curve.ids();
        return ids[Math.max(0, Math.min(ids.length - 1, index))];
    }

    private static int curveIndex(TransitionConfig.Curve curve) {
        String[] ids = TransitionConfig.Curve.ids();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(curve.id())) {
                return i;
            }
        }
        return 2;
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        // 整段包起来：Sodium 的配置 API 仍在演进，任何不匹配都只记日志，绝不能让游戏崩在启动阶段
        try {
            register(builder);
        } catch (Throwable t) {
            System.err.println("[UI Transitions] 注册 Sodium 配置页失败（不影响游戏与模组本身）: " + t);
        }
    }

    private void register(ConfigBuilder builder) {
        TransitionConfig.ensureLoaded();

        ModOptionsBuilder mod = builder.registerOwnModOptions();

        // ---------------------------------------------------------- 动画
        OptionGroupBuilder anim = builder.createOptionGroup().setName(tr("动画"));
        anim.addOption(bool(builder, "enabled", "启用动画",
                "总开关。关闭后完全等同原版界面", true,
                TransitionConfig.enabled(), TransitionConfig::setEnabled));

        // 曲线用整数选项（0..7 对应 curve 列表）：Sodium 的枚举选项要求枚举实现它的 TextProvider，
        // 这里不引入额外耦合，改用整数 + 数值格式化，显示效果同样是"当前曲线名"。
        anim.addOption(builder.createIntegerOption(id("curve"))
                .setName(tr("缓动曲线"))
                .setTooltip(tr("0 linear / 1 sine / 2 cubic（默认）/ 3 quart / 4 quint / 5 expo / 6 circ / 7 back"))
                .setDefaultValue(2)
                .setRange(0, 7, 1)
                .setValueFormatter(value -> tr(curveName(value)))
                .setBinding(value -> TransitionConfig.setCurveId(curveName(value)),
                        () -> curveIndex(TransitionConfig.curve()))
                .setStorageHandler(TransitionConfig::save));

        anim.addOption(intOption(builder, "duration_ms", "动画时长", "滑入滑出持续的时间，默认 300 毫秒",
                TransitionConfig.DEFAULT_DURATION_MS, 50, 2000, 10,
                TransitionConfig.durationMs(), TransitionConfig::setDurationMs,
                value -> tr(value + " 毫秒")));

        anim.addOption(intOption(builder, "offset", "位移距离", "界面滑动多少像素，默认 120；0 = 只淡入淡出",
                Math.round(TransitionConfig.DEFAULT_OFFSET), 0, 400, 1,
                Math.round(TransitionConfig.offset()),
                value -> TransitionConfig.setOffset(value),
                value -> tr(value + " 像素")));

        anim.addOption(intOption(builder, "jelly", "果冻回弹强度", "打开时冲过静止位置再回落的弹性手感，0 = 关闭（默认）",
                0, 0, 100, 5, Math.round(TransitionConfig.jelly() * 100.0F),
                value -> TransitionConfig.setJelly(value / 100.0F),
                value -> tr(value + "%")));

        // ---------------------------------------------------------- 参与动画的部分
        OptionGroupBuilder layers = builder.createOptionGroup().setName(tr("参与动画的部分"));
        layers.addOption(bool(builder, "animate_panel", "容器底板跟随动画",
                "背包/箱子的整块底板与槽位背景是否一起滑动淡变", true,
                TransitionConfig.animatePanel(), TransitionConfig::setAnimatePanel));
        layers.addOption(bool(builder, "animate_dim", "变暗遮罩跟随位移",
                "那层变暗遮罩是否也跟着上下滑。默认关闭（静止）", false,
                TransitionConfig.animateDim(), TransitionConfig::setAnimateDim));
        layers.addOption(bool(builder, "animate_subtitles", "音效字幕跟随动画",
                "字幕在背景层里顺带绘制，默认不参与动画（否则打开背包时字幕会跟着动）", false,
                TransitionConfig.animateSubtitles(), TransitionConfig::setAnimateSubtitles));
        layers.addOption(bool(builder, "animate_tab_switch", "分类标签切换动画",
                "点创造模式物品栏的分类标签时，物品区从点击方向滑入（底板与快捷栏不动）", true,
                TransitionConfig.animateTabSwitch(), TransitionConfig::setAnimateTabSwitch));
        layers.addOption(intOption(builder, "tab_switch_ms", "标签切换时长", "换页动画毫秒数，默认 300",
                300, 50, 1000, 10, TransitionConfig.tabSwitchMs(),
                TransitionConfig::setTabSwitchMs, value -> tr(value + " 毫秒")));
        layers.addOption(intOption(builder, "tab_slide", "标签切换位移", "内容横向滑入距离（像素），0 = 只淡入淡出（默认）",
                0, 0, 200, 4, TransitionConfig.tabSlide(),
                TransitionConfig::setTabSlide, value -> tr(value + " 像素")));
        layers.addOption(intOption(builder, "scroll_fade_band", "滚动渐变带高度",
                "滚动时多高范围内的格子参与渐变，越大越明显", 200, 16, 300, 2,
                TransitionConfig.scrollFadeBand(), TransitionConfig::setScrollFadeBand,
                value -> tr(value + " 像素")));
        layers.addOption(intOption(builder, "scroll_fade_min", "滚动渐变最低透明度",
                "边缘格子最淡到什么程度（%），越小越明显", 0, 0, 100, 5,
                TransitionConfig.scrollFadeMin(), TransitionConfig::setScrollFadeMin,
                value -> tr(value + "%")));
        layers.addOption(bool(builder, "hide_player_model_on_close", "关闭时隐藏玩家模型",
                "关闭界面时玩家小模型直接不画", true,
                TransitionConfig.hidePlayerModelOnClose(), TransitionConfig::setHidePlayerModelOnClose));
        layers.addOption(bool(builder, "tab_follow_click", "滑入方向跟随点击",
                "关掉则固定从右侧滑入", true,
                TransitionConfig.tabFollowClick(), TransitionConfig::setTabFollowClick));
        layers.addOption(bool(builder, "animate_same_type_switch", "同类界面切换也做动画",
                "创造模式分类标签、配方书翻页这类同界面换页默认直接切换", false,
                TransitionConfig.animateSameTypeSwitch(), TransitionConfig::setAnimateSameTypeSwitch));
        layers.addOption(bool(builder, "overlay_mods_fade_only", "装了 JEI 类模组时只淡变不位移",
                "JEI/EMI/REI 的固定按钮和底板在同一条渲染层里，只能靠整个界面不滑来让它们留在原地", true,
                TransitionConfig.overlayModsFadeOnly(), TransitionConfig::setOverlayModsFadeOnly));
        layers.addOption(bool(builder, "animate_all_screens", "所有界面都加动画",
                "默认只对容器界面与额外列出的界面生效", false,
                TransitionConfig.animateAllScreens(), TransitionConfig::setAnimateAllScreens));

        // ---------------------------------------------------------- 淡入淡出细节
        OptionGroupBuilder fadeGroup = builder.createOptionGroup().setName(tr("淡入淡出细节"));
        fadeGroup.addOption(bool(builder, "fade", "逐元素淡入淡出",
                "总开关。关闭后只滑动、不改变透明度", true,
                TransitionConfig.fade(), TransitionConfig::setFade));
        fadeGroup.addOption(bool(builder, "fade_dim", "遮罩随动画一起淡出",
                "界面淡出时那层变暗遮罩也一起变淡，世界随之变亮，动画中途不会偏黑", true,
                TransitionConfig.fadeDim(), TransitionConfig::setFadeDim));
        fadeGroup.addOption(bool(builder, "fade_items", "物品图标淡入淡出",
                "关掉则物品直接出现，但仍随底板滑动", true,
                TransitionConfig.fadeItems(), TransitionConfig::setFadeItems));
        fadeGroup.addOption(bool(builder, "fade_text", "文字淡入淡出",
                "标题、数量等文字是否一起淡变", true,
                TransitionConfig.fadeText(), TransitionConfig::setFadeText));

        // ---------------------------------------------------------- 组装页面
        OptionPageBuilder page = builder.createOptionPage().setName(tr("界面过渡动画"));
        page.addOptionGroup(anim);
        page.addOptionGroup(layers);
        page.addOptionGroup(fadeGroup);
        mod.addPage(page);
    }

    /**
     * 必须**实时**读取配置：若在注册时捕获当时的取值，
     * Sodium 会永远显示旧值（表现为"调完再打开又是 0"），
     * 而且它保存时会把旧值写回文件，覆盖掉另一个配置界面（Cloth Config）里的修改。
     */
    private static boolean boolGetter(String key) {
        return switch (key) {
            case "enabled" -> TransitionConfig.enabled();
            case "animate_panel" -> TransitionConfig.animatePanel();
            case "animate_dim" -> TransitionConfig.animateDim();
            case "animate_subtitles" -> TransitionConfig.animateSubtitles();
            case "animate_same_type_switch" -> TransitionConfig.animateSameTypeSwitch();
            case "animate_tab_switch" -> TransitionConfig.animateTabSwitch();
            case "hide_player_model_on_close" -> TransitionConfig.hidePlayerModelOnClose();
            case "tab_follow_click" -> TransitionConfig.tabFollowClick();
            case "overlay_mods_fade_only" -> TransitionConfig.overlayModsFadeOnly();
            case "animate_all_screens" -> TransitionConfig.animateAllScreens();
            case "fade" -> TransitionConfig.fade();
            case "fade_dim" -> TransitionConfig.fadeDim();
            case "fade_items" -> TransitionConfig.fadeItems();
            case "fade_text" -> TransitionConfig.fadeText();
            default -> true;
        };
    }

    private static int intGetter(String key) {
        return switch (key) {
            case "duration_ms" -> TransitionConfig.durationMs();
            case "offset" -> Math.round(TransitionConfig.offset());
            case "jelly" -> Math.round(TransitionConfig.jelly() * 100.0F);
            case "curve" -> curveIndex(TransitionConfig.curve());
            case "tab_switch_ms" -> TransitionConfig.tabSwitchMs();
            case "tab_slide" -> TransitionConfig.tabSlide();
            case "scroll_fade_band" -> TransitionConfig.scrollFadeBand();
            case "scroll_fade_min" -> TransitionConfig.scrollFadeMin();
            default -> 0;
        };
    }

    private static BooleanOptionBuilder bool(ConfigBuilder builder, String key, String name, String tooltip,
                                             boolean defaultValue, boolean current,
                                             java.util.function.Consumer<Boolean> setter) {
        return builder.createBooleanOption(id(key))
                .setName(tr(name))
                .setTooltip(tr(tooltip))
                .setDefaultValue(defaultValue)
                .setBinding(setter, () -> boolGetter(key))
                .setStorageHandler(TransitionConfig::save);
    }

    private static IntegerOptionBuilder intOption(ConfigBuilder builder, String key, String name, String tooltip,
                                                  int defaultValue, int min, int max, int step,
                                                  int current,
                                                  java.util.function.Consumer<Integer> setter,
                                                  java.util.function.IntFunction<Component> formatter) {
        return builder.createIntegerOption(id(key))
                .setName(tr(name))
                .setTooltip(tr(tooltip))
                .setDefaultValue(defaultValue)
                .setRange(min, max, step)
                .setValueFormatter(value -> formatter.apply(value))
                .setBinding(setter, () -> intGetter(key))
                .setStorageHandler(TransitionConfig::save);
    }
}
