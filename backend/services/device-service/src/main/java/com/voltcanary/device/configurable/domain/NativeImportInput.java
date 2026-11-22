package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.vcl.VclBooleanTokens;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclUnits;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bounded, strict CSV/JSONL parsing and explicitly declared conversions, without target writes. */
public final class NativeImportInput {

    @FunctionalInterface
    public interface ReferenceResolver {
        JsonNode resolve(JsonNode literal, JsonNode conversion);
    }

    private static final VclParser PARSER = new VclParser();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private NativeImportInput() {}

    public static List<JsonNode> read(byte[] bytes, JsonNode spec) {
        var parsed = readPrefix(bytes, spec);
        require(parsed.complete(), "LIMIT_EXCEEDED");
        return parsed.rows();
    }

    public record Prefix(List<JsonNode> rows, boolean complete) {
        public Prefix {
            rows = List.copyOf(rows);
        }
    }

    public static Prefix readPrefix(byte[] bytes, JsonNode spec) {
        require(bytes.length <= spec.at("/limits/maxBytes").longValue(), "LIMIT_EXCEEDED");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new VclException("INVALID_UTF8", "");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return spec.at("/format/kind").asText().equals("CSV") ? csv(text, spec) : jsonl(text, spec);
    }

    private static Prefix jsonl(String text, JsonNode spec) {
        var result = new ArrayList<JsonNode>();
        long maximum = spec.at("/limits/maxRows").longValue();
        int start = 0;
        while (start < text.length()) {
            if (result.size() >= maximum) return new Prefix(result, false);
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String line = text.substring(start, end);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            rowLimit(line, spec);
            var row = PARSER.readJson(line.getBytes(StandardCharsets.UTF_8));
            require(row.isObject(), "FORMAT_INVALID");
            result.add(row);
            start = end + 1;
        }
        return new Prefix(result, true);
    }

