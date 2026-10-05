package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 贴图块（容器面板、图标等）的透明度。
 *
 * BlitRenderState 在 26.3 有两个构造器：姿态参数分别是 Matrix3x2f / Matrix3x2fc。
 * 这里把两个构造器分别写全描述符，避免 `method = "<init>"` 同时匹配两个重载
 * （Mixin 要求每个被匹配的方法都注入成功，否则整体失败）。
 * 两个构造器的 int 参数顺序一致：x0, y0, x1, y1, color —— 颜色是第 5 个，即 ordinal = 4。
 */
@Mixin(BlitRenderState.class)
public abstract class BlitRenderStateMixin {

    private static final String CTOR_MATRIX3X2F =
            "<init>(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
            + "Lnet/minecraft/client/gui/render/TextureSetup;"
            + "Lorg/joml/Matrix3x2f;IIIIFFFFI"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    private static final String CTOR_MATRIX3X2FC =
            "<init>(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
            + "Lnet/minecraft/client/gui/render/TextureSetup;"
            + "Lorg/joml/Matrix3x2fc;IIIIFFFFI"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    @ModifyVariable(method = CTOR_MATRIX3X2F, at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private static int uiTransitions$colorMatrix3x2f(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }

    @ModifyVariable(method = CTOR_MATRIX3X2FC, at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private static int uiTransitions$colorMatrix3x2fc(int color) {
        return UiTransitions.applyAlphaBlit(color);
    }
}
