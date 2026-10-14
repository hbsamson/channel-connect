package com.svi.channelconnect.whatsapp.exception;

public class MalformedWebhookException extends RuntimeException {
	public MalformedWebhookException(String message) {
		super(message);
	}

	public MalformedWebhookException(String message, Throwable cause) {
		super(message, cause);
	}
}
