package com.voltcanary.aiagent;

import com.voltcanary.aiagent.service.CreationPeriod;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CreationPeriodTest {
    @Test void theLatestExplicitClarificationOverridesAnEarlierPeriod() {
        assertThat(CreationPeriod.explicitOrProposed("Последние сутки; последние 7 дней", null,
                java.util.List.of("Последние 7 дней", "Название"))).isEqualTo("LAST_7_DAYS");
        assertThatThrownBy(() -> CreationPeriod.explicitOrProposed("Последние сутки", null,
                java.util.List.of("Последние 7 дней или последние 30 дней"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitSupportedWordsTakePrecedenceAndAmbiguousPeriodsAsk() {
        assertThat(CreationPeriod.explicitOrProposed("за последние сутки", "LAST_7_DAYS")).isEqualTo("LAST_24_HOURS");
        assertThat(CreationPeriod.explicitOrProposed("последние 24 часа", null)).isEqualTo("LAST_24_HOURS");
        assertThat(CreationPeriod.explicitOrProposed("за последние шесть часов", null)).isEqualTo("LAST_6_HOURS");
        assertThat(CreationPeriod.explicitOrProposed("last 7 days", null)).isEqualTo("LAST_7_DAYS");
        assertThat(CreationPeriod.explicitOrProposed("период шаблона", null)).isNull();
        assertThatThrownBy(() -> CreationPeriod.explicitOrProposed("последние сутки или последние 7 дней", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
