package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.ResourceRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Resolves a small intent into one ordinary domain command; model maps never become HTTP commands. */
@Component
public class DeviceCreationCompiler {

    public record CreateDeviceIntent(
        String name,
        String deviceTypeRef,
        String locationRef,
        String manufacturer,
        String model
    ) {}

    private static final Set<String> FIELDS = Set.of(
        "name",
        "deviceTypeRef",
        "locationRef",
        "manufacturer",
        "model",
        "type",
        "locationId"
    );
    private static final Pattern MULTIPLE = Pattern.compile(
        "(?iuU)\\b(два|две|три|четыре|пять|несколько|второй|two|three|multiple|several|second|[2-9]|[1-9][0-9]+)\\s+(?:(?:новых|новые|новый|new|additional)\\s+)?(?:устройств\\p{L}*|датчик\\p{L}*|холодильник\\p{L}*|автобус\\p{L}*|автомобил\\p{L}*|машин\\p{L}*|devices?|sensors?|fridges?|buses|vehicles?)\\b"
    );
    private static final Pattern UNSAFE = Pattern.compile(
        "(?iu)игнорируй|ignore previous|обойди|bypass|чуж|other account|accountId|ownerId|tenantId|администратор|admin|удали|delete|опубликуй|publish|javascript|\\bSQL\\b|секрет|secret|внешн.*URL"
    );

