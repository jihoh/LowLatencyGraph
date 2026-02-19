package com.trading.drg;

// Disruptor imports removed
import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.fn.finance.TriangularArbSpread;
import com.trading.drg.fn.finance.Ewma;
import com.trading.drg.util.GraphExplain;
// Wiring imports removed
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * Triangular Arbitrage Demo using {@link TriangularArbSpread}.
 *
 * Demonstrates:
 * 1. Graph construction with `TriangularArbSpread` node.
 * 2. `GraphPublisher` integration for event handling.
 * 3. Reading node values from the graph.
 */
public class TriangularArbDemo {
    private static final Logger log = LogManager.getLogger(TriangularArbDemo.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("Starting Triangular Arbitrage Demo...");

        // 1. Build Graph
        var g = GraphBuilder.create("tri_arb");

        // Sources
        var eurUsd = g.scalarSource("EURUSD", 1.0850);
        var usdJpy = g.scalarSource("USDJPY", 145.20);
        var eurJpy = g.scalarSource("EURJPY", 157.55); // Slightly off theoretical (1.085 * 145.2 = 157.542)

        // Compute Spread: Direct - (Leg1 * Leg2)
        // Spread = EURJPY - (EURUSD * USDJPY)
        var arbSpread = g.compute("Arb.Spread",
                new TriangularArbSpread(),
                eurUsd, usdJpy, eurJpy);

        // Apply EWMA to smooth the spread
        var arbEwma = g.compute("Arb.Spread.Ewma", new Ewma(0.1), arbSpread);

        // 2. Build Engine
        var context = g.buildWithContext();
        var engine = context.engine();
        var listener = new com.trading.drg.util.LatencyTrackingListener();
        engine.setListener(listener);

        // 3. Setup Mechanism (Direct Engine Usage)
        // No Disruptor needed for this simple demo.

        // 5. Simulation Loop
        Random rng = new Random(42);
        int updates = 10_000;

        // Pre-resolve node IDs
        int eurUsdId = context.getNodeId("EURUSD");
        int usdJpyId = context.getNodeId("USDJPY");
        int eurJpyId = context.getNodeId("EURJPY");

        log.info("Publishing updates...");
        for (int i = 0; i < updates; i++) {
            // Random walk
            double shock = (rng.nextDouble() - 0.5) * 0.01;

            // Occasionally create an arb opportunity
            if (i % 500 == 0) {
                eurJpy.updateDouble(158.0);
                engine.markDirty(eurJpyId);
            } else {
                eurUsd.updateDouble(eurUsd.doubleValue() + shock);
                engine.markDirty(eurUsdId);

                usdJpy.updateDouble(usdJpy.doubleValue() + shock * 100);
                engine.markDirty(usdJpyId);

                eurJpy.updateDouble(eurJpy.doubleValue() + shock * 100);
                engine.markDirty(eurJpyId);
            }

            // Run engine (stabilize)
            engine.stabilize();

            // Check results (Manual Callback logic inline)
            if (i % 1000 == 0) {
                double spread = arbSpread.doubleValue();
                if (Math.abs(spread) > 0.05) {
                    log.info(
                            String.format(
                                    "[Arb Opportunity] Spread: %.4f | EWMA: %.4f | EURUSD: %.4f | USDJPY: %.2f | EURJPY: %.2f",
                                    spread, arbEwma.doubleValue(), eurUsd.doubleValue(), usdJpy.doubleValue(),
                                    eurJpy.doubleValue()));
                }
            }

            // Throttle slightly
            try {
                Thread.sleep(0, 500_000);
            } catch (InterruptedException e) {
            }
        }

        Thread.sleep(1000);

        log.info("Demo complete.");
        log.info(String.format("Latency Stats: Avg: %.2f us | Min: %d ns | Max: %d ns | Total Events: %d",
                listener.avgLatencyMicros(),
                listener.minLatencyNanos(),
                listener.maxLatencyNanos(),
                listener.totalStabilizations()));
    }
}
