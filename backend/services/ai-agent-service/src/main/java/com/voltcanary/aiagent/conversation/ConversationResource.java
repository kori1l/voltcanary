package com.voltcanary.aiagent.conversation;

import java.util.UUID;

/** A successful action receipt is provenance, never a grant of current access. */
public record ConversationResource(
    UUID deviceId,
    String sourceId,
    String name,
    UUID reviewId,
    UUID actionId,
    long messageRevision
) {}
