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

        // 2. [REQUIRED] Start Engine
        graph.start();

        // 3. [OPTIONAL] Simulation Loop
        Random rng = new Random(42);
        int updates = 10_000;
        CountDownLatch latch = new CountDownLatch(1);

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
                graph.publish("EURJPY", 158.0, true);
            } else {
                graph.publish("EURUSD", currentEurUsd + shock, false);
                graph.publish("USDJPY", currentUsdJpy + shock * 100, false);
                // Last update in batch triggers stabilization
                graph.publish("EURJPY", currentEurJpy + shock * 100, true);
            }

            if (i == updates - 1) {
                latch.countDown();
            }

            TimeUnit.MICROSECONDS.sleep(1);

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
        }

        Thread.sleep(1000); // Wait for processing

        // 5. [OPTIONAL] Get Latency Stats
        var listener = graph.getLatencyListener();
        log.info("Demo complete.");
        log.info(String.format("Latency Stats: Avg: %.2f us | Min: %d ns | Max: %d ns | Total Events: %d",
                listener.avgLatencyMicros(),
                listener.minLatencyNanos(),
                listener.maxLatencyNanos(),
                listener.totalStabilizations()));

        // 7. [REQUIRED] Stop Engine
        graph.stop();

        System.out.println("\n--- Node Performance Profile ---");
        System.out.println(profiler.dump());
    }
}
