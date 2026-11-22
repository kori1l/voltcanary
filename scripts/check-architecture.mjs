import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import java from "prettier-plugin-java";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

async function filesUnder(directory, suffix) {
  const files = [];
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    if (["target", "node_modules", ".git"].includes(entry.name)) continue;
    const filename = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await filesUnder(filename, suffix)));
    else if (entry.name.endsWith(suffix)) files.push(filename);
  }
  return files;
}

function javaViolations(filename, ast) {
  const violations = [];
  const relative = path.relative(root, filename).replaceAll("\\", "/");
  const imports = ast.namedChildren.filter((node) => node.type === "import_declaration");
  const dependencies = new Set(imports.map((node) => node.value));
  function collectQualifiedReferences(node) {
    if (["package_declaration", "import_declaration"].includes(node.type)) return;
    if (["scoped_type_identifier", "scoped_identifier", "field_access"].includes(node.type)
        && /^(com\.voltcanary|org\.springframework|jakarta\.persistence|software\.amazon|com\.amazonaws)\./.test(node.value)) {
      dependencies.add(node.value);
    }
    for (const child of node.namedChildren ?? []) collectQualifiedReferences(child);
  }
  collectQualifiedReferences(ast);
  for (const name of dependencies) {
    if (relative.includes("/domain/") && /\.(controller|repository|dto|service)\.|org\.springframework\.(web|kafka)|software\.amazon|com\.amazonaws/.test(name)) {
      violations.push(`${relative}: domain depends on an adapter/application contract: ${name}`);
    }
    if (relative.includes("/controller/") && /\.repository\.|\b(JdbcTemplate|EntityManager|KafkaTemplate)\b/.test(name)) {
      violations.push(`${relative}: controller accesses infrastructure directly: ${name}`);
    }
    if (relative.includes("/common-business-contracts/") && !/^(?:import (?:static )?)?java\./.test(name)) {
      violations.push(`${relative}: shared business contracts must be infrastructure independent: ${name}`);
    }
  }
  return violations;
}

const violations = [];
let sources = 0;
for (const filename of await filesUnder(path.join(root, "backend"), ".java")) {
  if (!filename.replaceAll("\\", "/").includes("/src/main/java/")) continue;
  const source = await fs.readFile(filename, "utf8");
  const ast = await java.parsers.java.parse(source);
  violations.push(...javaViolations(filename, ast));
  sources++;
}

for (const filename of await filesUnder(path.join(root, "backend/services"), "pom.xml")) {
  const module = path.basename(path.dirname(filename));
  const xml = await fs.readFile(filename, "utf8");
  for (const dependency of xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const artifact = dependency[1].match(/<artifactId>([^<]+)<\/artifactId>/)?.[1];
    if (artifact?.endsWith("-service") && artifact !== module && !/<scope>test<\/scope>/.test(dependency[1])) {
      violations.push(`${path.relative(root, filename)}: runtime dependency on another service: ${artifact}`);
    }
  }
}

if (violations.length) {
  console.error(violations.join("\n"));
  process.exitCode = 1;
} else {
  console.log(`Architecture boundaries passed for ${sources} Java sources.`);
}
