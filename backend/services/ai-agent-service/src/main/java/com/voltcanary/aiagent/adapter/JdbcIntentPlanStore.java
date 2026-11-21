package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.ConversationProperties;
import com.voltcanary.aiagent.conversation.ConversationRedaction;
import com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementAnswers;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Focus;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Input;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.BindingLiteral;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepState;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Question;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Focus;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PendingQuestion;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.conflict;

/** Reuses the conversation journal and its owner/retention boundary. No remote calls in transactions. */
public class JdbcIntentPlanStore implements IntentPlanStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ProtocolValidator validator;
    private final Clock clock;
    private final Duration lease;
    private final JdbcCollaborationStore collaboration;

    public JdbcIntentPlanStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
            ProtocolValidator validator, ConversationProperties properties) {
        this(jdbc, tx, mapper, validator, Clock.systemUTC(), properties.getLease());
    }

    public JdbcIntentPlanStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
            ProtocolValidator validator, Clock clock, Duration lease) {
        this(jdbc, tx, mapper, validator, clock, lease, null);
    }

    public JdbcIntentPlanStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
            ProtocolValidator validator, Clock clock, Duration lease, JdbcCollaborationStore collaboration) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
        this.lease = lease;
        this.collaboration = collaboration;
    }

    @Override
    public IntentPlans.Claim begin(Scope owner, IntentPlans.State seed, UUID request, long expected,
            String kind, String requestJson, String rawMessage, boolean interrupt) {
        return begin(owner, seed, request, expected, kind, requestJson, rawMessage, interrupt, null);
    }

    @Override
    public IntentPlans.Claim begin(Scope owner, IntentPlans.State seed, UUID request, long expected,
            String kind, String requestJson, String rawMessage, boolean interrupt, Long conversationRevision) {
        return tx.execute(transaction -> beginInTransaction(owner, seed, request, expected, kind, requestJson,
                rawMessage, interrupt, conversationRevision));
    }

    private IntentPlans.Claim beginInTransaction(Scope owner, IntentPlans.State seed, UUID request, long expected,
            String kind, String requestJson, String rawMessage, boolean interrupt, Long conversationRevision) {
        lockConversation(owner, seed.conversationId());
        String digest = SkillRegistry.digest(kind + ":" + requestJson);
        var prior = jdbc.query("select plan_id,payload_digest,status from ai_orchestration_requests where conversation_id=? and request_id=?",
                (row, index) -> {
                    if (!digest.equals(row.getString("payload_digest")) || !seed.planId().equals(row.getObject("plan_id", UUID.class))) throw conflict("REQUEST_ID_CONFLICT");
                    return row.getString("status");
                }, seed.conversationId(), request);
        if (!prior.isEmpty()) return new IntentPlans.Claim(null, read(owner, seed.planId()), false, prior.getFirst());
        if (conversationRevision != null && !conversationRevision.equals(jdbc.queryForObject("select revision from ai_conversations where id=?", Long.class, seed.conversationId()))) throw conflict("CONVERSATION_REVISION_CHANGED");
        var rows = jdbc.query("select state_json from ai_orchestrations where id=? for update", (row, index) -> row.getString(1), seed.planId());
        IntentPlans.State state;
        if (rows.isEmpty()) {
            if (expected != 0 || !kind.equals("TURN")) throw conflict("PLAN_NOT_CREATED");
            if (!seed.steps().isEmpty() || !seed.facts().isEmpty() || !seed.reviews().isEmpty()) throw conflict("INVALID_PLAN_SEED");
            state = seed;
            jdbc.update("insert into ai_orchestrations(id,conversation_id,user_id,account_id,revision,state_json,expires_at,updated_at) values(?,?,?,?,0,?,?,?)",
                    seed.planId(), seed.conversationId(), owner.userId(), owner.accountId(), json(seed), time(seed.expiresAt()), now());
        } else state = read(owner, seed.planId());
        if (state.revision() != expected) throw conflict("REVISION_CHANGED");
        if (!state.expiresAt().isAfter(clock.instant())) throw new IntentPlans.Rejected(410, "PLAN_EXPIRED");
        var pending = jdbc.query("select request_id,lease_until from ai_orchestration_requests where plan_id=? and status='PENDING'",
                (row, index) -> new Pending(row.getObject(1, UUID.class), row.getTimestamp(2).toInstant()), seed.planId());
        for (var item : pending) {
            if (!interrupt && item.until().isAfter(clock.instant())) throw conflict("TURN_IN_PROGRESS");
            jdbc.update("update ai_orchestration_requests set status='UNKNOWN' where plan_id=? and request_id=?", seed.planId(), item.id());
            jdbc.update("update ai_conversation_requests set status='UNKNOWN' where conversation_id=? and request_id=?", state.conversationId(), item.id());
            append(state.conversationId(), item.id(), "REPLY", "Прерванный запрос сохранён; автоматического повтора нет.", seed.planId(), "UNKNOWN");
        }
        if (jdbc.queryForObject("select count(*) from ai_conversation_requests where conversation_id=? and status='PENDING'", Long.class, state.conversationId()) > 0) throw conflict("CONVERSATION_BUSY");
        var deadline = clock.instant().plus(lease);
        jdbc.update("insert into ai_conversation_requests(conversation_id,request_id,payload_hash,status,plan_id,lease_until) values(?,?,?,'PENDING',?,?)",
                state.conversationId(), request, digest, seed.planId(), time(deadline));
        boolean requirementAnswer = kind.equals("REQUIREMENTS_ANSWER");
        UUID message = append(state.conversationId(), request, kind.equals("TURN") || requirementAnswer ? "INPUT" : kind,
                rawMessage, seed.planId(), "PENDING");
        if (kind.equals("TURN")) {
            String title = ConversationRedaction.text(rawMessage).replaceAll("\\s+", " ").strip();
            if (title.length() > 64) title = title.substring(0, 64);
            jdbc.update("update ai_conversations set title=? where id=? and title='Новый разговор'", title, state.conversationId());
        }
        UUID primary = stable(request, "primary");
        UUID replan = stable(request, "replan");
        var metadata = validator.parse(requestJson).deepCopy();
        if (kind.equals("TURN")) ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).remove("message");
        if (requirementAnswer) ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).remove("answer");
        jdbc.update("""
                insert into ai_orchestration_requests(conversation_id,request_id,plan_id,request_kind,payload_digest,request_json,
                message_id,primary_call_id,replan_call_id,status,lease_until,answered_question_json) values(?,?,?,?,?,?,?,?,?,'PENDING',?,?)
                """, state.conversationId(), request, state.planId(), kind, digest, metadata.toString(), message, primary, replan, time(deadline),
                state.pendingQuestion() == null ? null : json(state.pendingQuestion()));
        var claimed = state.revision(state.revision() + 1);
        if (conversationRevision != null) jdbc.update("update ai_orchestration_requests set client_revision=? where plan_id=? and request_id=?", conversationRevision, state.planId(), request);
        write(owner, claimed, request);
        return new IntentPlans.Claim(new IntentPlans.Token(owner, state.conversationId(), state.planId(), request, message,
                claimed.revision(), primary, replan, rawMessage, state.pendingQuestion()), claimed, true, "PENDING");
    }

    @Override
    public IntentPlans.State read(Scope owner, UUID id) {
        var states = jdbc.query("""
                select p.state_json,p.schema_version from ai_orchestrations p join ai_conversations c on c.id=p.conversation_id
                where p.id=? and p.user_id=? and p.account_id=? and c.user_id=? and c.account_id=? and c.expires_at>?
                """, (row, index) -> {
                    int schema = row.getInt(2);
                    if (schema != 1 && schema != 2) throw IntentPlans.reject("UNSUPPORTED_PERSISTED_PLAN_SCHEMA");
                    var state = parse(row.getString(1), IntentPlans.State.class);
                    if ((schema == 2) != (state.execution() != null)) throw IntentPlans.reject("PERSISTED_GRAPH_SCHEMA_MISMATCH");
                    return state;
                }, id, owner.userId(), owner.accountId(), owner.userId(), owner.accountId(), now());
        if (states.isEmpty()) throw new IntentPlans.Rejected(404, "PLAN_UNAVAILABLE");
        return states.getFirst();
    }

    @Override
    public UUID conversation(Scope owner, UUID id) { return read(owner, id).conversationId(); }

    @Override
    public IntentPlans.State commit(IntentPlans.Token token, IntentPlans.State state, String reply, boolean terminal) {
        return tx.execute(transaction -> {
            var updated = terminal && collaboration != null ? arbitrateConsultationQuestion(token, state) : state;
            if (terminal && collaboration != null && updated == state) synchronizeRequirementQuestion(token, state);
            return commitInTransaction(token, updated, updated == state ? reply : updated.pendingQuestion().question().text(), terminal);
        });
    }

    private IntentPlans.State arbitrateConsultationQuestion(IntentPlans.Token token, IntentPlans.State state) {
        if (state.pendingQuestion() != null || !"ACTIVE".equals(state.status())
                || state.steps().stream().noneMatch(step -> "PREPARING".equals(step.status()))) return state;
        lockConversation(token.owner(), token.conversationId());
        var snapshot = collaboration.readInTransaction(token.owner(), token.planId());
        var waiting = snapshot.consultations().tasks().values().stream()
                .filter(task -> task.status() == com.voltcanary.aiagent.orchestration.collaboration.Consultations.Status.WAITING_USER
                        && task.assignment() != null && token.requestId().equals(task.assignment().originatingTurnId())
                        && task.request().inputRefs().isEmpty())
                .sorted(java.util.Comparator.comparing(com.voltcanary.aiagent.orchestration.collaboration.Consultations.Task::id))
                .toList();
        if (waiting.isEmpty() || snapshot.requirements().question() != null) return state;
        var eligibleSteps = state.steps().stream()
                .filter(step -> "CREATE_DASHBOARD".equals(step.intentId()) && "PREPARING".equals(step.status())
                        && step.reviewId() == null && step.receiptId() == null).toList();
        if (eligibleSteps.size() != 1) return state;
        var step = eligibleSteps.getFirst();
        String prefix = com.voltcanary.aiagent.orchestration.collaboration.NameConsultation.pathPrefix(step.stepId());
        var changes = new com.voltcanary.aiagent.orchestration.collaboration.RequirementChanges((type, value) ->
                validator.validate("collab.VclLiteral", value));
        var arbiter = new com.voltcanary.aiagent.orchestration.collaboration.QuestionArbiter(changes);
        UUID questionId = stable(token.requestId(), "consultation-question");
        Instant now = clock.instant();
        Instant expiry = now.plusSeconds(600).isBefore(state.expiresAt()) ? now.plusSeconds(600) : state.expiresAt();
        var decision = arbiter.decide(snapshot.requirements(), waiting, Set.of(), questionId, token.requestId(), now, expiry);
        var question = decision.requirements().question();
        if (question == null || question.slots().size() != 1) return state;
        var slot = decision.requirements().slots().get(question.slots().getFirst());
        if (!slot.path().startsWith(prefix) || !slot.path().endsWith("/name") || !"text-v1".equals(slot.schemaId())) return state;
        collaboration.updateInTransaction(token, snapshot.revision(), "consultation-question:" + token.requestId(),
                SkillRegistry.digest(json(question)), (before, time) -> {
                    if (!question.expiresAt().isAfter(time)) throw conflict("QUESTION_EXPIRED");
                    return new CollaborationStore.State(before.revision() + 1, decision.requirements(), before.consultations());
                }, (before, next) -> {});
        var pending = new PendingQuestion(questionId.toString(), stable(token.requestId(), "REPLY").toString(), step.stepId(),
                token.revision() + 1, new Question(question.text(), List.of("name"), List.of()));
        return IntentPlans.replace(state, IntentPlans.step(step, "WAITING_INPUT", null, null, null), "WAITING_INPUT", pending);
    }

    private void synchronizeRequirementQuestion(IntentPlans.Token token, IntentPlans.State state) {
        lockConversation(token.owner(), token.conversationId());
        var current = read(token.owner(), token.planId());
        var pending = state.pendingQuestion();
        if (pending != null && Objects.equals(token.answeredQuestion(), pending)) return;
        boolean supported = pending != null && pending.stepId() != null && pending.question().missingFields().equals(List.of("name"))
                && (pending.question().choices() == null || pending.question().choices().isEmpty())
                && state.steps().stream().anyMatch(step -> pending.stepId().equals(step.stepId())
                    && Set.of("CREATE_DASHBOARD", "CREATE_DEVICE").contains(step.intentId()));
        if (!supported && current.pendingQuestion() == null && token.answeredQuestion() == null) return;
        var snapshot = collaboration.readInTransaction(token.owner(), token.planId());
        if (!supported && snapshot.requirements().question() == null) return;
        String key = "question:" + token.requestId();
        collaboration.updateInTransaction(token, snapshot.revision(), key, SkillRegistry.digest(json(state)), (before, now) -> {
            var requirements = before.requirements();
            if (supported) {
                UUID questionId = UUID.fromString(pending.questionId());
                String path = com.voltcanary.aiagent.orchestration.collaboration.NameConsultation.pathPrefix(pending.stepId()) + questionId + "/name";
                var slot = new Requirements.Slot(questionId, path, "text-v1", true,
                        Requirements.Status.UNKNOWN, null, Set.of(token.messageId()), Set.of(), Set.of(), 0,
                        Requirements.Origin.MODEL_PROPOSED);
                var changes = new com.voltcanary.aiagent.orchestration.collaboration.RequirementChanges((schema, value) ->
                        validator.validate("collab.VclLiteral", value));
                requirements = changes.propose(requirements, requirements.revision(), token.requestId(), List.of(slot), Set.of(token.messageId()));
                var expires = now.plusSeconds(600);
                if (state.expiresAt().isBefore(expires)) expires = state.expiresAt();
                requirements = changes.question(requirements, new Requirements.Question(questionId, 0, requirements.revision(),
                        List.of(slot.id()), pending.question().text(), List.of(), Set.of(), expires), now);
            } else if (requirements.question() != null) {
                requirements = new Requirements.Snapshot(requirements.version(), requirements.planId(), requirements.revision(),
                        requirements.slots(), null, requirements.events());
            }
            return new CollaborationStore.State(before.revision() + 1, requirements, before.consultations());
        }, (before, next) -> {});
    }

    /** The caller supplies the affected step IDs from the reviewed requirement bindings. */
    public IntentPlans.State commitRequirements(JdbcCollaborationStore collaboration, IntentPlans.Token token,
            long expectedCollaboration, String requestKey, String digest, CollaborationStore.Mutation mutation,
            Set<String> affectedSteps, String reply) {
        return commitRequirements(collaboration, token, expectedCollaboration, requestKey, digest, mutation, affectedSteps, reply, "TURN");
    }

    public IntentPlans.State submitRequirementAnswer(JdbcCollaborationStore collaboration, RequirementAnswers answers,
            Scope owner, UUID planId, JsonNode body) {
        validator.validate("collab.RequirementsAnswer", body);
        if (!planId.toString().equals(body.get("planId").textValue())) throw Requirements.invalid("REQUIREMENT_PLAN_CHANGED");
        UUID request = UUID.fromString(body.get("requestId").textValue());
        var answer = body.get("answer");
        String display = answer.get("kind").textValue().equals("TEXT") ? answer.get("text").textValue() : "Ответ на уточнение";
        return tx.execute(transaction -> {
            var current = read(owner, planId);
            lockConversation(owner, current.conversationId());
            current = read(owner, planId);
            if (!current.expiresAt().isAfter(clock.instant())) throw new IntentPlans.Rejected(410, "PLAN_EXPIRED");
            var claim = beginInTransaction(owner, current, request, current.revision(), "REQUIREMENTS_ANSWER",
                    json(body), display, false, null);
            if (!claim.claimed()) {
                if (!"COMPLETED".equals(claim.requestStatus())) throw conflict("REQUIREMENT_ANSWER_NOT_COMPLETED");
                return claim.state();
            }
            var snapshot = collaboration.readInTransaction(owner, planId);
            return commitRequirementAnswerInTransaction(collaboration, answers, claim.token(), snapshot.revision(), body, Set.of());
        });
    }

    private IntentPlans.State commitRequirementAnswerInTransaction(JdbcCollaborationStore collaboration, RequirementAnswers answers,
            IntentPlans.Token token, long expectedCollaboration, JsonNode body, Set<String> affectedSteps) {
        String kind = "REQUIREMENTS_ANSWER";
        String inputDigest = SkillRegistry.digest(kind + ":" + json(body));
        return commitRequirementsInTransaction(collaboration, token, expectedCollaboration, token.requestId().toString(), inputDigest, (before, now) -> {
            var input = jdbc.queryForObject("""
                    select r.payload_digest,r.answered_question_json from ai_orchestration_requests r
                    join ai_conversation_messages m on m.id=r.message_id and m.request_id=r.request_id
                        and m.plan_id=r.plan_id and m.conversation_id=r.conversation_id
                    where r.plan_id=? and r.request_id=? and m.id=? and m.role='user' and m.kind='INPUT'
                    """, (row, index) -> new RequirementInput(row.getString(1), row.getString(2)),
                    token.planId(), token.requestId(), token.messageId());
            if (input == null || !inputDigest.equals(input.digest())) throw conflict("REQUIREMENT_ANSWER_INPUT_CHANGED");
            if (input.questionJson() == null || !Objects.equals(token.answeredQuestion(), parse(input.questionJson(), PendingQuestion.class))) {
                throw conflict("QUESTION_CHANGED");
            }
            return new CollaborationStore.State(before.revision() + 1,
                    answers.apply(before.requirements(), body, token, now), before.consultations());
        }, affectedSteps, "Ответ на уточнение сохранён.", kind);
    }

    private record RequirementInput(String digest, String questionJson) { }

    private IntentPlans.State commitRequirements(JdbcCollaborationStore collaboration, IntentPlans.Token token,
            long expectedCollaboration, String requestKey, String digest, CollaborationStore.Mutation mutation,
            Set<String> affectedSteps, String reply, String kind) {
        return tx.execute(transaction -> commitRequirementsInTransaction(collaboration, token, expectedCollaboration,
                requestKey, digest, mutation, affectedSteps, reply, kind));
    }

    private IntentPlans.State commitRequirementsInTransaction(JdbcCollaborationStore collaboration, IntentPlans.Token token,
            long expectedCollaboration, String requestKey, String digest, CollaborationStore.Mutation mutation,
            Set<String> affectedSteps, String reply, String kind) {
        var stepIds = Set.copyOf(affectedSteps);
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 147
                || digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw Requirements.invalid("INVALID_COLLABORATION_REQUEST");
        }
        String key = "requirements:" + requestKey;
        String boundDigest = SkillRegistry.digest(json(List.of(token.requestId(), digest, expectedCollaboration, stepIds.stream().sorted().toList(),
                Objects.requireNonNullElse(reply, ""))));
        lockConversation(token.owner(), token.conversationId());
        var current = read(token.owner(), token.planId());
        if (!current.conversationId().equals(token.conversationId())) throw conflict("REQUIREMENT_PLAN_CHANGED");
        var completed = jdbc.query("""
                select e.request_digest from ai_orchestration_collaboration_events e
                join ai_orchestration_requests r on r.plan_id=e.plan_id and r.request_id=e.turn_id
                where e.plan_id=? and e.request_key=? and r.request_id=? and r.message_id=? and r.status='COMPLETED'
                """, (row, index) -> row.getString(1), token.planId(), key, token.requestId(), token.messageId());
        if (!completed.isEmpty()) {
            if (!boundDigest.equals(completed.getFirst())) throw conflict("COLLABORATION_REQUEST_KEY_CONFLICT");
            if (!current.expiresAt().isAfter(clock.instant())) throw new IntentPlans.Rejected(410, "PLAN_EXPIRED");
            return current;
        }
        var kinds = jdbc.query("select request_kind from ai_orchestration_requests where plan_id=? and request_id=?",
                (row, index) -> row.getString(1), token.planId(), token.requestId());
        if (!kinds.equals(List.of(kind))) throw conflict("REQUIREMENT_TURN_REQUIRED");
        collaboration.updateInTransaction(token, expectedCollaboration, key, boundDigest, mutation, (before, next) -> {
            var requirements = next.requirements();
            var question = requirements.question();
            var pending = requirementQuestion(token, requirements);
            var previous = before.requirements().question();
            if (current.pendingQuestion() != null && (previous == null
                    || !current.pendingQuestion().questionId().equals(previous.id().toString()))) {
                throw conflict("ROOT_QUESTION_ALREADY_PENDING");
            }
            var reviews = new HashMap<>(current.reviews());
            var outputs = new HashMap<>(current.outputs());
            var focus = current.focus();
            for (String id : stepIds) {
                var step = current.step(id);
                if (!Set.of("BLOCKED", "READY", "WAITING_DATA", "WAITING_INPUT", "WAITING_CONFIRMATION").contains(step.status())) {
                    throw conflict("REQUIREMENT_STEP_NOT_EDITABLE");
                }
                var review = reviews.remove(id);
                if (review != null) {
                    invalidatePendingReview(token, review.reviewId());
                    if (focus != null && Objects.equals(focus.reviewId(), review.reviewId().toString())) {
                        focus = new Focus(
                                focus.deviceRef(), focus.dashboardRef(), null, focus.stepId(), focus.origin());
                    }
                }
                outputs.remove(id);
            }
            var steps = current.steps().stream().map(step -> {
                if (kind.equals("REQUIREMENTS_ANSWER") && current.pendingQuestion() != null
                        && step.stepId().equals(current.pendingQuestion().stepId())) {
                    return bindAnsweredName(step, previous, requirements);
                }
                return stepIds.contains(step.stepId())
                        ? IntentPlans.step(step, "WAITING_INPUT", null, step.receiptId(), null) : step;
            }).toList();
            var state = current.revise(current.revision(), pending == null ? "ACTIVE" : "WAITING_INPUT",
                    steps, current.goals(), current.facts(), pending, focus, current.skills(), reviews, outputs);
            commitInTransaction(token, state, question == null ? reply : question.text(), true);
        });
        return read(token.owner(), token.planId());
    }

    private static StepState bindAnsweredName(StepState step, Requirements.Question previous, Requirements.Snapshot requirements) {
        if (previous == null || previous.slots().size() != 1 || !"WAITING_INPUT".equals(step.status())
                || step.reviewId() != null || step.receiptId() != null) {
            throw conflict("REQUIREMENT_STEP_CHANGED");
        }
        var slot = requirements.slots().get(previous.slots().getFirst());
        String prefix = com.voltcanary.aiagent.orchestration.collaboration.NameConsultation.pathPrefix(step.stepId());
        if (slot == null || slot.status() != Requirements.Status.CONFIRMED || !slot.path().startsWith(prefix)
                || !slot.path().endsWith("/name") || !"text-v1".equals(slot.schemaId())) throw conflict("REQUIREMENT_STEP_CHANGED");
        var name = slot.value().path("value");
        int limit = "CREATE_DASHBOARD".equals(step.intentId()) ? 80 : 255;
        if (!name.isTextual() || name.textValue().isBlank() || name.textValue().length() > limit) {
            throw Requirements.invalid("REQUIREMENT_NAME_INVALID");
        }
        var inputs = new java.util.ArrayList<>(step.inputs().stream().filter(input -> !"name".equals(input.field())).toList());
        inputs.add(new Input("name", new BindingLiteral("literal", name)));
        return new StepState(step.stepId(), step.intentId(), step.skillVersion(), step.schemaVersion(), step.revision() + 1,
                step.dependsOn(), List.copyOf(inputs), step.goalIds(), "READY", null, null, null);
    }

    private PendingQuestion requirementQuestion(IntentPlans.Token token, Requirements.Snapshot requirements) {
        var question = requirements.question();
        if (question == null) return null;
        // Typed choices belong to the collaboration contract, not legacy assistant input bindings.
        var fields = question.slots().stream().map(UUID::toString).toList();
        return new PendingQuestion(question.id().toString(), stable(token.requestId(), "REPLY").toString(), null,
                token.revision() + 1, new Question(question.text(), fields, List.of()));
    }

    private void invalidatePendingReview(IntentPlans.Token token, UUID review) {
        int updated = jdbc.update("""
                update ai_reviewed_plans set status='SUPERSEDED',updated_at=?
                where id=? and user_id=? and account_id=? and status='READY'
                """, now(), review, token.owner().userId(), token.owner().accountId());
        if (updated != 1) throw conflict("REQUIREMENT_REVIEW_NOT_READY");
    }

    private IntentPlans.State commitInTransaction(IntentPlans.Token token, IntentPlans.State state, String reply, boolean terminal) {
        lockConversation(token.owner(), token.conversationId());
        var current = read(token.owner(), token.planId());
        if (current.revision() != token.revision() || !state.planId().equals(current.planId())
                || !state.conversationId().equals(current.conversationId())) throw conflict("LATE_OR_STALE_RESULT");
        if (current.execution() != null && (state.execution() == null
                || !current.execution().engine().equals(state.execution().engine())
                || !current.execution().definition().equals(state.execution().definition()))) {
            throw conflict("GRAPH_ENGINE_VERSION_CHANGED");
        }
        var pending = jdbc.query("""
                select r.status from ai_orchestration_requests r join ai_conversation_requests c
                on c.conversation_id=r.conversation_id and c.request_id=r.request_id
                where r.plan_id=? and r.request_id=? and r.status='PENDING' and c.status='PENDING' and r.lease_until>?
                """, (row, index) -> row.getString(1), token.planId(), token.requestId(), now());
        if (pending.isEmpty()) throw conflict("TURN_NO_LONGER_OWNED");
        var next = state.revision(current.revision() + 1);
        if (next.wire() != null) validator.validate("planState", mapper.valueToTree(next.wire()));
        write(token.owner(), next, terminal ? null : token.requestId());
        for (var entry : current.reviews().entrySet()) {
            var replacement = next.reviews().get(entry.getKey());
            if (replacement != null && !replacement.reviewId().equals(entry.getValue().reviewId())) {
                jdbc.update("update ai_reviewed_plans set status='SUPERSEDED' where id=? and user_id=? and account_id=? and status='READY'",
                        entry.getValue().reviewId(), token.owner().userId(), token.owner().accountId());
            }
        }
        for (var confirmed : next.facts()) {
            if (confirmed.output() == null || current.facts().stream().anyMatch(fact -> fact.fact().factId().equals(confirmed.fact().factId()))) continue;
            if (jdbc.queryForObject("select count(*) from ai_orchestration_resource_events where conversation_id=? and fact_id=?",
                    Long.class, token.conversationId(), confirmed.fact().factId()) > 0) continue;
            var output = confirmed.output();
            var step = next.steps().stream().filter(item -> output.reviewId().toString().equals(item.reviewId())).findFirst()
                    .orElseThrow(() -> IntentPlans.reject("FACT_STEP_UNAVAILABLE"));
            Long sequence = jdbc.queryForObject("select coalesce(max(sequence),0)+1 from ai_orchestration_resource_events where conversation_id=?", Long.class, token.conversationId());
            jdbc.update("""
                    insert into ai_orchestration_resource_events(conversation_id,sequence,source_message_id,step_id,intent_id,fact_id,review_id,action_id,output_key,output_type,output_pointer,resource_kind)
                    values(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, token.conversationId(), sequence, UUID.fromString(confirmed.fact().sourceMessageId()), step.stepId(), step.intentId(),
                    confirmed.fact().factId(), output.reviewId(), output.actionId(), output.key(), output.type(), output.pointer(), output.resourceKind());
            // A longer conversation retention must not leave live resource links pointing at purged receipts.
            jdbc.update("""
                    update ai_reviewed_plans set retain_until=case
                    when retain_until<(select expires_at from ai_conversations where id=?)
                    then (select expires_at from ai_conversations where id=?) else retain_until end
                    where id=? and user_id=? and account_id=?
                    """, token.conversationId(), token.conversationId(), output.reviewId(), token.owner().userId(), token.owner().accountId());
        }
        if (terminal) {
            jdbc.update("update ai_orchestration_requests set status='COMPLETED' where plan_id=? and request_id=?", token.planId(), token.requestId());
            jdbc.update("update ai_conversation_requests set status='COMPLETED' where conversation_id=? and request_id=?", token.conversationId(), token.requestId());
            append(token.conversationId(), token.requestId(), "REPLY", reply, token.planId(), next.status());
        }
        return next;
    }

    @Override
    public Set<String> sourceMessages(Scope owner, UUID conversation) {
        lockRead(owner, conversation);
        return Set.copyOf(jdbc.query("select id from ai_conversation_messages where conversation_id=? and kind='INPUT' and role='user'",
                (row, index) -> row.getObject(1, UUID.class).toString(), conversation));
    }

    @Override
    public List<IntentPlans.ResourceEvent> resources(Scope owner, UUID conversation, long after, int limit) {
        lockRead(owner, conversation);
        if (after < 0 || limit < 1 || limit > 100) throw new IntentPlans.Rejected(400, "INVALID_PAGE");
        return jdbc.query("select * from ai_orchestration_resource_events where conversation_id=? and sequence>? order by sequence limit ?",
                (row, index) -> new IntentPlans.ResourceEvent(row.getLong("sequence"), row.getObject("source_message_id", UUID.class),
                        row.getString("step_id"), row.getString("intent_id"), row.getString("fact_id"), new IntentPlans.Output(row.getString("output_key"), row.getString("output_type"),
                        row.getObject("review_id", UUID.class), row.getObject("action_id", UUID.class), row.getString("output_pointer"), row.getString("resource_kind"))), conversation, after, limit);
    }

    @Override
    public IntentPlans.SourceMessage latestInput(Scope owner, UUID plan) {
        var state = read(owner, plan);
        var messages = jdbc.query("select id,display_text from ai_conversation_messages where conversation_id=? and plan_id=? and kind='INPUT' and role='user' order by sequence desc limit 1",
                (row, index) -> new IntentPlans.SourceMessage(row.getObject(1, UUID.class), row.getString(2)), state.conversationId(), plan);
        if (messages.isEmpty()) throw IntentPlans.reject("SOURCE_MESSAGE_UNAVAILABLE");
        return messages.getFirst();
    }

    @Override
    public String request(Scope owner, UUID plan, UUID request) {
        read(owner, plan);
        var rows = jdbc.query("select r.request_json,r.request_kind,m.display_text from ai_orchestration_requests r join ai_conversation_messages m on m.id=r.message_id where r.plan_id=? and r.request_id=?",
                (row, index) -> {
                    var document = validator.parse(row.getString(1));
                    if (row.getString(2).equals("TURN")) ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("message", row.getString(3));
                    return document.toString();
                }, plan, request);
        if (rows.isEmpty()) throw new IntentPlans.Rejected(404, "TURN_UNAVAILABLE");
        return rows.getFirst();
    }

    private void write(Scope owner, IntentPlans.State state, UUID activeRequest) {
        jdbc.update("update ai_orchestrations set revision=?,state_json=?,schema_version=?,active_request_id=?,updated_at=? where id=? and user_id=? and account_id=?",
                state.revision(), json(state), state.execution() == null ? 1 : 2, activeRequest, now(), state.planId(), owner.userId(), owner.accountId());
    }

    private UUID append(UUID conversation, UUID request, String kind, String text, UUID plan, String outcome) {
        var existing = jdbc.query("select id from ai_conversation_messages where conversation_id=? and request_id=? and kind=?",
                (row, index) -> row.getObject(1, UUID.class), conversation, request, kind);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID id = stable(request, kind);
        Long sequence = jdbc.queryForObject("select revision+1 from ai_conversations where id=?", Long.class, conversation);
        String safe = ConversationRedaction.text(text == null ? "" : text);
        jdbc.update("""
                insert into ai_conversation_messages(id,conversation_id,sequence,request_id,role,kind,display_text,redacted,plan_id,turn_id,outcome,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, conversation, sequence, request, kind.equals("REPLY") ? "assistant" : "user", kind, safe,
                ConversationRedaction.marked(text, safe), plan, request, outcome, now());
        jdbc.update("update ai_conversations set revision=?,updated_at=? where id=?", sequence, now(), conversation);
        return id;
    }

    private void lockConversation(Scope owner, UUID conversation) {
        var rows = jdbc.query("select id from ai_conversations where id=? and user_id=? and account_id=? and expires_at>? for update",
                (row, index) -> row.getObject(1, UUID.class), conversation, owner.userId(), owner.accountId(), now());
        if (rows.isEmpty()) throw new IntentPlans.Rejected(404, "CONVERSATION_UNAVAILABLE");
    }
    private void lockRead(Scope owner, UUID conversation) {
        Long count = jdbc.queryForObject("select count(*) from ai_conversations where id=? and user_id=? and account_id=? and expires_at>?",
                Long.class, conversation, owner.userId(), owner.accountId(), now());
        if (count == null || count != 1) throw new IntentPlans.Rejected(404, "CONVERSATION_UNAVAILABLE");
    }
    private record Pending(UUID id, java.time.Instant until) { }
    public static UUID stable(UUID request, String kind) { return UUID.nameUUIDFromBytes((request + ":" + kind).getBytes(StandardCharsets.UTF_8)); }
    private Timestamp now() { return time(clock.instant()); }
    private static Timestamp time(java.time.Instant instant) { return Timestamp.from(instant); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("ORCHESTRATION_ENCODING"); }
    }
    private <T> T parse(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("ORCHESTRATION_DECODING"); }
    }
}
