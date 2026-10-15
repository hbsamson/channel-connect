package com.svi.channelconnect.telegram.config;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

@Validated
@Profile({"local", "test"})
@ConditionalOnProperty(name = {"telegram.enabled", "telegram.campaign.enabled"}, havingValue = "true")
@ConfigurationProperties("telegram.campaign")
public record TelegramCampaignProperties(
		@NotBlank @Size(min = 32, max = 256) @Pattern(regexp = "[A-Za-z0-9_-]+") String apiToken,
		@NotEmpty Set<@Pattern(regexp = "[1-9][0-9]{0,18}(:[1-9][0-9]{0,18})?") String> allowedRecipients,
		@DefaultValue({"opening", "followup1", "followup2", "script1", "script3"})
		@NotEmpty Set<@Pattern(regexp = "opening|followup1|followup2|script1|script3") String> allowedScripts,
		@DefaultValue("1000") @Min(1) @Max(100000) int idempotencyCapacity,
		@DefaultValue("24h") @NotNull Duration idempotencyTtl
) {
	public TelegramCampaignProperties {
		allowedRecipients = allowedRecipients == null ? Set.of() : Set.copyOf(allowedRecipients);
		allowedScripts = allowedScripts == null ? Set.of() : Set.copyOf(allowedScripts);
	}

	@AssertTrue(message = "Telegram campaign idempotency TTL must be between one minute and seven days")
	public boolean isTtlValid() {
		return idempotencyTtl != null && idempotencyTtl.compareTo(Duration.ofMinutes(1)) >= 0
				&& idempotencyTtl.compareTo(Duration.ofDays(7)) <= 0;
	}

	@Override
	public String toString() {
		return "TelegramCampaignProperties[apiToken=<redacted>, allowedRecipients=<redacted>, allowedScripts="
				+ allowedScripts + ", idempotencyCapacity=" + idempotencyCapacity
				+ ", idempotencyTtl=" + idempotencyTtl + "]";
	}
}
