package com.uitransitions.anim;

/**
 * 时间源。
 *
 * <p>存在的唯一理由是**可测**：动画逻辑一旦直接调 {@link System#nanoTime()}，就无法在断言里
 * 控制"现在几点"，于是"打断接续落在正确的位置""进度过半时时长被改掉会不会跳变"这类问题
 * 只能靠真机看。注入一个时钟，这些就变成纯函数断言。
 *
 * <p>当前实现（{@code UiTransitions.java}）有 7 处直接读 {@code System.nanoTime()}
 * （拦截切屏、tick 补切屏、进度换算、标签切换、滚动、聊天淡入、遮罩），
 * 迁移时逐处替换为 {@code clock.nanos()}。
 *
 * <p><b>契约</b>：必须是单调不减的纳秒计数。不要求与真实时间同步，
 * 只要求两次调用之间的差值有意义。
 */
@FunctionalInterface
public interface Clock {

    /** 当前时间（纳秒）。 */
    long nanos();

    /** 系统时钟：与现有实现完全一致（{@code System.nanoTime()}）。 */
    Clock SYSTEM = System::nanoTime;
}
