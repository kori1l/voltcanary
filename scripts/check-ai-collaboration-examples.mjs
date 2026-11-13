// Documentation contract vectors only. Not the runtime scheduler, ACL or metric engine.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = name => JSON.parse(fs.readFileSync(path.join(root, "docs/target", name), "utf8"));
const fixtures = read("ai-assistant-collaboration.examples.json");
const schema = read("ai-assistant-collaboration.schema.json");
const vcl = read("configurable-operations-language.schema.json");
if (JSON.stringify(schema.$defs.VclLiteral) !== JSON.stringify(vcl.$defs.Literal)) {
  throw new Error("VclLiteral projection diverged from its canonical type registry");
}

function check(body, current) {
  const assignment = fixtures.fixtureAssignment;
  for (const field of ["taskId", "attemptId", "requirementsRevision", "readSetDigest"]) {
    if (body[field] !== assignment[field]) return "ASSIGNMENT_MISMATCH";
  }
  if (!current.authorized) return "DENIED";
  if (current.cancellationEpoch !== assignment.cancellationEpoch) return "CANCELLED";
  if (current.continuationEpoch !== assignment.continuationEpoch) return "STALE_ATTEMPT";
  if (current.requirementsRevision < assignment.requirementsRevision) return "STALE";
  for (const [slot, version] of Object.entries(assignment.dependencyVersions)) {
    if (current.dependencyVersions[slot] !== version) return "STALE";
  }
  if ((body.evidenceRefs ?? []).some(id => !assignment.allowedEvidenceRefs.includes(id))) return "REF_DENIED";
  if (body.outcome === "DELEGATE") {
    if (body.requests.some(r => r.parentTaskId !== body.taskId)) return "PARENT_MISMATCH";
    if (body.requests.some(r => r.requirementsRevision !== body.requirementsRevision)) return "ASSIGNMENT_MISMATCH";
  }
  if (body.outcome === "NEED_USER") {
    for (const question of body.questions) {
      for (const choice of question.choices) {
        if (Object.keys(choice.bindings).some(slot => !question.slotIds.includes(slot))) return "QUESTION_BINDING_MISMATCH";
      }
    }
  }
  if (body.outcome === "ANSWER") {
    const metric = body.payload;
    if (metric.metricDefinitionId !== assignment.metricDefinitionId
        || metric.metricVersion !== assignment.metricVersion) return "METRIC_BINDING_MISMATCH";
    if (metric.unit !== assignment.metricUnit) return "UNIT_MISMATCH";
    if (metric.value.type !== "NULL" && metric.value.type !== assignment.metricValueType) return "TYPE_MISMATCH";
    if (metric.evidenceRefs.some(id => !assignment.allowedEvidenceRefs.includes(id))) return "REF_DENIED";
  }
  return current.requirementsRevision === assignment.requirementsRevision ? "ACCEPTED" : "REBASE_ACCEPTED";
}

const cases = fixtures.semanticCases.map(test => {
  const document = structuredClone(fixtures.documents[test.documentIndex]);
  Object.assign(document.body, test.bodyEdits);
  const current = {...structuredClone(fixtures.fixtureCurrent), ...test.currentEdits};
  const observed = check(document.body, current);
  if (observed !== test.expected) throw new Error(`${test.id}: expected ${test.expected}, observed ${observed}`);
  return {id: test.id, document};
});
if (process.argv.includes("--schema-cases")) {
  console.log(JSON.stringify(cases));
} else {
  console.log(JSON.stringify({scope:"DOCUMENTATION_ONLY_NOT_RUNTIME_SCHEDULER", semanticVectors:cases.length,
    literalRegistryProjection:"PASS", dependencyRebaseAndEvidenceFixtures:"PASS"}));
}
