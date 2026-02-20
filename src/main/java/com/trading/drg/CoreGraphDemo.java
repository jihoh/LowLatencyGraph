package com.trading.drg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;

/**
 * Demonstrates the usage of the {@link CoreGraph} wrapper class.
 */
public class CoreGraphDemo {
    private static final Logger log = LogManager.getLogger(CoreGraphDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting CoreGraph Demo...");

        // Initialize CoreGraph
        var graph = new CoreGraph("src/main/resources/tri_arb.json");
        var profiler = graph.enableNodeProfiling();
        var latencyListener = graph.enableLatencyTracking();

        // Simulation Loop
        Random rng = new Random(42);
        int updates = 10_000;

        log.info("Publishing updates...");
        for (int i = 0; i < updates; i++) {
            double shock = (rng.nextDouble() - 0.5) * 0.01;

            // Use direct values for the next step of the random walk
            double currentEurUsd = graph.getDouble("EURUSD");
            double currentUsdJpy = graph.getDouble("USDJPY");
            double currentEurJpy = graph.getDouble("EURJPY");

            if (i % 500 == 0) {
                graph.update("EURJPY", 158.0);
                graph.stabilize();
            } else {
                graph.update("EURUSD", currentEurUsd + shock);
                graph.update("USDJPY", currentUsdJpy + shock * 100);
                graph.update("EURJPY", currentEurJpy + shock * 100);
                // Trigger stabilization manually
                graph.stabilize();
            }

            if (i % 1000 == 0) {
                double spread = graph.getDouble("Arb.Spread");
                if (Math.abs(spread) > 0.05) {
                    log.info(String.format(
                            "[Arb Opportunity] Spread: %.4f | EWMA: %.4f | EURUSD: %.4f | USDJPY: %.2f | EURJPY: %.2f",
                            spread,
                            graph.getDouble("Arb.Spread.Ewma"),
                            currentEurUsd,
                            currentUsdJpy,
                            currentEurJpy));
                }
            }
        }

        // Get Latency Stats
        log.info("Demo complete.");
        System.out.println("\n--- Global Latency Stats ---");
        System.out.println(latencyListener.dump());
        System.out.println("\n--- Node Performance Profile ---");
        System.out.println(profiler.dump());

        System.out.println("\n--- Final Graph State (Mermaid) ---");
        System.out.println(new com.trading.drg.util.GraphExplain(graph.getEngine()).toMermaid());
    }
}
