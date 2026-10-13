package com.svi.channelconnect.messenger.exception;

public class MalformedWebhookException extends RuntimeException {
    public MalformedWebhookException() {
        super("Malformed Messenger webhook payload");
    }
}
