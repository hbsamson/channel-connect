package com.svi.channelconnect.sms.service;

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
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.sms.model.SmsInbound;
import com.svi.channelconnect.sms.model.SmsSubmission;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;

/** Single-process SMS campaign demo. The model has no booking or transport access. */
@Service
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
public class SmsTmbgDemoService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SmsTmbgDemoService.class);
	private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
	private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
	private static final DateTimeFormatter SLOT_LABEL = DateTimeFormatter.ofPattern("EEE MMM d h:mma", Locale.ENGLISH);
	private static final Set<String> SERVICES = Set.of("registration", "ctpl", "pms", "comprehensive");
	private static final Set<String> STAGES = Set.of("OPENER_SENT", "REG_STATUS_ASKED", "REG_STATUS_KNOWN", "OFFER_MADE", "SLOTS_OFFERED", "SLOT_CHOSEN");
	private static final Pattern FALSE_CONFIRMATION = Pattern.compile("(?i)\\b(confirmed|booked|TMB-\\d+)\\b");
	private static final String DEMO_RULES = """

		DEMO OVERRIDES
		This is an SMS booking demo using a fictional customer. Only a saved booking ends the demo.
		Other outcomes do not close the conversation. Already-renewed customers may continue with PMS or insurance.
		No callbacks, pickups or human transfers are implemented. Do not promise those actions; say they are unavailable in this demo and return to the registration or booking question.
		Never set CONFIRMED. Only the service saves a booking and sends its reference.
		""";

	private final AiClient client;
	private final AiProperties ai;
	private final ObjectMapper json;
	private final SmsService sms;
	private final SmsMessageRepository messages;
	private final Clock clock;
	private final Path bookingsFile;
	private final JsonNode customer;
	private final Map<String, JsonNode> templates = new LinkedHashMap<>();
	private final Map<String, Session> sessions = new LinkedHashMap<>();
	private final Map<String, String> replies = new LinkedHashMap<>();
	private final List<JsonNode> bookings = new ArrayList<>();
	private final String rules;
	private final String instructions;

	public SmsTmbgDemoService(AiClient client, AiProperties ai, ObjectMapper json, ResourceLoader resources,
			SmsService sms, SmsMessageRepository messages, Clock clock,
			@Value("${sms.tmbg-bookings-file:bookings.txt}") String bookingsFile) {
		this.client = client; this.ai = ai; this.json = json; this.sms = sms; this.messages = messages; this.clock = clock;
		this.bookingsFile = Path.of(bookingsFile);
		try {
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
			throw new IllegalStateException("Cannot load SMS demo resources or bookings file", exception);
		}
	}

	public synchronized SmsSubmission start(String account, String recipient) {
		String phone = SmsValidation.phone(recipient == null || recipient.isBlank() ? customer.path("phone").asString() : recipient);
		Session session = new Session();
		String greeting = render("OPEN_GREETING", session, fields(session, phone));
		SmsSubmission submission = sms.submit("tmbg-open:" + UUID.randomUUID(), account, phone, greeting);
		session.history.add(Map.of("direction", "OUTBOUND", "content", greeting));
		sessions.put(key(account, phone), session);
		return submission;
	}

	public synchronized boolean accepts(String account, String sender) {
		return sessions.containsKey(key(account, sender));
	}

	public boolean handles(SmsInbound incoming) {
		return incoming.status().equals("PROCESSING") && accepts(incoming.accountId(), incoming.sender());
	}

	public synchronized void process(SmsInbound incoming) {
		if (!handles(incoming)) return;
		Session session = sessions.computeIfAbsent(key(incoming.accountId(), incoming.sender()), ignored -> new Session());
		String reply = replies.get(incoming.id());
		if (reply == null) {
			if (session.stage.equals("CONFIRMED")) { messages.completeInbound(incoming.id()); return; }
			registrationStatus(session, incoming.content());
			Map<String, Object> fields = fields(session, incoming.sender());
			JsonNode answer;
			try {
				answer = ai.provider() == AiProperties.Provider.MOCK ? mockAnswer(session, incoming.content(), fields)
						: json.readTree(client.generateResponse(new AiRequest(incoming.id(), context(session, incoming.content(), fields), instructions, true)).text());
				reply = apply(session, incoming, answer, fields);
			} catch (RuntimeException exception) {
				LOGGER.warn("SMS demo reply unavailable; using prepared template");
				reply = fallback(session, fields);
			}
			replies.put(incoming.id(), reply);
			session.history.add(Map.of("direction", "INBOUND", "content", incoming.content()));
			session.history.add(Map.of("direction", "OUTBOUND", "content", reply));
		}
		sms.validate(incoming.accountId(), incoming.sender(), reply);
		messages.enqueue("reply:" + incoming.id(), incoming.accountId(), incoming.sender(), reply, null);
		messages.completeInbound(incoming.id());
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
		return "RULES\n" + rules + DEMO_RULES + "\nCUSTOMER RECORD\n" + json.writeValueAsString(customer)
				+ "\nDERIVED FIELDS\n" + json.writeValueAsString(fields)
				+ "\nCONVERSATION STATE\n" + json.writeValueAsString(state)
				+ "\nTEMPLATES\n" + json.writeValueAsString(filled) + "\nCUSTOMER SMS\n" + json.writeValueAsString(text);
	}

	private String apply(Session session, SmsInbound incoming, JsonNode answer, Map<String, Object> fields) {
		if (answer == null || !answer.isObject() || !Set.of("en", "tl").contains(answer.path("lang").asString())) return fallback(session, fields);
		session.language = answer.path("lang").asString();
		String template = answer.path("template_id").asString("");
		String outcome = answer.path("outcome").asString("");
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
			session.slots = availableSlots();
			stage = "SLOTS_OFFERED";
			// Keep the service's exact menu so later slot letters cannot refer to invented times.
			text = render("SLOTS_OFFER", session, fields);
		}
		if (STAGES.contains(stage)) session.stage = stage;
		return text;
	}

	private String book(Session session, SmsInbound incoming, JsonNode answer, Map<String, Object> fields) {
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		String code = answer.path("slot_code").asString("");
		if (!session.stage.equals("SLOTS_OFFERED") || session.registration.equals("unknown")
				|| !code.equals(chosenSlot(session, incoming.content())) || !session.slots.containsKey(code)) return fallback(session, fields);
		String selected = session.slots.get(code);
		if (!availableSlots().containsValue(selected)) {
			session.slots = availableSlots();
			if (session.slots.isEmpty()) return noSlots(session);
			return render("SLOT_TAKEN", session, fields(session, incoming.sender()));
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
		booking.put("account_id", incoming.accountId()); booking.put("phone", incoming.sender());
		booking.put("slot_code", code); booking.put("appointment", selected); booking.put("services", services);
		booking.put("created_at", clock.instant().atZone(MANILA).toOffsetDateTime().toString());
		fields.put("booking_ref", reference); fields.put("slot_selected", label(selected)); fields.put("services_list", String.join(", ", services));
		String confirmation = render("BOOKING_CONFIRMED", session, fields);
		if (!validSms(confirmation)) return render("BOOKING_ERROR", session, fields);
		try {
			Files.writeString(bookingsFile, json.writeValueAsString(booking) + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			LOGGER.warn("SMS demo booking file could not be written");
			return render("BOOKING_ERROR", session, fields);
		}
		bookings.add(json.valueToTree(booking));
		session.stage = "CONFIRMED";
		return confirmation;
	}

	private JsonNode mockAnswer(Session session, String text, Map<String, Object> fields) {
		String normalized = text.toLowerCase(Locale.ROOT);
		if (normalized.matches("(?s).*\\b(po|opo|hindi|sige|oo|salamat|magkano|ano|gusto)\\b.*")) session.language = "tl";
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
		Map<String, String> slots = availableSlots();
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
			if (slots.size() == 3) break;
		}
		return slots;
	}

	private String chosenSlot(Session session, String text) {
		String value = text.toLowerCase(Locale.ROOT).trim().replaceAll("[.!?]$", "");
		var code = Pattern.compile("^(?:(?:option|slot|yung|letter)\\s+)?([abc])(?:\\s+po)?$").matcher(value);
		if (code.matches()) return code.group(1).toUpperCase(Locale.ROOT);
		List<String> matches = new ArrayList<>();
		for (var entry : session.slots.entrySet()) {
			LocalDateTime slot = LocalDateTime.parse(entry.getValue());
			boolean date = value.contains(slot.toLocalDate().toString()) || value.contains(SHORT_DATE.format(slot).toLowerCase(Locale.ROOT))
					|| value.contains(slot.getDayOfWeek().name().toLowerCase(Locale.ROOT));
			String time = DateTimeFormatter.ofPattern("ha", Locale.ENGLISH).format(slot).toLowerCase(Locale.ROOT);
			boolean hour = value.replace(" ", "").contains(time) || value.contains(slot.toLocalTime().toString());
			if (date || hour) matches.add(entry.getKey());
		}
		return matches.size() == 1 ? matches.getFirst() : null;
	}

	private String fallback(Session session, Map<String, Object> fields) {
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (session.stage.equals("SLOTS_OFFERED")) return session.language.equals("tl")
				? "Aling slot po ang gusto ninyo? Reply " + String.join("/", session.slots.keySet()) + "."
				: "Which slot would you like po? Reply " + String.join("/", session.slots.keySet()) + ".";
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
		return text;
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
	private String key(String account, String phone) { return account + ":" + phone; }
	private String read(ResourceLoader resources, String name) throws IOException {
		try (var stream = resources.getResource("classpath:sms/tmbg/" + name).getInputStream()) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
		}
	}
	private static final class Session {
		private String stage = "OPENER_SENT";
		private String registration = "unknown";
		private String language = "tl";
		private Map<String, String> slots = new LinkedHashMap<>();
		private List<String> services = List.of("registration", "ctpl", "pms");
		private final List<Map<String, String>> history = new ArrayList<>();
	}
}
