package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.domain.DashboardTaskState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded compatibility adapter; stored selections are preferences, never authorization. */
final class DashboardTaskContext {
    private DashboardTaskContext() { }

    static DashboardTaskState.Selection selection(Map<String, Object> input) {
        Object supplied = input.get("deviceIds");
        List<?> values = supplied instanceof List<?> list ? list
                : input.get("deviceId") != null ? List.of(input.get("deviceId")) : List.of();
        if (values.size() > 100) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Narrow the device selection");
        try {
            var sources = new LinkedHashMap<UUID, String>();
            if (input.get("sourceSelections") instanceof Map<?, ?> selected) selected.forEach((key, value) -> {
                if (!(value instanceof String source)) throw new IllegalArgumentException("Source IDs must be strings");
                sources.put(UUID.fromString(String.valueOf(key)), source);
            });
            DashboardTaskState.SelectionOrigin origin = null;
            if (input.get("conversationSelection") instanceof Map<?, ?> provenance) {
                origin = new DashboardTaskState.SelectionOrigin(UUID.fromString(String.valueOf(provenance.get("conversationId"))),
                        UUID.fromString(String.valueOf(provenance.get("reviewId"))), UUID.fromString(String.valueOf(provenance.get("actionId"))));
            }
            return new DashboardTaskState.Selection(values.stream().map(value -> UUID.fromString(value.toString())).distinct().toList(),
                    text(input, "sourceId"), text(input, "metric"), text(input, "from"), text(input, "to"), sources, origin);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device selection");
        }
    }

    static Map<String, Object> input(DashboardTaskState state) {
        var result = new LinkedHashMap<String, Object>();
        result.put("taskKind", "DASHBOARD");
        if (state.selection().origin() != null) {
            var origin = state.selection().origin();
            result.put("conversationSelection", Map.of("origin", "CONFIRMED_EXECUTION", "conversationId", origin.conversationId().toString(),
                    "reviewId", origin.reviewId().toString(), "actionId", origin.actionId().toString()));
        }
        result.put("originalDashboardRequest", state.originalRequest());
        if (state.name() != null) result.put("name", state.name().value());
        if (!state.selection().deviceIds().isEmpty()) result.put("deviceIds", state.selection().deviceIds().stream().map(UUID::toString).toList());
        put(result, "sourceId", state.selection().sourceId());
        if (!state.selection().sourcesByDevice().isEmpty()) {
            var sources = new LinkedHashMap<String, String>();
            state.selection().sourcesByDevice().forEach((device, source) -> sources.put(device.toString(), source));
            result.put("sourceSelections", Map.copyOf(sources));
        }
        put(result, "metric", state.selection().metric());
        put(result, "from", state.selection().from());
        put(result, "to", state.selection().to());
        String pending = state.pending().stream().filter(change -> change.disposition() != DashboardTaskState.Disposition.APPLIED
                && change.disposition() != DashboardTaskState.Disposition.SUPERSEDED).map(DashboardTaskState.PendingChange::request)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (pending.length() > 6000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Split pending changes into a new task");
        if (!pending.isBlank()) result.put("pendingDashboardChange", pending);
        result.put("pendingDashboardChangeIds", state.pending().stream().filter(change -> change.disposition() != DashboardTaskState.Disposition.APPLIED
                && change.disposition() != DashboardTaskState.Disposition.SUPERSEDED).map(change -> change.changeId().toString()).toList());
        return Map.copyOf(result);
    }

    static String explicitName(String rawMessage, Map<String, Object> input) {
        String command = DashboardTaskName.explicit(rawMessage, Map.of());
        if (command != null) return command;
        // Direct, structured user input is already validated by PlanningInputGuard.
        return input.get("name") instanceof String name && !name.isBlank() && name.length() <= 80 ? name : null;
    }

    static boolean grounded(String candidate, String rawMessage) {
        return candidate != null && !candidate.isBlank() && candidate.length() <= 80
                && (candidate.equals(rawMessage.strip()) || candidate.equals(DashboardTaskName.explicit(rawMessage, Map.of()))
                || rawMessage.contains("«" + candidate + "»") || rawMessage.contains("\"" + candidate + "\""));
    }

    static String confirmedLegacyName(Map<String, Object> input, String original) {
        if (!(input.get("name") instanceof String name)) return null;
        if (grounded(name, original)) return name;
        if (input.get("clarificationAnswers") instanceof List<?> answers
                && answers.stream().anyMatch(answer -> answer instanceof String text && grounded(name, text))) return name;
        return null;
    }

    private static String text(Map<String, Object> input, String key) {
        return input.get(key) instanceof String value ? value : null;
    }

    private static void put(Map<String, Object> values, String key, String value) {
        if (value != null) values.put(key, value);
    }
}
