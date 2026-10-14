package com.svi.channelconnect.messenger.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.messenger.config.MessengerProperties;
import com.svi.channelconnect.messenger.exception.InvalidWebhookSignatureException;

@Component
public class MessengerSignatureVerifier {
    private final MessengerProperties properties;

    public MessengerSignatureVerifier(MessengerProperties properties) {
        this.properties = properties;
    }

    public void verify(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            throw new InvalidWebhookSignatureException();
        }
        try {
            byte[] provided = HexFormat.of().parseHex(signatureHeader.substring("sha256=".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(rawBody), provided)) {
                throw new InvalidWebhookSignatureException();
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidWebhookSignatureException();
        } catch (Exception exception) {
            if (exception instanceof InvalidWebhookSignatureException invalid) {
                throw invalid;
            }
            throw new IllegalStateException("Cannot verify Messenger signature", exception);
        }
    }
}
