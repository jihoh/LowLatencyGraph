package com.trading.drg.node;

import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimerEwmaNodeTest {

    @Test
    public void testTimerEwmaSingleInput() throws InterruptedException {
        GraphBuilder builder = GraphBuilder.create();
        TimerSourceNode timer = builder.timer("timer", 50);
        TimerEwmaNode ewma = builder.timerEwma("ewma", timer, 100);

        StabilizationEngine engine = builder.build();

        // 1. Initial tick
        engine.markDirty("timer");
        engine.stabilize();
        assertEquals(1.0, ewma.value(), 0.0001);

        // 2. Second tick after 50ms
        Thread.sleep(50);
        engine.markDirty("timer");
        engine.stabilize();

        // Timer output incremented to 2.0. EWMA should move towards 2.0
        assertTrue("EWMA should lie between initial (1.0) and new tick (2.0)", ewma.value() > 1.0 && ewma.value() < 2.0);
    }

    @Test
    public void testTimerEwmaDualInputDecayDuringMarketSilence() throws InterruptedException {
        GraphBuilder builder = GraphBuilder.create();
        ScalarSourceNode price = builder.scalarSource("price", 100.0);
        TimerSourceNode timer = builder.timer("timer", 50);

        // 50ms half-life time-decay EWMA driven by timer node
        TimerEwmaNode ewma = builder.timerEwma("ewma", price, timer, 50);

        StabilizationEngine engine = builder.build();

        // 1. Initial setup at T0
        engine.markDirty("price");
        engine.markDirty("timer");
        engine.stabilize();
        assertEquals(100.0, ewma.value(), 0.0001);

        // 2. Update price to 200.0 during market event
        price.update(200.0);

        // 3. Market silence: price source is NOT marked dirty, but timer ticks after 50ms (1 half-life)
        Thread.sleep(50);
        engine.markDirty("timer");
        engine.stabilize();

        // After 1 half-life (~50ms) with price at 200.0, EWMA should have decayed ~50% towards 200.0 (near 150.0)
        double valAfterOneHalfLife = ewma.value();
        assertTrue("EWMA should decay towards 200.0 on timer tick during market silence (expected ~150). Was: " + valAfterOneHalfLife,
                valAfterOneHalfLife > 140.0 && valAfterOneHalfLife < 160.0);
    }
}
