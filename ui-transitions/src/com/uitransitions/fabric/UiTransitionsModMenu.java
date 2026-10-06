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
        // 入口页本身不依赖 Cloth Config（曲线编辑器是本模组自带的），
        // 所以即使没装 Cloth 也照样给按钮，进去以后那个按钮会提示缺库。
        try {
            return parent -> new UiTransitionsHubScreen(parent);
        } catch (Throwable t) {
            System.err.println("[UI Transitions] 入口页不可用: " + t);
            return null;
        }
    }

    /** 供测试驱动调用（避免测试代码硬依赖 Cloth Config） */
    public static Screen createConfigScreen(Screen parent) {
        return UiTransitionsConfigScreen.create(parent);
    }
}
