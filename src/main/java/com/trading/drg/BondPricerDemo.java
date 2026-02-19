package com.trading.drg;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;
import com.trading.drg.dsl.*;
// import com.trading.drg.wiring.*;
import com.trading.drg.node.*;

// Disruptor imports removed
import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.Node;
// wiring imports removed
import com.trading.drg.util.GraphExplain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

public class BondPricerDemo {
    private static final Logger log = LogManager.getLogger(BondPricerDemo.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("════════════════════════════════════════════════");
        log.info("  Bond Pricer Demo (Disruptor + Programmatic)");
        log.info("════════════════════════════════════════════════");

        // 1. Build Graph Programmatically
        var g = GraphBuilder.create("bond_pricer");

        String[] tenors = { "2Y", "3Y", "5Y", "10Y", "30Y" }; // Expanded set
        String[] venues = { "Btec", "Fenics", "Dweb" };

        // Create topology for each tenor
        List<ScalarValue> allMids = new ArrayList<>();
        for (String tenor : tenors) {
            String prefix = "UST_" + tenor;
            List<ScalarValue> bidInputs = new ArrayList<>();
            List<ScalarValue> askInputs = new ArrayList<>();

            for (String venue : venues) {
                String base = prefix + "." + venue;

                // Sources
                var bid = g.scalarSource(base + ".bid", 100.0);
                var bidQty = g.scalarSource(base + ".bidQty", 1000.0);
                var ask = g.scalarSource(base + ".ask", 100.015625);
                var askQty = g.scalarSource(base + ".askQty", 1000.0);

                bidInputs.add(bid);
                bidInputs.add(bidQty);
                askInputs.add(ask);
                askInputs.add(askQty);
            }

            // Aggregation Logic (Weighted Average)
            // Function: (p1, q1, p2, q2, ...) -> sum(p*q) / sum(q)
            var wBid = g.computeN(prefix + ".wBid",
                    BondPricerDemo::weightedAvg,
                    bidInputs.toArray(new ScalarValue[0]));

            var wAsk = g.computeN(prefix + ".wAsk",
                    BondPricerDemo::weightedAvg,
                    askInputs.toArray(new ScalarValue[0]));

            // Calculate Mid for this instrument
            var mid = g.compute(prefix + ".mid", (b, a) -> (b + a) / 2.0, wBid, wAsk);
            allMids.add(mid);
        }

        // Global Score Aggregation (Average of all Mids)
        // Function: (m1, m2, ...) -> sum(m) / count(m)
        g.computeN("Global.Score",
                inputs -> {
                    double sum = 0;
                    for (double v : inputs) {
                        sum += v;
                    }
                    return inputs.length == 0 ? 0 : sum / inputs.length;
                },
                allMids.toArray(new ScalarValue[0]));

        // 2. Build Engine
        var context = g.buildWithContext();
        var engine = context.engine();
        engine.setListener(new com.trading.drg.util.LatencyTrackingListener());
        var nodes = context.nodesByName();

        // 3. Setup Mechanism (Direct Engine Usage)
        // No Disruptor needed.

        long tStart = System.nanoTime();
        int totalUpdates = 200_000;

        // 4. Simulation Loop
        Random rng = new Random(42);
        log.info("Starting producer...");

        // Pre-resolve nodes (Zero-GC hot path)
        ScalarSourceNode[] bidNodes = new ScalarSourceNode[tenors.length * venues.length];
        ScalarSourceNode[] bidQtyNodes = new ScalarSourceNode[tenors.length * venues.length];
        ScalarSourceNode[] askNodes = new ScalarSourceNode[tenors.length * venues.length];
        ScalarSourceNode[] askQtyNodes = new ScalarSourceNode[tenors.length * venues.length];

        int[] bidIds = new int[tenors.length * venues.length];
        int[] bidQtyIds = new int[tenors.length * venues.length];
        int[] askIds = new int[tenors.length * venues.length];
        int[] askQtyIds = new int[tenors.length * venues.length];

        int idx = 0;
        for (String tenor : tenors) {
            String prefix = "UST_" + tenor;
            for (String venue : venues) {
                String base = prefix + "." + venue;
                bidNodes[idx] = (ScalarSourceNode) nodes.get(base + ".bid");
                bidQtyNodes[idx] = (ScalarSourceNode) nodes.get(base + ".bidQty");
                askNodes[idx] = (ScalarSourceNode) nodes.get(base + ".ask");
                askQtyNodes[idx] = (ScalarSourceNode) nodes.get(base + ".askQty");

                bidIds[idx] = context.getNodeId(base + ".bid");
                bidQtyIds[idx] = context.getNodeId(base + ".bidQty");
                askIds[idx] = context.getNodeId(base + ".ask");
                askQtyIds[idx] = context.getNodeId(base + ".askQty");
                idx++;
            }
        }

        for (int i = 0; i < totalUpdates; i++) {
            // Pick random instrument by index
            int k = rng.nextInt(tenors.length * venues.length);

            double mid = 100.0 + rng.nextGaussian() * 0.1;
            double spread = 0.015625;
            double bidPx = mid - spread / 2.0;
            double askPx = mid + spread / 2.0;

            bidNodes[k].updateDouble(bidPx);
            engine.markDirty(bidIds[k]);

            bidQtyNodes[k].updateDouble(1000 + rng.nextInt(500));
            engine.markDirty(bidQtyIds[k]);

            askNodes[k].updateDouble(askPx);
            engine.markDirty(askIds[k]);

            askQtyNodes[k].updateDouble(1000 + rng.nextInt(500));
            engine.markDirty(askQtyIds[k]);

            engine.stabilize();

            if (i % 10000 == 0) {
                printSnapshot("5Y", nodes);
            }
        }

        long tEnd = System.nanoTime();
        double nanosPerOp = (double) (tEnd - tStart) / totalUpdates;
        log.info(String.format("Done. %.2f ns/op", nanosPerOp));
    }

    private static void printSnapshot(String tenor, Map<String, Node<?>> nodes) {
        String p = "UST_" + tenor;
        Node<?> wBid = nodes.get(p + ".wBid");
        Node<?> wAsk = nodes.get(p + ".wAsk");
        Node<?> score = nodes.get("Global.Score");

        if (wBid instanceof ScalarValue && wAsk instanceof ScalarValue) {
            double b = ((ScalarValue) wBid).doubleValue();
            double a = ((ScalarValue) wAsk).doubleValue();
            double s = (score instanceof ScalarValue) ? ((ScalarValue) score).doubleValue() : Double.NaN;
            log.info(String.format("[%s] wBid: %.4f | wAsk: %.4f | Global.Score: %.4f", tenor, b, a, s));
        }
    }

    // Weighted Average Logic: Pairs of (Value, Weight)
    private static double weightedAvg(double[] inputs) {
        double sumProd = 0.0;
        double sumW = 0.0;
        for (int i = 0; i < inputs.length; i += 2) {
            double val = inputs[i];
            double w = inputs[i + 1];
            sumProd += val * w;
            sumW += w;
        }
        return (sumW == 0) ? 0.0 : sumProd / sumW;
    }
}
