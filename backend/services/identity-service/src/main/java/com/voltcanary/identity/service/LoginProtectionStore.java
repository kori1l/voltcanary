package com.voltcanary.identity.service;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** An atomic, shared login-limit decision that survives a rejected login transaction. */
public interface LoginProtectionStore {
    <T> T update(Function<Entries, T> operation);

    interface Entries {
        Instant now();

        State get(String key);

        void put(String key, State state);

        void remove(String key);
    }

    record State(List<Instant> attempts, int failures, Instant lockedUntil, Instant expiresAt) {
        public State {
            attempts = List.copyOf(attempts);
        }
    }
}
