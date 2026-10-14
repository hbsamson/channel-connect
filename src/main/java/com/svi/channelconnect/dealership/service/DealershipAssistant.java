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
		context.put("workflowStage", state.stage());
		context.put("history", store.history(customerId, properties.historyLimit()));
		context.put("summary", store.summary(customerId));
		context.put("catalog", config.catalog());
		context.put("appointment", store.appointment(customerId).orElse(null));
		context.put("representativeRequest", store.representativeRequest(customerId, null).orElse(null));
		CampaignConfiguration campaign = store.activeCampaign(customerId).orElse(config.campaign());
		context.put("campaign", campaign);
		// Reply wording can change without rewriting the campaign's saved outreach configuration.
		if (campaign.id().equals(config.campaign().id())) campaign = config.campaign();
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
		if (action.equals("SERVICES")) {
			String offerings = config.catalog().services().values().stream().map(DemoConfiguration.Service::name)
					.limit(5).collect(java.util.stream.Collectors.joining(", "));
			return new Answer(local(language, "Our services include " + offerings + ". Which service would you like help with po?",
					"Kasama sa services namin ang " + offerings + ". Aling service ang gusto ninyo po?"), state);
		}
		if (action.equals("REQUEST_STATUS")) {
			var reference = java.util.regex.Pattern.compile("(?i)\\bREQ-[A-Z0-9]{16}\\b").matcher(message);
			String requestedTracking = reference.find() ? reference.group().toUpperCase(java.util.Locale.ROOT) : null;
			var request = store.representativeRequest(customerId, requestedTracking);
			if (request.isPresent()) return new Answer(requestStatus(request.get(), language), state);
			if (requestedTracking != null) return new Answer(local(language,
					"I couldn't find that tracking number in your conversation po. Could you check the reference?",
					"Hindi ko makita ang tracking number na iyon sa conversation ninyo po. Paki-check po ang reference."), state);
			return new Answer(local(language, "I haven't recorded a representative request in this conversation yet po. Would you like me to record one?",
					"Wala pa pong naitalang request para sa representative sa conversation na ito. Gusto po ba ninyong mag-record tayo?"),
					new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), language));
		}
		if (action.equals("VERIFY_HELP") || action.equals("CLARIFY") && state.stage().equals("VERIFY_REQUIRED")) {
			return new Answer(local(language,
					"A dealership representative needs to verify that this account belongs to you before I access customer records or confirm a booking po. Would you like me to record a request for help with verification?",
					"Kailangang i-verify ng representative na sa inyo ang account bago makita ang customer record o makumpirma ang booking po. Gusto po ba ninyong mag-record tayo ng request para sa verification?"),
					new Workflow("VERIFY_REQUIRED", state.serviceId(), state.date(), state.time(), language));
		}
		if (action.equals("CLARIFY")) {
			if (state.stage().equals("CANCEL_CONFIRM")) return new Answer(local(language,
					"I'm asking whether you want to cancel your current appointment request po. Should I cancel it?",
					"Tinatanong ko po kung gusto ninyong i-cancel ang kasalukuyang appointment request. I-cancel ko po ba?"), state);
			if (java.util.Set.of("BOOK", "RESCHEDULE").contains(state.stage())) return new Answer(local(language,
					"We're choosing a date and time for your visit po. What schedule would you prefer?",
					"Pumipili tayo ng petsa at oras para sa visit ninyo po. Anong schedule ang gusto ninyo?"), state);
			if (state.stage().equals("HANDOFF_CONFIRM")) return new Answer(local(language,
					"I'm offering to record a request for a representative to help with your inquiry po. Would you like me to do that?",
					"Maaari po akong mag-record ng request para tulungan kayo ng representative. Gusto po ba ninyo?"), state);
			if (state.stage().equals("HANDOFF_REQUESTED")) {
				var request = store.representativeRequest(customerId, null);
				if (request.isPresent()) return new Answer(local(language, "Sorry, that wasn't clear po. ", "Pasensya po, hindi malinaw ang sagot ko. ")
						+ requestStatus(request.get(), language), state);
			}
			return new Answer(local(language, "I didn't fully understand your request po. Could you rephrase it?",
					"Hindi ko lubos na naintindihan ang request ninyo po. Maaari po bang sabihin ninyo ito sa ibang paraan?"), state);
		}
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
		if (action.equals("HANDOFF") || java.util.Set.of("HANDOFF_CONFIRM", "VERIFY_REQUIRED", "HANDOFF_REQUESTED").contains(state.stage()) && action.equals("CONFIRM")) {
			RepresentativeRequest request = store.recordRepresentativeRequest(customerId, state, clock.millis());
			store.executionStatus(customerId, "COMPLETED");
			values.put("tracking_number", request.trackingNumber());
			values.put("request_status", request.status().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
			String reply = scripts.render(campaign, "handoff", language, values);
			if (!reply.contains(request.trackingNumber())) reply += " Tracking number: " + request.trackingNumber() + "; status: " + values.get("request_status") + ".";
			return new Answer(reply,
					new Workflow("HANDOFF_REQUESTED", state.serviceId(), state.date(), state.time(), language));
		}
		if (action.equals("INQUIRY")) {
			return new Answer(local(language, "Those details need confirmation from the dealership po. Would you like me to record a request for a representative?",
					"Kailangan pong kumpirmahin sa dealership ang detalye. Gusto po ba ninyong mag-record tayo ng request para sa representative?"),
					new Workflow("HANDOFF_CONFIRM", state.serviceId(), state.date(), state.time(), language));
		}
		if (!verified && java.util.Set.of("BOOK", "RESCHEDULE", "CANCEL", "DUE", "CONFIRM").contains(action)) {
			return new Answer(local(language, "I can help plan your visit po. A dealership representative needs to verify this account before I access customer records or confirm a booking; would you like me to record a request for help?",
					"Makakatulong po ako sa pagplano ng visit ninyo. Kailangang i-verify muna ng representative ang account bago makita ang customer record o makumpirma ang booking; gusto po ba ninyong mag-record tayo ng request para sa tulong?"),
					new Workflow("VERIFY_REQUIRED", intent.serviceId() == null ? state.serviceId() : intent.serviceId(),
							intent.date() == null ? state.date() : intent.date(), intent.time() == null ? state.time() : intent.time(), language));
		}
		if (action.equals("HOURS")) return new Answer(config.catalog().hours(), state);
		if (action.equals("LOCATION")) return new Answer(config.catalog().location(), state);
		if (action.equals("DUE")) return new Answer(customer.renewalDue() == null
				? local(language, "Your renewal date needs confirmation from the dealership po.", "Kailangan pong kumpirmahin sa dealership ang renewal date ninyo.")
				: local(language, "Your registration renewal is due on " + customer.renewalDue() + " according to your record po.", "Due po ang registration renewal ninyo sa " + customer.renewalDue() + " ayon sa record."), state);
		if (action.equals("PRICE")) {
			var service = config.catalog().services().get(intent.serviceId() == null ? state.serviceId() : intent.serviceId());
			return new Answer(service == null || service.price() == null ? "The price needs confirmation from a dealership representative po." : service.price(), state);
		}
		if (action.equals("PACKAGES")) {
			String id = intent.serviceId() == null ? "3-in-1" : intent.serviceId();
			var offering = config.catalog().services().get(id);
			String reply = offering == null ? "The package inclusions need dealership confirmation po."
					: offering.name() + ": " + String.join(", ", offering.inclusions()) + ".";
			if (verified && id.equals("3-in-1")) reply += " Your record lists CTPL as " + (customer.ctplStatus() == null ? "unconfirmed" : customer.ctplStatus()) + " po.";
			return new Answer(reply, state);
		}
		if (action.equals("AVAILABILITY")) {
			String requestedDate = intent.date();
			var slots = store.slots(clock.millis()).stream().filter(s -> requestedDate == null || date(s.startsAt()).equals(requestedDate)).limit(3).toList();
			String reply = slots.isEmpty() ? local(language, "No configured slots are available for that date po. What other date works?", "Walang available na slot sa petsang iyon po. Anong ibang petsa ang pwede?")
					: local(language, "Available slots: ", "Available na slots: ") + slots.stream().map(s -> format(s.startsAt())).collect(java.util.stream.Collectors.joining("; ")) + ". "
					+ local(language, "Which would you prefer po?", "Alin ang gusto ninyo po?");
			return new Answer(reply, state);
		}
		if (action.equals("DECLINE")) {
			store.executionStatus(customerId, "COMPLETED");
			store.event(customerId, null, "CAMPAIGN_COMPLETED", clock.millis());
			return new Answer(scripts.render(campaign, "closing", language, values), new Workflow("IDLE", state.serviceId(), null, null, language));
		}
		if (action.equals("CANCEL")) {
			if (store.appointment(customerId).isEmpty()) return new Answer("There is no active appointment request to cancel po.", state);
			return new Answer(local(language, "Would you like me to cancel your current appointment request po?", "Gusto po ba ninyong i-cancel ang kasalukuyang appointment request?"),
					new Workflow("CANCEL_CONFIRM", state.serviceId(), state.date(), state.time(), language));
		}
		if (state.stage().equals("CANCEL_CONFIRM") && action.equals("CONFIRM")) {
			store.cancel(customerId, clock.millis());
			return new Answer(scripts.render(campaign, "cancellation", language, values), new Workflow("IDLE", state.serviceId(), null, null, language));
		}
		if (!verified) return new Answer(scripts.render(campaign, "redirect", language, values), state);
		if (action.equals("BOOK") || action.equals("RESCHEDULE") || action.equals("CONFIRM")
				|| action.equals("CONTINUE") && java.util.Set.of("BOOK", "RESCHEDULE").contains(state.stage())) {
			return appointment(customerId, customer, state, intent, campaign);
		}
		return new Answer(scripts.render(campaign, "redirect", language, values), state);
	}

	private Answer appointment(String id, CustomerProfile customer, Workflow state, DealershipIntent intent, CampaignConfiguration campaign) {
		boolean reschedule = intent.intent().equals("RESCHEDULE") || state.stage().startsWith("RESCHEDULE");
		var existing = store.appointment(id);
		if (reschedule && existing.isEmpty() && state.stage().equals("BOOK")) reschedule = false;
		if (reschedule && existing.isEmpty()) return new Answer("There is no active appointment request to reschedule po. Would you like to book one?", Workflow.initial());
		if (!reschedule && existing.isPresent()) return new Answer("You already have an appointment request on " + format(existing.get().startsAt()) + " po. Would you like to reschedule it?", state);
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
		if (requestedDate == null) return new Answer(local(intent.language(), "What date would you prefer for your " + serviceName(service) + " visit po?", "Anong petsa ang gusto ninyo para sa " + serviceName(service) + " visit po?"), next);
		if (requestedTime == null) return new Answer(local(intent.language(), "What time would you prefer on " + requestedDate + " po?", "Anong oras ang gusto ninyo sa " + requestedDate + " po?"), next);
		long startsAt = LocalDate.parse(requestedDate).atTime(LocalTime.parse(requestedTime)).atZone(MANILA).toInstant().toEpochMilli();
		var slot = store.slots(clock.millis()).stream().filter(s -> s.startsAt() == startsAt).findFirst();
		if (slot.isEmpty()) return new Answer(local(intent.language(), "That slot is unavailable po. What other date and time would you prefer?", "Hindi available ang slot na iyon po. Anong ibang schedule ang gusto ninyo?"), next);
		Appointment saved = store.book(id, service, slot.get().id(), reschedule, clock.millis());
		String reply = scripts.render(campaign, reschedule ? "rescheduling" : "booking", intent.language(), scripts.values(customer, properties.name(), serviceName(saved.serviceId()), format(saved.startsAt())));
		return new Answer(reply, new Workflow("IDLE", saved.serviceId(), date(saved.startsAt()), time(saved.startsAt()), intent.language()));
	}
	private String serviceName(String id) {
		var service = config.catalog().services().get(id);
		return service == null ? "service" : service.name();
	}
	private String requestStatus(RepresentativeRequest request, String language) {
		String status = request.status().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return local(language, "Your request ", "Ang request ninyo na ") + request.trackingNumber()
				+ local(language, " is recorded po, with status ", " ay naitala na po, na may status ") + status
				+ local(language, ". A representative has not been notified automatically; the tracking number lets you follow up here.",
						". Wala pang automatic notification sa representative; magagamit ninyo ang tracking number para mag-follow up dito.");
	}
	private String local(String language, String english, String filipino) { return "tl".equals(language) ? filipino : english; }
	private String date(long at) { return Instant.ofEpochMilli(at).atZone(MANILA).toLocalDate().toString(); }
	private String time(long at) { return Instant.ofEpochMilli(at).atZone(MANILA).toLocalTime().toString(); }
	private String format(long at) { return DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a 'Asia/Manila'").format(Instant.ofEpochMilli(at).atZone(MANILA)); }
	public record Answer(String text, Workflow workflow) { }
	public String introduction() {
		return "Hello po! I'm the customer service bot for " + properties.name() + ". ";
	}
}