    public AgentPlan compile(AgentPlan plan, Prepared context) {
        String original = String.valueOf(
            context.context().getOrDefault("originalDeviceRequest", plan.userMessage())
        );
        if (
            plan
                .actions()
                .stream()
                .anyMatch(action -> action.tool() == AgentTool.CREATE_DEMO_DEVICE) &&
            !plan
                .userMessage()
                .toLowerCase(Locale.ROOT)
                .matches("(?s).*(demo|демо|simulat|симуля).*$")
        ) {
            return question(
                plan,
                "Уточните создание реального устройства. Демо и симуляция требуют отдельного явного запроса."
            );
        }
        var creates = plan
            .actions()
            .stream()
            .filter(action -> action.tool() == AgentTool.CREATE_DEVICE)
            .toList();
        if (creates.isEmpty()) return plan;
        if (
            creates.size() != 1 || plan.actions().size() != 1 || MULTIPLE.matcher(original).find()
        ) {
            return question(
                plan,
                "Первый шаг создаёт одно устройство. Выберите одно имя и тип; источник подключается отдельно."
            );
        }
        if (UNSAFE.matcher(plan.userMessage()).find()) return question(
            plan,
            "Поддерживается создание одного личного устройства без изменения прав или публикации."
        );
        var identifiers = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        ).matcher(plan.userMessage());
        while (identifiers.find()) {
            String id = identifiers.group();
            if (
                context
                    .refs()
                    .stream()
                    .noneMatch(ref -> ref.resourceId().equalsIgnoreCase(id))
            ) {
                return question(
                    plan,
                    "Выберите доступную личную локацию из каталога; указанный идентификатор недоступен."
                );
            }
        }
        AgentAction proposed = creates.getFirst();
        if (!FIELDS.containsAll(proposed.parameters().keySet())) return replace(
            plan,
            "Не удалось подготовить изменения",
            "Не удалось проверить предложенные моделью параметры. Устройство не создавалось. Можно повторить запрос.",
            List.of()
        );
        CreateDeviceIntent intent = new CreateDeviceIntent(
            context.context().get("name") instanceof String known
                ? known
                : text(proposed.parameters(), "name"),
            context.context().get("type") instanceof String knownType
                ? knownType
                : first(proposed.parameters(), "deviceTypeRef", "type"),
            first(proposed.parameters(), "locationRef", "locationId"),
            context.context().get("manufacturer") instanceof String manufacturer
                ? manufacturer
                : text(proposed.parameters(), "manufacturer"),
            context.context().get("model") instanceof String model
                ? model
                : text(proposed.parameters(), "model")
        );
        if (
            intent.name() == null ||
            intent.name().isBlank() ||
            intent.name().length() > 255 ||
            !explicitName(plan.userMessage(), context.context(), intent.name())
        ) {
            return question(
                plan,
                "Как назвать устройство? Укажите имя в кавычках или после «назови»."
            );
        }
        ResourceRef type = resolve(context.refs(), "type", intent.deviceTypeRef());
        if (type == null) return question(
            plan,
            "Выберите тип устройства из доступного каталога: " + choices(context.refs(), "type")
        );
        ResourceRef location =
            intent.locationRef() == null
                ? null
                : resolve(context.refs(), "location", intent.locationRef());
        if (intent.locationRef() != null && location == null) return question(
            plan,
            "Уточните личную локацию из каталога: " + choices(context.refs(), "location")
        );
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("name", intent.name().trim());
        parameters.put("type", type.resourceId());
        Map<String, Object> connection;
        try {
            connection = context.context().containsKey("deviceSetupCapabilities")
                ? DeviceConnectionIntent.hsl(original)
                : Map.of();
        } catch (IllegalArgumentException ambiguous) {
            return question(
                plan,
                "Уточните один борт HSL в формате 0012-02512 и вид транспорта bus или tram. Устройство ещё не создано."
            );
        }
        if (!connection.isEmpty()) {
            if (!"VEHICLE".equals(type.resourceId())) return question(
                plan,
                "Для HSL требуется тип VEHICLE. Уточните тип устройства."
            );
            parameters.put("connection", connection);
        }
        if (location != null) {
            parameters.put("locationId", location.resourceId());
            parameters.put("locationName", location.summary().getOrDefault("name", ""));
        }
        putExplicit(
            parameters,
            "manufacturer",
            intent.manufacturer(),
            plan.userMessage(),
            context.context()
        );
        putExplicit(parameters, "model", intent.model(), plan.userMessage(), context.context());
        AgentAction compiled = new AgentAction(
            proposed.id(),
            AgentTool.CREATE_DEVICE,
            "Создать «" + intent.name().trim() + "»",
            "Тип: " +
                type.summary().getOrDefault("displayName", type.resourceId()) +
                (location == null
                    ? ". Без локации."
                    : ". Локация: " + location.summary().getOrDefault("name", "")) +
                (parameters.containsKey("manufacturer")
                    ? " Производитель: " + parameters.get("manufacturer") + "."
                    : " Производитель не указан.") +
                (parameters.containsKey("model")
                    ? " Модель: " + parameters.get("model") + "."
                    : " Модель не указана.") +
                (connection.isEmpty()
                    ? " Личное устройство; подключение источника — следующим шагом."
                    : " Подключить реальный HSL " +
                      connection.get("mode") +
                      " " +
                      connection.get("vehicleKey") +
                      " через API/REST и проверить поступление данных. Симуляция выключена."),
            true,
            Map.copyOf(parameters)
        );
        return replace(
            plan,
            "Создание личного устройства",
            connection.isEmpty()
                ? "Проверьте имя, тип и локацию перед созданием. Источник пока не подключён."
                : "Проверьте устройство и борт HSL. После подтверждения подключим источник; готовность телеметрии проверяется отдельно.",
            List.of(compiled)
        );
    }

    private boolean explicitName(String message, Map<String, Object> context, String name) {
        if (name.equals(context.get("name"))) return true;
        if (
            context.get("clarificationAnswers") instanceof List<?> answers && answers.contains(name)
        ) return true;
        String folded = message.toLowerCase(Locale.ROOT);
        String value = name.toLowerCase(Locale.ROOT);
        if (
            folded.contains("«" + value + "»") ||
            folded.contains("\"" + value + "\"") ||
            folded.contains("'" + value + "'")
        ) return true;
        var marker = Pattern.compile(
            "(?iu)(назови|именем|названием|название|имя|named|called|name)\\s*[:=]?\\s*"
        ).matcher(message);
        return (
            marker.find() &&
            message.substring(marker.end()).toLowerCase(Locale.ROOT).contains(value)
        );
    }

    private ResourceRef resolve(List<ResourceRef> refs, String kind, String value) {
        if (value == null || value.isBlank()) return null;
        var matches = refs
            .stream()
            .filter(
                ref ->
                    ref.kind().equals(kind) &&
                    (ref.ref().equals(value) ||
                        ref.resourceId().equalsIgnoreCase(value) ||
                        ref
                            .summary()
                            .values()
                            .stream()
                            .anyMatch(label -> label.toString().equalsIgnoreCase(value)))
            )
            .toList();
        // The same resource may occur as selected context and catalogue entry; that is not ambiguity.
        var ids = matches.stream().map(ResourceRef::resourceId).distinct().toList();
        return ids.size() == 1 ? matches.getFirst() : null;
    }

    private String choices(List<ResourceRef> refs, String kind) {
        return refs
            .stream()
            .filter(ref -> ref.kind().equals(kind))
            .map(ref ->
                ref
                    .summary()
                    .getOrDefault(
                        "displayName",
                        ref.summary().getOrDefault("name", ref.resourceId())
                    )
                    .toString()
            )
            .distinct()
            .reduce((left, right) -> left + ", " + right)
            .orElse("нет доступных вариантов");
    }

    private void putExplicit(
        Map<String, Object> target,
        String key,
        String value,
        String message,
        Map<String, Object> context
    ) {
        String cue = key.equals("manufacturer")
            ? "производитель|бренд|manufacturer|brand"
            : "модель|model";
        var marker = Pattern.compile("(?iu)(?:" + cue + ")\\s*[:=]?\\s*").matcher(message);
        boolean supplied =
            value != null &&
            (value.equals(context.get(key)) ||
                (marker.find() &&
                    message
                        .substring(marker.end())
                        .toLowerCase(Locale.ROOT)
                        .contains(value.toLowerCase(Locale.ROOT))));
        if (
            value != null &&
            !value.isBlank() &&
            value.length() <= 255 &&
            !value.matches("(?iu)unknown|неизвест.*|не знаю") &&
            supplied
        ) {
            target.put(key, value.trim());
        }
    }

    private String first(Map<String, Object> values, String primary, String alternate) {
        return text(values, primary) != null ? text(values, primary) : text(values, alternate);
    }

    private String text(Map<String, Object> values, String key) {
        return values.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private AgentPlan question(AgentPlan plan, String question) {
        return replace(plan, "Требуется уточнение", question, List.of());
    }

    public AgentPlan duplicateReview(AgentPlan plan, boolean duplicate) {
        if (!duplicate) return plan;
        return replace(
            plan,
            plan.summary(),
            plan.explanation() +
                " У вас уже есть устройство с таким именем. Подтверждение создаст отдельное устройство; имя не служит идентификатором.",
            plan.actions()
        );
    }

    private AgentPlan replace(
        AgentPlan plan,
        String summary,
        String explanation,
        List<AgentAction> actions
    ) {
        return new AgentPlan(
            plan.id(),
            plan.userId(),
            plan.userMessage(),
            summary,
            explanation,
            plan.safetyNotes(),
            actions,
            plan.createdAt(),
            plan.providerType(),
            plan.providerName(),
            plan.modelId(),
            plan.providerId(),
            plan.modelConfigId(),
            plan.fallbackUsed()
        );
    }
}
