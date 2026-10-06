package org.joml;

public class Matrix3x2f implements Matrix3x2fc {
    /** 与真实 JOML 一致：平移分量存在 m20 / m21 */
    private float m20;
    private float m21;

    public Matrix3x2f() {
    }

    public Matrix3x2f(Matrix3x2fc source) {
        if (source instanceof Matrix3x2f) {
            this.m20 = ((Matrix3x2f) source).m20;
            this.m21 = ((Matrix3x2f) source).m21;
        }
    }

    public Matrix3x2f(Matrix3x2f source) {
        this.m20 = source.m20;
        this.m21 = source.m21;
    }

    public Matrix3x2f translate(float x, float y) {
        this.m20 += x;
        this.m21 += y;
        Matrix3x2fStack.lastTranslateX = x;
        Matrix3x2fStack.lastTranslateY = y;
        return this;
    }

    public float m20() {
        return this.m20;
    }

    public float m21() {
        return this.m21;
    }
}
