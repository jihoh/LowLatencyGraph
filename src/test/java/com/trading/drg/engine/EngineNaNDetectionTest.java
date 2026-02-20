package com.trading.drg.engine;

import com.trading.drg.api.*;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class EngineNaNDetectionTest {

    // Custom node that evaluates to NaN
    private static class NaNNode implements Node<Double>, ScalarValue {
        private final String name;

        public NaNNode(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean stabilize() {
            return true; // Always say changed
        }

        @Override
        public Double value() {
            return Double.NaN;
        }

        @Override
        public double doubleValue() {
            return Double.NaN;
        }
    }

    @Test
    public void testNaNDetectionTriggersError() {
        // 1. Setup Graph with custom NaN node
        // We need a source to kickstart the engine (source nodes are marked dirty
        // initially)
        // But since we can mark any node dirty manually, we can just use our NaNNode.
        NaNNode nanNode = new NaNNode("BadNode");

        TopologicalOrder topology = TopologicalOrder.builder()
                .addNode(nanNode)
                // We don't mark it as a "SourceNode" in the builder sense (which usually
                // implies external input),
                // but we will mark it dirty manually to trigger stabilization.
                .build();

        StabilizationEngine engine = new StabilizationEngine(topology);

        // 2. Setup Listener
        AtomicBoolean errorReported = new AtomicBoolean(false);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        engine.setListener(new StabilizationListener() {
            @Override
            public void onStabilizationStart(long epoch) {
            }

            @Override
            public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed,
                    long durationNanos) {
            }

            @Override
            public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
                errorReported.set(true);
                errorRef.set(error);
            }

            @Override
            public void onStabilizationEnd(long epoch, int nodesStabilized) {
            }
        });

        // 3. Mark dirty to trigger stabilize()
        engine.markDirty("BadNode");

        // 4. Stabilize
        // The engine should detect that BadNode stabilized to NaN and call onNodeError
        try {
            engine.stabilize();
        } catch (RuntimeException e) {
            // Engine might throw if healthy=false
            System.out.println("Engine threw exception: " + e.getMessage());
        }

        // 5. Verify
        assertTrue("onNodeError should have been called for NaN value", errorReported.get());
        assertNotNull("Error exception should not be null", errorRef.get());
        assertTrue("Error should be RuntimeException", errorRef.get() instanceof RuntimeException);
        assertEquals("Error message should match", "Node evaluated to NaN", errorRef.get().getMessage());

        System.out.println("Verified: Engine reported error for NaN value: " + errorRef.get().getMessage());
    }
}
