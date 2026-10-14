package com.svi.channelconnect.whatsapp.messaging.messenger.exception;

public class MalformedWebhookException extends RuntimeException {
    public MalformedWebhookException() {
        super("Malformed Messenger webhook payload");
    }
}
