package com.svi.channelconnect.sms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.AiResponse;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.sms.model.SmsInbound;
import com.svi.channelconnect.sms.model.SmsSubmission;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.support.SmsTestProperties;

class SmsTmbgDemoServiceTests {
	private static final String PHONE = "+639170000011";
	private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
	@TempDir Path temporary;
	private final ObjectMapper json = new ObjectMapper();
	private final AiClient client = mock(AiClient.class);
	private final SmsService sms = mock(SmsService.class);
	private final SmsMessageRepository messages = mock(SmsMessageRepository.class);
	private final List<String> replies = new ArrayList<>();
	private SmsTmbgDemoService demo;
	private Path bookings;

	@BeforeEach
	void setup() {
		bookings = temporary.resolve("bookings.txt");
		var ai = new AiProperties(AiProperties.Provider.OPENAI, "synthetic-key", "synthetic-model",
				URI.create("https://example.invalid/v1"), Duration.ofSeconds(1), 0, 4096, "", 2048);
		when(sms.validate(anyString(), anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
		when(sms.submit(anyString(), anyString(), anyString(), anyString())).thenReturn(new SmsSubmission("greeting", "QUEUED", false));
		when(messages.enqueue(anyString(), anyString(), anyString(), anyString(), isNull())).thenAnswer(invocation -> {
			replies.add(invocation.getArgument(3));
			return new SmsSubmission("reply-" + replies.size(), "QUEUED", false);
		});
		demo = new SmsTmbgDemoService(client, ai, json, new DefaultResourceLoader(), sms, messages,
				Clock.fixed(NOW, ZoneOffset.UTC), bookings.toString());
	}

	@Test
	void sendsTheFilledGreetingWithoutAnAiCallAndScopesTheDemoToItsRecipient() {
		var submitted = demo.start("sim-1", null);

		assertThat(submitted.messageId()).isEqualTo("greeting");
		var greeting = ArgumentCaptor.forClass(String.class);
		verify(sms).submit(anyString(), eq("sim-1"), eq(PHONE), greeting.capture());
		assertThat(greeting.getValue()).contains("Amelia", "AI assistant", "Toyota Manila Bay", "STOP").doesNotContain("{{");
		assertThat(demo.accepts("sim-1", PHONE)).isTrue();
		assertThat(demo.accepts("sim-2", PHONE)).isFalse();
		assertThat(demo.accepts("sim-1", "+639170000012")).isFalse();
		assertThat(demo.handles(inbound("stored", "hello", "STORED"))).isFalse();
		verifyNoInteractions(client);
		assertThat(bookings).doesNotExist();
	}

	@Test
	void sendsSixOrderedContextBlocksWithFilledFactsAndTheLatestCustomerSms() {
		demo.start("sim-1", null);
		when(client.generateResponse(any())).thenReturn(answer("Has your registration been renewed this year po?",
				"REG_STATUS_ASK", "REG_STATUS_ASKED", null, null));

		demo.process(inbound("first", "Hello po"));

		var request = ArgumentCaptor.forClass(AiRequest.class);
		verify(client).generateResponse(request.capture());
		assertThat(request.getValue().jsonOutput()).isTrue();
		assertThat(request.getValue().instructions()).contains("Toyota Manila Bay", "Never confirm a booking yourself");
		String context = request.getValue().message();
		int previous = -1;
		for (String heading : List.of("RULES", "CUSTOMER RECORD", "DERIVED FIELDS", "CONVERSATION STATE", "TEMPLATES", "CUSTOMER SMS")) {
			int position = context.lastIndexOf(heading);
			assertThat(position).as(heading + " is ordered").isGreaterThan(previous);
			previous = position;
		}
		assertThat(context).contains("Amelia", "TST 3080", "2026-10-15", "Oct 16", "OPENER_SENT", "Hello po");
		assertThat(context.substring(context.lastIndexOf("TEMPLATES"))).doesNotContain("{{first_name}}", "{{slots_text}}");
		assertThat(replies).containsExactly("Has your registration been renewed this year po?");
		verify(messages).completeInbound("first");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"I haven't renewed yet|not_yet",
			"I'm not sure if it was renewed|unknown"
	})
	void doesNotTreatNegativeOrUncertainRenewalStatementsAsAlreadyRenewed(String text, String expectedStatus) {
		demo.start("sim-1", null);
		when(client.generateResponse(any())).thenReturn(answer("Has your registration been renewed this year po?",
				"REG_STATUS_ASK", "REG_STATUS_ASKED", null, null));

		demo.process(inbound("status", text));

		var prompt = ArgumentCaptor.forClass(AiRequest.class);
		verify(client).generateResponse(prompt.capture());
		assertThat(prompt.getValue().message()).contains("\"registration_status\":\"" + expectedStatus + "\"");
	}

	@Test
	void writesOneJsonLineThenUsesTheServiceConfirmationForAChosenOfferedSlot() throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		assertThat(replies.getLast()).doesNotContain("AGENT");
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));

		demo.process(inbound("choice", "A"));

		List<String> lines = Files.readAllLines(bookings);
		assertThat(lines).hasSize(1);
		JsonNode booking = json.readTree(lines.getFirst());
		assertThat(booking.path("booking_ref").asString()).matches("TMB-\\d{5}");
		assertThat(booking.path("receipt_id").asString()).isEqualTo("choice");
		assertThat(booking.path("customer_id").asString()).isEqualTo("CUS-DEMO-0001");
		assertThat(booking.path("plate").asString()).isEqualTo("TST 3080");
		assertThat(booking.path("phone").asString()).isEqualTo(PHONE);
		assertThat(booking.path("appointment").asString()).isEqualTo("2026-10-16T09:00");
		assertThat(booking.path("services").toString()).contains("registration", "ctpl", "pms");
		assertThat(replies.getLast()).contains("Confirmed po!", booking.path("booking_ref").asString(), "Oct 16", "OR/CR").doesNotContain("AGENT");
		verify(messages).completeInbound("choice");

		demo.process(inbound("choice", "A"));
		assertThat(Files.readAllLines(bookings)).hasSize(1);
	}

	@Test
	void acceptsTheSuppliedLongerMenuAndConfirmationThroughRealTwoSegmentValidation() throws Exception {
		var properties = SmsTestProperties.properties(Map.of("sms.max-segments", "2"));
		var realSms = new SmsService(properties, messages, mock(DealershipRepository.class));
		var ai = new AiProperties(AiProperties.Provider.OPENAI, "synthetic-key", "synthetic-model",
				URI.create("https://example.invalid/v1"), Duration.ofSeconds(1), 0, 4096, "", 2048);
		demo = new SmsTmbgDemoService(client, ai, json, new DefaultResourceLoader(), realSms, messages,
				Clock.fixed(NOW, ZoneOffset.UTC), bookings.toString());
		demo.start("sim-1", null);
		reachSlots();
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));

		demo.process(inbound("two-segment-choice", "A"));

		assertThat(Files.readAllLines(bookings)).hasSize(1);
		assertThat(replies.getLast()).contains("Confirmed po!").hasSizeGreaterThan(160).hasSizeLessThanOrEqualTo(306);
		replies.forEach(text -> SmsValidation.content(text, properties));
	}

	@Test
	void rejectsABookingRequestAfterTheCustomerReportsExpiredRegistration() {
		demo.start("sim-1", null);
		reachSlots();
		var response = json.readTree(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A").text()).deepCopy();
		((tools.jackson.databind.node.ObjectNode) response).putArray("flags").add("expired_registration");
		when(client.generateResponse(any())).thenReturn(new AiResponse(json.writeValueAsString(response)));

		demo.process(inbound("expired", "Actually the registration expired, but book A"));

		assertThat(bookings).doesNotExist();
		assertThat(replies.getLast().toLowerCase()).doesNotContain("confirmed", "booked", "ref tmb-");
	}

	@Test
	void keepsTheDemoConversationOpenWithoutPromisingAnAdvisorOrCallbackAction() {
		demo.start("sim-1", null);
		when(client.generateResponse(any())).thenReturn(
				answer("I'm passing you to an advisor now po. They will message you here.", "HANDOFF", "HANDOFF", "handoff", null),
				answer("Has your registration been renewed this year po?", "REG_STATUS_ASK", "REG_STATUS_ASKED", null, null));

		demo.process(inbound("advisor", "AGENT"));
		demo.process(inbound("continue", "Let's continue"));

		assertThat(replies).hasSize(2);
		assertThat(replies.getFirst()).doesNotContain("passing you", "They will message", "I'll message");
		assertThat(replies.getLast()).isEqualTo("Has your registration been renewed this year po?");
		assertThat(bookings).doesNotExist();
	}

	@ParameterizedTest
	@MethodSource("invalidReplies")
	void replacesMalformedOversizeNonGsmAndFalseConfirmationReplies(String modelReply) {
		demo.start("sim-1", null);
		when(client.generateResponse(any())).thenReturn(new AiResponse(modelReply));

		demo.process(inbound("invalid", "Hello"));

		assertThat(replies).hasSize(1);
		assertThat(replies.getFirst()).contains("registration").hasSizeLessThanOrEqualTo(306);
		assertThat(replies.getFirst()).doesNotContain("\uD83D\uDE00", "Confirmed", "TMB-99999");
		assertThat(bookings).doesNotExist();
		verify(messages).completeInbound("invalid");
	}

	private static Stream<String> invalidReplies() {
		return Stream.of("not-json",
				answer("x".repeat(307), "REG_STATUS_ASK", "REG_STATUS_ASKED", null, null).text(),
				answer("Hello po \uD83D\uDE00", "REG_STATUS_ASK", "REG_STATUS_ASKED", null, null).text(),
				answer("Confirmed po! Ref TMB-99999", "REG_STATUS_ASK", "REG_STATUS_ASKED", null, null).text());
	}

	private void reachSlots() {
		when(client.generateResponse(any())).thenReturn(
				answer("Has your registration been renewed this year po?", "REG_STATUS_ASK", "REG_STATUS_ASKED", null, null),
				answer("We can do registration, CTPL and PMS in one visit po. Would you like to book?", "OFFER_WINDOW_OPEN", "OFFER_MADE", null, null),
				answer("Available po: A Oct 16 9am; B Oct 19 1pm; C Oct 20 10am. Reply A, B or C.", "SLOTS_OFFER", "SLOTS_OFFERED", null, null));
		demo.process(inbound("hello", "Hello"));
		demo.process(inbound("registration", "Not yet"));
		demo.process(inbound("offer", "Yes please"));
	}

	private static AiResponse answer(String reply, String template, String stage, String outcome, String code) {
		var json = new ObjectMapper();
		var root = json.createObjectNode();
		root.put("reply_text", reply);
		root.put("template_id", template);
		root.put("lang", "en");
		root.putNull("short_code_read");
		root.put("state_after", stage);
		root.put("outcome", outcome);
		root.put("slot_code", code);
		var data = root.putObject("outcome_data");
		data.putArray("services").add("registration").add("ctpl").add("pms");
		data.putNull("odometer_reported");
		data.putNull("decline_reason");
		data.putNull("callback_time");
		root.put("needs_human", false);
		root.putArray("flags");
		return new AiResponse(json.writeValueAsString(root));
	}

	private SmsInbound inbound(String id, String content) { return inbound(id, content, "PROCESSING"); }
	private SmsInbound inbound(String id, String content, String status) {
		return new SmsInbound(id, "sim-1", 1, PHONE, content, NOW.plusSeconds(1).toEpochMilli(), status, 1);
	}
}
