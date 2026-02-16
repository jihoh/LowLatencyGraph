package com.trading.drg.core;

import com.trading.drg.GraphBuilder;
import com.trading.drg.node.DoubleSourceNode;
import com.trading.drg.core.DoubleReadable;
import org.junit.Test;
import static org.junit.Assert.*;

public class CircuitBreakerTest {

    @Test
    public void testCircuitBreakerTrips() {
        GraphBuilder g = GraphBuilder.create("breaker_test");
        var srcA = g.doubleSource("SrcA", 10.0);

        // LogicA throws if input > 50
        var logicA = g.compute("LogicA", (a) -> {
            if (a > 50)
                throw new RuntimeException("Overload!");
            return a;
        }, srcA);

        var context = g.buildWithContext();
        var engine = context.engine();
        var nodes = context.nodesByName();

        assertTrue(engine.isHealthy());

        // 1. Initial success
        engine.stabilize();
        assertTrue(engine.isHealthy());

        // 2. Trigger Failure
        ((DoubleSourceNode) nodes.get("SrcA")).updateDouble(100.0);
        engine.markDirty("SrcA");

        try {
            engine.stabilize();
            fail("Should have thrown RuntimeException due to Fail Fast");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Stabilization failed"));
            // Check cause
            assertEquals("Overload!", e.getCause().getMessage());
        }

        // 3. Verify Circuit Breaker is OPEN (Unhealthy)
        assertFalse("Engine should be unhealthy", engine.isHealthy());

        // 4. Try again - should fail immediately
        try {
            engine.stabilize();
            fail("Should have thrown IllegalStateException immediately");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("unhealthy state"));
        }

        // 5. Reset Health
        engine.resetHealth();
        assertTrue(engine.isHealthy());

        // 6. Fix input and retry
        ((DoubleSourceNode) nodes.get("SrcA")).updateDouble(10.0);
        engine.markDirty("SrcA");

        engine.stabilize();
        assertTrue(engine.isHealthy());
        assertEquals(10.0, ((DoubleReadable) nodes.get("LogicA")).doubleValue(), 0.001);
    }
}
