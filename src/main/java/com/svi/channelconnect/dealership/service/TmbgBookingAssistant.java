package com.svi.channelconnect.dealership.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;

/** Shared booking rules and availability, with separate conversation state for each channel and participant. */
@Service
@Profile({"local", "test"})
public class TmbgBookingAssistant {

	private static final Logger LOGGER = LoggerFactory.getLogger(TmbgBookingAssistant.class);
	private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
	private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
	private static final DateTimeFormatter SLOT_LABEL = DateTimeFormatter.ofPattern("EEE MMM d h:mma", Locale.ENGLISH);
	private static final List<String> SERVICES = List.of("pms", "insurance", "renewal");
	private static final int SLOT_CHOICES = 3;
	private static final Set<String> STAGES = Set.of("OPENER_SENT", "REG_STATUS_ASKED", "REG_STATUS_KNOWN", "SERVICE_OFFERED", "OFFER_MADE", "SLOTS_OFFERED");
	private static final Pattern FALSE_CONFIRMATION = Pattern.compile("(?i)\\b(confirmed|booked|TMB-\\d+)\\b");
	private static final Pattern INTERNAL_LABEL = Pattern.compile("(?i)\\bdemo\\b");
	private static final String CAPABILITY_RULES = """

		CHAT CAPABILITIES (take precedence over suggested actions in templates or reference material)
		A saved booking ends booking operations, but general dealership questions remain available.
		Already-renewed customers may continue with maintenance or insurance. Never default to registration or add unaccepted services.
		No callbacks, pickups, quote delivery or human transfers are implemented. Do not promise those actions.
		For a person, callback or unavailable action, politely ask the customer to contact Toyota Manila Bay directly. Do not follow that with another sales question.
		Never use the word demo in customer-facing replies. Do not describe a locally recorded visit as a live dealership reservation.
		Never set CONFIRMED. Only the service saves a booking and sends its reference.
		Choosing an offered date, day or time that identifies one time confirms the customer's intent to book; no extra yes is needed.
		Set slot to the exact offered date/time and request booking_requested. Never show internal codes, letter labels or numbered reply instructions.
		Availability questions, refusals, tentative dates and ambiguous choices are not slot selections. A clear request to book an offered date is a selection, including "Can you book me for...".
		If a date has multiple offered times, ask which time they want. For an unavailable date, show the available dates/times. Keep SLOTS_OFFERED until a clear selection.
		GENERAL DEALERSHIP QUESTIONS: Use APPROVED DEALERSHIP KNOWLEDGE to answer questions about Toyota Manila Bay and its services at any stage.
		For a general question answered by that knowledge, set knowledge_topic to its exact key and reply_text to null; the service supplies the approved answer.
		Only select a knowledge_topic if its answer actually addresses the latest question. Do not treat unrelated questions as dealership questions or repeat a sales pitch for missing facts.
		Do not select a topic for booking commands, consent, opt-out, appointment changes, or vehicle-specific facts not covered by that answer. Set knowledge_topic to null otherwise.
		AFTER CONFIRMED: Never restart booking, offer new slots, cancel, reschedule or claim changes were saved. For changes set outcome to booking_change; otherwise unsupported questions use outcome unavailable. General knowledge questions still use knowledge_topic.
		Offer at most three appointment choices at a time, using only the current slots_text. When the customer asks to explore other dates while SLOTS_OFFERED, set outcome more_dates; the service supplies the next set. Never assemble your own menu from the full customer record.
		Do not invent contact numbers, URLs, prices, offers, address, hours, inventory, availability, warranty promises or advisor handoffs. Tell customers to contact Toyota Manila Bay directly for unsupported requests.
		""";

	private final AiClient client;
	private final AiProperties ai;
	private final ObjectMapper json;
	private final Clock clock;
	private final Path bookingsFile;
	private final JsonNode customer;
	private final Map<String, JsonNode> templates = new LinkedHashMap<>();
	private final Map<ConversationKey, Session> sessions = new ConcurrentHashMap<>();
	private final List<JsonNode> bookings = new ArrayList<>();
	private final String rules;
	private final String instructions;
	private final String playbook;
	private final JsonNode serviceCatalog;
	private final JsonNode insurancePrices;
	private final TmbgKnowledgeBase knowledge;

