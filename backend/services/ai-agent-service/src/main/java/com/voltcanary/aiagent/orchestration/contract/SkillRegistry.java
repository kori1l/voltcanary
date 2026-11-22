package com.voltcanary.aiagent.orchestration.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.IntentSummary;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Manifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable deployment-owned packages. Model output can never register skills or grant capabilities. */
public final class SkillRegistry {

    public record Package(
        JsonNode manifest,
        Map<String, JsonNode> schemas,
        Set<String> requires,
        Map<String, String> loaderSchemas,
        Set<String> plannerFields
    ) {
        /** Existing fixture/legacy packages retain their explicitly registered planner parameters. */
        public Package(
            JsonNode manifest,
            Map<String, JsonNode> schemas,
            Set<String> requires,
            Map<String, String> loaderSchemas
        ) {
            this(manifest, schemas, requires, loaderSchemas, null);
        }

        public Package {
            manifest = manifest.deepCopy();
            var copies = new LinkedHashMap<String, JsonNode>();
            schemas.forEach((key, value) -> copies.put(key, value.deepCopy()));
            schemas = Map.copyOf(copies);
            requires = Set.copyOf(requires);
            loaderSchemas = Map.copyOf(loaderSchemas);
            if (plannerFields == null) {
                var fields = new java.util.HashSet<String>();
                manifest
                    .path("parameterRules")
                    .forEach(rule -> fields.add(rule.path("field").asText()));
                plannerFields = Set.copyOf(fields);
            } else plannerFields = Set.copyOf(plannerFields);
        }
    }

    private record Entry(
        Manifest manifest,
        Map<String, JsonNode> schemas,
        Set<String> requires,
        Map<String, String> loaderSchemas,
        Set<String> plannerFields
    ) {}

    private final Map<String, Entry> entries;
    private final ProtocolValidator validator;

    public SkillRegistry(ProtocolValidator validator, List<Package> packages) {
        this.validator = validator;
        var registered = new LinkedHashMap<String, Entry>();
        for (var pkg : packages) {
            var manifest = validator.read("manifest", pkg.manifest(), Manifest.class);
            if (
                !manifest.instructions().digest().equals(digest(manifest.instructions().text()))
            ) reject("SKILL_DIGEST");
            for (String id : List.of(
                manifest.inputSchemaId(),
                manifest.outputSchemaId(),
                manifest.commandSchemaId()
            )) {
                if (!pkg.schemas().containsKey(id)) reject("UNKNOWN_SCHEMA");
            }
            if (!manifest.protocolVersions().contains("assistant-v2")) reject("PROTOCOL_VERSION");
            for (String loader : manifest.loaderIds()) {
                if (
                    !pkg.loaderSchemas().containsKey(loader) ||
                    !pkg.schemas().containsKey(pkg.loaderSchemas().get(loader))
                ) reject("UNKNOWN_LOADER_SCHEMA");
            }
            if (
                manifest
                    .parameterRules()
                    .stream()
                    .map(rule -> rule.field())
                    .distinct()
                    .count() != manifest.parameterRules().size()
            ) reject("DUPLICATE_FIELD");
            if (
                !manifest
                    .parameterRules()
                    .stream()
                    .map(rule -> rule.field())
                    .collect(java.util.stream.Collectors.toSet())
                    .containsAll(pkg.plannerFields())
            ) reject("UNKNOWN_PLANNER_FIELD");
            if (
                registered.putIfAbsent(
                    manifest.intentId(),
                    new Entry(
                        manifest,
                        pkg.schemas(),
                        pkg.requires(),
                        pkg.loaderSchemas(),
                        pkg.plannerFields()
                    )
                ) != null
            ) reject("DUPLICATE_INTENT");
        }
        entries = Map.copyOf(registered);
    }

    public List<IntentSummary> catalogue(Set<String> authorizedIntents, Set<String> capabilities) {
        var summaries = entries
            .values()
            .stream()
            .filter(
                entry ->
                    authorizedIntents.contains(entry.manifest().intentId()) &&
                    capabilities.containsAll(entry.requires())
            )
            .sorted(java.util.Comparator.comparing(entry -> entry.manifest().intentId()))
            .map(entry ->
                new IntentSummary(
                    entry.manifest().intentId(),
                    catalogueDescription(entry),
                    entry.plannerFields().stream().sorted().toList(),
                    entry
                        .manifest()
                        .outputs()
                        .stream()
                        .map(output ->
                            new AssistantProtocol.IntentSummaryOutputsItem(
                                output.key(),
                                output.type()
                            )
                        )
                        .toList(),
                    entry.requires().stream().sorted().toList()
                )
            )
            .toList();
        if (summaries.size() > 32) reject("CATALOGUE_REQUIRES_PAGING");
        return summaries;
    }

