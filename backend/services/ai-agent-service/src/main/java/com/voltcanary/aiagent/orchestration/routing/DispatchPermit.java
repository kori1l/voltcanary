package com.voltcanary.aiagent.orchestration.routing;

import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request-local authority issued only after the durable reservation and required start. */
public final class DispatchPermit {
    private final String digest;
    private final long deadline;
    private final AtomicBoolean consumed = new AtomicBoolean();

    DispatchPermit(String digest, long timeoutMillis) {
        this.digest = digest;
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    public void verifyBody(String body) {
        if (!digest.equals(SkillRegistry.digest(body))) throw new ModelRouting.Stop("DISPATCH_BODY_MISMATCH");
    }

    public void consume() {
        if (System.nanoTime() - deadline >= 0) throw new ModelRouting.Stop("DISPATCH_PERMIT_EXPIRED");
        if (!consumed.compareAndSet(false, true)) throw new ModelRouting.Stop("DISPATCH_PERMIT_CONSUMED");
    }
}
