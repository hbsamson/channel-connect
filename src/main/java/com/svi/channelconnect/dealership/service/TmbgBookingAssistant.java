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
	private static final Set<String> SERVICES = Set.of("registration", "ctpl", "pms", "comprehensive");
	private static final int SLOT_CHOICES = 3;
	private static final Set<String> STAGES = Set.of("OPENER_SENT", "REG_STATUS_ASKED", "REG_STATUS_KNOWN", "OFFER_MADE", "SLOTS_OFFERED");
	private static final Pattern FALSE_CONFIRMATION = Pattern.compile("(?i)\\b(confirmed|booked|TMB-\\d+)\\b");
	private static final String DEMO_RULES = """

		DEMO OVERRIDES
		This is a booking demo using a fictional customer. A saved booking ends booking operations, but general dealership questions remain available.
		Other outcomes do not close the conversation. Already-renewed customers may continue with PMS or insurance.
		No callbacks, pickups or human transfers are implemented. Do not promise those actions; say they are unavailable in this demo and return to the registration or booking question.
		Never set CONFIRMED. Only the service saves a booking and sends its reference.
		Choosing an offered date, day or time that identifies one slot confirms the customer's intent to book; letters are optional shortcuts and no extra yes is needed.
		Map a clear date/time selection to its slot_code internally and request booking_requested. Never require the customer to type a letter.
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
	private final TmbgKnowledgeBase knowledge;

	public TmbgBookingAssistant(AiClient client, AiProperties ai, ObjectMapper json, ResourceLoader resources, Clock clock,
			@Value("${sms.tmbg-bookings-file:bookings.txt}") String bookingsFile) {
		this.client = client; this.ai = ai; this.json = json; this.clock = clock;
		this.bookingsFile = Path.of(bookingsFile);
		try {
			knowledge = new TmbgKnowledgeBase(resources, json);
			rules = read(resources, "rules_3in1.txt");
			instructions = read(resources, "system-prompt.md") + DEMO_RULES;
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
			String greeting = start(channel, account, participant);
			session = sessions.get(key);
			session.history.addFirst(Map.of("direction", "INBOUND", "content", content));
			session.replies.put(receiptId, greeting);
			return greeting;
		}
		String cached = session.replies.get(receiptId);
		if (cached != null) return cached;
		session.language = content.toLowerCase(Locale.ROOT).matches(
				"(?s).*\\b(po|opo|hindi|sige|oo|salamat|magkano|ano|anong|gusto|pwede|puwede|paki|ako|ko|kami|namin|ninyo|niyo|yung|iyong|sa|ng|na|lang|bukas|kailan|paano|wala|meron|ayoko|huwag|wag)\\b.*") ? "tl" : "en";
		String previousRegistration = session.registration;
		List<String> previousServices = session.services;
		if (!session.stage.equals("CONFIRMED")) registrationStatus(session, content);
		Map<String, Object> fields = fields(session, phone(channel, participant));
		String reply;
		try {
			if (session.stage.equals("SLOTS_OFFERED") && content.toLowerCase(Locale.ROOT).trim().matches(
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
		return "RULES\n" + channelText(rules + DEMO_RULES, session.channel) + "\nCUSTOMER RECORD\n" + json.writeValueAsString(customer)
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
		if (outcome.equals("unavailable")) return chatUnavailable(session);
		for (JsonNode flag : answer.path("flags")) {
			if (flag.asString().equals("expired_registration")) session.registration = "expired";
		}
		if (session.stage.equals("REG_STATUS_ASKED") && session.registration.equals("unknown")) {
			if (template.equals("REG_ALREADY_RENEWED")) session.registration = "renewed";
			if (template.equals("OFFER_WINDOW_OPEN") || template.equals("OFFER_WINDOW_UPCOMING")) session.registration = "not_yet";
			if (session.registration.equals("not_yet") && today().isAfter(LocalDate.parse(customer.path("reg_window_end").asString()))) session.registration = "expired";
		}
		if (outcome.equals("booking_requested")) return book(session, incoming, answer, fields);
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (answer.path("needs_human").asBoolean(false) || Set.of("handoff", "callback_requested", "collection_booked").contains(outcome)
				|| Set.of("HANDOFF", "CALLBACK_ASK", "CALLBACK_CONFIRM", "EXPIRED_COLLECTION").contains(template)) {
			return session.language.equals("tl") ? "Wala pong callback o advisor transfer sa demo. Gusto po ba ninyong mag-book ng visit?"
					: "Callbacks and advisor transfers are unavailable in this demo. Would you like to book a visit po?";
		}
		if (!template.isEmpty() && (!templates.containsKey(template) || !templates.get(template).path("used_by").asString().equals("assistant"))) return fallback(session, fields);
		if (!answer.path("reply_text").isString()) return fallback(session, fields);
		String text = answer.path("reply_text").asString();
		if (!validSms(text) || FALSE_CONFIRMATION.matcher(text).find()) return fallback(session, fields);
		String stage = answer.path("state_after").asString();
		if (!session.registration.equals("renewed") && !session.registration.equals("not_yet")) {
			if (Set.of("OFFER_MADE", "SLOTS_OFFERED", "SLOT_CHOSEN").contains(stage)
					|| Set.of("SLOTS_OFFER", "OFFER_WINDOW_OPEN", "OFFER_WINDOW_UPCOMING").contains(template)) return fallback(session, fields);
		}
		if (template.equals("REG_STATUS_ASK")) stage = "REG_STATUS_ASKED";
		if (template.equals("REG_ALREADY_RENEWED")) stage = "OFFER_MADE";
		if (template.equals("SLOTS_OFFER") || stage.equals("SLOTS_OFFERED")) {
			if (availableSlots().isEmpty()) return noSlots(session);
			session.slots = slotChoices(session);
			stage = "SLOTS_OFFERED";
			// Keep the service's exact menu so later slot letters cannot refer to invented times.
			text = render("SLOTS_OFFER", session, fields(session, phone(incoming.channel(), incoming.sender())));
		}
		if (STAGES.contains(stage)) session.stage = stage;
		return text;
	}

	private String book(Session session, Incoming incoming, JsonNode answer, Map<String, Object> fields) {
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		String code = answer.path("slot_code").asString("");
		if (!session.stage.equals("SLOTS_OFFERED") || session.registration.equals("unknown")
				|| !code.equals(chosenSlot(session, incoming.content())) || !session.slots.containsKey(code)) return fallback(session, fields);
		String selected = session.slots.get(code);
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
		if (session.services.equals(List.of("pms"))) services = new ArrayList<>(session.services);
		if (services.contains("comprehensive") && !session.services.contains("comprehensive")) return fallback(session, fields);
		if (services.contains("registration") && !services.contains("ctpl")) return fallback(session, fields);
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
		fields.put("booking_ref", reference); fields.put("slot_selected", label(selected)); fields.put("services_list", String.join(", ", services));
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
		String topic = knowledge.mockTopic(text);
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
			answer.put("lang", session.language); answer.put("outcome", "booking_requested"); answer.put("slot_code", code);
			answer.put("reply_text", null); answer.put("outcome_data", Map.of("services", session.services));
			return json.valueToTree(answer);
		} else if (normalized.matches("(?s).*(price|cost|magkano|fee).*")) template = "FEES";
		else if (normalized.contains("ctpl")) template = "CTPL_INFO";
		else if (session.registration.equals("unknown")) { template = "REG_STATUS_ASK"; stage = "REG_STATUS_ASKED"; }
		else if (session.stage.equals("OPENER_SENT") || session.stage.equals("REG_STATUS_ASKED")) {
			template = session.registration.equals("renewed") ? "REG_ALREADY_RENEWED"
					: fields.get("window_status").equals("upcoming") ? "OFFER_WINDOW_UPCOMING" : "OFFER_WINDOW_OPEN";
			stage = "OFFER_MADE";
		} else { template = "SLOTS_OFFER"; stage = "SLOTS_OFFERED"; }
		answer.put("lang", session.language); answer.put("template_id", template); answer.put("state_after", stage);
		answer.put("reply_text", render(template, session, fields));
		return json.valueToTree(answer);
	}

	private void registrationStatus(Session session, String text) {
		String value = text.toLowerCase(Locale.ROOT).trim();
		if (value.matches("(?s).*(expired|lapsed|window has passed|since june).*")) session.registration = "expired";
		else if (value.matches("(?s).*(not sure|unsure|hindi ko alam|di ko sure|nakalimutan).*")) session.registration = "unknown";
		else if (value.matches("(?s).*(not yet|not renewed|haven't renewed|have not renewed|hindi.*pa.*renew|di.*pa.*renew|hindi pa|di pa|wala pa).*")) session.registration = "not_yet";
		else if (value.matches("(?s).*(already renewed|na-renew ko na|narenew ko na|na-renew na|narenew na|tapos na|done na|last month pa).*")
				|| value.matches("(renewed|na-renew|narenew)( na)?( po)?[.! ]*")) session.registration = "renewed";
		else if (session.stage.equals("REG_STATUS_ASKED")) {
			if (value.matches("(1|yes|yes po|oo|opo|oo po)[.! ]*")) session.registration = "renewed";
			if (value.equals("2")) session.registration = "not_yet";
		}
		if (session.registration.equals("not_yet") && today().isAfter(LocalDate.parse(customer.path("reg_window_end").asString()))) session.registration = "expired";
		if (value.matches("(?s).*(pms only|pms lang|just.*pms).*")) session.services = List.of("pms");
		if (value.matches("(?s).*(include|add|kasama).*(comprehensive).*")) session.services = List.of("registration", "ctpl", "pms", "comprehensive");
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
					: name.equals("pms_price") || name.equals("reg_service_fee") ? "PHP " + value.asString() : value.asString());
		}
		Map<String, String> slots = session.stage.equals("SLOTS_OFFERED") ? session.slots : slotChoices(session);
		fields.put("slots_text", slots.entrySet().stream().map(entry -> entry.getKey() + "=" + label(entry.getValue())).reduce((a, b) -> a + "; " + b).orElse("no slots available"));
		fields.put("slot_codes", String.join("/", slots.keySet()));
		return fields;
	}

	private Map<String, String> availableSlots() {
		Map<String, String> slots = new LinkedHashMap<>();
		LocalDate start = LocalDate.parse(customer.path("reg_window_start").asString());
		LocalDate end = LocalDate.parse(customer.path("reg_window_end").asString());
		for (JsonNode value : customer.path("enrichment").path("slots")) {
			String slot = value.asString();
			LocalDate date = LocalDateTime.parse(slot).toLocalDate();
			if (!date.isAfter(today()) || date.isBefore(start) || date.isAfter(end)
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
		List<String> matches = new ArrayList<>();
		for (var entry : session.slots.entrySet()) {
			LocalDateTime slot = LocalDateTime.parse(entry.getValue());
			String code = "(?:(?:option|slot|yung|letter) )?" + entry.getKey().toLowerCase(Locale.ROOT);
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
			if (value.matches(code + "|" + date + "(?: (?:at |sa |ng )?" + alternatives(times) + ")?|" + explicitTime)) matches.add(entry.getKey());
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
			String options = String.join("; ", session.slots.entrySet().stream().map(entry -> entry.getKey() + "=" + label(entry.getValue())).toList());
			String codes = String.join("/", session.slots.keySet());
			return session.language.equals("tl")
					? "Aling petsa/oras po ang gusto ninyo? " + options + ". Sabihin ang petsa/oras o " + codes + "."
					: "Which date/time would you like po? " + options + ". Reply with a date/time or " + codes + ".";
		}
		if (session.registration.equals("unknown")) { session.stage = "REG_STATUS_ASKED"; return render("REG_STATUS_ASK", session, fields); }
		return session.language.equals("tl") ? "Kukumpirmahin po ng TMBG advisor ang detalye. Gusto po ba ninyong mag-book ng visit?"
				: "A TMBG advisor can confirm that detail po. Would you like to book a visit?";
	}

	private String noSlots(Session session) {
		return session.language.equals("tl") ? "Wala pong available na slot sa registration window sa demo. Kukumpirmahin ng TMBG advisor ang ibang petsa."
				: "There are no available demo slots within your registration window po. A TMBG advisor can confirm other dates.";
	}

	private String render(String id, Session session, Map<String, Object> fields) { return render(id, session.language, fields); }
	private String render(String id, String language, Map<String, Object> fields) {
		String text = templates.get(id).path(language).asString();
		// These actions are deliberately absent from the demo.
		text = text.replace(" Need other dates? Reply AGENT.", "")
				.replace(" Iba pong petsa? Reply AGENT.", "")
				.replace(" To change it, reply AGENT.", " Demo booking only.")
				.replace(" Para magpalit, reply AGENT.", " Demo booking lamang po.")
				.replace(" Reply AGENT to talk to a person.", "")
				.replace(" Reply AGENT para makausap ang tao.", "");
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
				&& text.chars().allMatch(c -> c >= 32 && c <= 126 && "[]{}^|~`\\".indexOf(c) < 0);
	}
	private List<Map<String, String>> slotList(Map<String, String> slots) {
		return slots.entrySet().stream().map(entry -> Map.of("code", entry.getKey(), "datetime", entry.getValue(), "label", label(entry.getValue()))).toList();
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
		private List<String> services = List.of("registration", "ctpl", "pms");
		private final List<Map<String, String>> history = new ArrayList<>();
		private final Map<String, String> replies = new LinkedHashMap<>();
		private Session(String channel) { this.channel = channel; }
	}
	private record ConversationKey(String channel, String accountId, String participant) { }
	private record Incoming(String channel, String accountId, String sender, String id, String content) { }
}
