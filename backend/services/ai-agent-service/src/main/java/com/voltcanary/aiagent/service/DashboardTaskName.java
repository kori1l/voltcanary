package com.voltcanary.aiagent.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Explicit naming commands outrank suggestions, even before a dashboard can be prepared. */
final class DashboardTaskName {

    private static final Pattern COMMAND = Pattern.compile(
        "(?iu)^\\s*(?:назови|название|name(?: it)?|call it)\\s*[:=]?\\s+(.{1,82})\\s*$"
    );
    private static final Pattern QUOTED_DASHBOARD = Pattern.compile(
        "(?iu)(?:дашборд|dashboard)\\s*[«\"]([^»\"]{1,80})[»\"]"
    );

    private DashboardTaskName() {}

    static String latest(String message, Map<String, Object> context) {
        if (
            context.get("clarificationAnswers") instanceof List<?> answers &&
            !answers.isEmpty() &&
            answers.getLast() instanceof String answer
        ) return answer;
        return message;
    }

    static String explicit(String message, Map<String, Object> context) {
        if (context.containsKey("previousDashboardIntent")) return null;
        var quoted = QUOTED_DASHBOARD.matcher(latest(message, context));
        if (quoted.find()) return quoted.group(1).trim().isBlank() ? null : quoted.group(1).trim();
        return standaloneCommand(latest(message, context));
    }

    static String standaloneCommand(String message) {
        var match = COMMAND.matcher(message);
        if (!match.matches()) return null;
        String name = match.group(1).trim();
        if (
            (name.startsWith("«") && name.endsWith("»")) ||
            (name.startsWith("\"") && name.endsWith("\""))
        ) {
            name = name.substring(1, name.length() - 1).trim();
        }
        return !name.isBlank() && name.length() <= 80 ? name : null;
    }
}
