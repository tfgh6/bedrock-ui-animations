package net.minecraft.client.gui;

import org.joml.Matrix3x2fStack;

public class GuiGraphicsExtractor {
    public final Matrix3x2fStack pose = new Matrix3x2fStack();

    public Matrix3x2fStack pose() {
        return this.pose;
    }
}
