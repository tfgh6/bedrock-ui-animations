package com.uitransitions.neoforge;

import com.uitransitions.DependencyCheck;
import com.uitransitions.fabric.UiTransitionsHubScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge 侧入口：依赖检查 + 把配置界面注册到「模组列表 → 配置」。
 *
 * ## NeoForge 这边为什么要特殊处理
 *
 * 1. **mod id 不一样**：Cloth Config 在 Fabric 上是 `cloth-config`（连字符），
 *    在 NeoForge 上是 `cloth_config`（下划线，见它自己的 neoforge.mods.toml）。
 *    两个都要认，否则会把"装了"误判成"没装"。
 * 2. **报错时机不一样**：Fabric 的 depends 会在加载入口之前就拦住；
 *    NeoForge 是先构造 @Mod 类，所以检查要放在构造函数的第一件事，
 *    而且**不能**用 try/catch 把异常吞掉 —— 吞掉就等于又变成静默降级了。
 * 3. **neoforge.mods.toml 里也要同步**：把 cloth_config 标成 required，
 *    这样即使构造函数没跑到，加载器自己也会给出"缺少依赖"的界面。
 */
@Mod(value = "ui_transitions", dist = Dist.CLIENT)
public final class UiTransitionsNeoForge {

    public UiTransitionsNeoForge() {
        // 第一件事就是查依赖：缺了直接抛，让加载器把这段说明显示出来。
        // 这里刻意**不**包 try/catch —— 包住就又回到"静默降级"了。
        boolean clothPresent = ModList.get().isLoaded(DependencyCheck.CLOTH_ID_NEOFORGE)
                || ModList.get().isLoaded(DependencyCheck.CLOTH_ID_FABRIC);
        if (!clothPresent) {
            DependencyCheck.failMissingCloth("NeoForge");
        }
        System.out.println("[Bedrock UI Animations] NeoForge 入口：已检测到 Cloth Config");

        try {
            // 注册的工厂延迟加载界面类：真点了配置按钮才会加载，
            // 万一 Cloth Config 出问题也只是那一次点不开，不会连带崩游戏。
            ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class,
                    () -> (IConfigScreenFactory) (modContainer, parent) ->
                            new UiTransitionsHubScreen(parent));
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
