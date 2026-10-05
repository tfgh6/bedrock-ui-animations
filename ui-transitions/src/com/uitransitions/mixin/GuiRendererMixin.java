package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物品提交阶段：套用它在提取阶段被记下的透明度，结束后立刻清理。
 *
 * 为什么需要这一对注入：物品图标不是普通的贴图块，而是在提交（submit）阶段
 * 才从物品图集里绘制出来，那时已经离开了屏幕内容的渲染窗口，
 * 因此必须单独把透明度"带"过来。
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    private static final String SUBMIT =
            "submitBlitFromItemAtlas(Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;"
            + "Lnet/minecraft/client/gui/render/GuiItemAtlas$SlotView;)V";

    @Inject(method = SUBMIT, at = @At("HEAD"))
    private void uiTransitions$itemSubmitBegin(GuiItemRenderState state, GuiItemAtlas.SlotView slotView,
                                               CallbackInfo ci) {
        UiTransitions.beginItemSubmit(state);
    }

    @Inject(method = SUBMIT, at = @At("RETURN"))
    private void uiTransitions$itemSubmitEnd(GuiItemRenderState state, GuiItemAtlas.SlotView slotView,
                                             CallbackInfo ci) {
        UiTransitions.endItemSubmit(state);
    }
}
