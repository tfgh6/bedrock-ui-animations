package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 纯色矩形（格子高亮、分隔线、有的界面用它画底板）的透明度。
 *
 * 两个构造器的 int 参数顺序一致：x0, y0, x1, y1, col1, col2
 * —— 两个颜色分别是第 5、6 个 int，即 ordinal = 4 与 5。
 */
@Mixin(ColoredRectangleRenderState.class)
public abstract class ColoredRectangleRenderStateMixin {

    private static final String CTOR_WITHOUT_SCISSOR =
            "<init>(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
            + "Lnet/minecraft/client/gui/render/TextureSetup;"
            + "Lorg/joml/Matrix3x2fc;IIIIII"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    private static final String CTOR_WITH_SCISSOR =
            "<init>(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
            + "Lnet/minecraft/client/gui/render/TextureSetup;"
            + "Lorg/joml/Matrix3x2fc;IIIIII"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    @ModifyVariable(method = CTOR_WITHOUT_SCISSOR, at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private static int uiTransitions$col1WithoutScissor(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }

    @ModifyVariable(method = CTOR_WITHOUT_SCISSOR, at = @At("HEAD"), argsOnly = true, ordinal = 5)
    private static int uiTransitions$col2WithoutScissor(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }

    @ModifyVariable(method = CTOR_WITH_SCISSOR, at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private static int uiTransitions$col1WithScissor(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }

    @ModifyVariable(method = CTOR_WITH_SCISSOR, at = @At("HEAD"), argsOnly = true, ordinal = 5)
    private static int uiTransitions$col2WithScissor(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }
}
