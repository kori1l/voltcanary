package com.voltcanary.alert.configurable.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import java.util.UUID;

public interface GrantScopeAuthority {
    JsonNode validate(String authorization, UUID actorId, UUID accountId, AutomationGrantScope scope);
}
