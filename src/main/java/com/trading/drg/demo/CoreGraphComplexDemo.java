package com.trading.drg.demo;

import com.trading.drg.CoreGraph;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.lmax.disruptor.EventHandler;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.ThreadFactory;

/**
 * End-To-End Testing Demo using LMAX Disruptor pattern.
 * <p>
 * Simulates an external market data feed streaming real-time QUOTE
 * events into a Disruptor RingBuffer. A dedicated consumer translates those
 * raw binary network events into explicit node updates and strictly triggers
 * stabilization bursts exactly per batch.
 */
@Log4j2
public class CoreGraphComplexDemo {

    private static final int PORT = 8089;
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
                ProducerType.MULTI,
                new YieldingWaitStrategy());

        // Bind our logic handler to the ring buffer
        MarketDataEventHandler handler = new MarketDataEventHandler(graph);
        disruptor.handleEventsWith(handler);

        // Start Disruptor Native Executor
        RingBuffer<MarketDataEvent> ringBuffer = disruptor.start();

        // 3. Start timer nodes — publish synthetic ticks into the same RingBuffer
        graph.startTimers((timer, nodeId) -> {
            long seq = ringBuffer.next();
            try {
                MarketDataEvent event = ringBuffer.get(seq);
                event.setTimerTick(nodeId);
            } finally {
                ringBuffer.publish(seq);
            }
        });
        log.info("Timer nodes started (publishing into Disruptor RingBuffer)");

        // 4. Setup Dashboard Server with Telemetry
        new com.trading.drg.web.DashboardWiring(graph)
                .enableNodeProfiling()
                .withRollingWindowSec(300)
                .withWarmupEpochs(500)
                .enableLatencyTracking()
                .bindDisruptorTelemetry(ringBuffer)
                .withAllocationProfiler(handler.getProfiler())
                .enableDashboardServer(PORT);

        // 5. Simulate Market Feed (Producer Thread)
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

        @Getter
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

            if (event.getEventType() == MarketDataEvent.TYPE_TIMER) {
                // Timer tick: mark the timer node dirty for downstream propagation
                graph.getEngine().markDirty(event.getTimerNodeId());
            } else if (event.getEventType() == MarketDataEvent.TYPE_QUOTE) {
                // Market data: zero-allocation Trie routing
                router.route(event);
            }

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

    @Getter
    public static class MarketDataEvent {
        public static final byte TYPE_NONE = 0;
        public static final byte TYPE_QUOTE = 1;
        public static final byte TYPE_TIMER = 2;

        private byte eventType = TYPE_NONE;

        @com.trading.drg.api.GraphAutoRouter.RoutingKey(order = 1)
        private String instrument;
        @com.trading.drg.api.GraphAutoRouter.RoutingKey(order = 2)
        private String venue;

        @com.trading.drg.api.GraphAutoRouter.RoutingValue
        private double bid;
        @com.trading.drg.api.GraphAutoRouter.RoutingValue
        private double bidQty;
        @com.trading.drg.api.GraphAutoRouter.RoutingValue
        private double ask;
        @com.trading.drg.api.GraphAutoRouter.RoutingValue
        private double askQty;

        private int timerNodeId = -1;

        public MarketDataEvent() {
            clear();
        }

        public void clear() {
            this.eventType = TYPE_NONE;
            this.venue = null;
            this.instrument = null;
            this.bid = Double.NaN;
            this.bidQty = Double.NaN;
            this.ask = Double.NaN;
            this.askQty = Double.NaN;
            this.timerNodeId = -1;
        }

        /** Configures this event as a timer tick for the given node ID. */
        public void setTimerTick(int nodeId) {
            this.eventType = TYPE_TIMER;
            this.timerNodeId = nodeId;
        }

        public void setQuote(String venue, String instrument, double bid, double bidQty, double ask, double askQty) {
            this.eventType = TYPE_QUOTE;
            this.venue = venue;
            this.instrument = instrument;
            this.bid = bid;
            this.bidQty = bidQty;
            this.ask = ask;
            this.askQty = askQty;
        }
    }
}
