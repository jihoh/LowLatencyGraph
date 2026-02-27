package com.trading.drg;

import org.apache.logging.log4j.LogManager;

import org.apache.logging.log4j.Logger;

import java.util.Random;

/**
 * Demonstrates the usage of Vector Nodes within the {@link CoreGraph} wrapper.
 */
public class CoreGraphVectorDemo {
    private static final Logger log = LogManager.getLogger(CoreGraphVectorDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting CoreGraph Vector Demo...");

        var graph = new CoreGraph("src/main/resources/vector_demo.json")
                .enableNodeProfiling()
                .enableLatencyTracking()
                .enableDashboardServer(8089);

        // Run the simulation
        simulateVectorFeed(graph);

        // Get Latency Stats
        log.info("Demo complete.");
        System.out.println("\n--- Global Latency Stats ---");
        System.out.println(graph.getLatencyListener().dump());
        System.out.println("\n--- Node Performance Profile ---");
        System.out.println(graph.getProfileListener().dump());

        // Stop the dashboard server after demo
        graph.getDashboardServer().stop();
    }

    private static void simulateVectorFeed(CoreGraph graph) throws InterruptedException {
        Random rng = new Random(42);
        int updates = 20_000;

        // Initialize the base yield curve vector: [1M, 3M, 6M, 1Y, 2Y]
        double[] baseCurve = { 4.50, 4.55, 4.60, 4.65, 4.70 };

        // Push initial state using index-based updates
        for (int i = 0; i < baseCurve.length; i++) {
            graph.update(graph.getNodeId("MarketYieldCurve"), i, baseCurve[i]);
        }
        graph.stabilize();

        log.info("Publishing vector updates (mock yield curve shifts)...");
        for (int i = 0; i < updates; i++) {

            // Randomly shift 1 to 3 tenors of the curve
            int shifts = 1 + rng.nextInt(3);
            for (int s = 0; s < shifts; s++) {
                int tenorIndex = rng.nextInt(5);
                // small bps shock
                double shock = (rng.nextDouble() - 0.5) * 0.10;
                baseCurve[tenorIndex] += shock;
                graph.update(graph.getNodeId("MarketYieldCurve"), tenorIndex, baseCurve[tenorIndex]);
            }

            // Always stabilize to propagate whatever state exists
            graph.stabilize();

            if (i % 20 == 0) {
                double yield1M = graph.getDouble("MarketYieldCurve.1M");
                double yield2Y = graph.getDouble("MarketYieldCurve.2Y");
                double spread = graph.getDouble("Spread2Y1M");

                log.info(String.format(
                        "Curve Update | 1M: %.4f | 2Y: %.4f | Steepness (2Y-1M): %.4f",
                        yield1M, yield2Y, spread));
            }

            Thread.sleep(250); // Pause for dashboard visualization
        }
    }
}
