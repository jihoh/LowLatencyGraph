package com.trading.drg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.trading.drg.util.NodeProfileListener;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the usage of the {@link CoreGraph} wrapper class.
 */
public class CoreGraphDemo {
    private static final Logger log = LogManager.getLogger(CoreGraphDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting CoreGraph Demo...");

        // 1. [REQUIRED] Initialize CoreGraph - Simplifies: Parsing, Compilation, Engine
        // Setup, Disruptor Setup
        var graph = new CoreGraph("src/main/resources/tri_arb.json");
        NodeProfileListener profiler = graph.enableNodeProfiling();
        var latencyListener = graph.enableLatencyTracking();

        // 2. [REQUIRED] Start Engine -> Removed (Passive Mode)
        // graph.start();

        // 3. [OPTIONAL] Simulation Loop
        Random rng = new Random(42);
        int updates = 10_000;

        // CountDownLatch removed as we are single threaded now effectively,
        // or rather we control the loop.

        // Create a reusable reader for consistent access to ALL fields
        // Watch all scalar nodes by default
        var reader = graph.getSnapshot().createReader();

        log.info("Publishing updates...");
        for (int i = 0; i < updates; i++) {
            double shock = (rng.nextDouble() - 0.5) * 0.01;

            // [CONSUMER] Refresh snapshot (Wait-Free)
            // Triple Buffering guarantees this is always successful and consistent
            // immediately.
            reader.refresh();

            // Use snapshot values for the next step of the random walk
            double currentEurUsd = reader.get("EURUSD");
            double currentUsdJpy = reader.get("USDJPY");
            double currentEurJpy = reader.get("EURJPY");

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

            // [MONITOR] Log the state we just saw (or the state that drove this update)
            if (i % 1000 == 0) {
                // We use the same reader values we captured at the start of the tick
                double spread = reader.get("Arb.Spread");
                if (Math.abs(spread) > 0.05) {
                    log.info(String.format(
                            "[Arb Opportunity] Spread: %.4f | EWMA: %.4f | EURUSD: %.4f | USDJPY: %.2f | EURJPY: %.2f",
                            spread,
                            reader.get("Arb.Spread.Ewma"),
                            currentEurUsd,
                            currentUsdJpy,
                            currentEurJpy));
                }
            }

            // Artificial delay to simulate real-time
            // TimeUnit.MICROSECONDS.sleep(1);
        }

        // 5. [OPTIONAL] Get Latency Stats
        log.info("Demo complete.");
        System.out.println("\n--- Global Latency Stats ---");
        System.out.println(latencyListener.dump());

        // 7. [REQUIRED] Stop Engine -> Removed (Passive Mode)
        // graph.stop();

        System.out.println("\n--- Node Performance Profile ---");
        System.out.println(profiler.dump());
    }
}
