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
 *
 * 本页的选项必须与 Cloth Config 那一页（UiTransitionsConfigScreen）**保持一致**：
 * 同一个开关在两处显示不同的默认值或说明，比少一个开关更糟。
 * 两边都只暴露"真的会生效"的选项。
 */
@ConfigEntryPointForge("ui_transitions")
public final class UiTransitionsSodiumConfig implements ConfigEntryPoint {

    private static final String MOD_ID = "ui_transitions";

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** 原样文本（曲线名之类不需要翻译的） */
    private static Component tr(String text) {
        return Component.literal(text);
    }

    /** 翻译键；键名统一加 ui_transitions. 前缀 */
    private static Component tKey(String key) {
        return Component.translatable("ui_transitions." + key);
    }

    private static String curveName(int index) {
        String[] ids = TransitionConfig.Curve.ids();
        return ids[Math.max(0, Math.min(ids.length - 1, index))];
    }

    private static int curveIndex(TransitionConfig.Curve curve) {
        String[] ids = TransitionConfig.Curve.ids();
        String wanted = curve == null ? "cubic" : curve.id();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(wanted)) {
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
        OptionGroupBuilder anim = builder.createOptionGroup().setName(tKey("sodium.group.animation"));
        anim.addOption(bool(builder, "enabled", true,
                TransitionConfig::setEnabled));

        // 曲线用整数选项（下标对应 Curve.ids()）：Sodium 的枚举选项要求枚举实现它的 TextProvider，
        // 这里不引入额外耦合，改用整数 + 数值格式化，显示效果同样是"当前曲线名"。
        // 范围跟着 Curve.ids() 走，加了 custom 之后不会再出现"下标越界"。
        String[] curveIds = TransitionConfig.Curve.ids();
        anim.addOption(builder.createIntegerOption(id("curve"))
                .setName(tKey("sodium.curve"))
                .setTooltip(tKey("sodium.curve.tip"))
                .setDefaultValue(2)
                .setRange(0, curveIds.length - 1, 1)
                .setValueFormatter(value -> tr(curveName(value)))
                .setBinding(value -> TransitionConfig.setCurveId(curveName(value)),
                        () -> curveIndex(TransitionConfig.curve()))
                .setStorageHandler(TransitionConfig::save));

        anim.addOption(intOption(builder, "duration_ms", TransitionConfig.DEFAULT_DURATION_MS,
                TransitionConfig.MIN_DURATION_MS, TransitionConfig.MAX_DURATION_MS, 10,
                TransitionConfig::setDurationMsBoth,
                value -> Component.translatable("ui_transitions.unit.ms", value)));

        anim.addOption(intOption(builder, "offset", Math.round(TransitionConfig.DEFAULT_OFFSET), 0, 400, 1,
                value -> TransitionConfig.setOffset(value),
                value -> Component.translatable("ui_transitions.unit.px", value)));

        anim.addOption(intOption(builder, "jelly", 0, 0, 100, 5,
                value -> TransitionConfig.setJelly(value / 100.0F),
                value -> tr(value + "%")));

        // ---------------------------------------------------------- 参与动画的部分
        OptionGroupBuilder layers = builder.createOptionGroup().setName(tKey("sodium.group.layers"));
        layers.addOption(bool(builder, "animate_panel", true,
                TransitionConfig::setAnimatePanel));
        layers.addOption(bool(builder, "animate_dim", false,
                TransitionConfig::setAnimateDim));
        layers.addOption(bool(builder, "animate_subtitles", false,
                TransitionConfig::setAnimateSubtitles));
        layers.addOption(bool(builder, "stagger_close", true,
                TransitionConfig::setStaggerClose));
        layers.addOption(bool(builder, "animate_tab_switch", true,
                TransitionConfig::setAnimateTabSwitch));
        layers.addOption(intOption(builder, "tab_switch_ms", TransitionConfig.DEFAULT_TAB_SWITCH_MS,
                TransitionConfig.MIN_TAB_SWITCH_MS, TransitionConfig.MAX_TAB_SWITCH_MS, 10,
                TransitionConfig::setTabSwitchMs,
                value -> Component.translatable("ui_transitions.unit.ms", value)));
        layers.addOption(intOption(builder, "scroll_fade_band", 200, 16, 300, 2,
                TransitionConfig::setScrollFadeBand,
                value -> Component.translatable("ui_transitions.unit.px", value)));
        layers.addOption(intOption(builder, "scroll_fade_min", 0, 0, 100, 5,
                TransitionConfig::setScrollFadeMin,
                value -> tr(value + "%")));
        layers.addOption(intOption(builder, "portal_duration_ms", TransitionConfig.DEFAULT_PORTAL_DURATION_MS,
                TransitionConfig.MIN_PORTAL_DURATION_MS, TransitionConfig.MAX_PORTAL_DURATION_MS, 50,
                TransitionConfig::setPortalDurationMs,
                value -> Component.translatable("ui_transitions.unit.ms", value)));
        
        // 默认值与 TransitionConfig.animateSameTypeSwitch 一致：默认是**做**动画的
        layers.addOption(bool(builder, "animate_same_type_switch", true,
                TransitionConfig::setAnimateSameTypeSwitch));
        layers.addOption(bool(builder, "open_from_bottom", true,
                value -> {
                    if (value != TransitionConfig.openFromBottom()) {
                        TransitionConfig.toggleOpenDirection();
                    }
                }));
        layers.addOption(bool(builder, "close_to_bottom", true,
                value -> {
                    if (value != TransitionConfig.closeToBottom()) {
                        TransitionConfig.toggleCloseDirection();
                    }
                }));
        layers.addOption(bool(builder, "allow_look_during_close", true,
                TransitionConfig::setAllowLookDuringClose));
        layers.addOption(bool(builder, "overlay_mods_fade_only", true,
                TransitionConfig::setOverlayModsFadeOnly));
        layers.addOption(bool(builder, "animate_all_screens", false,
                TransitionConfig::setAnimateAllScreens));

        // ---------------------------------------------------------- 淡入淡出细节
        OptionGroupBuilder fadeGroup = builder.createOptionGroup().setName(tKey("sodium.group.fade"));
        fadeGroup.addOption(bool(builder, "fade", true,
                TransitionConfig::setFade));
        fadeGroup.addOption(bool(builder, "fade_dim", true,
                TransitionConfig::setFadeDim));
        fadeGroup.addOption(bool(builder, "fade_items", true,
                TransitionConfig::setFadeItems));
        fadeGroup.addOption(bool(builder, "fade_text", true,
                TransitionConfig::setFadeText));

        // ---------------------------------------------------------- 组装页面
        OptionPageBuilder page = builder.createOptionPage().setName(tKey("sodium.group.title"));
        page.addOptionGroup(anim);
        page.addOptionGroup(layers);
        page.addOptionGroup(fadeGroup);
        mod.addPage(page);
    }

