package com.trading.drg.web;

import java.lang.management.*;
import java.util.List;

/**
 * Collects JVM telemetry (heap, GC, threads, memory pools) for dashboard
 * publishing.
 * Extracted from WebsocketPublisherListener to keep that class focused on
 * orchestration.
 */
public final class JvmMetricsCollector {
    private final MemoryMXBean memBean;
    private final ThreadMXBean threadBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final List<MemoryPoolMXBean> poolBeans;
    private final RuntimeMXBean runtimeBean;

    // Cached results (written by collect(), read by append())
    private long heapUsed, heapMax;
    private long edenUsed, edenMax;
    private long survivorUsed, survivorMax;
    private long oldGenUsed, oldGenMax;
    private long uptimeMs;
    private int threadCount;
    private long youngGcCount, youngGcTime;
    private long oldGcCount, oldGcTime;

    public JvmMetricsCollector() {
        this.memBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.poolBeans = ManagementFactory.getMemoryPoolMXBeans();
        this.runtimeBean = ManagementFactory.getRuntimeMXBean();
    }

    /** Samples all JVM metrics. Call from the I/O thread before appending JSON. */
    public void collect() {
        heapUsed = memBean.getHeapMemoryUsage().getUsed();
        heapMax = memBean.getHeapMemoryUsage().getMax();
        threadCount = threadBean.getThreadCount();
        uptimeMs = runtimeBean.getUptime();

        youngGcCount = 0;
        youngGcTime = 0;
        oldGcCount = 0;
        oldGcTime = 0;

        for (int i = 0; i < gcBeans.size(); i++) {
            GarbageCollectorMXBean gc = gcBeans.get(i);
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) {
                String name = gc.getName();
                if (name.contains("G1 Old") || name.contains("MarkSweep") || name.contains("PS Old")
                        || name.contains("ConcurrentMarkSweep")) {
                    oldGcCount += c;
                    oldGcTime += t;
                } else {
                    youngGcCount += c;
                    youngGcTime += t;
                }
            }
        }

        edenUsed = 0;
        edenMax = 0;
        survivorUsed = 0;
        survivorMax = 0;
        oldGenUsed = 0;
        oldGenMax = 0;

        for (int i = 0; i < poolBeans.size(); i++) {
            MemoryPoolMXBean p = poolBeans.get(i);
            MemoryUsage u = p.getUsage();
            if (u == null)
                continue;
            String name = p.getName();
            if (name.contains("Eden")) {
                edenUsed = u.getUsed();
                edenMax = u.getMax();
            } else if (name.contains("Survivor")) {
                survivorUsed = u.getUsed();
                survivorMax = u.getMax();
            } else if (name.contains("Old") || name.contains("Tenured")) {
                oldGenUsed = u.getUsed();
                oldGenMax = u.getMax();
            }
        }
    }

    /** Appends the collected JVM metrics as a JSON object into the builder. */
    public void appendJson(StringBuilder sb) {
        sb.append("{")
                .append("\"heapUsed\":").append(heapUsed).append(",")
                .append("\"heapMax\":").append(heapMax).append(",")
                .append("\"edenUsed\":").append(edenUsed).append(",")
                .append("\"edenMax\":").append(edenMax).append(",")
                .append("\"survivorUsed\":").append(survivorUsed).append(",")
                .append("\"survivorMax\":").append(survivorMax).append(",")
                .append("\"oldGenUsed\":").append(oldGenUsed).append(",")
                .append("\"oldGenMax\":").append(oldGenMax).append(",")
                .append("\"uptime\":").append(uptimeMs).append(",")
                .append("\"threads\":").append(threadCount).append(",")
                .append("\"youngGcCount\":").append(youngGcCount).append(",")
                .append("\"youngGcTime\":").append(youngGcTime).append(",")
                .append("\"oldGcCount\":").append(oldGcCount).append(",")
                .append("\"oldGcTime\":").append(oldGcTime);
    }
}