    private static Prefix csv(String text, JsonNode spec) {
        char delimiter = spec.at("/format/delimiter").asText().charAt(0);
        var header = new ArrayList<String>();
        spec.at("/format/header").forEach(value -> header.add(value.asText()));
        var declared = new HashSet<String>();
        spec.path("columns").forEach(column -> declared.add(column.path("sourceColumn").asText()));
        require(declared.equals(new HashSet<>(header)), "FORMAT_INVALID");
        var result = new ArrayList<JsonNode>();
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false,
            closed = false,
            first = true;
        int rowStart = 0;
        long maximum = spec.at("/limits/maxRows").longValue();
        for (int i = 0; i < text.length(); i++) {
            if (!first && result.size() >= maximum && i == rowStart) return new Prefix(
                result,
                false
            );
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else cell.append(c);
            } else if (c == delimiter || c == '\n' || c == '\r') {
                cells.add(cell.toString());
                cell.setLength(0);
                closed = false;
                if (c != delimiter) {
                    if (c == '\r') {
                        require(
                            i + 1 < text.length() && text.charAt(i + 1) == '\n',
                            "FORMAT_INVALID"
                        );
                        i++;
                    }
                    rowLimit(text.substring(rowStart, i + 1), spec);
                    first = csvRow(result, cells, header, first);
                    cells.clear();
                    rowStart = i + 1;
                }
            } else if (c == '"') {
                require(cell.isEmpty() && !closed, "FORMAT_INVALID");
                quoted = true;
            } else {
                require(!closed, "FORMAT_INVALID");
                cell.append(c);
            }
        }
        require(!quoted, "FORMAT_INVALID");
        if (rowStart < text.length()) {
            rowLimit(text.substring(rowStart), spec);
            cells.add(cell.toString());
            first = csvRow(result, cells, header, first);
        }
        require(!first, "FORMAT_INVALID");
        return new Prefix(result, true);
    }

    private static boolean csvRow(
        List<JsonNode> rows,
        List<String> cells,
        List<String> header,
        boolean first
    ) {
        require(cells.size() == header.size(), "FORMAT_INVALID");
        if (first) require(cells.equals(header), "FORMAT_INVALID");
        else {
            ObjectNode row = JSON.objectNode();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), cells.get(i));
            rows.add(row);
        }
        return false;
    }

    private static void rowLimit(String row, JsonNode spec) {
        require(
            row.getBytes(StandardCharsets.UTF_8).length <=
                spec.at("/limits/maxRowBytes").longValue(),
            "LIMIT_EXCEEDED"
        );
    }

    public static JsonNode convert(JsonNode row, JsonNode column) {
        return convert(row, column, (literal, conversion) -> {
            throw new VclException("IMPORT_CONVERSION_UNSUPPORTED", "");
        });
    }

    public static JsonNode convert(JsonNode row, JsonNode column, ReferenceResolver references) {
        JsonNode value = row.get(column.path("sourceColumn").asText());
        require(value != null, "REQUIRED_FIELD_MISSING");
        if (value.isTextual() && value.textValue().isEmpty()) {
            switch (column.path("emptyValue").asText()) {
                case "AS_NULL" -> value = JSON.nullNode();
                case "REJECT" -> throw new VclException("TYPE_MISMATCH", "");
                default -> {
                }
            }
        }
        if (value.isNull()) return JSON.objectNode().put("type", "NULL").putNull("value");
        var literal = JSON.objectNode().put("type", column.path("sourceType").asText());
        literal.set("value", value);
        boolean numeric = List.of("INT64", "DECIMAL").contains(column.path("sourceType").asText());
        if (
            numeric &&
            !column.path("sourceUnit").isNull() &&
            !column.path("sourceUnit").asText().equals("1")
        ) literal.set("unit", column.path("sourceUnit"));
        JsonNode current = PARSER.literal(literal.toString());
        // TEXT's source unit belongs to the mapping, not to the closed TEXT wire literal.
        // Retain it internally until the declared numeric parser consumes that text.
        if (
            !numeric &&
            !column.path("sourceUnit").isNull() &&
            !column.path("sourceUnit").asText().equals("1")
        ) {
            ((ObjectNode) current).set("unit", column.path("sourceUnit"));
        }
        for (var conversion : column.path("conversions"))
            current = conversion(current, conversion, references);
        return current;
    }

    private static JsonNode conversion(
        JsonNode literal,
        JsonNode conversion,
        ReferenceResolver references
    ) {
        String method = conversion.path("method").asText();
        if (method.equals("identity")) return literal;
        if (method.equals("resolve_ref")) return references.resolve(literal, conversion);
        String text = literal.path("value").asText();
        var result = JSON.objectNode();
        try {
            switch (method) {
                case "parse_int64" -> {
                    if (conversion.path("trim").asBoolean()) text = text.strip();
                    require(text.matches("-?(0|[1-9][0-9]*)"), "TYPE_MISMATCH");
                    result.put("type", "INT64").put("value", Long.toString(Long.parseLong(text)));
                }
                case "parse_decimal" -> {
                    String separator = conversion.path("decimalSeparator").asText();
                    require(
                        text.matches(
                            "-?(0|[1-9][0-9]*)(" +
                                java.util.regex.Pattern.quote(separator) +
                                "[0-9]+)?"
                        ),
                        "TYPE_MISMATCH"
                    );
                    var number = new BigDecimal(text.replace(separator, ".")).setScale(
                        conversion.path("scale").intValue(),
                        conversion.path("rounding").asText().equals("REJECT")
                            ? RoundingMode.UNNECESSARY
                            : RoundingMode.HALF_EVEN
                    );
                    result.put("type", "DECIMAL").put("value", number.toPlainString());
                }
                case "parse_boolean" -> result
                    .put("type", "BOOLEAN")
                    .put("value", VclBooleanTokens.parse(conversion, "").evaluate(text, ""));
                case "parse_date" -> result
                    .put("type", "DATE")
                    .put("value", LocalDate.parse(text).toString());
                case "parse_instant" -> result
                    .put("type", "INSTANT")
                    .put("value", instant(text, conversion));
                case "parse_duration" -> {
                    require(text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"), "TYPE_MISMATCH");
                    BigDecimal factor = switch (conversion.path("sourceUnit").asText()) {
                        case "us" -> BigDecimal.ONE;
                        case "ms" -> BigDecimal.valueOf(1000);
                        case "s" -> BigDecimal.valueOf(1_000_000);
                        case "min" -> BigDecimal.valueOf(60_000_000);
                        case "h" -> BigDecimal.valueOf(3_600_000_000L);
                        default -> throw new VclException("UNIT_MISMATCH", "");
                    };
                    BigDecimal micros = new BigDecimal(text).multiply(factor);
                    result
                        .put("type", "DURATION")
                        .put("value", Long.toString(micros.longValueExact()));
                }
                case "convert_unit" -> {
                    BigDecimal number = VclUnits.standard().convert(
                        new BigDecimal(text),
                        conversion.path("sourceUnit").asText(),
                        conversion.path("targetUnit").asText(),
                        MathContext.UNLIMITED
                    );
                    result
                        .put("type", "DECIMAL")
                        .put("value", number.toPlainString())
                        .put("unit", conversion.path("targetUnit").asText());
                }
                default -> throw new VclException("IMPORT_CONVERSION_UNSUPPORTED", "");
            }
            if (
                List.of("parse_int64", "parse_decimal").contains(method) && literal.has("unit")
            ) result.set("unit", literal.path("unit"));
            return PARSER.literal(result.toString());
        } catch (NumberFormatException | java.time.DateTimeException invalid) {
            throw new VclException("TYPE_MISMATCH", "");
        } catch (ArithmeticException loss) {
            throw new VclException("PRECISION_LOSS", "");
        }
    }

    private static String instant(String text, JsonNode config) {
        if (config.path("format").asText().equals("RFC3339_OFFSET")) return OffsetDateTime.parse(
            text
        )
            .toInstant()
            .toString();
        var local = LocalDateTime.parse(
            text,
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(
                ResolverStyle.STRICT
            )
        );
        var offsets = ZoneId.of(config.path("timezone").asText()).getRules().getValidOffsets(local);
        require(!offsets.isEmpty(), "DST_GAP");
        require(
            offsets.size() == 1 || !config.path("dstPolicy").asText().equals("REJECT"),
            "DST_AMBIGUOUS"
        );
        return local
            .toInstant(
                config.path("dstPolicy").asText().equals("LATER")
                    ? offsets.getLast()
                    : offsets.getFirst()
            )
            .toString();
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
