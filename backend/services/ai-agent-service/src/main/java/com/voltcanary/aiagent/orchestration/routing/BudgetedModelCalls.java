package com.voltcanary.aiagent.orchestration.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** One exchange per reservation. A correction/continuation must return through this same boundary. */
public final class BudgetedModelCalls {
    public record Reply(String outputMode, String proposalJson, Usage usage, String completionCode) {
        public Reply(String outputMode, String proposalJson, Usage usage) { this(outputMode, proposalJson, usage, null); }
    }
    @FunctionalInterface
    public interface SingleExchange {
        Reply send(String completeSerializedBody, ModelRouting.Route route, long timeoutMillis) throws Exception;
        /** Pure wire lowering before canonical validation; stored successful replies are already canonical. */
        default JsonNode normalize(JsonNode proposal, String completeSerializedBody) { return proposal; }
    }

    private final AttemptLedger ledger;
    private final ProtocolValidator validator;
    private final AssistantTraceRecorder traces;

    public BudgetedModelCalls(AttemptLedger ledger, ProtocolValidator validator, AssistantTraceRecorder traces) {
        this.ledger = ledger;
        this.validator = validator;
        this.traces = traces;
    }

    public JsonNode call(AttemptLedger.Turn turn, AttemptLedger.Attempt attempt, String completeSerializedBody,
            String responseDefinition, AssistantTraceRecorder.Correlation correlation, SingleExchange exchange,
            Consumer<JsonNode> registeredContractCheck) {
        if (!correlation.owner().equals(turn.owner()) || !correlation.conversationId().equals(turn.conversationId())
                || !correlation.turnId().equals(turn.turnId())) throw new ModelRouting.Stop("TRACE_SCOPE_MISMATCH");
        if (!SkillRegistry.digest(completeSerializedBody).equals(attempt.requestDigest())) throw new ModelRouting.Stop("REQUEST_DIGEST_MISMATCH");
        long bodyBytes = completeSerializedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bodyBytes != attempt.route().size().bytes() || attempt.route().size().inputTokens() < bodyBytes) throw new ModelRouting.Stop("REQUEST_SIZE_MISMATCH");
        var receipt = ledger.reserve(turn, attempt);
        if (!receipt.newlyReserved()) {
            if (receipt.status().equals("SUCCEEDED") && receipt.responseJson() != null) {
                var result = validator.parse(receipt.responseJson());
                validator.validate(responseDefinition, result);
                registeredContractCheck.accept(result);
                return result;
            }
            throw new ModelRouting.Stop("CALL_ALREADY_RESERVED:" + receipt.status());
        }
        var details = details(attempt);
        // Required persistent start; trace failure cannot permit network I/O.
        try {
            traces.append(correlation, attempt.callId(), "BUDGET", "RESERVED", null, null, details);
            traces.append(correlation, attempt.callId(), "MODEL_CALL", "STARTED", null, null, details);
        } catch (RuntimeException failure) {
            ledger.finish(turn, attempt.callId(), "FAILED", null, null);
            throw failure;
        }
        long started = System.nanoTime();
        com.voltcanary.aiagent.trace.AssistantTrace.modelAttempt(attempt.callId());
        Usage actual = null;
        boolean finished = false;
        try {
            Reply reply = exchange.send(completeSerializedBody, attempt.route(), ledger.remainingTimeMillis(turn));
            if (!reply.outputMode().equals(attempt.route().candidate().outputMode())) throw new ModelRouting.Stop("OUTPUT_MODE_MISMATCH");
            if (attempt.route().price() != null) actual = ModelRouting.reconcile(attempt.route().price(), reply.usage());
            else if (reply.usage() != null) actual = new Usage(reply.usage().inputTokens(), reply.usage().cachedInputTokens(),
                    reply.usage().outputTokens(), reply.usage().reasoningTokens(), null);
            if (reply.completionCode() != null) throw new ModelRouting.Stop(reply.completionCode());
            if (actual != null && actual.costMicros() != null) {
                long output = actual.outputTokens();
                if (!attempt.route().price().reasoningIncludedInOutput()) output = Math.addExact(output, actual.reasoningTokens());
                if (actual.inputTokens() > attempt.route().size().inputTokens() || output > attempt.route().size().outputTokens()
                        || actual.costMicros() > attempt.route().costMicros()) throw new ModelRouting.Stop("RESERVATION_EXCEEDED");
            }
            JsonNode proposal;
            try {
                proposal = validator.parse(reply.proposalJson());
            } catch (IllegalArgumentException invalid) {
                if (reply.proposalJson() == null || reply.proposalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ProtocolValidator.MAX_BYTES) throw invalid;
                throw new ModelProposalRejected(com.fasterxml.jackson.databind.node.TextNode.valueOf(reply.proposalJson()),
                        responseDefinition + ": INVALID_JSON; return exactly one complete JSON object without prose or Markdown, preserving the original request.");
            }
            try {
                proposal = exchange.normalize(proposal, completeSerializedBody);
                validator.validate(responseDefinition, proposal);
                registeredContractCheck.accept(proposal);
            } catch (IllegalArgumentException invalid) {
                if (invalid instanceof ModelProposalRejected rejected) throw rejected;
                String diagnostic = invalid instanceof ProtocolValidator.RegisteredViolation registered
                        ? "registered schema: " + registered.locations() : validator.violations(responseDefinition, proposal);
                if (diagnostic.isEmpty()) diagnostic = java.util.Set.of("UNKNOWN_QUESTION_FIELD", "UNKNOWN_COMMAND_SCHEMA", "UNKNOWN_LOADER", "UNKNOWN_OR_UNAVAILABLE_INTENT")
                        .contains(String.valueOf(invalid.getMessage())) ? invalid.getMessage() : "REGISTERED_CONTRACT_VIOLATION";
                throw new ModelProposalRejected(proposal, responseDefinition + ": " + diagnostic);
            }
            ledger.finish(turn, attempt.callId(), "SUCCEEDED", actual, proposal.toString());
            finished = true;
            var outcome = new LinkedHashMap<>(details);
            outcome.put("usage", actual == null ? "UNKNOWN" : actual);
            outcome.put("costStatus", actual == null || actual.costMicros() == null ? "UNKNOWN" : "PRICED");
            outcome(correlation, attempt, "SUCCEEDED", null, started, outcome);
            return proposal;
        } catch (Exception failure) {
            if (!finished) {
                // Unknown transport completion retains the full reservation. Never repeat an uncertain call.
                String state = failure instanceof IllegalArgumentException || failure instanceof ModelRouting.Stop ? "FAILED" : "UNCERTAIN";
                ledger.finish(turn, attempt.callId(), state, actual, null);
                var failedDetails = new LinkedHashMap<>(details);
                failedDetails.put("usage", actual == null ? "UNKNOWN" : actual);
                failedDetails.put("costStatus", actual == null || actual.costMicros() == null ? "UNKNOWN" : "PRICED");
                if (failure instanceof ModelProposalRejected rejected) {
                    failedDetails.put("validation", rejected.diagnostic());
                    failedDetails.put("proposalShape", proposalShape(rejected.proposal()));
                }
                outcome(correlation, attempt, state.equals("UNCERTAIN") ? "UNKNOWN" : "FAILED",
                        failure instanceof ModelRouting.Stop ? failure.getMessage() : failure instanceof ModelProposalRejected ? "MODEL_PROPOSAL_CONTRACT_INVALID" : "PROVIDER_OR_CONTRACT_FAILURE", started, failedDetails);
            }
            if (failure instanceof ModelRouting.Stop stop) throw stop;
            if (failure instanceof IllegalArgumentException invalid) throw invalid;
            throw new ModelRouting.Stop("PROVIDER_UNCERTAIN");
        }
    }

    private static Map<String, Object> proposalShape(JsonNode proposal) {
        var result = new LinkedHashMap<String, Object>();
        result.put("jsonObject", proposal.isObject());
        result.put("hasQuestion", proposal.hasNonNull("question"));
        result.put("hasCommand", proposal.hasNonNull("command"));
        result.put("stepCount", Math.min(128, proposal.path("steps").size()));
        result.put("operationCount", Math.min(128, proposal.at("/command/payload/operations").size()));
        String decision = proposal.path("decision").asText();
        if (java.util.Set.of("PLAN", "AMEND", "CONTINUE", "ASK", "UNSUPPORTED").contains(decision)) result.put("decision", decision);
        String outcome = proposal.path("outcome").asText();
        if (java.util.Set.of("PREPARED", "QUESTION", "NEED_CONTEXT", "REPLAN_REQUIRED", "UNSUPPORTED", "NO_CHANGE").contains(outcome)) result.put("outcome", outcome);
        return result;
    }

    private static Map<String, Object> details(AttemptLedger.Attempt attempt) {
        var details = new LinkedHashMap<String, Object>();
        details.put("callId", attempt.callId());
        details.put("phase", attempt.phase());
        details.put("purpose", attempt.purpose());
        details.put("stepId", attempt.stepId());
        details.put("policyId", attempt.policyId());
        details.put("policyVersion", attempt.policyVersion());
        details.put("skillVersion", attempt.skillVersion());
        details.put("schemaVersion", attempt.schemaVersion());
        details.put("outputMode", attempt.route().candidate().outputMode());
        details.put("projectionVersion", StrictOutputProjection.VERSION);
        details.put("contextVersion", attempt.contextVersion());
        details.put("contextBytes", attempt.route().size().bytes());
        details.put("estimatedInputTokens", attempt.route().size().inputTokens());
        details.put("sizePrecision", attempt.route().size().precision());
        details.put("reservedOutputTokens", attempt.route().size().outputTokens());
        details.put("reservedCostMicros", attempt.route().costMicros());
        details.put("routeReason", attempt.route().reason());
        details.put("providerId", attempt.route().candidate().providerId());
        details.put("modelConfigId", attempt.route().candidate().modelConfigId());
        details.put("priceSnapshotId", attempt.route().candidate().priceSnapshotId());
        return details;
    }

    private void outcome(AssistantTraceRecorder.Correlation correlation, AttemptLedger.Attempt attempt, String status,
            String code, long started, Map<String, Object> details) {
        try {
            traces.append(correlation, attempt.callId(), "MODEL_CALL", status, code, (System.nanoTime() - started) / 1_000_000, details);
            traces.append(correlation, attempt.callId(), "BUDGET", "RECONCILED", code, null, details);
        } catch (RuntimeException unavailable) {
            // Durable ledger outcome survives a diagnostic outage; an unfinished trace start is recoverable.
        }
    }
}
