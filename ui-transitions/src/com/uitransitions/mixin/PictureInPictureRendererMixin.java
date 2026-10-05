package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 背包里的玩家小模型走的是画中画（PIP）通道：先把实体渲到离屏贴图，再在渲染阶段贴回界面。
 *
 * 也就是说它既不在 Screen 的背景层/内容层窗口里，PictureInPictureRenderState.pose() 又恒为单位矩阵，
 * 所以原来完全不受动画影响 —— 底板在淡出、模型却纹丝不动。
 *
 * blitTexture 就是"把离屏贴图贴回界面"的那一步：在这里把本帧的动画透明度交给渲染状态，
 * 贴图本身就会跟着一起渐变（BlitRenderState 的 alpha 通道已有统一处理）。
 */
@Mixin(PictureInPictureRenderer.class)
public abstract class PictureInPictureRendererMixin {

    private static final String BLIT =
            "blitTexture(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;"
                    + "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;)V";

    @Inject(method = BLIT, at = @At("HEAD"))
    private void uiTransitions$pipBlitBegin(PictureInPictureRenderState state, GuiRenderState guiRenderState,
                                            CallbackInfo ci) {
        UiTransitions.beginPipBlit(state);
    }

    @Inject(method = BLIT, at = @At("RETURN"))
    private void uiTransitions$pipBlitEnd(PictureInPictureRenderState state, GuiRenderState guiRenderState,
                                          CallbackInfo ci) {
        UiTransitions.endPipBlit();
    }
}
