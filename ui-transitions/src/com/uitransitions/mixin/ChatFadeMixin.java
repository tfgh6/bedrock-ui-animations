package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天栏（HUD 上的聊天消息）新消息淡入 —— **施加透明度**的那一半。
 * （计时那一半见 {@link ChatFadeNoteMixin}。）
 *
 * ## 注入点怎么选的
 *
 * `ChatComponent.forEachLine` 是每行问一次透明度的地方，看起来最合适 ——
 * 但它的参数类型 `ChatComponent$AlphaCalculator` 是**私有**嵌套类型，签名里根本写不出来。
 * 曾经试过"把 mixin 放进 net.minecraft.client.gui.components 包"来绕过，
 * 结果被 `build_jar.py` 的产物纯净性硬闸拦下：往 net/minecraft 下放类会让
 * NeoForge 因 JPMS 包冲突直接拒绝启动（这正是 1.3.0~1.4.0 翻车的原因）。
 * 所以改挂 public 的 `extractRenderState`。
 *
 * ## 怎么施加效果
 *
 * 聊天文字走 `GuiTextRenderState`，本模组已经在那条通道上调制透明度
 * （见 GuiTextRenderStateMixin → applyAlphaText）。所以在**画聊天的那一段**
 * 把透明度乘上"聊天淡入系数"，整块聊天就会跟着淡入 —— 不需要复制任何原版逻辑。
 *
 * ## 为什么是"整块"而不是"逐条"
 *
 * 逐条要按行施加透明度，而那正是上面那个私有类型堵死的路。
 * 现在的做法给出的是"新消息出现时整块聊天快速淡入一次"，观感接近但不是逐条交错。
 * 这个取舍写在 README 的已知限制里，不假装它是逐条动画。
 */
@Mixin(ChatComponent.class)
public abstract class ChatFadeMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void uiTransitionsChatFadeBegin(CallbackInfo ci) {
        UiTransitions.beginChatRender();
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void uiTransitionsChatFadeEnd(CallbackInfo ci) {
        UiTransitions.endChatRender();
    }
}
