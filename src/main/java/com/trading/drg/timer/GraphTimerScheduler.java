package com.trading.drg.timer;

import com.trading.drg.CoreGraph;
import com.trading.drg.api.TimerTickCallback;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.node.TimerSourceNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import lombok.extern.log4j.Log4j2;

/**
 * Dedicated background scheduler for {@link TimerSourceNode}s in a {@link CoreGraph}.
 * <p>
 * Decouples thread pool management and clock scheduling from the core compute graph.
 * Discovers all {@link TimerSourceNode} instances in the graph topology and schedules
 * periodic ticks calling the provided {@link TimerTickCallback}.
 * <p>
 * Implements {@link AutoCloseable} for clean resource cleanup.
 */
@Log4j2
public final class GraphTimerScheduler implements AutoCloseable {

    private final CoreGraph graph;
    private ScheduledExecutorService timerScheduler;
    private List<ScheduledFuture<?>> timerFutures;

    /**
     * Creates a timer scheduler for the given {@link CoreGraph}.
     *
     * @param graph target graph instance containing timer nodes
     */
    public GraphTimerScheduler(CoreGraph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }
        this.graph = graph;
    }

    /**
     * Starts all {@link TimerSourceNode}s in the graph using a single shared
     * daemon thread for scheduling.
     * <p>
     * If no {@link TimerSourceNode}s exist in the graph topology, no background
     * scheduler thread will be created.
     *
     * @param onTimerTick callback receiving the timer node and its topological ID
     */
    public synchronized void start(TimerTickCallback onTimerTick) {
        if (timerScheduler != null) {
            throw new IllegalStateException("Timers already started");
        }
        if (onTimerTick == null) {
            throw new IllegalArgumentException("onTimerTick callback cannot be null");
        }

        TopologicalOrder topology = graph.getEngine().topology();
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        ScheduledExecutorService scheduler = null;

        for (int i = 0; i < topology.nodeCount(); i++) {
            if (topology.node(i) instanceof TimerSourceNode timer) {
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "coregraph-timer");
                        t.setDaemon(true);
                        return t;
                    });
                }
                final int nodeId = i;
                long intervalMs = timer.intervalMs();
                ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                        () -> onTimerTick.onTick(timer, nodeId),
                        intervalMs, intervalMs, TimeUnit.MILLISECONDS);
                futures.add(future);
            }
        }

        this.timerScheduler = scheduler;
        this.timerFutures = futures;
    }

    /** Returns {@code true} if the scheduler is actively running. */
    public synchronized boolean isRunning() {
        return timerScheduler != null && !timerScheduler.isShutdown();
    }

    /** Stops all running timers and shuts down the background scheduler executor. */
    public synchronized void stop() {
        if (timerFutures != null) {
            for (ScheduledFuture<?> future : timerFutures) {
                future.cancel(false);
            }
            timerFutures = null;
        }
        if (timerScheduler != null) {
            timerScheduler.shutdown();
            timerScheduler = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
