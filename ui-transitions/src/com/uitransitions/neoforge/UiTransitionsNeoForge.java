package com.uitransitions.neoforge;

import com.uitransitions.fabric.UiTransitionsConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge 侧入口：只负责把配置界面注册到「模组列表 → 配置」。
 *
 * 注意 Cloth Config 的 mod id 在两个加载器上**不一样**：
 *   Fabric   → cloth-config（连字符）
 *   NeoForge → cloth_config（下划线，见其 neoforge.mods.toml 的 modId）
 * 两个都要认，否则会误判成"没装"而不注册配置界面。
 */
@Mod(value = "ui_transitions", dist = Dist.CLIENT)
public final class UiTransitionsNeoForge {

    public UiTransitionsNeoForge() {
        System.out.println("[Bedrock UI Animations] NeoForge 入口构造函数已执行");
        try {
            boolean clothPresent = ModList.get().isLoaded("cloth_config")
                    || ModList.get().isLoaded("cloth-config");
            System.out.println("[Bedrock UI Animations] Cloth Config 检测结果: " + clothPresent);
            if (!clothPresent) {
                System.out.println("[Bedrock UI Animations] 未安装 Cloth Config，跳过配置界面"
                        + "（可直接编辑 config/ui-transitions.properties）");
                return;
            }
            // 注册的工厂延迟加载界面类：真点了配置按钮才会加载，
            // 万一 Cloth Config 出问题也只是那一次点不开，不会连带崩游戏。
            ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class,
                    () -> (IConfigScreenFactory) (modContainer, parent) ->
                            UiTransitionsConfigScreen.create(parent));
            System.out.println("[Bedrock UI Animations] 已注册 NeoForge 配置界面（模组列表里的配置按钮）");
        } catch (Throwable t) {
            System.err.println("[Bedrock UI Animations] 注册 NeoForge 配置界面失败（不影响游戏）: " + t);
            Throwable cause = t.getCause();
            while (cause != null) {
                System.err.println("[Bedrock UI Animations]   根因: " + cause);
                cause = cause.getCause();
            }
        }
    }
}
