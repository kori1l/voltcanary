package com.voltcanary.analytics.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.ml-features")
@Validated
@Getter
@Setter
public class MlFeatureGenerationProperties {

    @Min(1)
    @Max(100_000)
    private int maxInputRows = 20_000;

    @Min(1)
    @Max(100_000)
    private int maxLabels = 20_000;

    @Min(1)
    @Max(1000)
    private int maxImportedFiles = 100;
}
