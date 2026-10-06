package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 没有界面时，跨维度过渡遮罩画在 HUD 这一层。
 *
 * 为什么需要这个兜底：换维度结束后 LevelLoadingScreen 就被撤掉了，这时屏幕上
 * **没有任何 Screen**，"遮罩淡出、逐渐露出新世界"这一段就没地方画。
 *
 * 为什么挂在 Hud 而不是 Gui：Gui.extractRenderState 里的绘制顺序是
 *   Hud.extractRenderState -> Screen.extractRenderStateWithTooltipAndSubtitles -> Toast
 * 而它自己并不把绘制器传出来（方法签名只有 DeltaTracker）。Hud 这一层拿得到绘制器，
 * 又在 Screen 之前 —— 正好满足"没有界面时才轮到我画"。
 * 有界面时由 ScreenMixin 的收尾处画（那样才盖得住界面），两边互斥，不会重复画。
 */
@Mixin(Hud.class)
public abstract class HudVeilMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
    private void uiTransitions$portalVeil(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker,
                                          CallbackInfo ci) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.gui != null && minecraft.gui.screen() != null) {
                return;     // 有界面：交给 ScreenMixin 那边画，避免重复
            }
            UiTransitions.drawPortalVeil(extractor);
        } catch (Throwable ignored) {
            // 遮罩画不出来不影响游戏
        }
    }
}
