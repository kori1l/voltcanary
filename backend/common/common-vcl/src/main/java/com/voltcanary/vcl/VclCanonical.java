package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

public final class VclCanonical {
    private VclCanonical() { }

    public static String json(VclDocument document) { return encode(VclValues.normalized(document.json())); }

    public static String documentDigest(VclDocument document) { return sha256(json(document)); }

    /** Display labels are omitted only at documented resource/field presentation locations. */
    public static String semanticDigest(VclDocument document) {
        var value = VclValues.normalized(document.json());
        var spec = (ObjectNode) value.path("spec");
        if (document.kind() == VclDocument.Kind.COLLECTION) {
            spec.remove("label");
            spec.path("fields").forEach(field -> ((ObjectNode) field).remove("label"));
        }
        if (document.kind() == VclDocument.Kind.RULE) spec.remove("name");
        return sha256(encode(value));
    }

    /** JCS for trusted typed snapshots; this does not parse, authorize or validate a VCL document. */
    public static String encode(JsonNode value) {
        try { return new JsonCanonicalizer(value.toString()).getEncodedString(); }
        catch (IOException error) { throw new VclException("CANONICALIZATION_INVALID", ""); }
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
