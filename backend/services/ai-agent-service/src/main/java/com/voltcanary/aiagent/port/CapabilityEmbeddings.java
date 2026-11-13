package com.voltcanary.aiagent.port;

import java.util.List;

/** Deployment-owned catalogue retrieval only; independent of account chat providers. */
public interface CapabilityEmbeddings {
    String identity();
    List<float[]> embed(List<String> texts);
}
