package com.voltcanary.aiagent.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DashboardTaskNameTest {
    @Test void initialQuotedDashboardNameIsAKnownFactBeforeTheOperationModelRuns() {
        assertThat(DashboardTaskName.explicit("Создай дашборд «QA двери» для транспорта. Период час.", Map.of())).isEqualTo("QA двери");
        assertThat(DashboardTaskName.explicit("Create dashboard \"My tram\" with a map", Map.of())).isEqualTo("My tram");
        assertThat(DashboardTaskName.explicit("Назови Мой трамвай", Map.of())).isEqualTo("Мой трамвай");
        assertThat(DashboardTaskName.explicit("Убери заголовок «Скорость»", Map.of())).isNull();
        assertThat(DashboardTaskName.explicit("Создай дашборд «Другой»", Map.of("previousDashboardIntent", Map.of()))).isNull();
    }
}
