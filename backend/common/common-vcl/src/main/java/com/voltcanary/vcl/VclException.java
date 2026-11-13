package com.voltcanary.vcl;

/** Stable diagnostic code and JSON pointer; never includes offending customer values. */
public final class VclException extends IllegalArgumentException {
    private final String code;
    private final String path;

    public VclException(String code, String path) {
        super(code + " at " + path);
        this.code = code;
        this.path = path;
    }

    public String code() { return code; }
    public String path() { return path; }

    static void require(boolean condition, String code, String path) {
        if (!condition) throw new VclException(code, path);
    }
}
