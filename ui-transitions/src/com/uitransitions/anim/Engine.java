package com.uitransitions.anim;

import java.util.function.DoubleSupplier;

/**
 * 颜色调制引擎 —— 现有 {@code UiTransitions.applyAlphaBlit} / {@code applyAlphaText} /
 * {@code modulate} 那几段的**行为等价替身**，把全局状态换成显式依赖。
 *
 * <h2>为什么做成"每个来源一个 DoubleSupplier"</h2>
 *
 * 现有实现的 alpha 来自 {@code ThreadLocal<Float>}，而且**读哪个 ThreadLocal 是按通道分的**
 * （见 {@link Channel.AlphaSource}）。迁移期我需要"两边都读同一份旧状态"，
 * 所以引擎不去碰那些 ThreadLocal，而是让调用方把取值器递进来：
 *
 * <pre>{@code
 * // 迁移期（第 2 步）—— 读旧的 ThreadLocal，行为逐位不变
 * Engine engine = new Engine(
 *         () -> WINDOW_ALPHA.get(),
 *         () -> TEXT_ALPHA.get(),
 *         () -> PIP_FRAME_ALPHA);
 *
 * // 迁完之后（第 3 步）—— 换成帧上下文，引擎本身不用改
 * Engine engine = new Engine(ctx::windowAlpha, ctx::textAlpha, ctx::presentedAlpha);
 * }</pre>
 *
 * <h2>聊天乘子：它是调用点的策略，不是引擎的状态</h2>
 *
 * 现有 {@code applyAlphaText}（{@code UiTransitions.java:558-575}）里有四道守卫：
 * {@code fadeText()} 早退、{@code TAB_STATIC} 冻结早退、读 {@code TEXT_ALPHA}、
 * 以及**聊天乘子** {@code if (chatFadeActive) alpha *= chatFadeAlpha();}。
 *
 * <p>本引擎把前两道与最后那个乘子都留给**调用方**（它们各自依赖 {@code TransitionConfig}
 * 与 {@code UiTransitions} 的私有状态），只负责"取通道 alpha → 调制颜色"这一段。
 * 这样做的直接好处：第 2 步可以**只换落点、不动那四道守卫**，
 * 而"漏掉聊天乘子会导致聊天淡入静默失效"这个风险面被压到最小
 * （它在调用点原样保留，不在引擎里）。
 */
public final class Engine {

    private final DoubleSupplier windowAlpha;
    private final DoubleSupplier textAlpha;
    private final DoubleSupplier presentedAlpha;
    private final ChannelRenderer renderer;

    /**
     * 渲染状态是否就绪的判据。GUI 渲染可能发生在 Minecraft 尚未初始化时（启动早期），
     * 那时不该碰任何全局状态 —— 现有实现里这条守在最外层（{@code TransitionConfig.ensureLoaded()} 之前）。
     */
    @FunctionalInterface
    public interface ReadyCheck {
        boolean ready();
    }

    private final ReadyCheck ready;

    /**
     * @param windowAlpha   窗口透明度来源（贴图块 / 纯色块）
     * @param textAlpha     文字透明度来源
     * @param presentedAlpha 帧级"最终呈现"透明度（物品提交 / 画中画贴回）
     */
    public Engine(DoubleSupplier windowAlpha, DoubleSupplier textAlpha, DoubleSupplier presentedAlpha) {
        this(windowAlpha, textAlpha, presentedAlpha, () -> true);
    }

    public Engine(DoubleSupplier windowAlpha, DoubleSupplier textAlpha, DoubleSupplier presentedAlpha,
                  ReadyCheck ready) {
        this.windowAlpha = windowAlpha;
        this.textAlpha = textAlpha;
        this.presentedAlpha = presentedAlpha;
        this.ready = ready;
        this.renderer = new ChannelRenderer();
    }

    /**
     * 按通道施加透明度。
     *
     * <p>调用方负责前置守卫（{@code enabled} / {@code fadeText} / 冻结 / 聊天乘子），
     * 本方法只做"取 alpha → 调制"，并且**出错时原样返回颜色**（与现有实现的
     * {@code catch (Throwable) { return color; }} 同策略：渲染热路径绝不抛）。
     */
    public int apply(Channel channel, int color) {
        try {
            if (!this.ready.ready()) {
                return color;
            }
            return this.renderer.apply(channel, color, alphaOf(channel));
        } catch (Throwable t) {
            this.renderer.reportOnce(channel, t);
            return color;
        }
    }

    /**
     * 按通道 + **显式 alpha** 施加（给"调用方已经算好 alpha"的场景，例如文字乘子已在外面算完）。
     *
     * <h3>⚠️ 本重载**不查 alpha 来源**，只做一次乘法</h3>
     *
     * 调用方传进来的 {@code alpha} 会被**原样使用**，不会再去读该通道的
     * {@link Channel.AlphaSource}。这一点看调用点是**分不出来**的 ——
     * 两个重载只差一个参数。所以：
     *
     * <pre>{@code
     * engine.apply(Channel.TEXT, color);            // 读 TEXT_ALPHA 来源 × 1 次
     * engine.apply(Channel.TEXT, color, alpha);     // 不读来源，只用 alpha × 1 次
     * }</pre>
     *
     * <p>现有调用点（{@code UiTransitions.applyAlphaText}）走的是**后者**，因为它在外面
     * 已经把 {@code TEXT_ALPHA} 与聊天乘子乘完了。**如果哪天改成让 alphaOf 也参与进来，
     * 就会变成双重乘** —— 而表现是"聊天淡入看起来只有一半深"这种
     * 绝不会被归因到乘法次数的现象。
     *
     * <p>这条依赖由 {@code ColorMathVerify} 的
     * "显式 alpha 不做来源查询"断言钉住（断言与 TEXT_ALPHA 的当前值无关）。
     */
    public int apply(Channel channel, int color, float alpha) {
        try {
            return this.renderer.apply(channel, color, alpha);
        } catch (Throwable t) {
            this.renderer.reportOnce(channel, t);
            return color;
        }
    }

    /** 取某通道当前的 alpha（读的就是对应来源；迁移期即旧 ThreadLocal）。 */
    public float alphaOf(Channel channel) {
        DoubleSupplier source = switch (channel.alphaSource()) {
            case WINDOW -> this.windowAlpha;
            case TEXT -> this.textAlpha;
            case FRAME_PRESENTED -> this.presentedAlpha;
        };
        if (source == null) {
            return 1.0F;
        }
        double value = source.getAsDouble();
        return (float) value;
    }
}
