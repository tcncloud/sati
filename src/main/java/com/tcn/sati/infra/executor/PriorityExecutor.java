package com.tcn.sati.infra.executor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;

/**
 * A priority-based worker pool that ensures high-priority work (real-time jobs like
 * listPools, popAccount, readFields) is served before low-priority work (slow events
 * like onAgentCall, onTelephonyResult).
 *
 * <p>Backed by a single fixed thread pool with a {@link PriorityBlockingQueue} — all threads
 * serve both priorities and HIGH items are always dequeued first. A {@link Semaphore} caps the
 * number of concurrent LOW items in the pipeline ({@code maxLowDepth}); sati's virtual thread
 * blocks on {@code acquire()} when full, back-pressuring the gRPC stream naturally.
 *
 * <p>Exports OTel metrics and maintains in-process rolling stats for the dashboard.
 */
public class PriorityExecutor {

    private static final Logger log = LoggerFactory.getLogger(PriorityExecutor.class);

    private static final AttributeKey<String> ATTR_PRIORITY = AttributeKey.stringKey("priority");
    private static final AttributeKey<String> ATTR_STATUS = AttributeKey.stringKey("status");
    private static final AttributeKey<String> ATTR_TYPE = AttributeKey.stringKey("type");
    private static final String TYPE_UNSPECIFIED = "unspecified";

    public enum Priority {
        HIGH(0),  // fast jobs — served first
        LOW(1);   // slow events — served after all HIGH items

        final int order;

        Priority(int order) {
            this.order = order;
        }
    }

    private final ThreadPoolExecutor executor;
    private final int poolSize;
    private final AtomicLong sequence = new AtomicLong();

    // Hard bound on concurrent LOW items in the pipeline (queued + executing).
    private final Semaphore lowAdmission;

    // OTel instruments (nullable — no-op if meter not available)
    private final DoubleHistogram durationHistogram;
    private final DoubleHistogram queueWaitHistogram;
    private final LongCounter completedCounter;

    // In-process rolling stats
    private final RollingStats highStats = new RollingStats(1000);
    private final RollingStats lowStats = new RollingStats(1000);
    private final AtomicLong highCompleted = new AtomicLong();
    private final AtomicLong lowCompleted = new AtomicLong();
    private final AtomicLong highFailed = new AtomicLong();
    private final AtomicLong lowFailed = new AtomicLong();
    private final AtomicLong highSubmitted = new AtomicLong();
    private final AtomicLong lowSubmitted = new AtomicLong();
    private final RecentTasks recentTasks = new RecentTasks(50);
    private final ThroughputTracker throughputTracker =
            new ThroughputTracker(Duration.ofSeconds(10));

    // Per-type stats keyed by "HIGH:list_pools", "LOW:agent_call", etc.
    private final ConcurrentHashMap<String, TypeStats> byType = new ConcurrentHashMap<>();

    public PriorityExecutor(int poolSize, int maxLowDepth, Meter meter) {
        this.poolSize = poolSize;
        this.lowAdmission = new Semaphore(maxLowDepth > 0 ? maxLowDepth : 100);

        var queue = new PriorityBlockingQueue<Runnable>(64, (a, b) -> {
            if (a instanceof PriorityTask<?> pa && b instanceof PriorityTask<?> pb) {
                int cmp = Integer.compare(pa.priority.order, pb.priority.order);
                if (cmp != 0) return cmp;
                return Long.compare(pa.seq, pb.seq);
            }
            return 0;
        });

        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                queue,
                Thread.ofVirtual().name("exile-worker-", 0).factory());

        if (meter != null) {
            this.durationHistogram = meter.histogramBuilder("exile.worker.duration")
                    .setDescription("Time to execute a worker task")
                    .setUnit("s")
                    .build();
            this.queueWaitHistogram = meter.histogramBuilder("exile.worker.queue_wait")
                    .setDescription("Time a task waited in the priority queue")
                    .setUnit("s")
                    .build();
            this.completedCounter = meter.counterBuilder("exile.worker.completed")
                    .setDescription("Total worker tasks completed")
                    .build();

            meter.gaugeBuilder("exile.worker.active")
                    .setDescription("Currently executing worker tasks")
                    .ofLongs()
                    .buildWithCallback(obs -> obs.record(executor.getActiveCount()));
            meter.gaugeBuilder("exile.worker.queue_depth")
                    .setDescription("Tasks waiting in the priority queue")
                    .ofLongs()
                    .buildWithCallback(obs -> obs.record(executor.getQueue().size()));
        } else {
            this.durationHistogram = null;
            this.queueWaitHistogram = null;
            this.completedCounter = null;
        }

