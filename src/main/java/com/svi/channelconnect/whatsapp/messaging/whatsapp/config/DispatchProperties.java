package com.svi.channelconnect.whatsapp.messaging.whatsapp.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "dispatch")
public record DispatchProperties(
		@Min(1) @Max(32) int workerCount,
		@Min(1) @Max(10000) int queueCapacity,
		@Min(1) @Max(1000000) int deduplicationCapacity,
		@NotNull Duration deduplicationTtl,
		@NotNull Duration shutdownTimeout
) {
}
