package com.trading.drg.api;

import com.trading.drg.node.TimerSourceNode;

/**
 * Primitive-specialized callback for timer ticks, avoiding {@code Integer} autoboxing.
 *
 * @see com.trading.drg.CoreGraph#startTimers(TimerTickCallback)
 */
@FunctionalInterface
public interface TimerTickCallback {
    void onTick(TimerSourceNode timer, int nodeId);
}
