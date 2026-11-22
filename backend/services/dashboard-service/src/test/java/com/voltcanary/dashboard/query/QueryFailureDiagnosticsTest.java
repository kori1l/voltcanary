package com.voltcanary.dashboard.query;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class QueryFailureDiagnosticsTest {

    @Test
    void logsUsefulFailureLocationsWithoutExceptionMessagesOrThrowablePayloads() {
        var logger = (Logger) LoggerFactory.getLogger(QueryFailureDiagnostics.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            UUID dashboard = UUID.randomUUID();
            var cause = new IllegalStateException(
                "Authorization: secret-credential; SQL private-value"
            );
            cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(
                    "com.voltcanary.dashboard.OwnerRead",
                    "read",
                    "OwnerRead.java",
                    42
                ),
                new StackTraceElement("external.Client", "send", "Client.java", 9),
            });
            QueryFailureDiagnostics.unexpected(
                dashboard,
                "widget-1",
                "projection",
                new ExecutionException(cause)
            );
            assertThat(appender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                        .contains(
                            dashboard.toString(),
                            "widget-1",
                            "projection",
                            "java.util.concurrent.ExecutionException",
                            "java.lang.IllegalStateException",
                            "OwnerRead.java:42"
                        )
                        .doesNotContain(
                            "secret-credential",
                            "private-value",
                            "Authorization",
                            "external.Client"
                        );
                    assertThat(event.getThrowableProxy()).isNull();
                });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void preservesCallerInterruption() {
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            QueryFailureDiagnostics.unexpected(
                UUID.randomUUID(),
                "widget-1",
                "query",
                new InterruptedException()
            );
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
