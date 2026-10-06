package com.uitransitions;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * 配置：<游戏目录>/config/ui-transitions.properties
 *
 * 全部字段在首次使用时从磁盘加载一次；任何一次修改都会立刻写回。
 * 配置读取失败不影响游戏，一律回退到默认值。
 */
public final class TransitionConfig {

    /**
     * 默认动画时长。
     *
     * 300ms 起步太快：缓出曲线在前 100ms 就冲到将近 70% 的不透明度，
     * 观感上更像"闪一下"而不是"淡入"。500ms 让淡变真正看得出来。
     */
    public static final int DEFAULT_DURATION_MS = 500;
    public static final float DEFAULT_OFFSET = 120.0F;
    /** 默认额外适配的界面（物品管理器一类）：按类名/包名前缀匹配 */
    public static final String DEFAULT_EXTRA_SCREENS = "mezz.jei,dev.emi.emi,me.shedaniel.rei";

    public static final int MIN_DURATION_MS = 50;
    public static final int MAX_DURATION_MS = 5000;

    /** 原地淡变（点分类标签 / 滚动）的默认时长与范围 */
    public static final int DEFAULT_TAB_SWITCH_MS = 450;
    public static final int MIN_TAB_SWITCH_MS = 50;
    public static final int MAX_TAB_SWITCH_MS = 2000;

    private static final float MIN_OFFSET = 0.0F;
    private static final float MAX_OFFSET = 400.0F;

    private static volatile boolean loaded;

    // 热路径字段一律 volatile：渲染线程每帧都会读，不加锁也不分配
    private static volatile boolean enabled = true;
    private static volatile int durationMs = DEFAULT_DURATION_MS;
    private static volatile float offset = DEFAULT_OFFSET;
    private static volatile boolean fade = true;
    private static volatile boolean fadeDim = true;
    private static volatile boolean fadeItems = true;
    private static volatile boolean fadeText = true;
    private static volatile boolean openFromBottom = true;
    private static volatile boolean closeToBottom = true;
    private static volatile boolean animateAllScreens = false;
    private static volatile boolean animatePanel = true;
    private static volatile boolean animateDim = false;
    private static volatile boolean animateSubtitles = false;
    private static volatile boolean animateSameTypeSwitch = true;
    private static volatile float jelly = 0.0F;
    private static volatile boolean overlayModsFadeOnly = true;
    private static volatile boolean allowLookDuringClose = true;
    private static volatile boolean staggerClose = true;
    private static volatile boolean animateTabSwitch = true;
    private static volatile int tabSwitchMs = DEFAULT_TAB_SWITCH_MS;
    private static volatile int scrollFadeBand = 200;
    private static volatile int scrollFadeMin = 0;
    private static volatile boolean hidePlayerModelOnClose = true;
    private static volatile int previewFadeDelay = 35;
    private static volatile String excludedScreens = "";
    private static volatile Set<String> excludedSet = Collections.emptySet();
    private static volatile String extraScreens = DEFAULT_EXTRA_SCREENS;
    private static volatile Set<String> extraSet = Collections.emptySet();
    private static volatile String curveId = "cubic";
    /** 解析好的曲线枚举：curve() 在渲染热路径上，不能每次都解析字符串 */
    private static volatile Curve curveCache = Curve.CUBIC;
    /** 上次写盘的完整内容，用来跳过"什么都没变"的重复写 */
    private static String lastWritten = null;
    /** 上面那份内容对应的文件路径：换了游戏目录就不能再拿它当"已写过"的依据 */
    private static String lastWrittenPath = null;

    private TransitionConfig() {
    }

    // ================================================================== 缓动曲线

    /** 可选缓动曲线：曲线越"陡"，动画起步/收尾越快 */
    public enum Curve {
        LINEAR("linear"),
        SINE("sine"),
        CUBIC("cubic"),
        QUART("quart"),
        QUINT("quint"),
        EXPO("expo"),
        CIRC("circ"),
        BACK("back");

        private final String id;

