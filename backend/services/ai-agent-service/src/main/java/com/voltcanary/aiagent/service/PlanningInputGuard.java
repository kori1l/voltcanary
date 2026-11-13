package com.voltcanary.aiagent.service;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Applies before any provider call or persistence, including for legacy requests. */
@Component
public class PlanningInputGuard {
    private record Limits(int depth, int items, int characters) { }
    private static final Limits USER_INPUT = new Limits(8, 2048, 32768);
    private static final Limits WIDGET_CATALOGUE = new Limits(12, 16384, 220000);
    private static final Pattern SECRET_KEY = Pattern.compile("password|secret|api.?key|authorization|credential|token$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)bearer\\s+\\S+|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|\\bsk-[A-Za-z0-9_-]{16,}|\\bAIza[A-Za-z0-9_-]{30,}|\\bAQ\\.[A-Za-z0-9_-]{30,}");

    public void validate(String message, Object context) {
        if (message == null || message.isBlank() || message.length() > 8000) {
            reject("Message must contain 1–8000 characters");
        }
        inspect(message, 0, new int[]{0, 0}, USER_INPUT);
        inspect(context, 0, new int[]{0, 0}, USER_INPUT);
    }

    public void inspect(Object value) {
        inspect(value, 0, new int[]{0, 0}, USER_INPUT);
    }

    /** Only the dashboard owner's fetched widget descriptors, never client-supplied context. */
    public void inspectWidgetCatalogue(Object value) {
        if (!(value instanceof Collection<?> widgets) || widgets.size() > 64) reject("Widget catalogue exceeds type limit");
        inspect(value, 0, new int[]{0, 0}, WIDGET_CATALOGUE);
    }

    private void inspect(Object value, int depth, int[] budget, Limits limits) {
        if (depth > limits.depth() || ++budget[0] > limits.items()) {
            reject("Assistant input exceeds depth or item limit");
        }
        if (value == null || value instanceof Boolean) {
            return;
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) reject("Assistant numbers must be finite");
        } else if (value instanceof String text) {
            budget[1] += text.length();
            if (text.length() > 8000 || budget[1] > limits.characters() || SECRET_VALUE.matcher(text).find()) reject("Remove credentials or oversized text from assistant input");
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.length() > 80 || SECRET_KEY.matcher(key).find()) {
                    reject("Remove credential fields from assistant input");
                }
                inspect(entry.getValue(), depth + 1, budget, limits);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object child : collection) inspect(child, depth + 1, budget, limits);
        } else {
            reject("Unsupported assistant input type");
        }
    }

    private void reject(String reason) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
