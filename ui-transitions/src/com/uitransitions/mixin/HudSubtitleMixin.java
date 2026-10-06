package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 音效字幕不参与界面过渡动画（animateSubtitles=true 时才放开）。
 *
 * 为什么要挂在 Hud 而不是 Screen 的调用点上：
 * 字幕是在背景层里"顺带"绘制的，调用它的地方不止一个 ——
 * Screen.extractBackground（默认实现）、PauseScreen.extractBackground（自己完整重写、不调 super）、
 * LoadingOverlay。早期版本把抵消写在 Screen.extractBackground 的 INVOKE 上，
 * 于是暂停菜单这类自己调用字幕的界面完全没被覆盖：开了 animateAllScreens 之后，
 * 打开暂停菜单字幕照样会跟着一起动。
 *
 * 挂在 Hud.extractDeferredSubtitles 这一个点上，所有调用方就都被覆盖了。
 * 需要的 extractor 由 UiTransitions 在 beginBackgroundLayer 时暂存。
 */
@Mixin(Hud.class)
public abstract class HudSubtitleMixin {

    private static final String SUBTITLES = "extractDeferredSubtitles()V";

    @Inject(method = SUBTITLES, at = @At("HEAD"))
    private void uiTransitionsSubtitleBegin(CallbackInfo ci) {
        UiTransitions.pauseForHud();
    }

    @Inject(method = SUBTITLES, at = @At("RETURN"))
    private void uiTransitionsSubtitleEnd(CallbackInfo ci) {
        UiTransitions.resumeAfterHud();
    }
}
