// Documentation consistency guard. This is NOT the backend VCL compiler/evaluator.
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = name => JSON.parse(fs.readFileSync(path.join(root, "docs/target", name), "utf8"));
const examples = read("configurable-operations-language.examples.json");
const registry = examples.fixtureRegistry;
const datasets = new Map(registry.datasets.map(d => [d.id, d]));
const bindings = new Map(registry.bindings.map(b => [b.id, b]));
const fieldMap = fields => new Map(fields.map(f => [f.id, f]));
class ContractError extends Error {
  constructor(code, detail = "") { super(code + ": " + detail); this.code = code; }
}
function requireThat(condition, code, detail) {
  if (!condition) throw new ContractError(code, detail);
}
function uniqueFields(values, property) {
  requireThat(new Set(values.map(v => v[property])).size === values.length, "DUPLICATE_FIELD", property);
}
function dataset(target) {
  const d = datasets.get(target.datasetId);
  requireThat(d && d.revision === target.schemaRevision, "SCHEMA_UNAVAILABLE", target.datasetId);
  return d;
}
function field(scope, id) {
  const f = scope.get(id);
  requireThat(f, "FIELD_NOT_IN_SCOPE", id);
  return f;
}
function compatible(a, b) {
  if (a.type === "NULL" || b.type === "NULL") return;
  requireThat(a.type === b.type, "TYPE_MISMATCH", a.type + "/" + b.type);
  if (["DECIMAL", "INT64"].includes(a.type))
    requireThat((a.unit ?? "1") === (b.unit ?? "1"), "UNIT_MISMATCH", a.unit + "/" + b.unit);
  if (a.type === "REF" && a.target && b.target)
    requireThat(JSON.stringify(a.target) === JSON.stringify(b.target), "REF_TYPE_MISMATCH");
}
function expression(e, scope) {
  if (e.op === "FIELD") return field(scope, e.fieldId);
  if (e.op === "CONST") return e.literal;
  const args = e.args.map(a => expression(a, scope));
  const b = {type: "BOOLEAN", nullable: false};
  if (["is_known", "is_null"].includes(e.method)) return b;
  if (["and", "or", "not"].includes(e.method)) {
    for (const a of args) requireThat(a.type === "BOOLEAN", "TYPE_MISMATCH", e.method);
    return b;
  }
  if (["eq", "ne", "lt", "le", "gt", "ge"].includes(e.method)) {
    compatible(args[0], args[1]); return b;
  }
  if (["text_eq", "text_prefix"].includes(e.method)) {
    requireThat(args.every(a => a.type === "TEXT"), "TYPE_MISMATCH", e.method); return b;
  }
  if (e.method === "distance_m") {
    requireThat(args.every(a => a.type === "POINT"), "TYPE_MISMATCH", e.method);
    return {type: "DECIMAL", unit: "m"};
  }
  if (e.method === "inside") {
    requireThat(args[0].type === "POINT" && args[1].type === "REF"
      && args[2].type === "DECIMAL" && args[2].unit === "m", "TYPE_MISMATCH", e.method);
    return b;
  }
  if (e.method === "convert_unit" || e.method === "duration_from") {
    requireThat(["DECIMAL", "INT64"].includes(args[0].type)
      && e.args[1].op === "CONST" && args[1].type === "TEXT", "TYPE_MISMATCH", e.method);
    if (e.method === "duration_from") return {type: "DURATION"};
    const target = e.args[1].literal.value;
    requireThat(registry.units[args[0].unit ?? "1"] === registry.units[target]
      && registry.units[target], "UNIT_MISMATCH", target);
    return {type: "DECIMAL", unit: target};
  }
  requireThat(["add", "subtract", "multiply", "divide"].includes(e.method), "METHOD_UNKNOWN", e.method);
  requireThat(args.every(a => ["DECIMAL", "INT64"].includes(a.type)), "TYPE_MISMATCH", e.method);
  if (["add", "subtract"].includes(e.method)) compatible(args[0], args[1]);
  return args[0];
}
function predicate(e, scope) {
  requireThat(expression(e, scope).type === "BOOLEAN", "PREDICATE_TYPE");
}
function checkRule(rule) {
  const nodes = new Map(rule.nodes.map(n => [n.id, n]));
  requireThat(nodes.size === rule.nodes.length, "DUPLICATE_NODE_ID");
  const emits = rule.nodes.filter(n => n.method === "emit_alert");
  requireThat(emits.length === 1 && emits[0].id === rule.terminal, "TERMINAL_EFFECT_COUNT");
  const visiting = new Set(), done = new Map();
  function resolve(id) {
    requireThat(nodes.has(id), "GRAPH_REFERENCE_UNKNOWN", id);
    requireThat(!visiting.has(id), "GRAPH_CYCLE", id);
    if (done.has(id)) return done.get(id);
    visiting.add(id);
    const n = nodes.get(id), c = n.config;
    const inputs = Object.fromEntries(Object.entries(n.inputs).map(([p, v]) => [p, resolve(v)]));
    const source = inputs.events ?? inputs.sessions ?? inputs.measures ?? inputs.decisions;
    let output;
    function input(port, kind) {
      requireThat(inputs[port]?.kind === kind, "PORT_TYPE_MISMATCH", n.id + "/" + port);
      return inputs[port];
    }
    if (n.method === "read_stream") {
      const binding = bindings.get(c.bindingId);
      requireThat(binding && binding.schemaRevision === c.schemaRevision, "BINDING_UNAVAILABLE");
      output = {kind:"EVENT", fields:fieldMap(binding.fields)};
    } else if (n.method === "lookup_asof") {
      const stream = input("events", "EVENT"), d = dataset(c.target), f = fieldMap(d.fields);
      requireThat(d.runtimeProjection?.supportsAsOf, "PROJECTION_UNAVAILABLE", d.id);
      for (const k of c.keyPairs) compatible(field(stream.fields, k.eventFieldId), field(f, k.recordFieldId));
      output = {kind:"EVENT", fields:new Map(stream.fields)};
      for (const id of c.select) output.fields.set(id, field(f, id));
      if (c.temporal.mode === "INTERVAL_SPLIT") {
        requireThat(field(stream.fields, c.temporal.startFieldId).type === "INSTANT"
          && field(stream.fields, c.temporal.endFieldId).type === "INSTANT", "TYPE_MISMATCH");
      }
    } else if (n.method === "filter") {
      output = input("events", "EVENT"); predicate(c.predicate, output.fields);
    } else if (["zone_session", "window_session"].includes(n.method)) {
      const stream = input("events", "EVENT");
      field(stream.fields, c.subjectFieldId); field(stream.fields, c.occurrenceFieldId);
      if (n.method === "zone_session") {
        requireThat(field(stream.fields, c.positionFieldId).type === "POINT"
          && field(stream.fields, c.zoneFieldId).type === "REF", "TYPE_MISMATCH");
        if (c.accuracyFieldId) {
          const accuracy = field(stream.fields, c.accuracyFieldId);
          requireThat(accuracy.type === "DECIMAL" && accuracy.unit === "m", "UNIT_MISMATCH");
        }
        requireThat(c.accuracyFallback !== "APPROVED_FLOOR" || c.uncertaintyFloorMetres > 0,
          "ACCURACY_POLICY_INVALID");
      } else {
        predicate(c.startWhen, stream.fields); predicate(c.closeWhen, stream.fields);
      }
      requireThat(BigInt(c.maxDurationUs) > 0n, "DURATION_NOT_POSITIVE");
      output = {kind:"SESSION", fields:stream.fields, session:id};
    } else if (n.method === "observed_within") {
      const session = input("sessions", "SESSION"); predicate(c.predicate, session.fields);
      output = {kind:"WITNESS", fields:session.fields, session:session.session};
    } else if (n.method === "absent_within") {
      const session = input("sessions", "SESSION"), witness = input("witnesses", "WITNESS");
      requireThat(session.session === witness.session, "SESSION_MISMATCH");
      requireThat(c.allowedLatenessUs === rule.timing.allowedLatenessUs, "LATENESS_MISMATCH");
      output = {kind:"DECISION", fields:session.fields};
    } else if (n.method === "sustained") {
      const stream = input("events", "EVENT"); field(stream.fields, c.subjectFieldId);
      predicate(c.predicate, stream.fields);
      requireThat(BigInt(c.maxGapUs) <= BigInt(rule.coverage.maxGapUs), "GAP_POLICY_WEAKENED");
      output = {kind:"DECISION", fields:stream.fields};
    } else if (n.method === "interval_union_window" || n.method === "window_aggregate") {
      const stream = input("events", "EVENT");
      const subject = field(stream.fields, c.subjectFieldId);
      let measure;
      if (n.method === "interval_union_window") {
        requireThat(field(stream.fields, c.startFieldId).type === "INSTANT"
          && field(stream.fields, c.endFieldId).type === "INSTANT", "TYPE_MISMATCH");
        predicate(c.includeWhere, stream.fields); predicate(c.excludeWhere, stream.fields);
        measure = {type:"DURATION"};
      } else {
        requireThat((c.reducer === "COUNT") === (c.valueFieldId === null), "REDUCER_FIELD_MISMATCH");
        measure = c.reducer === "COUNT" ? {type:"INT64", unit:"1"} : field(stream.fields, c.valueFieldId);
      }
      output = {kind:"MEASURE", fields:new Map([[c.subjectFieldId,subject],[c.measureFieldId,measure]])};
    } else if (n.method === "compare") {
      const measure = input("measures", "MEASURE"); predicate(c.predicate, measure.fields);
      output = {kind:"DECISION", fields:measure.fields};
    } else if (n.method === "emit_alert") {
      output = {kind:"ALERT", fields:input("decisions", "DECISION").fields};
    } else throw new ContractError("METHOD_UNKNOWN", n.method);
    visiting.delete(id); done.set(id, output); return output;
  }
  resolve(rule.terminal);
  requireThat(done.size === nodes.size, "GRAPH_UNREACHABLE");
}
function checkMutation(m) {
  const d = dataset(m.target), fields = fieldMap(d.fields);
  requireThat(m.targetPrecondition.mode === d.storage.mode, "TARGET_MODE_MISMATCH");
  uniqueFields(m.key, "fieldId"); uniqueFields(m.values, "fieldId");
  if (m.operation !== "INSERT")
    requireThat(JSON.stringify(m.key.map(k=>k.fieldId)) === JSON.stringify(d.keyFieldIds), "UNIQUE_TARGET_KEY_REQUIRED");
  for (const k of m.key) {
    requireThat(k.literal.type !== "NULL", "NULL_KEY");
    compatible(field(fields, k.fieldId), k.literal);
  }
  for (const v of m.values) {
    const f = field(fields, v.fieldId);
    requireThat(!m.key.some(k=>k.fieldId === v.fieldId), "MUTATION_IMMUTABLE_KEY");
    if (v.literal.type === "NULL") requireThat(f.nullable, "NULL_NOT_ALLOWED");
    else compatible(f, v.literal);
  }
}
function checkImport(i) {
  const d = dataset(i.target), fields = fieldMap(d.fields);
  requireThat(d.storage.mode === i.targetPrecondition.mode, "TARGET_MODE_MISMATCH");
  uniqueFields(i.columns, "sourceColumn"); uniqueFields(i.columns, "targetFieldId");
  for (const id of i.keyFields) field(fields, id);
  requireThat(d.keyFieldIds.length > 0
    && JSON.stringify(i.keyFields) === JSON.stringify(d.keyFieldIds), "UNIQUE_TARGET_KEY_REQUIRED");
  if (["ATOMIC_INSERT","ATOMIC_UPSERT"].includes(i.mode))
    requireThat(i.limits.maxRows <= 500, "ATOMIC_ROW_LIMIT");
  for (const c of i.columns) {
    const target = field(fields, c.targetFieldId);
    if (i.format.kind === "CSV") requireThat(c.sourceType === "TEXT", "SOURCE_TYPE_MISMATCH");
    let current = {type:c.sourceType, unit:c.sourceUnit ?? "1"};
    for (const v of c.conversions) {
      if (v.method.startsWith("parse_")) {
        requireThat(current.type === "TEXT", "CONVERSION_TYPE_MISMATCH");
        current = {...current, type:{parse_int64:"INT64",parse_decimal:"DECIMAL",
          parse_boolean:"BOOLEAN",parse_date:"DATE",parse_instant:"INSTANT",parse_duration:"DURATION"}[v.method]};
      } else if (v.method === "convert_unit") {
        requireThat(current.unit === v.sourceUnit
          && registry.units[v.sourceUnit] === registry.units[v.targetUnit]
          && registry.units[v.targetUnit], "IMPORT_UNIT_MISMATCH");
        current = {type:"DECIMAL", unit:v.targetUnit};
      } else if (v.method === "resolve_ref") {
        const refDataset = dataset(v.target);
        compatible(current, field(fieldMap(refDataset.fields), v.keyFieldId));
        current = {type:"REF", target:{kind:"DATASET",datasetId:refDataset.id}};
      } else requireThat(v.method === "identity", "METHOD_UNKNOWN");
    }
    compatible(target, current);
    requireThat(c.emptyValue !== "AS_NULL" || target.nullable, "NULL_NOT_ALLOWED");
  }
  const artifact = examples.fixtureArtifacts.find(a=>a.artifactId === i.artifactId);
  requireThat(artifact && crypto.createHash("sha256").update(artifact.contentUtf8,"utf8").digest("hex")
    === i.artifactSha256, "ARTIFACT_HASH_MISMATCH");
}
function check(doc) {
  if (doc.kind === "RULE") checkRule(doc.spec);
  if (doc.kind === "MUTATION") checkMutation(doc.spec);
  if (doc.kind === "IMPORT") checkImport(doc.spec);
  if (doc.kind === "QUERY") {
    const scope = fieldMap(dataset(doc.spec.target).fields);
    for (const id of doc.spec.select) field(scope,id);
    for (const sort of doc.spec.order) field(scope,sort.fieldId);
    predicate(doc.spec.where,scope);
  }
  if (doc.kind === "COLLECTION") {
    uniqueFields(doc.spec.fields,"id");
    const scope = fieldMap(doc.spec.fields);
    for (const id of doc.spec.keyFieldIds) {
      const f = field(scope,id);
      requireThat(!f.nullable && ["TEXT","ENUM","INT64","DATE","INSTANT","REF"].includes(f.type),
        "INVALID_KEY_FIELD", id);
    }
  }
}
for (const doc of examples.documents) check(doc);
const negative = read("configurable-operations-language.conformance.json");
function patched(test) {
  const doc = structuredClone(examples.documents[test.documentIndex]);
  for (const edit of test.edits) {
    const parts = edit.path.slice(1).split("/").map(x=>x.replaceAll("~1","/").replaceAll("~0","~"));
    let target = doc;
    for (const part of parts.slice(0,-1)) target = target[part];
    const key = parts.at(-1);
    if (edit.op === "remove") delete target[key]; else target[key] = edit.value;
  }
  return doc;
}
let semanticCases = 0;
for (const test of negative.cases.filter(t=>t.stage === "SEMANTIC")) {
  let error;
  try { check(patched(test)); } catch (e) { error = e; }
  requireThat(error?.code === test.expected, "CONFORMANCE_CASE_FAILED",
    test.id + ": expected " + test.expected + ", observed " + error?.code);
  semanticCases++;
}
if (process.argv.includes("--schema-cases")) {
  console.log(JSON.stringify(negative.cases.map(test => ({id:test.id,
    stage:test.stage, document:patched(test)}))));
} else {
  console.log(JSON.stringify({scope:"DOCUMENTATION_ONLY_NOT_BACKEND_COMPILER",
    validDocuments:examples.documents.length, negativeSemanticCases:semanticCases,
    fixtureTypesUnitsGraphAndArtifactHashes:"PASS"}));
}

