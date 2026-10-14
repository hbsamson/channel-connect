package com.svi.channelconnect.whatsapp.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;

@Component
public class WebhookSignatureVerifier {
	private static final String SIGNATURE_PREFIX = "sha256=";

	private final byte[] appSecret;
	private final byte[] verificationToken;

	public WebhookSignatureVerifier(WhatsAppProperties properties) {
		this.appSecret = properties.appSecret().getBytes(StandardCharsets.UTF_8);
		this.verificationToken = properties.verifyToken().getBytes(StandardCharsets.UTF_8);
	}

	public boolean isVerificationTokenValid(String candidate) {
		return candidate != null && MessageDigest.isEqual(
				verificationToken,
				candidate.getBytes(StandardCharsets.UTF_8));
	}

	public boolean isSignatureValid(byte[] requestBody, String signatureHeader) {
		if (requestBody == null || signatureHeader == null
				|| !signatureHeader.matches("sha256=[0-9a-fA-F]{64}")) {
			return false;
		}

		try {
			byte[] supplied = HexFormat.of().parseHex(signatureHeader.substring(SIGNATURE_PREFIX.length()));
			Mac hmac = Mac.getInstance("HmacSHA256");
			hmac.init(new SecretKeySpec(appSecret, "HmacSHA256"));
			return MessageDigest.isEqual(hmac.doFinal(requestBody), supplied);
		}
		catch (IllegalArgumentException | GeneralSecurityException exception) {
			return false;
		}
	}
}
