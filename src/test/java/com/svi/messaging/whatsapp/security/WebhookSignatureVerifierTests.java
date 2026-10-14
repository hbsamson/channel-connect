package com.svi.messaging.whatsapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.svi.channelconnect.whatsapp.messaging.whatsapp.security.WebhookSignatureVerifier;
import com.svi.messaging.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTests {
	private WebhookSignatureVerifier verifier;

	@BeforeEach
	void setUp() {
		verifier = new WebhookSignatureVerifier(TestProperties.whatsApp());
	}

	@Test
	void acceptsValidSignature() throws Exception {
		byte[] body = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);

		assertThat(verifier.isSignatureValid(body, signature(body))).isTrue();
	}

	@Test
	void rejectsInvalidMissingMalformedAndModifiedSignatures() throws Exception {
		byte[] body = "original".getBytes(StandardCharsets.UTF_8);

		assertThat(verifier.isSignatureValid(body, null)).isFalse();
		assertThat(verifier.isSignatureValid(body, "sha256=not-hex")).isFalse();
		assertThat(verifier.isSignatureValid(body, "sha1=" + "0".repeat(64))).isFalse();
		assertThat(verifier.isSignatureValid("modified".getBytes(StandardCharsets.UTF_8), signature(body)))
				.isFalse();
	}

	@Test
	void validatesVerificationToken() {
		assertThat(verifier.isVerificationTokenValid("test-verify-token")).isTrue();
		assertThat(verifier.isVerificationTokenValid("wrong-token")).isFalse();
		assertThat(verifier.isVerificationTokenValid(null)).isFalse();
	}

	private String signature(byte[] body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
	}
}
