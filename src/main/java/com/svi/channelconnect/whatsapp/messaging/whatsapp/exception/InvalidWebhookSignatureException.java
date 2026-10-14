package com.svi.channelconnect.whatsapp.messaging.whatsapp.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
	public InvalidWebhookSignatureException() {
		super("Webhook authentication failed");
	}
}
