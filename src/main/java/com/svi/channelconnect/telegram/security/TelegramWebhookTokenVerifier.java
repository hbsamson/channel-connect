package com.svi.channelconnect.telegram.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.svi.channelconnect.telegram.config.TelegramProperties;

public class TelegramWebhookTokenVerifier {
	public static final String HEADER_NAME = "X-Telegram-Bot-Api-Secret-Token";

	private final byte[] expectedToken;

	public TelegramWebhookTokenVerifier(TelegramProperties properties) {
		this.expectedToken = properties.webhookSecretToken().getBytes(StandardCharsets.UTF_8);
	}

	public boolean isValid(String token) {
		return token != null && token.matches("[A-Za-z0-9_-]{1,256}")
				&& MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
	}
}
