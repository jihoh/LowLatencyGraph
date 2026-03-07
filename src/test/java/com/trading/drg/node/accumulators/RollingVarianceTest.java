package com.trading.drg.node.accumulators;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RollingVarianceTest {

    @Test
    public void testWelfordMath() {
        RollingVariance var = new RollingVariance(4);
        
        // Add 2
        var.onAdd(2.0);
        assertEquals(0.0, var.result(), 0.0001);
        
        // Add 4
        var.onAdd(4.0);
        // values: 2, 4. Mean: 3. Var: ((2-3)^2 + (4-3)^2) / 1 = 2.0
        assertEquals(2.0, var.result(), 0.0001);
        
        // Add 4
        var.onAdd(4.0);
        // values: 2, 4, 4. Mean: 3.33. Var: ((2-3.33)^2 + (4-3.33)^2*2) / 2 = (1.777 + 0.444*2)/2 = 1.333
        assertEquals(1.3333333, var.result(), 0.0001);
        
        // Remove 2 (simulating sliding window)
        var.onRemove(2.0);
        // values: 4, 4. Mean: 4. Var: 0.0
        assertEquals(0.0, var.result(), 0.0001);
    }
}