	public TmbgBookingAssistant(AiClient client, AiProperties ai, ObjectMapper json, ResourceLoader resources, Clock clock,
			@Value("${sms.tmbg-bookings-file:bookings.txt}") String bookingsFile) {
		this.client = client; this.ai = ai; this.json = json; this.clock = clock;
		this.bookingsFile = Path.of(bookingsFile);
		try {
			knowledge = new TmbgKnowledgeBase(resources, json);
			rules = read(resources, "rules_3in1.txt");
			instructions = read(resources, "system-prompt.md") + CAPABILITY_RULES;
			playbook = read(resources, "negotiation_playbook.txt") + "\n" + read(resources, "dealership-reference.txt");
			serviceCatalog = json.readTree(read(resources, "service_catalog.json"));
			insurancePrices = json.readTree(read(resources, "insurance_prices.json"));
			customer = json.readTree(read(resources, "customer_records_3in1.json")).path("records").get(0);
			for (JsonNode template : json.readTree(read(resources, "templates_3in1.json")).path("templates")) {
				templates.put(template.path("id").asString(), template);
			}
			if (Files.exists(this.bookingsFile)) {
				for (String line : Files.readAllLines(this.bookingsFile, StandardCharsets.UTF_8)) {
					if (!line.isBlank()) bookings.add(json.readTree(line));
				}
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load booking demo resources or bookings file", exception);
		}
	}

	public String customerPhone() { return customer.path("phone").asString(); }

	public synchronized String greeting(String channel, String participant) {
		Session session = new Session(channel);
		return render("OPEN_GREETING", session, fields(session, phone(channel, participant)));
	}

	public synchronized String start(String channel, String account, String participant) {
		Session session = new Session(channel);
		String greeting = greeting(channel, participant);
		session.history.add(Map.of("direction", "OUTBOUND", "content", greeting));
		sessions.put(new ConversationKey(channel, account, participant), session);
		return greeting;
	}

	public boolean accepts(String channel, String account, String participant) {
		return sessions.containsKey(new ConversationKey(channel, account, participant));
	}

	public synchronized String reply(String channel, String account, String participant, String receiptId, String content) {
		ConversationKey key = new ConversationKey(channel, account, participant);
		Session session = sessions.get(key);
		if (session == null) {
			if (!optOut(content)) {
				String greeting = start(channel, account, participant);
				session = sessions.get(key);
				session.history.addFirst(Map.of("direction", "INBOUND", "content", content));
				session.replies.put(receiptId, greeting);
				return greeting;
			}
			session = new Session(channel);
			sessions.put(key, session);
		}
		if (session.replies.containsKey(receiptId)) return session.replies.get(receiptId);
		if (session.suppressed) return null;
		session.language = content.toLowerCase(Locale.ROOT).matches(
				"(?s).*\\b(po|opo|hindi|sige|oo|salamat|magkano|ano|anong|gusto|pwede|puwede|paki|ako|ko|kami|namin|ninyo|niyo|yung|iyong|sa|ng|na|lang|bukas|kailan|paano|wala|meron|ayoko|huwag|wag)\\b.*") ? "tl" : "en";
		String previousRegistration = session.registration;
		List<String> previousServices = session.services;
		if (!session.stage.equals("CONFIRMED")) registrationStatus(session, content);
		Map<String, Object> fields = fields(session, phone(channel, participant));
		String reply;
		try {
			if (optOut(content)) {
				session.suppressed = true;
				reply = render("OPTOUT_CONFIRM", session, fields);
			} else if (content.toLowerCase(Locale.ROOT).matches("(?s).*\\b(wrong number|not the owner|hindi ako si|maling number)\\b.*")) {
				session.suppressed = true;
				reply = render("ID_WRONG_PERSON", session, fields);
			} else if (session.stage.equals("SLOTS_OFFERED") && content.toLowerCase(Locale.ROOT).trim().matches(
					"(?s)(?:more|next)[.!? ]*|.*\\b(?:(?:more|other|different|next|later|another) (?:dates?|slots?|options?|times?)|ibang (?:petsa|araw|schedule)|iba pa)\\b.*")) {
				reply = moreDates(session, phone(channel, participant));
			} else if (session.stage.equals("CONFIRMED") && knowledge.bookingChange(content)) {
				reply = bookingChangesUnavailable(session);
			} else {
				JsonNode answer = ai.provider() == AiProperties.Provider.MOCK ? mockAnswer(session, content, fields)
						: json.readTree(client.generateResponse(new AiRequest(receiptId, context(session, content, fields), channelText(instructions, channel), true)).text());
				if (answer != null && (knowledge.answer(answer.path("knowledge_topic").asString(""), session.language, channel) != null
						|| answer.path("outcome").asString().equals("unavailable"))) {
					session.registration = previousRegistration;
					session.services = previousServices;
				}
				reply = apply(session, new Incoming(channel, account, participant, receiptId, content), answer, fields);
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("Booking demo reply unavailable; using prepared template");
			reply = fallback(session, fields);
		}
		if (session.language.equals("en")) reply = fullEnglish(reply);
		session.replies.put(receiptId, reply);
		session.history.add(Map.of("direction", "INBOUND", "content", content));
		session.history.add(Map.of("direction", "OUTBOUND", "content", reply));
		return reply;
	}

	private String context(Session session, String text, Map<String, Object> fields) {
		List<Map<String, String>> filled = new ArrayList<>();
		for (var entry : templates.entrySet()) {
			if (!entry.getValue().path("used_by").asString().equals("assistant")) continue;
			filled.add(Map.of("id", entry.getKey(), "en", render(entry.getKey(), "en", fields), "tl", render(entry.getKey(), "tl", fields)));
		}
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("stage", session.stage); state.put("registration_status", session.registration);
		state.put("language", session.language); state.put("slots_offered", slotList(session.slots));
		state.put("services", session.services); state.put("history", session.history);
		state.put("service_status", session.serviceStatus); state.put("service_in_focus", session.focus);
		return "RULES\n" + channelText(rules + CAPABILITY_RULES, session.channel)
				+ "\nNEGOTIATION PLAYBOOK\n" + playbook
				+ "\nSERVICE CATALOG\n" + json.writeValueAsString(serviceCatalog)
				+ "\nINSURANCE PRICES\n" + json.writeValueAsString(insurancePrices)
				+ "\nCUSTOMER RECORD\n" + json.writeValueAsString(customer)
				+ "\nDERIVED FIELDS\n" + json.writeValueAsString(fields)
				+ "\nCONVERSATION STATE\n" + json.writeValueAsString(state)
				+ "\nTEMPLATES\n" + json.writeValueAsString(filled)
				+ "\nAPPROVED DEALERSHIP KNOWLEDGE\n" + json.writeValueAsString(knowledge.answers())
				+ channelText("\nCUSTOMER SMS\n", session.channel) + json.writeValueAsString(text);
	}

	private String apply(Session session, Incoming incoming, JsonNode answer, Map<String, Object> fields) {
		if (answer == null || !answer.isObject() || !Set.of("en", "tl").contains(answer.path("lang").asString())) return fallback(session, fields);
		session.language = answer.path("lang").asString();
		if (session.stage.equals("CONFIRMED") && (knowledge.bookingChange(incoming.content())
				|| Set.of("booking_change", "booking_requested", "callback_requested", "collection_booked").contains(answer.path("outcome").asString()))) {
			return bookingChangesUnavailable(session);
		}
		String approved = knowledge.answer(answer.path("knowledge_topic").asString(""), session.language, session.channel);
		if (approved != null) return session.language.equals("en") ? fullEnglish(approved) : approved;
		if (session.stage.equals("CONFIRMED")) return chatUnavailable(session);
		if (session.stage.equals("SLOTS_OFFERED") && answer.path("outcome").asString().equals("more_dates")) {
			return moreDates(session, phone(incoming.channel(), incoming.sender()));
		}
		String template = answer.path("template_id").asString("");
		String outcome = answer.path("outcome").asString("");
		if (outcome.equals("opt_out") || outcome.equals("wrong_person")) {
			session.suppressed = true;
			return render(outcome.equals("opt_out") ? "OPTOUT_CONFIRM" : "ID_WRONG_PERSON", session, fields);
		}
		if (outcome.equals("unavailable")) return chatUnavailable(session);
		for (JsonNode flag : answer.path("flags")) {
			if (flag.asString().equals("expired_registration")) session.registration = "expired";
		}
		if (session.stage.equals("REG_STATUS_ASKED") && session.registration.equals("unknown")) {
			if (template.equals("REG_ALREADY_RENEWED")) session.registration = "renewed";
			if (template.equals("RENEWAL_OFFER_OPEN") || template.equals("RENEWAL_OFFER_UPCOMING")) session.registration = "not_yet";
			if (session.registration.equals("not_yet") && today().isAfter(LocalDate.parse(customer.path("reg_window_end").asString()))) session.registration = "expired";
		}
		if (!outcome.equals("booking_requested")) updateServices(session, answer);
		if (outcome.equals("booking_requested")) return book(session, incoming, answer, fields);
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (answer.path("needs_human").asBoolean(false) || Set.of("handoff", "callback_requested", "collection_booked").contains(outcome)
				|| Set.of("HANDOFF", "CALLBACK_CONFIRM", "EXPIRED_COLLECTION").contains(template)) {
			return contactDealership(session);
		}
		if (!template.isEmpty() && (!templates.containsKey(template) || !templates.get(template).path("used_by").asString().equals("assistant"))) return fallback(session, fields);
		if (!answer.path("reply_text").isString()) return fallback(session, fields);
		String text = answer.path("reply_text").asString();
		if (!validSms(text) || FALSE_CONFIRMATION.matcher(text).find()) return fallback(session, fields);
		String stage = answer.path("state_after").asString();
		if (template.equals("RENEWAL_ASK_STATUS") || template.equals("REG_VALID_CHECK")) stage = "REG_STATUS_ASKED";
		if (template.equals("REG_ALREADY_RENEWED")) stage = "OFFER_MADE";
		if (template.equals("SLOTS_OFFER") || stage.equals("SLOTS_OFFERED")) {
			if (session.services.isEmpty()) return fallback(session, fields);
			if (session.registration.equals("unknown") && fields.get("window_status").equals("closed")) {
				session.stage = "REG_STATUS_ASKED";
				return render("REG_VALID_CHECK", session, fields);
			}
			if (availableSlots().isEmpty()) return noSlots(session);
			session.slots = slotChoices(session);
			stage = "SLOTS_OFFERED";
			// Keep the exact offered times for subsequent selection validation.
			text = render("SLOTS_OFFER", session, fields(session, phone(incoming.channel(), incoming.sender())));
		}
		if (STAGES.contains(stage)) session.stage = stage;
		return text;
	}

	private String book(Session session, Incoming incoming, JsonNode answer, Map<String, Object> fields) {
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		String code = chosenSlot(session, incoming.content());
		if (!session.stage.equals("SLOTS_OFFERED") || session.services.isEmpty() || code == null
				|| (session.registration.equals("unknown") && fields.get("window_status").equals("closed"))) return fallback(session, fields);
		String selected = session.slots.get(code);
		if (!selected.equals(answer.path("slot").asString())) return fallback(session, fields);
		if (!availableSlots().containsValue(selected)) {
			session.slots = slotChoices(session);
			if (session.slots.isEmpty()) return noSlots(session);
			return render("SLOT_TAKEN", session, fields(session, phone(incoming.channel(), incoming.sender())));
		}
		List<String> services = new ArrayList<>();
		for (JsonNode value : answer.path("outcome_data").path("services")) {
			if (!SERVICES.contains(value.asString())) return fallback(session, fields);
			if (!services.contains(value.asString())) services.add(value.asString());
		}
		if (services.isEmpty()) services.addAll(session.services);
		if (!Set.copyOf(services).equals(Set.copyOf(session.services))) return fallback(session, fields);
		// Retain the existing booking-file vocabulary; renewal includes the required basic insurance.
		services = services.stream().flatMap(service -> switch (service) {
			case "renewal" -> java.util.stream.Stream.of("registration", "ctpl");
			case "insurance" -> java.util.stream.Stream.of("comprehensive");
			default -> java.util.stream.Stream.of(service);
		}).toList();
		Map<String, Object> booking = new LinkedHashMap<>();
		int next = bookings.stream().mapToInt(value -> Integer.parseInt(value.path("booking_ref").asString().substring(4))).max().orElse(0) + 1;
		String reference = "TMB-%05d".formatted(next);
		booking.put("booking_ref", reference); booking.put("receipt_id", incoming.id());
		booking.put("customer_id", customer.path("customer_id").asString()); booking.put("plate", customer.path("plate").asString());
		booking.put("account_id", incoming.accountId()); booking.put("phone", phone(incoming.channel(), incoming.sender()));
		if (incoming.channel().equals("MESSENGER") || incoming.channel().equals("WHATSAPP")) {
			booking.put("channel", incoming.channel()); booking.put("sender_id", incoming.sender());
		}
		booking.put("slot_code", code); booking.put("appointment", selected); booking.put("services", services);
		booking.put("created_at", clock.instant().atZone(MANILA).toOffsetDateTime().toString());
		fields.put("booking_ref", reference); fields.put("slot_selected", label(selected));
		fields.put("services_list", String.join(", ", session.services.stream().map(this::serviceName).toList()));
		String confirmation = render("BOOKING_CONFIRMED", session, fields);
		if (!validSms(confirmation)) return render("BOOKING_ERROR", session, fields);
		try {
			Files.writeString(bookingsFile, json.writeValueAsString(booking) + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			LOGGER.warn("Booking demo file could not be written");
			return render("BOOKING_ERROR", session, fields);
		}
		bookings.add(json.valueToTree(booking));
		session.stage = "CONFIRMED";
		return confirmation;
	}

	private JsonNode mockAnswer(Session session, String text, Map<String, Object> fields) {
		String normalized = text.toLowerCase(Locale.ROOT);
		String topic = normalized.matches("(?s).*\\b(magkano|how much|cost|fees?)\\b.*") ? "" : knowledge.mockTopic(text);
		if (!topic.isEmpty() || session.stage.equals("CONFIRMED")) {
			return json.valueToTree(Map.of("lang", session.language, "knowledge_topic", topic,
					"outcome", session.stage.equals("CONFIRMED") && knowledge.bookingChange(text) ? "booking_change" : "unavailable"));
		}
		String template;
		String stage = session.stage;
		String code = chosenSlot(session, text);
		Map<String, Object> answer = new LinkedHashMap<>();
		if (session.registration.equals("expired")) template = "DRIVE_IN_REFUSAL";
		else if (session.stage.equals("SLOTS_OFFERED") && code != null) {
			answer.put("lang", session.language); answer.put("outcome", "booking_requested"); answer.put("slot", session.slots.get(code));
			answer.put("reply_text", null); answer.put("outcome_data", Map.of("services", session.services));
			return json.valueToTree(answer);
		} else if (normalized.matches("(?s).*\\b(person|advisor|agent|tao|callback|call me)\\b.*")) {
			answer.put("lang", session.language); answer.put("outcome", "handoff");
			return json.valueToTree(answer);
		} else if (normalized.matches("(?s).*\\b(busy|not now|mamaya)\\b.*")) {
			template = "CALLBACK_ASK";
		} else if (normalized.matches("(?s).*\\b(price|cost|magkano|fee)\\b.*")) {
			template = "insurance".equals(session.focus) ? "INSURANCE_PRICE_INFO"
					: "renewal".equals(session.focus) ? "FEES" : "PMS_PRICE_INFO";
		} else if (normalized.contains("ctpl")) template = "BASIC_INSURANCE_INFO";
		else if (session.stage.equals("REG_STATUS_ASKED") && !session.registration.equals("unknown") && !session.services.isEmpty()) {
			template = "SLOTS_OFFER"; stage = "SLOTS_OFFERED";
		} else if (normalized.matches("(?s).*\\b(no|no thanks|hindi|ayoko|already serviced|just serviced|already renewed|may insurance na)\\b.*")) {
			if (session.focus != null) session.serviceStatus.put(session.focus,
					normalized.matches("(?s).*(already|just serviced|may insurance na).*") ? "skipped" : "declined");
			template = offerNext(session);
			stage = "SERVICE_OFFERED";
		} else if (normalized.matches("(?:yes|yes please|sure|ok|okay|sige|sige po|oo|opo|please do|go ahead)[.! ]*")
				&& session.focus != null && session.serviceStatus.get(session.focus).equals("offered")) {
			session.serviceStatus.put(session.focus, "accepted");
			session.services = SERVICES.stream().filter(service -> session.serviceStatus.get(service).equals("accepted")).toList();
			template = "SLOTS_OFFER"; stage = "SLOTS_OFFERED";
		} else if (session.stage.equals("OPENER_SENT")) {
			template = pmsOffer(); stage = "SERVICE_OFFERED";
		} else template = "UNCLEAR";
		answer.put("lang", session.language); answer.put("template_id", template); answer.put("state_after", stage);
		answer.put("reply_text", render(template, session, fields));
		return json.valueToTree(answer);
	}

	private void updateServices(Session session, JsonNode answer) {
		for (String service : SERVICES) {
			String previous = session.serviceStatus.get(service);
			String next = answer.path("service_status").path(service).asString("");
			if (!Set.of("declined", "skipped").contains(previous)
					&& Set.of("not_offered", "offered", "accepted", "declined", "skipped").contains(next)) {
				session.serviceStatus.put(service, next);
			}
		}
		String focus = answer.path("service_in_focus").asString("");
		if (SERVICES.contains(focus)) session.focus = focus;
		session.services = SERVICES.stream().filter(service -> session.serviceStatus.get(service).equals("accepted")).toList();
	}

	private String offerNext(Session session) {
		if (session.serviceStatus.values().stream().filter("declined"::equals).count() >= 2) return "DECLINE_CLOSE";
		for (String service : SERVICES) {
			if (!session.serviceStatus.get(service).equals("not_offered")) continue;
			if (service.equals("insurance") && customer.path("comp_status").asString().equals("active")) {
				// Without a configured expiry threshold, ask rather than infer that a policy is due.
				JsonNode days = insurancePrices.path("offer_if_expiring_within_days");
				String expiry = customer.path("comp_expiry_date").asString("");
				if (days.isNumber() && !expiry.isEmpty() && LocalDate.parse(expiry).isAfter(today().plusDays(days.asLong()))) {
					session.serviceStatus.put(service, "skipped"); continue;
				}
			}
			if (service.equals("renewal") && session.registration.equals("renewed")) {
				session.serviceStatus.put(service, "skipped"); continue;
			}
			session.focus = service;
			session.serviceStatus.put(service, "offered");
			return switch (service) {
				case "pms" -> pmsOffer();
				case "insurance" -> customer.path("comp_status").asString().equals("lapsed") ? "INSURANCE_OFFER_LAPSED" : "INSURANCE_ASK_STATUS";
				default -> "RENEWAL_ASK_STATUS";
			};
		}
		return "DECLINE_CLOSE";
	}

	private String pmsOffer() {
		return switch (customer.path("service_state").asString()) {
			case "overdue" -> "PMS_OFFER_OVERDUE";
			case "stopped" -> "PMS_OFFER_RETURNING";
			case "never_serviced_here" -> "PMS_OFFER_FIRST";
			default -> "PMS_OFFER_DUE_SOON";
		};
	}

	private String serviceName(String service) {
		return switch (service) {
			case "pms" -> "regular maintenance";
			case "insurance" -> "car insurance";
			case "renewal" -> "registration renewal";
			default -> throw new IllegalArgumentException("Unknown service");
		};
	}

	private boolean optOut(String text) {
		return text.toLowerCase(Locale.ROOT).trim().matches("(?:stop|tigil|unsubscribe|opt[ -]?out)[.! ]*|(?s).*(?:do not|don't|huwag|wag) (?:contact|text|message|send|na.*(?:text|message)).*|(?s).*(?:no more (?:messages|reminders)|remove me from|ayaw ko na.*(?:message|reminder)).*");
	}

	private String contactDealership(Session session) {
		return session.language.equals("tl") ? "Sige po. Hindi po ako makakapag-connect sa advisor dito. Kontakin po ang Toyota Manila Bay nang direkta para sa tulong."
				: "Of course. I can't connect you with an advisor here. Please contact Toyota Manila Bay directly for help.";
	}

	private void registrationStatus(Session session, String text) {
		String value = text.toLowerCase(Locale.ROOT).trim();
		boolean mentionsRegistration = value.matches("(?s).*\\b(registration|rehistro|lto)\\b.*");
		if (!mentionsRegistration && (!session.stage.equals("REG_STATUS_ASKED") && !"renewal".equals(session.focus)
				|| value.matches("(?s).*\\b(insurance|policy)\\b.*"))) return;
		if (value.matches("(?s).*(expired|lapsed|window has passed|since june).*")) session.registration = "expired";
		else if (value.matches("(?s).*(not sure|unsure|hindi ko alam|di ko sure|nakalimutan).*")) session.registration = "unknown";
		else if (value.matches("(?s).*(not yet|not renewed|haven't renewed|have not renewed|hindi.*pa.*renew|di.*pa.*renew|hindi pa|di pa|wala pa).*")) session.registration = "not_yet";
		else if (value.matches("(?s).*(already renewed|na-renew ko na|narenew ko na|na-renew na|narenew na|tapos na|done na|last month pa).*")
				|| value.matches("(renewed|na-renew|narenew)( na)?( po)?[.! ]*")) session.registration = "renewed";
		else if (session.stage.equals("REG_STATUS_ASKED")) {
			if (value.matches("(yes|yes po|oo|opo|oo po)[.! ]*")) session.registration = "renewed";
		}
		if (session.registration.equals("not_yet") && today().isAfter(LocalDate.parse(customer.path("reg_window_end").asString()))) session.registration = "expired";
	}

	private Map<String, Object> fields(Session session, String phone) {
		Map<String, Object> fields = new LinkedHashMap<>();
		LocalDate start = LocalDate.parse(customer.path("reg_window_start").asString());
		LocalDate end = LocalDate.parse(customer.path("reg_window_end").asString());
		fields.put("today", today().toString());
		fields.put("window_status", today().isBefore(start) ? "upcoming" : today().isAfter(end) ? "closed" : "open");
		fields.put("opens_in_days", ChronoUnit.DAYS.between(today(), start)); fields.put("closes_in_days", ChronoUnit.DAYS.between(today(), end));
		fields.put("reg_window_start", SHORT_DATE.format(start)); fields.put("reg_window_end", SHORT_DATE.format(end));
		fields.put("first_name", customer.path("first_name").asString()); fields.put("plate", customer.path("plate").asString());
		String model = customer.path("model_description").asString("your Toyota");
		fields.put("model_or_plate", model + " (" + customer.path("plate").asString() + ")");
		fields.put("dial_number", phone); fields.put("branch_name", customer.path("enrichment").path("branch_name").asString());
		for (String name : List.of("pms_milestone_km", "pms_price", "reg_service_fee", "collection_note")) {
			JsonNode value = customer.path("enrichment").path(name);
			fields.put(name, value.isNull() || value.isMissingNode() ? "to be confirmed by a TMBG advisor"
					: (name.equals("pms_price") || name.equals("reg_service_fee")) && !value.asString().startsWith("PHP ") ? "PHP " + value.asString() : value.asString());
		}
		JsonNode priceRange = insurancePrices.path("price_range");
		fields.put("insurance_price", priceRange.path("low").isNumber() && priceRange.path("high").isNumber()
				? "PHP " + priceRange.path("low").asString() + " to " + priceRange.path("high").asString() : "to be confirmed by an advisor");
		fields.put("comp_expiry_date", customer.path("comp_expiry_date").isString()
				? SHORT_DATE.format(LocalDate.parse(customer.path("comp_expiry_date").asString())) : "to be confirmed by an advisor");
		fields.put("service_name", session.focus == null ? "service" : serviceName(session.focus));
		fields.put("next_service_name", SERVICES.stream().filter(service -> session.serviceStatus.get(service).equals("not_offered"))
				.findFirst().map(this::serviceName).orElse("service"));
		Map<String, String> slots = session.stage.equals("SLOTS_OFFERED") ? session.slots : slotChoices(session);
		fields.put("slots_text", slots.values().stream().map(this::label).reduce((a, b) -> a + "; " + b).orElse("no times available"));
		return fields;
	}

	private Map<String, String> availableSlots() {
		Map<String, String> slots = new LinkedHashMap<>();
		LocalDate start = LocalDate.parse(customer.path("reg_window_start").asString());
		LocalDate end = LocalDate.parse(customer.path("reg_window_end").asString());
		List<String> configured = new ArrayList<>();
		for (JsonNode value : customer.path("enrichment").path("slots")) configured.add(value.asString());
		for (String slot : configured.stream().sorted().toList()) {
			LocalDateTime appointment = LocalDateTime.parse(slot);
			LocalDate date = appointment.toLocalDate();
			if (!appointment.isAfter(LocalDateTime.now(clock.withZone(MANILA))) || date.isBefore(start) || date.isAfter(end)
					|| bookings.stream().anyMatch(booking -> booking.path("appointment").asString().equals(slot))) continue;
			slots.put(String.valueOf((char) ('A' + slots.size())), slot);
		}
		return slots;
	}

	private Map<String, String> slotChoices(Session session) {
		List<String> available = new ArrayList<>(availableSlots().values());
		if (session.slotOffset >= available.size()) session.slotOffset = 0;
		Map<String, String> choices = new LinkedHashMap<>();
		for (int index = session.slotOffset; index < Math.min(session.slotOffset + SLOT_CHOICES, available.size()); index++) {
			choices.put(String.valueOf((char) ('A' + choices.size())), available.get(index));
		}
		return choices;
	}

	private String moreDates(Session session, String phone) {
		session.slotOffset += SLOT_CHOICES;
		session.slots = slotChoices(session);
		if (session.slots.isEmpty()) return noSlots(session);
		return render("SLOTS_OFFER", session, fields(session, phone));
	}

	private String chosenSlot(Session session, String text) {
		// Match a complete selection, rather than a date mentioned in a question or refusal.
		String value = text.toLowerCase(Locale.ROOT).replace('\u2019', '\'').trim().replaceAll("\\s+", " ")
				.replaceAll(",\\s*", " ").replace("a.m.", "am").replace("p.m.", "pm")
				.replaceAll("\\b(jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\\.", "$1")
				.replaceAll("\\b(\\d{1,2})(?:st|nd|rd|th)\\b", "$1");
		if (value.contains("?") && !value.matches("^(?:please )?(?:(?:can|could|would) you (?:please )?)?(?:book|reserve|schedule)\\b.*")) return null;
		value = value.replaceAll("[.!?]+$", "").trim()
				.replaceFirst("^(?:(?:yes|okay|ok|sige|oo|opo|please) )+", "")
				.replaceFirst("^(?:i(?:'d| would) like|i (?:want|prefer)|gusto ko(?: po)?)(?: to)?\\s+", "")
				.replaceFirst("^(?:i(?:'ll| will) (?:take|choose)|let's (?:do|book)|i meant)\\s+", "")
				.replaceFirst("^(?:i(?:'m| am) available|i can (?:come|visit)|my (?:choice|preferred date) is)(?: on)?\\s+", "")
				.replaceFirst("^(?:(?:can|could|would) you (?:please )?)?(?:book|reserve|schedule|choose|select|paki-?book|pa-?book)(?: me| my (?:visit|appointment))?(?: po)?\\s+", "")
				.replaceFirst("^(?:(?:for|on|sa|yung|this|coming|the) )+", "")
				.replaceFirst("(?: (?:po|please|na lang|instead|works for me|would work for me|works|would work|is fine|is good|na|lang))+$", "")
				.replaceAll("\\s+(am|pm)\\b", "$1");
		if (value.matches("(?:the )?(?:first|earliest)(?: one)?|(?:yung )?pinakamaaga")) {
			return session.slots.keySet().stream().findFirst().orElse(null);
		}
		List<String> matches = new ArrayList<>();
		for (var entry : session.slots.entrySet()) {
			LocalDateTime slot = LocalDateTime.parse(entry.getValue());
			List<String> dates = new ArrayList<>(List.of(slot.toLocalDate().toString(), Integer.toString(slot.getDayOfMonth())));
			for (String format : List.of("MMM d", "MMMM d", "d MMM", "d MMMM", "M/d", "MM/dd", "d/M", "dd/MM")) {
				String date = DateTimeFormatter.ofPattern(format, Locale.ENGLISH).format(slot).toLowerCase(Locale.ROOT);
				dates.add(date);
				dates.add(date + (format.contains("/") ? "/" : " ") + slot.getYear());
			}
			if (slot.toLocalDate().equals(today().plusDays(1))) dates.addAll(List.of("tomorrow", "bukas"));
			String weekday = alternatives(List.of(slot.getDayOfWeek().name().toLowerCase(Locale.ROOT),
					DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).format(slot).toLowerCase(Locale.ROOT)));
			String date = "(?:" + weekday + "|(?:" + weekday + " )?" + alternatives(dates) + ")";
			List<String> times = new ArrayList<>();
			for (String format : List.of("HH:mm", "H:mm", "h:mma")) {
				times.add(DateTimeFormatter.ofPattern(format, Locale.ENGLISH).format(slot).toLowerCase(Locale.ROOT));
			}
			if (slot.getMinute() == 0) times.add(DateTimeFormatter.ofPattern("ha", Locale.ENGLISH).format(slot).toLowerCase(Locale.ROOT));
			String explicitTime = alternatives(times);
			if (slot.getMinute() == 0) {
				times.add(Integer.toString(slot.getHour()));
				times.add(DateTimeFormatter.ofPattern("h", Locale.ENGLISH).format(slot));
			}
			if (value.matches(date + "(?: (?:at |sa |ng )?" + alternatives(times) + ")?|" + explicitTime)) matches.add(entry.getKey());
		}
		return matches.size() == 1 ? matches.getFirst() : null;
	}

	private String alternatives(List<String> values) {
		return "(?:" + String.join("|", values.stream().distinct().map(Pattern::quote).toList()) + ")";
	}

	private String fallback(Session session, Map<String, Object> fields) {
		if (session.stage.equals("CONFIRMED")) return chatUnavailable(session);
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (session.stage.equals("SLOTS_OFFERED")) {
			String options = String.join("; ", session.slots.values().stream().map(this::label).toList());
			return session.language.equals("tl")
					? "Aling araw at oras po ang gusto ninyo? " + options + "."
					: "Which day and time works for you? " + options + ".";
		}
		if (session.stage.equals("OPENER_SENT")) {
			session.stage = "SERVICE_OFFERED";
			return render(pmsOffer(), session, fields);
		}
		return render("UNCLEAR", session, fields);
	}

	private String noSlots(Session session) {
		return session.language.equals("tl") ? "Wala pong available na oras sa renewal period ninyo. Kontakin po ang Toyota Manila Bay para sa ibang petsa."
				: "There are no available times within your renewal period. Please contact Toyota Manila Bay for other dates.";
	}

	private String render(String id, Session session, Map<String, Object> fields) { return render(id, session.language, fields); }
	private String render(String id, String language, Map<String, Object> fields) {
		String text = templates.get(id).path(language).asString();
		if ((id.startsWith("INSURANCE_") && id.contains("PRICE") || id.startsWith("INSURANCE_OFFER"))
				&& !insurancePrices.path("price_range").path("low").isNumber()) {
			text = templates.get(id).path("fallback_" + language).asString(text);
		}
		if (id.equals("PMS_PRICE_INFO") && (customer.path("enrichment").path("pms_price").isNull()
				|| customer.path("enrichment").path("pms_price").isMissingNode())) {
			text = templates.get(id).path("fallback_" + language).asString(text);
		}
		for (var field : fields.entrySet()) text = text.replace("{{" + field.getKey() + "}}", field.getValue().toString());
		if (text.contains("{{")) return language.equals("tl") ? "Kukumpirmahin po ng TMBG advisor ang detalye."
				: "A TMBG advisor can confirm that detail po.";
		return language.equals("en") ? fullEnglish(text) : text;
	}

	private String fullEnglish(String text) {
		return text.replaceAll("(?i)\\b(?:po|opo)\\b", "")
				.replaceAll(" +([,.!?])", "$1").replaceAll(" {2,}", " ").trim();
	}

	private String bookingChangesUnavailable(Session session) {
		return session.language.equals("tl")
				? "Hindi po maaaring baguhin o i-cancel ang booking sa chat sa ngayon. Kontakin po ang Toyota Manila Bay nang direkta para sa tulong."
				: "Bookings cannot be changed or cancelled through this chat at this time. Please contact Toyota Manila Bay directly for help.";
	}

	private String chatUnavailable(Session session) {
		return session.language.equals("tl")
				? "General service questions lang po ang kayang sagutin ng chat sa ngayon. Para sa ibang tulong, kontakin po ang Toyota Manila Bay nang direkta."
				: "This chat can only answer general service questions right now. Please contact Toyota Manila Bay directly for further help.";
	}

	private boolean validSms(String text) {
		// All supplied templates use ordinary GSM-7 punctuation. Reject extended characters and Unicode.
		return text != null && !text.isBlank() && text.length() <= 306
				&& !INTERNAL_LABEL.matcher(text).find()
				&& text.chars().allMatch(c -> c >= 32 && c <= 126 && "[]{}^|~`\\".indexOf(c) < 0);
	}
	private List<Map<String, String>> slotList(Map<String, String> slots) {
		return slots.values().stream().map(slot -> Map.of("datetime", slot, "label", label(slot))).toList();
	}
	private String label(String slot) { return SLOT_LABEL.format(LocalDateTime.parse(slot)); }
	private LocalDate today() { return LocalDate.now(clock.withZone(MANILA)); }
	private String phone(String channel, String participant) { return channel.equals("SMS") ? participant : customerPhone(); }
	private String channelText(String text, String channel) {
		String channelName = channel.equals("WHATSAPP") ? "WhatsApp" : "Messenger";
		return channel.equals("SMS") ? text : text.replace("CUSTOMER SMS", "CUSTOMER MESSAGE").replace("SMS", channelName);
	}
	private String read(ResourceLoader resources, String name) throws IOException {
		try (var stream = resources.getResource("classpath:sms/tmbg/" + name).getInputStream()) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
		}
	}
	private static final class Session {
		private final String channel;
		private String stage = "OPENER_SENT";
		private String registration = "unknown";
		private String language = "en";
		private Map<String, String> slots = new LinkedHashMap<>();
		private int slotOffset;
		private List<String> services = List.of();
		private final Map<String, String> serviceStatus = new LinkedHashMap<>(Map.of("pms", "not_offered", "insurance", "not_offered", "renewal", "not_offered"));
		private String focus = "pms";
		private boolean suppressed;
		private final List<Map<String, String>> history = new ArrayList<>();
		private final Map<String, String> replies = new LinkedHashMap<>();
		private Session(String channel) { this.channel = channel; serviceStatus.put("pms", "offered"); }
	}
	private record ConversationKey(String channel, String accountId, String participant) { }
	private record Incoming(String channel, String accountId, String sender, String id, String content) { }
}
