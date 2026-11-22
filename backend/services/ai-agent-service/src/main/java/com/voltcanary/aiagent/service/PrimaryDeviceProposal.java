package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves the current catalogue choices into the ordinary device-create payload. */
record PrimaryDeviceProposal(
    Map<String, Object> parameters,
    List<AuthorizedPlanningContext.ResourceRef> catalogue
) {
    Map<String, Object> reviewPayload(String selectedLocationId) {
        if (
            !(parameters.get("name") instanceof String name) ||
            name.isBlank() ||
            name.length() > 255
        ) {
            throw IntentPlans.reject("DEVICE_NAME_INVALID");
        }
        if (
            catalogue
                .stream()
                .noneMatch(
                    ref ->
                        ref.kind().equals("type") && ref.resourceId().equals(parameters.get("type"))
                )
        ) {
            throw IntentPlans.reject("DEVICE_TYPE_UNAVAILABLE");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("type", parameters.get("type"));
        for (String key : List.of("manufacturer", "model")) {
            Object value = parameters.get(key);
            if (value == null) continue;
            if (!(value instanceof String text) || text.length() > 255) {
                throw IntentPlans.reject("DEVICE_OPTIONAL_TEXT_INVALID");
            }
            result.put(key, text);
        }
        Object locationChoice = parameters.get("locationId");
        if (locationChoice instanceof String location) {
            String id = catalogue
                .stream()
                .filter(ref -> ref.kind().equals("location"))
                .filter(ref -> ref.ref().equals(location) || ref.resourceId().equals(location))
                .map(AuthorizedPlanningContext.ResourceRef::resourceId)
                .findFirst()
                .orElseThrow(() -> IntentPlans.reject("DEVICE_LOCATION_UNAVAILABLE"));
            result.put("locationId", id);
        } else if (locationChoice != null) {
            throw IntentPlans.reject("DEVICE_LOCATION_UNAVAILABLE");
        }
        if (selectedLocationId != null) {
            Object locationId = result.putIfAbsent("locationId", selectedLocationId);
            if (locationId != null && !selectedLocationId.equals(locationId)) {
                throw IntentPlans.reject("DEVICE_LOCATION_BINDING_CHANGED");
            }
        }
        return Map.copyOf(result);
    }
}
