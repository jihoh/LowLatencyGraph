package com.trading.drg;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.trading.drg.core.DoubleReadable;
import com.trading.drg.core.Node;
import com.trading.drg.disruptor.GraphEvent;
import com.trading.drg.disruptor.GraphPublisher;
import com.trading.drg.util.GraphExplain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

public class TreasurySimulator2 {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("════════════════════════════════════════════════");
        System.out.println("  Treasury Simulator 2 (Disruptor + Programmatic)");
        System.out.println("════════════════════════════════════════════════");

        // 1. Build Graph Programmatically
        var g = GraphBuilder.create("treasuries_prog");

        String[] tenors = { "2Y", "3Y", "5Y", "10Y", "30Y" }; // Expanded set
        String[] venues = { "Btec", "Fenics", "Dweb" };

        // Create topology for each tenor
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
            g.computeN(prefix + ".wBid",
                    bidInputs.toArray(new DoubleReadable[0]),
                    TreasurySimulator2::weightedAvg);

            g.computeN(prefix + ".wAsk",
                    askInputs.toArray(new DoubleReadable[0]),
                    TreasurySimulator2::weightedAvg);
        }

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
            System.out.println("Graph visualization saved to treasury_graph_2.md");
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
        // ----------------------------------

        // 5. Simulation Loop
        Random rng = new Random(42);
        System.out.println("Starting producer...");

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

            // Publish 4 events (Bid, Ask, BidQty, AskQty)
            publish(ringBuffer, bidIds[k], mid - spread / 2, false);
            publish(ringBuffer, bidQtyIds[k], 1000 + rng.nextInt(5000), false);
            publish(ringBuffer, askIds[k], mid + spread / 2, false);
            publish(ringBuffer, askQtyIds[k], 1000 + rng.nextInt(5000), true); // Trigger stabilize
        }

        latch.await();

        long elapsed = System.nanoTime() - tStart;
        System.out.printf("\nProcessed %d updates (batches) in %.1f ms (%.0f batches/sec)\n",
                totalUpdates, elapsed / 1e6, 1e9 * totalUpdates / elapsed);

        disruptor.shutdown();
    }

    // Weighted Average Logic: Pairs of (Value, Weight)
    private static double weightedAvg(double[] inputs) {
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < inputs.length; i += 2) {
            double px = inputs[i];
            double qty = inputs[i + 1];
            num += px * qty;
            den += qty;
        }
        return den == 0 ? 0 : num / den;
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
        Node<?> nBid = nodes.get("UST_" + tenor + ".wBid");
        Node<?> nAsk = nodes.get("UST_" + tenor + ".wAsk");

        if (nBid instanceof DoubleReadable b && nAsk instanceof DoubleReadable a) {
            double bid = b.doubleValue();
            double ask = a.doubleValue();
            System.out.printf("[UST_%s] Weighted Bid: %.5f | Weighted Ask: %.5f | Spread: %.5f\n",
                    tenor, bid, ask, ask - bid);
        }
    }
}
