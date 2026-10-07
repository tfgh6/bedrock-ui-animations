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
     * <p>反解用哪条式子由 {@code opening} 决定，**必须与调用方接下来怎么取值一致** ——
     * 这两条式子不同，而且混用会得到一个"看着很合理"的错进度：
     * <ul>
     *   <li>{@code opening = true}（新动画是打开/进场，取值走 {@link #valueOut}）
     *       → 反解 {@link Easing#progressForOut}</li>
     *   <li>{@code opening = false}（新动画是关闭/退场，取值走 {@link #valueIn}）
     *       → 反解 {@link Easing#progressForIn}</li>
     * </ul>
     * 这正是现有实现里 {@code solveProgress(..., closing)} 那个布尔参数的含义
     * （`UiTransitions.java:183-184` 与 `:216-217` 分别对应 false / true）。
     *
     * <p><b>{@code from}/{@code to}/{@code visible} 必须同域</b>：它内部做
     * {@code (visible - from) / (to - from)}。把 alpha（0..1）配上像素域（0..120）
     * 会静默得到错误的进度 —— 本轮踩过，所以 {@link #progress} 的语义固定为
     * "这一趟淡变走完了多少"，而位移由 progress 驱动、不参与反解。
     *
     * @param now           当前时间
     * @param visible       当前可见值（现有实现传的是"可见透明度"）
     * @param durationNanos 新动画的时长
     * @param easing        新动画的曲线
     * @param from          新动画的起始值
     * @param to            新动画的结束值
     * @param opening       新动画是否走缓出方向（打开/进场）
     * @return 起点已回拨的 {@code Tween}
     */
    public static Tween continueFrom(long now, float visible, long durationNanos, Easing easing,
                                     float from, float to, boolean opening) {
        // 值域退化（from == to）时无法反解，按"从头播"处理，避免除以 0
        float span = to - from;
        if (span == 0.0F || Float.isNaN(span)) {
            return new Tween(now, durationNanos, easing, from, to);
        }
        float normalized = Easing.clamp01((visible - from) / span);
        Easing target = easing == null ? NamedEasing.LINEAR : easing;
        float progress = opening ? target.progressForOut(normalized) : target.progressForIn(normalized);
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
