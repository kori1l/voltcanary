package com.voltcanary.dashboard.templates;

import com.voltcanary.dashboard.application.DefinitionBindings;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.query.WidgetManifests;
import com.voltcanary.dashboard.query.WidgetQueryDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TemplateDefinitionChecks {

    private final DefinitionValidation validation;
    private final DefinitionBindings bindings;
    private final WidgetManifests manifests;
    private final WidgetQueryDecoder decoder;

    public void validate(
        DashboardActor actor,
        String authorization,
        DashboardDefinition definition
    ) {
        validation.validate(definition);
        bindings.requireAuthorized(authorization, actor, definition);
        for (var widget : definition.widgets()) {
            var manifest = manifests
                .find(widget.type(), widget.typeVersion())
                .filter(item -> item.supported())
                .orElseThrow(() ->
                    new DashboardFailure("VALIDATION_ERROR", "Template widget is unsupported")
                );
            decoder.scope(widget.binding());
            decoder.config(widget, manifest);
        }
    }
}
