package com.trading.drg.node;

import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimerSourceNodeTest {

    @Test
    public void testTimerSourceNodeStabilizesAndIncrementsTicks() {
        TimerSourceNode timer = new TimerSourceNode("timer1", 100);

        assertEquals("timer1", timer.name());
        assertEquals(100, timer.intervalMs());
        assertEquals(0.0, timer.value(), 1e-9);

        // Stabilize directly
        boolean changed = timer.stabilize();
        assertTrue("Stabilize should return true on tick", changed);
        assertEquals(1.0, timer.value(), 1e-9);

        boolean changed2 = timer.stabilize();
        assertTrue("Stabilize should return true on next tick", changed2);
        assertEquals(2.0, timer.value(), 1e-9);
    }

    @Test
    public void testTimerIntegrationInGraph() {
        GraphBuilder builder = GraphBuilder.create();
        TimerSourceNode timer = builder.timer("heartbeat", 50);
        
        // Downstream calculation node that reads timer ticks
        builder.compute("tickCount", t -> t, timer);
        StabilizationEngine engine = builder.build();

        // Initial stabilization
        int count1 = engine.stabilize();
        assertTrue("Initial stabilization includes timer and compute node", count1 >= 1);

        // Mark timer dirty and stabilize again
        engine.markDirty("heartbeat");
        int count2 = engine.stabilize();
        assertEquals("Both heartbeat and downstream tickCount recompute", 2, count2);
    }
}
