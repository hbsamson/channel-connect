package com.svi.channelconnect.whatsapp.messaging.whatsapp.config;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

@Validated
@Profile({"local", "test"})
@ConfigurationProperties(prefix = "outbound")
public record OutboundMessagingProperties(
		@NotBlank String localAuthToken,
		@NotEmpty Set<@Pattern(regexp = "[1-9]\\d{7,14}") String> allowedRecipients,
		@NotEmpty Set<@Pattern(regexp = "[a-z0-9_]{1,512}") String> allowedTemplates,
		@Min(1) @Max(100000) int idempotencyCapacity,
		@NotNull Duration idempotencyTtl
) {
	public OutboundMessagingProperties {
		allowedRecipients = allowedRecipients == null ? Set.of() : Set.copyOf(allowedRecipients);
		allowedTemplates = allowedTemplates == null ? Set.of() : Set.copyOf(allowedTemplates);
		if (idempotencyTtl != null && (idempotencyTtl.isZero() || idempotencyTtl.isNegative())) {
			throw new IllegalArgumentException("outbound.idempotency-ttl must be positive");
		}
	}

	@Override
	public String toString() {
		return "OutboundMessagingProperties[localAuthToken=<redacted>"
				+ ", allowedRecipients=<redacted>, allowedTemplates=" + allowedTemplates
				+ ", idempotencyCapacity=" + idempotencyCapacity
				+ ", idempotencyTtl=" + idempotencyTtl + "]";
	}
}
