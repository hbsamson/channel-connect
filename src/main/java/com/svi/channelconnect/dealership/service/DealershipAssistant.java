package com.svi.channelconnect.dealership.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.svi.channelconnect.ai.dto.DealershipIntent;
import com.svi.channelconnect.ai.service.DealershipIntentInterpreter;
import com.svi.channelconnect.dealership.config.DealershipProperties;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.model.*;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;

@Service
public class DealershipAssistant {
	private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
	private final DealershipRepository store;
	private final DemoConfiguration config;
	private final DealershipProperties properties;
	private final DealershipIntentInterpreter interpreter;
	private final CampaignTemplates scripts;
	private final Clock clock;

	public DealershipAssistant(DealershipRepository store, DemoConfiguration config, DealershipProperties properties,
			DealershipIntentInterpreter interpreter, CampaignTemplates scripts, Clock clock) {
		this.store = store;
		this.config = config;
		this.properties = properties;
		this.interpreter = interpreter;
		this.scripts = scripts;
		this.clock = clock;
	}

	public Answer answer(String customerId, boolean verified, String message) {
		Workflow state = store.workflow(customerId);
		return apply(customerId, verified, state, interpret(customerId, verified, message, state,
				store.history(customerId, properties.historyLimit()), store.summary(customerId)));
	}

	/** Language interpretation has no business side effects and runs outside SMS database transactions. */
	public DealershipIntent interpret(String customerId, boolean verified, String message, Workflow state, Object history, String summary) {
		CustomerProfile customer = store.customer(customerId);
		Map<String, Object> context = new HashMap<>();
		context.put("workflow", state);
		context.put("history", history);
		context.put("summary", summary);
		context.put("catalog", config.catalog());
		context.put("goal", "Help the customer choose and book an available dealership service appointment, while respecting opt-out and requests for a person.");
		context.put("identityVerified", verified);
		context.put("availableSlots", store.slots(clock.millis()));
		context.put("appointment", verified ? store.appointment(customerId).orElse(null) : null);
		CampaignConfiguration campaign = store.activeCampaign(customerId).orElse(config.campaign());
		context.put("campaign", campaign);
		if (verified) {
			Map<String, Object> known = new HashMap<>();
			known.put("firstName", customer.firstName());
			known.put("vehicleModel", customer.vehicleModel());
			known.put("renewalDue", customer.renewalDue());
			known.put("registrationDue", customer.registrationDue());
			known.put("insuranceDue", customer.insuranceDue());
			known.put("renewalStatus", customer.renewalStatus());
			known.put("ctplStatus", customer.ctplStatus());
			known.put("serviceOfferings", customer.serviceOfferings());
			known.put("dealerBranch", customer.dealerBranch());
			context.put("customer", known);
		}
		return interpreter.interpret(customerId, message,
				config.prompt().replace("{{dealer_name}}", properties.name()), context);
	}

