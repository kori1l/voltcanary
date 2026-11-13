package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Explicit, exact token matching for the VCL parse_boolean conversion. */
public final class VclBooleanTokens {
    private final Set<String> trueTokens;
    private final Set<String> falseTokens;

    private VclBooleanTokens(Set<String> trueTokens, Set<String> falseTokens) {
        this.trueTokens = Set.copyOf(trueTokens);
        this.falseTokens = Set.copyOf(falseTokens);
    }

    public static VclBooleanTokens parse(JsonNode config, String path) {
        if (!config.isObject() || config.size() != 3 || !"parse_boolean".equals(config.path("method").textValue())) {
            throw new VclException("BOOLEAN_TOKEN_CONFIG_INVALID", path);
        }
        var yes = read(config.path("trueTokens"), path);
        var no = read(config.path("falseTokens"), path);
        if (yes.stream().anyMatch(no::contains)) throw new VclException("BOOLEAN_TOKEN_OVERLAP", path);
        return new VclBooleanTokens(yes, no);
    }

    private static Set<String> read(JsonNode array, String path) {
        if (!array.isArray() || array.isEmpty() || array.size() > 8) throw new VclException("BOOLEAN_TOKEN_CONFIG_INVALID", path);
        var values = new HashSet<String>();
        for (var value : array) {
            String token = value.textValue();
            if (token == null || token.isEmpty() || token.codePointCount(0, token.length()) > 256 || !values.add(token)) {
                throw new VclException("BOOLEAN_TOKEN_CONFIG_INVALID", path);
            }
        }
        return values;
    }

    public boolean evaluate(String token, String path) {
        if (trueTokens.contains(token)) return true;
        if (falseTokens.contains(token)) return false;
        throw new VclException("BOOLEAN_TOKEN_UNKNOWN", path);
    }
}
