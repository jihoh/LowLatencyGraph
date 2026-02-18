package com.trading.drg;

import com.trading.drg.api.ScalarValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * Complex JSON Template Demo mirroring BondPricerDemo logic.
 */
public class JsonBondPricerDemo {
    private static final Logger log = LogManager.getLogger(JsonBondPricerDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("════════════════════════════════════════════════");
        log.info("  JSON Bond Pricer Demo");
        log.info("════════════════════════════════════════════════");

        // 1. Initialize CoreGraph with template-based JSON
        var graph = new CoreGraph("src/main/resources/bond_pricer_template.json");
        graph.start();

        // 2. Simulation Parameters
        String[] tenors = { "UST_2Y", "UST_3Y", "UST_5Y", "UST_10Y", "UST_30Y" };
        String[] venues = { "Btec", "Fenics", "Dweb" };
        int totalUpdates = 50_000;
        Random rng = new Random(42);

        // 3. Simulation Loop
        log.info("Starting simulation...");
        long tStart = System.nanoTime();

        for (int i = 0; i < totalUpdates; i++) {
            // Pick random instrument
            String tenor = tenors[rng.nextInt(tenors.length)];
            String venue = venues[rng.nextInt(venues.length)];
            String prefix = tenor + "." + venue;

            double mid = 100.0 + rng.nextGaussian() * 0.1;
            double spread = 0.015625;
            double bidPx = mid - spread / 2.0;
            double askPx = mid + spread / 2.0;

            // Publish updates
            graph.publish(prefix + ".bid", bidPx, false);
            graph.publish(prefix + ".bidQty", (double) (1000 + rng.nextInt(500)), false);
            graph.publish(prefix + ".ask", askPx, false);
            graph.publish(prefix + ".askQty", (double) (1000 + rng.nextInt(500)), true);

            if (i % 5000 == 0) {
                var scoreNode = graph.<ScalarValue>getNode("Global.Score");
                var midNode = graph.<ScalarValue>getNode("UST_5Y.mid");
                log.info("Update {}: UST_5Y.mid={:.4f}, Global.Score={:.4f}",
                        i, midNode.doubleValue(), scoreNode.doubleValue());
            }
        }

        long tEnd = System.nanoTime();
        double nanosPerOp = (double) (tEnd - tStart) / totalUpdates;
        log.info(String.format("Done. %.2f ns/op (includes graph overhead)", nanosPerOp));

        // 4. Get Latency Stats
        var listener = graph.getLatencyListener();
        log.info(String.format("Latency Stats: Avg: %.2f us | Min: %d ns | Max: %d ns | Total Events: %d",
                listener.avgLatencyMicros(),
                listener.minLatencyNanos(),
                listener.maxLatencyNanos(),
                listener.totalStabilizations()));

        graph.stop();
    }
}
