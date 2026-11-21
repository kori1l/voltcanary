package com.voltcanary.aiagent.nativeops;

import static com.voltcanary.aiagent.nativeops.NativeAuthoringFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.config.PrimaryAssistantProperties;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import com.voltcanary.vcl.VclCanonical;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Controlled preparation checks; no live model or owning SQL service qualification. */
class NativeTableWorkflowTest {
    final NativeOwnerClient owner = mock(NativeOwnerClient.class);
    final AuthorizedPlanningContext authority = mock(AuthorizedPlanningContext.class);
    final PlanStore reviews = mock(PlanStore.class);
    final PrimaryOrchestrationJournal journal = mock(PrimaryOrchestrationJournal.class);
    final PrimaryAssistantProperties properties = new PrimaryAssistantProperties();
    final ProtocolValidator validator = new ProtocolValidator(JSON);
    final NativeTableWorkflow workflow = new NativeTableWorkflow(JSON, validator, owner, properties, authority, reviews, journal);
    final AuthorizedPlanningContext.Scope scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
    final OrchestrationPorts.Session session = new OrchestrationPorts.Session(new AuthenticatedUser(scope.userId(), "fixture@example.test", "USER"), scope, AUTH);

    @Test void disabledPackageMakesNoAuthorityOrOwnerCalls() {
        assertThat(workflow.capabilities(session)).isEmpty();
        verifyNoInteractions(owner, authority, reviews, journal);
        properties.setNativeDataEnabled(true);
        when(authority.scope(session.user(), AUTH)).thenReturn(scope);
        when(owner.language(AUTH)).thenReturn(language());
        assertThat(workflow.capabilities(session)).isEqualTo(Set.of(NativeTableSkill.CAPABILITY));
        when(owner.language(AUTH)).thenThrow(new NativeOwnerClient.Uncertain("unavailable"));
        assertThat(workflow.capabilities(session)).isEmpty();
    }

    @Test void changedScopeCannotReserveOrPersistAReview() {
        properties.setNativeDataEnabled(true);
        when(authority.scope(session.user(), AUTH)).thenReturn(new AuthorizedPlanningContext.Scope(UUID.randomUUID(), scope.accountId()));
        assertThatThrownBy(() -> prepare(input())).hasMessage("NATIVE_SESSION_SCOPE_CHANGED");
        verifyNoInteractions(owner, reviews, journal);
    }

    @Test void mappingPreparationSavesOnlyTheSelectedOwnerPreview() {
        properties.setNativeDataEnabled(true);
        when(authority.scope(session.user(), AUTH)).thenReturn(scope);
        var binding = UUID.randomUUID();
        var field = UUID.randomUUID();
        var snapshot = UUID.randomUUID();
        var device = UUID.randomUUID();
        var definition = JSON.createObjectNode().put("id", binding.toString()).put("schemaRevision", 1)
                .put("profile", com.voltcanary.vcl.VclSourceBindingProfile.VERSION).put("configurationDigest", DIGEST);
        definition.putArray("fields").addObject().put("id", field.toString()).put("type", "TEXT")
                .put("label", "Switch").put("nullable", true);
        var digest = VclCanonical.sha256(VclCanonical.encode(definition));
        var source = new com.voltcanary.vcl.VclSourceBindingDescription(binding, scope.accountId(), scope.userId(), device, 1, 1,
                snapshot, definition, digest, DIGEST, DIGEST, true, false, com.voltcanary.vcl.VclSourceBindingProfile.capabilities());
        var pin = new com.voltcanary.vcl.VclObservationReadiness.Pin(binding, scope.accountId(), device, "source", 1, snapshot, digest);
        when(owner.sourceCapture(AUTH, scope, binding, 1)).thenReturn(new com.voltcanary.vcl.VclSourceCaptureDescription(
                source, com.voltcanary.vcl.VclObservationReadiness.absent(pin, "AWAITING_INPUT")));
        var payload = JSON.createObjectNode().put("fieldId", field.toString()).put("sample", "off");
        var conversion = payload.putObject("conversion").put("method", "parse_boolean");
        conversion.putArray("trueTokens").add("on");
        conversion.putArray("falseTokens").add("off");
        var preview = JSON.createObjectNode().put("schemaSnapshotId", snapshot.toString()).put("mappingDigest", DIGEST);
        preview.putObject("literal").put("type", "BOOLEAN").put("value", false);
        when(owner.previewMapping(AUTH, scope, binding, 1, field, conversion, payload.path("sample"))).thenReturn(preview);
        when(reviews.save(any(), any())).thenAnswer(call -> new PlanStore.Stored(call.getArgument(0), scope.accountId(),
                1, DIGEST, Instant.now().plusSeconds(60), "READY", null, List.of()));
        var submitted = JSON.createObjectNode();
        submitted.putArray("selection").addObject().put("field", "sourceBinding").put("ref", binding + ":1");
        var input = input();
        var command = new AssistantProtocol.Command(NativeMappingSkill.COMMAND, "1", payload);
        var result = workflow.prepareMapping(input, command, session, submitted.toString());

        assertThat(result.review().requiresConfirmation()).isTrue();
        var plan = ArgumentCaptor.forClass(AgentPlan.class);
        verify(reviews).save(plan.capture(), any());
        var saved = JSON.valueToTree(plan.getValue().actions().getFirst().parameters());
        assertThat(saved.path("bindingId").asText()).isEqualTo(binding.toString());
        assertThat(saved.at("/mappingExample/literal")).isEqualTo(preview.path("literal"));
        verify(owner).sourceCapture(AUTH, scope, binding, 1);
        verify(owner).previewMapping(AUTH, scope, binding, 1, field, conversion, payload.path("sample"));
        verifyNoMoreInteractions(owner);
        verify(journal).bind(result.review().reviewId(), input.state().planId(), "native");
    }

