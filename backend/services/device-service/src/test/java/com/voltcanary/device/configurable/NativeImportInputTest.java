package com.voltcanary.device.configurable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.domain.NativeImportInput;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeImportInputTest {
    ObjectNode spec(String kind) {
        var spec = NativeFixtures.MAPPER.createObjectNode();
        var format = spec.putObject("format").put("kind", kind).put("encoding", "UTF-8");
        if (kind.equals("CSV")) { format.put("delimiter", ","); format.putArray("header").add("name").add("value"); }
        spec.putObject("limits").put("maxBytes", 4096).put("maxRows", 10).put("maxRowBytes", 100);
        spec.putArray("columns").addObject().put("sourceColumn", "name");
        ((com.fasterxml.jackson.databind.node.ArrayNode) spec.path("columns")).addObject().put("sourceColumn", "value");
        return spec;
    }

    ObjectNode column(String sourceType, String unit, String conversion) throws Exception {
        var column = NativeFixtures.MAPPER.createObjectNode().put("sourceColumn", "value").put("sourceType", sourceType).put("emptyValue", "PRESERVE_EMPTY");
        if (unit == null) column.putNull("sourceUnit"); else column.put("sourceUnit", unit);
        column.putArray("conversions").add(NativeFixtures.MAPPER.readTree(conversion));
        return column;
    }

    JsonNode convert(String value, ObjectNode column) {
        return NativeImportInput.convert(NativeFixtures.MAPPER.createObjectNode().put("value", value), column);
    }

    @Test void csvSupportsQuotedDelimiterEscapesMultilineBomAndCrLf() {
        var rows = NativeImportInput.read("\uFEFFname,value\r\n\"a,b\",\"x\"\"y\"\r\n\"line\nnext\",z\r\n".getBytes(StandardCharsets.UTF_8), spec("CSV"));
        assertEquals(2, rows.size());
        assertEquals("a,b", rows.getFirst().path("name").textValue());
        assertEquals("x\"y", rows.getFirst().path("value").textValue());
        assertEquals("line\nnext", rows.getLast().path("name").textValue());
    }

    @Test void csvRejectsBadQuotingWrongHeaderDuplicateHeaderAndColumnCounts() {
        for (String content : new String[]{"name,value\na\"b,1", "name,value\n\"a\"junk,1", "name,value\n\"a,1", "name,name\na,1", "value,name\na,1", "name,value\na,1,2", "name,value\ra,1"}) {
            assertEquals("FORMAT_INVALID", assertThrows(VclException.class, () -> NativeImportInput.read(content.getBytes(StandardCharsets.UTF_8), spec("CSV"))).code(), content);
        }
    }

    @Test void enforcesExactByteRowAndUtf8Limits() {
        var spec = spec("CSV");
        ((ObjectNode) spec.path("limits")).put("maxRows", 1);
        assertEquals("LIMIT_EXCEEDED", assertThrows(VclException.class, () -> NativeImportInput.read("name,value\na,1\nb,2".getBytes(StandardCharsets.UTF_8), spec)).code());
        ((ObjectNode) spec.path("limits")).put("maxBytes", 1);
        assertEquals("LIMIT_EXCEEDED", assertThrows(VclException.class, () -> NativeImportInput.read(new byte[2], spec)).code());
        assertEquals("INVALID_UTF8", assertThrows(VclException.class, () -> NativeImportInput.read(new byte[]{(byte) 0xC3}, spec("JSONL"))).code());
        assertEquals("LIMIT_EXCEEDED", assertThrows(VclException.class, () -> NativeImportInput.read(("{\"value\":\"" + "é".repeat(50) + "\"}\n").getBytes(StandardCharsets.UTF_8), spec("JSONL"))).code());
    }

    @Test void jsonlRejectsDuplicateKeysTrailingJunkEmptyLinesAndArrays() {
        for (String content : new String[]{"{\"value\":1,\"value\":2}", "{} trailing", "[]", "{}\n\n"}) {
            assertThrows(VclException.class, () -> NativeImportInput.read(content.getBytes(StandardCharsets.UTF_8), spec("JSONL")), content);
        }
    }

    @Test void decimalPreservesSourceUnitAndRequiresExplicitRounding() throws Exception {
        var column = column("TEXT", "V", "{\"method\":\"parse_decimal\",\"decimalSeparator\":\",\",\"scale\":3,\"rounding\":\"REJECT\"}");
        var literal = convert("220,125", column);
        assertEquals("V", literal.path("unit").asText());
        assertEquals("220.125", literal.path("value").asText());
        ((com.fasterxml.jackson.databind.node.ArrayNode) column.path("conversions")).addObject().put("method", "convert_unit").put("sourceUnit", "V").put("targetUnit", "kV");
        assertEquals("0.220125", convert("220,125", column).path("value").asText());
        assertEquals("PRECISION_LOSS", assertThrows(VclException.class, () -> convert("220,1255", column)).code());
        assertEquals("TYPE_MISMATCH", assertThrows(VclException.class, () -> convert("220.125", column)).code());
    }

    @Test void integerRejectsWhitespaceWithoutTrimOverflowAndFraction() throws Exception {
        var column = column("TEXT", null, "{\"method\":\"parse_int64\",\"trim\":false}");
        assertEquals("9223372036854775807", convert("9223372036854775807", column).path("value").asText());
        for (String value : new String[]{" 1", "1.5", "9223372036854775808"}) assertThrows(VclException.class, () -> convert(value, column));
        ((ObjectNode) column.path("conversions").get(0)).put("trim", true);
        assertEquals("1", convert(" 1 ", column).path("value").asText());
    }

    @Test void booleanUsesExactDeclaredTokens() throws Exception {
        var column = column("TEXT", null, "{\"method\":\"parse_boolean\",\"trueTokens\":[\"Y\"],\"falseTokens\":[\"N\"]}");
        assertTrue(convert("Y", column).path("value").asBoolean());
        assertFalse(convert("N", column).path("value").asBoolean());
        assertThrows(VclException.class, () -> convert("y", column));
    }

    @Test void datesInstantsAndDstRejectImplicitOrNonexistentTimes() throws Exception {
        var date = column("TEXT", null, "{\"method\":\"parse_date\",\"format\":\"YYYY-MM-DD\"}");
        assertThrows(VclException.class, () -> convert("2026-02-30", date));
        var local = column("TEXT", null, "{\"method\":\"parse_instant\",\"format\":\"YYYY-MM-DD HH:mm:ss\",\"timezone\":\"Europe/Belgrade\",\"dstPolicy\":\"REJECT\"}");
        assertEquals("DST_GAP", assertThrows(VclException.class, () -> convert("2026-03-29 02:30:00", local)).code());
        assertEquals("DST_AMBIGUOUS", assertThrows(VclException.class, () -> convert("2026-10-25 02:30:00", local)).code());
        ((ObjectNode) local.path("conversions").get(0)).put("dstPolicy", "EARLIER");
        assertEquals("2026-10-25T00:30:00Z", convert("2026-10-25 02:30:00", local).path("value").asText());
        ((ObjectNode) local.path("conversions").get(0)).put("dstPolicy", "LATER");
        assertEquals("2026-10-25T01:30:00Z", convert("2026-10-25 02:30:00", local).path("value").asText());
    }

    @Test void durationIsExactMicrosWithoutCalendarGuessing() throws Exception {
        var column = column("TEXT", null, "{\"method\":\"parse_duration\",\"sourceUnit\":\"s\"}");
        assertEquals("1500000", convert("1.5", column).path("value").asText());
        assertEquals("PRECISION_LOSS", assertThrows(VclException.class, () -> convert("0.0000001", column)).code());
        assertThrows(VclException.class, () -> convert("P1M", column));
    }

    @Test void omissionNullEmptyAndTypedValuesAreDistinct() throws Exception {
        var column = column("TEXT", null, "{\"method\":\"identity\"}");
        assertEquals("", convert("", column).path("value").asText());
        column.put("emptyValue", "AS_NULL");
        assertEquals("NULL", convert("", column).path("type").asText());
        assertEquals("REQUIRED_FIELD_MISSING", assertThrows(VclException.class, () -> NativeImportInput.convert(NativeFixtures.MAPPER.createObjectNode(), column)).code());
        var integer = column("INT64", null, "{\"method\":\"identity\"}");
        assertThrows(VclException.class, () -> NativeImportInput.convert(NativeFixtures.MAPPER.createObjectNode().put("value", 123), integer));
        assertEquals("123", convert("123", integer).path("value").asText());
    }
}
