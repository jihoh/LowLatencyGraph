package com.trading.drg.engine;

import com.trading.drg.api.StabilizationListener;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.ScalarNode;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.util.ScalarCutoffs;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class StabilizationEngineTest {

    private TopologicalOrder topology;
    private StabilizationEngine engine;
    private ScalarSourceNode sourceA;
    private ScalarSourceNode sourceB;

    // Tracking execution orders
    private List<String> executionLog;

    @Before
    public void setUp() {
        executionLog = new ArrayList<>();

        sourceA = new ScalarSourceNode("A", 1.0);
        sourceB = new ScalarSourceNode("B", 5.0);

        // C = A + B
        ScalarNode c = new ScalarCalcNode("C", ScalarCutoffs.ALWAYS, () -> {
            executionLog.add("C");
            return sourceA.value() + sourceB.value();
        });

        // D = C * 2
        ScalarNode d = new ScalarCalcNode("D", ScalarCutoffs.ALWAYS, () -> {
            executionLog.add("D");
            return c.value() * 2;
        });

        // Topology:
        // A \
        // C -> D
        // B /
        topology = TopologicalOrder.builder()
                .addNode(sourceA)
                .addNode(sourceB)
                .addNode(c)
                .addNode(d)
                .addEdge("A", "C")
                .addEdge("B", "C")
                .addEdge("C", "D")
                .markSource("A")
                .markSource("B")
                .build();

        engine = new StabilizationEngine(topology);
    }

    @Test
    public void testInitialStabilizationPropagatesAllSources() {
        // Initially, both A and B are marked dirty by the constructor.
        assertEquals(0, engine.epoch());

        int stabilized = engine.stabilize();

        // C and D should both execute
        assertTrue(executionLog.contains("C"));
        assertTrue(executionLog.contains("D"));
        assertEquals(1, engine.epoch());

        // A and B do not 'execute' lambda calculation in the strict sense,
        // they just act as sources, but C and D are stabilized.
        // So nodes stabilized = 4 (A, B, C, D) since sources also "stabilize" if dirty.
        // Wait, source node's stabilize() returns true if changed.
        assertTrue("Expected 4 nodes stabilized (A, B, C, D)", stabilized == 4);
        assertEquals(4, engine.lastStabilizedCount());
    }

    @Test
    public void testCleanGraphDoesNoWork() {
        // First stabilize
        engine.stabilize();
        executionLog.clear();

        // Second stabilize with no data changes -> 0 nodes processed.
        int stabilized = engine.stabilize();
        assertEquals(0, stabilized);
        assertTrue(executionLog.isEmpty());
        assertEquals(2, engine.epoch());
    }

    @Test
    public void testPartialPropagation() {
        engine.stabilize(); // initialize
        executionLog.clear();

        // Update A
        sourceA.update(2.0);
        engine.markDirty("A");

        int stabilized = engine.stabilize();

        // C and D should execute since A cascaded to C, and C cascaded to D.
        // A is dirty -> C is dirty -> D is dirty.
        // So 3 nodes stabilized (A, C, D). B is skipped.
        assertEquals(3, stabilized);
        assertEquals(List.of("C", "D"), executionLog);
    }

    @Test
    public void testCutoffStopsPropagation() {
        // Replace D with a NEVER cutoff node
        ScalarNode dCutoff = new ScalarCalcNode("D_Stop", ScalarCutoffs.NEVER, () -> {
            executionLog.add("D_Stop");
            return 99.0;
        });

        // E = D_Stop + 1
        ScalarNode e = new ScalarCalcNode("E", ScalarCutoffs.ALWAYS, () -> {
            executionLog.add("E");
            return dCutoff.value() + 1;
        });

        TopologicalOrder customTopo = TopologicalOrder.builder()
                .addNode(sourceA)
                .addNode(dCutoff)
                .addNode(e)
                .addEdge("A", "D_Stop")
                .addEdge("D_Stop", "E")
                .markSource("A")
                .build();

        StabilizationEngine customEngine = new StabilizationEngine(customTopo);
        customEngine.stabilize();
        executionLog.clear();

        // Update A -> triggers D_Stop. D_Stop NEVER propagates -> E is skipped.
        sourceA.update(2.0);
        customEngine.markDirty("A");
        int stab = customEngine.stabilize();

        assertEquals(2, stab); // A and D_Stop
        assertEquals(List.of("D_Stop"), executionLog); // E should not fire
    }

    @Test
    public void testExceptionPropagatesNaN() {
        ScalarNode explosive = new ScalarCalcNode("Explosive", ScalarCutoffs.ALWAYS, () -> {
            throw new RuntimeException("Simulated failure");
        });

        ScalarNode downstream = new ScalarCalcNode("Downstream", ScalarCutoffs.ALWAYS, () -> {
            return explosive.value() + 10.0;
        });

        TopologicalOrder badTopo = TopologicalOrder.builder()
                .addNode(sourceA)
                .addNode(explosive)
                .addNode(downstream)
                .addEdge("A", "Explosive")
                .addEdge("Explosive", "Downstream")
                .markSource("A")
                .build();

        StabilizationEngine badEngine = new StabilizationEngine(badTopo);

        // The engine should handle the error internally and continue running
        // It should NOT throw an exception to the caller.
        badEngine.stabilize();

        // The explosive node should evaluate to NaN because it caught its own error
        assertTrue("Explosive node should output NaN on error", Double.isNaN(explosive.value()));

        // Downstream should also evaluate to NaN because the calculation involved NaN
        assertTrue("Downstream node should propagate NaN", Double.isNaN(downstream.value()));
    }

    @Test
    public void testLargeGraphBitSetLogic() {
        // Create 100 isolated sources to test the long[] dirtyWords > 64 bits logic
        TopologicalOrder.Builder builder = TopologicalOrder.builder();
        List<ScalarSourceNode> nodes = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            ScalarSourceNode sn = new ScalarSourceNode("S" + i, 0.0);
            builder.addNode(sn).markSource(sn.name());
            nodes.add(sn);
        }

        TopologicalOrder hugeTopo = builder.build();
        StabilizationEngine hugeEngine = new StabilizationEngine(hugeTopo);

        // Init
        assertEquals(100, hugeEngine.stabilize());

        // Mark node 75 dirty (crosses the 64-bit word boundary into word[1])
        nodes.get(75).update(1.0);
        hugeEngine.markDirty("S75");

        assertEquals(1, hugeEngine.stabilize());
    }

    @Test
    public void testStabilizationListener() {
        AtomicInteger calls = new AtomicInteger();
        engine.setListener(new StabilizationListener() {
            @Override
            public void onStabilizationStart(long epoch) {
                calls.incrementAndGet();
            }

            @Override
            public void onNodeStabilized(long epoch, int topoIndex, String nodeName, boolean changed,
                    long durationNanos) {
                calls.incrementAndGet();
            }

            @Override
            public void onNodeError(long epoch, int topoIndex, String nodeName, Throwable error) {
            }

            @Override
            public void onStabilizationEnd(long epoch, int stabilizedCount) {
                calls.incrementAndGet();
            }
        });

        engine.stabilize();

        // 1 start, 4 nodes, 1 end = 6 calls
        assertEquals(6, calls.get());
    }
}
