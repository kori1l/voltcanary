package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.config.PrimaryAssistantProperties;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import com.voltcanary.aiagent.service.PlanningInputGuard;
import com.voltcanary.vcl.VclCanonical;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Focused native creation preparation. HTTP completes before the existing short review-store transaction. */
@Component
@RequiredArgsConstructor
public class NativeTableWorkflow {
    private final ObjectMapper mapper;
    private final ProtocolValidator validator;
    private final NativeOwnerClient owner;
    private final PrimaryAssistantProperties properties;
    private final AuthorizedPlanningContext authority;
    private final PlanStore reviews;
    private final PrimaryOrchestrationJournal journal;

    private com.voltcanary.vcl.VclSourceBindingDescription mappingSource(IntentPlans.ModelInput input,
            OrchestrationPorts.Session session, String submitted) {
        require(properties.isNativeDataEnabled(), "NATIVE_AUTHORING_DISABLED");
        require(input.token().owner().equals(session.owner()), "NATIVE_SESSION_SCOPE_CHANGED");
        checkSession(session);
        var refs = new java.util.ArrayList<String>();
        for (var item : validator.parse(submitted).path("selection")) {
            if ("sourceBinding".equals(item.path("field").asText())) refs.add(item.path("ref").asText());
        }
        require(refs.size() == 1, "SOURCE_CAPTURE_EXPLICIT_SELECTION_REQUIRED");
        var parts = refs.getFirst().split(":", -1);
        require(parts.length == 2, "SOURCE_CAPTURE_SELECTION_INVALID");
        return owner.sourceCapture(session.authorization(), session.owner(), UUID.fromString(parts[0]),
                Long.parseLong(parts[1])).source();
    }

    ContextSources.Page mappingContext(IntentPlans.ModelInput input, OrchestrationPorts.Session session, String submitted) {
        var source = mappingSource(input, session, submitted);
        var data = mapper.createObjectNode();
        var context = data.putObject("context").put("bindingId", source.id().toString()).put("schemaRevision", source.schemaRevision());
        context.set("fields", source.definition().path("fields"));
        data.set("operationSchema", NativeMappingSkill.commandSchema(validator));
        return new ContextSources.Page(SkillRegistry.digest(data.toString()), Instant.now(), data, null);
    }

    OrchestrationPorts.Prepared prepareMapping(IntentPlans.ModelInput input, AssistantProtocol.Command command,
            OrchestrationPorts.Session session, String submitted) {
        require(NativeMappingSkill.COMMAND.equals(command.schemaId()) && "1".equals(command.schemaVersion()),
                "NATIVE_SKILL_VERSION_CHANGED");
        var source = mappingSource(input, session, submitted);
        var payload = command.payload();
        validator.validateRegistered(NativeMappingSkill.commandSchema(validator), payload);
        var field = UUID.fromString(payload.path("fieldId").textValue());
        var conversion = payload.path("conversion");
        com.voltcanary.vcl.VclBooleanTokens.parse(conversion, "/conversion");
        var preview = owner.previewMapping(session.authorization(), session.owner(), source.id(), source.schemaRevision(),
                field, conversion, payload.path("sample"));
        require(source.snapshotId().toString().equals(preview.path("schemaSnapshotId").asText()), "NATIVE_MAPPING_PREVIEW_CHANGED");
        var action = new AgentAction(UUID.randomUUID(), AgentTool.APPLY_SOURCE_MAPPING, "Применить маппинг источника",
                "Проверьте правила преобразования в логическое значение.", true,
                Map.of("accountId", session.owner().accountId().toString(), "userId", session.owner().userId().toString(),
                        "bindingId", source.id().toString(), "schemaRevision", source.schemaRevision(),
                        "fieldId", field.toString(), "conversion", conversion, "mappingDigest", preview.path("mappingDigest").textValue(),
                        "mappingExample", Map.of("sample", payload.path("sample"), "literal", preview.path("literal"))));
        var plan = new AgentPlan(UUID.randomUUID(), session.owner().userId(), input.currentMessage().text(),
                "Подготовлен маппинг источника", "Проверьте преобразование перед подтверждением.",
                List.of("assistant-v2"), List.of(action), Instant.now());
        var saved = reviews.save(plan, new AuthorizedPlanningContext.Prepared(session.owner(), Map.of(), List.of()));
        journal.bind(saved.plan().id(), input.state().planId(), step(input));
        return new OrchestrationPorts.Prepared(new IntentPlans.Review(saved.plan().id(), saved.version(), saved.digest(),
                List.of(action.id()), true), List.of());
    }

    public Set<String> capabilities(OrchestrationPorts.Session session) {
        if (!properties.isNativeDataEnabled()) return Set.of();
        try {
            checkSession(session);
            owner.language(session.authorization());
            return Set.of(NativeTableSkill.CAPABILITY);
        } catch (RuntimeException unavailable) { return Set.of(); }
    }

    public ContextSources.Page context(OrchestrationPorts.Session session) {
        require(properties.isNativeDataEnabled(), "NATIVE_AUTHORING_DISABLED");
        checkSession(session);
        var language = owner.language(session.authorization());
        var data = mapper.createObjectNode();
        var context = data.putObject("context");
        context.put("authoringProfile", NativeCollectionDraft.PROFILE).put("registryDigest", language.at("/registry/registryDigest").asText())
                .put("ruleExecutionReady", false).put("historicalQueriesReady", false);
        data.set("operationSchema", NativeCollectionDraft.schema(mapper));
        return new ContextSources.Page(SkillRegistry.digest(data.toString()), Instant.now(), data, null);
    }

