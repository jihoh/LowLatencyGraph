package com.trading.drg;

import java.util.Random;

public class NodeProfileDemo {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Starting Node Profile Demo...");

        // 1. Initialize Graph
        CoreGraph graph = new CoreGraph("src/main/resources/tri_arb.json");
        // graph.start();

        // 2. Enable Profiling
        graph.enableNodeProfiling();
        System.out.println("Profiling enabled.");

        // 3. Optimize Sources
        // 4. Run Simulation
        System.out.println("Running simulation for 2 seconds...");
        Random rng = new Random();
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < 2000) {
            double shock = (rng.nextDouble() - 0.5) * 0.001;
            graph.update("EURUSD", 1.0850 + shock);
            graph.update("USDJPY", 145.20 + shock * 100);
            graph.stabilize();

            // Spin a bit to allow stabilization
            Thread.sleep(1);
        }

        // 5. Dump Stats
        System.out.println("\n--- Node Performance Profile ---");
        System.out.println(graph.getProfileListener().dump());

        System.out.println(graph.getProfileListener().dump());

        // graph.stop();
        System.exit(0);
    }
}
