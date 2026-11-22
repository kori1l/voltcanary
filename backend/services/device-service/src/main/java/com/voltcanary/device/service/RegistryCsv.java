package com.voltcanary.device.service;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.commons.csv.CSVFormat;

/** Shared CSV quoting rules for device and source import/export, including multiline fields. */
final class RegistryCsv {

    private RegistryCsv() {}

    static List<String[]> read(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.startsWith("\uFEFF") ? text.substring(1) : text;
        try (var parser = CSVFormat.DEFAULT.parse(new StringReader(normalized))) {
            return parser
                .getRecords()
                .stream()
                .map(record -> record.toList().toArray(String[]::new))
                .toList();
        } catch (IOException | UncheckedIOException invalid) {
            throw new IllegalArgumentException("Invalid CSV quoting or record structure", invalid);
        }
    }

    static String cell(String value) {
        if (value == null) return "";
        if (
            value.indexOf(',') >= 0 ||
            value.indexOf('"') >= 0 ||
            value.indexOf('\r') >= 0 ||
            value.indexOf('\n') >= 0
        ) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
