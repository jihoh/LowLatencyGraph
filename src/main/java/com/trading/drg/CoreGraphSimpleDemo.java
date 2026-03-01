package com.trading.drg;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.trading.drg.api.GraphAutoRouter;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.Random;

/**
 * Demonstrates the usage of the {@link CoreGraph} wrapper class.
 */
@Log4j2
public class CoreGraphSimpleDemo {

    public static void main(String[] args) throws Exception {
        log.info("Starting CoreGraph Simple Demo...");

        // 1. Initialize the graph
        var graph = new CoreGraph("src/main/resources/fx_arb.json");

        // 2. Initialize Disruptor
        int bufferSize = 1024;
        Disruptor<FxTickEvent> disruptor = new Disruptor<>(
                FxTickEvent::new,
                bufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy());

        // 3. Bind Handler
        FxTickEventHandler handler = new FxTickEventHandler(graph);
        disruptor.handleEventsWith(handler);
        RingBuffer<FxTickEvent> ringBuffer = disruptor.start();

        // 4. Wiring
        var dashboard = new com.trading.drg.web.DashboardWiring(graph)
                .enableNodeProfiling()
                .enableLatencyTracking()
                .bindDisruptorTelemetry(ringBuffer)
                .withAllocationProfiler(handler.getProfiler())
                .enableDashboardServer(8081);

        // Run the simulation
        simulateMarketFeed(ringBuffer);

        // Stop the dashboard server after demo
        dashboard.getDashboardServer().stop();
        System.exit(0);
    }

    public static class FxTickEvent {
        @GraphAutoRouter.RoutingValue
        public double EURUSD = Double.NaN;
        @GraphAutoRouter.RoutingValue
        public double USDJPY = Double.NaN;
        @GraphAutoRouter.RoutingValue
        public double EURJPY = Double.NaN;

        public void set(double eurUsd, double usdJpy, double eurJpy) {
            this.EURUSD = eurUsd;
            this.USDJPY = usdJpy;
            this.EURJPY = eurJpy;
        }
    }

    public static class FxTickEventHandler implements com.lmax.disruptor.EventHandler<FxTickEvent> {
        private final CoreGraph graph;
        private final GraphAutoRouter router;

        @Getter
        private final com.trading.drg.util.AllocationProfiler profiler;

        public FxTickEventHandler(CoreGraph graph) {
            this.graph = graph;
            this.router = new GraphAutoRouter(graph).registerClass(FxTickEvent.class);
            this.profiler = new com.trading.drg.util.AllocationProfiler();

            // Warmup
            FxTickEvent dummy = new FxTickEvent();
            dummy.set(0, 0, 0);
            router.route(dummy);
        }

        @Override
        public void onEvent(FxTickEvent event, long sequence, boolean endOfBatch) throws Exception {
            profiler.start();

            // Zero-GC routing using the class fields purely
            router.route(event);

            if (endOfBatch && graph != null) {
                graph.stabilize();
            }

            profiler.stop();
        }
    }

    private static void simulateMarketFeed(RingBuffer<FxTickEvent> ringBuffer) {
        Random rng = new Random(42);
        int updates = 100_000;

        double currentEurUsd = 1.18;
        double currentUsdJpy = 154.9;
        double currentEurJpy = 182.8;

        // Push baseline immediately
        long initSeq = ringBuffer.next();
        try {
            ringBuffer.get(initSeq).set(currentEurUsd, currentUsdJpy, currentEurJpy);
        } finally {
            ringBuffer.publish(initSeq);
        }

        int nanCountdown = 0;
        int nanTargetIndex = -1; // 0=EURUSD, 1=USDJPY, 2=EURJPY

        log.info("Publishing updates (1 tick/sec, with 5s NaN faults)...");
        for (int i = 0; i < updates; i++) {

            if (nanCountdown > 0) {
                if (nanTargetIndex == 0)
                    currentEurUsd = Double.NaN;
                else if (nanTargetIndex == 1)
                    currentUsdJpy = Double.NaN;
                else
                    currentEurJpy = Double.NaN;

                nanCountdown--;
                if (nanCountdown == 0) {
                    currentEurUsd = 1.18;
                    currentUsdJpy = 154.9;
                    currentEurJpy = 182.8;
                    log.info("Recovered from NaN fault.");
                }
            } else if (i > 0 && i % 500 == 0) {
                nanTargetIndex = rng.nextInt(3);
                nanCountdown = 100;
                log.info("Injected NaN fault into index " + nanTargetIndex + " for 100 seconds.");
            } else {
                int targetNode = rng.nextInt(3);
                while (nanCountdown > 0 && nanTargetIndex == targetNode) {
                    targetNode = rng.nextInt(3);
                }

                double shock = (rng.nextDouble() - 0.5) * 0.05;
                if (targetNode == 0) {
                    currentEurUsd += shock;
                } else if (targetNode == 1) {
                    currentUsdJpy += (shock * 100);
                } else {
                    currentEurJpy += (shock * 100);
                }
            }

            // Publish cleanly to the RingBuffer without allocating
            long sequence = ringBuffer.next();
            try {
                ringBuffer.get(sequence).set(currentEurUsd, currentUsdJpy, currentEurJpy);
            } finally {
                ringBuffer.publish(sequence);
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
