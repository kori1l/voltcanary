package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardTaskNameTest {

    @Test
    void standaloneRenameUsesCurrentCommandEvenWithAnExistingReview() {
        assertThat(DashboardTaskName.standaloneCommand("Назови «Мой любимый трамвай»")).isEqualTo(
            "Мой любимый трамвай"
        );
        assertThat(
            DashboardTaskName.standaloneCommand("Перемести виджет «Мой любимый трамвай»")
        ).isNull();
    }

    @Test
    void initialQuotedDashboardNameIsAKnownFactBeforeTheOperationModelRuns() {
        assertThat(
            DashboardTaskName.explicit(
                "Создай дашборд «QA двери» для транспорта. Период час.",
                Map.of()
            )
        ).isEqualTo("QA двери");
        assertThat(
            DashboardTaskName.explicit("Create dashboard \"My tram\" with a map", Map.of())
        ).isEqualTo("My tram");
        assertThat(DashboardTaskName.explicit("Назови Мой трамвай", Map.of())).isEqualTo(
            "Мой трамвай"
        );
        assertThat(DashboardTaskName.explicit("Убери заголовок «Скорость»", Map.of())).isNull();
        assertThat(
            DashboardTaskName.explicit(
                "Создай дашборд «Другой»",
                Map.of("previousDashboardIntent", Map.of())
            )
        ).isNull();
    }
}
