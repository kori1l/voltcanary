package com.voltcanary.common.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VclWorkerAssertionTest {

    private static final byte[] KEY = new byte[32];
    private static final Instant NOW = Instant.parse("2026-11-06T15:00:00Z");

    private static VclWorkerAssertion signer(String audience, Instant now) {
        return new VclWorkerAssertion(KEY, "worker-1", audience, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static VclWorkerAssertion.Scope scope() {
        return new VclWorkerAssertion.Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SOURCE_CAPTURE",
            "a".repeat(64)
        );
    }

    @Test
    void exactScopedReadRoundTripsAndCannotBecomeUserOrOtherTargetAuthority() {
        var scope = scope();
        var signed = signer("device-service", NOW).sign(scope, 20);
        assertEquals(
            scope,
            signer("device-service", NOW.plusSeconds(5)).verify(
                signed,
                scope.operation(),
                scope.target(),
                scope.requestDigest()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            signer("alert-service", NOW).verify(
                signed,
                scope.operation(),
                scope.target(),
                scope.requestDigest()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            signer("device-service", NOW).verify(
                signed,
                "SOURCE_COVERAGE",
                scope.target(),
                scope.requestDigest()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            signer("device-service", NOW).verify(
                signed,
                scope.operation(),
                UUID.randomUUID(),
                scope.requestDigest()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            signer("device-service", NOW).verify(
                signed,
                scope.operation(),
                scope.target(),
                "b".repeat(64)
            )
        );
    }

    @Test
    void expiryFutureIssueTamperAndMissingKeyFailClosed() {
        var scope = scope();
        var token = signer("device-service", NOW).sign(scope, 10);
        assertThrows(IllegalArgumentException.class, () ->
            signer("device-service", NOW.plusSeconds(10)).verify(
                token,
                scope.operation(),
                scope.target(),
                scope.requestDigest()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            signer("device-service", NOW.minusSeconds(1)).verify(
                token,
                scope.operation(),
                scope.target(),
                scope.requestDigest()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            signer("device-service", NOW).verify(
                token + "x",
                scope.operation(),
                scope.target(),
                scope.requestDigest()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new VclWorkerAssertion(
                new byte[0],
                "worker-1",
                "device-service",
                Clock.fixed(NOW, ZoneOffset.UTC)
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            signer("device-service", NOW).sign(scope, 31)
        );
    }
}
