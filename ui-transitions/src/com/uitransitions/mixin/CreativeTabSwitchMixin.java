package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 创造模式物品栏的**分类标签切换**动画（iOS 式滑入）。
 *
 * 为什么不能走界面切换那套：点标签时原版执行的是
 *   selectTab(CreativeModeTab) → refreshCurrentTabContents(...)
 * 也就是**原地刷新**物品列表，并没有 Gui.setScreen —— 所以挂在切屏上的动画永远不会触发。
 *
 * 这里改为挂在 selectTab 上记录一次"换页"，然后在**内容层**（extractRenderState）外包一层
 * 横向平移 + 透明度：底板与标签栏在背景层，天然不动，只有格子内容滑入。
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeTabSwitchMixin {

    private static final String EXTRACT =
            "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    @Inject(method = "selectTab(Lnet/minecraft/world/item/CreativeModeTab;)V", at = @At("HEAD"))
    private void uiTransitions$tabSelected(CreativeModeTab tab, CallbackInfo ci) {
        UiTransitions.onTabSelected((Screen) (Object) this);
    }

    /**
     * 滚动物品列表时也给一次淡入：上下滚动后新出现的物品从半透明淡入，而不是瞬间替换。
     * 复用换页那套淡变（同一份时长/开关配置），这样物品区的表现是一致的。
     */
    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"))
    private void uiTransitionsGridScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                         org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (scrollY != 0.0) {
            UiTransitions.onTabSelected((Screen) (Object) this);
        }
    }

    @Inject(method = EXTRACT, at = @At("HEAD"))
    private void uiTransitions$tabContentBegin(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        UiTransitions.beginTabContent((Screen) (Object) this, extractor);
    }

    @Inject(method = EXTRACT, at = @At("RETURN"))
    private void uiTransitions$tabContentEnd(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        UiTransitions.endTabContent((Screen) (Object) this, extractor);
    }
}
