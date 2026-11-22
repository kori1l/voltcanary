package com.voltcanary.aiagent.adapter;

import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;

/** Configured capability only. No probing, model-name inference or error-triggered fallback. */
public final class PrimaryOutputCapabilities {

    private PrimaryOutputCapabilities() {}

    public static String mode(AiResolvedModel selected) {
        var config = selected.modelConfig();
        String format = config.getResponseFormat();
        if ("json_schema".equals(format) && config.isSupportsJson()) return "STRICT_JSON";
        if ("tool".equals(format) && config.isSupportsTools()) return "NATIVE_TOOLS";
        if (
            format == null ||
            format.isBlank() ||
            "text".equals(format) ||
            "json_object".equals(format)
        ) return "JSON_TEXT";
        throw new ModelRouting.Stop("OUTPUT_CAPABILITY_UNSUPPORTED");
    }
}