    /**
     * 必须**实时**读取配置：若在注册时捕获当时的取值，
     * Sodium 会永远显示旧值（表现为"调完再打开又是 0"），
     * 而且它保存时会把旧值写回文件，覆盖掉另一个配置界面（Cloth Config）里的修改。
     *
     * 因此这里不再额外接收一个"当前值"参数 —— 传进来也只会被忽略，容易让人误以为它生效。
     * 未列出的 key 会走 default 分支：真漏了会立刻看出来（而不是静默读到 0/false）。
     */
    private static boolean boolGetter(String key) {
        return switch (key) {
            case "enabled" -> TransitionConfig.enabled();
            case "animate_panel" -> TransitionConfig.animatePanel();
            case "animate_dim" -> TransitionConfig.animateDim();
            case "animate_subtitles" -> TransitionConfig.animateSubtitles();
            case "animate_same_type_switch" -> TransitionConfig.animateSameTypeSwitch();
            case "animate_tab_switch" -> TransitionConfig.animateTabSwitch();
            case "stagger_close" -> TransitionConfig.staggerClose();
            case "overlay_mods_fade_only" -> TransitionConfig.overlayModsFadeOnly();
            case "open_from_bottom" -> TransitionConfig.openFromBottom();
            case "close_to_bottom" -> TransitionConfig.closeToBottom();
            case "allow_look_during_close" -> TransitionConfig.allowLookDuringClose();
            case "animate_all_screens" -> TransitionConfig.animateAllScreens();
            case "fade" -> TransitionConfig.fade();
            case "fade_dim" -> TransitionConfig.fadeDim();
            case "fade_items" -> TransitionConfig.fadeItems();
            case "fade_text" -> TransitionConfig.fadeText();
            default -> unknownBool(key);
        };
    }

    private static int intGetter(String key) {
        return switch (key) {
            case "duration_ms" -> TransitionConfig.openDurationMs();
            case "offset" -> Math.round(TransitionConfig.offset());
            case "jelly" -> Math.round(TransitionConfig.jelly() * 100.0F);
            case "curve" -> curveIndex(TransitionConfig.curve());
            case "tab_switch_ms" -> TransitionConfig.tabSwitchMs();
            case "scroll_fade_band" -> TransitionConfig.scrollFadeBand();
            case "scroll_fade_min" -> TransitionConfig.scrollFadeMin();
            case "portal_duration_ms" -> TransitionConfig.portalDurationMs();
            default -> unknownInt(key);
        };
    }

    /**
     * 忘了给新选项登记 getter 时的兜底。
     *
     * 这里**不能抛异常**：Sodium 是在游戏启动末尾构建配置页的，
     * 抛出去就是"Failed to build config options"直接崩在启动画面上 ——
     * 一个漏改的分支能让整个游戏进不去（真踩过：加了 portal_duration_ms 却忘了登记）。
     * 配置页少显示一个值是可以接受的，进不去游戏不行。
     */
    private static boolean unknownBool(String key) {
        System.err.println("[UI Transitions] Sodium 配置：未登记的布尔选项 " + key + "，按 false 处理");
        return false;
    }

    private static int unknownInt(String key) {
        System.err.println("[UI Transitions] Sodium 配置：未登记的整数选项 " + key + "，按 0 处理");
        return 0;
    }

    private static BooleanOptionBuilder bool(ConfigBuilder builder, String key,
                                             boolean defaultValue,
                                             java.util.function.Consumer<Boolean> setter) {
        return builder.createBooleanOption(id(key))
                .setName(tKey("sodium." + key))
                .setTooltip(tKey("sodium." + key + ".tip"))
                .setDefaultValue(defaultValue)
                .setBinding(setter, () -> boolGetter(key))
                .setStorageHandler(TransitionConfig::save);
    }

    private static IntegerOptionBuilder intOption(ConfigBuilder builder, String key,
                                                  int defaultValue, int min, int max, int step,
                                                  java.util.function.Consumer<Integer> setter,
                                                  java.util.function.IntFunction<Component> formatter) {
        return builder.createIntegerOption(id(key))
                .setName(tKey("sodium." + key))
                .setTooltip(tKey("sodium." + key + ".tip"))
                .setDefaultValue(defaultValue)
                .setRange(min, max, step)
                .setValueFormatter(value -> formatter.apply(value))
                .setBinding(setter, () -> intGetter(key))
                .setStorageHandler(TransitionConfig::save);
    }
}
