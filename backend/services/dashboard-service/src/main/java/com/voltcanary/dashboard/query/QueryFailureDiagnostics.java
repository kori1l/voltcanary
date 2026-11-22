package com.voltcanary.dashboard.query;

import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Preserve failure locations without logging remote bodies, SQL values or credentials in messages. */
final class QueryFailureDiagnostics {

    private static final Logger LOG = LoggerFactory.getLogger(QueryFailureDiagnostics.class);

    private QueryFailureDiagnostics() {}

    static void unexpected(UUID dashboardId, String widgetId, String phase, Exception failure) {
        Throwable cause = failure;
        for (
            int depth = 0;
            depth < 8 && cause.getCause() != null && cause.getCause() != cause;
            depth++
        ) {
            cause = cause.getCause();
        }
        var sites = Arrays.stream(cause.getStackTrace())
            .filter(frame -> frame.getClassName().startsWith("com.voltcanary."))
            .limit(6)
            .map(StackTraceElement::toString)
            .toList();
        LOG.warn(
            "dashboardQueryFailure dashboardId={} widgetId={} phase={} failureType={} causeType={} sites={}",
            dashboardId,
            widgetId,
            phase,
            failure.getClass().getName(),
            cause.getClass().getName(),
            sites
        );
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
    }
}
