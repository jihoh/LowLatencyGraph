package com.trading.drg;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.lmax.disruptor.EventHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ThreadFactory;

/**
 * End-To-End Testing Demo using LMAX Disruptor pattern.
 * <p>
 * Simulates an external market data feed streaming real-time QUOTE
 * events into a Disruptor RingBuffer. A dedicated consumer translates those
 * raw binary network events into explicit node updates and strictly triggers
 * stabilization bursts exactly per batch.
 */
public class CoreGraphComplexDemo {

    private static final Logger log = LogManager.getLogger(CoreGraphComplexDemo.class);
    private static final int PORT = 8080;
    private static final int RING_BUFFER_SIZE = 1024;

    public static void main(String[] args) throws Exception {
        log.info("Starting Disruptor E2E Demo...");

        // 1. Initialize Graph Engine
        CoreGraph graph = new CoreGraph("src/main/resources/bond_pricer.json");

        // 2. Setup LMAX Disruptor
        ThreadFactory threadFactory = DaemonThreadFactory.INSTANCE;
        int bufferSize = RING_BUFFER_SIZE;

        Disruptor<MarketDataEvent> disruptor = new Disruptor<>(
                MarketDataEvent::new,
                bufferSize,
                threadFactory,
                ProducerType.SINGLE,
                new BlockingWaitStrategy());

        // Bind our logic handler to the ring buffer
        MarketDataEventHandler handler = new MarketDataEventHandler(graph);
        disruptor.handleEventsWith(handler);

        // Start Disruptor Native Executor
        RingBuffer<MarketDataEvent> ringBuffer = disruptor.start();

        // 3. Setup Dashboard Server with Telemetry
        new com.trading.drg.web.DashboardWiring(graph)
                .enableNodeProfiling()
                .enableLatencyTracking()
                .bindDisruptorTelemetry(ringBuffer)
                .withAllocationProfiler(handler.getProfiler())
                .enableDashboardServer(PORT);

        // 4. Simulate Market Feed (Producer Thread)
        simulateMarketFeed(ringBuffer);
    }

    private static void simulateMarketFeed(RingBuffer<MarketDataEvent> ringBuffer) {
        log.info("Simulating High-Frequency Market Feed Injector...");

        String[] tenors = { "UST_2Y", "UST_3Y", "UST_5Y", "UST_10Y", "UST_30Y" };
        String[] venues = { "Btec", "Fenics", "Dweb" };

        double baseBid = 99.50;
        double baseAsk = 100.50;

        log.info("Entering Randomized HFT Simulation Loop...");
        while (true) {
            try {
                // Determine burst size for this epoch natively into buffer
                int burstSize = 1 + (int) (Math.random() * 5); // 1-5 updates per tick

                for (int i = 0; i < burstSize; i++) {
                    // Pre-select random routes using primitive indexes (Zero string heap garbage)
                    String tenor = tenors[(int) (Math.random() * tenors.length)];
                    String venue = venues[(int) (Math.random() * venues.length)];

                    long sequence = ringBuffer.next();
                    try {
                        MarketDataEvent event = ringBuffer.get(sequence);

                        double bid = baseBid + (Math.random() - 0.5) * 0.1;
                        double ask = baseAsk + (Math.random() - 0.5) * 0.1;
                        double bidQty = 1.0 + Math.floor(Math.random() * 10);
                        double askQty = 1.0 + Math.floor(Math.random() * 10);
                        event.setQuote(venue, tenor, bid, bidQty, ask, askQty);
                    } finally {
                        ringBuffer.publish(sequence);
                    }
                }

                // Simulate data feed rate limits
                Thread.sleep(100);

            } catch (Exception e) {
                log.error("Simulator interrupted", e);
                break;
            }
        }
    }

    public static class MarketDataEventHandler implements EventHandler<MarketDataEvent> {

        private final CoreGraph graph;
        private final com.trading.drg.api.GraphAutoRouter router;

        @lombok.Getter
        private final com.trading.drg.util.AllocationProfiler profiler;

        public MarketDataEventHandler(CoreGraph graph) {
            this.graph = graph;
            this.router = new com.trading.drg.api.GraphAutoRouter(graph)
                    .registerClass(MarketDataEvent.class);
            this.profiler = new com.trading.drg.util.AllocationProfiler();
        }

        @Override
        public void onEvent(MarketDataEvent event, long sequence, boolean endOfBatch) throws Exception {
            // Snapshot current Thread Allocated Bytes
            profiler.start();

            // Zero-allocation, Zero GC topological update based purely on Trie routing
            router.route(event);

            // The beauty of the Disruptor is endOfBatch.
            // It guarantees we only stabilize the graph ONCE per burst, maximizing
            // throughput and preventing jitter.
            if (endOfBatch && graph != null) {
                graph.stabilize();
            }

            long bytesAllocated = profiler.stop();
            // Ignore the JVM JIT warmup phase
            if (bytesAllocated > 0 && sequence > 10000) {
                System.err.println("WARNING: Hot-path allocated " + bytesAllocated + " bytes at sequence " + sequence);
            }
        }
    }

    @lombok.Getter
    public static class MarketDataEvent {
        @com.trading.drg.api.GraphAutoRouter.RoutingKey(order = 1)
        private String instrument;
        @com.trading.drg.api.GraphAutoRouter.RoutingKey(order = 2)
        private String venue;

        @com.trading.drg.api.GraphAutoRouter.RoutingValue("bid")
        private double bid;
        @com.trading.drg.api.GraphAutoRouter.RoutingValue("bidQty")
        private double bidQty;
        @com.trading.drg.api.GraphAutoRouter.RoutingValue("ask")
        private double ask;
        @com.trading.drg.api.GraphAutoRouter.RoutingValue("askQty")
        private double askQty;

        public MarketDataEvent() {
            clear();
        }

        public void clear() {
            this.venue = null;
            this.instrument = null;
            this.bid = Double.NaN;
            this.bidQty = Double.NaN;
            this.ask = Double.NaN;
            this.askQty = Double.NaN;
        }

        public void setQuote(String venue, String instrument, double bid, double bidQty, double ask, double askQty) {
            this.venue = venue;
            this.instrument = instrument;
            this.bid = bid;
            this.bidQty = bidQty;
            this.ask = ask;
            this.askQty = askQty;
        }
    }
}
