package com.voltcanary.aiagent.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public interface PrimaryDeviceReads {
    JsonNode device(UUID deviceId, String authorization);
    JsonNode source(UUID deviceId, UUID sourceId, String authorization);
}
