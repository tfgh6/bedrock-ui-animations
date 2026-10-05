package com.uitransitions.mixin;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取创造模式物品栏的私有滚动位置字段。
 *
 * 用它来判断"这一帧物品列表是否真的滚动了" —— 因为手机上每次点击都会被映射成拖动事件，
 * 挂在输入事件上的淡变会变成"点哪里都闪"，只有比较滚动位置才可靠。
 */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeScrollAccessor {

    @Accessor("scrollOffs")
    float uiTransitionsScrollOffset();
}