	/** SMS calls this inside the same transaction as scoped state and its durable reply. */
	public Answer apply(String customerId, boolean verified, Workflow state, DealershipIntent intent) {
		CustomerProfile customer = store.customer(customerId);
		CampaignConfiguration campaign = store.activeCampaign(customerId).orElse(config.campaign());
		String language = intent.language();
		state = new Workflow(state.stage(), state.serviceId(), state.date(), state.time(), language);
		var values = scripts.values(customer, properties.name(), serviceName(state.serviceId()), null);
		String action = intent.intent();
		if (action.equals("OPT_OUT")) {
			store.optOut(customerId, clock.millis());
			return new Answer(scripts.render(campaign, "optout", language, values), new Workflow("IDLE", state.serviceId(), null, null, language));
		}
		if (action.equals("OUT_OF_SCOPE")) return new Answer(scripts.render(campaign, "redirect", language, values), state);
		if (action.equals("PRIVACY")) {
			String reply = scripts.render(campaign, "privacy", language, values);
			if (config.catalog().privacyContact() != null) reply += " " + config.catalog().privacyContact();
			return new Answer(reply, state);
		}
		if (action.equals("HANDOFF") || state.stage().equals("HANDOFF_CONFIRM") && action.equals("CONFIRM")) {
			store.event(customerId, null, "HUMAN_HANDOFF_REQUESTED", clock.millis());
			store.executionStatus(customerId, "COMPLETED");
			return new Answer(scripts.render(campaign, "handoff", language, values), new Workflow("IDLE", state.serviceId(), state.date(), state.time(), language));
		}
		if (action.equals("INQUIRY")) {
			return new Answer(local(language, "Those details need confirmation from the dealership po. Would you like me to record a request for a representative?",
					"Kailangan pong kumpirmahin sa dealership ang detalye. Gusto po ba ninyong mag-record tayo ng request para sa representative?"),
					new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), language));
		}
		if (!verified && java.util.Set.of("BOOK", "RESCHEDULE", "CANCEL", "DUE", "CONFIRM").contains(action)) {
			return new Answer(local(language, "Please ask the demo administrator to verify and link this account before accessing customer records or appointments po.",
					"Paki-request po sa demo administrator na i-verify at i-link ang account ninyo bago tingnan ang customer record o appointment."), state);
		}
		if (action.equals("HOURS")) return new Answer(config.catalog().hours(), state);
		if (action.equals("LOCATION")) return new Answer(config.catalog().location(), state);
		if (action.equals("DUE")) {
			boolean insurance = "insurance".equals(intent.serviceId());
			String due = insurance ? customer.insuranceDue() : customer.registrationDue() == null ? customer.renewalDue() : customer.registrationDue();
			String renewal = insurance ? "insurance renewal" : "registration renewal";
			return new Answer(due == null
					? local(language, "Your " + renewal + " date needs dealership confirmation po.", "Kailangan pong kumpirmahin ang " + renewal + " date ninyo.")
					: local(language, "Your " + renewal + " is due on " + due + " in this demo record po.", "Due po ang " + renewal + " ninyo sa " + due + " ayon sa demo record."), state);
		}
		if (action.equals("PRICE")) {
			var service = config.catalog().services().get(intent.serviceId() == null ? state.serviceId() : intent.serviceId());
			return new Answer(service == null || service.price() == null ? "The price needs confirmation from a dealership representative po." : service.price(), state);
		}
		if (java.util.Set.of("VALUE", "PRICE_OBJECTION", "PMS_DETAILS", "INSURANCE_DETAILS", "INSURANCE_OBJECTION",
				"LTO_DETAILS", "LTO_DIY", "WAIT_TIME", "ESSENTIAL_QUOTE", "BUNDLE").contains(action)) {
			if (state.stage().equals("IDLE") && intent.serviceId() != null && customer.serviceOfferings() != null
					&& customer.serviceOfferings().contains(intent.serviceId())) {
				state = new Workflow(state.stage(), intent.serviceId(), state.date(), state.time(), language);
			}
			return cannedAnswer(customerId, verified, state, action, language);
		}
		if (action.equals("PACKAGES")) {
			String id = intent.serviceId() == null ? "3-in-1" : intent.serviceId();
			var offering = config.catalog().services().get(id);
			String reply = offering == null ? "The package inclusions need dealership confirmation po."
					: offering.name() + ": " + String.join(", ", offering.inclusions()) + ".";
			if (verified && id.equals("3-in-1")) reply += " Your demo record lists CTPL as " + (customer.ctplStatus() == null ? "unconfirmed" : customer.ctplStatus()) + " po.";
			if (!verified) state = new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), language);
			else if (state.stage().equals("IDLE") && customer.serviceOfferings() != null && customer.serviceOfferings().contains(id)
					&& store.appointment(customerId).isEmpty()) {
				state = new Workflow(state.stage(), id, state.date(), state.time(), language);
			}
			ReplyQuestion question = appointmentQuestion(verified, store.appointment(customerId).isPresent(), state, language);
			String compact = offering == null || offering.smsInclusions() == null ? null : offering.smsInclusions() + " " + question.smsText();
			return new Answer(reply + " " + question.text(), state, compact);
		}
		if (action.equals("AVAILABILITY")) {
			String selectedService = intent.serviceId() == null ? state.serviceId() : intent.serviceId();
			if (verified && (!config.catalog().services().containsKey(selectedService) || customer.serviceOfferings() == null
					|| !customer.serviceOfferings().contains(selectedService))) {
				return new Answer("That service needs dealership confirmation po. Would you like a representative to help?",
						new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), language));
			}
			String requestedDate = intent.date();
			var slots = store.slots(clock.millis()).stream().filter(s -> requestedDate == null || date(s.startsAt()).equals(requestedDate)).limit(3).toList();
			String reply = slots.isEmpty() ? local(language, "No configured demo slots are available for that date po. What other date works?", "Walang available na demo slot sa petsang iyon po. Anong ibang petsa ang pwede?")
					: local(language, "Available demo slots: ", "Available na demo slots: ") + slots.stream().map(s -> format(s.startsAt())).collect(java.util.stream.Collectors.joining("; ")) + ". "
					+ local(language, "Which would you prefer po?", "Alin ang gusto ninyo po?");
			Workflow next = verified && state.stage().equals("IDLE") && store.appointment(customerId).isEmpty()
					? new Workflow("BOOK", selectedService, requestedDate, null, language) : state;
			String compact = slots.isEmpty() ? local(language, "No configured demo slots for that date. Another date?", "Walang demo slot sa petsang iyon. Ibang petsa?")
					: "Demo slots: " + slots.stream().limit(2).map(s -> date(s.startsAt()) + " " + time(s.startsAt()))
							.collect(java.util.stream.Collectors.joining("; ")) + " Asia/Manila. "
							+ local(language, "Choose date/time?", "Anong petsa/oras?");
			return new Answer(reply, next, compact);
		}
		if (action.equals("DECLINE")) {
			store.executionStatus(customerId, "COMPLETED");
			store.event(customerId, null, "CAMPAIGN_COMPLETED", clock.millis());
			return new Answer(scripts.render(campaign, "closing", language, values), new Workflow("IDLE", state.serviceId(), null, null, language));
		}
		if (action.equals("CANCEL")) {
			if (store.appointment(customerId).isEmpty()) return new Answer("There is no active demo appointment to cancel po.", state);
			return new Answer(local(language, "Would you like me to cancel your current demo appointment po?", "Gusto po ba ninyong i-cancel ang kasalukuyang demo appointment?"),
					new Workflow("CANCEL_CONFIRM", state.serviceId(), state.date(), state.time(), language));
		}
		if (state.stage().equals("CANCEL_CONFIRM") && action.equals("CONFIRM")) {
			store.cancel(customerId, clock.millis());
			return new Answer(scripts.render(campaign, "cancellation", language, values), new Workflow("IDLE", state.serviceId(), null, null, language));
		}
		if (!verified) return new Answer(scripts.render(campaign, "redirect", language, values), state);
		boolean dateSelection = action.equals("CONTINUE") && (intent.date() != null || intent.time() != null);
		if (dateSelection && state.stage().equals("IDLE") && store.appointment(customerId).isPresent()) {
			return new Answer(local(language,
					"An appointment is already recorded. Please request a reschedule if you would like to change it po.",
					"May naka-record nang appointment. Paki-request po ng reschedule kung gusto ninyong palitan ito."), state);
		}
		boolean canStartFromDate = dateSelection && config.catalog().services().containsKey(state.serviceId())
				&& customer.serviceOfferings() != null && customer.serviceOfferings().contains(state.serviceId());
		if (action.equals("BOOK") || action.equals("RESCHEDULE") || action.equals("CONFIRM")
				|| action.equals("CONTINUE") && (!state.stage().equals("IDLE") || canStartFromDate)) {
			return appointment(customerId, customer, state, intent, campaign);
		}
		return new Answer(scripts.render(campaign, "redirect", language, values), state);
	}

	private Answer cannedAnswer(String customerId, boolean verified, Workflow state, String action, String language) {
		var approved = config.catalog().cannedAnswers() == null ? null : config.catalog().cannedAnswers().get(action);
		if (approved == null) {
			return new Answer(local(language,
					"Those details need dealership confirmation po. Would you like a representative to explain the service and quote?",
					"Kailangan pong kumpirmahin sa dealership ang detalye. Gusto po ba ninyong magpaliwanag ang representative ng service at quote?"),
					new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), language),
					local(language, "Those details need advisor confirmation. Request a representative?", "Kailangan ng advisor confirmation. Mag-request ng representative?"));
		}
		String reply = "tl".equals(language) && approved.taglish() != null ? approved.taglish() : approved.english();
		if (!verified) state = new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), language);
		ReplyQuestion question = appointmentQuestion(verified, store.appointment(customerId).isPresent(), state, language);
		String compact = "tl".equals(language) && approved.smsTaglish() != null ? approved.smsTaglish() : approved.smsEnglish();
		return new Answer(reply + " " + question.text(), state, compact == null ? null : compact + " " + question.smsText());
	}

	private ReplyQuestion appointmentQuestion(boolean verified, boolean booked, Workflow state, String language) {
		if (!verified || state.stage().equals("HANDOFF_CONFIRM")) return new ReplyQuestion(
				local(language, "Would you like a representative to help po?", "Gusto po ba ninyong tumulong ang representative?"),
				local(language, "Ask a representative to help?", "Mag-request ng representative?"));
		if (state.stage().equals("CANCEL_CONFIRM")) return new ReplyQuestion(
				local(language, "Would you still like to cancel your demo appointment po?", "Gusto pa rin po ba ninyong i-cancel ang demo appointment?"),
				local(language, "Cancel your demo appointment?", "I-cancel ang demo appointment?"));
		if (state.stage().equals("BOOK") || state.stage().startsWith("RESCHEDULE")) {
			if (state.date() == null) return new ReplyQuestion(
					local(language, "What date would you prefer for your visit po?", "Anong petsa ang gusto ninyo para sa visit po?"),
					local(language, "Preferred visit date?", "Anong petsa ng visit?"));
			if (state.time() == null) return new ReplyQuestion(
					local(language, "What time would you prefer on " + state.date() + " po?", "Anong oras ang gusto ninyo sa " + state.date() + " po?"),
					local(language, "Preferred time on " + state.date() + "?", "Anong oras sa " + state.date() + "?"));
			return new ReplyQuestion(
					local(language, "What other date and time would work for you po?", "Anong ibang petsa at oras ang pwede sa inyo po?"),
					local(language, "Another date and time?", "Ibang petsa at oras?"));
		}
		if (booked) return new ReplyQuestion(
				local(language, "Would you like to check or change your appointment po?", "Gusto po ba ninyong tingnan o palitan ang appointment?"),
				local(language, "Check or change your demo appointment?", "Tingnan o palitan ang demo appointment?"));
		return new ReplyQuestion(
				local(language, "Would you like to book a " + serviceName(state.serviceId()) + " visit po?", "Gusto po ba ninyong mag-book ng " + serviceName(state.serviceId()) + " visit?"),
				local(language, "Book a " + serviceName(state.serviceId()) + " visit?", "Mag-book ng " + serviceName(state.serviceId()) + " visit?"));
	}

	/** Transport presentation remains grounded in the same approved answer and saved workflow. */
	public String smsReply(Answer answer) {
		return answer.smsText() == null ? answer.text() : answer.smsText();
	}

	private Answer appointment(String id, CustomerProfile customer, Workflow state, DealershipIntent intent, CampaignConfiguration campaign) {
		boolean reschedule = intent.intent().equals("RESCHEDULE") || state.stage().startsWith("RESCHEDULE");
		var existing = store.appointment(id);
		if (reschedule && existing.isEmpty() && state.stage().equals("BOOK")) reschedule = false;
		if (reschedule && existing.isEmpty()) return new Answer("There is no active demo appointment to reschedule po. Would you like to book one?", Workflow.initial());
		if (!reschedule && existing.isPresent()) return new Answer("You already have a demo appointment on " + format(existing.get().startsAt()) + " po. Would you like to reschedule it?", state);
		String service = intent.serviceId() == null ? state.serviceId() : intent.serviceId();
		if (!config.catalog().services().containsKey(service) || customer.serviceOfferings() == null || !customer.serviceOfferings().contains(service)) {
			return new Answer("That service needs dealership confirmation po. Would you like a representative to help?",
					new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), state.language()));
		}
		String requestedDate = intent.date() == null ? state.date() : intent.date();
		String requestedTime = intent.time() == null ? state.time() : intent.time();
		if (intent.intent().equals("RESCHEDULE")) {
			requestedDate = intent.date();
			requestedTime = intent.time();
		} else if (intent.date() != null && !intent.date().equals(state.date())) requestedTime = intent.time();
		String stage = reschedule ? "RESCHEDULE" : "BOOK";
		Workflow next = new Workflow(stage, service, requestedDate, requestedTime, intent.language());
		if (requestedDate == null) return new Answer(local(intent.language(), "What date would you prefer for your demo " + serviceName(service) + " visit po?", "Anong petsa ang gusto ninyo para sa demo " + serviceName(service) + " visit po?"), next);
		if (requestedTime == null) return new Answer(local(intent.language(), "What time would you prefer on " + requestedDate + " po?", "Anong oras ang gusto ninyo sa " + requestedDate + " po?"), next);
		long startsAt = LocalDate.parse(requestedDate).atTime(LocalTime.parse(requestedTime)).atZone(MANILA).toInstant().toEpochMilli();
		var slot = store.slots(clock.millis()).stream().filter(s -> s.startsAt() == startsAt).findFirst();
		if (slot.isEmpty()) return new Answer(local(intent.language(), "That demo slot is unavailable po. What other date and time would you prefer?", "Hindi available ang demo slot na iyon po. Anong ibang schedule ang gusto ninyo?"), next);
		Appointment saved = store.book(id, service, slot.get().id(), reschedule, clock.millis());
		String reply = scripts.render(campaign, reschedule ? "rescheduling" : "booking", intent.language(), scripts.values(customer, properties.name(), serviceName(saved.serviceId()), format(saved.startsAt())));
		String compact = local(intent.language(), reschedule ? "Demo appointment moved: " : "Demo appointment recorded: ",
				reschedule ? "Nailipat ang demo appointment: " : "Naitala ang demo appointment: ")
				+ date(saved.startsAt()) + " " + time(saved.startsAt()) + " Asia/Manila. "
				+ local(intent.language(), "This is not a real reservation.", "Wala pang tunay na reservation.");
		return new Answer(reply, new Workflow("IDLE", saved.serviceId(), date(saved.startsAt()), time(saved.startsAt()), intent.language()), compact);
	}
	private String serviceName(String id) {
		var service = config.catalog().services().get(id);
		return service == null ? "service" : service.name();
	}
	private String local(String language, String english, String filipino) { return "tl".equals(language) ? filipino : english; }
	private String date(long at) { return Instant.ofEpochMilli(at).atZone(MANILA).toLocalDate().toString(); }
	private String time(long at) { return Instant.ofEpochMilli(at).atZone(MANILA).toLocalTime().toString(); }
	private String format(long at) { return DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a 'Asia/Manila'").format(Instant.ofEpochMilli(at).atZone(MANILA)); }
	public record Answer(String text, Workflow workflow, String smsText) {
		public Answer(String text, Workflow workflow) { this(text, workflow, null); }
	}
	private record ReplyQuestion(String text, String smsText) { }
}
