package com.svi.channelconnect.whatsapp.messaging.messenger.exception;

public class QueueUnavailableException extends RuntimeException {
    public QueueUnavailableException() {
        super("Messenger processing queue is full");
    }
}
