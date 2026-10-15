package com.svi.channelconnect.sms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.AiResponse;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;
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
		var assistant = new TmbgBookingAssistant(client, ai, json, new DefaultResourceLoader(),
				Clock.fixed(NOW, ZoneOffset.UTC), bookings.toString());
		demo = new SmsTmbgDemoService(assistant, sms, messages);
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
	void stopsCallingTheModelAfterSavingTheCustomerChosenBooking() throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));
		demo.process(inbound("choice", "A"));

		demo.process(inbound("after-booking", "Hello again"));

		verify(client, times(4)).generateResponse(any());
		verify(messages).completeInbound("after-booking");
		assertThat(replies).hasSize(4);
		assertThat(Files.readAllLines(bookings)).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"A?", "Is Oct 16 available?", "Is Friday available", "I can't do Friday",
			"Not 9am", "Maybe Friday", "Friday or Monday", "Oct 16 at 1pm", "9:30am", "Yes",
			"October 19 at 9am", "October 19 2027", "October 30 please", "Friday October 19"})
	void keepsReplyingWithoutBookingWhenTheCustomerHasNotChosenASlot(String text) throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		// Even an incorrect model decision must not substitute for a customer's selection.
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));

		demo.process(inbound("not-a-choice", text));

		assertThat(bookings).doesNotExist();
		assertThat(replies.getLast()).contains("date/time", "A/B/C").doesNotContain("Confirmed");
		demo.process(inbound("actual-choice", "A"));
		assertThat(Files.readAllLines(bookings)).hasSize(1);
		assertThat(replies.getLast()).contains("Confirmed po!");
	}

	@Test
	void doesNotResetAConfirmedSessionWhenSendingANewGreetingFails() throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));
		demo.process(inbound("choice", "A"));
		when(sms.submit(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalArgumentException("Submission rejected"));

		assertThatThrownBy(() -> demo.start("sim-1", null)).isInstanceOf(IllegalArgumentException.class);
		demo.process(inbound("after-failed-start", "Hello"));

		verify(client, times(4)).generateResponse(any());
		assertThat(replies).hasSize(4);
		assertThat(Files.readAllLines(bookings)).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"A", "slot A po", "Book A please", "Oct 16", "Friday", "Oct 16 at 9am", "Fri Oct 16 9:00AM", "2026-10-16", "09:00"})
	void confirmsOnlyAnUnambiguousOfferedSlotSelection(String text) throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));

		demo.process(inbound("choice", text));

		assertThat(Files.readAllLines(bookings)).hasSize(1);
		assertThat(replies.getLast()).contains("Confirmed po!");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"October 19 please|B|2026-10-19T13:00",
			"October 19, 2026|B|2026-10-19T13:00",
			"I would like to book October 19 at 1 pm|B|2026-10-19T13:00",
			"Can you book me for October 19 at 1pm?|B|2026-10-19T13:00",
			"Sa October 19 na lang po|B|2026-10-19T13:00",
			"Yung Monday po|B|2026-10-19T13:00",
			"Monday works for me|B|2026-10-19T13:00",
			"19th October 2026|B|2026-10-19T13:00",
			"10/19/2026|B|2026-10-19T13:00",
			"19/10/2026|B|2026-10-19T13:00",
			"October 20 at 10am|C|2026-10-20T10:00",
			"20 October at 10|C|2026-10-20T10:00",
			"I prefer October 16, 2026 at 09:00|A|2026-10-16T09:00",
			"Tomorrow please|A|2026-10-16T09:00",
			"bukas po|A|2026-10-16T09:00"
	})
	void booksAnOfferedDateOrTimeFromNaturalCustomerWording(String text, String code, String appointment) throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		assertThat(replies.getLast()).contains("date/time");
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", code));

		demo.process(inbound("date-choice", text));

		assertThat(Files.readAllLines(bookings)).hasSize(1);
		JsonNode booking = json.readTree(Files.readAllLines(bookings).getFirst());
		assertThat(booking.path("appointment").asString()).isEqualTo(appointment);
		assertThat(booking.path("slot_code").asString()).isEqualTo(code);
		assertThat(replies.getLast()).contains("Confirmed po!");
	}

	@ParameterizedTest
	@ValueSource(strings = {"CONFIRMED", "SLOT_CHOSEN", "DECLINED", "HANDOFF"})
	void doesNotEndOrStrandTheConversationBasedOnlyOnTheModelStage(String stage) throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		when(client.generateResponse(any())).thenReturn(
				answer("Which slot would you like po?", null, stage, null, null),
				answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));

		demo.process(inbound("model-stage", "Tell me more"));
		assertThat(bookings).doesNotExist();
		demo.process(inbound("choice", "A"));

		verify(client, times(5)).generateResponse(any());
		assertThat(Files.readAllLines(bookings)).hasSize(1);
		assertThat(replies.getLast()).contains("Confirmed po!");
	}

	@Test
	void keepsReplyingAndAllowsRetryWhenSavingTheBookingFails() throws Exception {
		demo.start("sim-1", null);
		reachSlots();
		when(client.generateResponse(any())).thenReturn(answer(null, null, "SLOT_CHOSEN", "booking_requested", "A"));
		Files.createDirectory(bookings);

		demo.process(inbound("failed-save", "A"));

		assertThat(replies.getLast()).doesNotContain("Confirmed po!", "TMB-00001");
		Files.delete(bookings);
		demo.process(inbound("retry-save", "A"));

		verify(client, times(5)).generateResponse(any());
		assertThat(Files.readAllLines(bookings)).hasSize(1);
		assertThat(replies.getLast()).contains("Confirmed po!", "TMB-00001");
	}

	@Test
	void acceptsTheSuppliedLongerMenuAndConfirmationThroughRealTwoSegmentValidation() throws Exception {
		var properties = SmsTestProperties.properties(Map.of("sms.max-segments", "2"));
		var realSms = new SmsService(properties, messages, mock(DealershipRepository.class));
		var ai = new AiProperties(AiProperties.Provider.OPENAI, "synthetic-key", "synthetic-model",
				URI.create("https://example.invalid/v1"), Duration.ofSeconds(1), 0, 4096, "", 2048);
		var assistant = new TmbgBookingAssistant(client, ai, json, new DefaultResourceLoader(),
				Clock.fixed(NOW, ZoneOffset.UTC), bookings.toString());
		demo = new SmsTmbgDemoService(assistant, realSms, messages);
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
