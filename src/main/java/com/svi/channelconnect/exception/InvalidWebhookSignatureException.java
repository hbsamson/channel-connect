package com.svi.channelconnect.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("Invalid Messenger webhook signature");
    }
}