    private String catalogueDescription(Entry entry) {
        var constraints = new java.util.ArrayList<String>();
        entry
            .schemas()
            .get(entry.manifest().inputSchemaId())
            .path("properties")
            .fields()
            .forEachRemaining(field -> {
                if (!entry.plannerFields().contains(field.getKey())) return;
                if (field.getValue().has("enum")) constraints.add(
                    field.getKey() + "=" + field.getValue().path("enum")
                );
                else if (field.getValue().has("maxLength")) constraints.add(
                    field.getKey() + ".maxLength=" + field.getValue().path("maxLength")
                );
            });
        String description =
            entry.manifest().description() +
            (constraints.isEmpty() ? "" : " Input constraints: " + String.join("; ", constraints));
        if (description.length() > 1000) reject("CATALOGUE_CONSTRAINTS_REQUIRE_NARROWING");
        return description;
    }

    public Manifest require(
        String intent,
        String skill,
        String version,
        Set<String> authorizedIntents,
        Set<String> capabilities
    ) {
        var entry = entry(intent, authorizedIntents, capabilities);
        if (
            !entry.manifest().skillId().equals(skill) ||
            !entry.manifest().skillVersion().equals(version)
        ) reject("UNKNOWN_SKILL_VERSION");
        return entry.manifest();
    }

    public Manifest manifest(
        String intent,
        Set<String> authorizedIntents,
        Set<String> capabilities
    ) {
        return entry(intent, authorizedIntents, capabilities).manifest();
    }

    public void validatePlanner(
        JsonNode response,
        Set<String> authorizedIntents,
        Set<String> capabilities
    ) {
        validator.validate("plannerResponse", response);
        for (var step : response.path("steps")) {
            var entry = entry(step.path("intentId").asText(), authorizedIntents, capabilities);
            for (var input : step.path("inputs")) {
                String field = input.path("field").asText();
                if (!entry.plannerFields().contains(field)) reject("PLANNER_PARAMETER_NOT_ALLOWED");
                if (
                    input.at("/binding/kind").asText().equals("literal")
                ) validator.validateRegistered(
                    entry
                        .schemas()
                        .get(entry.manifest().inputSchemaId())
                        .path("properties")
                        .path(field),
                    input.at("/binding/value")
                );
            }
        }
    }

    /** In-flight policies must not mix skill versions/instructions across deployments. */
    public String versionDigest() {
        return versionDigest(entries.values().stream());
    }

    /** Disabled optional packages cannot change a legacy turn's pinned policy bytes. */
    public String versionDigest(Set<String> authorizedIntents, Set<String> capabilities) {
        return versionDigest(
            entries
                .values()
                .stream()
                .filter(
                    entry ->
                        authorizedIntents.contains(entry.manifest().intentId()) &&
                        capabilities.containsAll(entry.requires())
                )
        );
    }

    private String versionDigest(java.util.stream.Stream<Entry> selected) {
        return digest(
            selected
                .map(
                    entry ->
                        entry.manifest().intentId() +
                        ":" +
                        entry.manifest().skillVersion() +
                        ":" +
                        entry.manifest().description() +
                        ":" +
                        entry.manifest().instructions().digest() +
                        ":" +
                        String.join(",", entry.plannerFields().stream().sorted().toList())
                )
                .sorted()
                .collect(java.util.stream.Collectors.joining("\n"))
        );
    }

