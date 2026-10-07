package com.uitransitions.anim;

/**
 * 缓动函数。**本项目唯一的曲线抽象**。
 *
 * <p>现有实现有三种曲线形态散在 {@code TransitionConfig.Curve} 里：8 条命名曲线、
 * 四点贝塞尔自定义曲线、多点曲线。它们对外都被当作"一条曲线"用，
 * 但取的是两个方向不同的值（进场用 {@code easeOut}、退场用 {@code easeIn}），
 * 这里就把它固定成契约。
 *
 * <p><b>为什么到位即夹取</b>：现有 {@code Curve.easeIn/easeOut} 都会把结果夹进 {@code [0,1]}
 * （见 {@code TransitionConfig.java:525-532}）。这不是疏忽 —— 它意味着
 * {@code back} 曲线的过冲**不会**表现在位移上（本项目另用 {@code jelly} 做回弹）。
 * 新实现必须保持这个语义，否则观感会变。要拿到不过冲的原始值，用
 * {@link NamedEasing#rawIn(float)}。
 *
 * <p><b>为什么需要反解</b>：动画被打断时（关闭到一半又打开），新动画要"从当前可见状态接着走"。
 * 现有实现用二分反解（{@code UiTransitions.solveProgress}，40 次迭代）而不是解析求逆，
 * 因为曲线可由用户任意配置。{@link #progressForIn(float)} 把它固化下来，
 * 于是"打断接续"成为一条**可离线断言**的纯函数，不必每加一个功能重推一遍。
 */
public interface Easing {

    /**
     * 缓入方向：{@code t=0 → 0}，{@code t=1 → 1}，曲线先慢后快。
     *
     * <p>用于"退场"（关闭界面：进度越大越透明/越靠外）。
     *
     * @param t 归一化进度，超出 {@code [0,1]} 会被夹取
     * @return 缓动后的值，**已夹进 {@code [0,1]}**
     */
    float easeIn(float t);

    /**
     * 缓出方向：{@code t=0 → 0}，{@code t=1 → 1}，曲线先快后慢。
     *
     * <p>用于"进场"（打开界面）。默认实现是 {@code easeIn} 的镜像
     * （{@code 1 - easeIn(1-t)}），与现有实现一致。
     */
    default float easeOut(float t) {
        return clamp01(1.0F - easeIn(1.0F - t));
    }

    /**
     * 反解 @{code 1 - easeIn(p)} 在目标值处的进度（**递减**函数，用二分）。
     *
     * <p>这就是现有 {@code UiTransitions.solveProgress(curve, target, closing=true)}
     * 逐字对应的式子（见 `UiTransitions.java:1316-1330`）：
     * {@code value = 1 - easeIn(mid); if (value > target) lo = mid; else hi = mid;}
     * 共 40 次迭代、结果取 {@code (lo+hi)/2}。
     *
     * <p><b>两个反解原语必须分清</b>（本轮在这里连错三次，每次结果都是"看着很合理的小数"）：
     * <ul>
     *   <li>{@code progressForIn} —— 反解 {@code 1 - easeIn}，对应关闭/退场取值</li>
     *   <li>{@link #progressForOut} —— 反解 {@code easeOut}，对应打开/进场取值</li>
     * </ul>
     * 用直观看不出来的地方在于：线性曲线下两者互为补（{@code 0.99} vs {@code 0.01}），
     * 所以传错不会崩、只会精确地接错位置。断言里那条"与旧实现逐位一致"就是为它写的。
     *
     * @param target 目标值，会被夹进 {@code [0,1]}
     * @return 对应的进度，落在 {@code [0,1]}
     */
    default float progressForIn(float target) {
        float t = clamp01(target);
        float lo = 0.0F;
        float hi = 1.0F;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) * 0.5F;
            // 与旧实现的条件完全一致：命中就往下收
            if (1.0F - easeIn(mid) > t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5F;
    }

    /**
     * 反解 {@link #easeIn} 在目标值处的进度（递增函数，用二分）。
     *
     * <p>服务于打开/进场的取直：{@code easeOut(p) = 1 - easeIn(1-p)}，
     * 于是 {@code progressForOut(v) = 1 - progressForIn(1 - v)}。
     */
    default float progressForOut(float target) {
        return 1.0F - progressForIn(1.0F - clamp01(target));
    }

    /**
     * 夹进 {@code [0,1]}。
     *
     * <p><b>NaN 的语义是"1"，不是"0"</b> —— 这是刻意的保真：Java 的
     * {@code Math.max(0.0F, Math.min(1.0F, NaN))} 返回 {@code 1.0F}，而现有实现
     * （{@code TransitionConfig.clamp01}，见 `TransitionConfig.java:1398`）正是这么写的。
     * 新实现若把 NaN 处理成 0，就等于顺手改了行为，而这种改动在观感上极难发现。
     * （配置里真的写进 NaN 时，现有代码就靠这条落在"不淡出"上；见分析报告 R 段。）
     */
    static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    /** 线性插值。 */
    static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
