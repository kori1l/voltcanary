import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.vcl.adapter.AutomationGrantSnapshots;
import com.voltcanary.analytics.vcl.adapter.EvidencePolicySnapshots;
import com.voltcanary.analytics.vcl.adapter.NativeDatasetSnapshots;
import com.voltcanary.analytics.vcl.adapter.NativeAsOfOwner;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshots;
import com.voltcanary.analytics.vcl.adapter.SourceBindingSnapshots;
import com.voltcanary.analytics.vcl.adapter.SourceCaptureSnapshots;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.util.Map;
import java.util.UUID;

/** Opt-in Compose smoke helper. Input credentials stay on stdin and never enter its output. */
class ConfigurableOwnerSmoke {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var input = json.readTree(System.in);
        var properties = new AnalyticsDownstreamProperties();
        properties.getDeviceService().setBaseUrl("http://device-service:8082");
        properties.getAlertService().setBaseUrl("http://alert-service:8086");
        var snapshots = new RuleCompilationSnapshots(new NativeDatasetSnapshots(JacksonRestClients.builder(), properties),
                new SourceBindingSnapshots(JacksonRestClients.builder(), properties),
                new AutomationGrantSnapshots(JacksonRestClients.builder(), properties),
                new EvidencePolicySnapshots(JacksonRestClients.builder(), properties),
                new SourceCaptureSnapshots(JacksonRestClients.builder(), properties),
                new NativeAsOfOwner(JacksonRestClients.builder(), properties));
        var parser = new VclParser();
        var compiler = new VclCompiler(new VclRegistry(parser), VclUnits.standard());
        String authorization = "Bearer " + input.path("accessToken").asText();
        UUID actor = UUID.fromString(input.path("actorId").asText());
        UUID account = UUID.fromString(input.path("accountId").asText());
        String stage = "CAPTURE";
        try {
            var captured = snapshots.capture(authorization, actor, account, parser.parse(input.path("document").toString()));
            stage = "COMPILE";
            var compilation = captured.compile(compiler);
            if (!"STATIC_TYPED_IR".equals(compilation.stage()) || compilation.dependencies().size() != 1) throw new IllegalStateException();
            stage = "ASOF_REJECTION";
            var lookup = snapshots.capture(authorization, actor, account, parser.parse(input.path("lookupDocument").toString()));
            try {
                lookup.compile(compiler);
                throw new IllegalStateException();
            } catch (VclException expected) {
                if (!"PROJECTION_UNAVAILABLE".equals(expected.code())) throw expected;
            }
            stage = "REVOKE";
            JacksonRestClients.builder().baseUrl("http://alert-service:8086").build().post()
                    .uri("/alert-rules/configurable/grants/{id}/revoke", input.path("grantId").asText())
                    .header("Authorization", authorization).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("expectedEpoch", "1", "idempotencyKey", "owner-smoke-revoke-" + UUID.randomUUID()))
                    .retrieve().toBodilessEntity();
            stage = "REVOKED_REUSE";
            try {
                captured.compile(compiler);
                throw new IllegalStateException();
            } catch (VclException expected) {
                if (!"AUTOMATION_GRANT_INACTIVE".equals(expected.code())) throw expected;
            }
            System.out.println(json.writeValueAsString(Map.of("status", "PASS", "staticOwnerComposition", true,
                    "unsupportedAsOfRejected", true, "realOwnerRevocationRejected", true, "ruleExecutionReady", false)));
        } catch (RuntimeException failure) {
            String code = failure instanceof VclException vcl ? vcl.code() : "OWNER_SMOKE_FAILED";
            System.out.println(json.writeValueAsString(Map.of("status", "FAIL", "stage", stage, "code", code)));
            System.exit(1);
        }
    }
}
