package com.trading.drg;

import com.trading.drg.api.ScalarValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.concurrent.CountDownLatch;

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
        graph.start();

        // 3. Simulation Loop
        Random rng = new Random(42);
        CountDownLatch latch = new CountDownLatch(1);
        log.info("Publishing updates...");
        for (int i = 0; i < 10_000; i++) {
            double shock = (rng.nextDouble() - 0.5) * 0.01;
            switch (i % 3) {
                case 0 -> graph.publish("EURUSD", graph.<ScalarValue>getNode("EURUSD").doubleValue() + shock);
                case 1 -> graph.publish("USDJPY", graph.<ScalarValue>getNode("USDJPY").doubleValue() + shock * 100);
                case 2 -> graph.publish("EURJPY", graph.<ScalarValue>getNode("EURJPY").doubleValue() + shock * 100);
            }
            if (i % 1000 == 0)
                log.info("Published update {}", i);
        }
        latch.countDown();

        // 4. Stop Engine
        graph.stop();
        log.info("Demo complete.");
    }
}