    /** Origins are assigned by the caller from server events, never taken from model claims. */
    public void validateParameters(
        String intent,
        JsonNode values,
        String origin,
        Set<String> authorizedIntents,
        Set<String> capabilities
    ) {
        if (
            !Set.of("USER_EXPLICIT", "MODEL_PROPOSED", "USER_CONFIRMED", "DOMAIN_RECEIPT").contains(
                origin
            )
        ) reject("PARAMETER_ORIGIN");
        var entry = entry(intent, authorizedIntents, capabilities);
        validator.validateRegistered(entry.schemas().get(entry.manifest().inputSchemaId()), values);
        var rules = entry.manifest().parameterRules();
        values.fieldNames().forEachRemaining(field -> {
            var rule = rules
                .stream()
                .filter(item -> item.field().equals(field))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_PARAMETER"));
            var value = values.get(field);
            if (value.isNull() && !rule.nullable()) reject("NULL_PARAMETER");
            if (
                value.isTextual() &&
                rule.maxLength() != null &&
                value.asText().length() > rule.maxLength()
            ) reject("PARAMETER_LENGTH");
            if (
                !value.isNull() &&
                !rule.allowedValues().isEmpty() &&
                !rule.allowedValues().contains(value)
            ) reject("PARAMETER_VALUE");
            if (
                origin.equals("MODEL_PROPOSED") &&
                !rule.proposalPolicy().equals("MODEL_MAY_PROPOSE")
            ) reject("PARAMETER_ORIGIN");
            if (
                rule.proposalPolicy().equals("DOMAIN_ONLY") && !origin.equals("DOMAIN_RECEIPT")
            ) reject("PARAMETER_ORIGIN");
        });
        for (var rule : rules)
            if (rule.required() && !values.has(rule.field())) reject("MISSING_PARAMETER");
    }

    public void validateStep(
        JsonNode request,
        JsonNode response,
        Set<String> authorizedIntents,
        Set<String> capabilities
    ) {
        var manifest = validateStepRequest(request, authorizedIntents, capabilities);
        validator.validate("stepResponse", response);
        var entry = entries.get(manifest.intentId());
        var operations = "PREPARED".equals(response.path("outcome").asText())
            ? response.at("/command/payload/operations")
            : null;
        validator.validateOperationDocument(
            entry.schemas().get(manifest.outputSchemaId()),
            response,
            entry.schemas().get(manifest.commandSchemaId()),
            operations
        );
        if (response.path("outcome").asText().equals("QUESTION")) {
            for (var field : response.path("question").path("missingFields")) {
                if (
                    manifest
                        .parameterRules()
                        .stream()
                        .noneMatch(rule -> rule.field().equals(field.asText()))
                ) reject("UNKNOWN_QUESTION_FIELD");
            }
            for (var choice : response.path("question").path("choices"))
                for (var binding : choice.path("bindings")) {
                    if (
                        manifest
                            .parameterRules()
                            .stream()
                            .noneMatch(rule -> rule.field().equals(binding.path("field").asText()))
                    ) reject("UNKNOWN_QUESTION_FIELD");
                }
        }
        if (response.path("outcome").asText().equals("PREPARED")) {
            var command = response.path("command");
            if (
                !command.path("schemaId").asText().equals(manifest.commandSchemaId()) ||
                !command.path("schemaVersion").asText().equals(manifest.skillVersion())
            ) reject("UNKNOWN_COMMAND_SCHEMA");
            validator.validateRegistered(
                entry.schemas().get(manifest.commandSchemaId()),
                command.path("payload")
            );
        }
        if (response.path("outcome").asText().equals("NEED_CONTEXT")) {
            for (var load : response.path("requests")) {
                String id = load.path("argumentsSchemaId").asText();
                if (
                    !manifest.loaderIds().contains(load.path("loaderId").asText()) ||
                    !id.equals(entry.loaderSchemas().get(load.path("loaderId").asText()))
                ) reject("UNKNOWN_LOADER");
                validator.validateRegistered(entry.schemas().get(id), load.path("arguments"));
            }
        }
    }

