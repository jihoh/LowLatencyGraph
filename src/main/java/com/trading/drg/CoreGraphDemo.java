package com.trading.drg;

import com.trading.drg.api.ScalarValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.concurrent.CountDownLatch;

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

        // 2. [OPTIONAL] Access Nodes
        var arbSpreadNode = graph.<ScalarValue>getNode("Arb.Spread");
        var eurUsdNode = graph.<ScalarValue>getNode("EURUSD");
        var usdJpyNode = graph.<ScalarValue>getNode("USDJPY");
        var eurJpyNode = graph.<ScalarValue>getNode("EURJPY");
        var arbEwmaNode = graph.<ScalarValue>getNode("Arb.Spread.Ewma");

        // 3. [OPTIONAL] Setup Publisher Callback
        graph.getPublisher().setPostStabilizationCallback((epoch, count) -> {
            if (epoch % 1000 == 0) {
                double spread = arbSpreadNode.doubleValue();
                if (Math.abs(spread) > 0.05) {
                    log.info(String.format(
                            "[Arb Opportunity] Spread: %.4f | EWMA: %.4f | EURUSD: %.4f | USDJPY: %.2f | EURJPY: %.2f",
                            spread, arbEwmaNode.doubleValue(), eurUsdNode.doubleValue(), usdJpyNode.doubleValue(),
                            eurJpyNode.doubleValue()));
                }
            }
        });

        // 4. [REQUIRED] Start Engine
        graph.start();

        // 5. [OPTIONAL] Simulation Loop
        // Publishing is simplified via graph.publish()
        Random rng = new Random(42);
        int updates = 10_000;
        CountDownLatch latch = new CountDownLatch(1);

        log.info("Publishing updates...");
        for (int i = 0; i < updates; i++) {
            double shock = (rng.nextDouble() - 0.5) * 0.01;

            if (i % 500 == 0) {
                graph.publish("EURJPY", 158.0, true);
            } else {
                graph.publish("EURUSD", eurUsdNode.doubleValue() + shock, false);
                graph.publish("USDJPY", usdJpyNode.doubleValue() + shock * 100, false);
                // Last update in batch triggers stabilization
                graph.publish("EURJPY", eurJpyNode.doubleValue() + shock * 100, true);
            }

            if (i == updates - 1) {
                latch.countDown();
            }

            try {
                Thread.sleep(0, 500_000);
            } catch (InterruptedException e) {
            }
        }

        Thread.sleep(1000); // Wait for processing

        // 6. [OPTIONAL] Get Latency Stats
        var listener = graph.getLatencyListener();
        log.info("Demo complete.");
        log.info(String.format("Latency Stats: Avg: %.2f us | Min: %d ns | Max: %d ns | Total Events: %d",
                listener.avgLatencyMicros(),
                listener.minLatencyNanos(),
                listener.maxLatencyNanos(),
                listener.totalStabilizations()));

        // 7. [REQUIRED] Stop Engine
        graph.stop();
    }
}
