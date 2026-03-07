package com.trading.drg.node;

import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimeDecayNodeTest {

    @Test
    public void testExponentialTimeDecay() throws InterruptedException {
        GraphBuilder builder = GraphBuilder.create();
        ScalarSourceNode input = builder.scalarSource("source", 100.0);
        
        // 50ms half-life
        TimeDecayNode ewma = builder.timeDecay("ewma", input, 50);
        
        StabilizationEngine engine = builder.build();

        // 1. First event establishes the baseline
        engine.stabilize();
        assertEquals("First event must exactly match the input", 100.0, ewma.value(), 0.0001);

        // 2. Wait exactly one half-life (50ms) and spike the input to 200
        Thread.sleep(50);
        
        input.update(200.0);
        engine.markDirty("source");
        engine.stabilize();
        
        // Formula: ewma = (currentInput * (1 - decay)) + (previousEwma * decay)
        // Since elapsed == halfLife, decay is 0.5
        // ewma = (200 * 0.5) + (100 * 0.5) = 150.0
        
        double valAfterOneHalfLife = ewma.value();
        
        // Due to Thread.sleep() OS scheduling jitter, elapsed time won't be EXACTLY 50.0000ms.
        // It will likely be slightly more (e.g., 51-55ms), meaning decay will be slightly less than 0.5,
        // and the value will weight slightly MORE towards the new 200 input.
        // We assert it's reasonably close to 150 (between 145 and 155).
        assertTrue("Value should be near the midpoint (150) after one half-life. Was: " + valAfterOneHalfLife,  
                   valAfterOneHalfLife > 145.0 && valAfterOneHalfLife < 155.0);
                   
        // 3. Wait a very long time (10x half-life = 500ms) with a slightly different input
        Thread.sleep(500);
        input.update(200.1); 
        engine.markDirty("source");
        engine.stabilize();
        
        // After 10 half-lives, the weight of the new value is ~99.9% (1023/1024) and old value is ~0.1% (1/1024)
        // Previous ewma was ~150. New input is 200.1.
        // Expected = (200.1 * 1023/1024) + (150 * 1/1024) = ~200.05
        assertEquals("Value should be ~99.9% converged after 10 half-lives", 200.05, ewma.value(), 0.05);
    }
}
