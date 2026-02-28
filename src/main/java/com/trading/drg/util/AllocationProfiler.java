package com.trading.drg.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Tracks exact heap memory allocations per thread to verify Zero-GC compliance.
 */
public class AllocationProfiler {
    private final com.sun.management.ThreadMXBean threadBean;
    private long startBytes;
    private volatile long lastAllocatedBytes = -1;

    public AllocationProfiler() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean) {
            this.threadBean = (com.sun.management.ThreadMXBean) bean;
            if (this.threadBean.isThreadAllocatedMemorySupported()) {
                this.threadBean.setThreadAllocatedMemoryEnabled(true);
            }
        } else {
            this.threadBean = null;
        }
    }

    /** Snapshots the current thread's allocated bytes. */
    public void start() {
        if (threadBean != null) {
            startBytes = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
    }

    /** Returns the exact number of bytes allocated since start() was called. */
    public long stop() {
        if (threadBean != null) {
            long endBytes = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            lastAllocatedBytes = endBytes - startBytes;
            return lastAllocatedBytes;
        }
        return -1;
    }

    /** Returns the last measured allocation bytes for passive telemetry. */
    public long getLastAllocatedBytes() {
        return lastAllocatedBytes;
    }
}