        Curve(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        /** 基础缓入曲线（0..1 → 0..1） */
        private float base(float t) {
            float x = Math.max(0.0F, Math.min(1.0F, t));
            return switch (this) {
                case LINEAR -> x;
                case SINE -> 1.0F - (float) Math.cos(x * Math.PI / 2.0);
                case CUBIC -> x * x * x;
                case QUART -> x * x * x * x;
                case QUINT -> x * x * x * x * x;
                case EXPO -> x <= 0.0F ? 0.0F : (float) Math.pow(2.0, 10.0 * x - 10.0);
                case CIRC -> 1.0F - (float) Math.sqrt(Math.max(0.0, 1.0 - (double) x * x));
                case BACK -> 2.70158F * x * x * x - 1.70158F * x * x;
            };
        }

        /** 关闭用：缓入 */
        public float easeIn(float t) {
            return Math.max(0.0F, Math.min(1.0F, base(t)));
        }

        /** 打开用：缓出（基础曲线的镜像） */
        public float easeOut(float t) {
            return Math.max(0.0F, Math.min(1.0F, 1.0F - base(1.0F - t)));
        }

        public static Curve byId(String value) {
            if (value != null) {
                String trimmed = value.trim().toLowerCase(Locale.ROOT);
                for (Curve curve : values()) {
                    if (curve.id.equals(trimmed)) {
                        return curve;
                    }
                }
            }
            return CUBIC;
        }

        public static String[] ids() {
            Curve[] values = values();
            String[] ids = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                ids[i] = values[i].id;
            }
            return ids;
        }
    }

    // ================================================================== 读写

