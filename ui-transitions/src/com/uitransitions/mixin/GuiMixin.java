package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 关闭动画需要「先播动画、再切屏」：
 *   - setScreen HEAD（可取消）：当前是容器界面且目标为 null（关闭界面）时，取消这次原版切屏，
 *     记下动画起点，让旧界面继续渲染并播放滑出；
 *   - tick TAIL：动画播完后，用 Gui.setScreen 真正完成切换（BYPASS 标志防止递归拦截）。
 *
 * 26.3 里 Minecraft.setScreenAndShow 也是转调 Gui.setScreen，所以这里是唯一的切屏入口。
 * 这里直接调用公开的 Gui.screen() / Gui.setScreen()，不需要反射。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void uiTransitions$setScreen(Screen target, CallbackInfo ci) {
        if (UiTransitions.interceptSetScreen((Gui) (Object) this, target)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void uiTransitions$tick(CallbackInfo ci) {
        UiTransitions.tick((Gui) (Object) this);
    }
}
