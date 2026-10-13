package com.svi.channelconnect.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        @NotBlank String apiKey,
        @NotBlank String model,
        String vectorStoreId,
        @Min(1) int historyLimit,
        @DefaultValue("300") @Min(1) int maxOutputTokens,
        @DefaultValue("minimal") @Pattern(regexp = "|minimal|low|medium|high|none|xhigh") String reasoningEffort) {
}
