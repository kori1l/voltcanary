package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VclZoneSessionKernelTest {

    private static final String SCOPE = "a".repeat(64);
    private static final UUID ZONE = new UUID(0, 1);
    private static final UUID RULE = new UUID(0, 2);
    private static final KeyedStateStore.Key KEY = new KeyedStateStore.Key(
        new UUID(0, 3),
        RULE,
        new UUID(0, 4),
        "b".repeat(64),
        "c".repeat(64)
    );
    private static final SpatialFeatureSnapshot.Point INSIDE = new SpatialFeatureSnapshot.Point(
        0,
        0
    );
    private static final SpatialFeatureSnapshot.Point OUTSIDE = new SpatialFeatureSnapshot.Point(
        0.001,
        0
    );
    private static final SpatialFeatureSnapshot.Circle CIRCLE = new SpatialFeatureSnapshot.Circle(
        ZONE,
        1,
        INSIDE,
        new BigDecimal("30")
    );
    private static final SpatialFeatureSnapshot GEOMETRY = new SpatialFeatureSnapshot(
        SCOPE,
        Map.of(ZONE, CIRCLE)
    );
    private static final VclZoneSessionKernel.Plan PLAN = new VclZoneSessionKernel.Plan(
        "d".repeat(64),
        SCOPE,
        RULE,
        CIRCLE,
        BigDecimal.ZERO,
        false,
        30_000_000,
        5_000_000,
        0,
        2
    );

    private final VclZoneSessionKernel kernel = new VclZoneSessionKernel();

    @Test
    void positionlessMessagesPreserveEntryAndExitEvidence() {
        var state = observe(VclZoneSessionKernel.empty(PLAN, KEY), 1, 0, OUTSIDE);
        state = observe(state, 2, 1, null);
        assertEquals(new UUID(0, 1), state.lastId());
        state = observe(state, 3, 1, INSIDE);
        assertEquals(VclZoneSessionKernel.Phase.OPEN, state.phase());
        assertEquals(new UUID(0, 3), state.entryWitness());
        state = observe(state, 4, 2, OUTSIDE);
        state = observe(state, 5, 2, OUTSIDE);
        state = observe(state, 6, 3, null);
        assertEquals(VclZoneSessionKernel.Phase.OPEN, state.phase());
        assertEquals(1, state.outsideCount());
        state = observe(state, 7, 4, OUTSIDE);
        assertEquals(VclZoneSessionKernel.Phase.CLOSED, state.phase());
        assertEquals(new UUID(0, 7), state.exitWitness());
    }

    @Test
    void conflictingPositionsAtOneInstantDoNotCountTowardExit() {
        var state = observe(VclZoneSessionKernel.empty(PLAN, KEY), 1, 0, OUTSIDE);
        state = observe(state, 2, 1, INSIDE);
        state = observe(state, 3, 2, OUTSIDE);
        state = observe(state, 4, 2, INSIDE);
        state = observe(state, 5, 2, OUTSIDE);
        assertEquals(0, state.outsideCount());
        assertEquals(Wgs84ScalarKernel.Region.UNCERTAIN, state.lastRegion());
        state = observe(state, 6, 3, OUTSIDE);
        assertEquals(VclZoneSessionKernel.Phase.OPEN, state.phase());
        state = observe(state, 7, 4, OUTSIDE);
        assertEquals(VclZoneSessionKernel.Phase.CLOSED, state.phase());
    }

    @Test
    void timeoutWithoutAnObservedEntryDoesNotCreateAnExit() {
        var state = observe(VclZoneSessionKernel.empty(PLAN, KEY), 1, 0, OUTSIDE);
        state = observe(state, 2, 1, OUTSIDE);
        state = VclZoneSessionKernel.timeout(PLAN, state, state.deadline(), SCOPE);
        assertEquals(VclZoneSessionKernel.Phase.UNKNOWN, state.phase());
        assertEquals("SESSION_TIMEOUT", state.reason());
        assertNull(state.entryWitness());
        assertNull(state.exitWitness());
    }

    private VclZoneSessionKernel.State observe(
        VclZoneSessionKernel.State state,
        long id,
        long seconds,
        SpatialFeatureSnapshot.Point position
    ) {
        return kernel.observe(
            PLAN,
            state,
            new VclZoneSessionKernel.Observation(
                new UUID(0, id),
                Instant.EPOCH.plusSeconds(seconds),
                KEY,
                ZONE,
                position,
                BigDecimal.ONE
            ),
            GEOMETRY
        );
    }
}
