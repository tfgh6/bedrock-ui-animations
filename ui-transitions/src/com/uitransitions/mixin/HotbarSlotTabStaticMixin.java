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
 * 容器界面里「玩家背包最下面那一行」（快捷栏槽位）不参与分类标签换页的淡变。
 *
 * 那一行不是 HUD 快捷栏，而是菜单里的普通槽位（InventoryMenu 索引 36–44），
 * 由 AbstractContainerScreen.extractSlot 逐个绘制 —— 所以要在这一层排除，
 * 不能只在 Hud 那边处理。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class HotbarSlotTabStaticMixin {

    private static final String EXTRACT_SLOT =
            "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V";

    private static final int HOTBAR_FIRST = 36;
    private static final int HOTBAR_LAST = 44;

    @Inject(method = EXTRACT_SLOT, at = @At("HEAD"))
    private void uiTransitionsHotbarSlotBegin(GuiGraphicsExtractor extractor, Slot slot, int mouseX, int mouseY,
                                              CallbackInfo ci) {
        if (isHotbarSlot(slot)) {
            UiTransitions.pauseForTabStatic(extractor);
        }
    }

    @Inject(method = EXTRACT_SLOT, at = @At("RETURN"))
    private void uiTransitionsHotbarSlotEnd(GuiGraphicsExtractor extractor, Slot slot, int mouseX, int mouseY,
                                            CallbackInfo ci) {
        if (isHotbarSlot(slot)) {
            UiTransitions.resumeAfterTabStatic(extractor);
        }
    }

    private static boolean isHotbarSlot(Slot slot) {
        return slot != null && slot.index >= HOTBAR_FIRST && slot.index <= HOTBAR_LAST;
    }
}
