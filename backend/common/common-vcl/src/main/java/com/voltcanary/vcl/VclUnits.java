package com.voltcanary.vcl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/** Immutable registered dimensions; conversions cannot relabel incompatible quantities. */
public final class VclUnits {
    public record Unit(String dimension, BigDecimal factor, BigDecimal divisor, BigDecimal offset, boolean absolute) {
        public Unit {
            if (factor.signum() <= 0 || divisor.signum() <= 0) throw new IllegalArgumentException("Unit factors must be positive");
        }
    }
    private final Map<String, Unit> units;

    public VclUnits(Map<String, Unit> units) { this.units = Map.copyOf(units); }

    public static VclUnits standard() {
        return new VclUnits(Map.ofEntries(
                Map.entry("1", unit("dimensionless", "1", "0", false)),
                Map.entry("m", unit("length", "1", "0", false)),
                Map.entry("km", unit("length", "1000", "0", false)),
                Map.entry("m/s", unit("speed", "1", "0", false)),
                Map.entry("km/h", new Unit("speed", BigDecimal.ONE, new BigDecimal("3.6"), BigDecimal.ZERO, false)),
                Map.entry("s", unit("time", "1", "0", false)),
                Map.entry("min", unit("time", "60", "0", false)),
                Map.entry("h", unit("time", "3600", "0", false)),
                Map.entry("V", unit("voltage", "1", "0", false)),
                Map.entry("kV", unit("voltage", "1000", "0", false)),
                Map.entry("Cel", unit("temperature_absolute", "1", "273.15", true)),
                Map.entry("K", unit("temperature_absolute", "1", "0", true))));
    }

    private static Unit unit(String dimension, String factor, String offset, boolean absolute) {
        return new Unit(dimension, new BigDecimal(factor), BigDecimal.ONE, new BigDecimal(offset), absolute);
    }

    public Unit require(String id, String path) {
        VclException.require(units.containsKey(id), "UNIT_UNKNOWN", path);
        return units.get(id);
    }

    public String digest() {
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        units.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var unit = entry.getValue();
            json.putObject(entry.getKey()).put("dimension", unit.dimension()).put("factor", unit.factor().stripTrailingZeros().toPlainString())
                    .put("divisor", unit.divisor().stripTrailingZeros().toPlainString())
                    .put("offset", unit.offset().stripTrailingZeros().toPlainString()).put("absolute", unit.absolute());
        });
        return VclCanonical.sha256(VclCanonical.encode(json));
    }

    public void compatible(String from, String to, String path) {
        VclException.require(require(from, path).dimension().equals(require(to, path).dimension()), "UNIT_MISMATCH", path);
    }

    public BigDecimal convert(BigDecimal value, String from, String to, MathContext policy) {
        compatible(from, to, "");
        var source = require(from, "");
        var target = require(to, "");
        var numerator = value.multiply(source.factor()).add(source.offset().subtract(target.offset()).multiply(source.divisor()))
                .multiply(target.divisor());
        return numerator.divide(source.divisor().multiply(target.factor()), policy);
    }
}
