package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.AiResponse;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.model.ChannelIdentity;
import com.svi.channelconnect.dealership.model.CustomerProfile;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.messenger.client.MessengerSendClient;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.provider.SmsProvider;
import com.svi.channelconnect.sms.service.SmsValidation;
import com.svi.channelconnect.sms.service.SmsWorkDispatcher;
import com.svi.channelconnect.whatsapp.client.WhatsAppApiClient;

/** Full application wiring with a synthetic local PHP gateway and mocked AI/Meta boundaries. */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:smshttpapiconversation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"sms.enabled=true", "sms.mode=HTTP_API", "sms.live-authorized=true", "sms.api-token=test-sms-api-token",
		"sms.session-timeout=2s", "sms.poll-interval=600s", "sms.shutdown-timeout=2s",
		"sms.dispatch-delay-ms=600000", "sms.maintenance-delay-ms=600000",
		"sms.channels[0].account-id=sim-1", "sms.channels[0].device-id=synthetic-goip", "sms.channels[0].sim-channel=1",
		"sms.channels[0].source-address=127.0.0.1", "sms.channels[0].auth-id=unused-web-id", "sms.channels[0].password=unused-web-password", "sms.channels[0].send-interval=1ms",
		"sms.channels[1].account-id=sim-2", "sms.channels[1].device-id=synthetic-goip", "sms.channels[1].sim-channel=2",
		"sms.channels[1].source-address=127.0.0.1", "sms.channels[1].auth-id=unused-web-id", "sms.channels[1].password=unused-web-password", "sms.channels[1].send-interval=1ms",
		"ai.provider=openai", "ai.api-key=test-openai-key", "ai.model=test-model"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GoipHttpApiConversationTests {
	private static final AtomicReference<String> SNAPSHOT = new AtomicReference<>("{\"success\":true,\"count\":0,\"messages\":[]}");
	private static final List<Map<String, String>> SENT = new CopyOnWriteArrayList<>();
	private static final List<Map<String, String>> RECEIVE_QUERIES = new CopyOnWriteArrayList<>();
	private static final HttpServer GATEWAY = gateway();

	@org.springframework.boot.test.context.TestConfiguration
	static class TestTime {
		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		Clock smsTestClock() {
			return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), Instant.parse("2026-10-14T01:00:00Z")));
		}
	}
	@DynamicPropertySource
	static void remoteGateway(DynamicPropertyRegistry properties) {
		String base = "http://127.0.0.1:" + GATEWAY.getAddress().getPort();
		properties.add("sms.http-api.send-url", () -> base + "/send.php");
		properties.add("sms.http-api.receive-url", () -> base + "/receive.php");
		properties.add("sms.http-api.receive-key", () -> "synthetic-receive-key");
	}
	@AfterAll static void stopGateway() { GATEWAY.stop(0); }

	@Autowired GoipHttpApiReceivePoller poller;
	@Autowired SmsWorkDispatcher dispatcher;
	@Autowired SmsMessageRepository messages;
	@Autowired DealershipRepository customers;
	@Autowired DemoConfiguration configuration;
	@Autowired SmsProperties properties;
	@Autowired JdbcClient db;
	@Autowired ObjectMapper json;
	@Autowired MockMvc mvc;
	@Autowired Clock clock;
	@Autowired ApplicationContext application;
	@MockitoBean AiClient ai;
	@MockitoBean WhatsAppApiClient whatsapp;
	@MockitoBean MessengerSendClient messenger;

	@Test
	void pollsFreshLinkedReplyWithOpeningContextAndSendsOneApprovedAnswerOnTheSameSim() throws Exception {
		assertThat(application.getBeansOfType(SmsProvider.class)).hasSize(1);
		assertThat(application.getBean(SmsProvider.class)).isInstanceOf(GoipHttpApiSmsProvider.class);
		assertThat(application.getBeansOfType(GoipSmsProvider.class)).isEmpty();
		customers.reset();
		var profile = json.readValue("{\"customer_id\":\"remote-client\",\"first_name\":\"Test\",\"service_offerings\":[\"pms\"]}", CustomerProfile.class);
		customers.seed(List.of(profile), configuration.catalog(), clock.millis());
		customers.link(new ChannelIdentity("SMS", "sim-2", "+639000000001", "remote-client", true, true, true, null), clock.millis());
		when(ai.generateResponse(any())).thenReturn(new AiResponse("{\"intent\":\"PRICE_OBJECTION\",\"date\":null,\"time\":null,\"serviceId\":\"pms\",\"language\":\"en\"}"));

		String opening = "Approved first message: would you like to book a PMS visit?";
		String request = json.writeValueAsString(Map.of("accountId", "sim-2", "recipient", "+639000000001", "content", opening));
		mvc.perform(post("/api/v1/sms/send").contentType("application/json").content(request)).andExpect(status().isUnauthorized());
		String queued = mvc.perform(post("/api/v1/sms/send").header("Authorization", "Bearer test-sms-api-token")
				.header("Idempotency-Key", "http-api-opening").contentType("application/json").content(request))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		String openingId = json.readTree(queued).path("messageId").asString();
		await(() -> messages.outbound(openingId).orElseThrow().status().equals("UNKNOWN"));
		verifyNoInteractions(ai);
		assertThat(SENT).hasSize(1);

		String objection = "Your product is too expensive; I can get my car done cheaper at another store.";
		String recent = Instant.ofEpochMilli(clock.millis()).toString();
		String old = Instant.ofEpochMilli(clock.millis()).minus(Duration.ofDays(1)).toString();
		SNAPSHOT.set(json.writeValueAsString(Map.of("success", true, "count", 3, "messages", List.of(
				remoteMessage("302", "09000000001", objection, recent),
				remoteMessage("301", "09000000001", "Historical reply must be ignored.", old),
				remoteMessage("303", "DEMOOTP", "Synthetic banking notification.", recent)))));
		poller.poll();
		await(() -> messages.inboundBacklog() == 0 && messages.outboundBacklog() == 0 && SENT.size() == 2);
		poller.poll();
		poller.poll();
		dispatcher.tick();

		assertThat(SENT).hasSize(2).allSatisfy(sent -> {
			assertThat(sent).containsEntry("line", "2").containsEntry("number", "09000000001");
			assertThat(sent).doesNotContainKey("gui_send");
		});
		String reply = SENT.getLast().get("message");
		assertThat(reply).contains("Genuine parts", "trained technicians", "quote", "Book a")
				.doesNotContain("Please contact the dealership for the full response");
		SmsValidation.content(reply, properties);
		assertThat(db.sql("SELECT COUNT(*) FROM sms_inbox").query(Integer.class).single()).isEqualTo(3);
		assertThat(db.sql("SELECT COUNT(*) FROM sms_inbox WHERE status = 'STORED'").query(Integer.class).single()).isEqualTo(2);
		assertThat(db.sql("SELECT COUNT(*) FROM dealer_events WHERE event_type = 'SMS_RESPONSE_TOO_LONG'").query(Integer.class).single()).isZero();
		assertThat(db.sql("SELECT COUNT(*) FROM sms_outbox WHERE status = 'UNKNOWN' AND error_code = 'HTTP_API_SUBMISSION_UNCONFIRMED'")
				.query(Integer.class).single()).isEqualTo(2);
		ArgumentCaptor<AiRequest> prompt = ArgumentCaptor.forClass(AiRequest.class);
		verify(ai, times(1)).generateResponse(prompt.capture());
		assertThat(prompt.getValue().message()).contains(opening, objection, "deliveryStatus", "UNKNOWN", "catalog", "goal")
				.doesNotContain("Historical reply must be ignored", "synthetic-private-password", "raw_packet", "Synthetic banking notification");
		assertThat(RECEIVE_QUERIES).allSatisfy(query -> assertThat(query)
				.containsExactlyInAnyOrderEntriesOf(Map.of("action", "message", "key", "synthetic-receive-key")));

		mvc.perform(get("/api/v1/sms/receive").param("accountId", "sim-2")).andExpect(status().isUnauthorized());
		var received = mvc.perform(get("/api/v1/sms/receive").header("Authorization", "Bearer test-sms-api-token")
				.param("accountId", "sim-2").param("waitSeconds", "0")).andExpect(request().asyncStarted()).andReturn();
		mvc.perform(asyncDispatch(received)).andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(3));
		mvc.perform(get("/api/v1/sms/receive-all").header("Authorization", "Bearer test-sms-api-token"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(3))
				.andExpect(jsonPath("$.messages[0].content").value("Historical reply must be ignored."))
				.andExpect(jsonPath("$.messages[0].status").value("STORED"));
	}

	private static Map<String, Object> remoteMessage(String receiveId, String sender, String content, String timestamp) {
		return Map.of("id", "synthetic-hash-" + receiveId, "recvid", receiveId, "goip_id", "synthetic-goip", "channel", 2,
				"sim_number", "09000000002", "from", sender, "message", content, "received_at", timestamp,
				"raw_packet", "synthetic-private-password");
	}
	private void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) { dispatcher.tick(); Thread.sleep(10); }
		assertThat(condition.getAsBoolean()).isTrue();
	}
	private static HttpServer gateway() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/receive.php", exchange -> {
				RECEIVE_QUERIES.add(parameters(exchange.getRequestURI().getRawQuery()));
				byte[] response = SNAPSHOT.get().getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, response.length);
				try (var output = exchange.getResponseBody()) { output.write(response); }
			});
			server.createContext("/send.php", exchange -> {
				if (!exchange.getRequestMethod().equals("POST") || exchange.getRequestBody().readAllBytes().length != 0) {
					exchange.sendResponseHeaders(400, -1);
					exchange.close();
					return;
				}
				SENT.add(parameters(exchange.getRequestURI().getRawQuery()));
				byte[] response = "<html>Management form with history</html>".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, response.length);
				try (var output = exchange.getResponseBody()) { output.write(response); }
			});
			server.start();
			return server;
		} catch (IOException exception) { throw new IllegalStateException("Cannot start synthetic loopback HTTP gateway", exception); }
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
