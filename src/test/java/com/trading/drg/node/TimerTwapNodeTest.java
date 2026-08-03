package com.trading.drg.node;

import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimerTwapNodeTest {

    @Test
    public void testTimerTwapSingleInput() throws InterruptedException {
        GraphBuilder builder = GraphBuilder.create();
        TimerSourceNode timer = builder.timer("timer", 50);
        TimerTwapNode twap = builder.timerTwap("twap", timer);

        StabilizationEngine engine = builder.build();

        // 1. First tick (timer value = 1.0)
        engine.markDirty("timer");
        engine.stabilize();
        assertEquals(1.0, twap.value(), 0.0001);

        // 2. Second tick after 50ms (timer value = 2.0)
        Thread.sleep(50);
        engine.markDirty("timer");
        engine.stabilize();

        // Previous tick value was 1.0 held for 50ms. TWAP should be 1.0
        assertEquals(1.0, twap.value(), 0.01);

        // 3. Third tick after 50ms (timer value = 3.0)
        Thread.sleep(50);
        engine.markDirty("timer");
        engine.stabilize();

        // TWAP of (1.0 for 50ms + 2.0 for 50ms) / 100ms = 1.5
        assertEquals(1.5, twap.value(), 0.05);
    }

    @Test
    public void testTimerTwapDualInputMarketSilence() throws InterruptedException {
        GraphBuilder builder = GraphBuilder.create();
        ScalarSourceNode price = builder.scalarSource("price", 100.0);
        TimerSourceNode timer = builder.timer("timer", 50);
        TimerTwapNode twap = builder.timerTwap("twap", price, timer);

        StabilizationEngine engine = builder.build();

        // T0: price = 100.0
        engine.markDirty("price");
        engine.markDirty("timer");
        engine.stabilize();
        assertEquals(100.0, twap.value(), 0.0001);

        // Price changes to 200.0
        price.update(200.0);
        engine.markDirty("price");
        engine.stabilize();

        // 100ms market silence (only timer ticks)
        Thread.sleep(100);
        engine.markDirty("timer");
        engine.stabilize();

        // 200.0 was held for 100ms. TWAP should equal 200.0
        assertEquals(200.0, twap.value(), 0.01);
    }
}
