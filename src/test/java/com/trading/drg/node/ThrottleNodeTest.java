package com.trading.drg.node;

import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ThrottleNodeTest {

    @Test
    public void testThrottleMechanism() throws InterruptedException {
        GraphBuilder builder = GraphBuilder.create();
        ScalarSourceNode input = builder.scalarSource("source", 0.0);
        
        // Throttle updates to at most once every 50ms
        ThrottleNode throttle = builder.throttle("throttle", input, 50);
        
        // Downstream node
        ScalarCalcNode counter = builder.compute("passthrough", val -> val + 1.0, throttle);
        
        StabilizationEngine engine = builder.build();

        // 1. First event should pass through immediately
        input.update(10.0);
        engine.markDirty("source");
        engine.stabilize();
        assertEquals("First event must pass", 10.0, throttle.value(), 0.0001);
        double firstCounterValue = counter.value();
        
        // 2. Immediate second event should be throttled
        input.update(20.0);
        engine.markDirty("source");
        engine.stabilize();
        
        assertEquals("Second event must be throttled", 10.0, throttle.value(), 0.0001);
        assertEquals("Counter should not be evaluated due to throttle suppression", firstCounterValue, counter.value(), 0.0001);

        // 3. Wait strictly past throttle window
        Thread.sleep(60);
        
        input.update(30.0);
        engine.markDirty("source");
        engine.stabilize();
        assertEquals("Event after window must pass", 30.0, throttle.value(), 0.0001);
        assertNotEquals("Counter should evaluate again", firstCounterValue, counter.value(), 0.0001);
    }
}
