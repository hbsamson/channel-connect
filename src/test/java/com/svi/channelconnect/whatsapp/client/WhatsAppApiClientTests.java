package com.svi.channelconnect.whatsapp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.whatsapp.client.WhatsAppApiClient;
import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;
import com.svi.channelconnect.whatsapp.exception.WhatsAppApiException;
import com.svi.channelconnect.support.TestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WhatsAppApiClientTests {
	private MockRestServiceServer server;
	private HttpServer httpServer;

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.verify();
		}
		if (httpServer != null) {
			httpServer.stop(0);
		}
	}

	@Test
	void sendsCorrectAuthenticatedTextMessage() {
		WhatsAppApiClient client = client(TestProperties.whatsApp());
		server.expect(once(), requestTo("https://graph.example.test/v99.0/123456789/messages"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
				.andExpect(content().json("""
						{"messaging_product":"whatsapp","recipient_type":"individual","to":"15551234567",
						 "type":"text","text":{"preview_url":false,"body":"Hello"}}
						"""))
				.andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.OUTBOUND\"}]}", MediaType.APPLICATION_JSON));

		assertThat(client.sendTextMessage("15551234567", "Hello")).isEqualTo("wamid.OUTBOUND");
	}

	@Test
	void sendsCorrectParameterlessTemplateMessage() {
		WhatsAppApiClient client = client(TestProperties.whatsApp());
		server.expect(once(), requestTo("https://graph.example.test/v99.0/123456789/messages"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
				.andExpect(content().json("""
						{"messaging_product":"whatsapp","recipient_type":"individual","to":"639123456789",
						 "type":"template","template":{"name":"hello_world","language":{"code":"en_US"}}}
						""", true))
				.andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.TEMPLATE\"}]}",
						MediaType.APPLICATION_JSON));

		assertThat(client.sendTemplateMessage(
				"639123456789", "hello_world", "en_US", List.of()))
				.isEqualTo("wamid.TEMPLATE");
	}

	@Test
	void sendsTemplateBodyTextParametersInOrder() {
		WhatsAppApiClient client = client(TestProperties.whatsApp());
		server.expect(once(), requestTo("https://graph.example.test/v99.0/123456789/messages"))
				.andExpect(content().json("""
						{"messaging_product":"whatsapp","recipient_type":"individual","to":"15551234567",
						 "type":"template","template":{"name":"order_update","language":{"code":"en_US"},
						 "components":[{"type":"body","parameters":[
						 {"type":"text","text":"Dwyght"},{"type":"text","text":"A-123"}]}]}}
						""", true))
				.andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.PARAMETERS\"}]}",
						MediaType.APPLICATION_JSON));

		assertThat(client.sendTemplateMessage(
				"15551234567", "order_update", "en_US", List.of("Dwyght", "A-123")))
				.isEqualTo("wamid.PARAMETERS");
	}

	@Test
	void rejectsInvalidTemplateMessageBeforeCallingMeta() {
		WhatsAppApiClient client = client(TestProperties.whatsApp());

		assertThatThrownBy(() -> client.sendTemplateMessage(
				"+639123456789", "hello_world", "en_US", List.of()))
				.isInstanceOfSatisfying(WhatsAppApiException.class,
						exception -> assertThat(exception.category())
								.isEqualTo(ExternalFailureCategory.INVALID_REQUEST));
	}

	@Test
	void retriesOnlyExplicitRateLimitResponse() {
		WhatsAppApiClient client = client(TestProperties.whatsApp());
		String url = "https://graph.example.test/v99.0/123456789/messages";
		server.expect(once(), requestTo(url))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "0"));
		server.expect(once(), requestTo(url))
				.andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.RETRIED\"}]}", MediaType.APPLICATION_JSON));

		assertThat(client.sendTemplateMessage(
				"15551234567", "hello_world", "en_US", List.of()))
				.isEqualTo("wamid.RETRIED");
	}

	@Test
	void reportsRateLimitWhenBoundedRetriesAreExhausted() {
		WhatsAppApiClient client = client(TestProperties.whatsApp());
		String url = "https://graph.example.test/v99.0/123456789/messages";
		server.expect(once(), requestTo(url))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "0"));
		server.expect(once(), requestTo(url))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> client.sendTemplateMessage(
				"15551234567", "hello_world", "en_US", List.of()))
				.isInstanceOfSatisfying(WhatsAppApiException.class,
						exception -> assertThat(exception.category())
								.isEqualTo(ExternalFailureCategory.THROTTLED));
	}

	@Test
	void categorizesCredentialsServerErrorsAndMalformedResponses() {
		WhatsAppApiClient authenticationClient = client(TestProperties.whatsApp());
		server.expect(once(), requestTo("https://graph.example.test/v99.0/123456789/messages"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		assertThatThrownBy(() -> authenticationClient.sendTemplateMessage(
				"15551234567", "hello_world", "en_US", List.of()))
				.isInstanceOfSatisfying(WhatsAppApiException.class,
						exception -> assertThat(exception.category()).isEqualTo(ExternalFailureCategory.AUTHENTICATION));
		server.verify();

		WhatsAppApiClient serverErrorClient = client(TestProperties.whatsApp());
		server.expect(once(), requestTo("https://graph.example.test/v99.0/123456789/messages"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		assertThatThrownBy(() -> serverErrorClient.sendTextMessage("15551234567", "Hello"))
				.isInstanceOfSatisfying(WhatsAppApiException.class,
						exception -> assertThat(exception.category()).isEqualTo(ExternalFailureCategory.TRANSIENT));
		server.verify();

		WhatsAppApiClient malformedClient = client(TestProperties.whatsApp());
		server.expect(once(), requestTo("https://graph.example.test/v99.0/123456789/messages"))
				.andRespond(withSuccess("{\"messages\":[]}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> malformedClient.sendTextMessage("15551234567", "Hello"))
				.isInstanceOfSatisfying(WhatsAppApiException.class,
						exception -> assertThat(exception.category()).isEqualTo(ExternalFailureCategory.MALFORMED_RESPONSE));
	}

	@Test
	void categorizesReadTimeoutWithoutRetryingAmbiguousSend() throws IOException {
		httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		httpServer.createContext("/v99.0/123456789/messages", exchange -> {
			try {
				Thread.sleep(500);
				exchange.sendResponseHeaders(200, 0);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			finally {
				exchange.close();
			}
		});
		httpServer.start();
		URI baseUrl = URI.create("http://" + httpServer.getAddress().getHostString()
				+ ":" + httpServer.getAddress().getPort());
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(Duration.ofMillis(50));
		RestClient restClient = RestClient.builder().baseUrl(baseUrl.toString())
				.requestFactory(requestFactory).build();
		WhatsAppApiClient client = new WhatsAppApiClient(restClient, TestProperties.whatsApp(baseUrl));

		assertThatThrownBy(() -> client.sendTemplateMessage(
				"15551234567", "hello_world", "en_US", List.of()))
				.isInstanceOfSatisfying(WhatsAppApiException.class,
						exception -> assertThat(exception.category()).isEqualTo(ExternalFailureCategory.TIMEOUT));
	}

	private WhatsAppApiClient client(WhatsAppProperties properties) {
		RestClient.Builder builder = RestClient.builder()
				.baseUrl(properties.graphBaseUrl().toString())
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken());
		server = MockRestServiceServer.bindTo(builder).build();
		return new WhatsAppApiClient(builder.build(), properties);
	}
}
