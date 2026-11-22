package com.voltcanary.aiagent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Device choices are separate from dashboard review memory. */
final class DeviceTaskContext {

    private static final Pattern NAME = Pattern.compile(
        "(?imu)^\\s*(?:название|имя|назови|name(?: it)?|named|call it)\\s*[:=]?\\s+([^\\r\\n]{1,255})$"
    );
    private static final Pattern QUOTED = Pattern.compile(
        "(?iu)(?:устройств\\p{L}*|автобус|device|vehicle)\\s*(?:с\\s+(?:именем|названием)\\s*)?[«\"]([^»\"]{1,255})[»\"]"
    );

    private DeviceTaskContext() {}

    static boolean requested(String message) {
        var request = Pattern.compile(
            "(?iu)(?:созда[йт]|добав[ьи]|create|add)([^.!?\\r\\n]{0,80}?)(устройств\\p{L}*|автобус|холодильник|датчик|device|vehicle|bus|fridge|sensor|дашборд|dashboard|панель|источник|source|правил\\p{L}*|rule|симулятор|simulator|конфигурац\\p{L}*|config)"
        ).matcher(message);
        if (
            !request.find() ||
            request
                .group(2)
                .matches(
                    "(?iu)дашборд|dashboard|панель|источник|source|правил\\p{L}*|rule|симулятор|simulator|конфигурац\\p{L}*|config"
                )
        ) return false;
        if (
            request.group(1).matches("(?isu).*(demo|демо|simulat|симулир).*") ||
            message.matches("(?isu).*(с\\s+симулятором|with\\s+(?:a\\s+)?simulator).*")
        ) return false;
        // In 'create for this bus a dashboard', the bus is a referent, not the object being created.
        boolean referencedDevice = request
            .group(1)
            .matches("(?isu).*(?<!\\p{L})(?:для|for)\\s+[^.!?]*");
        boolean dashboardFollows = message
            .substring(request.end())
            .matches("(?isu)^[^.!?\\r\\n]*(?<!\\p{L})(?:дашборд|dashboard|панель).*?");
        return !(referencedDevice && dashboardFollows);
    }

    static Map<String, Object> prepare(String message, Map<String, Object> input) {
        var values = new LinkedHashMap<>(input);
        if (!input.containsKey("originalDeviceRequest") && !requested(message)) return values;
        values.putIfAbsent("originalDeviceRequest", message);
        String latest = message;
        if (input.get("clarificationAnswers") instanceof List<?> answers && !answers.isEmpty()) {
            latest = String.valueOf(answers.getLast());
        }
        String name = explicitName(latest);
        if (
            name == null &&
            input.get("priorAssistantQuestion") instanceof String question &&
            ("name".equals(input.get("devicePendingField")) ||
                question.matches(
                    "(?isu).*(как назвать устройство|имя.*устройств|название.*устройств|device name|name.*device).*"
                ))
        ) {
            String literal = unquote(latest);
            if (
                !literal.isBlank() &&
                literal.length() <= 255 &&
                !literal.contains("\n") &&
                !literal.matches("(?iu)да|нет|yes|no|ок|ok")
            ) name = literal;
        }
        if (name != null) values.put("name", name);
        var field = Pattern.compile(
            "(?imu)^\\s*(тип(?: устройства)?|device type|type|производитель|manufacturer|модель|model)\\s*[:=]\\s*([^\\r\\n]{1,255})$"
        ).matcher(latest);
        while (field.find()) {
            String key = field.group(1).toLowerCase(java.util.Locale.ROOT);
            key = key.matches("тип.*|device type|type")
                ? "type"
                : key.matches("производитель|manufacturer")
                  ? "manufacturer"
                  : "model";
            values.put(key, unquote(field.group(2)));
        }
        if (
            input.get("priorAssistantQuestion") instanceof String question &&
            question.matches("(?isu).*(тип устройства|device type).*")
        ) {
            String literal = unquote(latest);
            if (
                !literal.isBlank() && literal.length() <= 100 && !literal.contains("\n")
            ) values.put("type", literal);
        }
        return values;
    }

    static String explicitName(String message) {
        var named = NAME.matcher(message);
        String name = null;
        while (named.find()) name = unquote(named.group(1));
        if (name != null) return name;
        var quoted = QUOTED.matcher(message);
        return quoted.find() ? quoted.group(1).strip() : null;
    }

    private static String unquote(String value) {
        return value.strip().replaceAll("^[«\"']|[»\"']$", "").strip();
    }
}
