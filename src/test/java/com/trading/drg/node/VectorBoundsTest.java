package com.trading.drg.node;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;
import com.trading.drg.dsl.*;
import com.trading.drg.wiring.*;
import com.trading.drg.node.*;

import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.wiring.GraphEvent;
import com.trading.drg.wiring.GraphPublisher;
import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.GraphContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class VectorBoundsTest {

    @Test
    public void testUpdateLengthMismatch() {
        VectorSourceNode node = new VectorSourceNode("test", 3);
        try {
            node.update(new double[] { 1.0, 2.0 }); // Too short
            fail("Should throw IllegalArgumentException for short update");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("length"));
        }

        try {
            node.update(new double[] { 1.0, 2.0, 3.0, 4.0 }); // Too long
            fail("Should throw IllegalArgumentException for long update");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("length"));
        }
    }

    @Test
    public void testUpdateAtBounds() {
        VectorSourceNode node = new VectorSourceNode("test", 3);
        try {
            node.updateAt(-1, 1.0);
            fail("Should throw IndexOutOfBoundsException for negative index");
        } catch (IndexOutOfBoundsException e) {
            // Expected
        }

        try {
            node.updateAt(3, 1.0);
            fail("Should throw IndexOutOfBoundsException for index >= size");
        } catch (IndexOutOfBoundsException e) {
            // Expected
        }
    }

    @Test
    public void testPublisherResilience() {
        // Setup graph
        GraphBuilder g = GraphBuilder.create("safety_test");
        var vec = g.vectorSource("vec", 2);
        var ctx = g.buildWithContext();

        GraphPublisher publisher = new GraphPublisher(ctx.engine());

        // Mock event for invalid index (index 5 for size 2 vector)
        // We can't easily mock GraphEvent as it's likely a final class or struct-like,
        // but let's assume we can construct one or it has setters (it's a flyweight).
        // If not, we'll have to rely on the fact that GraphEvent is usually mutable.
        GraphEvent event = new GraphEvent();
        event.setVectorElementUpdate(0, 5, 100.0, false, 1L);

        // This should NOT throw exception if publisher is safe
        try {
            publisher.onEvent(event, 1, true);
        } catch (Exception e) {
            e.printStackTrace();
            fail("GraphPublisher.onEvent should catch exceptions and log them, not throw. Caught: "
                    + e.getClass().getName());
        }
    }
}
