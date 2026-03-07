package com.trading.drg.node;

import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WindowedNodeTest {

    @Test
    public void testRollingSum() {
        GraphBuilder builder = GraphBuilder.create();
        ScalarSourceNode input = builder.scalarSource("source", 10.0);
        
        // Window of 3
        WindowedNode sum = builder.rollingSum("sum", input, 3);
        StabilizationEngine engine = builder.build();

        engine.stabilize();
        assertEquals(10.0, sum.value(), 0.0001);

        input.update(20.0);
        engine.markDirty("source");
        engine.stabilize();
        assertEquals(30.0, sum.value(), 0.0001);

        input.update(30.0);
        engine.markDirty("source");
        engine.stabilize();
        assertEquals(60.0, sum.value(), 0.0001);

        // Window is full (10, 20, 30). Next update drops 10.
        input.update(40.0);
        engine.markDirty("source");
        engine.stabilize();
        // sum(20, 30, 40) = 90
        assertEquals(90.0, sum.value(), 0.0001);
    }

    @Test
    public void testRollingMax() {
        GraphBuilder builder = GraphBuilder.create();
        ScalarSourceNode input = builder.scalarSource("source", 5.0);
        
        // Window of 3
        WindowedNode max = builder.rollingMax("max", input, 3);
        StabilizationEngine engine = builder.build();

        engine.stabilize();
        assertEquals(5.0, max.value(), 0.0001);

        input.update(10.0);  // Buffer: [5, 10]
        engine.markDirty("source");
        engine.stabilize();
        assertEquals(10.0, max.value(), 0.0001);

        input.update(2.0);   // Buffer: [5, 10, 2]
        engine.markDirty("source");
        engine.stabilize();
        assertEquals(10.0, max.value(), 0.0001);

        input.update(8.0);   // Buffer drops 5: [10, 2, 8]
        engine.markDirty("source");
        engine.stabilize();
        assertEquals(10.0, max.value(), 0.0001);

        input.update(3.0);   // Buffer drops 10: [2, 8, 3] -> new max is 8
        engine.markDirty("source");
        engine.stabilize();
        assertEquals(8.0, max.value(), 0.0001);
    }

    @Test
    public void testRollingVariance() {
        GraphBuilder builder = GraphBuilder.create();
        ScalarSourceNode input = builder.scalarSource("source", 2.0);
        
        WindowedNode var = builder.rollingVariance("var", input, 4);
        StabilizationEngine engine = builder.build();

        engine.stabilize();
        assertEquals(0.0, var.value(), 0.0001); // N=1, variance is 0

        input.update(4.0);
        engine.markDirty("source");
        engine.stabilize();
        // Values [2, 4], Mean=3, Var = sum((x-mean)^2)/(n-1) = (1+1)/1 = 2.0
        assertEquals(2.0, var.value(), 0.0001);

        input.update(6.0);
        engine.markDirty("source");
        engine.stabilize();
        // Values [2, 4, 6], Mean=4, Var = (4+0+4)/2 = 4.0
        assertEquals(4.0, var.value(), 0.0001);
        
        input.update(8.0);
        engine.markDirty("source");
        engine.stabilize();
        // Values [2, 4, 6, 8], Mean=5, Var = (9+1+1+9)/3 = 6.66666
        assertEquals(6.6666666, var.value(), 0.0001);
        
        input.update(10.0); // Drops the 2. Window is now [4, 6, 8, 10]
        engine.markDirty("source");
        engine.stabilize();
        // Window [4, 6, 8, 10], Mean=7, Var = (9+1+1+9)/3 = 6.66666
        assertEquals(6.6666666, var.value(), 0.0001);
    }
}
