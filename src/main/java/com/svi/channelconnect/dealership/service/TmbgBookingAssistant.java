package com.svi.channelconnect.dealership.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
	private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("EEE MMM d yyyy", Locale.ENGLISH);
	private static final List<String> SERVICES = List.of("renewal", "pms", "insurance");
	private static final Set<String> DECISIONS = Set.of("accepted", "declined", "skipped");
	private static final Pattern FALSE_CONFIRMATION = Pattern.compile("(?i)\\b(confirmed|booked|TMB-\\d+)\\b");
	private static final Pattern INTERNAL_LABEL = Pattern.compile("(?i)\\bdemo\\b");
	private static final Map<String, Pattern> CONVERSATIONAL_OPENERS = conversationalOpeners();
	private static final String CAPABILITY_RULES = """

		CHAT CAPABILITIES (take precedence over suggested actions in templates or reference material)
		A saved booking ends booking operations, but general dealership questions remain available.
		Short social replies are allowed at every stage. Greet naturally, thank briefly and acknowledge the customer's words before returning to the current question.
		The service handles recognized leading greetings, thanks and wellbeing questions; do not repeat them when the latest message contains only the remaining request.
		Greetings, gratitude and small talk are never service acceptance, refusal or date selection. Pair social-only replies with a relevant next step: resume the current question after a greeting, invite confirmed customers to visit, and invite further car questions after thanks or a completed conversation. Never restart completed offers.
		Already-renewed customers may continue with maintenance or insurance. Never default to registration or add unaccepted services.
		No callbacks, pickups, quote delivery or human transfers are implemented. Do not promise those actions.
		For a person, callback or unavailable action, politely ask the customer to contact Toyota Manila Bay directly. Do not follow that with another sales question.
		Never use the word demo in customer-facing replies. Do not describe a locally recorded visit as a live dealership reservation.
		Never set CONFIRMED. Only the service saves a booking and sends its reference.
		Always offer registration renewal first, regular maintenance second, and car insurance third, even after earlier refusals.
		The service owns the order and records only the decision for the currently offered service. Never mark an unasked service accepted, declined or skipped.
		A yes accepts the current service; a no declines only that service. Already completed means skipped. Continue to the next service after every decision.
		Do not stop after two declines or after one acceptance. Do not automatically skip insurance because the record says active.
		Never request booking or dates until all_services_answered is true. If no services were accepted, close without booking.
		Only after all three decisions, a clear date selection inside booking_window_start_iso through booking_window_end_iso requests booking_requested.
		Set slot to the selected ISO date (YYYY-MM-DD), without a time. There are no configured appointment hours or capacity guarantees.
		Availability questions, refusals, tentative dates and ambiguous choices are not selections. A clear request such as "Can you book me for October 19?" is a selection.
		Present the inclusive date window, not a fixed menu of appointment times. Ask which day works. Never show internal codes or numbered reply instructions.
		GENERAL DEALERSHIP QUESTIONS: Use APPROVED DEALERSHIP KNOWLEDGE to answer questions about Toyota Manila Bay and its services at any stage.
		For a general question answered by that knowledge, set knowledge_topic to its exact key and reply_text to null; the service supplies the approved answer.
		Only select a knowledge_topic if its answer actually addresses the latest question. Do not treat unrelated questions as dealership questions or repeat a sales pitch for missing facts.
		Do not select a topic for booking commands, consent, opt-out, appointment changes, or vehicle-specific facts not covered by that answer. Set knowledge_topic to null otherwise.
		AFTER CONFIRMED: Never restart booking, offer new slots, cancel, reschedule or claim changes were saved. For changes set outcome to booking_change; otherwise unsupported questions use outcome unavailable. General knowledge questions still use knowledge_topic.
		When the customer asks for other dates while SLOTS_OFFERED, set outcome more_dates; the service repeats the current date window. Dates outside that window cannot be saved here.
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
			if (!optOut(conversationalMessage(content).message())) {
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
				"(?s).*\\b(po|opo|hindi|sige|oo|salamat|magkano|ano|anong|gusto|pwede|puwede|paki|ako|ko|kami|namin|ninyo|niyo|yung|iyong|sa|ng|na|lang|bukas|kailan|paano|wala|meron|ayoko|huwag|wag|kumusta|kamusta|magandang)\\b.*") ? "tl" : "en";
		ConversationalMessage conversational = conversationalMessage(content);
		String message = conversational.message();
		String previousRegistration = session.registration;
		List<String> previousServices = session.services;
		if (!session.stage.equals("CONFIRMED") && !message.isBlank()) registrationStatus(session, message);
		Map<String, Object> fields = fields(session, phone(channel, participant));
		String reply;
		try {
			if (optOut(message)) {
				session.suppressed = true;
				reply = render("OPTOUT_CONFIRM", session, fields);
			} else if (message.toLowerCase(Locale.ROOT).matches("(?s).*\\b(wrong number|not the owner|hindi ako si|maling number)\\b.*")) {
				session.suppressed = true;
				reply = render("ID_WRONG_PERSON", session, fields);
			} else if (!conversational.templateIds().isEmpty() && message.isBlank()) {
				reply = conversationalFollowup(session, conversational, fields);
			} else if (session.stage.equals("SLOTS_OFFERED") && message.toLowerCase(Locale.ROOT).trim().matches(
					"(?s)(?:more|next)[.!? ]*|.*\\b(?:(?:more|other|different|next|later|another) (?:dates?|slots?|options?|times?)|ibang (?:petsa|araw|schedule)|iba pa)\\b.*")) {
				reply = moreDates(session, phone(channel, participant));
			} else if (session.stage.equals("CONFIRMED") && knowledge.bookingChange(message)) {
				reply = bookingChangesUnavailable(session);
			} else {
				JsonNode answer = ai.provider() == AiProperties.Provider.MOCK ? mockAnswer(session, message, fields)
						: json.readTree(client.generateResponse(new AiRequest(receiptId, context(session, message, fields), channelText(instructions, channel), true)).text());
				if (answer != null && (knowledge.answer(answer.path("knowledge_topic").asString(""), session.language, channel) != null
						|| answer.path("outcome").asString().equals("unavailable"))) {
					session.registration = previousRegistration;
					session.services = previousServices;
				}
				reply = apply(session, new Incoming(channel, account, participant, receiptId, message), answer, fields);
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("Booking demo reply unavailable; using prepared template");
			reply = fallback(session, fields);
		}
		if (!session.suppressed && !conversational.templateIds().isEmpty()) {
			List<String> acknowledgements = new ArrayList<>();
			for (String id : conversational.templateIds()) acknowledgements.add(render(id, session, fields));
			String acknowledgement = String.join(" ", acknowledgements);
			if (validSms(acknowledgement + " " + reply)) reply = acknowledgement + (reply.isBlank() ? "" : " " + reply);
		}
		if (session.language.equals("en")) reply = fullEnglish(reply);
		session.replies.put(receiptId, reply);
		session.history.add(Map.of("direction", "INBOUND", "content", content));
		session.history.add(Map.of("direction", "OUTBOUND", "content", reply));
		return reply;
	}

	private static Map<String, Pattern> conversationalOpeners() {
		Map<String, Pattern> patterns = new LinkedHashMap<>();
		String end = "(?=$|[\\s,.!?;:])[,!?.;:\\s]*";
		patterns.put("GREETING_ACK", Pattern.compile("(?i)^(?:(?:hi|hello|hey)(?: there)?|good (?:morning|afternoon|evening|day)|magandang (?:umaga|hapon|gabi))(?: po)?" + end));
		patterns.put("THANKS_ACK", Pattern.compile("(?i)^(?:thanks(?: a lot| so much)?|thank you(?: so much)?|(?:maraming )?salamat)(?: po)?(?: (?:for (?:your help|the help|explaining|the information)|sa (?:tulong|info)))?" + end));
		patterns.put("COURTESY_ACK", Pattern.compile("(?i)^(?:you(?:'|\u2019)?re welcome|you are welcome|walang anuman)(?: po)?" + end));
		patterns.put("WELLBEING_ACK", Pattern.compile("(?i)^(?:how are you(?: doing)?(?: today)?|(?:kumusta|kamusta)(?: po)?(?: (?:ka|kayo))?)(?: po)?(?=$|[,.!?;:])[,!?.;:\\s]*"));
		return patterns;
	}

	private ConversationalMessage conversationalMessage(String content) {
		List<String> acknowledgements = new ArrayList<>();
		String message = content.trim();
		// Consume a short social opener without losing a following question, decision or date.
		for (int count = 0; count < 2; count++) {
			boolean matched = false;
			for (var entry : CONVERSATIONAL_OPENERS.entrySet()) {
				var matcher = entry.getValue().matcher(message);
				if (!matcher.find()) continue;
				if (!acknowledgements.contains(entry.getKey())) acknowledgements.add(entry.getKey());
				message = message.substring(matcher.end()).trim();
				matched = true;
				break;
			}
			if (!matched) break;
		}
		return new ConversationalMessage(acknowledgements, message);
	}

	private String conversationalFollowup(Session session, ConversationalMessage message, Map<String, Object> fields) {
		if (session.stage.equals("CONFIRMED")) return render("VISIT_INVITATION", session, fields);
		if (session.stage.equals("COMPLETED_NO_BOOKING")
				|| message.templateIds().stream().allMatch(id -> Set.of("THANKS_ACK", "COURTESY_ACK").contains(id))) {
			return render("FURTHER_HELP_INVITATION", session, fields);
		}
		if (session.focus != null && !session.stage.equals("REG_STATUS_ASKED")) return render("RESUME_SERVICE_OFFER", session, fields);
		return fallback(session, fields);
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
		state.put("services_asked", session.servicesAsked); state.put("all_services_answered", allServicesAnswered(session));
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
		if (session.stage.equals("CONFIRMED") || session.stage.equals("COMPLETED_NO_BOOKING")) return chatUnavailable(session);
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
		if (answer.path("needs_human").asBoolean(false) || Set.of("handoff", "callback_requested", "collection_booked").contains(outcome)
				|| Set.of("HANDOFF", "CALLBACK_CONFIRM", "EXPIRED_COLLECTION").contains(template)) {
			return contactDealership(session);
		}
		if (!template.isEmpty() && (!templates.containsKey(template) || !templates.get(template).path("used_by").asString().equals("assistant"))) return fallback(session, fields);
		if (updateServices(session, answer, incoming.content())) {
			return advanceServices(session, phone(incoming.channel(), incoming.sender()));
		}
		if (session.stage.equals("REG_STATUS_ASKED") && "renewal".equals(session.focus)
				&& !session.registration.equals("unknown")) {
			session.stage = "SERVICE_OFFERED";
			return render(serviceOffer(session), session, fields);
		}
		if (outcome.equals("booking_requested")) return book(session, incoming, answer, fields);
		if (template.startsWith("SLOTS_OFFER") || answer.path("state_after").asString().equals("SLOTS_OFFERED")) {
			return allServicesAnswered(session) ? offerDates(session, phone(incoming.channel(), incoming.sender())) : fallback(session, fields);
		}
		if (template.equals("DECLINE_CLOSE") || template.equals("ADDON_ASK") || template.equals("NEXT_SERVICE_LEAD_IN")) return fallback(session, fields);
		if (session.stage.equals("SLOTS_OFFERED") && session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (!answer.path("reply_text").isString()) return fallback(session, fields);
		String text = answer.path("reply_text").asString();
		if (!validSms(text) || FALSE_CONFIRMATION.matcher(text).find()) return fallback(session, fields);
		if (template.equals("RENEWAL_ASK_STATUS") || template.equals("RENEWAL_NOT_SURE") || template.equals("REG_VALID_CHECK")) {
			if (!"renewal".equals(session.focus) && !allServicesAnswered(session)) return fallback(session, fields);
			session.stage = "REG_STATUS_ASKED";
		}
		if (template.startsWith("PMS_OFFER") || template.startsWith("INSURANCE_OFFER")
				|| template.equals("INSURANCE_ASK_STATUS") || template.startsWith("RENEWAL_OFFER")) {
			return fallback(session, fields);
		}
		return text;
	}

	private String book(Session session, Incoming incoming, JsonNode answer, Map<String, Object> fields) {
		if (!allServicesAnswered(session)) return fallback(session, fields);
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		String code = chosenSlot(session, incoming.content());
		if (!session.stage.equals("SLOTS_OFFERED") || session.services.isEmpty() || code == null
				|| (session.registration.equals("unknown") && fields.get("window_status").equals("closed"))) return fallback(session, fields);
		String selected = session.slots.get(code);
		if (!selected.equals(answer.path("slot").asString())) return fallback(session, fields);
		if (!availableDates().containsValue(selected)) {
			session.slots = availableDates();
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
		booking.put("services_asked", List.copyOf(session.servicesAsked)); booking.put("all_services_answered", true);
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
		if (!topic.isEmpty() || session.stage.equals("CONFIRMED") || session.stage.equals("COMPLETED_NO_BOOKING")) {
			return json.valueToTree(Map.of("lang", session.language, "knowledge_topic", topic,
					"outcome", session.stage.equals("CONFIRMED") && knowledge.bookingChange(text) ? "booking_change" : "unavailable"));
		}
		String template;
		String stage = session.stage;
		String code = chosenSlot(session, text);
		Map<String, Object> answer = new LinkedHashMap<>();
		if (session.stage.equals("SLOTS_OFFERED") && code != null) {
			answer.put("lang", session.language); answer.put("outcome", "booking_requested"); answer.put("slot", session.slots.get(code));
			answer.put("reply_text", null); answer.put("outcome_data", Map.of("services", session.services));
			return json.valueToTree(answer);
		} else if (normalized.matches("(?s).*\\b(person|advisor|agent|tao|callback|call me)\\b.*")) {
			answer.put("lang", session.language); answer.put("outcome", "handoff");
			return json.valueToTree(answer);
		} else if (normalized.matches("(?s).*\\b(busy|not now|mamaya)\\b.*")) {
			template = "CALLBACK_ASK";
		} else if (normalized.matches("(?s).*\\b(price|cost|magkano|fees?|how much)\\b.*")) {
			template = "insurance".equals(session.focus) ? "INSURANCE_PRICE_INFO"
					: "renewal".equals(session.focus) ? "FEES" : "PMS_PRICE_INFO";
		} else if (normalized.contains("ctpl")) template = "BASIC_INSURANCE_INFO";
		else if (session.stage.equals("REG_STATUS_ASKED") && "renewal".equals(session.focus) && session.registration.equals("not_yet")) {
			template = serviceOffer(session);
		} else if (session.stage.equals("SLOTS_OFFERED")) template = "SLOT_UNAVAILABLE";
		else template = serviceOffer(session);
		answer.put("lang", session.language); answer.put("template_id", template); answer.put("state_after", stage);
		if (session.focus != null) {
			String decision = explicitDecision(session, text);
			if (decision != null) answer.put("service_status", Map.of(session.focus, decision));
		}
		answer.put("reply_text", render(template, session, fields));
		return json.valueToTree(answer);
	}

	private boolean updateServices(Session session, JsonNode answer, String content) {
		// Only an answer to the service actually asked by the server can advance the flow.
		if (session.focus == null || !session.servicesAsked.contains(session.focus)
				|| !session.serviceStatus.get(session.focus).equals("offered")) return false;
		if (Set.of("CALLBACK_ASK", "THINKING_TIME").contains(answer.path("template_id").asString(""))) return false;
		if (session.stage.equals("REG_STATUS_ASKED") && !session.registration.equals("renewed")) return false;
		String next = explicitDecision(session, content);
		if (next == null) next = answer.path("service_status").path(session.focus).asString("");
		if (!DECISIONS.contains(next)) return false;
		session.serviceStatus.put(session.focus, next);
		session.services = SERVICES.stream().filter(service -> session.serviceStatus.get(service).equals("accepted")).toList();
		return true;
	}

	private String explicitDecision(Session session, String text) {
		String value = text.toLowerCase(Locale.ROOT).trim();
		if (value.contains("?")) return null;
		if ("renewal".equals(session.focus) && session.registration.equals("renewed")) return "skipped";
		if (session.stage.equals("REG_STATUS_ASKED")) return null;
		if (value.matches("(?:yes|yes please|yes po|sure|ok|okay|sige|sige po|oo|oo po|opo|please do|go ahead)[.! ]*")) return "accepted";
		if (value.matches("(?:no|no thanks|no thank you|no po|hindi|hindi po|hindi muna|ayoko|ayoko po)[.! ]*")) return "declined";
		if ("pms".equals(session.focus) && value.matches("(?s).*(already serviced|just serviced|kaka-service|kakaservice).*")) return "skipped";
		if ("insurance".equals(session.focus) && value.matches("(?:no thanks[, ]*)?(?:i (?:already )?have (?:car )?insurance|i (?:already )?renewed (?:my )?insurance|already insured|may insurance na(?: ako)?|(?:my insurance is )?still active)[.! ]*")) return "skipped";
		return null;
	}

	private boolean allServicesAnswered(Session session) {
		return session.servicesAsked.containsAll(SERVICES)
				&& SERVICES.stream().allMatch(service -> DECISIONS.contains(session.serviceStatus.get(service)));
	}

	private String advanceServices(Session session, String phone) {
		String acknowledgementTemplate = switch (session.serviceStatus.get(session.focus)) {
			case "accepted" -> "SERVICE_ACCEPTED_ACK";
			case "declined" -> "SERVICE_DECLINED_ACK";
			case "skipped" -> "SERVICE_SKIPPED_ACK";
			default -> throw new IllegalStateException("Current service has no decision");
		};
		// Render before advancing so the acknowledgement names the service just answered.
		String acknowledgement = render(acknowledgementTemplate, session, fields(session, phone));
		String reply;
		if (!allServicesAnswered(session)) {
			String template = offerNext(session);
			reply = render(template, session, fields(session, phone));
		} else {
			session.focus = null;
			if (session.services.isEmpty()) {
				session.stage = "COMPLETED_NO_BOOKING";
				reply = render("DECLINE_CLOSE", session, fields(session, phone));
			} else {
				reply = offerDates(session, phone);
			}
		}
		return acknowledgement + " " + reply;
	}

	private String offerDates(Session session, String phone) {
		if (!allServicesAnswered(session) || session.services.isEmpty()) return fallback(session, fields(session, phone));
		Map<String, Object> fields = fields(session, phone);
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		session.slots = availableDates();
		if (session.slots.isEmpty()) return noSlots(session);
		session.stage = "SLOTS_OFFERED";
		return render("SLOTS_OFFER", session, fields(session, phone));
	}

	private String offerNext(Session session) {
		for (String service : SERVICES) {
			if (!session.serviceStatus.get(service).equals("not_offered")) continue;
			session.focus = service;
			session.serviceStatus.put(service, "offered");
			session.servicesAsked.add(service);
			session.stage = "SERVICE_OFFERED";
			return serviceOffer(session);
		}
		throw new IllegalStateException("No unanswered service remains");
	}

	private String serviceOffer(Session session) {
		if (session.focus == null) return "UNCLEAR";
		return switch (session.focus) {
			case "pms" -> pmsOffer();
			case "insurance" -> "INSURANCE_OFFER";
			default -> today().isBefore(LocalDate.parse(customer.path("reg_window_start").asString())) ? "RENEWAL_OFFER_UPCOMING" : "RENEWAL_OFFER_OPEN";
		};
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
		if (value.contains("?")) return;
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
		LocalDate first = today().isAfter(start) ? today() : start;
		fields.put("booking_window_start", DATE_LABEL.format(first)); fields.put("booking_window_end", DATE_LABEL.format(end));
		fields.put("booking_window_start_iso", first.toString()); fields.put("booking_window_end_iso", end.toString());
		fields.put("first_name", customer.path("first_name").asString()); fields.put("plate", customer.path("plate").asString());
		String model = customer.path("model_description").asString("your Toyota");
		fields.put("model_or_plate", model + " (" + customer.path("plate").asString() + ")");
		fields.put("dial_number", phone); fields.put("branch_name", customer.path("enrichment").path("branch_name").asString());
		for (String name : List.of("pms_milestone_km", "pms_price", "reg_service_fee", "collection_note")) {
			JsonNode value = customer.path("enrichment").path(name);
			fields.put(name, value.isNull() || value.isMissingNode() ? "to be confirmed by our advisor"
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
		fields.put("slots_text", first.isAfter(end) ? "no dates available" : DATE_LABEL.format(first) + " to " + DATE_LABEL.format(end));
		return fields;
	}

	private Map<String, String> availableDates() {
		Map<String, String> slots = new LinkedHashMap<>();
		LocalDate start = LocalDate.parse(customer.path("reg_window_start").asString());
		LocalDate end = LocalDate.parse(customer.path("reg_window_end").asString());
		// A preferred day is a request, not a reservation of the branch's daily capacity.
		for (LocalDate date = today().isAfter(start) ? today() : start; !date.isAfter(end); date = date.plusDays(1)) {
			slots.put(date.toString(), date.toString());
		}
		return slots;
	}

	private String moreDates(Session session, String phone) {
		return offerDates(session, phone);
	}

	private String chosenSlot(Session session, String text) {
		// Match a complete date selection, rather than a date in a question, refusal or tentative reply.
		String value = text.toLowerCase(Locale.ROOT).replace('\u2019', '\'').trim().replaceAll("\\s+", " ")
				.replaceAll(",\\s*", " ")
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
				.replaceFirst("(?: (?:po|please|na lang|instead|works for me|would work for me|works|would work|is fine|is good|na|lang))+$", "");
		if (value.matches("(?:the )?(?:first|earliest)(?: one| day| date)?|(?:yung )?pinakamaaga")) {
			return session.slots.keySet().stream().findFirst().orElse(null);
		}
		List<String> matches = new ArrayList<>();
		for (var entry : session.slots.entrySet()) {
			LocalDate slot = LocalDate.parse(entry.getValue());
			List<String> dates = new ArrayList<>(List.of(slot.toString(), Integer.toString(slot.getDayOfMonth())));
			for (String format : List.of("MMM d", "MMMM d", "d MMM", "d MMMM", "M/d", "MM/dd", "d/M", "dd/MM")) {
				String date = DateTimeFormatter.ofPattern(format, Locale.ENGLISH).format(slot).toLowerCase(Locale.ROOT);
				dates.add(date);
				dates.add(date + (format.contains("/") ? "/" : " ") + slot.getYear());
			}
			if (slot.equals(today())) dates.addAll(List.of("today", "ngayon"));
			if (slot.equals(today().plusDays(1))) dates.addAll(List.of("tomorrow", "bukas"));
			String weekday = alternatives(List.of(slot.getDayOfWeek().name().toLowerCase(Locale.ROOT),
					DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).format(slot).toLowerCase(Locale.ROOT)));
			String date = "(?:" + weekday + "|(?:" + weekday + " )?" + alternatives(dates) + ")";
			if (value.matches(date)) matches.add(entry.getKey());
		}
		return matches.size() == 1 ? matches.getFirst() : null;
	}

	private String alternatives(List<String> values) {
		return "(?:" + String.join("|", values.stream().distinct().map(Pattern::quote).toList()) + ")";
	}

	private String fallback(Session session, Map<String, Object> fields) {
		if (session.stage.equals("CONFIRMED") || session.stage.equals("COMPLETED_NO_BOOKING")) return chatUnavailable(session);
		if (allServicesAnswered(session) && session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (session.stage.equals("SLOTS_OFFERED")) {
			return availableDates().isEmpty() ? noSlots(session) : render("SLOTS_OFFER", session, fields);
		}
		if (session.focus != null) {
			if (session.stage.equals("REG_STATUS_ASKED")) return render("RENEWAL_NOT_SURE", session, fields);
			return render(serviceOffer(session), session, fields);
		}
		if (allServicesAnswered(session) && availableDates().isEmpty()) return noSlots(session);
		return render("UNCLEAR", session, fields);
	}

	private String noSlots(Session session) {
		return session.language.equals("tl") ? "Wala na pong natitirang petsa sa renewal period ninyo. Kontakin po ang Toyota Manila Bay para sa ibang petsa."
				: "There are no remaining dates within your renewal period. Please contact Toyota Manila Bay for other dates.";
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
		if (text.contains("{{")) return language.equals("tl") ? "Kukumpirmahin po ng advisor ang detalye."
				: "Our advisor can confirm that detail.";
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
		return slots.values().stream().map(slot -> Map.of("date", slot, "label", label(slot))).toList();
	}
	private String label(String slot) { return DATE_LABEL.format(LocalDate.parse(slot)); }
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
		private List<String> services = List.of();
		private final Map<String, String> serviceStatus = new LinkedHashMap<>(Map.of("pms", "not_offered", "insurance", "not_offered", "renewal", "not_offered"));
		private final Set<String> servicesAsked = new LinkedHashSet<>(List.of("renewal"));
		private String focus = "renewal";
		private boolean suppressed;
		private final List<Map<String, String>> history = new ArrayList<>();
		private final Map<String, String> replies = new LinkedHashMap<>();
		private Session(String channel) { this.channel = channel; serviceStatus.put("renewal", "offered"); }
	}
	private record ConversationKey(String channel, String accountId, String participant) { }
	private record ConversationalMessage(List<String> templateIds, String message) { }
	private record Incoming(String channel, String accountId, String sender, String id, String content) { }
}
