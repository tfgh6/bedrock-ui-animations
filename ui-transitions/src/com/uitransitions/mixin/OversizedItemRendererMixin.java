package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.render.pip.OversizedItemRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 超大物品（尺寸超出物品图集槽位的那些）走的是自己的画中画渲染器。
 *
 * OversizedItemRenderer **覆写**了 PictureInPictureRenderer.blitTexture，
 * 因此 PictureInPictureRendererMixin 的注入对它完全不起作用 ——
 * 表现就是其它内容都在淡出，而那个大图标纹丝不动。
 * 这里把同一条透明度逻辑再接一次，补上这个缺口。
 */
@Mixin(OversizedItemRenderer.class)
public abstract class OversizedItemRendererMixin {

    private static final String BLIT =
            "blitTexture(Lnet/minecraft/client/renderer/state/gui/pip/OversizedItemRenderState;"
                    + "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;)V";

    @Inject(method = BLIT, at = @At("HEAD"))
    private void uiTransitions$oversizedBlitBegin(OversizedItemRenderState state, GuiRenderState guiRenderState,
                                                  CallbackInfo ci) {
        UiTransitions.beginPipBlit(state);
    }

    @Inject(method = BLIT, at = @At("RETURN"))
    private void uiTransitions$oversizedBlitEnd(OversizedItemRenderState state, GuiRenderState guiRenderState,
                                                CallbackInfo ci) {
        UiTransitions.endPipBlit();
    }
}
