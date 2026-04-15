package com.trading.drg.engine;

import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.api.ScalarCutoffs;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class UpdatedNodesTest {

    private ScalarSourceNode sourceA;
    private ScalarSourceNode sourceB;
    private ScalarCalcNode nodeC;
    private ScalarCalcNode nodeD;
    private StabilizationEngine engine;

    @Before
    public void setUp() {
        sourceA = new ScalarSourceNode("A", 1.0);
        sourceB = new ScalarSourceNode("B", 5.0);

        // C = A + B
        nodeC = new ScalarCalcNode("C", ScalarCutoffs.ALWAYS, () -> sourceA.value() + sourceB.value());

        // D = C * 2
        nodeD = new ScalarCalcNode("D", ScalarCutoffs.ALWAYS, () -> nodeC.value() * 2);

        // A, B -> C -> D
        TopologicalOrder topology = TopologicalOrder.builder()
                .addNode(sourceA)
                .addNode(sourceB)
                .addNode(nodeC)
                .addNode(nodeD)
                .addEdge("A", "C")
                .addEdge("B", "C")
                .addEdge("C", "D")
                .markSource("A")
                .markSource("B")
                .build();

        engine = new StabilizationEngine(topology);
    }

    @Test
    public void testUpdatedNodesIncludeAllChanged() {
        engine.stabilize();
        UpdatedNodes updated = engine.updatedNodes();

        // All nodes that changed should be included, including sources.
        assertTrue("Source A should be included", updated.isChanged(engine.topology().topoIndex("A")));
        assertTrue("Source B should be included", updated.isChanged(engine.topology().topoIndex("B")));
        assertTrue("Derived C should be included", updated.isChanged(engine.topology().topoIndex("C")));
        assertTrue("Derived D should be included", updated.isChanged(engine.topology().topoIndex("D")));
        assertEquals(4, updated.count());
    }

    @Test
    public void testSimpleForEach() {
        engine.stabilize();
        UpdatedNodes updated = engine.updatedNodes();

        List<String> names = new ArrayList<>();
        updated.forEach(node -> names.add(node.name()));

        assertEquals(List.of("A", "B", "C", "D"), names);
    }

    @Test
    public void testCleanGraphHasNoUpdates() {
        engine.stabilize(); // initial pass
        engine.stabilize(); // nothing dirty

        UpdatedNodes updated = engine.updatedNodes();
        assertEquals(0, updated.count());
    }

    @Test
    public void testClearedBetweenCycles() {
        engine.stabilize();
        assertEquals(4, engine.updatedNodes().count());

        // No changes, stabilize again
        engine.stabilize();
        assertEquals(0, engine.updatedNodes().count());
    }

    @Test
    public void testPartialPropagation() {
        engine.stabilize(); // init

        sourceA.update(2.0);
        engine.markDirty("A");
        engine.stabilize();

        UpdatedNodes updated = engine.updatedNodes();

        // A changed, C and D propagated.
        assertTrue(updated.isChanged(engine.topology().topoIndex("A")));
        assertTrue(updated.isChanged(engine.topology().topoIndex("C")));
        assertTrue(updated.isChanged(engine.topology().topoIndex("D")));
        assertEquals(3, updated.count());
    }

    @Test
    public void testCutoffNodeNotInUpdated() {
        // E uses NEVER cutoff -> stabilize() returns false -> not in updated set
        ScalarCalcNode nodeE = new ScalarCalcNode("E", ScalarCutoffs.NEVER, () -> sourceA.value() * 3);

        // F depends on E
        ScalarCalcNode nodeF = new ScalarCalcNode("F", ScalarCutoffs.ALWAYS, () -> nodeE.value() + 1);

        TopologicalOrder topo = TopologicalOrder.builder()
                .addNode(sourceA)
                .addNode(nodeE)
                .addNode(nodeF)
                .addEdge("A", "E")
                .addEdge("E", "F")
                .markSource("A")
                .build();

        StabilizationEngine eng = new StabilizationEngine(topo);
        eng.stabilize(); // init

        sourceA.update(99.0);
        eng.markDirty("A");
        eng.stabilize();

        UpdatedNodes updated = eng.updatedNodes();
        // Source A changed, E uses NEVER cutoff -> changed=false -> not tracked, F
        // never dirtied
        assertTrue(updated.isChanged(topo.topoIndex("A")));
        assertFalse(updated.isChanged(topo.topoIndex("E")));
        assertFalse(updated.isChanged(topo.topoIndex("F")));
        assertEquals(1, updated.count());
    }

    @Test
    public void testLargeBitSetCrossesWordBoundary() {
        // 100 sources feeding 100 derived nodes -> test word boundary crossing
        TopologicalOrder.Builder builder = TopologicalOrder.builder();
        List<ScalarSourceNode> sources = new ArrayList<>();
        List<ScalarCalcNode> derived = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            ScalarSourceNode src = new ScalarSourceNode("S" + i, (double) i);
            builder.addNode(src).markSource(src.name());
            sources.add(src);
        }
        for (int i = 0; i < 100; i++) {
            final int idx = i;
            ScalarCalcNode calc = new ScalarCalcNode("D" + i, ScalarCutoffs.ALWAYS,
                    () -> sources.get(idx).value() * 2);
            builder.addNode(calc);
            builder.addEdge("S" + i, "D" + i);
            derived.add(calc);
        }

        TopologicalOrder topo = builder.build();
        StabilizationEngine eng = new StabilizationEngine(topo);
        eng.stabilize(); // init

        // Update one source that maps to a derived node beyond word 0
        sources.get(75).update(999.0);
        eng.markDirty("S75");
        eng.stabilize();

        UpdatedNodes updated = eng.updatedNodes();
        // S75 (source) + D75 (derived) both changed
        assertEquals(2, updated.count());
        assertTrue(updated.isChanged(topo.topoIndex("S75")));
        assertTrue(updated.isChanged(topo.topoIndex("D75")));
    }
}
