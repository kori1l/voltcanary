package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MlFeatureHashService {

    private final ObjectMapper objectMapper;

    public MlFeatureHashService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML feature metadata");
        }
    }

    public String featureDataHash(List<MlFeatureRowData> rows) {
        return sha256(rows.stream()
                .sorted(Comparator.comparing(MlFeatureRowData::entityId).thenComparing(MlFeatureRowData::predictionAt))
                .map(row -> row.entityId() + "|" + row.predictionAt() + "|" + writeJson(row.featureValues()) + "|" + writeJson(row.labelValues()))
                .collect(Collectors.joining("\n")));
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
