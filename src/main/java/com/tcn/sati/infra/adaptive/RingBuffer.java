package com.tcn.sati.infra.adaptive;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-capacity bounded ring buffer of {@code long} samples with percentile computation.
 *
 * <p>Thread-safe for concurrent {@link #add(long)} from N writers and concurrent reads
 * from the controller recompute path. Writes are O(1); reads snapshot and sort — O(n log n).
 * n is bounded by capacity so this is cheap at typical sizes (≤ 100 samples).
 */
final class RingBuffer {
    private final long[] data;
    private int idx;
    private int size;
    private final ReentrantLock lock = new ReentrantLock();

    RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.data = new long[capacity];
    }

    /** Record a sample. O(1), lock-protected. */
    void add(long value) {
        lock.lock();
        try {
            data[idx] = value;
            idx = (idx + 1) % data.length;
            if (size < data.length) {
                size++;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Nearest-rank percentile in the range (0, 1]. Returns 0 when the buffer is empty.
     *
     * <p>p=0.5 → median, p=0.95 → 95th, p=0.99 → 99th. Values outside (0, 1] are clamped.
     */
    long percentile(double p) {
        if (p <= 0) p = Double.MIN_VALUE;
        if (p > 1) p = 1;

        long[] copy;
        int n;
        lock.lock();
        try {
            n = size;
            if (n == 0) {
                return 0;
            }
            copy = Arrays.copyOf(data, n);
        } finally {
            lock.unlock();
        }
        Arrays.sort(copy);
        int rank = (int) Math.ceil(p * n) - 1;
        if (rank < 0) rank = 0;
        if (rank > n - 1) rank = n - 1;
        return copy[rank];
    }

    int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    int capacity() {
        return data.length;
    }
}
