package com.trading.drg;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;

/**
 * Demonstrates the usage of Vector Nodes within the {@link CoreGraph} wrapper.
 */
public class CoreGraphVectorDemo {
    private static final Logger log = LogManager.getLogger(CoreGraphVectorDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting CoreGraph Vector Demo...");

        var graph = new CoreGraph("src/main/resources/vector_demo.json");

        // 2. Initialize Disruptor
        int bufferSize = 1024;
        Disruptor<VectorUpdateEvent> disruptor = new Disruptor<>(
                VectorUpdateEvent::new,
                bufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy());

        // 3. Bind Handler
        VectorUpdateHandler handler = new VectorUpdateHandler(graph);
        disruptor.handleEventsWith(handler);
        RingBuffer<VectorUpdateEvent> ringBuffer = disruptor.start();

        // 4. Wiring
        var dashboard = new com.trading.drg.web.DashboardWiring(graph)
                .enableNodeProfiling()
                .enableLatencyTracking()
                .bindDisruptorTelemetry(ringBuffer)
                .withAllocationProfiler(handler.getProfiler())
                .enableDashboardServer(8082);

        // Run the simulation
        simulateVectorFeed(ringBuffer, graph.getNodeId("MarketYieldCurve"));

        // Stop the dashboard server after demo
        dashboard.getDashboardServer().stop();
        System.exit(0);
    }

    public static class VectorUpdateEvent {
        public int nodeId;
        public int index;
        public double value;

        public void set(int nodeId, int index, double value) {
            this.nodeId = nodeId;
            this.index = index;
            this.value = value;
        }
    }

    public static class VectorUpdateHandler implements com.lmax.disruptor.EventHandler<VectorUpdateEvent> {
        private final CoreGraph graph;

        @lombok.Getter
        private final com.trading.drg.util.AllocationProfiler profiler;

        public VectorUpdateHandler(CoreGraph graph) {
            this.graph = graph;
            this.profiler = new com.trading.drg.util.AllocationProfiler();
        }

        @Override
        public void onEvent(VectorUpdateEvent event, long sequence, boolean endOfBatch) {
            profiler.start();

            // Zero-GC Array Indexing update
            graph.update(event.nodeId, event.index, event.value);

            if (endOfBatch && graph != null) {
                graph.stabilize();
            }

            profiler.stop();
        }
    }

    private static void simulateVectorFeed(RingBuffer<VectorUpdateEvent> ringBuffer, int curveNodeId) {
        Random rng = new Random(42);
        int updates = 20_000;

        // Initialize the base yield curve vector: [1M, 3M, 6M, 1Y, 2Y]
        double[] baseCurve = { 4.50, 4.55, 4.60, 4.65, 4.70 };

        // Push initial state to the ring buffer
        for (int i = 0; i < baseCurve.length; i++) {
            long sequence = ringBuffer.next();
            try {
                ringBuffer.get(sequence).set(curveNodeId, i, baseCurve[i]);
            } finally {
                ringBuffer.publish(sequence);
            }
        }

        log.info("Publishing vector updates (mock yield curve shifts)...");
        for (int i = 0; i < updates; i++) {

            // Randomly shift 1 to 3 tenors of the curve
            int shifts = 1 + rng.nextInt(3);
            for (int s = 0; s < shifts; s++) {
                int tenorIndex = rng.nextInt(5);
                // small bps shock
                double shock = (rng.nextDouble() - 0.5) * 0.10;
                baseCurve[tenorIndex] += shock;

                long sequence = ringBuffer.next();
                try {
                    ringBuffer.get(sequence).set(curveNodeId, tenorIndex, baseCurve[tenorIndex]);
                } finally {
                    ringBuffer.publish(sequence);
                }
            }

            try {
                Thread.sleep(100); // Pause for dashboard visualization
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
