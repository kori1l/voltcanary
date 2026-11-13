package com.voltcanary.alert.configurable.port;

import java.util.UUID;

/** Resolves current authority for a verified actor; no cached local account row is authoritative. */
public interface RuleAccountAuthority {
    UUID account(UUID actorId);
}
