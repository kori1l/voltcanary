package com.voltcanary.aiagent;

import com.voltcanary.aiagent.trace.AssistantBoundary;
import com.voltcanary.aiagent.trace.AssistantBoundaryObservationAspect;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantBoundaryObservationTest {
    @Test
    void nestedProxyDispatchesOnceAndAnExporterFailurePreservesTheResult() {
        var registry = ObservationRegistry.create();
        var observed = new AtomicInteger();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStop(Observation.Context context) {
                observed.incrementAndGet();
                throw new IllegalStateException("export unavailable");
            }
        });
        var effects = new AtomicInteger();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Proxies.class, AssistantBoundaryObservationAspect.class);
            context.registerBean(ObservationRegistry.class, () -> registry);
            context.registerBean(Execution.class, () -> new Execution(effects));
            context.registerBean(Entry.class, () -> new Entry(context.getBean(Execution.class)));
            context.refresh();

            assertEquals(1, context.getBean(Entry.class).execute());
            assertEquals(1, effects.get());
            assertEquals(1, observed.get());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Proxies { }

    static class Entry {
        private final Execution execution;
        Entry(Execution execution) { this.execution = execution; }
        @AssistantBoundary(operation = AssistantBoundary.Operation.EXECUTE)
        public int execute() { return execution.execute(); }
    }

    static class Execution {
        private final AtomicInteger effects;
        Execution(AtomicInteger effects) { this.effects = effects; }
        @AssistantBoundary(operation = AssistantBoundary.Operation.EXECUTE)
        public int execute() { return effects.incrementAndGet(); }
    }
}
