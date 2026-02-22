package com.trading.drg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Demonstrates the usage of the {@link CoreGraph} wrapper class with the Bond
 * Pricer template.
 * This simulates a high-frequency market data feed across multiple tenors and
 * venues.
 */
public class BondPricerGraphDemo {
    private static final Logger log = LogManager.getLogger(BondPricerGraphDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting BondPricerGraph Demo...");

        var graph = new CoreGraph("src/main/resources/bond_pricer_template.json")
                .enableNodeProfiling()
                .enableLatencyTracking()
                .enableDashboardServer(8080);

        Random rng = new Random(42);
        int updates = 100_000;

        Map<String, Double> basePrices = Map.of(
                "UST_2Y", 100.0,
                "UST_3Y", 99.0,
                "UST_5Y", 98.0,
                "UST_10Y", 95.0,
                "UST_30Y", 90.0);
        String[] venues = { "Btec", "Fenics", "Dweb" };
        List<String> tenorsList = new ArrayList<>(basePrices.keySet());

        log.info("Seeding initial order book state...");
        for (String tenor : tenorsList) {
            double basePrice = basePrices.get(tenor);
            for (String venue : venues) {
                // Spread is 2 ticks (2/32s or 2/64s approx depending on tenor, using 0.015625 =
                // 1/64)
                graph.update(tenor + "." + venue + ".bid", basePrice - 0.015625);
                graph.update(tenor + "." + venue + ".ask", basePrice + 0.015625);
                graph.update(tenor + "." + venue + ".bidQty", 1000.0 + rng.nextInt(500));
                graph.update(tenor + "." + venue + ".askQty", 1000.0 + rng.nextInt(500));
            }
        }
        graph.stabilize();

        log.info("Publishing updates (high-frequency market data simulation)...");
        for (int i = 0; i < updates; i++) {

            // Iterate over all possible source nodes and update roughly half of them
            for (String targetTenor : tenorsList) {
                for (String targetVenue : venues) {

                    // 50% chance to skip this venue/tenor pair entirely for this tick
                    if (rng.nextBoolean())
                        continue;

                    // If we didn't skip, do we update Price or Size?
                    // 80% chance for price, 20% for size
                    if (rng.nextDouble() < 0.8) {
                        boolean isBid = rng.nextBoolean();
                        String side = isBid ? "bid" : "ask";
                        String nodeName = targetTenor + "." + targetVenue + "." + side;

                        double currentValue = graph.getDouble(nodeName);
                        if (Double.isNaN(currentValue))
                            currentValue = basePrices.get(targetTenor);

                        // Micro-shock the price (+/- 1/64 or 1/128 depending on random)
                        double shock = (rng.nextBoolean() ? 1 : -1) * (rng.nextBoolean() ? 0.015625 : 0.0078125);
                        graph.update(nodeName, currentValue + shock);
                    } else {
                        boolean isBidQty = rng.nextBoolean();
                        String side = isBidQty ? "bidQty" : "askQty";
                        String nodeName = targetTenor + "." + targetVenue + "." + side;

                        // Refresh quantity to simulate resting orders being placed or pulled
                        double newQty = 1000.0 + rng.nextInt(2000);
                        graph.update(nodeName, newQty);
                    }
                }
            }

            // Always stabilize to propagate the new state, after ALL pending updates are
            // applied
            graph.stabilize();

            if (i % 20 == 0) { // Log every 20 ticks
                double globalScore = graph.getDouble("Global.Score");
                double tenYearMid = graph.getDouble("UST_10Y.mid");
                double twoYearMid = graph.getDouble("UST_2Y.mid");

                if (!Double.isNaN(globalScore)) {
                    log.info(String.format(
                            "[Market Data] 2Y Mid: %.4f | 10Y Mid: %.4f | Global Composite Score: %.4f",
                            twoYearMid, tenYearMid, globalScore));
                }
            }

            // High frequency updates: 20ms sleep per tick
            Thread.sleep(100);
        }

        System.out.println("\n--- Final Graph State (Mermaid) ---");
        System.out.println(new com.trading.drg.util.GraphExplain(graph.getEngine()).toMermaid());

        if (graph.getDashboardServer() != null) {
            graph.getDashboardServer().stop();
        }

        log.info("Demo complete.");
        System.out.println("\n--- Global Latency Stats ---");
        if (graph.getLatencyListener() != null) {
            System.out.println(graph.getLatencyListener().dump());
        }
        System.out.println("\n--- Node Performance Profile ---");
        if (graph.getProfileListener() != null) {
            System.out.println(graph.getProfileListener().dump());
        }
    }
}
