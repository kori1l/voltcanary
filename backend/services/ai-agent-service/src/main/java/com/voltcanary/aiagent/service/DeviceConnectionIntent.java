package com.voltcanary.aiagent.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;

/** Fixed integration identities only. No model-supplied network destinations. */
final class DeviceConnectionIntent {
    private DeviceConnectionIntent() { }

    static Map<String, Object> hsl(String request) {
        if (!request.matches("(?isu).*\\bHSL\\b.*")
                || !request.matches("(?isu).*(подключ|connect|телеметр|mqtt).*")) return Map.of();
        var identities = new LinkedHashSet<String>();
        var keys = Pattern.compile("(?<![0-9])([0-9]{4})-([0-9]{5})(?![0-9])").matcher(request);
        while (keys.find()) identities.add(keys.group());
        var topic = Pattern.compile("/hfp/v2/journey/ongoing/vp/(bus|tram)/([0-9]{4})/([0-9]{5})/").matcher(request);
        String mode = null;
        if (request.matches("(?isu).*(автобус|\\bbus\\b).*")) mode = "bus";
        if (request.matches("(?isu).*(трамва|\\btram\\b).*")) mode = "tram";
        while (topic.find()) {
            if (mode != null && !mode.equals(topic.group(1))) throw new IllegalArgumentException("Conflicting transport modes");
            mode = topic.group(1);
            identities.add(topic.group(2) + "-" + topic.group(3));
        }
        if (identities.size() != 1 || mode == null) throw new IllegalArgumentException("One HSL vehicle identity and bus/tram mode are required");
        return Map.of("provider", "HSL_HFP", "mode", mode, "vehicleKey", identities.iterator().next());
    }
}
