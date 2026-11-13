package com.voltcanary.aiagent.orchestration.plan;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Diagnostic;

/** Safe compiler/verifier diagnostic. Only preparation may be repaired; mutations never are. */
public final class StepPreparationFailure extends RuntimeException {
    private final Diagnostic diagnostic;
    public StepPreparationFailure(Diagnostic diagnostic) {
        super(diagnostic.code());
        this.diagnostic = diagnostic;
    }
    public Diagnostic diagnostic() { return diagnostic; }
}
