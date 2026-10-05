package com.uitransitions.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

/**
 * ModMenu 集成入口：在模组列表里提供「配置」按钮。
 *
 * 依赖是可选的：
 *   - 没装 ModMenu   → 不会加载这个类（入口不会被调用）；
 *   - 没装 Cloth Config → 返回 null（不显示配置界面），仍可直接编辑
 *     config/ui-transitions.properties。
 * 真正引用 Cloth Config 的代码放在 {@link UiTransitionsConfigScreen} 里，
 * 只有点了配置按钮才会加载那个类，因此缺库也不会崩。
 */
public final class UiTransitionsModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            System.out.println("[UI Transitions] 未安装 Cloth Config，跳过配置界面（可直接编辑 config/ui-transitions.properties）");
            return null;
        }
        try {
            return parent -> UiTransitionsConfigScreen.create(parent);
        } catch (Throwable t) {
            System.err.println("[UI Transitions] 配置界面不可用: " + t);
            return null;
        }
    }

    /** 供测试驱动调用（避免测试代码硬依赖 Cloth Config） */
    public static Screen createConfigScreen(Screen parent) {
        return UiTransitionsConfigScreen.create(parent);
    }
}
