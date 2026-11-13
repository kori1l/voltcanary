package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.vcl.VclParser;

/** Strict persisted control JSON; it is never executable code. */
public final class GrantJson {
    private static final VclParser PARSER = new VclParser();
    private GrantJson() { }
    public static JsonNode read(String json) { return PARSER.readJson(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
