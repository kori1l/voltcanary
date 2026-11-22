package com.voltcanary.common.security;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BoundedWindowRateLimiterTest {
    @Test
    void cardinalityPressureCannotResetActiveCounters() {
        var limiter = new BoundedWindowRateLimiter(2);
        assertTrue(limiter.allow("a", 100, 1));
        assertTrue(limiter.allow("b", 100, 1));
        assertFalse(limiter.allow("c", 100, 1));
        assertFalse(limiter.allow("a", 100, 1));
        assertTrue(limiter.allow("c", 200, 1));
    }

    @Test
    void concurrentRequestsShareOneAllowance() {
        var limiter = new BoundedWindowRateLimiter(2);
        long accepted = IntStream.range(0, 1000).parallel().filter(index -> limiter.allow("one", 100, 10)).count();
        assertEquals(10, accepted);
    }
}
