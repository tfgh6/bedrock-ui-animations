package org.joml;

public class Matrix3x2f implements Matrix3x2fc {
    public Matrix3x2f() {
    }

    public Matrix3x2f(Matrix3x2fc source) {
    }

    public Matrix3x2f(Matrix3x2f source) {
    }

    public Matrix3x2f translate(float x, float y) {
        Matrix3x2fStack.lastTranslateX = x;
        Matrix3x2fStack.lastTranslateY = y;
        return this;
    }
}
