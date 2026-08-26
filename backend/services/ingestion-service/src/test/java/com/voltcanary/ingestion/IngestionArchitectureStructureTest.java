package com.voltcanary.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionArchitectureStructureTest {

    private static final List<String> CORE_PACKAGES = List.of(
            "pipeline",
            "canonical",
            "validation",
            "normalization",
            "idempotency",
            "abuse",
            "source",
            "kafka",
            "backpressure",
            "audit");

    private static final List<String> CONCRETE_ADAPTER_REFERENCES = List.of(
            "com.voltcanary.ingestion.rest",
            "com.voltcanary.ingestion.mqtt",
            "com.voltcanary.ingestion.adapter.http",
            "com.voltcanary.ingestion.adapter.mqtt",
            "com.voltcanary.ingestion.adapter.modbus",
            "com.voltcanary.ingestion.adapter.opcua",
            "com.voltcanary.ingestion.adapter.industrial",
            "com.voltcanary.ingestion.adapter.vendor");

    @Test
    void commonIngestionCoreDoesNotDependOnConcreteProtocolAdapters() throws IOException {
        Path ingestionPackage = moduleBaseDir().resolve("src/main/java/com/voltcanary/ingestion");

        for (String packageName : CORE_PACKAGES) {
            Path packagePath = ingestionPackage.resolve(packageName);
            if (Files.exists(packagePath)) {
                assertNoReferences(packagePath, CONCRETE_ADAPTER_REFERENCES);
            }
        }
    }

    @Test
    void adapterApiDoesNotDependOnSpringWebOrConcreteProtocolLibraries() throws IOException {
        Path adapterApi = moduleBaseDir().resolve("src/main/java/com/voltcanary/ingestion/adapter/api");

        assertNoReferences(adapterApi, List.of(
                "org.springframework.web",
                "org.eclipse.paho",
                "org.apache.kafka",
                "redis.clients"));
    }

    @Test
    void telemetryServiceDoesNotDependOnIngestionAdapterPackages() throws IOException {
        Path telemetryServiceMain = moduleBaseDir()
                .getParent()
                .resolve("telemetry-service/src/main/java");

        assertThat(Files.exists(telemetryServiceMain)).isTrue();
        assertNoReferences(telemetryServiceMain, List.of("com.voltcanary.ingestion.adapter"));
    }

    private void assertNoReferences(Path root, List<String> forbiddenReferences) throws IOException {
        for (Path file : javaFiles(root)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String forbiddenReference : forbiddenReferences) {
                assertThat(content)
                        .as("%s must not reference %s", moduleBaseDir().relativize(file), forbiddenReference)
                        .doesNotContain(forbiddenReference);
            }
        }
    }

    private List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private Path moduleBaseDir() {
        return Path.of("").toAbsolutePath();
    }
}
