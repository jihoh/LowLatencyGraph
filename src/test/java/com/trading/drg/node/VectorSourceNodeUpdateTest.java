package com.trading.drg.node;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;
import com.trading.drg.dsl.*;
import com.trading.drg.node.*;
import java.util.Arrays;

import org.junit.Test;
import static org.junit.Assert.*;

public class VectorSourceNodeUpdateTest {

    @Test
    public void testInitialUpdatePropagatesWithZeros() {
        // Bug 1: Initializing to 0.0 should propagate if it's the first value
        // usage pattern might be: create source -> update(0.0) -> stabilize
        VectorSourceNode source = new VectorSourceNode("source", 2, 1e-9);

        // Initial state is "dirty" implicitly? No, usually dirty is false until
        // updated?
        // Actually SourceNodes might start dirty or clean depending on implementation.
        // But if we call update, it MUST return true on stabilize.

        double[] zeros = new double[] { 0.0, 0.0 };
        source.update(zeros);

        assertTrue("First stabilization with 0.0 should return true (change detected)", source.stabilize());
        assertArrayEquals(zeros, source.value(), 1e-9);
    }

    @Test
    public void testMultipleUpdatesAccumulate() {
        // Bug 2: Multiple updates between stabilizations should compare against LAST
        // STABILIZED value
        VectorSourceNode source = new VectorSourceNode("source", 2, 0.1); // Tolerance 0.1

        // 1. Initial value
        source.update(new double[] { 10.0, 10.0 });
        assertTrue(source.stabilize()); // baseline = 10.0

        // 2. Small update (within tolerance)
        source.update(new double[] { 10.05, 10.0 });
        // Don't stabilize yet!

        // 3. Another small update (cumulative is now > tolerance?)
        // actually let's just check that we compare against 10.0, not 10.05
        source.update(new double[] { 10.2, 10.0 });

        // If the bug exists, the second update overwrote previousValues with 10.05.
        // Difference (10.2 - 10.05) = 0.15 > 0.1 -> Returns TRUE (correct)

        // Wait, let's try a case where the bug causes FALSE when it should be TRUE.
        // Baseline = 10.0
        // Update 1: 10.15 (Diff 0.15 > 0.1) -> Would be TRUE
        // Update 2: 10.0 (Back to original)
        // If bug exists: previous becomes 10.15. Compare 10.0 vs 10.15 -> Diff 0.15 >
        // 0.1 -> Returns TRUE.
        // Correct behavior: Compare 10.0 vs 10.0 -> Diff 0.0 -> Returns FALSE.

        source.update(new double[] { 10.0, 10.0 }); // Reset
        source.stabilize();

        // Scenario: Flapping
        source.update(new double[] { 10.15, 10.0 }); // Would trigger
        source.update(new double[] { 10.0, 10.0 }); // Back to start

        assertFalse("Net change is zero, should not stabilize", source.stabilize());
    }
}
