package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 文字（标题、物品数量、按钮文字等）的透明度。
 *
 * 构造器：(Font, FormattedCharSequence, Matrix3x2fc, int x, int y, int color, int backgroundColor,
 *          boolean shadow, boolean seeThrough, ScreenRectangle)
 * int 参数的 ordinal：x=0, y=1, color=2, backgroundColor=3。
 * 背景色也一起调制，否则淡出时文字背景会突兀地残留。
 */
@Mixin(GuiTextRenderState.class)
public abstract class GuiTextRenderStateMixin {

    private static final String CTOR =
            "<init>(Lnet/minecraft/client/gui/Font;"
            + "Lnet/minecraft/util/FormattedCharSequence;"
            + "Lorg/joml/Matrix3x2fc;IIIIZZ"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    @ModifyVariable(method = CTOR, at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private static int uiTransitions$textColor(int color) {
        return UiTransitions.applyAlphaText(color);
    }

    @ModifyVariable(method = CTOR, at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private static int uiTransitions$backgroundColor(int color) {
        return UiTransitions.applyAlphaText(color);
    }
}
