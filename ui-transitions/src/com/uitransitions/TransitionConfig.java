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
     * 默认动画时长（渐入与渐出各自的默认值）。
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

    /**
     * 穿越传送门（末地门 / 地狱门）时的加载界面。
     *
     * 那种界面本来就是一整块地形加载提示，跟着界面上下滑会很怪，
     * 所以默认**只淡入淡出、不位移**，而且比普通界面长一些 —— 免得一闪而过。
     */
    public static final int DEFAULT_PORTAL_DURATION_MS = 900;
    /**
     * 下限放到 0：0 表示"不要这个过渡效果"。
     * 上限从 10000 收到 3000 —— 用户反馈 1.5 秒已经嫌长，十秒没有意义。
     */
    public static final int MIN_PORTAL_DURATION_MS = 0;
    public static final int MAX_PORTAL_DURATION_MS = 3000;
    /**
     * 非零值不能低于这个数。
     *
     * 0 的语义是"关掉这个过渡"，是有意为之；但 1..250ms 的遮罩淡出在实机上就是闪一下，
     * 只会让人以为功能坏了 —— 用户调参时留下的残留值正是这个区间（真实踩过：
     * 配置里留着 100ms，把下限放开到 0 之后它就真的按 100ms 生效，看上去像"功能没了"）。
     */
    public static final int MIN_SENSIBLE_PORTAL_MS = 250;

    private static volatile int portalDurationMs = DEFAULT_PORTAL_DURATION_MS;

    public static int portalDurationMs() {
        return portalDurationMs;
    }


    public static synchronized void setPortalDurationMs(int value) {
        portalDurationMs = Math.max(MIN_PORTAL_DURATION_MS,
                Math.min(MAX_PORTAL_DURATION_MS, value));
        save();
    }

    /** 原地淡变（点分类标签 / 滚动）的默认时长与范围 */
    public static final int DEFAULT_TAB_SWITCH_MS = 600;
    public static final int MIN_TAB_SWITCH_MS = 50;
    public static final int MAX_TAB_SWITCH_MS = 2000;

    /** 自定义曲线的默认控制点（与 CSS 的 ease 接近） */
    public static final String DEFAULT_CUSTOM_BEZIER = "0.25,0.1,0.25,1.0";

    private static final float MIN_OFFSET = 0.0F;
    private static final float MAX_OFFSET = 400.0F;

    private static volatile boolean loaded;

    // 热路径字段一律 volatile：渲染线程每帧都会读，不加锁也不分配
    private static volatile boolean enabled = true;
    /** 渐入（打开界面）与渐出（关闭界面）各自独立 */
    private static volatile int openDurationMs = DEFAULT_DURATION_MS;
    private static volatile int closeDurationMs = DEFAULT_DURATION_MS;
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
    private static volatile String excludedScreens = "";
    private static volatile Set<String> excludedSet = Collections.emptySet();
    private static volatile String extraScreens = DEFAULT_EXTRA_SCREENS;
    private static volatile Set<String> extraSet = Collections.emptySet();
    private static volatile String curveId = "cubic";
    /** 解析好的曲线枚举：curve() 在渲染热路径上，不能每次都解析字符串 */
    private static volatile Curve curveCache = Curve.CUBIC;
    /** 渐入 / 渐出的曲线，可与通用曲线不同 */
    private static volatile String openCurveId = "cubic";
    private static volatile String closeCurveId = "cubic";
    private static volatile Curve openCurveCache = Curve.CUBIC;
    private static volatile Curve closeCurveCache = Curve.CUBIC;
    /** 自定义曲线的控制点：渐入、渐出各一份 */
    private static volatile String openCurveCustom = DEFAULT_CUSTOM_BEZIER;
    private static volatile String closeCurveCustom = DEFAULT_CUSTOM_BEZIER;

    /** 运行期见过的界面类名（供配置界面提示用），有上限，避免无限增长 */
    private static final java.util.LinkedHashSet<String> SEEN_SCREENS = new java.util.LinkedHashSet<>();
    private static final int MAX_SEEN_SCREENS = 120;
    /** 上次写盘的完整内容，用来跳过"什么都没变"的重复写 */
    private static String lastWritten = null;
    /** 上面那份内容对应的文件路径：换了游戏目录就不能再拿它当"已写过"的依据 */
    private static String lastWrittenPath = null;

    private TransitionConfig() {
    }

    // ================================================================== 缓动曲线

    /**
     * 缓动曲线。内置若干条，也可以用四个贝塞尔控制点自定义。
     *
     * 这里刻意**不是枚举**：自定义曲线要携带自己的控制点，而渐入、渐出各自可以
     * 有不同的自定义形状 —— 枚举常量是所有调用点共享的单个实例，装不下这份差异。
     * 命名曲线仍然是单例常量（Curve.CUBIC 等），用法与枚举时期一致。
     */
    public static final class Curve {

        public static final String CUSTOM_ID = "custom";

        private static final int KIND_CUSTOM = 100;

        public static final Curve LINEAR = new Curve("linear", 0);
        public static final Curve SINE = new Curve("sine", 1);
        public static final Curve CUBIC = new Curve("cubic", 2);
        public static final Curve QUART = new Curve("quart", 3);
        public static final Curve QUINT = new Curve("quint", 4);
        public static final Curve EXPO = new Curve("expo", 5);
        public static final Curve CIRC = new Curve("circ", 6);
        public static final Curve BACK = new Curve("back", 7);

        /** 不含 custom：给"曲线名"下拉/校验用，custom 由 TransitionConfig 按方向解析 */
        private static final Curve[] BUILT_IN = { LINEAR, SINE, CUBIC, QUART, QUINT, EXPO, CIRC, BACK };

        private final String id;
        private final int kind;
        /** 仅自定义曲线非空：x1,y1,x2,y2 */
        private final float[] bezier;

        private Curve(String id, int kind) {
            this(id, kind, null);
        }

        private Curve(String id, int kind, float[] bezier) {
            this.id = id;
            this.kind = kind;
            this.bezier = bezier;
        }

        /** 造一条带控制点的自定义曲线 */
        public static Curve custom(float[] bezier) {
            return new Curve(CUSTOM_ID, KIND_CUSTOM, bezier);
        }

        public String id() {
            return this.id;
        }

        /** 自定义曲线的控制点；命名曲线返回 null */
        public float[] bezier() {
            return this.bezier;
        }

        /** 基础缓入曲线（0..1 → 0..1） */
        private float base(float t) {
            float x = Math.max(0.0F, Math.min(1.0F, t));
            switch (this.kind) {
                case 0:
                    return x;
                case 1:
                    return 1.0F - (float) Math.cos(x * Math.PI / 2.0);
                case 2:
                    return x * x * x;
                case 3:
                    return x * x * x * x;
                case 4:
                    return x * x * x * x * x;
                case 5:
                    return x <= 0.0F ? 0.0F : (float) Math.pow(2.0, 10.0 * x - 10.0);
                case 6:
                    return 1.0F - (float) Math.sqrt(Math.max(0.0, 1.0 - (double) x * x));
                case 7:
                    return 2.70158F * x * x * x - 1.70158F * x * x;
                default:
                    return bezierEase(this.bezier, x);
            }
        }

        /** 关闭用：缓入 */
        public float easeIn(float t) {
            return Math.max(0.0F, Math.min(1.0F, base(t)));
        }

        /** 打开用：缓出（基础曲线的镜像） */
        public float easeOut(float t) {
            return Math.max(0.0F, Math.min(1.0F, 1.0F - base(1.0F - t)));
        }

        /**
         * 三次贝塞尔缓动（P0=(0,0)、P3=(1,1) 固定），与 CSS 的 cubic-bezier() 同一套：
         * 先按 x 反解参数 t，再取该 t 处的 y。
         *
         * 用二分而不是牛顿迭代 —— 只求 30 次、不依赖导数，碰到退化控制点也不会炸。
         * 公开出来是为了让曲线编辑界面能直接用它取样画图，保证"看到的"和"跑出来的"一致。
         */
        public static float bezierEase(float[] points, float x) {
            float x1 = 0.25F;
            float y1 = 0.1F;
            float x2 = 0.25F;
            float y2 = 1.0F;
            if (points != null && points.length == 4) {
                x1 = points[0];
                y1 = points[1];
                x2 = points[2];
                y2 = points[3];
            }
            if (x <= 0.0F) {
                return 0.0F;
            }
            if (x >= 1.0F) {
                return 1.0F;
            }
            float lo = 0.0F;
            float hi = 1.0F;
            float t = x;
            for (int i = 0; i < 30; i++) {
                t = (lo + hi) * 0.5F;
                if (bezierAxis(t, x1, x2) < x) {
                    lo = t;
                } else {
                    hi = t;
                }
            }
            return bezierAxis(t, y1, y2);
        }

        /** 三次贝塞尔在参数 t 处的某一维取值（端点固定为 0 与 1） */
        public static float bezierAxis(float t, float p1, float p2) {
            float u = 1.0F - t;
            return 3.0F * u * u * t * p1 + 3.0F * u * t * t * p2 + t * t * t;
        }

        /** 按 id 取命名曲线；"custom" 返回一条控制点为默认值的自定义曲线 */
        public static Curve byId(String value) {
            if (value != null) {
                String trimmed = value.trim().toLowerCase(Locale.ROOT);
                if (CUSTOM_ID.equals(trimmed)) {
                    return custom(TransitionConfig.parseBezier(DEFAULT_CUSTOM_BEZIER));
                }
                for (Curve curve : BUILT_IN) {
                    if (curve.id.equals(trimmed)) {
                        return curve;
                    }
                }
            }
            return CUBIC;
        }

        /** 全部可选 id（含 custom），供配置界面列出 */
        public static String[] ids() {
            String[] ids = new String[BUILT_IN.length + 1];
            for (int i = 0; i < BUILT_IN.length; i++) {
                ids[i] = BUILT_IN[i].id;
            }
            ids[BUILT_IN.length] = CUSTOM_ID;
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
        // 迁移：早期版本下限是 100ms，用户很可能在调参时留下一个"极小值"。
        // 几十毫秒的遮罩淡出等于没有，用户会以为功能坏了 —— 小于这个阈值就当作没设过。
        int storedPortal = readInt(properties, "portalDurationMs", portalDurationMs);
        if (storedPortal > 0 && storedPortal < MIN_SENSIBLE_PORTAL_MS) {
            System.out.println("[UI Transitions] portalDurationMs=" + storedPortal
                    + "ms 太短（几乎看不见），已按默认 " + DEFAULT_PORTAL_DURATION_MS + "ms 处理；"
                    + "想彻底关掉这个过渡请把它设成 0");
            storedPortal = DEFAULT_PORTAL_DURATION_MS;
        }
        portalDurationMs = Math.max(MIN_PORTAL_DURATION_MS,
                Math.min(MAX_PORTAL_DURATION_MS, storedPortal));
        excludedScreens = properties.getProperty("excludedScreens", excludedScreens);
        extraScreens = properties.getProperty("extraScreens", extraScreens);
        // 迁移：老配置里只有一个 durationMs / curve，把它当作渐入渐出共同的值
        int legacyDuration = clampDuration(readInt(properties, "durationMs", DEFAULT_DURATION_MS));
        String legacyCurve = properties.getProperty("curve", curveId);
        openDurationMs = clampDuration(readInt(properties, "openDurationMs", legacyDuration));
        closeDurationMs = clampDuration(readInt(properties, "closeDurationMs", legacyDuration));
        setOpenCurveInternal(properties.getProperty("openCurve", legacyCurve));
        setCloseCurveInternal(properties.getProperty("closeCurve", legacyCurve));
        setCurveIdInternal(legacyCurve);
        // 老配置只有一个 curveCustom，迁移时同时套给渐入与渐出
        String legacyCustom = properties.getProperty("curveCustom", DEFAULT_CUSTOM_BEZIER);
        if (!isValidBezier(legacyCustom)) {
            legacyCustom = DEFAULT_CUSTOM_BEZIER;
        }
        setOpenCurveCustomInternal(properties.getProperty("openCurveCustom", legacyCustom));
        setCloseCurveCustomInternal(properties.getProperty("closeCurveCustom", legacyCustom));
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
        // durationMs / curve 已拆成渐入渐出两项，不再写出；
        // 但 load() 仍会读它们，好让老配置平滑迁移过来
        properties.setProperty("openDurationMs", Integer.toString(openDurationMs));
        properties.setProperty("closeDurationMs", Integer.toString(closeDurationMs));
        properties.setProperty("offset", Float.toString(offset));
        // 只写"渐入/渐出"这一对。旧的通用 curve / curveCustom 已经没有任何界面在用，
        // 继续写只会让配置文件里多两行让人困惑的死键；load() 仍然读它们做迁移。
        properties.setProperty("openCurve", openCurveId);
        properties.setProperty("closeCurve", closeCurveId);
        properties.setProperty("openCurveCustom", openCurveCustom);
        properties.setProperty("closeCurveCustom", closeCurveCustom);
        properties.setProperty("portalDurationMs", Integer.toString(portalDurationMs));
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
        openDurationMs = DEFAULT_DURATION_MS;
        closeDurationMs = DEFAULT_DURATION_MS;
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
        portalDurationMs = DEFAULT_PORTAL_DURATION_MS;
        excludedScreens = "";
        extraScreens = DEFAULT_EXTRA_SCREENS;
        curveId = Curve.CUBIC.id();
        curveCache = Curve.CUBIC;
        openCurveId = Curve.CUBIC.id();
        openCurveCache = Curve.CUBIC;
        closeCurveId = Curve.CUBIC.id();
        closeCurveCache = Curve.CUBIC;
        openCurveCustom = DEFAULT_CUSTOM_BEZIER;
        closeCurveCustom = DEFAULT_CUSTOM_BEZIER;

        rebuildSets();
        save();
    }

    // ================================================================== 取值（热路径，无锁无分配）

    public static boolean enabled() {
        return enabled;
    }

    /** 打开界面时的动画时长（毫秒）——「渐入」 */
    public static int openDurationMs() {
        return openDurationMs;
    }

    /** 关闭界面时的动画时长（毫秒）——「渐出」 */
    public static int closeDurationMs() {
        return closeDurationMs;
    }

    /** 兼容旧调用：把渐入渐出一起设成同一个值 */
    public static void setDurationMsBoth(int value) {
        setOpenDurationMs(value);
        setCloseDurationMs(value);
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

    /** 渐入（打开）用的曲线 */
    public static Curve openCurve() {
        return openCurveCache;
    }

    /** 渐出（关闭）用的曲线 */
    public static Curve closeCurve() {
        return closeCurveCache;
    }

    public static String openCurveCustom() {
        return openCurveCustom;
    }

    public static String closeCurveCustom() {
        return closeCurveCustom;
    }

    /** 把曲线 id 解析成实例：custom 会带上该方向自己的控制点 */
    private static Curve resolveCurve(String id, String customPoints) {
        if (Curve.CUSTOM_ID.equals(id)) {
            return Curve.custom(parseBezier(customPoints));
        }
        return Curve.byId(id);
    }

    /** 自定义曲线的控制点 x1,y1,x2,y2（已经校验并夹紧） */
    /** 校验并解析 "x1,y1,x2,y2"；非法输入回退到默认值 */
    public static float[] parseBezier(String value) {
        if (value != null) {
            String[] parts = value.split(",");
            if (parts.length == 4) {
                try {
                    float x1 = Float.parseFloat(parts[0].trim());
                    float y1 = Float.parseFloat(parts[1].trim());
                    float x2 = Float.parseFloat(parts[2].trim());
                    float y2 = Float.parseFloat(parts[3].trim());
                    // x 必须落在 0..1（否则 x(t) 不再单调，反解会失真）；
                    // y 允许超出，这样能做出回弹/过冲
                    return new float[] { clamp01(x1), clampY(y1), clamp01(x2), clampY(y2) };
                } catch (NumberFormatException ignored) {
                    // 落到默认值
                }
            }
        }
        return new float[] { 0.25F, 0.1F, 0.25F, 1.0F };
    }

    /** 自定义曲线参数是否合法（配置界面用来提示） */
    public static boolean isValidBezier(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split(",");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (Float.isNaN(Float.parseFloat(part.trim()))) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private static float clampY(float v) {
        return Math.max(-2.0F, Math.min(3.0F, v));
    }

    /** 记录一个见过的界面，供配置界面提示用 */
    public static void noteSeenScreen(String className) {
        if (className == null || className.isEmpty()) {
            return;
        }
        synchronized (SEEN_SCREENS) {
            if (SEEN_SCREENS.size() >= MAX_SEEN_SCREENS && !SEEN_SCREENS.contains(className)) {
                return;
            }
            SEEN_SCREENS.add(className);
        }
    }

    /** 最近见过的界面（新的在后） */
    public static java.util.List<String> seenScreens() {
        synchronized (SEEN_SCREENS) {
            return new java.util.ArrayList<>(SEEN_SCREENS);
        }
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

    public static synchronized void setOpenDurationMs(int value) {
        openDurationMs = clampDuration(value);
        save();
    }

    public static synchronized void setCloseDurationMs(int value) {
        closeDurationMs = clampDuration(value);
        save();
    }

    public static synchronized void setOffset(float value) {
        offset = clampOffset(value);
        save();
    }

    public static synchronized void setCurve(Curve value) {
        String id = (value == null ? Curve.CUBIC : value).id();
        curveId = id;
        openCurveId = id;
        closeCurveId = id;
        // 注意：不能直接用传进来的那个实例。custom 必须按各自方向已保存的控制点重新解析，
        // 否则"把通用曲线设成 custom"会拿一份默认控制点，把用户调好的形状悄悄丢掉。
        openCurveCache = resolveCurve(id, openCurveCustom);
        closeCurveCache = resolveCurve(id, closeCurveCustom);
        curveCache = openCurveCache;
        save();
    }

    public static synchronized void setCurveId(String value) {
        setCurve(Curve.byId(value));
    }

    public static synchronized void setOpenCurve(String value) {
        setOpenCurveInternal(value);
        save();
    }

    public static synchronized void setCloseCurve(String value) {
        setCloseCurveInternal(value);
        save();
    }

    /** 设置渐入的自定义控制点，并把渐入切到 custom */
    public static synchronized void setOpenCurveCustom(String value) {
        setOpenCurveCustomInternal(value);
        openCurveId = Curve.CUSTOM_ID;
        openCurveCache = resolveCurve(openCurveId, openCurveCustom);
        curveId = openCurveId;
        curveCache = openCurveCache;
        save();
    }

    /** 设置渐出的自定义控制点，并把渐出切到 custom */
    public static synchronized void setCloseCurveCustom(String value) {
        setCloseCurveCustomInternal(value);
        closeCurveId = Curve.CUSTOM_ID;
        closeCurveCache = resolveCurve(closeCurveId, closeCurveCustom);
        save();
    }

    /** 只写字段不存盘：给 load() 用，避免"读配置"触发一次写盘 */
    private static void setCurveIdInternal(String value) {
        Curve resolved = Curve.byId(value);
        curveId = resolved.id();
        curveCache = resolved;
    }

    private static void setOpenCurveInternal(String value) {
        openCurveId = Curve.byId(value).id();
        openCurveCache = resolveCurve(openCurveId, openCurveCustom);
    }

    private static void setCloseCurveInternal(String value) {
        closeCurveId = Curve.byId(value).id();
        closeCurveCache = resolveCurve(closeCurveId, closeCurveCustom);
    }

    /** 只刷新自定义控制点（渐入渐出各一份） */
    private static void setOpenCurveCustomInternal(String value) {
        openCurveCustom = isValidBezier(value) ? value : DEFAULT_CUSTOM_BEZIER;
        openCurveCache = resolveCurve(openCurveId, openCurveCustom);
        curveCache = openCurveCache;
    }

    private static void setCloseCurveCustomInternal(String value) {
        closeCurveCustom = isValidBezier(value) ? value : DEFAULT_CUSTOM_BEZIER;
        closeCurveCache = resolveCurve(closeCurveId, closeCurveCustom);
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
                "enabled=%s open=%dms/%s close=%dms/%s offset=%.0fpx jelly=%.0f%% "
                        + "fade=%s(fadeDim=%s items=%s text=%s) openFromBottom=%s closeToBottom=%s "
                        + "allScreens=%s sameTypeSwitch=%s panel=%s dim=%s subtitles=%s "
                        + "portal=%dms lookDuringClose=%s",
                enabled(), openDurationMs(), openCurve().id(), closeDurationMs(), closeCurve().id(),
                offset(), jelly() * 100.0F,
                fade(), fadeDim(), fadeItems(), fadeText(),
                openFromBottom(), closeToBottom(), animateAllScreens(), animateSameTypeSwitch(),
                animatePanel(), animateDim(), animateSubtitles(),
                portalDurationMs(), allowLookDuringClose());
    }
}
