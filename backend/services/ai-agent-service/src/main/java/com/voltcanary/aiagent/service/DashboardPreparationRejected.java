package com.voltcanary.aiagent.service;

/** A model-generated composition failed read-only preparation; it is not an operator task. */
public final class DashboardPreparationRejected extends RuntimeException {

    private final String category;
    private final String code;
    private final boolean retriable;

    DashboardPreparationRejected(String diagnostics) {
        this("INVALID_PLAN", "DOMAIN_PREPARATION_REJECTED", diagnostics, true);
    }

    DashboardPreparationRejected(
        String category,
        String code,
        String diagnostics,
        boolean retriable
    ) {
        super(diagnostics);
        this.category = category;
        this.code = code;
        this.retriable = retriable;
    }

    public String category() {
        return category;
    }

    public String code() {
        return code;
    }

    public boolean retriable() {
        return retriable;
    }
}