    public Manifest validateStepRequest(
        JsonNode request,
        Set<String> authorizedIntents,
        Set<String> capabilities
    ) {
        validator.validate("stepRequest", request);
        var manifest = require(
            request.path("intentId").asText(),
            request.at("/skill/skillId").asText(),
            request.at("/skill/skillVersion").asText(),
            authorizedIntents,
            capabilities
        );
        if (
            !request.path("responseSchemaId").asText().equals(manifest.outputSchemaId()) ||
            !request.at("/instructions/digest").asText().equals(manifest.instructions().digest()) ||
            !request.at("/instructions/text").asText().equals(manifest.instructions().text())
        ) reject("SKILL_CONTRACT_MISMATCH");
        if (
            request.path("context").toString().getBytes(StandardCharsets.UTF_8).length >
            manifest.maxContextBytes()
        ) reject("CONTEXT_INSUFFICIENT");
        if (request.hasNonNull("contract")) {
            var contract = request.path("contract");
            if (
                !validator
                    .parse(contract.path("parameterRules").toString())
                    .equals(
                        validator.parse(
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                .valueToTree(manifest.parameterRules())
                                .toString()
                        )
                    ) ||
                !contract
                    .path("inputSchema")
                    .equals(inputSchema(manifest.intentId(), authorizedIntents, capabilities)) ||
                !contract
                    .path("commandSchema")
                    .equals(commandSchema(manifest.intentId(), authorizedIntents, capabilities))
            ) reject("SKILL_PARAMETER_CONTRACT_MISMATCH");
            var seen = new java.util.HashSet<String>();
            for (var loader : contract.path("loaders")) {
                String id = loader.path("loaderId").asText();
                String schemaId = loader.path("argumentsSchemaId").asText();
                if (
                    !seen.add(id) ||
                    !loader
                        .path("argumentsSchema")
                        .equals(
                            loaderSchema(
                                manifest.intentId(),
                                id,
                                schemaId,
                                authorizedIntents,
                                capabilities
                            )
                        )
                ) reject("SKILL_LOADER_CONTRACT_MISMATCH");
            }
            if (!seen.equals(Set.copyOf(manifest.loaderIds()))) reject(
                "SKILL_LOADER_CONTRACT_MISMATCH"
            );
            if (
                request.toString().getBytes(StandardCharsets.UTF_8).length >
                manifest.maxContextBytes()
            ) reject("CONTEXT_INSUFFICIENT");
        }
        return manifest;
    }

    public JsonNode responseProjection(
        String intent,
        Set<String> authorized,
        Set<String> capabilities
    ) {
        var entry = entry(intent, authorized, capabilities);
        return entry.schemas().get(entry.manifest().outputSchemaId()).deepCopy();
    }

    public JsonNode inputSchema(String intent, Set<String> authorized, Set<String> capabilities) {
        var entry = entry(intent, authorized, capabilities);
        return entry.schemas().get(entry.manifest().inputSchemaId()).deepCopy();
    }

    public JsonNode commandSchema(String intent, Set<String> authorized, Set<String> capabilities) {
        var entry = entry(intent, authorized, capabilities);
        return entry.schemas().get(entry.manifest().commandSchemaId()).deepCopy();
    }

    public JsonNode loaderSchema(
        String intent,
        String loader,
        String schemaId,
        Set<String> authorized,
        Set<String> capabilities
    ) {
        var entry = entry(intent, authorized, capabilities);
        if (
            !entry.manifest().loaderIds().contains(loader) ||
            !schemaId.equals(entry.loaderSchemas().get(loader))
        ) reject("UNKNOWN_LOADER");
        return entry.schemas().get(schemaId).deepCopy();
    }

    public String loaderSchemaId(
        String intent,
        String loader,
        Set<String> authorized,
        Set<String> capabilities
    ) {
        var entry = entry(intent, authorized, capabilities);
        if (!entry.manifest().loaderIds().contains(loader)) reject("UNKNOWN_LOADER");
        return entry.loaderSchemas().get(loader);
    }

    public void validateCommand(
        String intent,
        JsonNode command,
        Set<String> authorized,
        Set<String> capabilities
    ) {
        var entry = entry(intent, authorized, capabilities);
        validator.validate("command", command);
        if (
            !command.path("schemaId").asText().equals(entry.manifest().commandSchemaId()) ||
            !command.path("schemaVersion").asText().equals(entry.manifest().skillVersion())
        ) reject("UNKNOWN_COMMAND_SCHEMA");
        var payload = command.path("payload");
        var commandSchema = entry.schemas().get(entry.manifest().commandSchemaId());
        validator.validateOperationDocument(
            commandSchema,
            payload,
            commandSchema,
            payload.path("operations")
        );
    }

    private Entry entry(String intent, Set<String> authorized, Set<String> capabilities) {
        var result = entries.get(intent);
        if (
            result == null ||
            !authorized.contains(intent) ||
            !capabilities.containsAll(result.requires())
        ) reject("UNKNOWN_OR_UNAVAILABLE_INTENT");
        return result;
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void reject(String code) {
        throw new IllegalArgumentException(code);
    }
}
