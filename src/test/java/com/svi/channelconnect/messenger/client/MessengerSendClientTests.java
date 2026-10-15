package com.svi.channelconnect.messenger.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.messenger.config.MessengerProperties;
import com.svi.channelconnect.messenger.exception.ProviderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MessengerSendClientTests {

	@ParameterizedTest
	@ValueSource(strings = {"text/javascript", "text/plain", "application/octet-stream"})
	void acceptsJsonAcknowledgmentsRegardlessOfResponseMediaType(String mediaType) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		MessengerSendClient client = new MessengerSendClient(builder, new MessengerProperties("secret", "verify",
				"token", "page", "v99.0", 10));
		server.expect(requestTo("https://graph.facebook.com/v99.0/page/messages"))
				.andRespond(withSuccess("{\"message_id\":\"mid.TEST\"}", MediaType.parseMediaType(mediaType)));

		assertThat(client.send(new OutgoingMessageDTO("person", "Hello")).providerMessageId()).isEqualTo("mid.TEST");
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "not JSON", "{}", "{\"message_id\":\"\"}"})
	void rejectsSuccessfulResponsesWithoutAValidAcknowledgment(String body) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		MessengerSendClient client = new MessengerSendClient(builder, new MessengerProperties("secret", "verify",
				"token", "page", "v99.0", 10));
		server.expect(requestTo("https://graph.facebook.com/v99.0/page/messages"))
				.andRespond(withSuccess(body, MediaType.parseMediaType("text/javascript")));

		assertThatThrownBy(() -> client.send(new OutgoingMessageDTO("person", "Hello")))
				.isInstanceOfSatisfying(ProviderException.class,
						exception -> assertThat(exception.getCategory()).isEqualTo("invalid_response"));
		server.verify();
	}

	@Test
	void sendsAuthenticatedReplyWithResponseMessagingType() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		MessengerProperties properties = new MessengerProperties("test-app-secret", "test-verify-token",
				"test-page-token", "test-page", "v99.0", 10);
		MessengerSendClient client = new MessengerSendClient(builder, properties);
		server.expect(requestTo("https://graph.facebook.com/v99.0/test-page/messages"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-page-token"))
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(content().json("""
						{"recipient":{"id":"test-psid"},"messaging_type":"RESPONSE",
						 "message":{"text":"Choose a slot:\\nA: Friday, 9am"}}
						""", true))
				.andRespond(withSuccess("{\"recipient_id\":\"test-psid\",\"message_id\":\"mid.TEST\"}",
						MediaType.APPLICATION_JSON));

		SendMessageResponseDTO result = client.send(new OutgoingMessageDTO("test-psid",
				"Choose a slot:\nA: Friday, 9am"));

		assertThat(result.status()).isEqualTo("SENT");
		assertThat(result.providerMessageId()).isEqualTo("mid.TEST");
		server.verify();
	}
}
