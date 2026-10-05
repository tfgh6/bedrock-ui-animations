package org.joml;

/** 记录调用情况，供验证程序断言 */
public class Matrix3x2fStack extends Matrix3x2f {
    public static int pushCount;
    public static int popCount;
    public static float lastTranslateX;
    public static float lastTranslateY;

    public static void reset() {
        pushCount = 0;
        popCount = 0;
        lastTranslateX = 0.0F;
        lastTranslateY = 0.0F;
    }

    public Matrix3x2fStack pushMatrix() {
        pushCount++;
        return this;
    }

    public Matrix3x2fStack popMatrix() {
        popCount++;
        return this;
    }

    @Override
    public Matrix3x2f translate(float x, float y) {
        lastTranslateX = x;
        lastTranslateY = y;
        return this;
    }
}
