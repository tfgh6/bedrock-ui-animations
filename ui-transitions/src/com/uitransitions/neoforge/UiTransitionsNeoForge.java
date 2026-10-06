package com.uitransitions.neoforge;

import com.uitransitions.DependencyCheck;
import com.uitransitions.fabric.UiTransitionsHubScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge 侧入口：依赖检查 + 把配置界面注册到 NeoForge **自带的**模组列表。
 *
 * ## 与 Fabric 的差异
 *
 * Fabric 需要额外装 Mod Menu 才有「配置」按钮，所以那边 ModMenu 是硬前置；
 * NeoForge **自带模组管理界面**，配置入口由加载器提供 —— 这里只要注册
 * {@link IConfigScreenFactory} 就会出现按钮，不需要任何额外模组。
 *
 * ## 为什么用 ModList 查容器，而不是构造器注入
 *
 * 注册扩展点必须走 `ModContainer.registerExtensionPoint(...)`：旧的
 * `ModLoadingContext.get().registerExtensionPoint(...)` 在 1.20.5 就被标记废弃、之后被移除，
 * 用了它只会抛 NoSuchMethodError，被 try/catch 一吞，表现成"模组装了但配置按钮根本没有"。
 *
 * 容器本身则用 ModList 按 mod id 查（而不是往构造器里注入 IEventBus/ModContainer）：
 * 注入签名在不同 NeoForge 版本上不保证一致，写错就直接构造失败崩游戏；
 * 查一次则哪个版本都能用，拿不到也只是没有配置按钮，不会连累启动。
 *
 * ## mod id 不一样
 *
 * Cloth Config 在 Fabric 上是 `cloth-config`（连字符），在 NeoForge 上是 `cloth_config`
 * （下划线，见它自己的 neoforge.mods.toml）。两个都要认，否则会把"装了"误判成"没装"。
 */
@Mod(value = "ui_transitions", dist = Dist.CLIENT)
public final class UiTransitionsNeoForge {

    private static final String MOD_ID = "ui_transitions";

    public UiTransitionsNeoForge() {
        // 第一件事就是查依赖：缺了直接抛，让加载器把这段说明显示出来。
        // 这里刻意**不**包 try/catch —— 包住就又回到"静默降级"了。
        boolean clothPresent = ModList.get().isLoaded(DependencyCheck.CLOTH_ID_NEOFORGE)
                || ModList.get().isLoaded(DependencyCheck.CLOTH_ID_FABRIC);
        if (!clothPresent) {
            DependencyCheck.failMissing("NeoForge", DependencyCheck.CLOTH_NAME,
                    DependencyCheck.CLOTH_IDS, DependencyCheck.CLOTH_URL);
        }
        System.out.println("[Bedrock UI Animations] NeoForge 入口：已检测到 Cloth Config");

        try {
            ModContainer container = ModList.get().getModContainerById(MOD_ID).orElse(null);
            if (container == null) {
                System.err.println("[Bedrock UI Animations] 拿不到自己的 ModContainer，"
                        + "配置入口未注册（模组列表里不会出现配置按钮）");
                return;
            }
            // 工厂是延迟调用的：真点了配置按钮才会加载界面类。
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (modContainer, parent) -> new UiTransitionsHubScreen(parent));
            System.out.println("[Bedrock UI Animations] 已注册 NeoForge 配置入口（模组列表里的配置按钮）");
        } catch (Throwable t) {
            // 注册失败不该拦着游戏启动，但必须留下痕迹 —— 否则用户只会看到"没有配置按钮"
            System.err.println("[Bedrock UI Animations] 注册 NeoForge 配置入口失败: " + t);
            Throwable cause = t.getCause();
            while (cause != null) {
                System.err.println("[Bedrock UI Animations]   根因: " + cause);
                cause = cause.getCause();
            }
        }
    }
}
