package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 用矩阵平移把屏幕的「背景层」与「内容层」一起移动，实现滑入/滑出。
 *
 * 26.3 的界面分两层画（容器界面还会在背景层里顺带画两样不该动的东西）：
 *
 *   extractBackground(...)  背景层
 *     ├─ extractTransparentBackground(...)  变暗遮罩      → 默认不位移、但跟着淡出（fadeDim）
 *     ├─ 容器底板 / 槽位背景（子类 super 之后 blit）        → 跟着动
 *     └─ Hud.extractDeferredSubtitles()     音效字幕      → 默认不动也不淡（animateSubtitles）
 *   extractRenderState(...) 内容层
 *     └─ 槽内物品、标题文字等                              → 跟着动
 *
 * 只包内容层就会出现"物品在动、整块底板硬邦邦直接出现"；
 * 不抵消遮罩与字幕，它们就会跟着一起动/一起黑。三者都靠"在子区域里抵消矩阵平移、
 * 并按需复位透明度"实现，不需要反射，也不改写任何原版状态。
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    private static final String EXTRACT_ALL =
            "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private static final String BACKGROUND_CALL =
            "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private static final String CONTENT_CALL =
            "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private static final String VEIL_CALL =
            "Lnet/minecraft/client/gui/screens/Screen;extractTransparentBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V";

    private static final String SUBTITLE_CALL =
            "Lnet/minecraft/client/gui/Hud;extractDeferredSubtitles()V";

    private static final String BACKGROUND_METHOD =
            "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    // ------------------------------------------------------------------ 背景层（容器底板）

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

    // ------------------------------------------------------------------ 遮罩：默认静止但跟随淡出

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = VEIL_CALL, shift = At.Shift.BEFORE))
    private void uiTransitions$veilBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = VEIL_CALL, shift = At.Shift.AFTER))
    private void uiTransitions$veilEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                       float partialTick, CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    // ------------------------------------------------------------------ 模糊背景：留在原地

    private static final String BLUR_CALL =
            "Lnet/minecraft/client/gui/screens/Screen;extractBlurredBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V";

    private static final String MENU_BG_CALL =
            "Lnet/minecraft/client/gui/screens/Screen;extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V";

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = BLUR_CALL, shift = At.Shift.BEFORE))
    private void uiTransitions$blurBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);      // 模糊属于背景，不该跟着界面上下滑
    }

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = BLUR_CALL, shift = At.Shift.AFTER))
    private void uiTransitions$blurEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                       float partialTick, CallbackInfo ci) {
        UiTransitions.resumeAfterStaticRegion(extractor);
    }

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = MENU_BG_CALL, shift = At.Shift.BEFORE))
    private void uiTransitions$menuBgBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo ci) {
        UiTransitions.pauseForStaticRegion(extractor);
    }

    @Inject(method = BACKGROUND_METHOD, at = @At(value = "INVOKE", target = MENU_BG_CALL, shift = At.Shift.AFTER))
    private void uiTransitions$menuBgEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo ci) {
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

    // ------------------------------------------------------------------ 内容层（物品 / 文字）

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
}