    public void validate(JsonNode response) {
        if (response.path("outcome").asText().equals("PREPARED")) new NativeCollectionDraft(response.at("/command/payload"), validator, mapper);
    }

    public OrchestrationPorts.Prepared prepare(IntentPlans.ModelInput input, AssistantProtocol.Manifest skill,
            AssistantProtocol.Command command, OrchestrationPorts.Session session, AiResolvedModel selected) {
        require(properties.isNativeDataEnabled() && skill.intentId().equals(NativeTableSkill.INTENT), "NATIVE_AUTHORING_DISABLED");
        require(skill.skillId().equals("native-table") && skill.skillVersion().equals(NativeTableSkill.VERSION)
                && command.schemaId().equals(NativeTableSkill.INTENT + ":command:1") && command.schemaVersion().equals(NativeTableSkill.VERSION),
                "NATIVE_SKILL_VERSION_CHANGED");
        checkSession(session);
        owner.language(session.authorization());
        var draft = new NativeCollectionDraft(command.payload(), validator, mapper);
        new PlanningInputGuard().inspect(mapper.convertValue(draft.value(), Map.class));
        String step = step(input);
        String key = "ai-native-" + VclCanonical.sha256(session.owner() + "\n" + input.state().planId() + "\n" + step
                + "\n" + input.token().requestId() + "\n" + VclCanonical.encode(draft.value()));
        var reserved = owner.reserve(session.authorization(), key, draft.fieldKeys());
        require(Set.of("RESERVED", "PREPARED").contains(reserved.status()), "NATIVE_RESERVATION_NOT_PREPARABLE");
        var document = draft.document(reserved.datasetId(), reserved.fieldIds(), mapper);
        var prepared = owner.prepare(session.authorization(), reserved.id(), document.json());
        require(prepared.id().equals(reserved.id()) && prepared.datasetId().equals(reserved.datasetId())
                && prepared.receiptId().equals(reserved.receiptId()) && prepared.fieldIds().equals(reserved.fieldIds())
                && prepared.expiresAt().equals(reserved.expiresAt()) && prepared.revision() >= 1 && prepared.digest() != null && "PREPARED".equals(prepared.status())
                && VclCanonical.semanticDigest(document).equals(prepared.semanticDigest()) && document.spec().equals(prepared.diff()), "NATIVE_PREPARATION_CHANGED");
        var values = Map.<String, Object>of("protocol", NativeTableSkill.PROTOCOL, "ownerReviewId", prepared.id().toString(),
                "ownerReviewRevision", prepared.revision(), "ownerReviewDigest", prepared.digest(), "ownerReceiptId", prepared.receiptId().toString(),
                "datasetId", prepared.datasetId().toString(), "ownerIdempotencyKey", key,
                "semanticDigest", prepared.semanticDigest(), "document", mapper.convertValue(document.json(), Map.class));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_NATIVE_TABLE, "Создать таблицу «" + draft.value().path("label").asText() + "»",
                "Таблица с " + draft.fieldKeys().size() + " полями", true, values);
        var plan = new AgentPlan(UUID.randomUUID(), session.owner().userId(), input.currentMessage().text(), "Подготовлена новая таблица",
                "Проверьте поля и подтвердите создание.", List.of("assistant-v2", NativeCollectionDraft.PROFILE), List.of(action), Instant.now());
        if (selected != null) plan = new AgentPlan(plan.id(), plan.userId(), plan.userMessage(), plan.summary(), plan.explanation(), plan.safetyNotes(), plan.actions(), plan.createdAt(),
                selected.provider().getProviderType().name(), selected.provider().getName(), selected.modelConfig().getModelId(), selected.provider().getId(), selected.modelConfig().getId(), false);
        var ref = new AuthorizedPlanningContext.ResourceRef("native-review", "native-review", prepared.id().toString(),
                Map.of("reviewDigest", prepared.digest(), "revision", prepared.revision(), "datasetId", prepared.datasetId().toString(), "receiptId", prepared.receiptId().toString()));
        authority.revalidate(List.of(ref), session.authorization());
        var saved = reviews.saveBounded(plan, new AuthorizedPlanningContext.Prepared(session.owner(), Map.of("nativeProfile", NativeCollectionDraft.PROFILE), List.of(ref)), prepared.expiresAt());
        journal.bind(saved.plan().id(), input.state().planId(), step);
        var review = new IntentPlans.Review(saved.plan().id(), saved.version(), saved.digest(), List.of(action.id()), true);
        return new OrchestrationPorts.Prepared(review, List.of(new IntentPlans.Output("datasetId", "dataset-id", review.reviewId(), action.id(), "/datasetId", "dataset")));
    }

    private void checkSession(OrchestrationPorts.Session session) {
        require(authority.scope(session.user(), session.authorization()).equals(session.owner()), "NATIVE_SESSION_SCOPE_CHANGED");
    }
    public static String step(IntentPlans.ModelInput input) {
        return input.state().steps().stream().filter(value -> value.status().equals("PREPARING")).findFirst().orElseThrow().stepId();
    }
    private static void require(boolean value, String code) { if (!value) throw IntentPlans.reject(code); }
}
