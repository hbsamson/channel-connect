package com.svi.channelconnect.dealership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.dto.DealershipIntent;
import com.svi.channelconnect.ai.service.DealershipIntentInterpreter;
import com.svi.channelconnect.dealership.config.DealershipProperties;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.model.Appointment;
import com.svi.channelconnect.dealership.model.Workflow;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.support.TestProperties;
import com.svi.channelconnect.support.SmsTestProperties;
import com.svi.channelconnect.sms.service.SmsValidation;

class DealershipAssistantTests {
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ZoneOffset.UTC);
	private final DealershipRepository store = mock(DealershipRepository.class);
	private final DealershipIntentInterpreter interpreter = new DealershipIntentInterpreter(mock(AiClient.class),
			TestProperties.mockAi(), new ObjectMapper(), clock);
	private DemoConfiguration configuration;
	private DealershipAssistant assistant;

	@BeforeEach
	void configure() {
		var properties = new DealershipProperties("Toyota Manila Bay", "classpath:dealership/catalog.json",
				"classpath:campaigns/registration-renewal.json", "classpath:dealership/customers.json",
				"classpath:prompts/dealership-assistant.md", false, 20, 3);
		configuration = new DemoConfiguration(new ObjectMapper(), new DefaultResourceLoader(), properties);
		when(store.customer("demo-renewal")).thenReturn(configuration.customers().getFirst());
		when(store.activeCampaign("demo-renewal")).thenReturn(Optional.empty());
		when(store.appointment("demo-renewal")).thenReturn(Optional.empty());
		assistant = new DealershipAssistant(store, configuration, properties, interpreter, new CampaignTemplates(), clock);
	}

	@Test
	void priceObjectionUsesApprovedFactsAndResumesPendingAppointmentWithoutBooking() {
		var pending = new Workflow("BOOK", "registration", "2026-10-16", null, "en");
		var answer = assistant.apply("demo-renewal", true, pending,
				interpreter.parse("Your product is too expensive; I can get it cheaper at the other store"));
		assertThat(answer.text()).contains("Toyota Genuine Parts", "trained technicians", "essential-only", "2026-10-16");
		assertThat(answer.workflow()).isEqualTo(pending);
		assertThat(assistant.smsReply(answer)).contains("Genuine parts", "essential-only", "2026-10-16").hasSizeLessThanOrEqualTo(160);
		verify(store, never()).book(anyString(), anyString(), anyString(), eq(false), anyLong());
	}

	@Test
	void productAnswersStayGroundedAndRetainTaglishConversationState() {
		var answer = assistant.apply("demo-renewal", true, Workflow.initial(),
				interpreter.parse("Ang mahal sa casa kumpara sa labas po"));
		assertThat(answer.text()).contains("Naiintindihan", "Toyota Genuine Parts", "mag-book");
		assertThat(answer.workflow().language()).isEqualTo("tl");
		var essential = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("Show the essential-only estimate"));
		assertThat(essential.text()).contains("no essential-only price is configured").doesNotContain("PHP", "%", "guarantee");
		var wait = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("I have no time to wait"));
		assertThat(wait.text()).contains("Turnaround and availability need confirmation");
	}

	@Test
	void availableSlotInquiryCanContinueIntoBookingWithCustomerSelectedDateAndTime() {
		long at = Instant.parse("2026-10-16T01:00:00Z").toEpochMilli();
		when(store.slots(clock.millis())).thenReturn(List.of(new DealershipRepository.AvailableSlot("2026-10-16-0900", at, 2)));
		when(store.book("demo-renewal", "registration", "2026-10-16-0900", false, clock.millis()))
				.thenReturn(new Appointment("appt", "demo-renewal", "registration", "2026-10-16-0900", "BOOKED", at));
		var options = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("What slots are available?"));
		assertThat(options.workflow().stage()).isEqualTo("BOOK");
		assertThat(options.workflow().date()).isNull();
		verify(store, never()).book(anyString(), anyString(), anyString(), eq(false), anyLong());
		var booked = assistant.apply("demo-renewal", true, options.workflow(), interpreter.parse("Friday at 9am"));
		assertThat(booked.text()).contains("recorded", "Oct 16");
		assertThat(assistant.smsReply(booked)).contains("Demo appointment recorded", "2026-10-16 09:00 Asia/Manila", "not a real reservation");
		SmsValidation.content(assistant.smsReply(booked), SmsTestProperties.properties(Map.of()));
		verify(store).book("demo-renewal", "registration", "2026-10-16-0900", false, clock.millis());
	}

	@Test
	void absentApprovedFactsOfferHandoffAndUnverifiedAccountsDoNotBook() {
		var catalog = configuration.catalog();
		var custom = mock(DemoConfiguration.class);
		when(custom.catalog()).thenReturn(new DemoConfiguration.Catalog(catalog.disclaimer(), catalog.hours(),
				catalog.location(), catalog.privacyContact(), catalog.services(), catalog.slots(), null));
		when(custom.campaign()).thenReturn(configuration.campaign());
		var properties = mock(DealershipProperties.class);
		when(properties.name()).thenReturn("Toyota Manila Bay");
		var limited = new DealershipAssistant(store, custom, properties, interpreter, new CampaignTemplates(), clock);
		var answer = limited.apply("demo-renewal", false, Workflow.initial(), interpreter.parse("Why Toyota Manila Bay?"));
		assertThat(answer.text()).contains("need dealership confirmation").doesNotContain("Genuine Parts");
		assertThat(answer.workflow().stage()).isEqualTo("HANDOFF_CONFIRM");
		var blocked = assistant.apply("demo-renewal", false, Workflow.initial(), interpreter.parse("Book Friday at 9am"));
		assertThat(blocked.text()).contains("verify and link");
		verify(store, never()).book(anyString(), anyString(), anyString(), eq(false), anyLong());
	}

	@Test
	void configuredEnglishAndTaglishProductAnswersFitSingleSegmentSmsAndInviteBooking() {
		var properties = SmsTestProperties.properties(Map.of());
		for (String action : configuration.catalog().cannedAnswers().keySet()) {
			for (String language : List.of("en", "tl")) {
				String service = action.equals("PMS_DETAILS") ? "pms" : action.startsWith("INSURANCE") ? "insurance" : null;
				var answer = assistant.apply("demo-renewal", true, Workflow.initial(), new DealershipIntent(action, null, null, service, language));
				String sms = assistant.smsReply(answer);
				assertThat(sms).contains("visit?").hasSizeLessThanOrEqualTo(160);
				SmsValidation.content(sms, properties);
				assertThat(sms).doesNotContain("Please contact the dealership for the full response");
			}
		}
	}

	@Test
	void insuranceCanBeBookedIndependentlyFromPmsAndRegistration() {
		long at = Instant.parse("2026-10-16T01:00:00Z").toEpochMilli();
		when(store.slots(clock.millis())).thenReturn(List.of(new DealershipRepository.AvailableSlot("2026-10-16-0900", at, 2)));
		when(store.book("demo-renewal", "insurance", "2026-10-16-0900", false, clock.millis()))
				.thenReturn(new Appointment("appt", "demo-renewal", "insurance", "2026-10-16-0900", "BOOKED", at));
		var answer = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("Book insurance Friday at 9am"));
		assertThat(answer.text()).contains("Toyota Insure consultation", "recorded");
		verify(store).book("demo-renewal", "insurance", "2026-10-16-0900", false, clock.millis());
	}

	@Test
	void objectionDuringCancellationRetainsThePendingCancellationQuestion() {
		var state = new Workflow("CANCEL_CONFIRM", "registration", "2026-10-16", "09:00", "en");
		var answer = assistant.apply("demo-renewal", true, state, interpreter.parse("Why Toyota Manila Bay?"));
		assertThat(answer.workflow()).isEqualTo(state);
		assertThat(answer.text()).contains("still like to cancel").doesNotContain("like to book");
		assertThat(assistant.smsReply(answer)).contains("Cancel your demo appointment?");
		verify(store, never()).cancel(anyString(), anyLong());
	}

	@Test
	void insuranceDueInquiryDoesNotUseTheRegistrationDueDate() {
		var answer = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("When is my insurance renewal due?"));
		assertThat(answer.text()).contains("insurance renewal", "needs dealership confirmation").doesNotContain("2026-10-31");
	}

	@Test
	void pmsAvailabilityFollowedByTimeOnlyBooksTheRequestedServiceAndDate() {
		long at = Instant.parse("2026-10-16T01:00:00Z").toEpochMilli();
		when(store.slots(clock.millis())).thenReturn(List.of(new DealershipRepository.AvailableSlot("2026-10-16-0900", at, 2)));
		when(store.book("demo-renewal", "pms", "2026-10-16-0900", false, clock.millis()))
				.thenReturn(new Appointment("appt", "demo-renewal", "pms", "2026-10-16-0900", "BOOKED", at));
		var options = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("Is PMS available Friday?"));
		assertThat(options.workflow().serviceId()).isEqualTo("pms");
		assertThat(options.workflow().date()).isEqualTo("2026-10-16");
		var booked = assistant.apply("demo-renewal", true, options.workflow(), interpreter.parse("9am"));
		assertThat(booked.workflow().serviceId()).isEqualTo("pms");
		verify(store).book("demo-renewal", "pms", "2026-10-16-0900", false, clock.millis());
	}

	@Test
	void configuredPackageInclusionsFitSingleSegmentAndRetainSelectedPackage() {
		for (String service : List.of("2-in-1", "3-in-1")) {
			var answer = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("What is included in " + service + "?"));
			String sms = assistant.smsReply(answer);
			assertThat(sms).contains(service, "PMS", "visit?").hasSizeLessThanOrEqualTo(160);
			assertThat(answer.workflow().serviceId()).isEqualTo(service);
			SmsValidation.content(sms, SmsTestProperties.properties(Map.of()));
		}
	}

	@Test
	void unverifiedCannedAnswerCanConfirmRepresentativeRequestWithoutRequiringAppointmentVerification() {
		var answer = assistant.apply("demo-renewal", false, Workflow.initial(), interpreter.parse("Why Toyota Manila Bay?"));
		assertThat(answer.workflow().stage()).isEqualTo("HANDOFF_CONFIRM");
		var handoff = assistant.apply("demo-renewal", false, answer.workflow(), interpreter.parse("Yes"));
		assertThat(handoff.text()).contains("recorded your request").doesNotContain("verify and link");
		verify(store).event(eq("demo-renewal"), eq(null), eq("HUMAN_HANDOFF_REQUESTED"), anyLong());
	}

	@Test
	void dateOnlyReplyToScriptedOutreachAsksTimeThenBooksTheCurrentService() {
		long at = Instant.parse("2026-10-16T01:00:00Z").toEpochMilli();
		when(store.slots(clock.millis())).thenReturn(List.of(new DealershipRepository.AvailableSlot("2026-10-16-0900", at, 2)));
		when(store.book("demo-renewal", "registration", "2026-10-16-0900", false, clock.millis()))
				.thenReturn(new Appointment("appt", "demo-renewal", "registration", "2026-10-16-0900", "BOOKED", at));
		var date = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("Friday"));
		assertThat(date.text()).contains("What time", "2026-10-16");
		assertThat(date.workflow().serviceId()).isEqualTo("registration");
		var booked = assistant.apply("demo-renewal", true, date.workflow(), interpreter.parse("9am"));
		assertThat(assistant.smsReply(booked)).contains("Demo appointment recorded", "2026-10-16 09:00");
		verify(store).book("demo-renewal", "registration", "2026-10-16-0900", false, clock.millis());
	}

	@Test
	void idleDateOnlyReplyCannotSilentlyMoveAnExistingAppointment() {
		long at = Instant.parse("2026-10-16T01:00:00Z").toEpochMilli();
		when(store.appointment("demo-renewal")).thenReturn(Optional.of(new Appointment("appt", "demo-renewal", "registration", "2026-10-16-0900", "BOOKED", at)));
		var answer = assistant.apply("demo-renewal", true, Workflow.initial(), interpreter.parse("Saturday 10am"));
		assertThat(answer.text()).contains("already recorded", "request a reschedule").doesNotContain("moved");
		assertThat(answer.workflow()).isEqualTo(Workflow.initial());
		verify(store, never()).book(anyString(), anyString(), anyString(), eq(true), anyLong());
	}
}
