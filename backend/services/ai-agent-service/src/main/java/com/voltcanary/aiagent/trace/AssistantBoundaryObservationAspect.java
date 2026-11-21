package com.voltcanary.aiagent.trace;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Optional timing around application entry points. Durable trace and effects stay with their owners. */
@Aspect
@Component
@RequiredArgsConstructor
public class AssistantBoundaryObservationAspect {
    private static final String NAME = "voltcanary.assistant.boundary";
    private final ObservationRegistry observations;

    @Around(value = "@annotation(boundary)", argNames = "call,boundary")
    public Object observe(ProceedingJoinPoint call, AssistantBoundary boundary) throws Throwable {
        Observation current = observations.getCurrentObservation();
        if (current != null && NAME.equals(current.getContext().getName())) return call.proceed();

        Observation observation = Observation.NOOP;
        Observation.Scope scope = Observation.Scope.NOOP;
        try {
            observation = Observation.createNotStarted(NAME, observations)
                    .lowCardinalityKeyValue("operation", boundary.operation().name());
            observation.start();
            scope = observation.openScope();
        } catch (RuntimeException ignored) {
            // Optional observation failures do not own dispatch.
        }
        try {
            return call.proceed();
        } catch (Throwable failure) {
            try {
                observation.lowCardinalityKeyValue("outcome", "error");
            } catch (RuntimeException ignored) {
                // Preserve the original application exception.
            }
            throw failure;
        } finally {
            try {
                scope.close();
            } catch (RuntimeException ignored) {
                // Preserve the application result.
            }
            try {
                observation.stop();
            } catch (RuntimeException ignored) {
                // Preserve the application result.
            }
        }
    }
}
