package com.svi.messaging.whatsapp.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.svi.channelconnect.whatsapp.messaging.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.controller.OutboundMessageController;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.request.OutboundTemplateMessageRequest;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.response.OutboundMessageResponse;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.GlobalExceptionHandler;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.OutboundAuthorizationException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.WhatsAppApiException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.security.LocalOutboundAuthorizationInterceptor;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.service.OutboundMessageService;
import com.svi.messaging.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OutboundMessageControllerTests {
	private MockMvc mockMvc;
	private OutboundMessageService service;

	@BeforeEach
	void setUp() {
		service = mock(OutboundMessageService.class);
		var controller = new OutboundMessageController(service);
		var authorization = new LocalOutboundAuthorizationInterceptor(TestProperties.outbound());
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.addInterceptors(authorization)
				.build();
	}

	@Test
	void acceptsAuthenticatedTemplateSubmission() throws Exception {
		when(service.sendTemplateMessage(eq("request-123"), any(OutboundTemplateMessageRequest.class)))
				.thenReturn(OutboundMessageResponse.accepted("wamid.ACCEPTED"));

		mockMvc.perform(post("/api/v1/whatsapp/messages")
					.header(HttpHeaders.AUTHORIZATION, "Bearer test-local-auth-token")
					.header("Idempotency-Key", "request-123")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"recipient":"639123456789","templateName":"hello_world","languageCode":"en_US"}
							"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.providerMessageId").value("wamid.ACCEPTED"))
				.andExpect(jsonPath("$.submissionStatus").value("ACCEPTED_BY_PROVIDER"))
				.andExpect(jsonPath("$.idempotentReplay").value(false));
	}

	@Test
	void rejectsMissingAndInvalidAuthorizationBeforeCallingService() throws Exception {
		String body = """
				{"recipient":"639123456789","templateName":"hello_world","languageCode":"en_US"}
				""";
		mockMvc.perform(post("/api/v1/whatsapp/messages")
					.header("Idempotency-Key", "request-123")
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/whatsapp/messages")
					.header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
					.header("Idempotency-Key", "request-123")
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
		verify(service, never()).sendTemplateMessage(any(), any());
	}

	@Test
	void rejectsInvalidRecipientAndMissingTemplateFields() throws Exception {
		mockMvc.perform(authorizedRequest("validation-key", """
				{"recipient":"+639123456789","templateName":"","languageCode":""}
				"""))
				.andExpect(status().isBadRequest());
		verify(service, never()).sendTemplateMessage(any(), any());
	}

	@Test
	void rejectsMissingIdempotencyKey() throws Exception {
		mockMvc.perform(post("/api/v1/whatsapp/messages")
					.header(HttpHeaders.AUTHORIZATION, "Bearer test-local-auth-token")
					.contentType(MediaType.APPLICATION_JSON)
					.content(validBody()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void mapsAllowlistAndProviderFailuresWithoutLeakingDetails() throws Exception {
		when(service.sendTemplateMessage(eq("forbidden-key"), any()))
				.thenThrow(new OutboundAuthorizationException());
		when(service.sendTemplateMessage(eq("auth-failure-key"), any()))
				.thenThrow(new WhatsAppApiException(
						ExternalFailureCategory.AUTHENTICATION, "secret provider response"));

		mockMvc.perform(authorizedRequest("forbidden-key", validBody()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("Outbound request is not permitted"));
		mockMvc.perform(authorizedRequest("auth-failure-key", validBody()))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error").value("WhatsApp provider authentication failed"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizedRequest(
			String idempotencyKey, String body) {
		return post("/api/v1/whatsapp/messages")
				.header(HttpHeaders.AUTHORIZATION, "Bearer test-local-auth-token")
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private String validBody() {
		return """
				{"recipient":"639123456789","templateName":"hello_world","languageCode":"en_US"}
				""";
	}
}
