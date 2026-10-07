package com.uitransitions.mixin;

import com.uitransitions.UiTransitions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天栏淡入的**计时**部分：每次渲染 HUD 时记一下"聊天内容指纹"，变了就重新开始淡入。
 *
 * 为什么不挂在"新消息进来"的方法上：26.3 里消息可以来自玩家、服务器系统、客户端系统三条路
 * （addPlayerMessage / addServerSystemMessage / addClientSystemMessage），逐条挂钩容易漏；
 * 而"这一帧聊天内容跟上一帧不一样了"是个**收敛的判据**，哪条路进来的都会被发现。
 *
 * 指纹只用公开接口取（行数 + 最近聊天条数），不碰任何私有字段 ——
 * 这样原版改内部实现时最多是"淡入不触发了"，不会崩。
 */
@Mixin(Hud.class)
public abstract class ChatFadeNoteMixin {

    @Inject(method = "extractChat", at = @At("HEAD"))
    private void uiTransitionsNoteChat(net.minecraft.client.gui.GuiGraphicsExtractor extractor,
                                       net.minecraft.client.DeltaTracker tracker, CallbackInfo ci) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gui == null || minecraft.gui.hud == null) {
                return;
            }
            // 注意路径是 gui.hud.getChat()：`Gui` 上没有 getChat，聊天挂在 Hud 上
            net.minecraft.client.gui.components.ChatComponent chat = minecraft.gui.hud.getChat();
            if (chat == null) {
                return;
            }
            int lines = chat.getLinesPerPage();
            int recent = chat.getRecentChat().size();
            UiTransitions.noteChatContent(lines, recent * 31 + lines);
        } catch (Throwable ignored) {
            // 记指纹失败不影响聊天栏本身
        }
    }
}
