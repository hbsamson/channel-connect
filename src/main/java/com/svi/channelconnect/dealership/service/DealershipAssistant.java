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
		CustomerProfile customer = store.customer(customerId);
		Workflow state = store.workflow(customerId);
		Map<String, Object> context = new HashMap<>();
		context.put("workflow", state);
		context.put("history", store.history(customerId, properties.historyLimit()));
		context.put("summary", store.summary(customerId));
		context.put("catalog", config.catalog());
		context.put("appointment", store.appointment(customerId).orElse(null));
		CampaignConfiguration campaign = store.activeCampaign(customerId).orElse(config.campaign());
		context.put("campaign", campaign);
		if (verified) {
			Map<String, Object> known = new HashMap<>();
			known.put("firstName", customer.firstName());
			known.put("vehicleModel", customer.vehicleModel());
			known.put("renewalDue", customer.renewalDue());
			known.put("renewalStatus", customer.renewalStatus());
			known.put("ctplStatus", customer.ctplStatus());
			context.put("customer", known);
		}
		DealershipIntent intent = interpreter.interpret(customerId, message,
				config.prompt().replace("{{dealer_name}}", properties.name()), context);
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
		if (action.equals("DUE")) return new Answer(customer.renewalDue() == null
				? local(language, "Your renewal date needs confirmation from the dealership po.", "Kailangan pong kumpirmahin sa dealership ang renewal date ninyo.")
				: local(language, "Your registration renewal is due on " + customer.renewalDue() + " in this demo record po.", "Due po ang registration renewal ninyo sa " + customer.renewalDue() + " ayon sa demo record."), state);
		if (action.equals("PRICE")) {
			var service = config.catalog().services().get(intent.serviceId() == null ? state.serviceId() : intent.serviceId());
			return new Answer(service == null || service.price() == null ? "The price needs confirmation from a dealership representative po." : service.price(), state);
		}
		if (action.equals("PACKAGES")) {
			String id = intent.serviceId() == null ? "3-in-1" : intent.serviceId();
			var offering = config.catalog().services().get(id);
			String reply = offering == null ? "The package inclusions need dealership confirmation po."
					: offering.name() + ": " + String.join(", ", offering.inclusions()) + ".";
			if (verified && id.equals("3-in-1")) reply += " Your demo record lists CTPL as " + (customer.ctplStatus() == null ? "unconfirmed" : customer.ctplStatus()) + " po.";
			return new Answer(reply, state);
		}
		if (action.equals("AVAILABILITY")) {
			String requestedDate = intent.date();
			var slots = store.slots(clock.millis()).stream().filter(s -> requestedDate == null || date(s.startsAt()).equals(requestedDate)).limit(3).toList();
			String reply = slots.isEmpty() ? local(language, "No configured demo slots are available for that date po. What other date works?", "Walang available na demo slot sa petsang iyon po. Anong ibang petsa ang pwede?")
					: local(language, "Available demo slots: ", "Available na demo slots: ") + slots.stream().map(s -> format(s.startsAt())).collect(java.util.stream.Collectors.joining("; ")) + ". "
					+ local(language, "Which would you prefer po?", "Alin ang gusto ninyo po?");
			return new Answer(reply, state);
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
		if (action.equals("BOOK") || action.equals("RESCHEDULE") || action.equals("CONFIRM") || action.equals("CONTINUE") && !state.stage().equals("IDLE")) {
			return appointment(customerId, customer, state, intent, campaign);
		}
		return new Answer(scripts.render(campaign, "redirect", language, values), state);
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
		return new Answer(reply, new Workflow("IDLE", saved.serviceId(), date(saved.startsAt()), time(saved.startsAt()), intent.language()));
	}
	private String serviceName(String id) {
		var service = config.catalog().services().get(id);
		return service == null ? "service" : service.name();
	}
	private String local(String language, String english, String filipino) { return "tl".equals(language) ? filipino : english; }
	private String date(long at) { return Instant.ofEpochMilli(at).atZone(MANILA).toLocalDate().toString(); }
	private String time(long at) { return Instant.ofEpochMilli(at).atZone(MANILA).toLocalTime().toString(); }
	private String format(long at) { return DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a 'Asia/Manila'").format(Instant.ofEpochMilli(at).atZone(MANILA)); }
	public record Answer(String text, Workflow workflow) { }
}
