package com.voltcanary.aiagent.orchestration.routing;

import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DispatchPermitTest {
    @Test
    void boundBodyAndConcurrentConsumption() throws Exception {
        var permit = new DispatchPermit(SkillRegistry.digest("reserved"), 5_000);
        assertThrows(ModelRouting.Stop.class, () -> permit.verifyBody("changed"));
        permit.verifyBody("reserved");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> dispatch = () -> {
                if (!start.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timed out");
                try {
                    permit.consume();
                    return "consumed";
                } catch (ModelRouting.Stop rejected) {
                    return rejected.getMessage();
                }
            };
            var first = workers.submit(dispatch);
            var second = workers.submit(dispatch);
            start.countDown();
            var a = first.get(2, TimeUnit.SECONDS);
            var b = second.get(2, TimeUnit.SECONDS);
            assertNotEquals(a, b);
            assertEquals(java.util.Set.of("consumed", "DISPATCH_PERMIT_CONSUMED"), java.util.Set.of(a, b));
        }
    }
}
