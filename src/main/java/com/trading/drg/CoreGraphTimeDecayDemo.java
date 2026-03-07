package com.trading.drg;

import com.trading.drg.web.DashboardWiring;
import com.trading.drg.node.TimeDecayNode;

/**
 * Demonstrates the usage of the {@link TimeDecayNode} loaded
 * from JSON, rendering telemetry to the interactive web dashboard.
 */
public class CoreGraphTimeDecayDemo {

    /**
     * Executes the demo simulation.
     * Loads the graph topology from JSON, starts the web dashboard, and simulates
     * market prices to demonstrate how the TimeDecayNode calculates a smooth EWMA
     * over physical elapsed time.
     *
     * @param args Command line arguments (unused)
     * @throws Exception if graph loading or dashboard initialization fails
     */
    public static void main(String[] args) throws Exception {
        System.out.println("Starting TimeDecayNode JSON Demo...");

        // Load the graph statically from JSON
        CoreGraph graph = new CoreGraph("src/main/resources/time_decay_demo.json");

        // Wire it into the web dashboard
        DashboardWiring dashboard = new DashboardWiring(graph)
                .enableNodeProfiling()
                .enableLatencyTracking()
                .enableDashboardServer(8081);

        // Run a simulation that pushes ticks to see true physical time-based decay
        System.out.println("Running simulation on port 8081...");

        double currentPx = 1.0500;

        // Let it run for 500 ticks (~50 seconds)
        for (int i = 0; i < 500; i++) {

            // Introduce occasional large spikes to clearly show the EWMA lagging and
            // catching up
            if (i > 0 && i % 50 == 0) {
                currentPx += (Math.random() > 0.5 ? 0.05 : -0.05);
                System.out.println("\n>>> MARKET SHOCK applied <<<");
            } else {
                currentPx += (Math.random() - 0.5) * 0.005;
            }

            graph.update("EUR_USD", currentPx);
            graph.stabilize();

            TimeDecayNode smoothNode = graph.getNode("SmoothPrice", TimeDecayNode.class);
            System.out.printf("Tick %03d | Raw Spot: %07.4f | EWMA (500ms HL): %07.4f\n",
                    i, currentPx, smoothNode.value());

            Thread.sleep(100);
        }

        dashboard.getDashboardServer().stop();
        System.exit(0);
    }
}
