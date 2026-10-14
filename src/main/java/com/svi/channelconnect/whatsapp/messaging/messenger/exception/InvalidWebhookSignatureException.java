package com.svi.channelconnect.whatsapp.messaging.messenger.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("Invalid Messenger webhook signature");
    }
}
