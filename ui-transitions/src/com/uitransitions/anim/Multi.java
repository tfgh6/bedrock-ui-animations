package com.uitransitions.anim;

/**
 * 多点曲线：点集由用户在图上一颗一颗点出来，相邻两点之间用 {@code smoothstep} 插值。
 *
 * <p>数值与现有 {@code TransitionConfig.Curve.multiEase} 一致
 * （见 `TransitionConfig.java:281-304`）：段内 {@code s = t²(3-2t)}，即 smoothstep ——
 * **平滑、单调、不过冲**。这正是多点模式与贝塞尔模式的取舍：
 * 贝塞尔能做过冲（{@code y} 超出 0..1），多点则保证"拖出来的形状就是看到的形状"。
 *
 * <p><b>点集的整理不在这里做</b>。排序、去重、强制首尾 {@code (0,0)/(1,1)}、最小间隔
 * 这些属于"用户输入的容错"，现有实现放在 {@code Curve.normalizeMulti}，
 * 并且已经被离线断言覆盖（{@code VerifyAdvanced} 的多点一节）。本类**假定传入的
 * 点集已经整理过**，只负责求值 —— 这样"编辑器拖点"和"曲线求值"两件事各自可测，
 * 不会因为整理逻辑改动而静默影响动画。
 *
 * <p>越界行为（保真）：{@code x} 小于第一个点的 x 时返回"第一个点的 y"；
 * 走到最后一段之后返回"最后一个点的 y"。现有实现里首点固定是 {@code (0,0)}，
 * 所以这条只在点集**未经整理**时才会体现出来。
 */
public record Multi(float[] points) implements Easing {

    /** 空的点集：等价线性（现有实现 {@code multi(new float[0])} 的语义）。 */
    public static final Multi EMPTY = new Multi(new float[0]);

    /**
     * 从 {@code x,y,x,y,…} 数组构造（数组会被复制，避免调用方后续改动影响已建对象）。
     */
    public static Multi of(float[] points) {
        return points == null || points.length == 0 ? EMPTY : new Multi(points.clone());
    }

    /** 段内插值：步进 smoothstep。 */
    public static float ease(float[] pts, float x) {
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

    @Override
    public float easeIn(float t) {
        return Easing.clamp01(ease(this.points, Easing.clamp01(t)));
    }

    @Override
    public float easeOut(float t) {
        return Easing.clamp01(1.0F - ease(this.points, Easing.clamp01(1.0F - t)));
    }
}
