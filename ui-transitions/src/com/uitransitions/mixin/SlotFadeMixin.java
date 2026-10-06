package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 逐槽位的透明度控制。这是"原地淡变"（点分类标签 / 滚动物品列表）真正生效的地方。
 *
 * {@code extractSlot} 是 AbstractContainerScreen 里**逐个槽位**调用的绘制方法
 * （extractSlots 遍历 menu.slots 后对它逐个调用），因此它是唯一能区分
 * "这一格是物品列表"还是"这一格是玩家快捷栏"的注入点。
 * 26.3 里没有任何子类覆写它，CreativeModeInventoryScreen 也走这条。
 *
 * 两类格子在这里分道扬镳：
 *   · 玩家快捷栏那一排（容器是玩家背包、且在快捷栏范围内）—— 固定为原版观感，完全不淡；
 *   · 其余（创造模式的物品列表）—— 按"离进入边的距离"逐格渐变（滚动时），
 *     或整片均匀淡入（点标签时）。具体亮度由 UiTransitions.beginSlotFade 决定。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotFadeMixin {

    private static final String EXTRACT_SLOT =
            "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
                    + "Lnet/minecraft/world/inventory/Slot;II)V";

    @Inject(method = EXTRACT_SLOT, at = @At("HEAD"))
    private void uiTransitions$slotBegin(GuiGraphicsExtractor extractor, Slot slot, int mouseX, int mouseY,
                                         CallbackInfo ci) {
        UiTransitions.beginSlotFade(isHotbarRow(slot), slot.y);
    }

    @Inject(method = EXTRACT_SLOT, at = @At("RETURN"))
    private void uiTransitions$slotEnd(GuiGraphicsExtractor extractor, Slot slot, int mouseX, int mouseY,
                                       CallbackInfo ci) {
        UiTransitions.endSlotFade(slot.y);
    }

    /**
     * 是不是玩家快捷栏那一排。
     *
     * 用"容器 + 容器内索引"判断，而不是写死菜单槽位号：创造模式的菜单里
     * 物品列表用的是 CreativeModeInventoryScreen.CONTAINER，快捷栏用的是玩家背包，
     * 靠容器类型就能干净地区分；写死 36-44 在别的界面上会误判。
     */
    private static boolean isHotbarRow(Slot slot) {
        if (slot == null || !(slot.container instanceof Inventory)) {
            return false;
        }
        return slot.getContainerSlot() >= 0 && slot.getContainerSlot() < Inventory.getSelectionSize();
    }
}