    @Test void preparationPinsReservedDocumentAndOwnerExpiryWithoutApplying() {
        configure();
        var input = input();
        var result = prepare(input);
        assertThat(result.review().requiresConfirmation()).isTrue();
        assertThat(result.outputs()).singleElement().satisfies(output -> {
            assertThat(output.type()).isEqualTo("dataset-id");
            assertThat(output.resourceKind()).isEqualTo("dataset");
        });
        var plans = ArgumentCaptor.forClass(AgentPlan.class);
        verify(reviews).saveBounded(plans.capture(), any(), eq(Instant.parse("2030-01-01T00:15:00Z")));
        assertThat(plans.getValue().actions()).singleElement().satisfies(action -> {
            assertThat(action.requiresConfirmation()).isTrue();
            assertThat(JSON.<com.fasterxml.jackson.databind.JsonNode>valueToTree(action.parameters().get("document"))).isEqualTo(DOCUMENT.json());
            assertThat(action.parameters()).containsEntry("ownerReviewId", id("reviewId").toString());
        });
        verify(owner).language(AUTH);
        verify(owner).reserve(eq(AUTH), startsWith("ai-native-"), anyList());
        verify(owner).prepare(AUTH, id("reviewId"), DOCUMENT.json());
        verifyNoMoreInteractions(owner);
        verify(journal).bind(result.review().reviewId(), input.state().planId(), "native");
    }

    @Test void ownerRetargetingRejectsBeforePersistingAuthority() {
        configure();
        var good = review("PREPARED");
        when(owner.prepare(anyString(), any(), any())).thenReturn(new NativeOwnerClient.Review(UUID.randomUUID(), good.revision(),
                good.status(), good.expiresAt(), good.datasetId(), good.fieldIds(), good.semanticDigest(), good.digest(), good.diff(), good.receiptId()));
        assertThatThrownBy(() -> prepare(input())).hasMessage("NATIVE_PREPARATION_CHANGED");
        verifyNoInteractions(reviews, journal);
    }

    @Test void terminalReservationReplayCannotRequestPreparationOrPersistAuthority() {
        configure();
        when(owner.reserve(eq(AUTH), anyString(), anyList())).thenReturn(review("SUCCEEDED"));
        assertThatThrownBy(() -> prepare(input())).hasMessage("NATIVE_RESERVATION_NOT_PREPARABLE");
        verify(owner, never()).prepare(anyString(), any(), any());
        verifyNoInteractions(reviews, journal);
    }

    @Test void preparedReservationReplayCanRecoverTheSameReview() {
        configure();
        when(owner.reserve(eq(AUTH), anyString(), anyList())).thenReturn(review("PREPARED"));
        assertThat(prepare(input()).review().requiresConfirmation()).isTrue();
        verify(owner).prepare(AUTH, id("reviewId"), DOCUMENT.json());
    }

    private void configure() {
        properties.setNativeDataEnabled(true);
        when(authority.scope(session.user(), AUTH)).thenReturn(scope);
        when(owner.language(AUTH)).thenReturn(language());
        when(owner.reserve(eq(AUTH), anyString(), anyList())).thenReturn(review("RESERVED"));
        when(owner.prepare(eq(AUTH), eq(id("reviewId")), any())).thenReturn(review("PREPARED"));
        when(reviews.saveBounded(any(), any(), any())).thenAnswer(call -> new PlanStore.Stored(call.getArgument(0), scope.accountId(),
                1, DIGEST, call.getArgument(2), "READY", null, List.of()));
    }

    private NativeOwnerClient.Review review(String status) {
        var fields = new LinkedHashMap<String, UUID>();
        FIXTURE.at("/reservation/fieldIdsByKey").fields().forEachRemaining(entry -> fields.put(entry.getKey(), UUID.fromString(entry.getValue().asText())));
        return new NativeOwnerClient.Review(id("reviewId"), status.equals("RESERVED") ? 0 : 1, status,
                Instant.parse("2030-01-01T00:15:00Z"), id("resourceId"), fields, VclCanonical.semanticDigest(DOCUMENT), DIGEST, DOCUMENT.spec(), id("receiptId"));
    }

    private OrchestrationPorts.Prepared prepare(IntentPlans.ModelInput input) {
        var manifest = validator.read("manifest", NativeTableSkill.create(JSON, validator).manifest(), AssistantProtocol.Manifest.class);
        return workflow.prepare(input, manifest, new AssistantProtocol.Command(NativeTableSkill.INTENT + ":command:1", "1", FIXTURE.path("draft")), session, null);
    }

    private IntentPlans.ModelInput input() {
        UUID plan = UUID.randomUUID(), conversation = UUID.randomUUID(), message = UUID.randomUUID();
        var step = new AssistantProtocol.StepState("native", NativeTableSkill.INTENT, "1", "1", 1L,
                List.of(), List.of(), List.of(), "PREPARING", null, null, null);
        var state = new IntentPlans.State(plan, conversation, 1, "PREPARING", List.of(step), List.of(), List.of(), null,
                null, Map.of(), Map.of(), Map.of(), "fixture", "1", Instant.now().plusSeconds(600), List.of());
        var token = new IntentPlans.Token(scope, conversation, plan, UUID.randomUUID(), message, 1, UUID.randomUUID(), null, "Create table", null);
        return new IntentPlans.ModelInput(token, state, Map.of(), new IntentPlans.SourceMessage(message, "Create table"));
    }
}
