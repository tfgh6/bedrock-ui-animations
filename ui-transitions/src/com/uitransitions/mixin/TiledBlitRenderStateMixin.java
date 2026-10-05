package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 平铺贴图块（部分界面的可平铺背景）的透明度。
 *
 * 两个构造器的 int 参数顺序一致：tileWidth, tileHeight, x0, y0, x1, y1, color
 * —— 颜色是第 7 个，即 ordinal = 6。
 */
@Mixin(TiledBlitRenderState.class)
public abstract class TiledBlitRenderStateMixin {

    private static final String CTOR_WITHOUT_SCISSOR =
            "<init>(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
            + "Lnet/minecraft/client/gui/render/TextureSetup;"
            + "Lorg/joml/Matrix3x2f;IIIIIIFFFFI"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    private static final String CTOR_WITH_SCISSOR =
            "<init>(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
            + "Lnet/minecraft/client/gui/render/TextureSetup;"
            + "Lorg/joml/Matrix3x2f;IIIIIIFFFFI"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    @ModifyVariable(method = CTOR_WITHOUT_SCISSOR, at = @At("HEAD"), argsOnly = true, ordinal = 6)
    private static int uiTransitions$colorWithoutScissor(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }

    @ModifyVariable(method = CTOR_WITH_SCISSOR, at = @At("HEAD"), argsOnly = true, ordinal = 6)
    private static int uiTransitions$colorWithScissor(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }
}
