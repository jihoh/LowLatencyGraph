package com.trading.drg;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.trading.drg.util.AllocationProfiler;
import com.trading.drg.web.DashboardWiring;
import com.lmax.disruptor.EventHandler;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.trading.drg.api.GraphAutoRouter;

/**
 * End-To-End Testing Demo using LMAX Disruptor pattern.
 * <p>
 * This generates a generic complex graph with 1000+ nodes, 1000+ edges, and 10+
 * layers of depth.
 */
@Log4j2
public class CoreGraphStressDemo {

    private static final int PORT = 8082;
    private static final int RING_BUFFER_SIZE = 4096;
    private static final int ITEM_COUNT = 100;
    private static final String[] ITEM_CODES = new String[ITEM_COUNT];

    static {
        for (int i = 0; i < ITEM_COUNT; i++) {
            ITEM_CODES[i] = String.format("Item_%03d", i);
        }
    }

    public static void main(String[] args) throws Exception {
        log.info("Generating 1300-node Massive Generic JSON Graph...");
        String jsonPath = "src/main/resources/massive_graph.json";

        log.info("Starting Disruptor E2E Demo on massive graph...");

        // 1. Initialize Graph Engine
        CoreGraph graph = new CoreGraph(jsonPath);

        // 2. Setup LMAX Disruptor
        ThreadFactory threadFactory = DaemonThreadFactory.INSTANCE;
        int bufferSize = RING_BUFFER_SIZE;

        Disruptor<MarketDataEvent> disruptor = new Disruptor<>(
                MarketDataEvent::new,
                bufferSize,
                threadFactory,
                ProducerType.SINGLE,
                new YieldingWaitStrategy());

        // Bind our logic handler to the ring buffer
        MarketDataEventHandler handler = new MarketDataEventHandler(graph);
        disruptor.handleEventsWith(handler);

        // Start Disruptor Native Executor
        RingBuffer<MarketDataEvent> ringBuffer = disruptor.start();

        // 3. Setup Dashboard Server with Telemetry
        new DashboardWiring(graph)
                // .enableNodeProfiling() // disabling saves 1us per stabilization in this demo
                .enableLatencyTracking()
                .withThrottleIntervalMs(1000) // enable this for ultimate performance - enable for PROD
                .bindDisruptorTelemetry(ringBuffer)
                .withAllocationProfiler(handler.getProfiler())
                .enableDashboardServer(PORT);

        // 4. Simulate Market Feed (Producer Thread)
        simulateMarketFeed(ringBuffer);
    }

    private static void simulateMarketFeed(RingBuffer<MarketDataEvent> ringBuffer) {
        log.info("Simulating High-Frequency Generic Feed Injector...");
        log.info("Entering Randomized HFT Simulation Loop...");
        while (true) {
            try {
                int burstSize = 1 + (int) (Math.random() * 20);

                for (int i = 0; i < burstSize; i++) {
                    int itemIdx = (int) (Math.random() * ITEM_COUNT);
                    String itemCode = ITEM_CODES[itemIdx];

                    long sequence = ringBuffer.next();
                    try {
                        MarketDataEvent event = ringBuffer.get(sequence);

                        double s1 = 100.0 + (Math.random() - 0.5) * 5.0;
                        double s2 = 50.0 + (Math.random() - 0.5) * 2.0;
                        double s3 = 10.0 + (Math.random() - 0.5) * 1.0;
                        event.setQuote(itemCode, s1, s2, s3);
                    } finally {
                        ringBuffer.publish(sequence);
                    }
                }
                TimeUnit.MICROSECONDS.sleep(10);
            } catch (Exception e) {
                log.error("Simulator interrupted", e);
                break;
            }
        }
    }

    public static class MarketDataEventHandler implements EventHandler<MarketDataEvent> {

        private final CoreGraph graph;
        private final GraphAutoRouter router;

        @Getter
        private final AllocationProfiler profiler;

        public MarketDataEventHandler(CoreGraph graph) {
            this.graph = graph;
            this.router = new GraphAutoRouter(graph)
                    .registerClass(MarketDataEvent.class);
            this.profiler = new AllocationProfiler();

            log.info("GraphAutoRouter mappings created for generic items successfully.");
        }

        @Override
        public void onEvent(MarketDataEvent event, long sequence, boolean endOfBatch) throws Exception {
            profiler.start();
            router.route(event);

            if (endOfBatch && graph != null) {
                graph.stabilize();
            }

            long bytesAllocated = profiler.stop();
            if (bytesAllocated > 0 && sequence > 100000) {
                System.err.println("WARNING: Hot-path allocated " + bytesAllocated + " bytes at sequence " + sequence);
            }
        }
    }

    @Getter
    public static class MarketDataEvent {
        @GraphAutoRouter.RoutingKey(order = 1)
        private String itemCode;

        @GraphAutoRouter.RoutingValue
        private double source1;
        @GraphAutoRouter.RoutingValue
        private double source2;
        @GraphAutoRouter.RoutingValue
        private double source3;

        public MarketDataEvent() {
            clear();
        }

        public void clear() {
            this.itemCode = null;
            this.source1 = Double.NaN;
            this.source2 = Double.NaN;
            this.source3 = Double.NaN;
        }

        public void setQuote(String itemCode, double s1, double s2, double s3) {
            this.itemCode = itemCode;
            this.source1 = s1;
            this.source2 = s2;
            this.source3 = s3;
        }
    }
}
