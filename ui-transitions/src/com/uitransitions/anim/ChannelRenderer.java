package com.uitransitions.anim;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把"通道 + alpha → 调制后的颜色"这一步落地，并负责**同类错误只报一次**。
 *
 * <p>现有实现里 {@code modulate} 的 catch 走的是 {@code report("modulate", t)}，
 * 而 {@code report} 的语义是"同一个 where 只报一次"（{@code UiTransitions.java:1538-1542}）。
 * 渲染热路径每帧每元素都会走这里，所以"只报一次"不是可选优化 —— 否则一次异常会刷爆日志。
 * 本类按**通道**分别去重，比原来按一个字符串去重更细：哪条通道坏了，就只报哪条。
 *
 * <p>包私有：它是 {@link Engine} 的实现细节，不属于对外契约。
 */
final class ChannelRenderer {

    private final Set<Channel> reported = ConcurrentHashMap.newKeySet();

    /**
     * @param channel 通道（决定是否预乘）
     * @param color   原始颜色
     * @param alpha   透明度乘子
     * @return 调制后的颜色
     */
    int apply(Channel channel, int color, float alpha) {
        return ColorMath.apply(color, alpha, channel.premultiplied());
    }

    /** 每个通道只报一次，且走 stderr（与现有 report 一致，便于在日志里搜）。 */
    void reportOnce(Channel channel, Throwable t) {
        if (this.reported.add(channel)) {
            System.err.println("[UI Transitions] 颜色通道 " + channel + " 出错（同类错误只报一次）: " + t);
        }
    }

    /** 供测试观察：是否已经报过某通道。 */
    boolean hasReported(Channel channel) {
        return this.reported.contains(channel);
    }
}
