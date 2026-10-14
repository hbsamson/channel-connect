package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.svi.channelconnect.dealership.model.ChannelIdentity;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.sms.config.GoipHttpApiProperties;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.service.SmsValidation;
import com.svi.channelconnect.support.SmsTestProperties;

class GoipHttpApiReceivePollerTests {
	private static final Instant NOW = Instant.parse("2026-10-14T01:00:00Z");
	private static final String CUSTOMER_PHONE = "+639000000001";
	private final ObjectMapper json = new ObjectMapper();
	private final GoipHttpApiClient client = mock(GoipHttpApiClient.class);
	private final SmsMessageRepository messages = mock(SmsMessageRepository.class);
	private final DealershipRepository customers = mock(DealershipRepository.class);
	private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
	private final GoipHttpApiProperties api = api("synthetic-receive-key", null);
	private GoipHttpApiReceivePoller poller;

	@BeforeEach
	void setup() {
		when(messages.receiveBaseline(anyString(), anyLong())).thenAnswer(invocation -> invocation.getArgument(1));
		when(messages.acceptRemote(anyString(), anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);
		when(customers.identity(eq("SMS"), anyString(), eq(CUSTOMER_PHONE))).thenAnswer(invocation -> Optional.of(
				new ChannelIdentity("SMS", invocation.getArgument(1), CUSTOMER_PHONE, "synthetic-customer", true, true, true, null)));
		poller = poller(api);
		poller.start();
	}

	@AfterEach
	void close() { poller.close(); metrics.close(); }

	@Test
	void importsOriginalTimestampsAndConfiguredRoutesInChronologicalOrder() throws Exception {
		var later = entry(7, 2, CUSTOMER_PHONE, "Later reply", NOW.plusSeconds(2));
		later.put("raw_packet", "synthetic-sensitive-metadata");
		later.put("gateway_ip", "192.0.2.1");
		later.put("_fingerprint", "untrusted-remote-hash");
		var earlier = entry(400, 1, CUSTOMER_PHONE, "Earlier reply", NOW.plusSeconds(1));
		when(client.receive()).thenReturn(response(later, earlier));
		poller.poll();
		var order = inOrder(messages);
		order.verify(messages).acceptRemote("sim-1", 400, CUSTOMER_PHONE, "Earlier reply", NOW.plusSeconds(1).toEpochMilli());
		order.verify(messages).acceptRemote("sim-2", 7, CUSTOMER_PHONE, "Later reply", NOW.plusSeconds(2).toEpochMilli());
		assertThat(metrics.counter("sms.http_api.receive", "result", "accepted").count()).isEqualTo(2);
	}

	@Test
	void skipsHistoricBankUnknownLineAndUnverifiedUnconsentedOrUnknownClients() throws Exception {
		var wrongDevice = entry(3, 1, CUSTOMER_PHONE, "Unconfigured", NOW.plusSeconds(1));
		wrongDevice.put("goip_id", "other-device");
		when(customers.identity("SMS", "sim-1", "+639000000002")).thenReturn(Optional.empty());
		when(customers.identity("SMS", "sim-1", "+639000000003")).thenReturn(Optional.of(
				new ChannelIdentity("SMS", "sim-1", "+639000000003", "synthetic-unverified", false, true, true, null)));
		when(customers.identity("SMS", "sim-1", "+639000000004")).thenReturn(Optional.of(
				new ChannelIdentity("SMS", "sim-1", "+639000000004", "synthetic-no-consent", true, false, true, null)));
		when(customers.identity("SMS", "sim-1", "+639000000005")).thenReturn(Optional.of(
				new ChannelIdentity("SMS", "sim-1", "+639000000005", "synthetic-not-permitted", true, true, false, null)));
		when(client.receive()).thenReturn(response(
				entry(1, 1, CUSTOMER_PHONE, "Old reply", NOW.minusSeconds(1)),
				entry(2, 1, "SYNTHETICBANK", "Synthetic OTP", NOW.plusSeconds(1)), wrongDevice,
				entry(4, 8, CUSTOMER_PHONE, "Unconfigured line", NOW.plusSeconds(1)),
				entry(5, 1, "+639000000002", "Unknown", NOW.plusSeconds(1)),
				entry(6, 1, "+639000000003", "Unverified", NOW.plusSeconds(1)),
				entry(7, 1, "+639000000004", "Unconsented", NOW.plusSeconds(1)),
				entry(8, 1, "+639000000005", "Not permitted", NOW.plusSeconds(1))));
		poller.poll();
		verify(messages, never()).acceptRemote(anyString(), anyLong(), anyString(), anyString(), anyLong());
		verify(customers, never()).identity("SMS", "sim-1", "SYNTHETICBANK");
	}

	@Test
	void ignoresMalformedEntriesWithoutBlockingAValidCustomerReply() throws Exception {
		var malformedDate = entry(1, 1, CUSTOMER_PHONE, "Invalid date", NOW.plusSeconds(1));
		malformedDate.put("received_at", "invalid-date");
		var malformedLine = entry(2, 1, CUSTOMER_PHONE, "Invalid line", NOW.plusSeconds(1));
		malformedLine.put("channel", "1");
		var missingMessage = entry(3, 1, CUSTOMER_PHONE, "Missing text", NOW.plusSeconds(1));
		missingMessage.remove("message");
		when(client.receive()).thenReturn(response(malformedDate, malformedLine, missingMessage,
				entry(-1, 1, CUSTOMER_PHONE, "Invalid ID", NOW.plusSeconds(1)),
				entry(5, 1, CUSTOMER_PHONE, "x".repeat(3001), NOW.plusSeconds(1)),
				entry(6, 1, CUSTOMER_PHONE, "Valid reply", NOW.plusSeconds(1))));
		poller.poll();
		verify(messages).acceptRemote("sim-1", 6, CUSTOMER_PHONE, "Valid reply", NOW.plusSeconds(1).toEpochMilli());
		verify(messages, times(1)).acceptRemote(anyString(), anyLong(), anyString(), anyString(), anyLong());
	}

	@Test
	void defaultBaselineKeepsReceiptsFromTheGatewayStartupSecond() throws Exception {
		poller.close();
		poller = new GoipHttpApiReceivePoller(client, api, SmsTestProperties.properties(Map.of(
				"sms.mode", "HTTP_API", "sms.live-authorized", "true")), messages, customers, json, metrics,
				Clock.fixed(NOW.plusMillis(800), ZoneOffset.UTC));
		poller.start();
		when(client.receive()).thenReturn(response(entry(1, 1, CUSTOMER_PHONE, "Same-second reply", NOW)));
		poller.poll();
		verify(messages).acceptRemote("sim-1", 1, CUSTOMER_PHONE, "Same-second reply", NOW.toEpochMilli());
	}

	@Test
	void preservesTheDurableCutoffAcrossRestartAndReceiveKeyRotation() throws Exception {
		long durable = NOW.minusSeconds(30).toEpochMilli();
		when(messages.receiveBaseline(anyString(), anyLong())).thenReturn(durable);
		poller.close();
		poller = poller(api("rotated-synthetic-key", NOW.plusSeconds(60)));
		poller.start();
		when(client.receive()).thenReturn(response(entry(1, 1, CUSTOMER_PHONE, "Retained fresh reply", NOW.minusSeconds(10))));
		poller.poll();
		verify(messages).receiveBaseline(SmsValidation.fingerprint(api.receiveUrl().toString()), NOW.plusSeconds(60).toEpochMilli());
		verify(messages).acceptRemote("sim-1", 1, CUSTOMER_PHONE, "Retained fresh reply", NOW.minusSeconds(10).toEpochMilli());
	}

	@Test
	void replaysSnapshotsThroughDurableDeduplicationAndRetriesCapacityFailures() throws Exception {
		when(client.receive()).thenReturn(response(entry(1, 1, CUSTOMER_PHONE, "First", NOW.plusSeconds(1)),
				entry(2, 1, CUSTOMER_PHONE, "Second", NOW.plusSeconds(2))));
		when(messages.acceptRemote(eq("sim-1"), eq(1L), anyString(), anyString(), anyLong())).thenReturn(true, false);
		when(messages.acceptRemote(eq("sim-1"), eq(2L), anyString(), anyString(), anyLong()))
				.thenThrow(new SmsException(SmsException.Reason.CAPACITY)).thenReturn(true);
		poller.poll();
		poller.poll();
		verify(messages, times(2)).acceptRemote("sim-1", 1, CUSTOMER_PHONE, "First", NOW.plusSeconds(1).toEpochMilli());
		verify(messages, times(2)).acceptRemote("sim-1", 2, CUSTOMER_PHONE, "Second", NOW.plusSeconds(2).toEpochMilli());
		assertThat(metrics.counter("sms.http_api.receive", "result", "duplicate").count()).isEqualTo(1);
	}

	@Test
	void defersFailedHttpMalformedOrUnsuccessfulSnapshotsWithoutAcceptingMessages() throws Exception {
		for (String body : new String[] {"not-json", "{\"success\":false,\"count\":0,\"messages\":[]}",
				"{\"success\":true,\"count\":1,\"messages\":[]}"}) {
			when(client.receive()).thenReturn(new GoipHttpApiClient.HttpApiResponse(200, body));
			poller.poll();
		}
		when(client.receive()).thenReturn(new GoipHttpApiClient.HttpApiResponse(503, "synthetic-sensitive-error"));
		poller.poll();
		when(client.receive()).thenThrow(new IOException("synthetic-sensitive-transport-details"));
		poller.poll();
		verify(messages, never()).acceptRemote(anyString(), anyLong(), anyString(), anyString(), anyLong());
	}

	@Test
	void scheduledPollingUsesOneBoundedWorkerWithoutOverlappingRemoteReads() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		when(client.receive()).thenAnswer(invocation -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return response();
		});
		poller.tick();
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		for (int i = 0; i < 100; i++) poller.tick();
		verify(client, times(1)).receive();
		release.countDown();
	}

	private GoipHttpApiReceivePoller poller(GoipHttpApiProperties settings) {
		return new GoipHttpApiReceivePoller(client, settings, SmsTestProperties.properties(Map.of(
				"sms.mode", "HTTP_API", "sms.live-authorized", "true")), messages, customers, json, metrics,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}
	private static GoipHttpApiProperties api(String key, Instant startAt) {
		return new GoipHttpApiProperties(URI.create("https://example.invalid/send"), URI.create("https://example.invalid/receive"),
				key, 1048576, startAt);
	}
	private ObjectNode entry(long id, int channel, String sender, String content, Instant receivedAt) {
		var entry = json.createObjectNode();
		entry.put("id", "synthetic-entry-" + id);
		entry.put("recvid", Long.toString(id));
		entry.put("goip_id", "test-device");
		entry.put("channel", channel);
		entry.put("sim_number", "+639000000010");
		entry.put("from", sender);
		entry.put("message", content);
		entry.put("received_at", receivedAt.toString());
		return entry;
	}
	private GoipHttpApiClient.HttpApiResponse response(JsonNode... entries) {
		var root = json.createObjectNode();
		root.put("success", true);
		root.put("count", entries.length);
		var snapshot = root.putArray("messages");
		for (JsonNode entry : entries) snapshot.add(entry);
		return new GoipHttpApiClient.HttpApiResponse(200, json.writeValueAsString(root));
	}
}
