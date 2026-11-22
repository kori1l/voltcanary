package com.voltcanary.common.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Does not evict active counters: cardinality pressure must not reset a client's allowance. */
public final class BoundedWindowRateLimiter {
    private record Counter(long start, AtomicInteger count) { }
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final int capacity;

    public BoundedWindowRateLimiter(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Rate limiter capacity must be positive");
        this.capacity = capacity;
    }

    public boolean allow(String key, long windowStart, int limit) {
        if (!counters.containsKey(key)) {
            synchronized (counters) {
                if (!counters.containsKey(key)) {
                    if (counters.size() >= capacity) counters.entrySet().removeIf(entry -> entry.getValue().start() < windowStart);
                    if (counters.size() >= capacity) return false;
                    counters.put(key, new Counter(windowStart, new AtomicInteger()));
                }
            }
        }
        Counter counter = counters.computeIfPresent(key, (ignored, existing) -> existing.start() < windowStart
                ? new Counter(windowStart, new AtomicInteger()) : existing);
        if (counter == null) return false;
        int count = counter.count().updateAndGet(value -> value == Integer.MAX_VALUE ? value : value + 1);
        return count <= limit;
    }
}
