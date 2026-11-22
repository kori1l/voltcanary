package com.voltcanary.aiagent.port;

import java.util.UUID;

public interface PrimaryReviewBindings {
    void bind(UUID review, UUID plan, String step);
}
