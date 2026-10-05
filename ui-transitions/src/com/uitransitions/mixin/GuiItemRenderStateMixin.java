package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物品是从图集里在「提交阶段」才绘制的，而屏幕内容是在「提取阶段」录制的，
 * 所以物品的透明度必须在这里记下来，等提交时再套用（见 GuiRendererMixin）。
 */
@Mixin(GuiItemRenderState.class)
public abstract class GuiItemRenderStateMixin {

    private static final String CTOR =
            "<init>(Lorg/joml/Matrix3x2f;"
            + "Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;II"
            + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

    @Inject(method = CTOR, at = @At("TAIL"))
    private void uiTransitions$tagItem(Matrix3x2f pose, TrackingItemStackRenderState itemStack,
                                       int x, int y, ScreenRectangle scissorArea, CallbackInfo ci) {
        UiTransitions.tagItem(this);
    }
}
