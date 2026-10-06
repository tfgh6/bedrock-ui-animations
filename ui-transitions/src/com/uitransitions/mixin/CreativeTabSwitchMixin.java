package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 创造模式物品栏的**原地淡变**：分类标签切换 + 物品列表滚动。
 *
 * 为什么不能走界面切换那套：点标签时原版执行的是
 *   selectTab(CreativeModeTab) → refreshCurrentTabContents(...)
 * 也就是**原地刷新**物品列表，并没有 Gui.setScreen —— 所以挂在切屏上的动画永远不会触发。
 * 滚动同理，只是改 scrollOffs。
 *
 * 这里只负责"记录一次原地淡变"，透明度由 SlotFadeMixin 逐槽位施加。
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeTabSwitchMixin {

    private static final String EXTRACT =
            "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    /** 原版的滚动位置（0 = 顶部，1 = 底部）；每帧读一次用来判断是否真的滚动了 */
    @Shadow
    private float scrollOffs;

    @Inject(method = "selectTab(Lnet/minecraft/world/item/CreativeModeTab;)V", at = @At("HEAD"))
    private void uiTransitions$tabSelected(CreativeModeTab tab, CallbackInfo ci) {
        UiTransitions.onTabSelected((Screen) (Object) this);
    }

    @Inject(method = EXTRACT, at = @At("HEAD"))
    private void uiTransitions$tabContentBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        // 先看滚动（可能启动一段逐格渐变），再让 beginTabContent 统一设定本帧的整体透明度
        UiTransitions.onGridScrollIfChanged((Screen) (Object) this, this.scrollOffs);
        UiTransitions.beginTabContent((Screen) (Object) this, extractor);
    }

    @Inject(method = EXTRACT, at = @At("RETURN"))
    private void uiTransitions$tabContentEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        UiTransitions.endTabContent((Screen) (Object) this, extractor);
    }
}
