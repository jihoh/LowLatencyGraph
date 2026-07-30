package com.trading.drg.demo;

import com.trading.drg.CoreGraph;
import com.trading.drg.web.DashboardWiring;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Minimal Standalone Demo containing ONLY Timer nodes.
 * <p>
 * Demonstrates periodic timer execution, zero-allocation dirty propagation,
 * and real-time streaming to the Web Dashboard on port 8089.
 */
@Log4j2
public class TimerDemo {

    private static final int PORT = 8089;
    private static final int RING_BUFFER_SIZE = 1024;

    public static void main(String[] args) throws Exception {
        log.info("Starting Pure Timer Demo...");

        // 1. Initialize Graph Engine with Timer-only Topology
        CoreGraph graph = new CoreGraph("src/main/resources/timer_demo.json");

        // 2. Setup Disruptor Event Loop
        Disruptor<TimerEvent> disruptor = new Disruptor<>(
                TimerEvent::new,
                RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,
                new YieldingWaitStrategy());

        disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            if (event.getTimerNodeId() >= 0) {
                graph.getEngine().markDirty(event.getTimerNodeId());
            }
            if (endOfBatch) {
                graph.stabilize();
            }
        });

        RingBuffer<TimerEvent> ringBuffer = disruptor.start();

        // 3. Start Timer Scheduler — publishes synthetic ticks into the RingBuffer
        com.trading.drg.timer.GraphTimerScheduler timerScheduler = new com.trading.drg.timer.GraphTimerScheduler(graph);
        timerScheduler.start((timer, nodeId) -> {
            long seq = ringBuffer.next();
            try {
                TimerEvent event = ringBuffer.get(seq);
                event.setTimerNodeId(nodeId);
            } finally {
                ringBuffer.publish(seq);
            }
        });
        log.info("Timers started (1s, 2s, 0.5s intervals)");

        // 4. Enable Live Web Dashboard Telemetry on Port 8089
        new DashboardWiring(graph)
                .enableNodeProfiling()
                .withRollingWindowSec(300)
                .enableDashboardServer(PORT);

        log.info("=================================================");
        log.info("Dashboard live at: http://localhost:{}", PORT);
        log.info("=================================================");

        // 5. Console Status Output
        long startMs = System.currentTimeMillis();
        while (true) {
            Thread.sleep(1000);
            long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
            log.info("[{:3d}s] 0.5s: {:.0f} (ewma: {:.2f}) | 1s: {:.0f} (ewma: {:.2f}) | 2s: {:.0f} (ewma: {:.2f})",
                    elapsedSec,
                    graph.getDouble("0.5 sec timer"),
                    graph.getDouble("0.5 sec timer.ewma"),
                    graph.getDouble("1 sec timer"),
                    graph.getDouble("1 sec timer.ewma"),
                    graph.getDouble("2 sec timer"),
                    graph.getDouble("2 sec timer.ewma"));
        }
    }

    @Getter
    public static class TimerEvent {
        private int timerNodeId = -1;

        public void setTimerNodeId(int nodeId) {
            this.timerNodeId = nodeId;
        }
    }
}
