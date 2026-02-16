package com.trading.drg;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;
import com.trading.drg.dsl.*;
import com.trading.drg.wiring.*;
import com.trading.drg.node.*;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.trading.drg.api.DoubleReadable;
import com.trading.drg.api.Node;
import com.trading.drg.wiring.GraphEvent;
import com.trading.drg.wiring.GraphPublisher;
import com.trading.drg.util.GraphExplain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

public class TreasurySimulator2 {
    private static final Logger log = LogManager.getLogger(TreasurySimulator2.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("════════════════════════════════════════════════");
        log.info("  Treasury Simulator 2 (Disruptor + Programmatic)");
        log.info("════════════════════════════════════════════════");

        // 1. Build Graph Programmatically
        var g = GraphBuilder.create("treasuries_prog");

        String[] tenors = { "2Y", "3Y", "5Y", "10Y", "30Y" }; // Expanded set
        String[] venues = { "Btec", "Fenics", "Dweb" };

        // Create topology for each tenor
        List<DoubleReadable> allMids = new ArrayList<>();
        for (String tenor : tenors) {
            String prefix = "UST_" + tenor;
            List<DoubleReadable> bidInputs = new ArrayList<>();
            List<DoubleReadable> askInputs = new ArrayList<>();

            for (String venue : venues) {
                String base = prefix + "." + venue;

                // Sources
                var bid = g.doubleSource(base + ".bid", 100.0);
                var bidQty = g.doubleSource(base + ".bidQty", 1000.0);
                var ask = g.doubleSource(base + ".ask", 100.015625);
                var askQty = g.doubleSource(base + ".askQty", 1000.0);

                bidInputs.add(bid);
                bidInputs.add(bidQty);
                askInputs.add(ask);
                askInputs.add(askQty);
            }

            // Aggregation Logic (Weighted Average)
            // Function: (p1, q1, p2, q2, ...) -> sum(p*q) / sum(q)
            var wBid = g.computeN(prefix + ".wBid",
                    bidInputs.toArray(new DoubleReadable[0]),
                    TreasurySimulator2::weightedAvg);

            var wAsk = g.computeN(prefix + ".wAsk",
                    askInputs.toArray(new DoubleReadable[0]),
                    TreasurySimulator2::weightedAvg);

            // Calculate Mid for this instrument
            var mid = g.compute(prefix + ".mid", (b, a) -> (b + a) / 2.0, wBid, wAsk);
            allMids.add(mid);
        }

        // Global Score Aggregation (Average of all Mids)
        // Function: (m1, m2, ...) -> sum(m) / count(m)
        g.computeN("Global.Score",
                allMids.toArray(new DoubleReadable[0]),
                inputs -> {
                    double sum = 0;
                    for (double v : inputs) {
                        sum += v;
                    }
                    return inputs.length == 0 ? 0 : sum / inputs.length;
                });

        // 2. Build Engine
        var context = g.buildWithContext();
        var engine = context.engine();
        var nodes = context.nodesByName();

        // 3. Setup Disruptor
        int bufferSize = 1024;
        Disruptor<GraphEvent> disruptor = new Disruptor<>(
                GraphEvent::new,
                bufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new BlockingWaitStrategy());

        // 4. Connect GraphPublisher
        GraphPublisher publisher = new GraphPublisher(engine);

        long tStart = System.nanoTime();
        int[] updateCount = { 0 };
        int totalUpdates = 200_000; // Increased count
        CountDownLatch latch = new CountDownLatch(1);

        publisher.setPostStabilizationCallback((epoch, count) -> {
            updateCount[0]++;
            if (updateCount[0] % 10000 == 0) {
                printSnapshot("5Y", nodes);
            }
            if (updateCount[0] >= totalUpdates) {
                latch.countDown();
            }
        });

        disruptor.handleEventsWith((event, sequence, endOfBatch) -> publisher.onEvent(event, sequence, endOfBatch));

        disruptor.start();
        RingBuffer<GraphEvent> ringBuffer = disruptor.getRingBuffer();

        // --- Export Graph Visualization ---
        try {
            String mermaid = new GraphExplain(engine).toMermaid();
            java.nio.file.Files.writeString(java.nio.file.Path.of("treasury_graph_2.md"), mermaid);
            log.info("Graph visualization saved to treasury_graph_2.md");
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
        // ----------------------------------

        // 5. Simulation Loop
        Random rng = new Random(42);
        log.info("Starting producer...");

        // Pre-resolve node IDs (Zero-GC hot path)
        int[] bidIds = new int[tenors.length * venues.length];
        int[] bidQtyIds = new int[tenors.length * venues.length];
        int[] askIds = new int[tenors.length * venues.length];
        int[] askQtyIds = new int[tenors.length * venues.length];

        int idx = 0;
        for (String tenor : tenors) {
            String prefix = "UST_" + tenor;
            for (String venue : venues) {
                String base = prefix + "." + venue;
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

            publish(ringBuffer, bidIds[k], bidPx, false);
            publish(ringBuffer, bidQtyIds[k], 1000 + rng.nextInt(500), false);
            publish(ringBuffer, askIds[k], askPx, false);
            publish(ringBuffer, askQtyIds[k], 1000 + rng.nextInt(500), true); // End of batch
        }

        latch.await();
        long tEnd = System.nanoTime();
        double nanosPerOp = (double) (tEnd - tStart) / totalUpdates;
        log.info(String.format("Done. %.2f ns/op", nanosPerOp));

        disruptor.shutdown();
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

    private static void publish(RingBuffer<GraphEvent> rb, int nodeId, double value, boolean batchEnd) {
        long seq = rb.next();
        try {
            GraphEvent event = rb.get(seq);
            event.setDoubleUpdate(nodeId, value, batchEnd, seq);
        } finally {
            rb.publish(seq);
        }
    }

    private static void printSnapshot(String tenor, Map<String, Node<?>> nodes) {
        String p = "UST_" + tenor;
        Node<?> wBid = nodes.get(p + ".wBid");
        Node<?> wAsk = nodes.get(p + ".wAsk");
        Node<?> score = nodes.get("Global.Score");

        if (wBid instanceof DoubleReadable && wAsk instanceof DoubleReadable) {
            double b = ((DoubleReadable) wBid).doubleValue();
            double a = ((DoubleReadable) wAsk).doubleValue();
            double s = (score instanceof DoubleReadable) ? ((DoubleReadable) score).doubleValue() : Double.NaN;
            log.info(String.format("[%s] wBid: %.4f | wAsk: %.4f | Global.Score: %.4f", tenor, b, a, s));
        }
    }
}
