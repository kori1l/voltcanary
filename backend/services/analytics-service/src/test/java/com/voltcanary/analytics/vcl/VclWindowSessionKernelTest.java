package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.*;

import com.voltcanary.vcl.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VclWindowSessionKernelTest {

    private static final String SCOPE = "a".repeat(64);
    private static final UUID RULE = new UUID(0, 1),
        SUBJECT = new UUID(0, 2),
        OCCURRENCE = new UUID(0, 3),
        START = new UUID(0, 4),
        CLOSE = new UUID(0, 5);
    private static final VclParser PARSER = new VclParser();
    private static final VclMetadata METADATA = new VclMetadata() {
        public String accessScopeDigest() {
            return SCOPE;
        }

        public Optional<Snapshot> dataset(UUID id, long revision) {
            return Optional.empty();
        }

        public Optional<Snapshot> binding(UUID id, long revision) {
            return Optional.empty();
        }

        public boolean permits(Resource kind, UUID id) {
            return false;
        }
    };
    private static final ScalarValue ASSET = ScalarValue.known(
        PARSER,
        "{\"type\":\"TEXT\",\"value\":\"asset\"}"
    );
    private static final ScalarValue TRIP = ScalarValue.known(
        PARSER,
        "{\"type\":\"TEXT\",\"value\":\"trip\"}"
    );
    private static final KeyedStateStore.Key KEY = new KeyedStateStore.Key(
        new UUID(0, 6),
        RULE,
        new UUID(0, 7),
        VclWindowSessionKernel.identity(ASSET),
        VclWindowSessionKernel.identity(TRIP)
    );
    private final VclWindowSessionKernel kernel = new VclWindowSessionKernel();
    private final VclWindowSessionKernel.Plan plan = plan();

    @Test
    void anObservedStartAndCloseRetainTheActualBoundaryWitnesses() {
        var state = observe(
            VclWindowSessionKernel.empty(plan, KEY),
            1,
            0,
            truth(true),
            truth(false)
        );
        assertEquals(VclWindowSessionKernel.Phase.OPEN, state.phase());
        state = observe(state, 2, 1, truth(false), truth(true));
        assertEquals(VclWindowSessionKernel.Phase.CLOSED, state.phase());
        assertEquals(new UUID(0, 1), state.startWitness());
        assertEquals(new UUID(0, 2), state.closeWitness());
    }

    @Test
    void aLeftCensoredSessionNeedsAnActualStartBeforeItCanClose() {
        var state = observe(
            VclWindowSessionKernel.empty(plan, KEY),
            1,
            0,
            truth(false),
            truth(false)
        );
        assertEquals(VclWindowSessionKernel.Phase.LEFT_CENSORED, state.phase());
        var invalid = observe(state, 2, 1, truth(false), truth(true));
        assertEquals("SESSION_LEFT_CENSORED", invalid.reason());
        state = observe(state, 3, 2, truth(true), truth(false));
        assertEquals(Instant.EPOCH.plusSeconds(2), state.firstAt());
        assertEquals(Instant.EPOCH.plusSeconds(12), state.deadline());
    }

    @Test
    void simultaneousOrUnknownBoundariesCannotInventAClosedSession() {
        var empty = VclWindowSessionKernel.empty(plan, KEY);
        assertEquals(
            "SESSION_AMBIGUOUS_BOUNDARY",
            observe(empty, 1, 0, truth(true), truth(true)).reason()
        );
        assertEquals(
            "SESSION_BOUNDARY_UNKNOWN",
            observe(
                empty,
                2,
                0,
                ScalarValue.unknown("SOURCE_UNKNOWN", List.of()),
                truth(false)
            ).reason()
        );
        var opened = observe(empty, 3, 0, truth(true), truth(false));
        assertEquals(
            "SESSION_AMBIGUOUS_BOUNDARY",
            observe(opened, 4, 0, truth(false), truth(true)).reason()
        );
    }

    @Test
    void lateAndPausedInputsStayUnknownAndOlderEventsRequireReplay() {
        var opened = observe(
            VclWindowSessionKernel.empty(plan, KEY),
            1,
            1,
            truth(true),
            truth(false)
        );
        assertEquals("SESSION_TIMEOUT", observe(opened, 2, 11, truth(false), truth(true)).reason());
        assertEquals("PAUSED", VclWindowSessionKernel.pause(plan, opened, SCOPE).reason());
        assertEquals(
            "SESSION_CORRECTION_REQUIRED",
            assertThrows(VclException.class, () ->
                observe(opened, 3, 0, truth(false), truth(true))
            ).code()
        );
    }

    private VclWindowSessionKernel.State observe(
        VclWindowSessionKernel.State state,
        long id,
        long second,
        ScalarValue start,
        ScalarValue close
    ) {
        return kernel.observe(
            plan,
            state,
            new VclWindowSessionKernel.Observation(
                new UUID(0, id),
                Instant.EPOCH.plusSeconds(second),
                Map.of(SUBJECT, ASSET, OCCURRENCE, TRIP, START, start, CLOSE, close)
            ),
            SCOPE
        );
    }

    private static ScalarValue truth(boolean value) {
        return ScalarValue.known(PARSER, "{\"type\":\"BOOLEAN\",\"value\":" + value + "}");
    }

    private static VclWindowSessionKernel.Plan plan() {
        var scalar = new VclScalarCompiler(PARSER, new VclRegistry(PARSER), VclUnits.standard());
        var fields = Map.of(START, VclType.BOOLEAN, CLOSE, VclType.BOOLEAN);
        var start = scalar.compile(
            "{\"op\":\"FIELD\",\"fieldId\":\"" + START + "\"}",
            fields,
            METADATA
        );
        var close = scalar.compile(
            "{\"op\":\"FIELD\",\"fieldId\":\"" + CLOSE + "\"}",
            fields,
            METADATA
        );
        return new VclWindowSessionKernel.Plan(
            "b".repeat(64),
            SCOPE,
            RULE,
            SUBJECT,
            OCCURRENCE,
            10_000_000,
            new ScalarDecimalPolicy(38, 12, ScalarDecimalPolicy.Rounding.REJECT),
            start,
            close
        );
    }
}