    private static File file() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        return new File(minecraft.gameDirectory, "config" + File.separator + "ui-transitions.properties");
    }

    /** 热路径：已加载时无锁直接返回；还没到能定位游戏目录的时候就直接跳过，下次再试 */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        if (Minecraft.getInstance() == null) {
            return;
        }
        loadOnce();
    }

    private static synchronized void loadOnce() {
        if (loaded) {
            return;
        }
        loaded = true;
        load();
        File file = file();
        System.out.println("[UI Transitions] 配置已加载 (" + (file == null ? "内存默认值" : file.getPath())
                + ") " + describe());
    }

    private static void load() {
        File file = file();
        if (file == null) {
            return;
        }
        Properties properties = new Properties();
        boolean existed = file.isFile();
        if (existed) {
            try (FileInputStream in = new FileInputStream(file)) {
                properties.load(in);
            } catch (Throwable ignored) {
                // 读失败就用默认值
            }
        }
        enabled = readBoolean(properties, "enabled", enabled);
        durationMs = clampDuration(readInt(properties, "durationMs", durationMs));
        offset = clampOffset(readFloat(properties, "offset", offset));
        fade = readBoolean(properties, "fade", fade);
        fadeDim = readBoolean(properties, "fadeDim", fadeDim);
        fadeItems = readBoolean(properties, "fadeItems", fadeItems);
        fadeText = readBoolean(properties, "fadeText", fadeText);
        openFromBottom = readBoolean(properties, "openFromBottom", openFromBottom);
        closeToBottom = readBoolean(properties, "closeToBottom", closeToBottom);
        animateAllScreens = readBoolean(properties, "animateAllScreens", animateAllScreens);
        animatePanel = readBoolean(properties, "animatePanel", animatePanel);
        animateDim = readBoolean(properties, "animateDim", animateDim);
        animateSubtitles = readBoolean(properties, "animateSubtitles", animateSubtitles);
        animateSameTypeSwitch = readBoolean(properties, "animateSameTypeSwitch", animateSameTypeSwitch);
        jelly = clampJelly(readFloat(properties, "jelly", jelly));
        overlayModsFadeOnly = readBoolean(properties, "overlayModsFadeOnly", overlayModsFadeOnly);
        allowLookDuringClose = readBoolean(properties, "allowLookDuringClose", allowLookDuringClose);
        staggerClose = readBoolean(properties, "staggerClose", staggerClose);
        animateTabSwitch = readBoolean(properties, "animateTabSwitch", animateTabSwitch);
        tabSwitchMs = clampTabMs(readInt(properties, "tabSwitchMs", tabSwitchMs));
        scrollFadeBand = clampBand(readInt(properties, "scrollFadeBand", scrollFadeBand));
        scrollFadeMin = clampMin(readInt(properties, "scrollFadeMin", scrollFadeMin));
        hidePlayerModelOnClose = readBoolean(properties, "hidePlayerModelOnClose", hidePlayerModelOnClose);
        previewFadeDelay = Math.max(0, Math.min(100, readInt(properties, "previewFadeDelay", previewFadeDelay)));
        excludedScreens = properties.getProperty("excludedScreens", excludedScreens);
        extraScreens = properties.getProperty("extraScreens", extraScreens);
        setCurveIdInternal(properties.getProperty("curve", curveId));
        rebuildSets();
        // 只有在文件本来就不存在时才回写（首次运行生成默认配置）。
        // 否则"读一次配置"就会重写用户的文件，把注释和未知键全丢掉。
        if (!existed) {
            save();
        }
    }

    public static synchronized void save() {
        File file = file();
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("durationMs", Integer.toString(durationMs));
        properties.setProperty("offset", Float.toString(offset));
        properties.setProperty("curve", curveId);
        properties.setProperty("fade", Boolean.toString(fade));
        properties.setProperty("fadeDim", Boolean.toString(fadeDim));
        properties.setProperty("fadeItems", Boolean.toString(fadeItems));
        properties.setProperty("fadeText", Boolean.toString(fadeText));
        properties.setProperty("openFromBottom", Boolean.toString(openFromBottom));
        properties.setProperty("closeToBottom", Boolean.toString(closeToBottom));
        properties.setProperty("animateAllScreens", Boolean.toString(animateAllScreens));
        properties.setProperty("animatePanel", Boolean.toString(animatePanel));
        properties.setProperty("animateDim", Boolean.toString(animateDim));
        properties.setProperty("animateSubtitles", Boolean.toString(animateSubtitles));
        properties.setProperty("animateSameTypeSwitch", Boolean.toString(animateSameTypeSwitch));
        properties.setProperty("jelly", Float.toString(jelly));
        properties.setProperty("overlayModsFadeOnly", Boolean.toString(overlayModsFadeOnly));
        properties.setProperty("allowLookDuringClose", Boolean.toString(allowLookDuringClose));
        properties.setProperty("staggerClose", Boolean.toString(staggerClose));
        properties.setProperty("animateTabSwitch", Boolean.toString(animateTabSwitch));
        properties.setProperty("tabSwitchMs", Integer.toString(tabSwitchMs));
        properties.setProperty("scrollFadeBand", Integer.toString(scrollFadeBand));
        properties.setProperty("scrollFadeMin", Integer.toString(scrollFadeMin));
        properties.setProperty("hidePlayerModelOnClose", Boolean.toString(hidePlayerModelOnClose));
        properties.setProperty("previewFadeDelay", Integer.toString(previewFadeDelay));
        properties.setProperty("excludedScreens", excludedScreens == null ? "" : excludedScreens);
        properties.setProperty("extraScreens", extraScreens == null ? "" : extraScreens);
        String content = renderProperties(properties);
        String path = file.getPath();
        if (content.equals(lastWritten) && path.equals(lastWrittenPath)) {
            return;      // 内容没变就不写盘：配置界面保存时会连续调用二十来次
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (Throwable ignored) {
            // 写失败不影响游戏
            return;
        }
        lastWritten = content;
        lastWrittenPath = path;
    }

    /** 渲染成最终写盘字节；单独抽出来是为了"内容相同就跳过写盘" */
    private static String renderProperties(Properties properties) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try {
            properties.store(buffer, "UI Transitions - container/menu transition animations");
        } catch (Throwable ignored) {
            return "";
        }
        return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static synchronized void resetToDefaults() {
        enabled = true;
        durationMs = DEFAULT_DURATION_MS;
        offset = DEFAULT_OFFSET;
        fade = true;
        fadeDim = true;
        fadeItems = true;
        fadeText = true;
        openFromBottom = true;
        closeToBottom = true;
        animateAllScreens = false;
        animatePanel = true;
        animateDim = false;
        animateSubtitles = false;
        animateSameTypeSwitch = true;
        jelly = 0.0F;
        overlayModsFadeOnly = true;
        allowLookDuringClose = true;
        staggerClose = true;
        animateTabSwitch = true;
        tabSwitchMs = DEFAULT_TAB_SWITCH_MS;
        scrollFadeBand = 200;
        scrollFadeMin = 0;
        hidePlayerModelOnClose = true;
        previewFadeDelay = 35;
        excludedScreens = "";
        extraScreens = DEFAULT_EXTRA_SCREENS;
        curveId = Curve.CUBIC.id();
        curveCache = Curve.CUBIC;
        rebuildSets();
        save();
    }

    // ================================================================== 取值（热路径，无锁无分配）

    public static boolean enabled() {
        return enabled;
    }

    public static int durationMs() {
        return durationMs;
    }

    public static float offset() {
        return offset;
    }

    public static boolean fade() {
        return fade;
    }

    public static boolean fadeDim() {
        return fadeDim;
    }

    public static boolean fadeItems() {
        return fadeItems;
    }

    public static boolean fadeText() {
        return fadeText;
    }

    public static boolean openFromBottom() {
        return openFromBottom;
    }

    public static boolean closeToBottom() {
        return closeToBottom;
    }

    public static boolean animateAllScreens() {
        return animateAllScreens;
    }

    public static boolean animatePanel() {
        return animatePanel;
    }

    public static boolean animateDim() {
        return animateDim;
    }

    public static boolean animateSubtitles() {
        return animateSubtitles;
    }

    /**
     * 同类界面之间直接切换（例如创造模式物品栏切换分类标签、配方书翻页）是否也做动画。
     * 默认 true：创造模式分类标签这类"换页"也做滑动过渡；不想要可以关掉。
     */
    public static boolean animateSameTypeSwitch() {
        return animateSameTypeSwitch;
    }

    /** 果冻（回弹）强度 0..1，0 = 关闭 */
    public static float jelly() {
        return jelly;
    }

    /**
     * 装了 JEI / EMI / REI 这类"在容器界面上叠一层固定按钮"的模组时，
     * 是否改成只淡变、不位移 —— 那些按钮是画在同一条渲染层里的，
     * 只能靠"整个界面不滑"来保证它们待在原地。
     */
    public static boolean overlayModsFadeOnly() {
        return overlayModsFadeOnly;
    }

    /** 关闭动画期间是否允许立刻转动视角（默认允许） */
    public static boolean allowLookDuringClose() {
        return allowLookDuringClose;
    }

    /**
     * 关闭时是否分两段消失：物品与文字先淡出，底板最后淡出。
     * 同时消失会在中途露出"空格子"，看起来像一块空洞。
     */
    public static boolean staggerClose() {
        return staggerClose;
    }

    /** 创造模式分类标签等"换页"是否做 iOS 式滑入动画 */
    public static boolean animateTabSwitch() {
        return animateTabSwitch;
    }

    /** 换页 / 滚动的原地淡变时长（毫秒） */
    public static int tabSwitchMs() {
        return tabSwitchMs;
    }

    /** 滚动逐格渐变的渐变带高度（像素）：越大，越靠进入边的格子越淡 */
    public static int scrollFadeBand() {
        return scrollFadeBand;
    }

    /** 滚动逐格渐变在进入边的最低透明度（百分比）：越小越明显 */
    public static int scrollFadeMin() {
        return scrollFadeMin;
    }

    /** 关闭界面时是否直接隐藏玩家模型（布娃娃） */
    public static boolean hidePlayerModelOnClose() {
        return hidePlayerModelOnClose;
    }

    /** 打开界面时玩家模型延迟多久才开始淡入（占动画时长的百分比） */
    public static int previewFadeDelay() {
        return previewFadeDelay;
    }

    public static String excludedScreens() {
        return excludedScreens == null ? "" : excludedScreens;
    }

    public static String extraScreens() {
        return extraScreens == null ? "" : extraScreens;
    }

    /** 热路径：直接返回缓存的枚举，不做字符串解析、不分配 */
    public static Curve curve() {
        return curveCache;
    }

    /** excludedScreens 与 extraScreens 一样按前缀匹配（写包名也能整包排除） */
    public static boolean isExcluded(String className) {
        Set<String> set = excludedSet;
        return className != null && !set.isEmpty() && matches(set, className);
    }

    /** 额外适配的界面：类名等于某一项，或以其为前缀（因此可以写包名） */
    public static boolean isExtraScreen(String className) {
        if (className == null) {
            return false;
        }
        Set<String> set = extraSet;
        if (set.isEmpty()) {
            return false;
        }
        return matches(set, className);
    }

    private static boolean matches(Set<String> set, String className) {
        if (set.contains(className)) {
            return true;
        }
        for (String prefix : set) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void rebuildSets() {
        excludedSet = parseSet(excludedScreens);
        extraSet = parseSet(extraScreens);
    }

    private static Set<String> parseSet(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    // ================================================================== 改值

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    public static synchronized void setDurationMs(int value) {
        durationMs = clampDuration(value);
        save();
    }

    public static synchronized void setOffset(float value) {
        offset = clampOffset(value);
        save();
    }

    public static synchronized void setCurve(Curve value) {
        Curve resolved = value == null ? Curve.CUBIC : value;
        curveId = resolved.id();
        curveCache = resolved;
        save();
    }

    public static synchronized void setCurveId(String value) {
        setCurve(Curve.byId(value));
    }

    /** 只写字段不存盘：给 load() 用，避免"读配置"触发一次写盘 */
    private static void setCurveIdInternal(String value) {
        Curve resolved = Curve.byId(value);
        curveId = resolved.id();
        curveCache = resolved;
    }

    public static synchronized void setFade(boolean value) {
        fade = value;
        save();
    }

    public static synchronized void setFadeDim(boolean value) {
        fadeDim = value;
        save();
    }

    public static synchronized void setFadeItems(boolean value) {
        fadeItems = value;
        save();
    }

    public static synchronized void setFadeText(boolean value) {
        fadeText = value;
        save();
    }

    public static synchronized void toggleOpenDirection() {
        openFromBottom = !openFromBottom;
        save();
    }

    public static synchronized void toggleCloseDirection() {
        closeToBottom = !closeToBottom;
        save();
    }

    public static synchronized void setAnimateAllScreens(boolean value) {
        animateAllScreens = value;
        save();
    }

    public static synchronized void setAnimatePanel(boolean value) {
        animatePanel = value;
        save();
    }

    public static synchronized void setAnimateDim(boolean value) {
        animateDim = value;
        save();
    }

    public static synchronized void setAnimateSubtitles(boolean value) {
        animateSubtitles = value;
        save();
    }

    public static synchronized void setAnimateSameTypeSwitch(boolean value) {
        animateSameTypeSwitch = value;
        save();
    }

    public static synchronized void setJelly(float value) {
        jelly = clampJelly(value);
        save();
    }

    public static synchronized void setOverlayModsFadeOnly(boolean value) {
        overlayModsFadeOnly = value;
        save();
    }

    public static synchronized void setAllowLookDuringClose(boolean value) {
        allowLookDuringClose = value;
        save();
    }

    public static synchronized void setStaggerClose(boolean value) {
        staggerClose = value;
        save();
    }

    public static synchronized void setAnimateTabSwitch(boolean value) {
        animateTabSwitch = value;
        save();
    }

    public static synchronized void setTabSwitchMs(int value) {
        tabSwitchMs = clampTabMs(value);
        save();
    }

    public static synchronized void setScrollFadeBand(int value) {
        scrollFadeBand = clampBand(value);
        save();
    }

    public static synchronized void setScrollFadeMin(int value) {
        scrollFadeMin = clampMin(value);
        save();
    }

    public static synchronized void setHidePlayerModelOnClose(boolean value) {
        hidePlayerModelOnClose = value;
        save();
    }

    public static synchronized void setPreviewFadeDelay(int value) {
        previewFadeDelay = Math.max(0, Math.min(100, value));
        save();
    }

    public static synchronized void setExcludedScreens(String value) {
        excludedScreens = value == null ? "" : value;
        rebuildSets();
        save();
    }

    public static synchronized void setExtraScreens(String value) {
        extraScreens = value == null ? "" : value;
        rebuildSets();
        save();
    }

    // ================================================================== 工具

    private static int clampDuration(int value) {
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, value));
    }

    private static float clampOffset(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT_OFFSET;
        }
        return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, value));
    }

    private static int clampBand(int value) {
        return Math.max(16, Math.min(300, value));
    }

    private static int clampMin(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clampTabMs(int value) {
        return Math.max(MIN_TAB_SWITCH_MS, Math.min(MAX_TAB_SWITCH_MS, value));
    }

    private static float clampJelly(float value) {
        if (Float.isNaN(value)) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float readFloat(Properties properties, String key, float fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(value.trim());
            return Float.isNaN(parsed) ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String describe() {
        return String.format(Locale.ROOT,
                "enabled=%s duration=%dms offset=%.0fpx curve=%s jelly=%.0f%% "
                        + "fade=%s(fadeDim=%s items=%s text=%s) openFromBottom=%s closeToBottom=%s "
                        + "allScreens=%s sameTypeSwitch=%s panel=%s dim=%s subtitles=%s",
                enabled(), durationMs(), offset(), curve().id(), jelly() * 100.0F,
                fade(), fadeDim(), fadeItems(), fadeText(),
                openFromBottom(), closeToBottom(), animateAllScreens(), animateSameTypeSwitch(),
                animatePanel(), animateDim(), animateSubtitles());
    }
}
