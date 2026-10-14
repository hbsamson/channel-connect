package com.svi.messaging.support;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import com.svi.channelconnect.whatsapp.messaging.ai.config.AiProperties;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.config.DispatchProperties;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.config.OutboundMessagingProperties;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.config.WhatsAppProperties;

public final class TestProperties {
	private TestProperties() {
	}

	public static WhatsAppProperties whatsApp() {
		return new WhatsAppProperties(
				URI.create("https://graph.example.test"), "v99.0", "123456789",
				"test-access-token", "test-app-secret", "test-verify-token",
				2, Duration.ZERO, 262144);
	}

	public static WhatsAppProperties whatsApp(URI baseUrl) {
		WhatsAppProperties defaults = whatsApp();
		return new WhatsAppProperties(
				baseUrl, defaults.graphApiVersion(), defaults.phoneNumberId(),
				defaults.accessToken(), defaults.appSecret(), defaults.verifyToken(),
				defaults.outboundMaxAttempts(), defaults.outboundMaxRetryDelay(),
				defaults.webhookMaxRequestBytes());
	}

	public static AiProperties mockAi() {
		return new AiProperties(AiProperties.Provider.MOCK, "", "",
				URI.create("https://api.openai.com/v1"), Duration.ofSeconds(1), 0, 4096);
	}

	public static AiProperties openAi() {
		return new AiProperties(AiProperties.Provider.OPENAI, "test-openai-key", "test-model",
				URI.create("https://api.openai.com/v1"), Duration.ofSeconds(1), 0, 4096);
	}

	public static DispatchProperties dispatch(int workers, int queueCapacity) {
		return new DispatchProperties(
				workers, queueCapacity, 100, Duration.ofHours(1), Duration.ofSeconds(1));
	}

	public static OutboundMessagingProperties outbound() {
		return outbound(100);
	}

	public static OutboundMessagingProperties outbound(int idempotencyCapacity) {
		return new OutboundMessagingProperties(
				"test-local-auth-token",
				Set.of("15551234567", "639123456789"),
				Set.of("hello_world", "order_update"),
				idempotencyCapacity, Duration.ofHours(1));
	}
}
