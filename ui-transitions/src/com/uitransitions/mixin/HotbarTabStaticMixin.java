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
 * 快捷栏不参与"分类标签换页"的横向滑动。
 *
 * 创造模式物品栏换页时，我把内容层整体做横向平移；而玩家的快捷栏也在这条绘制路径上，
 * 于是它跟着一起滑了 —— 观感上"底部那排格子也在动"，这是不对的。
 *
 * 这里在快捷栏绘制前后把它平移回去（并保持不透明），只让物品区滑。
 */
@Mixin(Hud.class)
public abstract class HotbarTabStaticMixin {

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
