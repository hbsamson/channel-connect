package com.svi.channelconnect.whatsapp.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.svi.channelconnect.whatsapp.controller.WhatsAppWebhookController;
import com.svi.channelconnect.whatsapp.exception.GlobalExceptionHandler;
import com.svi.channelconnect.whatsapp.security.WebhookRequestSizeFilter;
import com.svi.channelconnect.whatsapp.security.WebhookSignatureVerifier;
import com.svi.channelconnect.whatsapp.service.WhatsAppWebhookService;
import com.svi.channelconnect.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WhatsAppWebhookControllerTests {
	private MockMvc mockMvc;
	private WhatsAppWebhookService webhookService;

	@BeforeEach
	void setUp() {
		webhookService = mock(WhatsAppWebhookService.class);
		var properties = TestProperties.whatsApp();
		var controller = new WhatsAppWebhookController(
				new WebhookSignatureVerifier(properties), webhookService);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.addFilters(new WebhookRequestSizeFilter(properties))
				.build();
	}

	@Test
	void returnsChallengeForCorrectVerificationToken() throws Exception {
		mockMvc.perform(get("/webhooks/whatsapp")
					.param("hub.mode", "subscribe")
					.param("hub.verify_token", "test-verify-token")
					.param("hub.challenge", "challenge-value"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
				.andExpect(content().string("challenge-value"));
	}

	@Test
	void rejectsIncorrectAndMissingVerificationParameters() throws Exception {
		mockMvc.perform(get("/webhooks/whatsapp")
					.param("hub.mode", "subscribe")
					.param("hub.verify_token", "wrong")
					.param("hub.challenge", "challenge"))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/webhooks/whatsapp"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void acceptsValidSignedPayload() throws Exception {
		byte[] body = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}".getBytes(StandardCharsets.UTF_8);
		when(webhookService.handle(body)).thenReturn(
				new WhatsAppWebhookService.WebhookHandlingResult(1, 0, 0, 0, 0, 0));

		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-Hub-Signature-256", signature(body))
					.content(body))
				.andExpect(status().isAccepted());
	}

	@Test
	void rejectsMissingMalformedInvalidAndModifiedSignatures() throws Exception {
		byte[] original = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
		byte[] modified = "{\"value\":2}".getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON).content(original))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-Hub-Signature-256", "malformed").content(original))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-Hub-Signature-256", "sha256=" + "0".repeat(64)).content(original))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-Hub-Signature-256", signature(original)).content(modified))
				.andExpect(status().isUnauthorized());

		verify(webhookService, never()).handle(any());
	}

	@Test
	void reportsIgnoredAndOverloadedPayloads() throws Exception {
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
		when(webhookService.handle(body))
				.thenReturn(new WhatsAppWebhookService.WebhookHandlingResult(0, 0, 0, 1, 0, 0))
				.thenReturn(new WhatsAppWebhookService.WebhookHandlingResult(0, 0, 1, 0, 0, 0));

		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-Hub-Signature-256", signature(body)).content(body))
				.andExpect(status().isOk());
		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-Hub-Signature-256", signature(body)).content(body))
				.andExpect(status().isServiceUnavailable());
	}

	@Test
	void rejectsOversizedPayloadBeforeSignatureVerification() throws Exception {
		byte[] body = new byte[TestProperties.whatsApp().webhookMaxRequestBytes() + 1];

		mockMvc.perform(post("/webhooks/whatsapp")
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isPayloadTooLarge());
		verify(webhookService, never()).handle(any());
	}

	private String signature(byte[] body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
	}
}
