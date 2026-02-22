package com.trading.drg.engine;

import com.trading.drg.api.Node;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.util.ScalarCutoffs;
import org.junit.Test;

import static org.junit.Assert.*;

public class TopologicalOrderTest {

    // Helper to create a simple compute node
    private Node<Double> createComputeNode(String name) {
        return new ScalarCalcNode(name, ScalarCutoffs.ALWAYS, () -> 2.0);
    }

    @Test
    public void testEmptyGraph() {
        TopologicalOrder order = TopologicalOrder.builder().build();
        assertEquals(0, order.nodeCount());
    }

    @Test
    public void testSingleNode() {
        Node<Double> a = new ScalarSourceNode("A", 1.0);
        TopologicalOrder order = TopologicalOrder.builder()
                .addNode(a)
                .markSource("A")
                .build();

        assertEquals(1, order.nodeCount());
        assertEquals("A", order.node(0).name());
        assertEquals(0, order.topoIndex("A"));
        assertTrue(order.isSource(0));
        assertEquals(0, order.childCount(0));
        assertEquals(0, order.parentCount(0));
    }

    @Test
    public void testLinearGraph() {
        // A -> B -> C
        Node<Double> a = new ScalarSourceNode("A", 1.0);
        Node<Double> b = createComputeNode("B");
        Node<Double> c = createComputeNode("C");

        TopologicalOrder order = TopologicalOrder.builder()
                .addNode(a).addNode(b).addNode(c)
                .addEdge("A", "B")
                .addEdge("B", "C")
                .markSource("A")
                .build();

        assertEquals(3, order.nodeCount());

        // Check Topo Order
        assertEquals("A", order.node(0).name());
        assertEquals("B", order.node(1).name());
        assertEquals("C", order.node(2).name());

        // Check Source Designation
        assertTrue(order.isSource(0));
        assertFalse(order.isSource(1));
        assertFalse(order.isSource(2));

        // Check Indexing
        assertEquals(0, order.topoIndex("A"));
        assertEquals(1, order.topoIndex("B"));
        assertEquals(2, order.topoIndex("C"));

        // Check CSR Edge structures
        assertEquals(1, order.childCount(0)); // A has 1 child (B)
        assertEquals(1, order.childCount(1)); // B has 1 child (C)
        assertEquals(0, order.childCount(2)); // C has 0 children

        assertEquals(1, order.child(0, 0)); // A's first child is B (index 1)
        assertEquals(2, order.child(1, 0)); // B's first child is C (index 2)

        // Check Parents
        assertEquals(0, order.parentCount(0));
        assertEquals(1, order.parentCount(1));
        assertEquals(1, order.parentCount(2));
    }

    @Test
    public void testDiamondGraph() {
        // A
        // / \
        // B C
        // \ /
        // D
        Node<Double> a = new ScalarSourceNode("A", 1.0);
        Node<Double> b = createComputeNode("B");
        Node<Double> c = createComputeNode("C");
        Node<Double> d = createComputeNode("D");

        TopologicalOrder order = TopologicalOrder.builder()
                .addNode(a).addNode(b).addNode(c).addNode(d)
                .addEdge("A", "B")
                .addEdge("A", "C")
                .addEdge("B", "D")
                .addEdge("C", "D")
                .markSource("A")
                .build();

        assertEquals(4, order.nodeCount());

        // A must be 0
        assertEquals(0, order.topoIndex("A"));
        assertTrue(order.isSource(0));

        int idxB = order.topoIndex("B");
        int idxC = order.topoIndex("C");
        int idxD = order.topoIndex("D");

        // D must be strictly after B and C
        assertTrue(idxD > idxB);
        assertTrue(idxD > idxC);

        // A should have 2 children: B and C
        assertEquals(2, order.childCount(0));
        assertTrue(order.child(0, 0) == idxB || order.child(0, 0) == idxC);
        assertTrue(order.child(0, 1) == idxB || order.child(0, 1) == idxC);

        // B and C should have 1 child: D
        assertEquals(1, order.childCount(idxB));
        assertEquals(idxD, order.child(idxB, 0));

        assertEquals(1, order.childCount(idxC));
        assertEquals(idxD, order.child(idxC, 0));

        // D should have 0 children and 2 parents
        assertEquals(0, order.childCount(idxD));
        assertEquals(2, order.parentCount(idxD));
    }

    @Test
    public void testDisjointGraphs() {
        // A -> B
        // C -> D
        Node<Double> a = new ScalarSourceNode("A", 1.0);
        Node<Double> b = createComputeNode("B");
        Node<Double> c = new ScalarSourceNode("C", 2.0);
        Node<Double> d = createComputeNode("D");

        TopologicalOrder order = TopologicalOrder.builder()
                .addNode(a).addNode(b).addNode(c).addNode(d)
                .addEdge("A", "B")
                .addEdge("C", "D")
                .markSource("A")
                .markSource("C")
                .build();

        assertEquals(4, order.nodeCount());

        int idxA = order.topoIndex("A");
        int idxB = order.topoIndex("B");
        int idxC = order.topoIndex("C");
        int idxD = order.topoIndex("D");

        assertTrue(order.isSource(idxA));
        assertTrue(order.isSource(idxC));
        assertFalse(order.isSource(idxB));
        assertFalse(order.isSource(idxD));

        assertTrue(idxB > idxA);
        assertTrue(idxD > idxC);

        assertEquals(1, order.childCount(idxA));
        assertEquals(idxB, order.child(idxA, 0));

        assertEquals(1, order.childCount(idxC));
        assertEquals(idxD, order.child(idxC, 0));
    }

    @Test(expected = IllegalStateException.class)
    public void testCycleDetection() {
        // A -> B -> C -> A
        Node<Double> a = new ScalarSourceNode("A", 1.0);
        Node<Double> b = createComputeNode("B");
        Node<Double> c = createComputeNode("C");

        TopologicalOrder.builder()
                .addNode(a).addNode(b).addNode(c)
                .addEdge("A", "B")
                .addEdge("B", "C")
                .addEdge("C", "A")
                .build();
    }

    @Test(expected = IllegalStateException.class)
    public void testSelfLoopDetection() {
        // A -> A
        Node<Double> a = new ScalarSourceNode("A", 1.0);

        TopologicalOrder.builder()
                .addNode(a)
                .addEdge("A", "A")
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDuplicateNodeException() {
        Node<Double> a1 = new ScalarSourceNode("A", 1.0);
        Node<Double> a2 = new ScalarSourceNode("A", 2.0);

        TopologicalOrder.builder()
                .addNode(a1)
                .addNode(a2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownEdgeSourceException() {
        Node<Double> b = createComputeNode("B");

        TopologicalOrder.builder()
                .addNode(b)
                .addEdge("A", "B");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownEdgeTargetException() {
        Node<Double> a = new ScalarSourceNode("A", 1.0);

        TopologicalOrder.builder()
                .addNode(a)
                .addEdge("A", "B");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownSourceMarkException() {
        TopologicalOrder.builder().markSource("UNKNOWN");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidTopoIndexLookup() {
        Node<Double> a = new ScalarSourceNode("A", 1.0);
        TopologicalOrder order = TopologicalOrder.builder().addNode(a).build();
        order.topoIndex("UNKNOWN");
    }
}
