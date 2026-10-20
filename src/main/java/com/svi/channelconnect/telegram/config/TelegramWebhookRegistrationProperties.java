package com.svi.channelconnect.telegram.config;

import java.net.URI;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "telegram.webhook")
@ConditionalOnProperty(name = {"telegram.enabled", "telegram.webhook.auto-register"}, havingValue = "true")
public record TelegramWebhookRegistrationProperties(String url) {
	@AssertTrue(message = "TELEGRAM_WEBHOOK_URL must be an HTTPS URL ending in /webhooks/telegram without credentials, a query or a fragment")
	public boolean isUrlValid() {
		if (url == null || url.isBlank()) return false;
		try {
			URI address = URI.create(url);
			return "https".equalsIgnoreCase(address.getScheme()) && address.getHost() != null
					&& address.getRawUserInfo() == null && address.getRawQuery() == null
					&& address.getRawFragment() == null && address.getRawPath() != null
					&& address.getRawPath().endsWith("/webhooks/telegram");
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@Override
	public String toString() {
		return "TelegramWebhookRegistrationProperties[url=<redacted>]";
	}
}
