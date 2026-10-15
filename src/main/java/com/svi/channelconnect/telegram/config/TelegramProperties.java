package com.svi.channelconnect.telegram.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "telegram")
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
public record TelegramProperties(
		@NotBlank String botToken,
		@NotBlank String webhookSecretToken,
		@DefaultValue("3s") @NotNull
		Duration connectTimeout,
		@DefaultValue("10s") @NotNull
		Duration readTimeout,
		@DefaultValue("262144") @Min(1024) @Max(1048576) int webhookMaxRequestBytes,
		@DefaultValue("4") @Min(1) @Max(32) int workerCount,
		@DefaultValue("25") @Min(1) @Max(10000) int queueCapacityPerWorker,
		@DefaultValue("10000") @Min(1) @Max(1000000) int deduplicationCapacity,
		@DefaultValue("24h") @NotNull
		Duration deduplicationTtl,
		@DefaultValue("10s") @NotNull
		Duration shutdownTimeout
) {
	@AssertTrue(message = "Telegram credential formats are invalid")
	public boolean isCredentialsValid() {
		return botToken != null && botToken.matches("[0-9]+:[A-Za-z0-9_-]+")
				&& webhookSecretToken != null && webhookSecretToken.matches("[A-Za-z0-9_-]{1,256}");
	}

	@AssertTrue(message = "Telegram durations must be within supported ranges")
	public boolean isDurationsValid() {
		return within(connectTimeout, Duration.ofSeconds(30))
				&& within(readTimeout, Duration.ofSeconds(60))
				&& within(deduplicationTtl, Duration.ofDays(7))
				&& within(shutdownTimeout, Duration.ofSeconds(60));
	}

	private boolean within(Duration value, Duration maximum) {
		return value != null && value.compareTo(Duration.ofSeconds(1)) >= 0
				&& value.compareTo(maximum) <= 0;
	}

	@Override
	public String toString() {
		return "TelegramProperties[botToken=<redacted>, webhookSecretToken=<redacted>"
				+ ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
				+ ", webhookMaxRequestBytes=" + webhookMaxRequestBytes
				+ ", workerCount=" + workerCount + ", queueCapacityPerWorker=" + queueCapacityPerWorker
				+ ", deduplicationCapacity=" + deduplicationCapacity
				+ ", deduplicationTtl=" + deduplicationTtl + ", shutdownTimeout=" + shutdownTimeout + "]";
	}
}
