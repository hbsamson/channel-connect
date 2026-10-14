package com.svi.channelconnect.ai.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

@Validated
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
		@NotNull Provider provider,
		String apiKey,
		String model,
		@NotNull URI baseUrl,
		@NotNull Duration timeout,
		@Min(0) @Max(3) int maxRetries,
		@Min(1) @Max(4096) int maxResponseCharacters,
		@DefaultValue("") @Pattern(regexp = "|none|minimal|low|medium|high|xhigh|max") String reasoningEffort,
		@DefaultValue("2048") @Min(128) @Max(8192) int maxOutputTokens
) {
	public enum Provider {
		MOCK,
		OPENAI
	}

	@AssertTrue(message = "ai.api-key and ai.model are required when ai.provider is OPENAI")
	public boolean isProviderConfigurationValid() {
		return provider != Provider.OPENAI
				|| (StringUtils.hasText(apiKey) && StringUtils.hasText(model));
	}

	@Override
	public String toString() {
		return "AiProperties[provider=" + provider + ", apiKey=<redacted>, model=" + model
				+ ", baseUrl=<configured>, timeout=" + timeout
				+ ", maxRetries=" + maxRetries
				+ ", maxResponseCharacters=" + maxResponseCharacters + "]";
	}
}
