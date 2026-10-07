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

    // ---------------------------------------------------------------- 聊天栏淡入

    /**
     * HUD 聊天栏新消息的淡入时长（毫秒）。
     *
     * 这是一个**全新的动画**（以前聊天消息是"啪"地直接出现）：它发生在 HUD 上，
     * 与"哪个界面在开/关"无关，所以单独一条配置、单独计时。
     * 0 = 关闭（回到原版观感）。
     */
    public static final int DEFAULT_CHAT_FADE_MS = 260;
    public static final int MIN_CHAT_FADE_MS = 0;
    public static final int MAX_CHAT_FADE_MS = 2000;

    private static volatile int chatFadeMs = DEFAULT_CHAT_FADE_MS;

    public static int chatFadeMs() {
        return chatFadeMs;
    }

    public static synchronized void setChatFadeMs(int value) {
        chatFadeMs = Math.max(MIN_CHAT_FADE_MS, Math.min(MAX_CHAT_FADE_MS, value));
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

        /**
         * "跟随全局"：按部位的曲线留空时用这个占位，表示"用 openCurve / closeCurve"。
         * 这样老配置升级后行为完全不变，界面上也能明确显示"跟随全局"。
         */
        public static final String FOLLOW_ID = "default";

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
        /** 仅多点曲线非空：x0,y0,x1,y1,…（x 递增，首尾固定） */
        private final float[] points;

        private Curve(String id, int kind) {
            this(id, kind, null, null);
        }

        private Curve(String id, int kind, float[] bezier) {
            this(id, kind, bezier, null);
        }

        private Curve(String id, int kind, float[] bezier, float[] points) {
            this.id = id;
            this.kind = kind;
            this.bezier = bezier;
            this.points = points;
        }

        /** 造一条带控制点的自定义曲线 */
        public static Curve custom(float[] bezier) {
            return new Curve(CUSTOM_ID, KIND_CUSTOM, bezier);
        }

        public String id() {
            return this.id;
        }


        /** 多点曲线的曲线 id 与控制点格式说明 */
        public static final String MULTI_ID = "multi";
        private static final int KIND_MULTI = 101;

        /** 多点曲线的点：偶数下标是 x、奇数下标是 y，x 必须递增，首尾固定 (0,0) 与 (1,1) */
        public static Curve multi(float[] points) {
            return new Curve(MULTI_ID, KIND_MULTI, null, normalizeMulti(points));
        }

        /**
         * 整理多点数组：按 x 排序、去掉越界点、强制首尾为 (0,0)/(1,1)。
         *
         * 界面允许用户随便拖，所以这里必须能容错 —— 拖出格、点重复、顺序乱了都要能救回来，
         * 否则一次误操作就会让曲线算出 NaN。
         */
        public static float[] normalizeMulti(float[] raw) {
            java.util.List<float[]> list = new java.util.ArrayList<>();
            if (raw != null) {
                for (int i = 0; i + 1 < raw.length; i += 2) {
                    float x = Math.max(0.0F, Math.min(1.0F, raw[i]));
                    float y = Math.max(-0.5F, Math.min(1.5F, raw[i + 1]));
                    if (x > 0.0001F && x < 0.9999F) {
                        list.add(new float[] { x, y });
                    }
                }
            }
            list.sort((a, b) -> Float.compare(a[0], b[0]));
            // 去掉 x 挨得太近的点：挨太近会让插值区间退化成除以 0
            java.util.List<float[]> clean = new java.util.ArrayList<>();
            float lastX = 0.0F;
            for (float[] p : list) {
                if (p[0] - lastX < 0.02F) {
                    continue;
                }
                clean.add(p);
                lastX = p[0];
            }
            float[] out = new float[(clean.size() + 2) * 2];
            out[0] = 0.0F;
            out[1] = 0.0F;
            int n = 2;
            for (float[] p : clean) {
                out[n++] = p[0];
                out[n++] = p[1];
            }
            out[n++] = 1.0F;
            out[n] = 1.0F;
            return out;
        }

        /** 多点曲线的点（可能为空）；贝塞尔/命名曲线返回 null */
        public float[] points() {
            return this.points;
        }

        /** 相邻点之间用 smoothstep 插值：平滑、单调、不过冲 */
        private static float multiEase(float[] pts, float x) {
            if (pts == null || pts.length < 4) {
                return x;
            }
            for (int i = 0; i + 3 < pts.length; i += 2) {
                float x0 = pts[i];
                float y0 = pts[i + 1];
                float x1 = pts[i + 2];
                float y1 = pts[i + 3];
                if (x <= x0) {
                    return y0;
                }
                if (x <= x1) {
                    float span = x1 - x0;
                    if (span <= 1.0E-5F) {
                        return y1;
                    }
                    float t = (x - x0) / span;
                    float s = t * t * (3.0F - 2.0F * t);       // smoothstep
                    return y0 + (y1 - y0) * s;
                }
            }
            return pts[pts.length - 1];
        }

        /** 多点曲线的点用 "x,y;x,y;…" 存；非法输入回退成空（等价线性） */
        public static float[] parseMulti(String spec) {
            if (spec == null || spec.isBlank()) {
                return new float[0];
            }
            java.util.List<Float> vals = new java.util.ArrayList<>();
            for (String pair : spec.split(";")) {
                String[] xy = pair.split(",");
                if (xy.length != 2) {
                    return new float[0];
                }
                try {
                    vals.add(Float.parseFloat(xy[0].trim()));
                    vals.add(Float.parseFloat(xy[1].trim()));
                } catch (NumberFormatException e) {
                    return new float[0];
                }
            }
            float[] out = new float[vals.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = vals.get(i);
            }
            return normalizeMulti(out);
        }

        // ------------------------------------------------------------- 多点曲线的编辑操作
        //
        // 曲线编辑器在图上加点、拖点、双击删点，都需要改这份点集。这些操作**刻意放在
        // 这里而不是界面类里**：界面类依赖 Minecraft 的 GUI 类型，离线断言跑不动它，
        // 于是"拖一个点把曲线拖成 NaN 或者点数对不上"这类问题只能靠人眼发现。
        // 放这里就能被 verify-uit 的真实 JVM 断言覆盖（见 VerifyAdvanced 的多点一节）。

        /** 相邻两个点之间 x 至少要隔这么远：挨太近会让插值区间退化成除以 0 */
        public static final float MIN_POINT_GAP = 0.02F;

        /**
         * 内部点的 x 允许范围（首尾固定为 0 与 1，不参与取值）。
         *
         * 边界要留出**两个** MIN_POINT_GAP，不能只留一个：插入时会检查"与最近的点是否
         * 至少隔开 MIN_POINT_GAP"，而首尾那两个点（0 与 1）也在检查范围内。
         * 早先这里写成 1 个 gap，夹取之后的 x 与端点恰好相距一个 gap，
         * 于是**贴着左右边缘的点击永远加不进点** —— 明明夹对了，却什么也没发生。
         */
        public static final float MIN_POINT_X = MIN_POINT_GAP * 2.0F;
        public static final float MAX_POINT_X = 1.0F - MIN_POINT_GAP * 2.0F;

        /** 内部点（可拖可删的那些）的点数：总点数减掉固定的首尾 */
        public static int interiorPointCount(float[] pts) {
            return pts == null ? 0 : Math.max(0, pts.length / 2 - 2);
        }

        /** 第 index 个内部点的 x */
        public static float pointX(float[] pts, int index) {
            return pts[2 + index * 2];
        }

        /** 第 index 个内部点的 y */
        public static float pointY(float[] pts, int index) {
            return pts[3 + index * 2];
        }

        /**
         * 在 x 处加一个点（y 就是该处曲线当前的高度），返回新点集。
         *
         * 加不进去就**原样返回**（返回的是传入的同一个数组引用，调用方可以拿
         * `inserted == before` 判断"这次点击什么也没做"），不抛异常：
         * 用户点歪了不该让界面崩。
         */
        public static float[] insertMulti(float[] pts, float x, float y) {
            float[] base = normalizeMulti(pts);
            float cx = Math.max(MIN_POINT_X, Math.min(MAX_POINT_X, x));
            for (int i = 0; i + 1 < base.length; i += 2) {
                if (Math.abs(base[i] - cx) < MIN_POINT_GAP) {
                    return pts;         // 和已有的点挨太近
                }
            }
            float cy = Math.max(-0.5F, Math.min(1.5F, y));
            java.util.List<Float> vals = new java.util.ArrayList<>();
            for (int i = 0; i + 1 < base.length; i += 2) {
                if (base[i] > cx && vals.size() >= 2 && vals.get(vals.size() - 2) < cx) {
                    vals.add(cx);
                    vals.add(cy);
                }
                vals.add(base[i]);
                vals.add(base[i + 1]);
            }
            float[] out = new float[vals.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = vals.get(i);
            }
            return normalizeMulti(out);
        }

        /** 删掉第 index 个内部点；首尾不可删，越界或点太少都原样返回 */
        public static float[] removeMulti(float[] pts, int index) {
            float[] base = normalizeMulti(pts);
            int count = interiorPointCount(base);
            if (index < 0 || index >= count) {
                return pts;
            }
            float[] out = new float[(count - 1 + 2) * 2];
            int n = 0;
            out[n++] = base[0];
            out[n++] = base[1];
            for (int i = 0; i < count; i++) {
                if (i == index) {
                    continue;
                }
                out[n++] = pointX(base, i);
                out[n++] = pointY(base, i);
            }
            out[n++] = base[base.length - 2];
            out[n] = base[base.length - 1];
            return normalizeMulti(out);
        }

        /**
         * 拖动第 index 个内部点：x 夹在左右邻居之间，y 夹在可视范围内。
         *
         * 夹 x 是必须的 —— 一旦越到邻居另一侧，排序之后点的身份就变了，
         * 表现为"拖到一半手指下的点突然换成另一个"，非常难用。
         */
        public static float[] moveMulti(float[] pts, int index, float x, float y) {
            float[] base = normalizeMulti(pts);
            int count = interiorPointCount(base);
            if (index < 0 || index >= count) {
                return pts;
            }
            float lo = index == 0 ? MIN_POINT_X : pointX(base, index - 1) + MIN_POINT_GAP;
            float hi = index == count - 1 ? MAX_POINT_X : pointX(base, index + 1) - MIN_POINT_GAP;
            float cx = Math.max(lo, Math.min(hi, x));
            float cy = Math.max(-0.5F, Math.min(1.5F, y));
            float[] out = base.clone();
            out[2 + index * 2] = cx;
            out[3 + index * 2] = cy;
            return normalizeMulti(out);
        }

        /**
         * 把点集换算成一条**尽量接近**的贝塞尔（P1/P2 由两端切线得出）。
         *
         * 用户在多点模式下调好形状，切回贝塞尔模式时如果直接丢掉，那一下就是白调。
         * 切线按两端相邻点连线的斜率取，再夹进合法范围。
         */
        public static float[] multiToBezier(float[] pts) {
            float[] base = normalizeMulti(pts);
            if (base.length < 6) {
                return new float[] { 0.25F, 0.1F, 0.25F, 1.0F };
            }
            float dx1 = base[2] - base[0];
            float dy1 = base[3] - base[1];
            float dx2 = base[base.length - 2] - base[base.length - 4];
            float dy2 = base[base.length - 1] - base[base.length - 3];
            float x1 = clamp01(0.5F * dx1);
            float y1 = clampY(dx1 <= 0.0F ? 0.0F : dy1 / dx1 * 0.5F);
            float x2 = clamp01(1.0F - 0.5F * dx2);
            float y2 = clampY(dx2 <= 0.0F ? 1.0F : 1.0F - dy2 / dx2 * 0.5F);
            return new float[] { x1, y1, x2, y2 };
        }

        /** 多点曲线的点格式化成字符串（含首尾） */
        public static String formatMulti(float[] pts) {
            if (pts == null || pts.length < 4) {
                return "0,0;1,1";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 1 < pts.length; i += 2) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(trimFloat(pts[i])).append(',').append(trimFloat(pts[i + 1]));
            }
            return sb.toString();
        }

        private static String trimFloat(float v) {
            String s = String.format(java.util.Locale.ROOT, "%.3f", v);
            while (s.contains(".") && (s.endsWith("0"))) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
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
                case KIND_MULTI:
                    return multiEase(this.points, x);
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
                if (MULTI_ID.equals(trimmed)) {
                    // 具体点位由 TransitionConfig 按方向/部位解析，这里只给个能用的默认
                    return multi(new float[0]);
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
            String[] ids = new String[BUILT_IN.length + 2];
            for (int i = 0; i < BUILT_IN.length; i++) {
                ids[i] = BUILT_IN[i].id;
            }
            ids[BUILT_IN.length] = CUSTOM_ID;
            ids[BUILT_IN.length + 1] = MULTI_ID;
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
        setExcludedScreensInternal(properties.getProperty("excludedScreens", excludedScreens));
        setExtraScreensInternal(properties.getProperty("extraScreens", extraScreens));
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
        // 按部位的曲线：没写过的部位保持"跟随全局"
        resetPartCurves();
        for (Part part : Part.values()) {
            for (boolean closing : new boolean[] { false, true }) {
                String dir = closing ? "close" : "open";
                String globalCustom = closing ? closeCurveCustom : openCurveCustom;
                String id = properties.getProperty("curve." + part.id() + "." + dir, Curve.FOLLOW_ID);
                String pts = properties.getProperty("curveCustom." + part.id() + "." + dir, globalCustom);
                partMap(closing, false).put(part, id == null ? Curve.FOLLOW_ID : id.trim().toLowerCase(java.util.Locale.ROOT));
                partMap(closing, true).put(part, isValidBezier(pts) ? pts : DEFAULT_CUSTOM_BEZIER);
            }
        }
        // 按界面分类的曲线与时长：没写过就保持"跟随全局"
        resetCategories();
        for (UiCategory category : UiCategory.values()) {
            setCategoryInternal(category,
                    properties.getProperty("categoryCurve." + category.id(), Curve.FOLLOW_ID),
                    properties.getProperty("categoryCurveCustom." + category.id(), ""),
                    readOptionalInt(properties, "categoryOpenMs." + category.id()),
                    readOptionalInt(properties, "categoryCloseMs." + category.id()));
        }
        setChatFadeMsInternal(readInt(properties, "chatFadeMs", chatFadeMs));
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
        // 只写"不跟随全局"的部位，配置文件才不会被 28 行 default 淹没
        for (Part part : Part.values()) {
            for (boolean closing : new boolean[] { false, true }) {
                String dir = closing ? "close" : "open";
                String id = partCurveId(part, closing);
                if (!Curve.FOLLOW_ID.equals(id)) {
                    properties.setProperty("curve." + part.id() + "." + dir, id);
                    properties.setProperty("curveCustom." + part.id() + "." + dir,
                            partCurveCustom(part, closing));
                }
            }
        }
        // 只写"不跟随全局"的分类，理由同上面按部位的曲线
        for (UiCategory category : UiCategory.values()) {
            String id = categoryCurveId(category);
            if (!Curve.FOLLOW_ID.equals(id)) {
                properties.setProperty("categoryCurve." + category.id(), id);
                properties.setProperty("categoryCurveCustom." + category.id(),
                        CATEGORY_CUSTOM.getOrDefault(category, DEFAULT_CUSTOM_BEZIER));
            }
            Integer openMs = CATEGORY_OPEN_MS.get(category);
            if (openMs != null) {
                properties.setProperty("categoryOpenMs." + category.id(), Integer.toString(openMs));
            }
            Integer closeMs = CATEGORY_CLOSE_MS.get(category);
            if (closeMs != null) {
                properties.setProperty("categoryCloseMs." + category.id(), Integer.toString(closeMs));
            }
        }
        properties.setProperty("chatFadeMs", Integer.toString(chatFadeMs));
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
        setChatFadeMsInternal(DEFAULT_CHAT_FADE_MS);
        resetCategories();
        setExcludedScreensInternal("");
        setExtraScreensInternal(DEFAULT_EXTRA_SCREENS);
        curveId = Curve.CUBIC.id();
        curveCache = Curve.CUBIC;
        openCurveId = Curve.CUBIC.id();
        openCurveCache = Curve.CUBIC;
        closeCurveId = Curve.CUBIC.id();
        closeCurveCache = Curve.CUBIC;
        openCurveCustom = DEFAULT_CUSTOM_BEZIER;
        closeCurveCustom = DEFAULT_CUSTOM_BEZIER;
        resetPartCurves();

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
    /** 可以单独配曲线的"部位"。顺序即界面上动画列表的顺序。 */
    public enum Part {
        PANEL("panel"),
        DIM("dim"),
        ITEMS("items"),
        TEXT("text"),
        SUBTITLES("subtitles"),
        TAB("tab"),
        PORTAL("portal");

        private final String id;

        Part(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        public static Part byId(String id) {
            for (Part p : values()) {
                if (p.id.equals(id)) {
                    return p;
                }
            }
            return null;
        }
    }

    /** 按部位存的曲线 id；没设过 / 设成 default 就跟随全局 */
    private static final java.util.Map<Part, String> PART_OPEN_CURVE =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(Part.class));
    private static final java.util.Map<Part, String> PART_CLOSE_CURVE =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(Part.class));
    private static final java.util.Map<Part, String> PART_OPEN_CUSTOM =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(Part.class));
    private static final java.util.Map<Part, String> PART_CLOSE_CUSTOM =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(Part.class));
    private static java.util.Map<Part, String> partMap(boolean closing, boolean custom) {
        if (closing) {
            return custom ? PART_CLOSE_CUSTOM : PART_CLOSE_CURVE;
        }
        return custom ? PART_OPEN_CUSTOM : PART_OPEN_CURVE;
    }

    /** 这个部位这一方向配的是哪条曲线（可能是 FOLLOW_ID） */
    public static String partCurveId(Part part, boolean closing) {
        return partMap(closing, false).getOrDefault(part, Curve.FOLLOW_ID);
    }

    public static String partCurveCustom(Part part, boolean closing) {
        return partMap(closing, true).getOrDefault(part, DEFAULT_CUSTOM_BEZIER);
    }

    /**
     * 实际要用的曲线：配了就用配的，没配（default）就回退到全局的渐入/渐出曲线。
     * 核心代码只调这一个，不必关心"跟随全局"这回事。
     */
    public static Curve curveFor(Part part, boolean closing) {
        String id = partCurveId(part, closing);
        if (part == null || Curve.FOLLOW_ID.equals(id)) {
            return closing ? closeCurve() : openCurve();
        }
        return resolveCurve(id, partCurveCustom(part, closing));
    }

    // ================================================================== 界面分类
    //
    // 「部位」切的是**一屏之内**的各个图层（底板/物品/文字…），
    // 「界面分类」切的是**哪一类界面**（聊天栏 / 创造物品栏 / 游戏菜单 / 容器 / 传送门）。
    // 两者正交：分类决定"这一类界面用哪条曲线、多长时长"，部位决定"这一屏里的某一层怎么淡"。

    /** 界面分类。顺序即配置界面里选项卡的顺序。 */
    public enum UiCategory {
        CHAT("chat", "ui_transitions.category.chat"),
        CREATIVE("creative", "ui_transitions.category.creative"),
        GAME_MENU("game_menu", "ui_transitions.category.game_menu"),
        CONTAINER("container", "ui_transitions.category.container"),
        PORTAL("portal", "ui_transitions.category.portal"),
        OTHER("other", "ui_transitions.category.other");

        private final String id;
        private final String labelKey;

        UiCategory(String id, String labelKey) {
            this.id = id;
            this.labelKey = labelKey;
        }

        public String id() {
            return this.id;
        }

        /** 翻译键（配置界面选项卡标题用） */
        public String labelKey() {
            return this.labelKey;
        }

        /**
         * 这个界面属于哪一类。按**类名**判断，不 import 具体界面类 ——
         * 这样以后原版改包名/加新界面时，最多是"归到 other"，不会编译不过。
         */
        public static UiCategory of(String className) {
            if (className == null) {
                return OTHER;
            }
            // 聊天栏：聊天输入框（HUD 上的聊天消息是另一条路，见 chatAlphaForLine）
            if (className.contains("ChatScreen")) {
                return CHAT;
            }
            if (className.contains("CreativeModeInventory")) {
                return CREATIVE;
            }
            if (className.contains("PauseScreen")) {
                return GAME_MENU;
            }
            if (className.contains("LevelLoading") || className.contains("ReceivingLevel")) {
                return PORTAL;
            }
            if (className.contains("ContainerScreen") || className.contains("InventoryScreen")
                    || className.contains("ChestScreen") || className.contains("FurnaceScreen")
                    || className.contains("CraftingScreen") || className.contains("HopperScreen")
                    || className.contains("ShulkerBoxScreen") || className.contains("DispenserScreen")
                    || className.contains("BrewingStandScreen") || className.contains("MerchantScreen")
                    || className.contains("AnvilScreen") || className.contains("BeaconScreen")
                    || className.contains("EnchantmentScreen") || className.contains("GrindstoneScreen")
                    || className.contains("LoomScreen") || className.contains("SmithingScreen")
                    || className.contains("StonecutterScreen") || className.contains("CartographyScreen")) {
                return CONTAINER;
            }
            return OTHER;
        }
    }

    /** 按分类存的曲线 id；没设过就跟随全局 */
    private static final java.util.Map<UiCategory, String> CATEGORY_CURVE =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(UiCategory.class));
    private static final java.util.Map<UiCategory, String> CATEGORY_CUSTOM =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(UiCategory.class));
    private static final java.util.Map<UiCategory, Integer> CATEGORY_OPEN_MS =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(UiCategory.class));
    private static final java.util.Map<UiCategory, Integer> CATEGORY_CLOSE_MS =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(UiCategory.class));

    /** 这一分类配的是哪条曲线（可能是 FOLLOW_ID = 跟随全局） */
    public static String categoryCurveId(UiCategory category) {
        return CATEGORY_CURVE.getOrDefault(category, Curve.FOLLOW_ID);
    }

    /** 这一分类实际要用的曲线：没单独配就回退到全局 */
    public static Curve curveForCategory(UiCategory category) {
        if (category == null) {
            return openCurve();
        }
        String id = CATEGORY_CURVE.getOrDefault(category, Curve.FOLLOW_ID);
        if (Curve.FOLLOW_ID.equals(id)) {
            return openCurve();
        }
        return resolveCurve(id, CATEGORY_CUSTOM.getOrDefault(category, DEFAULT_CUSTOM_BEZIER));
    }

    /** 某一分类的渐入时长（毫秒）；没单独设过就跟随全局 */
    public static int openDurationFor(UiCategory category) {
        Integer value = category == null ? null : CATEGORY_OPEN_MS.get(category);
        return value == null ? openDurationMs : value;
    }

    /** 某一分类的渐出时长（毫秒）；没单独设过就跟随全局 */
    public static int closeDurationFor(UiCategory category) {
        Integer value = category == null ? null : CATEGORY_CLOSE_MS.get(category);
        return value == null ? closeDurationMs : value;
    }

    /**
     * 这一分类有没有单独设过时长。
     * 界面上要显示"跟随全局（480ms）"还是"单独设置（700ms）"，靠它区分 ——
     * 光看数值分不出来（单独设成和全局一样也是合法的）。
     */
    public static boolean hasOwnDuration(UiCategory category, boolean closing) {
        java.util.Map<UiCategory, Integer> map = closing ? CATEGORY_CLOSE_MS : CATEGORY_OPEN_MS;
        return category != null && map.containsKey(category);
    }

    public static synchronized void setCategoryCurve(UiCategory category, String id) {
        if (category == null) {
            return;
        }
        String clean = id == null ? Curve.FOLLOW_ID : id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Curve.FOLLOW_ID.equals(clean) && !Curve.MULTI_ID.equals(clean)
                && !Curve.byId(clean).id().equals(clean)) {
            clean = Curve.FOLLOW_ID;
        }
        CATEGORY_CURVE.put(category, clean);
        save();
    }

    /** 设置分类的自定义形状（贝塞尔或多点，类型按值的格式推断） */
    public static synchronized void setCategoryCurveCustom(UiCategory category, String points) {
        if (category == null || !isValidBezier(points)) {
            return;
        }
        CATEGORY_CUSTOM.put(category, points);
        CATEGORY_CURVE.put(category, kindForPoints(points));
        save();
    }

    /** 把这一分类的时长单独设成 value；value <= 0 表示"回到跟随全局" */
    public static synchronized void setCategoryDuration(UiCategory category, boolean closing, int value) {
        if (category == null) {
            return;
        }
        java.util.Map<UiCategory, Integer> map = closing ? CATEGORY_CLOSE_MS : CATEGORY_OPEN_MS;
        if (value <= 0) {
            map.remove(category);
        } else {
            map.put(category, clampDuration(value));
        }
        save();
    }

    /** 全部回到"跟随全局"（读配置与重置时用） */
    private static void resetCategories() {
        for (java.util.Map<?, ?> m : java.util.List.of(
                CATEGORY_CURVE, CATEGORY_CUSTOM, CATEGORY_OPEN_MS, CATEGORY_CLOSE_MS)) {
            m.clear();
        }
    }

    private static void setCategoryInternal(UiCategory category, String curveId, String custom,
                                            Integer openMs, Integer closeMs) {
        if (curveId != null && !Curve.FOLLOW_ID.equals(curveId)) {
            CATEGORY_CURVE.put(category, curveId);
        }
        if (custom != null && !custom.isBlank()) {
            CATEGORY_CUSTOM.put(category, custom);
        }
        if (openMs != null) {
            CATEGORY_OPEN_MS.put(category, openMs);
        }
        if (closeMs != null) {
            CATEGORY_CLOSE_MS.put(category, closeMs);
        }
    }

    public static synchronized void setPartCurve(Part part, boolean closing, String id) {
        if (part == null) {
            return;
        }
        String clean = id == null ? Curve.FOLLOW_ID : id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Curve.FOLLOW_ID.equals(clean)
                && !Curve.MULTI_ID.equals(clean)
                && !Curve.byId(clean).id().equals(clean)) {
            clean = Curve.FOLLOW_ID;
        }
        partMap(closing, false).put(part, clean);
        save();
    }

    /**
     * 判断一段点位字符串是"贝塞尔控制点"还是"多点曲线"，返回该用哪个 id。
     *
     * **必须按内容判、不能按"这一项当前是什么 id"判。** 这两种数据共用 `curveCustom`
     * 字段，界面又可以在同一次编辑里把形状从一种改成另一种（多点 ↔ 控制点），
     * 所以只看旧 id 一定会写出"id 说 multi、值却是贝塞尔"这种自相矛盾的配置 ——
     * 之后按 multi 解析会得到空点集，曲线**静默变成一条直线**。
     *
     * 两种格式不可能混淆：多点一定有 `;`（至少两组 x,y），贝塞尔恰好四段且无 `;`。
     */
    private static String kindForPoints(String points) {
        if (points != null && points.indexOf(';') >= 0) {
            return Curve.MULTI_ID;
        }
        return Curve.CUSTOM_ID;
    }

    public static synchronized void setPartCurveCustom(Part part, boolean closing, String points) {
        if (part == null) {
            return;
        }
        if (!isValidBezier(points)) {
            points = DEFAULT_CUSTOM_BEZIER;
        }
        partMap(closing, true).put(part, points);
        // 改控制点意味着想用自定义曲线，顺手把这一项切到对应的自定义类型。
        // 类型按**值的格式**推断（见 kindForPoints）—— 早先这里无条件写 custom，
        // 多点曲线会被 parseBezier 判成非法输入、静默退回默认控制点，用户拖出来的形状白丢。
        partMap(closing, false).put(part, kindForPoints(points));
        save();
    }

    /** 把某个部位切成多点曲线（点集为空时等价于线性，界面随后会写回真实点位） */
    public static synchronized void setPartCurveMulti(Part part, boolean closing, String points) {
        if (part == null) {
            return;
        }
        partMap(closing, true).put(part, points == null ? "" : points);
        partMap(closing, false).put(part, Curve.MULTI_ID);
        save();
    }

    /** 全部回到"跟随全局" */
    private static void resetPartCurves() {
        for (java.util.Map<Part, String> m : java.util.List.of(
                PART_OPEN_CURVE, PART_CLOSE_CURVE, PART_OPEN_CUSTOM, PART_CLOSE_CUSTOM)) {
            m.clear();
        }
    }

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
        if (Curve.MULTI_ID.equals(id)) {
            return Curve.multi(Curve.parseMulti(customPoints));
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
        // 多点曲线存的是 "x,y;x,y;…"，用的是同一个字段，所以这里要一并放行 ——
        // 否则用户在图上拖出来的点会被判成非法、悄悄退回默认值。
        if (value.indexOf(';') >= 0) {
            return Curve.parseMulti(value).length >= 4;
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

    /**
     * 逗号分隔的配置项 → 列表。
     *
     * 从配置界面里搬出来的：排除列表要新增一个**双列表界面**，两边都得用同一套切分规则。
     * 各自实现一份的话，只要有一处 trim / 忽略空项的做法不同，
     * "界面上看着加进去了、实际没写进配置"这类问题就会跟着来。
     */
    public static java.util.List<String> splitList(String value) {
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

    /** 列表 → 逗号分隔的配置项 */
    public static String joinList(java.util.List<String> list) {
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

    /** 当前排除项的原始写法（可能是完整类名，也可能是包名前缀） */
    public static java.util.List<String> excludedEntries() {
        return splitList(excludedScreens);
    }

    /**
     * 这条排除项是不是"一段可以整个删掉的前缀"（也就是用户手填的包名，如 `mezz.jei`）。
     *
     * 判据：**最后一段以小写字母开头**就当它是包名。Java 的类名按惯例首字母大写，
     * 所以 `mezz.jei` → 前缀（true），`mezz.jei.SomeScreen` → 具体类名（false）。
     * 这个判据不完美（小写类名、或是包名里最后一段恰好大写都会判错），
     * 所以它只用来**决定界面上显示什么提示文案**，不用来决定能不能删除 ——
     * 用户一旦想删就必须能删掉，不能因为我们的猜测把人卡住。
     */
    public static boolean isPrefixEntry(String entry) {
        if (entry == null || entry.isEmpty()) {
            return false;
        }
        int dot = entry.lastIndexOf('.');
        String last = dot < 0 ? entry : entry.substring(dot + 1);
        return !last.isEmpty() && Character.isLowerCase(last.charAt(0));
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

    /**
     * 界面名单的两份表示：字符串（写文件用）与集合（判定用）**必须一起改**。
     *
     * 这就是这个私有方法存在的理由：`excludedScreens` 与 `excludedSet` 是同一份数据的两种形态，
     * 而"只改字符串、忘了重建集合"曾经真的发生过 —— 症状是**排除列表看着生效了，实际判定还按缓存来**：
     * 界面上显示已排除，动画却照做（或者反过来），而且不看代码根本想不到是缓存的事。
     * 所以对外只暴露下面那对 Internal / setter，任何赋值都必须经过这里。
     */
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

    /**
     * 设置渐入的多点曲线点集，并把渐入切到 multi。
     *
     * 与 setOpenCurveCustom 的差别只有"切成哪个 id"：点集和贝塞尔控制点共用
     * 同一个 `*CurveCustom` 字段，靠 id 决定怎么解析它。
     */
    public static synchronized void setOpenCurveMulti(String points) {
        setOpenCurveCustomInternal(points);
        openCurveId = Curve.MULTI_ID;
        openCurveCache = resolveCurve(openCurveId, openCurveCustom);
        curveId = openCurveId;
        curveCache = openCurveCache;
        save();
    }

    /** 设置渐出的多点曲线点集，并把渐出切到 multi */
    public static synchronized void setCloseCurveMulti(String points) {
        setCloseCurveCustomInternal(points);
        closeCurveId = Curve.MULTI_ID;
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
        setExcludedScreensInternal(value);
        save();
    }

    public static synchronized void setExtraScreens(String value) {
        setExtraScreensInternal(value);
        save();
    }

    /** 只写字段 + 重建集合、不存盘：给 load() / resetToDefaults() 用 */
    private static void setExcludedScreensInternal(String value) {
        excludedScreens = value == null ? "" : value;
        rebuildSets();
    }

    private static void setExtraScreensInternal(String value) {
        extraScreens = value == null ? "" : value;
        rebuildSets();
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

    /** 只写字段不存盘：给 load()/reset 用 */
    private static void setChatFadeMsInternal(int value) {
        chatFadeMs = Math.max(MIN_CHAT_FADE_MS, Math.min(MAX_CHAT_FADE_MS, value));
    }

    /**
     * 读一个"可以不写"的整数键：**没写就返回 null**，而不是回退到某个默认值。
     *
     * 这是"跟随全局"能成立的关键：分类时长必须能区分"没配过"与"配成了和全局一样"，
     * 用 `readInt(..., 默认值)` 会把两者压成同一个值，界面上就永远显示不出"跟随全局"。
     */
    private static Integer readOptionalInt(Properties properties, String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return clampDuration(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
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
