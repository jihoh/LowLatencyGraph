package com.trading.drg.timer;

import com.trading.drg.CoreGraph;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class GraphTimerSchedulerTest {

    @Test
    public void testGraphTimerSchedulerStartsAndStops() throws Exception {
        CoreGraph graph = new CoreGraph("src/main/resources/timer_demo.json");
        GraphTimerScheduler scheduler = new GraphTimerScheduler(graph);

        assertFalse(scheduler.isRunning());

        AtomicInteger ticks = new AtomicInteger(0);
        scheduler.start((timer, nodeId) -> {
            ticks.incrementAndGet();
        });

        assertTrue(scheduler.isRunning());
        Thread.sleep(1200);

        assertTrue("Scheduler should have fired ticks", ticks.get() > 0);

        scheduler.stop();
        assertFalse(scheduler.isRunning());
    }

    @Test
    public void testSchedulerWithoutTimerNodesDoesNotSpawnThread() throws Exception {
        File tempJson = File.createTempFile("no_timer_graph", ".json");
        tempJson.deleteOnExit();
        try (FileWriter writer = new FileWriter(tempJson)) {
            writer.write("{\"graph\": {\"name\": \"NoTimer\", \"version\": \"1.0\", \"nodes\": [{\"name\": \"val\", \"type\": \"SCALAR_SOURCE\", \"properties\": {\"value\": 10.0}}]}}");
        }

        CoreGraph graph = new CoreGraph(tempJson.getAbsolutePath());
        GraphTimerScheduler scheduler = new GraphTimerScheduler(graph);

        scheduler.start((timer, nodeId) -> {});
        assertFalse("Scheduler should not be marked running if 0 timer nodes exist", scheduler.isRunning());
    }
}
