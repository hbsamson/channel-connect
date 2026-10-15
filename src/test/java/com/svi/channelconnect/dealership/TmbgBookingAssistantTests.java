package com.svi.channelconnect.dealership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.AiResponse;
import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;

class TmbgBookingAssistantTests {

	private static final String PAGE = "page-1";
	private static final String PARTICIPANT = "person-1";
	@TempDir Path temporary;
	private final ObjectMapper json = new ObjectMapper();
	private final AiClient client = mock(AiClient.class);

	@Test
	void greetsTheFirstMessengerInboundWithoutAiAndReusesTheGreetingForItsDuplicate() {
		var assistant = assistant(AiProperties.Provider.OPENAI);

		String greeting = assistant.reply("MESSENGER", PAGE, PARTICIPANT, "first", "Hello");

		assertThat(greeting).isNotBlank();
		assertThat(assistant.reply("MESSENGER", PAGE, PARTICIPANT, "first", "Hello")).isEqualTo(greeting);
		assertThat(assistant.accepts("MESSENGER", PAGE, PARTICIPANT)).isTrue();
		verifyNoInteractions(client);
		assertThat(bookingsFile()).doesNotExist();
	}

	@Test
	void checksActiveSmsParticipantsWithoutWaitingForAnotherAiReply() throws Exception {
		var assistant = assistant(AiProperties.Provider.OPENAI);
		String phone = assistant.customerPhone();
		assistant.start("SMS", "sim-1", phone);
		assistant.start("MESSENGER", PAGE, PARTICIPANT);
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		when(client.generateResponse(any())).thenAnswer(invocation -> {
			entered.countDown();
			if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Reply was not released");
			return answer("REG_STATUS_ASK", "REG_STATUS_ASKED");
		});

		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			var reply = workers.submit(() -> assistant.reply("MESSENGER", PAGE, PARTICIPANT, "blocked", "Hello"));
			try {
				assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
				var active = workers.submit(() -> assistant.accepts("SMS", "sim-1", phone));
				assertThat(active.get(1, TimeUnit.SECONDS)).isTrue();
			} finally {
				release.countDown();
			}
			assertThat(reply.get(2, TimeUnit.SECONDS)).isNotBlank();
		}
	}

	@Test
	void isolatesHistoryAndReceiptCachesByChannelPageAndParticipant() {
		var assistant = assistant(AiProperties.Provider.OPENAI);
		when(client.generateResponse(any())).thenReturn(answer("REG_STATUS_ASK", "REG_STATUS_ASKED"));
		List<String[]> conversations = List.of(
				new String[] {"SMS", PAGE, PARTICIPANT, "sms-marker"},
				new String[] {"MESSENGER", PAGE, PARTICIPANT, "messenger-marker"},
				new String[] {"MESSENGER", "page-2", PARTICIPANT, "other-page-marker"},
				new String[] {"MESSENGER", PAGE, "person-2", "other-person-marker"});
		for (String[] conversation : conversations) {
			assistant.reply(conversation[0], conversation[1], conversation[2], "greeting-receipt", conversation[3]);
			String reply = assistant.reply(conversation[0], conversation[1], conversation[2], "shared-receipt", "Hello again");
			assertThat(assistant.reply(conversation[0], conversation[1], conversation[2], "shared-receipt", "Hello again"))
					.isEqualTo(reply);
		}

		var requests = ArgumentCaptor.forClass(AiRequest.class);
		verify(client, times(4)).generateResponse(requests.capture());
		for (int index = 0; index < conversations.size(); index++) {
			AiRequest request = requests.getAllValues().get(index);
			assertThat(request.message()).contains(conversations.get(index)[3], "Hello again");
			for (int other = 0; other < conversations.size(); other++) {
				if (other != index) assertThat(request.message()).doesNotContain(conversations.get(other)[3]);
			}
			assertThat(request.jsonOutput()).isTrue();
			if (conversations.get(index)[0].equals("MESSENGER")) {
				assertThat(request.instructions()).contains("Messenger").doesNotContain("SMS");
				assertThat(request.message()).contains("CUSTOMER MESSAGE\n").doesNotContain("CUSTOMER SMS\n");
			} else {
				assertThat(request.message()).contains("CUSTOMER SMS\n");
			}
		}
	}

	@Test
	void savesTheMessengerBookingOnceAndStopsAiAfterConfirmation() throws Exception {
		var assistant = assistant(AiProperties.Provider.OPENAI);
		when(client.generateResponse(any())).thenReturn(answer("REG_STATUS_ASK", "REG_STATUS_ASKED"),
				answer("OFFER_WINDOW_OPEN", "OFFER_MADE"), answer("SLOTS_OFFER", "SLOTS_OFFERED"),
				new AiResponse(json.writeValueAsString(Map.of("lang", "en", "outcome", "booking_requested",
						"slot_code", "A", "outcome_data", Map.of("services", List.of("registration", "ctpl", "pms"))))));
		assistant.reply("MESSENGER", PAGE, PARTICIPANT, "first", "Hello");
		assistant.reply("MESSENGER", PAGE, PARTICIPANT, "registration", "Hello again");
		assistant.reply("MESSENGER", PAGE, PARTICIPANT, "offer", "Not yet");
		assistant.reply("MESSENGER", PAGE, PARTICIPANT, "slots", "Yes please");

		String confirmation = assistant.reply("MESSENGER", PAGE, PARTICIPANT, "booking", "A");

		assertThat(confirmation).contains("TMB-00001");
		assertThat(assistant.reply("MESSENGER", PAGE, PARTICIPANT, "booking", "A")).isEqualTo(confirmation);
		assertThat(assistant.reply("MESSENGER", PAGE, PARTICIPANT, "after-booking", "Thank you")).isNull();
		verify(client, times(4)).generateResponse(any());
		verifyNoMoreInteractions(client);
		List<JsonNode> bookings = bookings();
		assertThat(bookings).hasSize(1);
		JsonNode booking = bookings.getFirst();
		assertThat(booking.path("channel").asString()).isEqualTo("MESSENGER");
		assertThat(booking.path("account_id").asString()).isEqualTo(PAGE);
		assertThat(booking.path("sender_id").asString()).isEqualTo(PARTICIPANT);
		assertThat(booking.path("phone").asString()).isEqualTo(assistant.customerPhone()).isNotEqualTo(PARTICIPANT);
		assertThat(booking.path("receipt_id").asString()).isEqualTo("booking");
	}

	@Test
	void asksForADateAndTimeWhenTheChosenDayHasMultipleOfferedAppointments() throws Exception {
		var original = new DefaultResourceLoader();
		JsonNode records;
		try (var stream = original.getResource("classpath:sms/tmbg/customer_records_3in1.json").getInputStream()) {
			records = json.readTree(stream);
		}
		((tools.jackson.databind.node.ObjectNode) records.path("records").get(0).path("enrichment"))
				.putArray("slots").add("2026-10-16T09:00").add("2026-10-16T13:00").add("2026-10-19T13:00");
		var resources = new DefaultResourceLoader() {
			@Override public Resource getResource(String location) {
				return location.endsWith("customer_records_3in1.json")
						? new ByteArrayResource(json.writeValueAsBytes(records)) : super.getResource(location);
			}
		};
		var assistant = assistant(AiProperties.Provider.OPENAI, resources);
		when(client.generateResponse(any())).thenReturn(answer("REG_STATUS_ASK", "REG_STATUS_ASKED"),
				answer("OFFER_WINDOW_OPEN", "OFFER_MADE"), answer("SLOTS_OFFER", "SLOTS_OFFERED"),
				bookingAnswer("A"), bookingAnswer("B"));
		offerSlots(assistant, "MESSENGER", PAGE, PARTICIPANT);

		String clarification = assistant.reply("MESSENGER", PAGE, PARTICIPANT, "day-only", "October 16");

		assertThat(bookingsFile()).doesNotExist();
		assertThat(clarification).contains("date/time", "9:00AM", "1:00PM");
		assertThat(assistant.reply("MESSENGER", PAGE, PARTICIPANT, "specific-time", "October 16 at 1pm"))
				.contains("TMB-00001");
		assertThat(bookings().getFirst().path("appointment").asString()).isEqualTo("2026-10-16T13:00");
	}

	@Test
	void sharesSlotAvailabilityAndBookingReferencesAcrossSmsAndMessenger() throws Exception {
		var assistant = assistant(AiProperties.Provider.MOCK);
		String phone = assistant.customerPhone();
		String smsMenu = offerSlots(assistant, "SMS", "sim-1", phone);
		String messengerMenu = offerSlots(assistant, "MESSENGER", PAGE, PARTICIPANT);
		assertThat(messengerMenu).isEqualTo(smsMenu);

		assertThat(assistant.reply("SMS", "sim-1", phone, "booking", "A")).contains("TMB-00001");
		String unavailable = assistant.reply("MESSENGER", PAGE, PARTICIPANT, "booking", "A");

		assertThat(bookings()).hasSize(1);
		String firstAppointment = bookings().getFirst().path("appointment").asString();
		String firstLabel = DateTimeFormatter.ofPattern("EEE MMM d h:mma", Locale.ENGLISH)
				.format(LocalDateTime.parse(firstAppointment));
		assertThat(unavailable).contains("A=").doesNotContain("TMB-", firstLabel);
		assertThat(assistant.reply("MESSENGER", PAGE, PARTICIPANT, "new-selection", "A")).contains("TMB-00002");
		List<JsonNode> bookings = bookings();
		assertThat(bookings).hasSize(2);
		assertThat(bookings).extracting(booking -> booking.path("booking_ref").asString())
				.containsExactly("TMB-00001", "TMB-00002");
		assertThat(bookings.get(1).path("appointment").asString()).isNotEqualTo(firstAppointment);
		assertThat(bookings.get(1).path("channel").asString()).isEqualTo("MESSENGER");
		verifyNoInteractions(client);
	}

	private String offerSlots(TmbgBookingAssistant assistant, String channel, String account, String participant) {
		assistant.start(channel, account, participant);
		assistant.reply(channel, account, participant, "registration", "Hello");
		assistant.reply(channel, account, participant, "offer", "Not yet");
		return assistant.reply(channel, account, participant, "slots", "Yes please");
	}

	private TmbgBookingAssistant assistant(AiProperties.Provider provider) {
		return assistant(provider, new DefaultResourceLoader());
	}

	private TmbgBookingAssistant assistant(AiProperties.Provider provider, ResourceLoader resources) {
		var properties = new AiProperties(provider, "synthetic-key", "synthetic-model", URI.create("https://example.invalid/v1"),
				Duration.ofSeconds(1), 0, 4096, "", 2048);
		return new TmbgBookingAssistant(client, properties, json, resources,
				Clock.fixed(Instant.parse("2026-10-15T01:00:00Z"), ZoneOffset.UTC), bookingsFile().toString());
	}

	private AiResponse answer(String template, String stage) {
		return new AiResponse(json.writeValueAsString(Map.of("lang", "en", "template_id", template,
				"state_after", stage, "reply_text", "Would you like to book a visit?")));
	}

	private AiResponse bookingAnswer(String code) {
		return new AiResponse(json.writeValueAsString(Map.of("lang", "en", "outcome", "booking_requested",
				"slot_code", code, "outcome_data", Map.of("services", List.of("registration", "ctpl", "pms")))));
	}

	private Path bookingsFile() { return temporary.resolve("bookings.txt"); }

	private List<JsonNode> bookings() throws Exception {
		return Files.readAllLines(bookingsFile()).stream().map(json::readTree).toList();
	}
}
