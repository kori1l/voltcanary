package com.voltcanary.aiagent.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Supported relative periods are normalized from explicit words, never calculated by the model. */
public final class CreationPeriod {

    private CreationPeriod() {}

    public static String explicitOrProposed(
        String message,
        String proposed,
        java.util.List<?> answers
    ) {
        for (int index = answers.size() - 1; index >= 0; index--) if (
            answers.get(index) instanceof String answer
        ) {
            String selected = explicitOrProposed(answer, null);
            if (selected != null) return selected;
        }
        return explicitOrProposed(message, proposed);
    }

    public static String explicitOrProposed(String message, String proposed) {
        Map<String, String> phrases = new LinkedHashMap<>();
        phrases.put(
            "LAST_24_HOURS",
            "последн[\\p{L}]*\\s+(24\\s*час[\\p{L}]*|сут[\\p{L}]*)|last\\s+24\\s+hours"
        );
        phrases.put(
            "LAST_6_HOURS",
            "последн[\\p{L}]*\\s+(6|шесть)\\s*час[\\p{L}]*|last\\s+6\\s+hours"
        );
        phrases.put("LAST_1_HOUR", "последний\\s+час|last\\s+hour");
        phrases.put(
            "LAST_7_DAYS",
            "последн[\\p{L}]*\\s+(7\\s*дн[\\p{L}]*|недел[\\p{L}]*)|last\\s+(7\\s+days|week)"
        );
        phrases.put("LAST_30_DAYS", "последн[\\p{L}]*\\s+30\\s*дн[\\p{L}]*|last\\s+30\\s+days");
        var selected = phrases
            .entrySet()
            .stream()
            .filter(entry ->
                Pattern.compile("(?iuU)" + entry.getValue())
                    .matcher(message)
                    .find()
            )
            .map(Map.Entry::getKey)
            .toList();
        if (selected.size() > 1) throw new IllegalArgumentException("Choose one relative period");
        return selected.isEmpty() ? proposed : selected.getFirst();
    }
}
