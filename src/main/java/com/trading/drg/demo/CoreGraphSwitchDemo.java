package com.trading.drg.demo;

import com.trading.drg.CoreGraph;

import com.trading.drg.web.DashboardWiring;
import com.trading.drg.node.SwitchNode;
import com.trading.drg.node.BooleanNode;
import com.trading.drg.api.ScalarValue;

/**
 * Demonstrates the usage of {@link SwitchNode} loaded from JSON.
 */
public class CoreGraphSwitchDemo {

    /**
     * Executes the demo simulation.
     * Loads the graph topology from JSON, wires the web dashboard, and simulates
     * a continuous market data stream crossing a threshold to demonstrate
     * dynamic execution branching.
     *
     * @param args Command line arguments (unused)
     * @throws Exception if graph loading or dashboard initialization fails
     */
    public static void main(String[] args) throws Exception {
        System.out.println("Starting SwitchNode JSON Demo...");

        // Load the graph statically from JSON
        CoreGraph graph = new CoreGraph("src/main/resources/switch_demo.json");

        // Wire it into the web dashboard
        DashboardWiring dashboard = new DashboardWiring(graph)
                .enableNodeProfiling()
                .enableLatencyTracking()
                .enableDashboardServer(8081);

        // Run a simulation that pushes thousands of ticks to see routing in action
        System.out.println("Running simulation on port 8081...");

        double currentPx = 10.0;

        boolean spike = false;

        for (int i = 0; i < 500; i++) {
            // Every 10 seconds (100 ticks), toggle between high and normal price regimes
            if (i % 100 == 0) {
                spike = !spike;
                System.out.println("\n>>> Market Regime Shift! Price target: " + (spike ? 20.0 : 10.0));
            }

            // Random walk towards the target
            double target = spike ? 20.0 : 10.0;
            currentPx += (target - currentPx) * 0.1 + (Math.random() - 0.5) * 1.5;

            graph.update("MarketPx", currentPx);
            graph.stabilize();

            // Log exactly what the switch router is doing
            boolean isHigh = graph.getNode("IsHighPx", BooleanNode.class).booleanValue();

            // By extracting the branch values, we can see that the inactive branch's value
            // STOPS UPDATING
            // entirely, perfectly demonstrating the O(1) skipping logic of the engine.
            double sellVal = graph.getNode("SellLogic", ScalarValue.class).value();
            double buyVal = graph.getNode("BuyLogic", ScalarValue.class).value();

            System.out.printf(
                    "Tick %03d | Px: %05.2f | Routing to: %-11s | SellLogic (Px-2): %05.2f | BuyLogic (Px+2): %05.2f\n",
                    i, currentPx, isHigh ? "[SellLogic]" : "[BuyLogic]", sellVal, buyVal);

            // Wait 100ms per tick - 10 ticks per second
            // Open the Web Dashboard (localhost:8081) to watch the branches turn
            // green/gray dynamically as the price crosses 15.0.
            Thread.sleep(100);
        }

        dashboard.getDashboardServer().stop();
        System.exit(0);
    }
}
