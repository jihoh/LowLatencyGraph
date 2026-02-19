package com.trading.drg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the usage of the {@link CoreGraph} wrapper class.
 */
public class CoreGraphSimpleDemo {
    private static final Logger log = LogManager.getLogger(CoreGraphSimpleDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting CoreGraph Simple Demo...");

        // 1. Initialize CoreGraph - Simplifies: Parsing, Compilation, Engine Setup,
        // Disruptor Setup
        var graph = new CoreGraph("src/main/resources/tri_arb.json");

        // 2. Start Engine
        // graph.start();

        // 3. Simulation Loop
        Random rng = new Random(42);
        // CountDownLatch latch = new CountDownLatch(1); // Unused
        log.info("Publishing updates...");

        // Use the default reader (watches ALL scalar nodes)
        // Convenient but copies more data than a specific reader.
        var reader = graph.getSnapshot().createReader();

        for (int i = 0; i < 10_000; i++) {
            double shock = (rng.nextDouble() - 0.5) * 0.01;

            // [CONSUMER] Refresh snapshot (Wait-Free)
            // Triple Buffering guarantees this is always successful and consistent
            // immediately.
            reader.refresh();

            // Access by name
            double currentEurUsd = reader.get("EURUSD");
            double currentUsdJpy = reader.get("USDJPY");
            double currentEurJpy = reader.get("EURJPY");

            if (i % 3 == 0) {
                graph.update("EURUSD", currentEurUsd + shock);
            } else if (i % 3 == 1) {
                graph.update("USDJPY", currentUsdJpy + shock * 100);
            } else {
                graph.update("EURJPY", currentEurJpy + shock * 100);
            }
            graph.stabilize();

            if (i % 1000 == 0) {
                log.info("Published update {}. Current Spread: {}", i, reader.get("Arb.Spread"));
            }

            TimeUnit.MICROSECONDS.sleep(10);
        }

        // 4. Stop Engine
        // graph.stop();
        log.info("Demo complete.");
    }
}