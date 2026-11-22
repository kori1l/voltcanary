package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Closed commit variants keep unrelated fences and acknowledgements out of each other's calls. */
sealed interface KeyedCommit {
    record Isolated() implements KeyedCommit {}

    record JournalWindow(
        Optional<JdbcRuleDeployments.Deployment> target,
        Instant start,
        Optional<JdbcJournalWatermarks.Candidate> progress
    ) implements KeyedCommit {
        public JournalWindow {
            Objects.requireNonNull(target);
            Objects.requireNonNull(start);
            Objects.requireNonNull(progress);
        }
    }

    record Session(JdbcRuleDeployments.Deployment target, Instant start) implements KeyedCommit {
        public Session {
            Objects.requireNonNull(target);
            Objects.requireNonNull(start);
        }
    }

    record ReviewedInput(
        JdbcRuleDeployments.Deployment target,
        Instant start
    ) implements KeyedCommit {
        public ReviewedInput {
            Objects.requireNonNull(target);
            Objects.requireNonNull(start);
        }
    }

    record SustainedFinalization(
        JdbcRuleDeployments.Deployment target,
        Instant start,
        Acknowledgement measure
    ) implements KeyedCommit {
        public SustainedFinalization {
            Objects.requireNonNull(target);
            Objects.requireNonNull(start);
            Objects.requireNonNull(measure);
        }
    }

    record CoveredAggregate(
        JdbcRuleDeployments.Deployment target,
        Optional<Instant> start,
        JdbcJournalWatermarks.Candidate progress
    ) implements KeyedCommit {
        public CoveredAggregate {
            Objects.requireNonNull(target);
            Objects.requireNonNull(start);
            Objects.requireNonNull(progress);
        }
    }

    default Optional<JdbcRuleDeployments.Deployment> deployment() {
        return switch (this) {
            case Isolated ignored -> Optional.empty();
            case JournalWindow value -> value.target();
            case Session value -> Optional.of(value.target());
            case ReviewedInput value -> Optional.of(value.target());
            case SustainedFinalization value -> Optional.of(value.target());
            case CoveredAggregate value -> Optional.of(value.target());
        };
    }

    default Optional<Instant> opening() {
        return switch (this) {
            case Isolated ignored -> Optional.empty();
            case JournalWindow value -> Optional.of(value.start());
            case Session value -> Optional.of(value.start());
            case ReviewedInput value -> Optional.of(value.start());
            case SustainedFinalization value -> Optional.of(value.start());
            case CoveredAggregate value -> value.start();
        };
    }

    default Optional<JdbcJournalWatermarks.Candidate> watermark() {
        return switch (this) {
            case JournalWindow value -> value.progress();
            case CoveredAggregate value -> Optional.of(value.progress());
            default -> Optional.empty();
        };
    }

    default Optional<Acknowledgement> acknowledgement() {
        return this instanceof SustainedFinalization value
            ? Optional.of(value.measure())
            : Optional.empty();
    }

    default boolean requiresSessionFence() {
        return (
            this instanceof Session ||
            this instanceof ReviewedInput ||
            this instanceof SustainedFinalization ||
            this instanceof CoveredAggregate
        );
    }
}
