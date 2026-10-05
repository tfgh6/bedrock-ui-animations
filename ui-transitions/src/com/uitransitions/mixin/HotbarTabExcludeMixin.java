package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 快捷栏不参与「分类标签切换」的淡入淡出。
 *
 * 需求：标签切换时只有物品区淡变，底部那排快捷栏槽位**保持原样**（不位移、也不淡出）。
 * 位移部分在 1.1.2 已从换页动画里彻底移除；这里再让快捷栏保持不透明，
 * 于是它既不会移动、也不会跟着淡。
 */
@Mixin(Hud.class)
public abstract class HotbarTabExcludeMixin {

    private static final String DECORATIONS =
            "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";

    private static final String ITEM_HOTBAR =
            "extractItemHotbar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";

    @Inject(method = DECORATIONS, at = @At("HEAD"))
    private void uiTransitionsHotbarBegin(GuiGraphicsExtractor extractor, DeltaTracker delta, CallbackInfo ci) {
        UiTransitions.pauseForTabStatic(extractor);
    }

    @Inject(method = DECORATIONS, at = @At("RETURN"))
    private void uiTransitionsHotbarEnd(GuiGraphicsExtractor extractor, DeltaTracker delta, CallbackInfo ci) {
        UiTransitions.resumeAfterTabStatic(extractor);
    }

    @Inject(method = ITEM_HOTBAR, at = @At("HEAD"))
    private void uiTransitionsItemHotbarBegin(GuiGraphicsExtractor extractor, DeltaTracker delta, CallbackInfo ci) {
        UiTransitions.pauseForTabStatic(extractor);
    }

    @Inject(method = ITEM_HOTBAR, at = @At("RETURN"))
    private void uiTransitionsItemHotbarEnd(GuiGraphicsExtractor extractor, DeltaTracker delta, CallbackInfo ci) {
        UiTransitions.resumeAfterTabStatic(extractor);
    }
}
