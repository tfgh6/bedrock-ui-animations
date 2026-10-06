package net.minecraft.client.gui;

import org.joml.Matrix3x2fStack;

public class GuiGraphicsExtractor {
    public final Matrix3x2fStack pose = new Matrix3x2fStack();

    /** 供跨维度遮罩计算全屏尺寸 */
    public int guiWidth = 1920;
    public int guiHeight = 1080;
    public int lastFillColor;

    public Matrix3x2fStack pose() {
        return this.pose;
    }

    public int guiWidth() {
        return this.guiWidth;
    }

    public int guiHeight() {
        return this.guiHeight;
    }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        this.lastFillColor = color;
    }

    public void outline(int x, int y, int width, int height, int color) {
        this.lastFillColor = color;
    }
}
