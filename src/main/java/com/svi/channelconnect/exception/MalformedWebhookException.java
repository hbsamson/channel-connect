package com.svi.channelconnect.exception;

public class MalformedWebhookException extends RuntimeException {
    public MalformedWebhookException() {
        super("Malformed Messenger webhook payload");
    }
}
