package com.uitransitions.anim;

/**
 * 颜色 alpha 调制的**纯函数** —— 现有 {@code UiTransitions.modulate} 的等价搬移 + 一处刻意修正。
 *
 * <h2>为什么值得单独抽出来</h2>
 *
 * 现有实现的 {@code modulate(color, alpha)}（{@code UiTransitions.java:581-605}）读的是一个
 * **全局标志** {@code PREMULTIPLIED}（ThreadLocal），由 {@code beginItemSubmit} 置位、
 * {@code endItemSubmit} 复位。于是：
 *
 * <blockquote>
 * 物品提交窗口内画出来的**任何**文字或矩形，都会被当成预乘颜色做 RGB 缩放。
 * </blockquote>
 *
 * 现在没暴露，是因为恰好没有人在那个窗口里画文字 —— 但这是**等触发的**结构缺陷
 * （分析报告 §6.1 的 S2）。本类把"是否预乘"从全局状态改成**显式参数**：
 * 判定跟着"这条颜色属于哪条渲染通道"走，而不是跟着"此刻谁在压栈"走。
 *
 * <h2>不变量（必须有断言守着）</h2>
 * <ol>
 *   <li>{@code alpha >= 0.999} 原样返回（快速路径，现有行为）</li>
 *   <li>{@code alpha <= 0.04} 返回 0（尾部归零，避免残亮闪一下）</li>
 *   <li>非预乘：只改高 8 位 alpha，RGB 原样保留</li>
 *   <li>预乘：RGB 与 A 一起乘 alpha（物品贴图所在管线的语义）</li>
 *   <li>{@code premultiplied} 是**参数**，与任何全局状态无关</li>
 * </ol>
 */
public final class ColorMath {

    /** 单条颜色都不再参与淡变的门限：{@code alpha >= 1} 时直接返回原值。 */
    public static final float OPAQUE_THRESHOLD = 0.999F;

    /** 低于此值直接归零：否则尾部几帧会残留一点亮度，看起来像在闪。 */
    public static final float ZERO_THRESHOLD = 0.04F;

    private ColorMath() {
    }

    /**
     * 按 alpha 调制颜色。
     *
     * @param argb           原始颜色（ARGB）
     * @param alpha          透明度乘子，会被夹进 {@code [0,1]}
     * @param premultiplied  true = 该颜色来自预乘 alpha 管线，RGB 必须一起缩放
     * @return 调制后的颜色
     */
    public static int apply(int argb, float alpha, boolean premultiplied) {
        float a = Easing.clamp01(alpha);
        if (a >= OPAQUE_THRESHOLD) {
            return argb;
        }
        if (a <= ZERO_THRESHOLD) {
            return 0;
        }
        int existing = (argb >>> 24) & 0xFF;
        if (premultiplied) {
            int r = Math.round(((argb >> 16) & 0xFF) * a);
            int g = Math.round(((argb >> 8) & 0xFF) * a);
            int b = Math.round((argb & 0xFF) * a);
            int na = Math.round(existing * a);
            return (na << 24) | (r << 16) | (g << 8) | b;
        }
        int modulated = Math.max(0, Math.min(255, Math.round(existing * a)));
        return (argb & 0xFFFFFF) | (modulated << 24);
    }

    /** 仅改 alpha 通道（贴图块、纯色块、文字走这条）。 */
    public static int applyAlphaOnly(int argb, float alpha) {
        return apply(argb, alpha, false);
    }

    /** 预乘管线的缩放（物品图集、画中画贴回走这条）。 */
    public static int applyPremultiplied(int argb, float alpha) {
        return apply(argb, alpha, true);
    }
}
