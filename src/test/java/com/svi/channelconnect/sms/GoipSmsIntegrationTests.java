package com.svi.channelconnect.sms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.dto.AiResponse;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.goip.GoipSmsProvider;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.service.SmsService;
import com.svi.channelconnect.sms.service.SmsWorkDispatcher;
import com.svi.channelconnect.sms.simulator.GoipGatewaySimulator.Behavior;
import com.svi.channelconnect.whatsapp.client.WhatsAppApiClient;
import com.svi.channelconnect.messenger.client.MessengerSendClient;

/** Real UDP sockets, real migrations/transactions, existing AI port mocked; no physical endpoints. */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:smsintegration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"sms.enabled=true", "sms.mode=MOCK", "sms.api-token=test-sms-api-token", "sms.allowed-recipients=+639000000001,+639000000002",
		"sms.packet-timeout=500ms", "sms.poll-interval=200ms", "sms.max-polls=2", "sms.session-timeout=4s", "sms.shutdown-timeout=2s",
		"sms.processing-attempts=2", "sms.processing-retry-delay=10ms", "sms.dispatch-delay-ms=600000", "sms.maintenance-delay-ms=600000",
		"sms.channels[0].account-id=sim-1", "sms.channels[0].device-id=test-device", "sms.channels[0].sim-channel=1",
		"sms.channels[0].source-address=127.0.0.1", "sms.channels[0].auth-id=test-id-1", "sms.channels[0].password=test-password", "sms.channels[0].send-interval=1ms",
		"sms.channels[1].account-id=sim-2", "sms.channels[1].device-id=test-device", "sms.channels[1].sim-channel=2",
		"sms.channels[1].source-address=127.0.0.1", "sms.channels[1].auth-id=test-id-2", "sms.channels[1].password=test-password", "sms.channels[1].send-interval=1ms",
		"ai.provider=openai", "ai.api-key=test-openai-key", "ai.model=test-model"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GoipSmsIntegrationTests {
	@org.springframework.boot.test.context.TestConfiguration
	static class TestTime {
		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		java.time.Clock smsTestClock() {
			// Anchor the dealership calendar while retaining advancing time for UDP availability and sending limits.
			return java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.between(java.time.Instant.now(),
					java.time.Instant.parse("2026-10-14T01:00:00Z")));
		}
	}
	@Autowired SmsService sms;
	@Autowired SmsMessageRepository messages;
	@Autowired GoipSmsProvider provider;
	@Autowired SmsWorkDispatcher dispatcher;
	@Autowired DealershipRepository customers;
	@Autowired JdbcClient db;
	@Autowired MockMvc mvc;
	@Autowired io.micrometer.core.instrument.MeterRegistry metrics;
	@Autowired com.svi.channelconnect.dealership.config.DemoConfiguration configuration;
	@Autowired com.svi.channelconnect.dealership.service.CampaignService campaigns;
	@Autowired java.time.Clock clock;
	@MockitoSpyBean com.svi.channelconnect.dealership.service.DealershipAssistant assistant;
	@MockitoBean AiClient ai;
	@MockitoBean WhatsAppApiClient whatsapp;
	@MockitoBean MessengerSendClient messenger;

	@BeforeEach void reset() throws Exception {
		await(() -> messages.outboundBacklog() == 0 && messages.inboundBacklog() == 0, false);
		for (String table : new String[] {"sms_inbox", "sms_outbox", "sms_conversations"}) db.sql("DELETE FROM " + table).update();
		customers.reset();
		for (String account : new String[] {"sim-1", "sim-2"}) { provider.simulator().behavior(account, Behavior.SUCCESS); provider.simulator().register(account); }
		await(() -> provider.available("sim-1") && provider.available("sim-2"), false);
		when(ai.generateResponse(any())).thenReturn(new AiResponse("{\"intent\":\"HOURS\",\"date\":null,\"time\":null,\"serviceId\":null,\"language\":\"en\"}"));
	}
	@Test void submitsAsynchronouslyAndPollsLostOkWithoutSendingTwice() throws Exception {
		provider.simulator().behavior("sim-1", Behavior.LOST_OK);
		int previous = provider.simulator().transmissions("sim-1");
		var queued = sms.submit("lost-ok", "sim-1", "09000000001", "Protocol test");
		assertThat(queued.status()).isEqualTo("QUEUED");
		assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(previous);
		awaitStatus(queued.messageId(), "GATEWAY_SENT");
		assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(previous + 1);
		assertThat(sms.submit("lost-ok", "sim-1", "+639000000001", "Protocol test").replay()).isTrue();
		assertThatThrownBy(() -> sms.submit("lost-ok", "sim-1", "+639000000001", "Changed")).isInstanceOf(SmsException.class);
		assertThat(provider.simulator().activeSessions("sim-1")).isZero();
	}
	@Test void handlesWaitAndRecipientErrorsThroughUdp() throws Exception {
		provider.simulator().behavior("sim-2", Behavior.WAIT_THEN_OK);
		var waiting = sms.submit("waiting", "sim-2", "+639000000002", "Protocol test");
		awaitStatus(waiting.messageId(), "GATEWAY_SENT");
		provider.simulator().behavior("sim-2", Behavior.ERROR);
		var rejected = sms.submit("rejected", "sim-2", "+639000000002", "Protocol test");
		awaitStatus(rejected.messageId(), "FAILED");
	}
	@Test void neverSubmitsARecipientAfterAuthenticationFailureOrLostInitialization() throws Exception {
		int previous = provider.simulator().transmissions("sim-1");
		for (Behavior behavior : new Behavior[] {Behavior.AUTH_FAILURE, Behavior.DROP_CHALLENGE, Behavior.DROP_AUTH_CONFIRM}) {
			provider.simulator().behavior("sim-1", behavior);
			var queued = sms.submit(behavior.name(), "sim-1", "+639000000001", "Protocol test");
			awaitStatus(queued.messageId(), "FAILED");
		}
		assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(previous);
	}
	@Test void preservesUnknownOutcomesAcrossRecoveryAndIdempotentApiReplay() throws Exception {
		provider.simulator().behavior("sim-1", Behavior.SILENT_RESULT);
		int previous = provider.simulator().transmissions("sim-1");
		var queued = sms.submit("unknown", "sim-1", "+639000000001", "Protocol test");
		awaitStatus(queued.messageId(), "UNKNOWN");
		messages.recover(); dispatcher.tick();
		assertThat(sms.submit("unknown", "sim-1", "+639000000001", "Protocol test").status()).isEqualTo("UNKNOWN");
		assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(previous + 1);
	}
	@Test void commitsBeforeAckAndRoutesOneAiReplyToTheOriginatingSim() throws Exception {
		int first = provider.simulator().transmissions("sim-1");
		int second = provider.simulator().transmissions("sim-2");
		provider.simulator().inbound("sim-2", 200001, "+639000000002", "What are your hours?; detail: yes\nsecond line").get(5, TimeUnit.SECONDS);
		assertThat(db.sql("SELECT status FROM sms_inbox WHERE receive_id = 200001").query(String.class).single()).isEqualTo("RECEIVED");
		verifyNoInteractions(ai);
		provider.simulator().inbound("sim-2", 200001, "+639000000002", "What are your hours?; detail: yes\nsecond line").get(5, TimeUnit.SECONDS);
		await(() -> count("sms_outbox", "status = 'GATEWAY_SENT'") == 1 && count("sms_inbox", "status = 'PROCESSED'") == 1, true);
		verify(ai, times(1)).generateResponse(any());
		verifyNoInteractions(whatsapp, messenger);
		assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(first);
		assertThat(provider.simulator().transmissions("sim-2")).isEqualTo(second + 1);
	}
	@Test void scopesDeduplicationAndConversationHistoryByAccountAndParticipant() throws Exception {
		provider.simulator().inbound("sim-1", 200002, "+639000000001", "First account question").get(5, TimeUnit.SECONDS);
		await(() -> count("sms_inbox", "status = 'PROCESSED'") == 1 && messages.outboundBacklog() == 0, true);
		provider.simulator().inbound("sim-2", 200002, "+639000000001", "Second account question").get(5, TimeUnit.SECONDS);
		await(() -> count("sms_inbox", "status = 'PROCESSED'") == 2 && messages.outboundBacklog() == 0, true);
		var requests = org.mockito.ArgumentCaptor.forClass(com.svi.channelconnect.ai.dto.AiRequest.class);
		verify(ai, times(2)).generateResponse(requests.capture());
		assertThat(requests.getAllValues().get(1).message()).doesNotContain("First account question");
		assertThat(count("sms_conversations", "1=1")).isEqualTo(2);
	}
	@Test void rejectsConflictingReceiveIdsAndCredentialsWithoutAcknowledgingSuccess() throws Exception {
		messages.accept("sim-1", 200003, "+639000000001", "Original");
		assertThatThrownBy(() -> messages.accept("sim-1", 200003, "+639000000001", "Different")).isInstanceOf(SmsException.class);
		provider.simulator().raw("sim-2", "RECEIVE:200004;id:test-id-2;pass:wrong;srcnum:+639000000002;msg:test");
		// A valid subsequent datagram on that socket establishes that the receiver has passed the invalid one.
		provider.simulator().inbound("sim-2", 200005, "+639000000002", "Valid").get(5, TimeUnit.SECONDS);
		assertThat(count("sms_inbox", "receive_id = 200004")).isZero();
		await(() -> messages.inboundBacklog() == 0 && messages.outboundBacklog() == 0, true);
	}
	@Test void recoversOnlyClaimsThatCannotHaveSubmittedASms() throws Exception {
		var beforeSend = sms.submit("safe-recovery", "sim-1", "+639000000001", "Protocol test");
		assertThat(messages.claimOutbound(beforeSend.messageId())).isTrue();
		var afterSend = sms.submit("unsafe-recovery", "sim-2", "+639000000002", "Protocol test");
		assertThat(messages.claimOutbound(afterSend.messageId())).isTrue(); messages.transmitting(afterSend.messageId());
		messages.recover();
		assertThat(messages.outbound(beforeSend.messageId()).orElseThrow().status()).isEqualTo("QUEUED");
		assertThat(messages.outbound(afterSend.messageId()).orElseThrow().status()).isEqualTo("UNKNOWN");
		awaitStatus(beforeSend.messageId(), "GATEWAY_SENT");
	}
	@Test void usesGuardedHttpContractsAndKeepsHandsetDeliveryUnconfirmed() throws Exception {
		String request = "{\"accountId\":\"sim-1\",\"recipient\":\"09000000001\",\"content\":\"Protocol test\"}";
		mvc.perform(post("/api/v1/sms/messages").contentType("application/json").content(request)).andExpect(status().isUnauthorized());
		mvc.perform(post(java.net.URI.create("/api/v1/%73ms/messages")).contentType("application/json").content(request)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/sms/messages").header("Authorization", "Bearer test-sms-api-token").contentType("application/json").content(request)).andExpect(status().isBadRequest());
		var response = mvc.perform(post("/api/v1/sms/messages").header("Authorization", "Bearer test-sms-api-token").header("Idempotency-Key", "http-1")
				.contentType("application/json").content(request)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andReturn();
		String id = new tools.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).get("messageId").asString();
		awaitStatus(id, "GATEWAY_SENT");
		mvc.perform(get("/api/v1/sms/messages/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/sms/messages/" + id).header("Authorization", "Bearer test-sms-api-token"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.handsetDelivery").value("UNCONFIRMED")).andExpect(jsonPath("$.content").doesNotExist());
		mvc.perform(post("/api/v1/sms/messages").header("Authorization", "Bearer test-sms-api-token").header("Idempotency-Key", "denied")
				.contentType("application/json").content(request.replace("09000000001", "09000000003"))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/sms/messages").header("Authorization", "Bearer test-sms-api-token").header("Idempotency-Key", "http-1")
				.contentType("application/json").content(request.replace("Protocol test", "Changed"))).andExpect(status().isConflict());
		mvc.perform(post("/api/v1/sms/messages").header("Authorization", "Bearer test-sms-api-token").contentType("application/json").content(" ".repeat(16385)))
				.andExpect(status().isPayloadTooLarge());
		mvc.perform(get("/api/admin/sms/channels")).andExpect(status().isUnauthorized());
	}
	@Test void supportsReadOnlyStatusQueries() throws Exception {
		assertThat(provider.query("sim-1", false)).isEqualTo("IDLE");
		assertThat(provider.statuses()).allMatch(s -> s.available());
	}
	@Test void deduplicatesAnActualGatewayRetransmissionAfterALostAck() throws Exception {
		provider.simulator().loseNextInboundAck("sim-1");
		provider.simulator().inbound("sim-1", 200010, "+639000000001", "Hours?").get(5, TimeUnit.SECONDS);
		assertThat(count("sms_inbox", "receive_id = 200010")).isEqualTo(1);
		assertThat(metrics.counter("sms.inbound", "result", "duplicate").count()).isGreaterThan(0);
		await(() -> messages.inboundBacklog() == 0 && messages.outboundBacklog() == 0, true);
		verify(ai, times(1)).generateResponse(any());
	}
	@Test void ignoresWrongSessionAndRecipientResponsesUntilTheMatchingResultArrives() throws Exception {
		provider.simulator().behavior("sim-1", Behavior.SILENT_RESULT);
		var queued = sms.submit("correlation", "sim-1", "+639000000001", "Protocol test");
		await(() -> messages.outbound(queued.messageId()).orElseThrow().status().equals("TRANSMITTING"), true);
		long session = messages.outbound(queued.messageId()).orElseThrow().sessionId();
		double received = metrics.counter("sms.goip.packets", "result", "received").count();
		provider.simulator().raw("sim-1", "OK " + (session + 100000) + " 1\n");
		provider.simulator().raw("sim-1", "OK " + session + " 2\n");
		await(() -> metrics.counter("sms.goip.packets", "result", "received").count() >= received + 2, false);
		assertThat(messages.outbound(queued.messageId()).orElseThrow().status()).isEqualTo("TRANSMITTING");
		provider.simulator().raw("sim-1", "OK " + session + " 1\n"); awaitStatus(queued.messageId(), "GATEWAY_SENT");
	}
	@Test void rollsBackBusinessSideEffectsBeforeRetryAndReusesTheCommittedReplyOnDuplicates() throws Exception {
		seedVerifiedSmsCustomer();
		when(ai.generateResponse(any())).thenReturn(new AiResponse("{\"intent\":\"BOOK\",\"date\":\"2026-10-16\",\"time\":\"09:00\",\"serviceId\":\"registration\",\"language\":\"en\"}"));
		doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("Injected completion failure"); })
				.when(assistant).apply(eq("sms-verified"), eq(true), any(), any());
		provider.simulator().inbound("sim-1", 200020, "+639000000001", "Book registration Friday at 9am").get(5, TimeUnit.SECONDS);
		await(() -> count("sms_inbox", "status = 'DEAD_LETTER'") == 1, true);
		assertThat(customers.appointment("sms-verified")).isEmpty(); assertThat(count("sms_outbox", "1=1")).isZero();
		doCallRealMethod().when(assistant).apply(eq("sms-verified"), eq(true), any(), any());
		String id = messages.deadLetters().getFirst().messageId(); messages.retryDeadLetter(id);
		await(() -> messages.inboundBacklog() == 0 && messages.outboundBacklog() == 0, true);
		assertThat(customers.appointment("sms-verified")).isPresent();
		provider.simulator().inbound("sim-1", 200020, "+639000000001", "Book registration Friday at 9am").get(5, TimeUnit.SECONDS);
		assertThat(count("dealer_events", "event_type = 'APPOINTMENT_CREATED'")).isEqualTo(1);
		assertThat(count("sms_outbox", "1=1")).isEqualTo(1);
	}
	@Test void tracksCampaignQueueSeparatelyAndCancelsItWhenTheCustomerResponds() throws Exception {
		seedVerifiedSmsCustomer();
		var mapper = new tools.jackson.databind.ObjectMapper();
		var tree = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(configuration.campaign());
		((tools.jackson.databind.node.ObjectNode) tree.get("scripts")).put("opening_sms", "Short approved reminder.");
		var config = mapper.treeToValue(tree, com.svi.channelconnect.dealership.model.CampaignConfiguration.class).withBase("SMS");
		var execution = campaigns.start("sms-verified", "SMS", config);
		assertThat(execution.status()).isEqualTo("SMS_QUEUED");
		assertThat(customers.acceptedCount(execution.id(), "SMS")).isZero();
		int before = provider.simulator().transmissions("sim-1");
		provider.simulator().inbound("sim-1", 200021, "+639000000001", "Hours?").get(5, TimeUnit.SECONDS);
		// Complete the inbound decision first: then the pending campaign is no longer authorized.
		String inbox = db.sql("SELECT id FROM sms_inbox WHERE receive_id = 200021").query(String.class).single();
		assertThat(messages.claimInbound(inbox)).isTrue();
		processor.process(inbox);
		await(() -> messages.outboundBacklog() == 0, true);
		assertThat(count("sms_outbox", "error_code = 'AUTHORIZATION_REVOKED'")).isEqualTo(1);
		assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(before + 1);
		assertThat(customers.execution("sms-verified", config.id()).orElseThrow().status()).isEqualTo("CUSTOMER_RESPONDED");
	}
	@Test void marksSuccessfulCampaignAttemptsOnlyAfterTheGatewayResult() throws Exception {
		seedVerifiedSmsCustomer();
		var mapper = new tools.jackson.databind.ObjectMapper();
		var tree = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(configuration.campaign());
		((tools.jackson.databind.node.ObjectNode) tree.get("scripts")).put("opening_sms", "Short approved reminder.");
		var config = mapper.treeToValue(tree, com.svi.channelconnect.dealership.model.CampaignConfiguration.class).withBase("SMS");
		var execution = campaigns.start("sms-verified", "SMS", config);
		assertThat(customers.acceptedCount(execution.id(), "SMS")).isZero();
		await(() -> messages.outboundBacklog() == 0, true);
		assertThat(customers.acceptedCount(execution.id(), "SMS")).isEqualTo(1);
		assertThat(customers.execution("sms-verified", config.id()).orElseThrow().status()).isEqualTo("FOLLOW_UP_SCHEDULED");
	}
	@Test void rechecksExpiryBeforeAnyRecipientTransmission() throws Exception {
		int before = provider.simulator().transmissions("sim-1");
		var queued = sms.submit("expired", "sim-1", "+639000000001", "Protocol test");
		db.sql("UPDATE sms_outbox SET created_at = 1 WHERE id = ?").param(queued.messageId()).update();
		awaitStatus(queued.messageId(), "FAILED");
		assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(before);
		assertThat(messages.errorCode(queued.messageId())).isEqualTo("EXPIRED");
	}
	@Autowired com.svi.channelconnect.sms.service.SmsConversationProcessor processor;
	private void seedVerifiedSmsCustomer() {
		var mapper = new tools.jackson.databind.ObjectMapper();
		var profile = mapper.readValue("{\"customer_id\":\"sms-verified\",\"first_name\":\"Test\",\"service_offerings\":[\"registration\"]}", com.svi.channelconnect.dealership.model.CustomerProfile.class);
		customers.seed(java.util.List.of(profile), configuration.catalog(), clock.millis());
		customers.link(new com.svi.channelconnect.dealership.model.ChannelIdentity("SMS", "sim-1", "+639000000001", "sms-verified", true, true, true, null), clock.millis());
	}
	private int count(String table, String condition) { return db.sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).query(Integer.class).single(); }
	private void awaitStatus(String id, String expected) throws Exception { await(() -> messages.outbound(id).orElseThrow().status().equals(expected), true); }
	private void await(BooleanSupplier condition, boolean work) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) { if (work) dispatcher.tick(); Thread.sleep(10); }
		assertThat(condition.getAsBoolean()).isTrue();
	}
}
