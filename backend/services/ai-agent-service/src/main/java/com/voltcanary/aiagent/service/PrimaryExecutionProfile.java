package com.voltcanary.aiagent.service;

/** Explicit experiment policy; never a second budget or a model selection policy. */
public enum PrimaryExecutionProfile {
    STANDARD(true, false),
    FIRST_PASS_DIAGNOSTIC(false, false),
    VERIFIED(true, true);

    private final boolean corrections;
    private final boolean judge;

    PrimaryExecutionProfile(boolean corrections, boolean judge) {
        this.corrections = corrections;
        this.judge = judge;
    }

    public boolean automaticCorrections() {
        return corrections;
    }

    public boolean modelJudge() {
        return judge;
    }
}
