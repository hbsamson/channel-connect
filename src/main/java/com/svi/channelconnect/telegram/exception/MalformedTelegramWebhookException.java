package com.svi.channelconnect.telegram.exception;

public class MalformedTelegramWebhookException extends RuntimeException {
	public MalformedTelegramWebhookException() {
		super("Malformed Telegram webhook payload");
	}
}
