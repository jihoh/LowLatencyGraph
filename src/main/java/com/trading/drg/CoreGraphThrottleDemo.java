package com.trading.drg;

import com.trading.drg.web.DashboardWiring;
import com.trading.drg.node.ThrottleNode;

/**
 * Demonstrates the usage of the {@link ThrottleNode} loaded
 * from JSON, rendering telemetry to the interactive web dashboard.
 */
public class CoreGraphThrottleDemo {

    /**
     * Executes the demo simulation.
     * Loads the graph topology from JSON, starts the web dashboard, and pushes
     * real-time ticks to demonstrate the ThrottleNode rate-limiting execution frequency.
     *
     * @param args Command line arguments (unused)
     * @throws Exception if graph loading or dashboard initialization fails
     */
    public static void main(String[] args) throws Exception {
        System.out.println("Starting ThrottleNode JSON Demo...");

        // Load the graph statically from JSON
        CoreGraph graph = new CoreGraph("src/main/resources/throttle_demo.json");

        // Wire it into the web dashboard
        DashboardWiring dashboard = new DashboardWiring(graph)
                .enableNodeProfiling()
                .enableLatencyTracking()
                .enableDashboardServer(8081);

        // Run a simulation that pushes thousands of ticks to see throttling in action
        System.out.println("Running simulation on port 8081...");

        double currentPx = 100.0;

        for (int i = 0; i < 1000; i++) {
            currentPx += (Math.random() - 0.5);
            graph.update("MarketTick", currentPx);
            graph.stabilize();

            // Check the status of the throttled node
            ThrottleNode throttledNode = graph.getNode("ThrottledTick", ThrottleNode.class);
            double outputValue = throttledNode.value();

            if (throttledNode.stabilize()) { // true if value actually flushed
                System.out.printf("Tick %04d | Fast Px: %05.2f | Throttled Output FLUSHED: %05.2f\n",
                        i, currentPx, outputValue);
            } else if (i % 10 == 0) {
                // Just log the fast tick occasionally if not flushing
                System.out.printf("Tick %04d | Fast Px: %05.2f | (Throttled output held at: %05.2f)\n",
                        i, currentPx, outputValue);
            }

            // Wait 100ms per tick - 10 ticks per second
            // The ThrottleNode (1000ms window) will suppress 9 out of every 10 ticks.
            Thread.sleep(100);
        }

        dashboard.getDashboardServer().stop();
        System.exit(0);
    }
}