        log.info("PriorityExecutor started with {} threads, maxLowDepth={}", poolSize, maxLowDepth);
    }

    public PriorityExecutor(int poolSize) {
        this(poolSize, 100, null);
    }

    /**
     * Submit a callable tagged with a task type. Blocks the caller until the result is available.
     * For LOW priority, the caller blocks on a semaphore until there is room in the pipeline.
     */
    public <T> T submit(Priority priority, String type, Callable<T> task) throws Exception {
        String resolvedType = (type == null || type.isBlank()) ? TYPE_UNSPECIFIED : type;
        var typeStats = byType.computeIfAbsent(typeKey(priority, resolvedType), k -> new TypeStats());
        typeStats.submitted.incrementAndGet();

        if (priority == Priority.HIGH) highSubmitted.incrementAndGet();
        else {
            lowSubmitted.incrementAndGet();
            lowAdmission.acquire();
        }

        long queuedAt = System.nanoTime();
        var future = new PriorityTask<>(priority, sequence.getAndIncrement(), () -> {
            long startedAt = System.nanoTime();
            double waitSec = (startedAt - queuedAt) / 1_000_000_000.0;
            recordQueueWait(priority, waitSec);

            boolean success = false;
            try {
                T result = task.call();
                success = true;
                return result;
            } finally {
                long finishedAt = System.nanoTime();
                double durationSec = (finishedAt - startedAt) / 1_000_000_000.0;
                recordCompletion(priority, resolvedType, typeStats, durationSec, success);
                if (priority == Priority.LOW) lowAdmission.release();
            }
        });

        executor.execute(future);
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    /** Submit a void task with the given priority and type. Blocks the caller until complete. */
    public void submit(Priority priority, String type, RunnableWithException task) throws Exception {
        submit(priority, type, () -> {
            task.run();
            return null;
        });
    }

    public <T> T submit(Priority priority, Callable<T> task) throws Exception {
        return submit(priority, TYPE_UNSPECIFIED, task);
    }

    public void submit(Priority priority, RunnableWithException task) throws Exception {
        submit(priority, TYPE_UNSPECIFIED, task);
    }

    private static String typeKey(Priority p, String type) {
        return p.name() + ":" + type;
    }

    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }

    public void shutdown() {
        log.info("PriorityExecutor shutting down (high={}, low={} completed)",
                highCompleted.get(), lowCompleted.get());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Recording ──────────────────────────────────────────────────────

    private void recordQueueWait(Priority priority, double seconds) {
        if (queueWaitHistogram != null) {
            queueWaitHistogram.record(seconds,
                    Attributes.of(ATTR_PRIORITY, priority.name()));
        }
    }

    private void recordCompletion(
            Priority priority, String type, TypeStats typeStats, double durationSec, boolean success) {
        if (durationHistogram != null) {
            durationHistogram.record(durationSec,
                    Attributes.of(
                            ATTR_PRIORITY, priority.name(),
                            ATTR_STATUS, success ? "ok" : "error",
                            ATTR_TYPE, type));
        }
        if (completedCounter != null) {
            completedCounter.add(1, Attributes.of(ATTR_PRIORITY, priority.name(), ATTR_TYPE, type));
        }

        if (priority == Priority.HIGH) {
            highCompleted.incrementAndGet();
            if (!success) highFailed.incrementAndGet();
            highStats.record(durationSec);
        } else {
            lowCompleted.incrementAndGet();
            if (!success) lowFailed.incrementAndGet();
            lowStats.record(durationSec);
        }

        typeStats.completed.incrementAndGet();
        if (!success) typeStats.failed.incrementAndGet();
        typeStats.stats.record(durationSec);

        throughputTracker.record();
        recentTasks.record(priority, durationSec);
    }

    // ── Metrics accessors ──────────────────────────────────────────────

    public int getQueueDepth() {
        return executor.getQueue().size();
    }

    public int lowQueueDepth() {
        return (int) (lowSubmitted.get() - lowCompleted.get());
    }

    public int getActiveThreads() {
        return executor.getActiveCount();
    }

    public int getPoolSize() {
        return poolSize;
    }

    public long getHighCompleted() {
        return highCompleted.get();
    }

    public long getLowCompleted() {
        return lowCompleted.get();
    }

    public long getTotalCompleted() {
        return highCompleted.get() + lowCompleted.get();
    }

    public long getTotalSubmitted() {
        return highSubmitted.get() + lowSubmitted.get();
    }

    public long getTotalFailed() {
        return highFailed.get() + lowFailed.get();
    }

    public long getTotalSucceeded() {
        return getTotalCompleted() - getTotalFailed();
    }

    public double getThroughput() {
        return throughputTracker.eventsPerSecond();
    }

    public long getWaiting() {
        long inFlight = lowSubmitted.get() - lowCompleted.get();
        int inPipeline = getQueueDepth() + getActiveThreads();
        return Math.max(0, inFlight - inPipeline);
    }

    /** Returns dashboard-friendly stats map with per-priority breakdown. */
    public Map<String, Object> getStats() {
        var result = new LinkedHashMap<String, Object>();

        result.put("poolSize", getPoolSize());
        result.put("activeThreads", getActiveThreads());
        result.put("queueDepth", getQueueDepth());
        result.put("lowPipelineDepth", lowQueueDepth());

        // Per-priority queue depth: iterate the shared PriorityBlockingQueue once.
        int highQ = 0, lowQ = 0;
        for (Runnable r : executor.getQueue()) {
            if (r instanceof PriorityTask<?> pt) {
                if (pt.priority == Priority.HIGH) highQ++;
                else lowQ++;
            }
        }
        result.put("highQueueDepth", highQ);
        result.put("lowQueueDepth", lowQ);
        result.put("totalSubmitted", getTotalSubmitted());
        result.put("totalCompleted", getTotalCompleted());
        result.put("totalFailed", getTotalFailed());
        result.put("totalSucceeded", getTotalSucceeded());
        result.put("waiting", getWaiting());
        result.put("throughput", Math.round(getThroughput() * 100.0) / 100.0);

        result.put("high", buildPriorityStats("HIGH", highSubmitted.get(), highCompleted.get(), highFailed.get(), highStats));
        result.put("low", buildPriorityStats("LOW", lowSubmitted.get(), lowCompleted.get(), lowFailed.get(), lowStats));

        var typeRows = new ArrayList<Map<String, Object>>(byType.size());
        byType.forEach((key, ts) -> {
            int colon = key.indexOf(':');
            var prio = colon > 0 ? key.substring(0, colon) : "UNKNOWN";
            var type = colon > 0 ? key.substring(colon + 1) : key;
            typeRows.add(buildTypeStats(prio, type, ts));
        });
        typeRows.sort((a, b) -> {
            int pcmp = ((String) a.get("priority")).compareTo((String) b.get("priority"));
            if (pcmp != 0) return pcmp;
            long aSub = ((Number) a.get("submitted")).longValue();
            long bSub = ((Number) b.get("submitted")).longValue();
            int scmp = Long.compare(bSub, aSub);
            if (scmp != 0) return scmp;
            return ((String) a.get("type")).compareTo((String) b.get("type"));
        });
        result.put("byType", typeRows);

        result.put("recentTasks", recentTasks.getEntries());

        return result;
    }

    private Map<String, Object> buildTypeStats(String priority, String type, TypeStats ts) {
        var m = new LinkedHashMap<String, Object>();
        m.put("priority", priority);
        m.put("priorityClass", "HIGH".equals(priority) ? "pill-warn" : "pill-muted");
        m.put("type", type);
        long submitted = ts.submitted.get();
        long completed = ts.completed.get();
        long failed = ts.failed.get();
        m.put("submitted", submitted);
        m.put("completed", completed);
        m.put("failed", failed);
        m.put("succeeded", completed - failed);
        var snap = ts.stats.snapshot();
        m.put("count", snap.count);
        m.put("avgMs", snap.count > 0 ? Math.round(snap.avg * 1000) : 0);
        m.put("p50Ms", Math.round(snap.p50 * 1000));
        m.put("p95Ms", Math.round(snap.p95 * 1000));
        m.put("p99Ms", Math.round(snap.p99 * 1000));
        return m;
    }

    static final class TypeStats {
        final AtomicLong submitted = new AtomicLong();
        final AtomicLong completed = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final RollingStats stats = new RollingStats(500);
    }

    private Map<String, Object> buildPriorityStats(String label, long submitted, long completed, long failed, RollingStats stats) {
        var m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("submitted", submitted);
        m.put("completed", completed);
        m.put("failed", failed);
        m.put("succeeded", completed - failed);
        var snap = stats.snapshot();
        m.put("count", snap.count);
        m.put("avgMs", snap.count > 0 ? Math.round(snap.avg * 1000) : 0);
        m.put("p50Ms", Math.round(snap.p50 * 1000));
        m.put("p95Ms", Math.round(snap.p95 * 1000));
        m.put("p99Ms", Math.round(snap.p99 * 1000));
        m.put("minMs", Math.round(snap.min * 1000));
        m.put("maxMs", Math.round(snap.max * 1000));
        return m;
    }

    // ── Rolling stats (ring buffer) ────────────────────────────────────

    static class RollingStats {
        private final double[] buffer;
        private int pos = 0;
        private int count = 0;

        RollingStats(int capacity) {
            this.buffer = new double[capacity];
        }

        synchronized void record(double value) {
            buffer[pos] = value;
            pos = (pos + 1) % buffer.length;
            if (count < buffer.length) count++;
        }

        synchronized Snapshot snapshot() {
            if (count == 0) return Snapshot.EMPTY;
            double[] copy = new double[count];
            System.arraycopy(buffer, 0, copy, 0, count);
            Arrays.sort(copy);

            double sum = 0;
            for (double v : copy) sum += v;

            return new Snapshot(
                    count,
                    sum / count,
                    copy[percentileIndex(count, 50)],
                    copy[percentileIndex(count, 95)],
                    copy[percentileIndex(count, 99)],
                    copy[0],
                    copy[count - 1]);
        }

        private static int percentileIndex(int count, int percentile) {
            return Math.min(count - 1, (int) Math.ceil(count * percentile / 100.0) - 1);
        }

        record Snapshot(int count, double avg, double p50, double p95, double p99, double min, double max) {
            static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0);
        }
    }

    // ── Recent tasks (for dashboard chart) ─────────────────────────────

    static class RecentTasks {
        private final int capacity;
        private final LinkedList<Map<String, Object>> entries = new LinkedList<>();

        RecentTasks(int capacity) {
            this.capacity = capacity;
        }

        synchronized void record(Priority priority, double durationSec) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("priority", priority.name());
            entry.put("durationMs", Math.round(durationSec * 1000));
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("color", priority == Priority.HIGH ? "#3b82f6" : "#a855f7");
            entry.put("labelColor", priority == Priority.HIGH ? "#2563eb" : "#9333ea");
            entries.addLast(entry);
            if (entries.size() > capacity) entries.removeFirst();
        }

        synchronized List<Map<String, Object>> getEntries() {
            return new ArrayList<>(entries);
        }
    }

    // ── Throughput tracker (sliding time window) ───────────────────────

    static class ThroughputTracker {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        private final long windowNanos;

        ThroughputTracker(Duration window) {
            this.windowNanos = window.toNanos();
        }

        synchronized void record() {
            long now = System.nanoTime();
            timestamps.addLast(now);
            evictExpired(now);
        }

        synchronized double eventsPerSecond() {
            long now = System.nanoTime();
            evictExpired(now);
            if (timestamps.isEmpty()) return 0.0;
            return timestamps.size() * 1_000_000_000.0 / windowNanos;
        }

        private void evictExpired(long now) {
            long cutoff = now - windowNanos;
            Long head;
            while ((head = timestamps.peekFirst()) != null && head < cutoff) {
                timestamps.pollFirst();
            }
        }
    }

    // ── Internal priority task ─────────────────────────────────────────

    private static class PriorityTask<T> extends FutureTask<T> implements Comparable<PriorityTask<T>> {
        final Priority priority;
        final long seq;

        PriorityTask(Priority priority, long seq, Callable<T> callable) {
            super(callable);
            this.priority = priority;
            this.seq = seq;
        }

        @Override
        public int compareTo(PriorityTask<T> other) {
            int cmp = Integer.compare(this.priority.order, other.priority.order);
            if (cmp != 0) return cmp;
            return Long.compare(this.seq, other.seq);
        }
    }
}
