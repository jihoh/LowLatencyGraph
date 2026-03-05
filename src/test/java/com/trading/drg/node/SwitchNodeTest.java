package com.trading.drg.node;

import com.trading.drg.api.Node;
import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.fn.Fn1;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

public class SwitchNodeTest {

    @Test
    public void testSwitchRouting() {
        GraphBuilder graph = GraphBuilder.create();

        // Sources
        ScalarSourceNode input = graph.scalarSource("InputVal", 10.0);
        ScalarSourceNode threshold = graph.scalarSource("Threshold", 5.0);

        // Condition: InputVal > Threshold
        BooleanNode isHigh = graph.condition("IsHigh", input, val -> val > threshold.value());

        // The switch
        SwitchNode router = graph.switchNode("Router", input, isHigh);

        // Telemetry
        AtomicInteger trueBranchCount = new AtomicInteger();
        AtomicInteger falseBranchCount = new AtomicInteger();

        // Branches
        Node trueBranch = graph.compute("TrueBranch", (Fn1) val -> {
            trueBranchCount.incrementAndGet();
            return val * 2;
        }, router);

        Node falseBranch = graph.compute("FalseBranch", (Fn1) val -> {
            falseBranchCount.incrementAndGet();
            return val / 2;
        }, router);

        // Register branches explicitly to the SwitchNode
        graph.markTrueBranch(router, trueBranch);
        graph.markFalseBranch(router, falseBranch);

        // Build and run topological sort
        StabilizationEngine engine = graph.build();

        // INIT: input=10.0, threshold=5.0 -> condition IS TRUE
        engine.stabilize();
        assertEquals("True branch should evaluate once on init", 1, trueBranchCount.get());
        assertEquals("False branch should skip evaluation", 0, falseBranchCount.get());

        // FIRE 1: Change input to 20.0 -> condition stays TRUE
        input.update(20.0);
        engine.markDirty("InputVal");
        engine.stabilize();
        assertEquals("True branch computes the new value 20", 2, trueBranchCount.get());
        assertEquals("False branch is still skipped", 0, falseBranchCount.get());

        // FIRE 2: Change input to 2.0 -> condition becomes FALSE
        input.update(2.0);
        engine.markDirty("InputVal");
        engine.stabilize();
        assertEquals("True branch skips evaluation because condition is now false", 2, trueBranchCount.get());
        assertEquals("False branch finally evaluates since condition is false", 1, falseBranchCount.get());

        // FIRE 3: Change input to 3.0 -> condition stays FALSE
        input.update(3.0);
        engine.markDirty("InputVal");
        engine.stabilize();
        assertEquals("True branch skips", 2, trueBranchCount.get());
        assertEquals("False branch evaluates new value 3", 2, falseBranchCount.get());
    }
}
