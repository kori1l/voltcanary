package com.voltcanary.analytics.service;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Parses logical CSV records, including quoted newlines, before persistence begins. */
@Component
public class MlImportCsv {
    public record Table(List<String> headers, List<CSVRecord> records) { }

    public Table read(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                .setTrim(true).setIgnoreEmptyLines(true).setAllowMissingColumnNames(false)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        try (var parser = format.parse(new StringReader(text))) {
            var headers = parser.getHeaderNames();
            if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV import file is empty");
            return new Table(List.copyOf(headers), parser.getRecords());
        } catch (IOException | UncheckedIOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed CSV import file");
        }
    }
}
