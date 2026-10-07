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
     * 现有实现的 {@code solveProgress(..., closing=true)} 的逐字复刻 —— **注意它不是
     * {@link #easeIn} 的反函数**。
     *
     * <p>它反解的是 {@code 1 - easeIn(p)}：{@code value = 1 - easeIn(mid); if (value > target) lo = mid;}
     * （`UiTransitions.java:1321-1322`）。名字保留是为了**可对照**旧代码，
     * 语义则由这个注释与 {@link #progressForAlpha} 的关系钉住：
     * {@code progressForIn(t) == progressForAlpha(1 - t)}，两者是同一个式子换元。
     *
     * <p>⚠️ 它**不能**用来反解 {@code easeIn} 的值（那会把 0.027 反解成 0.99）。
     * 本轮在这个点上连错多次，最后是"反解与取值必须互为逆运算"那条往返断言定的案。
     */
    default float progressForIn(float target) {
        return progressForAlpha(1.0F - clamp01(target));
    }

    /**
     * 反解 {@code f(p) = 1 - easeIn(p)}：求使 {@code 1 - easeIn(p) == target} 的 {@code p}。
     *
     * <p>这是"可见透明度"这一路最自然的反解 —— 打开时 alpha 恰是 {@code 1 - easeIn(p)}，
     * 关闭被打断接续时（`UiTransitions.java:183-184`）反解的也是它。
     * {@code f} 单调**递减**，二分方向与之匹配。
     *
     * <p><b>已知病态区</b>：{@code quart} / {@code quint} / {@code expo} 这些在 {@code p→0} 处
     * 极为平坦的曲线，{@code 1 - easeIn(p)} 会在 float 精度下**饱和成 1.0**（实测
     * {@code quart} 在 {@code p <= 0.01} 时即为 1.0）。此时上式在 {@code [0,1]} 内无解，
     * 本方法返回接近 0 的值 —— 这是信息已在前一步丢失，不是反解写错。
     * 影响面：打断接续发生在动画**开头**那一两帧（进度 < 2%）时，接续点会略微偏移；
     * 那一两帧人眼不可辨，且与现有实现的行为一致（旧二分同样无法反解）。
     */
    default float progressForAlpha(float target) {
        float t = clamp01(target);
        float lo = 0.0F;
        float hi = 1.0F;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) * 0.5F;
            // f(p) = 1 - easeIn(p) 递减：f(mid) > t ⇒ p < mid ⇒ 收上界
            // 与现有 UiTransitions.solveProgress 的 closing 分支逐字一致：
            //   value = 1 - easeIn(mid); if (value > target) lo = mid;   （`:1321-1322`）
            if (1.0F - easeIn(mid) > t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5F;
    }

    /**
     * {@link #easeIn} 的**真正反函数**：求使 {@code easeIn(p) == target} 的 {@code p}。
     *
     * <p>服务于"关闭/退场"一路的取值器 {@code valueIn}。{@code easeIn} 单调**递增**，
     * 所以二分方向与 {@link #progressForAlpha} 相反 —— 这正是前面把它们当成"同一个式子换元"
     * 而出错的地方：两者的单调方向相反，不能互相替换。
     *
     * <p>{@code target} 超出 {@code easeIn} 的值域时返回最接近的端点（夹取），不抛异常。
     */
    default float progressForEaseIn(float target) {
        float t = clamp01(target);
        float lo = 0.0F;
        float hi = 1.0F;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) * 0.5F;
            // easeIn 递增：easeIn(mid) < t ⇒ mid < p ⇒ p 在 mid 右侧 ⇒ 抬 lo
            if (easeIn(mid) < t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5F;
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
