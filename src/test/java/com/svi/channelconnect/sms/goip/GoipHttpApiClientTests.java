package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.svi.channelconnect.sms.config.GoipHttpApiProperties;
import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.provider.SmsProvider;
import com.svi.channelconnect.support.SmsTestProperties;

class GoipHttpApiClientTests {
	private HttpServer server;
	private URI endpoint;
	private final AtomicReference<String> method = new AtomicReference<>();
	private final AtomicReference<String> query = new AtomicReference<>();
	private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
	private final AtomicReference<byte[]> responseBody = new AtomicReference<>("<html>Management form</html>".getBytes(StandardCharsets.UTF_8));
	private final AtomicBoolean transmissionMarked = new AtomicBoolean();
	private final AtomicBoolean markerPresentAtRequest = new AtomicBoolean();
	private final AtomicInteger requestCount = new AtomicInteger();

	@BeforeEach void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/gateway.php", exchange -> {
			requestCount.incrementAndGet();
			method.set(exchange.getRequestMethod());
			query.set(exchange.getRequestURI().getRawQuery());
			requestBody.set(exchange.getRequestBody().readAllBytes());
			markerPresentAtRequest.set(transmissionMarked.get());
			byte[] body = responseBody.get();
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) { output.write(body); }
		});
		server.start();
		endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway.php");
	}
	@AfterEach void stopServer() { server.stop(0); }

	@Test
	void followsCollectionPostQueryAndDoesNotConfirmHtmlOrReplay() {
		var properties = smsProperties(Map.of());
		var client = new GoipHttpApiClient(apiProperties(1048576), properties);
		var provider = new GoipHttpApiSmsProvider(properties, client);
		var message = new SmsOutbound("synthetic-request", "sim-2", "+639000000001", "Book Tue? A&B + vehicle", 1, 0, "CLAIMED", "key", null);
		var result = provider.send(message, () -> transmissionMarked.set(true));
		assertThat(result.status()).isEqualTo("UNKNOWN");
		assertThat(result.errorCode()).isEqualTo("HTTP_API_SUBMISSION_UNCONFIRMED");
		assertThat(method.get()).isEqualTo("POST");
		assertThat(parameters(query.get())).containsExactlyInAnyOrderEntriesOf(Map.of(
				"line", "2", "message", "Book Tue? A&B + vehicle", "number", "09000000001"));
		assertThat(requestBody.get()).isEmpty();
		assertThat(markerPresentAtRequest.get()).isTrue();
		assertThat(requestCount.get()).isEqualTo(1);
	}

	@Test
	void springSelectsOnlyTheRemoteProviderWithoutOptInUdpReception() {
		new ApplicationContextRunner().withPropertyValues("sms.enabled=true", "sms.mode=HTTP_API", "sms.http-inbound.enabled=false")
				.withBean(com.svi.channelconnect.sms.config.SmsProperties.class, () -> smsProperties(Map.of()))
				.withBean(GoipHttpApiProperties.class, () -> apiProperties(1048576))
				.withUserConfiguration(GoipHttpApiClient.class, GoipHttpApiSmsProvider.class, GoipHttpSmsProvider.class,
						GoipSmsProvider.class, GoipHttpInboundListener.class)
				.run(context -> {
					assertThat(context).hasSingleBean(SmsProvider.class).hasSingleBean(GoipHttpApiClient.class)
							.doesNotHaveBean(GoipSmsProvider.class).doesNotHaveBean(GoipHttpInboundListener.class);
					assertThat(context.getBean(SmsProvider.class)).isInstanceOf(GoipHttpApiSmsProvider.class);
				});
	}

	@Test
	void combinesDirectSendingWithAReceiveOnlyHostedClient() throws Exception {
		var properties = smsProperties(Map.of("sms.mode", "HTTP", "sms.channels[0].source-address", "192.168.1.20",
				"sms.channels[1].source-address", "192.168.1.20"));
		var api = new GoipHttpApiProperties(null, endpoint, "synthetic-receive-key", 1048576, null);
		new ApplicationContextRunner().withPropertyValues("sms.enabled=true", "sms.mode=HTTP", "sms.http-api.receive-enabled=true")
				.withBean(com.svi.channelconnect.sms.config.SmsProperties.class, () -> properties)
				.withBean(GoipHttpApiProperties.class, () -> api)
				.withBean(com.svi.channelconnect.sms.persistence.SmsMessageRepository.class, () -> mock(com.svi.channelconnect.sms.persistence.SmsMessageRepository.class))
				.withBean(com.svi.channelconnect.dealership.persistence.DealershipRepository.class, () -> mock(com.svi.channelconnect.dealership.persistence.DealershipRepository.class))
				.withBean(tools.jackson.databind.ObjectMapper.class, tools.jackson.databind.ObjectMapper::new)
				.withBean(io.micrometer.core.instrument.MeterRegistry.class, io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
				.withBean(java.time.Clock.class, java.time.Clock::systemUTC)
				.withUserConfiguration(GoipHttpApiClient.class, GoipHttpApiSmsProvider.class, GoipHttpSmsProvider.class, GoipHttpApiReceivePoller.class)
				.run(context -> {
					assertThat(context).hasSingleBean(SmsProvider.class).hasSingleBean(GoipHttpApiClient.class).hasSingleBean(GoipHttpApiReceivePoller.class);
					assertThat(context.getBean(SmsProvider.class)).isInstanceOf(GoipHttpSmsProvider.class);
				});
		var client = new GoipHttpApiClient(api, properties);
		assertThat(client.receive().statusCode()).isEqualTo(200);
		assertThat(method.get()).isEqualTo("GET");
		assertThat(parameters(query.get())).containsEntry("action", "message");
		assertThatThrownBy(() -> client.send(1, "09000000001", "Must not send", () -> {}))
				.isInstanceOf(IllegalStateException.class).hasMessage("SMS HTTP API send URL is not configured");
		assertThat(requestCount.get()).isEqualTo(1);
	}

	@Test
	void retrievesReceiveSnapshotWithEncodedPrivateKeyAndRedactedRendering() throws Exception {
		responseBody.set("{\"success\":true,\"count\":0,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
		var client = new GoipHttpApiClient(apiProperties(1048576), smsProperties(Map.of()));
		var response = client.receive();
		assertThat(method.get()).isEqualTo("GET");
		assertThat(parameters(query.get())).containsExactlyInAnyOrderEntriesOf(Map.of("action", "message", "key", "synthetic key & +"));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"success\":true");
		assertThat(response.toString()).doesNotContain("messages", "success");
		assertThat(apiProperties(1048576).toString()).doesNotContain("gateway.php", "synthetic key");
	}

	@Test
	void rejectsOversizedAndInvalidUtf8ResponseWithoutExposingBody() {
		responseBody.set("sensitive-response".repeat(20).getBytes(StandardCharsets.UTF_8));
		var limited = new GoipHttpApiClient(apiProperties(32), smsProperties(Map.of()));
		assertThatThrownBy(limited::receive).isInstanceOf(IOException.class).hasMessage("HTTP_API_REQUEST_FAILED").hasNoCause();
		responseBody.set(new byte[] {(byte) 0xC3, 0x28});
		assertThatThrownBy(limited::receive).isInstanceOf(IOException.class).hasMessage("HTTP_API_RESPONSE_INVALID").hasNoCause();
	}

	@Test
	@SuppressWarnings("unchecked")
	void cancelsTimedOutRequestWithoutRetrying() throws Exception {
		HttpClient http = mock(HttpClient.class);
		CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
		when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
		var client = new GoipHttpApiClient(apiProperties(1048576), smsProperties(Map.of("sms.session-timeout", "20ms")), http);
		assertThatThrownBy(client::receive).isInstanceOf(IOException.class).hasMessage("HTTP_API_REQUEST_TIMEOUT").hasNoCause();
		assertThat(pending).isCancelled();
		verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	void requiresSecureConfiguredUrlsAndExplicitHardwareAuthorization() {
		assertThatThrownBy(() -> new GoipHttpApiProperties(URI.create("http://remote.example/gateway.php"), null, "", 1048576, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GoipHttpApiProperties(URI.create("https://remote.example/gateway.php?private=query"), null, "", 1048576, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> smsProperties(Map.of("sms.live-authorized", "false"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
	}

	private GoipHttpApiProperties apiProperties(int maximumBytes) {
		return new GoipHttpApiProperties(endpoint, endpoint, "synthetic key & +", maximumBytes, null);
	}
	private static com.svi.channelconnect.sms.config.SmsProperties smsProperties(Map<String, String> overrides) {
		var values = new LinkedHashMap<String, String>();
		values.put("sms.mode", "HTTP_API");
		values.put("sms.live-authorized", "true");
		values.put("sms.session-timeout", "2s");
		values.putAll(overrides);
		return SmsTestProperties.properties(values);
	}
	private static Map<String, String> parameters(String query) {
		var parameters = new LinkedHashMap<String, String>();
		for (String field : query.split("&")) {
			String[] pair = field.split("=", 2);
			parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
		}
		return parameters;
	}
}
