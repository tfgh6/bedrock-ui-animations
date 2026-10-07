package com.uitransitions.anim;

/**
 * 一段动画的不可变描述：起点 + 时长 + 曲线 + 起止值。
 *
 * <p>它把现有实现里散落的三处逻辑合成一个可断言的对象：
 * <ul>
 *   <li>进度换算 —— {@code UiTransitions.progress(screen)}（`UiTransitions.java:1349-1364`）</li>
 *   <li>回拨 —— {@code UiTransitions.backdateNanos}（`:1333-1339`）</li>
 *   <li>反解 —— {@code UiTransitions.solveProgress}（`:1316-1330`）</li>
 * </ul>
 *
 * <p><b>为什么时长要存在实例里、而不是每次去读配置</b>（现有实现的注释记录过这个坑）：
 * 用户在配置界面把时长从 200 改成 2000 再关掉时，正在播的那段动画若按新时长重算进度，
 * 会突然倒退或直接闪完。所以每段动画必须**记住自己开始时的时长**。
 *
 * <p><b>打断接续</b>是这里最值钱的一条：{@link #continueFrom} 让"关闭到一半又打开"从
 * 当前可见状态接着走，而不是跳回起点。现有实现反解的是**透明度**，位移由曲线的镜像关系
 * 自动接上（README 记录过实测：接续瞬间 alpha=185 与打断前完全一致、位移 32.99 = 120×(1−0.725)）。
 */
public record Tween(long startNanos, long durationNanos, Easing easing, float from, float to) {

    /** 取值方向：调用方接下来**实际用哪个取值器**。 */
    public enum Getter {
        /** 用 {@link Tween#valueIn} 取值 —— 可见比例 = {@code easeIn(p)} */
        IN,
        /** 用 {@link Tween#valueOut} 取值 —— 可见比例 = {@code easeOut(p)} */
        OUT
    }

    /**
     * 归一化进度。
     *
     * <p>时长非正时返回 1（即"立刻到位"），不抛异常：时长来自配置，可能是 0。
     * 现有实现里 0 走的是 {@code clamp01(inf)} → 1，语义一致。
     */
    public float progress(long now) {
        if (this.durationNanos <= 0L) {
            return 1.0F;
        }
        float p = (now - this.startNanos) / (float) this.durationNanos;
        return Easing.clamp01(p);
    }

    /** 是否已经播完。 */
    public boolean finished(long now) {
        return now - this.startNanos >= this.durationNanos;
    }

    /**
     * 当前值：用**缓出**方向（进场语义）插值。
     *
     * <p>退场要用缓入方向，见 {@link #valueIn(long)}。两者不是同一个函数：
     * {@code easeOut(t) = 1 - easeIn(1-t)}，对同一条 {@code t} 结果不同，
     * 而在两端都精确落在 {@code from}/{@code to}。
     */
    public float valueOut(long now) {
        return Easing.lerp(this.from, this.to, this.easing.easeOut(progress(now)));
    }

    /** 当前值：用**缓入**方向（退场语义）插值。 */
    public float valueIn(long now) {
        return Easing.lerp(this.from, this.to, this.easing.easeIn(progress(now)));
    }

    /**
     * 打断接续：造一段新动画，使它在 {@code now} 这一刻的**可见值**恰好等于 {@code visible}。
     *
     * <p>做法是**回拨起点**（把开始时间往前挪），而不是改曲线或改时长：
     * 这样新动画的其余部分与正常播放完全一致，观感上就是"从当前状态接着走"。
     *
     * <p><b>为什么必须显式声明 {@link Getter}</b>：可见值有两条式子，
     * 而"接续"的定义是"接续前后用**同一个取值器**取出来是同一个数"：
     * <ul>
     *   <li>{@link #valueOut}：可见比例 = {@code 1 - easeIn(p)} ⇒ 反解 {@link Easing#progressForAlpha}</li>
     *   <li>{@link #valueIn}：可见比例 = {@code easeIn(p)} ⇒ 反解 {@link Easing#progressForEaseIn}</li>
     * </ul>
     * 两者形状不同，混用会得到一个"看着合理"的错进度。
     * 现有实现的 {@code solveProgress(..., closing)} 就是同一件事
     * （`UiTransitions.java:183-184` 与 `:216-217` 各传一个方向）。
     *
     * <p>（本轮教训：先把这两个式子当成"同一个式子换元"而删掉了参数，
     * 结果两支恒等、都走 progressForAlpha —— 看似简化，实则丢失了方向信息。
     * 旧实现的布尔不是冗余，它编码的正是"调用方接下来用哪个取值器"。）
     *
     * <p><b>参数必须同域</b>：{@code from} / {@code to} / {@code visible} 三者同域
     * （内部做 {@code (visible - from) / (to - from)}）。alpha 用 {@code [0,1]}；
     * 位移由进度驱动、不参与反解。
     *
     * @param now           当前时间
     * @param visible       当前可见值（alpha 域下即"可见透明度"）
     * @param durationNanos 新动画的时长
     * @param easing        新动画的曲线
     * @param from          新动画的起始值
     * @param to            新动画的结束值
     * @param getter        调用方接下来用哪个取值器取值
     * @return 起点已回拨的 {@code Tween}
     */
    public static Tween continueFrom(long now, float visible, long durationNanos, Easing easing,
                                     float from, float to, Getter getter) {
        // 值域退化（from == to）时无法反解，按"从头播"处理，避免除以 0
        float span = to - from;
        if (span == 0.0F || Float.isNaN(span)) {
            return new Tween(now, durationNanos, easing, from, to);
        }
        float normalized = Easing.clamp01((visible - from) / span);
        Easing target = easing == null ? NamedEasing.LINEAR : easing;
        // 归一化后：valueIn 的可见比例是 easeIn(p)，valueOut 的是 easeOut(p) = 1 - easeIn(1-p)。
        // 所以按**取值器**选反解，与 from/to 的符号无关：
        //   getter=OUT ⇒ 反解 easeOut(p) = target ⇒ 1 - easeIn(1-p) = target
        //                ⇒ progressForAlpha(1 - target)
        //   getter=IN  ⇒ 反解 easeIn(p) = target  ⇒ progressForEaseIn(target)
        float progress = getter == Getter.OUT
                ? target.progressForAlpha(1.0F - normalized)
                : target.progressForEaseIn(normalized);
        long backdate = backdateNanos(progress, durationNanos);
        return new Tween(now - backdate, durationNanos, target, from, to);
    }

    /**
     * 进度 → 回拨时间。与现有 {@code backdateNanos} 一致：
     * 极小进度不回拨（避免"看起来像没接上"），并夹进 {@code [0,1]}。
     */
    public static long backdateNanos(float progress, long durationNanos) {
        float clamped = Easing.clamp01(progress);
        if (clamped <= 0.0001F) {
            return 0L;
        }
        return (long) (clamped * durationNanos);
    }
}
