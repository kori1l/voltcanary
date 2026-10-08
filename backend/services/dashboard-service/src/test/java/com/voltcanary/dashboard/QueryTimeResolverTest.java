package com.voltcanary.dashboard;

import com.voltcanary.dashboard.query.QueryTimeResolver;
import com.voltcanary.dashboard.query.QueryTypes.Compare;
import com.voltcanary.dashboard.query.QueryTypes.CompareMode;
import com.voltcanary.dashboard.query.QueryTypes.Comparison;
import com.voltcanary.dashboard.query.QueryTypes.Preset;
import com.voltcanary.dashboard.query.QueryTypes.Time;
import com.voltcanary.dashboard.query.QueryTypes.TimeMode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QueryTimeResolverTest {
    private final QueryTimeResolver resolver = new QueryTimeResolver();

    @Test
    void belgradeCalendarDayAndPreviousEqualDurationRespectDst() {
        Instant asOf = Instant.parse("2026-10-25T22:59:59Z");
        var frame = resolver.resolve(new Time(TimeMode.CALENDAR, Preset.TODAY, "Europe/Belgrade", null, null),
                null, new Compare(CompareMode.PREVIOUS, null, null), asOf);
        assertThat(frame.from()).isEqualTo(Instant.parse("2026-10-24T22:00:00Z"));
        assertThat(Duration.between(frame.from(), frame.to())).isEqualTo(Duration.ofHours(25).minusSeconds(1));
        assertThat(Duration.between(frame.compareFrom(), frame.compareTo())).isEqualTo(Duration.ofHours(25).minusSeconds(1));
        Instant spring = Instant.parse("2026-03-29T21:59:59Z");
        frame = resolver.resolve(new Time(TimeMode.CALENDAR, Preset.TODAY, "Europe/Belgrade", null, null), null, null, spring);
        assertThat(frame.from()).isEqualTo(Instant.parse("2026-03-28T23:00:00Z"));
        assertThat(Duration.between(frame.from(), frame.to())).isEqualTo(Duration.ofHours(23).minusSeconds(1));
    }

    @Test
    void fixedOverrideKeepsItsOwnRangeAndGlobalAsOf() {
        Instant asOf = Instant.parse("2026-10-08T12:00:00Z");
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-02T00:00:00Z");
        var frame = resolver.resolve(Time.defaultTime(), new Time(TimeMode.FIXED, null, "UTC", from, to), null, asOf);
        assertThat(frame.override()).isTrue();
        assertThat(frame.asOf()).isEqualTo(asOf);
        assertThat(frame.from()).isEqualTo(from);
        assertThat(frame.to()).isEqualTo(to);
    }

    @Test
    void zeroBaselineAndUnavailableEvidenceNeverBecomePercentages() {
        assertThat(Comparison.of(0.0, 0.0).percent()).isNull();
        assertThat(Comparison.of(5.0, 0.0).difference()).isEqualTo(5.0);
        assertThat(Comparison.of(5.0, 0.0).reason()).isEqualTo("zero_baseline");
        assertThat(Comparison.of(null, 5.0).difference()).isNull();
        assertThat(Comparison.of(10.0, 5.0).percent()).isEqualTo(100.0);
        assertThat(Comparison.of(Double.MAX_VALUE, -Double.MAX_VALUE).reason()).isEqualTo("numeric_overflow");
    }
}
