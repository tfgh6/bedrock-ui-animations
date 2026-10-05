package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把屏幕的「背景层」与「内容层」整体平移，实现滑入/滑出；并把不该动的东西留在原地。
 *
 * 26.3 的界面分两层绘制：
 *   extractBackground(...)   背景层：压暗遮罩 + 模糊 + 菜单底衬 + 容器底板 + 字幕
 *   extractRenderState(...)  内容层：物品、文字、玩家小模型
 *
 * 关键点：背景层里**只有容器底板该跟着滑**，其余都属于"屏幕背景"，必须留在原地、只淡出。
 * 早期版本我在调用点做抵消，只覆盖了基础实现那一条路；而 PauseScreen 等界面会自己重写
 * extractBackground 并调用别的重载 —— 于是那些没被抵消的底衬/压暗就被平移带走了，
 * 表现为动画中有一条暗色横带被推出屏幕。
 *
 * 现在改成**在方法内部**抵消：给每一个背景绘制方法各自包一层 HEAD/RETURN，
 * 于是无论谁调用、走哪个重载，都被覆盖。
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    private static final String EXTRACT_ALL =
            "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private static final String BACKGROUND_CALL =
            "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private static final String CONTENT_CALL =
            "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private static final String SUBTITLE_CALL =
            "Lnet/minecraft/client/gui/Hud;extractDeferredSubtitles()V";

    private static final String BACKGROUND_METHOD =
            "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    // ------------------------------------------------------------------ 层次窗口

    @Inject(method = EXTRACT_ALL, at = @At(value = "INVOKE", target = BACKGROUND_CALL, shift = At.Shift.BEFORE))
    private void uiTransitions$backgroundBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        UiTransitions.beginBackgroundLayer((Screen) (Object) this, extractor);
    }

    @Inject(method = EXTRACT_ALL, at = @At(value = "INVOKE", target = BACKGROUND_CALL, shift = At.Shift.AFTER))
    private void uiTransitions$backgroundEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        UiTransitions.endBackgroundLayer((Screen) (Object) this, extractor);
    }

    @Inject(method = EXTRACT_ALL, at = @At(value = "INVOKE", target = CONTENT_CALL, shift = At.Shift.BEFORE))
    private void uiTransitions$contentBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo ci) {
        UiTransitions.beginContentLayer((Screen) (Object) this, extractor);
    }

    @Inject(method = EXTRACT_ALL, at = @At(value = "INVOKE", target = CONTENT_CALL, shift = At.Shift.AFTER))
    private void uiTransitions$contentEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo ci) {
        UiTransitions.endContentLayer((Screen) (Object) this, extractor);
    }

    // ------------------------------------------------------------------ 留在原地的背景（方法级抵消）

    @Inject(method = "extractTransparentBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("HEAD"))
    private void uiTransitions$veilBegin(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = "extractTransparentBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("RETURN"))
    private void uiTransitions$veilEnd(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    @Inject(method = "extractBlurredBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("HEAD"))
    private void uiTransitions$blurBegin(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = "extractBlurredBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("RETURN"))
    private void uiTransitions$blurEnd(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("HEAD"))
    private void uiTransitions$menuBgBegin(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("RETURN"))
    private void uiTransitions$menuBgEnd(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    /** 5 参重载：暂停菜单等界面走的就是这一条 */
    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V",
            at = @At("HEAD"))
    private void uiTransitions$menuBgAreaBegin(GuiGraphicsExtractor extractor, int x0, int y0, int x1, int y1,
                                              CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V",
            at = @At("RETURN"))
    private void uiTransitions$menuBgAreaEnd(GuiGraphicsExtractor extractor, int x0, int y0, int x1, int y1,
                                            CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    /** 静态方法：处理器也必须是 static */
    @Inject(method = "extractMenuBackgroundTexture(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/resources/Identifier;IIFFII)V", at = @At("HEAD"))
    private static void uiTransitions$menuBgTexBegin(GuiGraphicsExtractor extractor,
                                                     net.minecraft.resources.Identifier texture,
                                                     int x, int y, float u, float v, int width, int height,
                                                     CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = "extractMenuBackgroundTexture(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/resources/Identifier;IIFFII)V", at = @At("RETURN"))
    private static void uiTransitions$menuBgTexEnd(GuiGraphicsExtractor extractor,
                                                   net.minecraft.resources.Identifier texture,
                                                   int x, int y, float u, float v, int width, int height,
                                                   CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    @Inject(method = "extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V", at = @At("HEAD"))
    private void uiTransitions$panoramaBegin(GuiGraphicsExtractor extractor, float partialTick, CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = "extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V", at = @At("RETURN"))
    private void uiTransitions$panoramaEnd(GuiGraphicsExtractor extractor, float partialTick, CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    // ------------------------------------------------------------------ 音效字幕：默认完全不动

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = SUBTITLE_CALL, shift = At.Shift.BEFORE))
    private void uiTransitions$subtitleBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        UiTransitions.pauseForHud(extractor);
    }

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = SUBTITLE_CALL, shift = At.Shift.AFTER))
    private void uiTransitions$subtitleEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo ci) {
        UiTransitions.resumeAfterHud(extractor);
    }
}
