package com.svi.channelconnect.messenger.service;

import com.svi.channelconnect.messenger.config.MessengerProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;

@Service
public class MessengerWebhookService {
    private final MessengerProperties properties;
    private final MessengerSignatureVerifier verifier;
    private final MessengerWebhookParser parser;
    private final MessengerWorkQueue queue;

    public MessengerWebhookService(MessengerProperties properties, MessengerSignatureVerifier verifier,
                                   MessengerWebhookParser parser, MessengerWorkQueue queue) {
        this.properties = properties;
        this.verifier = verifier;
        this.parser = parser;
        this.queue = queue;
    }

    public boolean verifySubscription(String mode, String token) {
        return "subscribe".equals(mode) && token != null && MessageDigest.isEqual(
                properties.verifyToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    public void accept(byte[] rawBody, String signature) {
        verifier.verify(rawBody, signature);
        queue.accept(parser.parse(rawBody));
    }
}
