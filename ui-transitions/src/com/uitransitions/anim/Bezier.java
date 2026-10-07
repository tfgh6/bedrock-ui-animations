package com.uitransitions.anim;

/**
 * 三点固定的三次贝塞尔缓动，与 CSS 的 {@code cubic-bezier()} 同一套：
 * {@code P0=(0,0)}、{@code P3=(1,1)} 固定，只让用户拖中间两个控制点。
 *
 * <p>数值与现有 {@code TransitionConfig.Curve.bezierEase} 一致
 * （见 `TransitionConfig.java:541-576`）：**先按 x 二分反解参数 t，再取该 t 处的 y**，
 * 30 次迭代。刻意不用牛顿迭代 —— 只求 30 次、不需要导数，碰到退化控制点（x1=x2）也不会炸，
 * 而用户的曲线是用来"拖"的，退化输入是常态而非例外。
 *
 * <p>控制点的取值范围由配置侧保证（见 {@code TransitionConfig.parseBezier}）：
 * {@code x} 必须落在 {@code [0,1]}（否则 x(t) 不再单调、反解会失真），
 * {@code y} 允许超出（这样能做回弹/过冲）。本类**不做夹取**，
 * 因为它同时被曲线图绘制使用 —— 那里需要看到真实的过冲形状。
 */
public record Bezier(float x1, float y1, float x2, float y2) implements Easing {

    /** 与配置里的默认控制点一致（接近 CSS 的 {@code ease}）。 */
    public static final Bezier DEFAULT = new Bezier(0.25F, 0.1F, 0.25F, 1.0F);

    /**
     * 三点贝塞尔在参数 {@code t} 处某一维的取值（端点固定为 0 与 1）。
     *
     * <p>与现有 {@code Curve.bezierAxis} 逐字一致 —— 系数写成 {@code 3u²t·p1 + 3ut²·p2 + t³}，
     * 不要展开成多项式：展开会改变浮点舍入，实测值就对不上了。
     */
    public static float axis(float t, float p1, float p2) {
        float u = 1.0F - t;
        return 3.0F * u * u * t * p1 + 3.0F * u * t * t * p2 + t * t * t;
    }

    /**
     * 按 x 反解参数 t，再取 y。{@code x <= 0} 返回 0、{@code x >= 1} 返回 1（与现有实现一致）。
     */
    public static float ease(float[] points, float x) {
        float px1 = DEFAULT.x1();
        float py1 = DEFAULT.y1();
        float px2 = DEFAULT.x2();
        float py2 = DEFAULT.y2();
        if (points != null && points.length == 4) {
            px1 = points[0];
            py1 = points[1];
            px2 = points[2];
            py2 = points[3];
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
            if (axis(t, px1, px2) < x) {
                lo = t;
            } else {
                hi = t;
            }
        }
        return axis(t, py1, py2);
    }

    /** 从 {@code Curve.bezier()} 那样的四元数组构造；长度不对时用默认控制点。 */
    public static Bezier of(float[] points) {
        if (points != null && points.length == 4) {
            return new Bezier(points[0], points[1], points[2], points[3]);
        }
        return DEFAULT;
    }

    /** 控制点数组（{@code x1,y1,x2,y2}），供曲线图与配置读写使用。 */
    public float[] points() {
        return new float[] { this.x1, this.y1, this.x2, this.y2 };
    }

    /**
     * {@inheritDoc}
     *
     * <p>注意贝塞尔曲线的 {@code y} 可以超出 {@code [0,1]}（用户故意做成回弹），
     * 而接口契约要求夹取 —— 这里夹取是**保真**的：现有 {@code Curve.easeIn} 也会夹。
     */
    @Override
    public float easeIn(float t) {
        return Easing.clamp01(ease(points(), Easing.clamp01(t)));
    }

    @Override
    public float easeOut(float t) {
        return Easing.clamp01(1.0F - ease(points(), Easing.clamp01(1.0F - t)));
    }
}
