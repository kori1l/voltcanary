package com.voltcanary.device.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RegistryCsvTest {

    @Test
    void quotedCommasQuotesAndLineBreaksRoundTrip() {
        var values = List.of("freezer, kitchen", "ACME \"Company\"", "line one\r\nline two");
        String record = values
            .stream()
            .map(RegistryCsv::cell)
            .collect(java.util.stream.Collectors.joining(","));
        assertThat(RegistryCsv.read("\uFEFF" + record))
            .singleElement()
            .satisfies(row -> assertThat(row).containsExactlyElementsOf(values));
    }

    @Test
    void invalidQuotingIsReportedAsAnImportValidationError() {
        assertThatThrownBy(() -> RegistryCsv.read("\"unterminated"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid CSV");
    }
}
