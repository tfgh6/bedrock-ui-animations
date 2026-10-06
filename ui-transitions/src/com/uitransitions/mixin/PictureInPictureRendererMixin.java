package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 背包里的玩家小模型（布娃娃）走的是画中画（PIP）通道：
 * 先把实体渲到离屏贴图，再在渲染阶段贴回界面。
 * 附魔台的附魔书、地图、旗帜预览也是同一套。
 *
 * 它既不在 Screen 的背景层/内容层窗口里，PictureInPictureRenderState.pose() 又恒为单位矩阵，
 * 所以只做透明度的话，界面滑动时它会**只淡不动** —— 面板滑走了，
 * 布娃娃/附魔书还孤零零留在原来的位置。
 *
 * 这里解决两件事：
 *   1. HEAD/RETURN：把本帧的动画透明度交给渲染状态，贴图跟着一起渐变；
 *   2. @ModifyArg：把内容层的位移叠到那张贴图的位姿上，让它跟着界面一起滑。
 *
 * 第 2 点之所以能精确命中：全游戏只有 PictureInPictureRenderer.blitTexture 这一处
 * 调用 PictureInPictureRenderState.pose()，而 blitTexture 里唯一决定位置的就是
 * 传给 BlitRenderState 构造器的那一个 pose 参数，因此不会波及其它 blit。
 */
@Mixin(PictureInPictureRenderer.class)
public abstract class PictureInPictureRendererMixin {

    private static final String BLIT =
            "blitTexture(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;"
                    + "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;)V";

    /** blitTexture 里构造 BlitRenderState 用的那个重载（姿态是 Matrix3x2fc、带两个 scissor） */
    private static final String BLIT_STATE_CTOR =
            "Lnet/minecraft/client/renderer/state/gui/BlitRenderState;<init>("
                    + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
                    + "Lnet/minecraft/client/gui/render/TextureSetup;"
                    + "Lorg/joml/Matrix3x2fc;IIIIFFFFI"
                    + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;"
                    + "Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V";

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

    /** 第 3 个参数（下标 2）是位姿，换成叠了动画位移的版本 */
    @ModifyArg(method = BLIT,
            at = @At(value = "INVOKE", target = BLIT_STATE_CTOR),
            index = 2)
    private Matrix3x2fc uiTransitions$shiftPipPose(Matrix3x2fc pose) {
        return UiTransitions.shiftPipPose(pose);
    }
}
