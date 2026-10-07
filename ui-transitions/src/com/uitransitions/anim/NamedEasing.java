package com.uitransitions.anim;

/**
 * 八条内置缓动曲线，与 {@code TransitionConfig.Curve} 的命名曲线**逐位等价**。
 *
 * <p>这里的公式全部照搬现有实现（{@code TransitionConfig.Curve.base}，见
 * `TransitionConfig.java:498-522`），一个常数都不改 —— 包括 {@code circ} 里那个
 * {@code (double)} 提升和 {@code expo} 的边界判断。浮点运算不做"看起来一样"的化简：
 * 只要有一处不同，实测的位移/透明度就会差几位，而这类差异在离线断言里必须为 0。
 *
 * <p>{@link #easeIn(float)} 与现有 {@code Curve.easeIn} 一致；
 * {@link #easeOut(float)} 用接口默认实现（{@code 1 - easeIn(1-t)}），
 * 与现有 {@code Curve.easeOut} 一致。
 *
 * <p>{@link #rawIn(float)} 是**不过冲**的原始值。现有实现里它只用于画曲线图；
 * 动画路径上一直用夹取后的值，所以 {@code BACK} 的回弹不会体现在位移上
 * （位移的回弹由配置项 {@code jelly} 单独负责）。
 */
public enum NamedEasing implements Easing {

    LINEAR("linear", 0),
    SINE("sine", 1),
    CUBIC("cubic", 2),
    QUART("quart", 3),
    QUINT("quint", 4),
    EXPO("expo", 5),
    CIRC("circ", 6),
    BACK("back", 7);

    /** 与配置里的曲线 id 一致（{@code curve=...}） */
    private final String id;
    /** 与现有实现的 kind 编号一致，便于逐条对照 */
    private final int kind;

    NamedEasing(String id, int kind) {
        this.id = id;
        this.kind = kind;
    }

    public String id() {
        return this.id;
    }

    public int kind() {
        return this.kind;
    }

    /**
     * 原始曲线值（**不夹取**）。
     *
     * <p>命名曲线的原始值都落在 {@code [0,1]}（{@code BACK} 会略微越界），
     * 但 {@code back} 的过冲在 {@code t} 接近 0 时是**负的**，所以不要在动画路径上直接用它。
     */
    public float rawIn(float t) {
        float x = Easing.clamp01(t);
        switch (this.kind) {
            case 0:
                return x;                                                   // linear
            case 1:
                return 1.0F - (float) Math.cos(x * Math.PI / 2.0);           // sine
            case 2:
                return x * x * x;                                            // cubic
            case 3:
                return x * x * x * x;                                        // quart
            case 4:
                return x * x * x * x * x;                                    // quint
            case 5:
                return x <= 0.0F ? 0.0F : (float) Math.pow(2.0, 10.0 * x - 10.0);  // expo
            case 6:
                // 这里的 (double) 提升与现有实现完全一致，不要"顺手化简"
                return 1.0F - (float) Math.sqrt(Math.max(0.0, 1.0 - (double) x * x));   // circ
            default:
                return 2.70158F * x * x * x - 1.70158F * x * x;              // back
        }
    }

    @Override
    public float easeIn(float t) {
        return Easing.clamp01(rawIn(t));
    }

    @Override
    public float easeOut(float t) {
        return Easing.clamp01(1.0F - rawIn(1.0F - t));
    }

    /** 按 id 取曲线；无法识别时回退 {@link #CUBIC}（与现有 {@code Curve.byId} 的兜底一致）。 */
    public static NamedEasing byId(String value) {
        if (value != null) {
            String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
            for (NamedEasing easing : values()) {
                if (easing.id.equals(trimmed)) {
                    return easing;
                }
            }
        }
        return CUBIC;
    }

    /** 全部内置曲线 id，顺序与现有 {@code Curve.ids()} 一致。 */
    public static String[] ids() {
        NamedEasing[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].id;
        }
        return out;
    }
}
