package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 滚动时的**逐格**渐变：按每个槽位自身的位置决定透明度。
 *
 * 效果：列表滚动时，新进入视野的那几行从半透明逐渐变实 —— 也就是手机列表里
 * "一个个小物件滚上来、边上来边显形"的手感。
 *
 * 只在滚动淡变进行中生效；平时（含开关界面）完全不介入。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotScrollFadeMixin {

    private static final String EXTRACT_SLOT =
            "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V";

    @Inject(method = EXTRACT_SLOT, at = @At("HEAD"))
    private void uiTransitionsSlotFadeBegin(GuiGraphicsExtractor extractor, Slot slot, int mouseX, int mouseY,
                                            CallbackInfo ci) {
        UiTransitions.applySlotFade(extractor, slot == null ? 0 : slot.y, slot == null ? -1 : slot.index);
    }

    @Inject(method = EXTRACT_SLOT, at = @At("RETURN"))
    private void uiTransitionsSlotFadeEnd(GuiGraphicsExtractor extractor, Slot slot, int mouseX, int mouseY,
                                          CallbackInfo ci) {
        UiTransitions.clearSlotFade(extractor);
    }
}
