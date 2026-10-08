package com.voltcanary.dashboard.domain;

/** Domain failure codes are mapped to HTTP only by the web adapter. */
public class DashboardFailure extends RuntimeException {
    private final String code;
    private final String field;

    public DashboardFailure(String code, String message) {
        this(code, message, null);
    }

    public DashboardFailure(String code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
