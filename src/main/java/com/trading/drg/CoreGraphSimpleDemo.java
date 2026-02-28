package com.trading.drg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;

/**
 * Demonstrates the usage of the {@link CoreGraph} wrapper class.
 */
public class CoreGraphSimpleDemo {
    private static final Logger log = LogManager.getLogger(CoreGraphSimpleDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting CoreGraph Demo...");

        // Initialize the graph
        var graph = new CoreGraph("src/main/resources/fx_arb.json");
        var dashboard = new com.trading.drg.web.DashboardWiring(graph)
                .enableNodeProfiling()
                .enableLatencyTracking()
                .enableDashboardServer(8081);

        // Run the simulation
        simulateMarketFeed(graph);

        // Get Latency Stats
        log.info("Demo complete.");
        System.out.println("\n--- Global Latency Stats ---");
        System.out.println(dashboard.getLatencyListener().dump());
        System.out.println("\n--- Node Performance Profile ---");
        System.out.println(dashboard.getProfileListener().dump());

        // Stop the dashboard server after demo
        dashboard.getDashboardServer().stop();
    }

    private static void simulateMarketFeed(CoreGraph graph) throws InterruptedException {
        // Advanced Simulation Loop
        Random rng = new Random(42);
        int updates = 100_000;

        String[] nodes = { "EURUSD", "USDJPY", "EURJPY" };
        graph.update("EURUSD", 1.18);
        graph.update("USDJPY", 154.9);
        graph.update("EURJPY", 182.8);
        graph.stabilize();

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
                    double recoveryValue = nanNode.equals("USDJPY") ? 154.9 : nanNode.equals("EURJPY") ? 182.8 : 1.18;
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
            while (nanNode != null && nanNode.equals(targetNode)) {
                targetNode = nodes[rng.nextInt(nodes.length)];
            }

            double shock = (rng.nextDouble() - 0.5) * 0.05; // Slightly larger shock for 1s ticks
            double currentValue = graph.getDouble(targetNode);

            // Remove the Double.isNaN() cold start block entirely.

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
    }
}
