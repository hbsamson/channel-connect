package com.svi.channelconnect.messenger.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("Invalid Messenger webhook signature");
    }
}
