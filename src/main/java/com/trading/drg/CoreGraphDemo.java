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

        var graph = new CoreGraph("src/main/resources/tri_arb.json")
                .enableNodeProfiling()
                .enableLatencyTracking()
                .enableDashboardServer(8080);

        // Advanced Simulation Loop
        Random rng = new Random(42);
        int updates = 100_000;

        String[] nodes = { "EURUSD", "USDJPY", "EURJPY" };
        int nanCountdown = 0;
        String nanNode = null;

        log.info("Publishing updates (1 tick/sec, with 5s NaN faults)...");
        for (int i = 0; i < updates; i++) {
            // Priority 1: Manage NaN Fault Injection Phase
            if (nanCountdown > 0) {
                // Keep the faulted node at NaN for the duration of the countdown
                graph.update(nanNode, Double.NaN);
                nanCountdown--;
                if (nanCountdown == 0) {
                    // Recover the node with a sensible baseline so it doesn't stay NaN forever.
                    double recoveryValue = nanNode.equals("USDJPY") ? 154.9 : (nanNode.equals("EURJPY") ? 182.8 : 1.18);
                    graph.update(nanNode, recoveryValue);
                    log.info("Recovered " + nanNode + " from NaN fault.");
                    nanNode = null;
                }
            } else if (i > 0 && i % 500 == 0) {
                // Trigger a new 100-second NaN fault every 500 ticks
                nanNode = nodes[rng.nextInt(nodes.length)];
                nanCountdown = 100;
                graph.update(nanNode, Double.NaN);
                log.info("Injected NaN fault into " + nanNode + " for 100 seconds.");
            }

            // Normal Random Walk for exactly ONE OTHER random node
            String targetNode = nodes[rng.nextInt(nodes.length)];

            // Loop until we find a node that is NOT currently the active NaN fault
            while (nanNode != null && targetNode.equals(nanNode)) {
                targetNode = nodes[rng.nextInt(nodes.length)];
            }

            double shock = (rng.nextDouble() - 0.5) * 0.05; // Slightly larger shock for 1s ticks
            double currentValue = graph.getDouble(targetNode);

            // Cold start: The graph now defaults to NaN. Seed it on the first tick.
            if (Double.isNaN(currentValue)) {
                currentValue = targetNode.equals("USDJPY") ? 154.9 : (targetNode.equals("EURJPY") ? 182.8 : 1.18);
            }

            if (targetNode.contains("JPY")) {
                shock *= 100;
            }
            graph.update(targetNode, currentValue + shock);

            // Always stabilize to propagate whatever state (good or NaN) exists
            graph.stabilize();

            if (i % 5 == 0) {
                double spread = graph.getDouble("Arb.Spread");
                if (!Double.isNaN(spread) && Math.abs(spread) > 0.05) {
                    log.info(String.format(
                            "[Arb Opportunity] Spread: %.4f | EWMA: %.4f | EURUSD: %.4f | USDJPY: %.2f | EURJPY: %.2f",
                            spread,
                            graph.getDouble("Arb.Spread.Ewma"),
                            graph.getDouble("EURUSD"),
                            graph.getDouble("USDJPY"),
                            graph.getDouble("EURJPY")));
                }
            }

            Thread.sleep(100);
        }

        System.out.println("\n--- Final Graph State (Mermaid) ---");
        System.out.println(new com.trading.drg.util.GraphExplain(graph.getEngine()).toMermaid());

        // Stop the dashboard server after demo
        if (graph.getDashboardServer() != null) {
            graph.getDashboardServer().stop();
        }

        // Get Latency Stats
        log.info("Demo complete.");
        System.out.println("\n--- Global Latency Stats ---");
        if (graph.getLatencyListener() != null) {
            System.out.println(graph.getLatencyListener().dump());
        }
        System.out.println("\n--- Node Performance Profile ---");
        if (graph.getProfileListener() != null) {
            System.out.println(graph.getProfileListener().dump());
        }
    }
}
