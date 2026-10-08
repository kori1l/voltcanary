package com.voltcanary.dashboard.query;

import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.query.QueryTypes.Compare;
import com.voltcanary.dashboard.query.QueryTypes.CompareMode;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import com.voltcanary.dashboard.query.QueryTypes.Preset;
import com.voltcanary.dashboard.query.QueryTypes.Time;
import com.voltcanary.dashboard.query.QueryTypes.TimeMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

@Component
public class QueryTimeResolver {
    public Frame resolve(Time inherited, Time override, Compare comparison, Instant asOf) {
        boolean local = override != null && override.mode() != null && override.mode() != TimeMode.INHERITED;
        Time selected = local ? override : inherited == null ? Time.defaultTime() : inherited;
        ZoneId zone;
        try {
            zone = ZoneId.of(selected.timezone() == null ? "UTC" : selected.timezone());
        } catch (RuntimeException exception) {
            throw invalid("Unknown IANA timezone");
        }
        Instant from;
        Instant to = asOf;
        if (selected.mode() == TimeMode.FIXED) {
            from = selected.from();
            to = selected.to();
        } else {
            Preset preset = selected.preset() == null ? Preset.LAST_24_HOURS : selected.preset();
            LocalDate today = asOf.atZone(zone).toLocalDate();
            from = switch (preset) {
                case LAST_24_HOURS -> asOf.minus(Duration.ofDays(1));
                case LAST_7_DAYS -> asOf.minus(Duration.ofDays(7));
                case LAST_30_DAYS -> asOf.minus(Duration.ofDays(30));
                case TODAY -> today.atStartOfDay(zone).toInstant();
                case WEEK_TO_DATE -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant();
                case MONTH_TO_DATE -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
                case NEXT_7_DAYS, NEXT_30_DAYS -> asOf;
            };
            if (preset == Preset.NEXT_7_DAYS || preset == Preset.NEXT_30_DAYS) {
                to = asOf.plus(Duration.ofDays(preset == Preset.NEXT_7_DAYS ? 7 : 30));
            }
        }
        requireRange(from, to);
        Instant compareFrom = null;
        Instant compareTo = null;
        String basis = "NONE";
        if (comparison != null && comparison.mode() != null && comparison.mode() != CompareMode.NONE) {
            if (comparison.mode() == CompareMode.PREVIOUS) {
                compareTo = from;
                compareFrom = from.minus(Duration.between(from, to));
                basis = "PREVIOUS_EQUAL_DURATION";
            } else {
                compareFrom = comparison.from();
                compareTo = comparison.to();
                basis = "MANUAL_EXPLICIT_INTERVAL";
            }
            requireRange(compareFrom, compareTo);
        }
        return new Frame(from, to, asOf, zone.getId(), local, compareFrom, compareTo, basis);
    }

    private void requireRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw invalid("Expected a positive half-open interval of at most31 days");
        }
    }

    private DashboardFailure invalid(String message) {
        return new DashboardFailure("VALIDATION_ERROR", message, "time");
    }
}
