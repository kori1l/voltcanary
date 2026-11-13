package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimarySkillManifestTest {
    @Test void everyGeneratedManifestSatisfiesItsBoundedCanonicalContract() {
        var mapper = new ObjectMapper();
        var validator = new ProtocolValidator(mapper);
        for (var pkg : PrimarySkillPackages.build(mapper, validator, List.of())) {
            String errors = validator.violations("manifest", pkg.manifest());
            assertTrue(errors.isBlank(), () -> pkg.manifest().path("intentId").asText() + ": " + errors
                    + "; instructionChars=" + pkg.manifest().at("/instructions/text").asText().length());
        }
    }
}
