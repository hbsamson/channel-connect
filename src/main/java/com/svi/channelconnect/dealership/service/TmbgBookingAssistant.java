package com.svi.channelconnect.dealership.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
import com.svi.channelconnect.ai.exception.AiClientException;

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
	private static final Pattern DATE_REFERENCE = Pattern.compile("(?i)\\b(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?"
			+ "|january|february|march|april|may(?=\\s+\\d)|june|july|august|september|october|november|december"
			+ "|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec|today|tomorrow|bukas|ngayon"
			+ "|lunes|martes|miyerkules|huwebes|biyernes|sabado|linggo|\\d{1,4}[-/]\\d{1,2}|\\d{1,2})\\b");
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
		The opening offers all three services together. While stage is OPENER_SENT, a clear yes accepts all three and a no invites the customer to choose services using SERVICE_CHOICE_PIVOT. Set opening_decision to accepted or declined only for clear consent or refusal of the combined offer; otherwise null. Questions, greetings and partial service choices are not consent to all three.
		The campaign's "3 in 1", "3-in-1" and "all three" refer to preventive maintenance, comprehensive insurance and registration renewal in one visit. Interpret replies in that context. Explain questions about the offer without recording consent; clear acceptance such as "yes, book the 3 in 1" accepts all three. This is not an insurance approval, fixed-price deal or guarantee of same-day completion.
		For an explicit choice of one, two or three services, set message_intent to decision and selected_services to the complete chosen list using renewal, pms and insurance. This applies at the opening, after bundle refusal and during individual offers. For example, "registration and maintenance only" selects renewal and pms, never insurance. A request such as "Can you book maintenance and insurance?" is a service choice; a pricing, availability, tentative or conditional question is not consent. Set selected_services to null for questions, refusals, greetings, status reports and date selections. Never infer omitted choices from mention alone.
		The service records explicit selections and proceeds directly to dates without offering omitted services. Do not pitch or repeat an unselected service. Bundle refusal alone sends SERVICE_CHOICE_PIVOT; a refusal of all services closes politely.
		A bare yes to an existing individual offer accepts only the current service; a no declines only that service. Already completed means skipped. Legacy individual offers may still advance until the customer explicitly selects their desired services.
		Never request booking or dates until all_services_answered is true. This flag also becomes true when the customer explicitly completes their service selection; it does not require asking about omitted services. If no services were accepted, close without booking.
		After service selection, a clear date selection sets message_intent=date_selection and slot=YYYY-MM-DD. It never saves a booking.
		Dates are next calendar week's Monday-Friday in Asia/Manila, intersected with the actual renewal window only when renewal is selected. Use only dates in slots_offered.
		After a date, ask for a preferred vehicle drop-off time from 8:00 AM to 2:00 PM inclusive in Asia/Manila. These are request hours, not available slots or capacity guarantees.
		For a clear time choice set message_intent=time_selection and selected_time=HH:mm in 24-hour Manila time. Preserve the chosen date; never treat a bare hour as a new day while a time is pending. Do not guess AM/PM if ambiguous.
		Set selected_time to null for questions, tentative choices or refusals. Date and time may be supplied together. Ask for explicit confirmation of both before saving.
		Only a clear affirmative reply while BOOKING_CONFIRMATION_PENDING requests outcome=booking_requested and message_intent=booking_confirmation. Never treat a new time, date, question or service choice as final confirmation.
		Every booking message must name the exact services in services_list, including time offers, invalid choices, confirmation, errors and the recorded request. Never add an unselected service or use generic 'selected services'.
		Availability questions, refusals, tentative dates and ambiguous choices are not selections. A clear request such as "Can you book me for October 19?" is a selection.
		Present the inclusive date window, not a fixed menu of appointment times. Ask which day works. Never show internal codes or numbered reply instructions.
		GENERAL DEALERSHIP QUESTIONS: Use APPROVED DEALERSHIP KNOWLEDGE to answer questions about Toyota Manila Bay and its services at any stage.
		Use AI reasoning to answer the actual question using the supplied facts. Templates and approved answers are reference material, not mandatory scripts. Set message_intent to question, decision, date_selection, time_selection, booking_confirmation or other. Questions must not record service decisions. For supported questions, compose a concise reply_text and optionally identify knowledge_topic; a null reply uses the approved answer as fallback.
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
		LOGGER.info("Booking campaign session initialized; channel={}", channel);
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
		String previousStage = session.stage;
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
						|| answer.path("message_intent").asString("").equals("question")
						|| answer.path("outcome").asString().equals("unavailable"))) {
					session.registration = previousRegistration;
					session.services = previousServices;
				}
				reply = apply(session, new Incoming(channel, account, participant, receiptId, message), answer, fields);
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("Booking reply failed; stage={}, failureType={}, category={}", session.stage,
					exception.getClass().getSimpleName(), exception instanceof AiClientException failure ? failure.category() : "INTERNAL");
			reply = fallback(session, fields);
		}
		if (!session.suppressed && !conversational.templateIds().isEmpty()) {
			List<String> acknowledgements = new ArrayList<>();
			for (String id : conversational.templateIds()) acknowledgements.add(render(id, session, fields));
			String acknowledgement = String.join(" ", acknowledgements);
			if (validReply(acknowledgement + " " + reply, session)) reply = acknowledgement + (reply.isBlank() ? "" : " " + reply);
		}
		if (session.language.equals("en")) reply = fullEnglish(reply);
		if (!previousStage.equals(session.stage)) {
			LOGGER.info("Booking stage changed; from={}, to={}", previousStage, session.stage);
		}
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
		state.put("service_selection_complete", session.serviceSelectionComplete);
		state.put("selected_date", session.selectedDate == null ? null : session.selectedDate.toString());
		state.put("selected_time", session.selectedTime == null ? null : session.selectedTime.toString());
		state.put("time_zone", MANILA.getId());
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
		if (answer == null || !answer.isObject() || !Set.of("en", "tl").contains(answer.path("lang").asString())) {
			LOGGER.debug("Booking response rejected; stage={}, reason=invalid_response_shape", session.stage);
			return fallback(session, fields);
		}
		session.language = answer.path("lang").asString();
		if (session.stage.equals("CONFIRMED") && (knowledge.bookingChange(incoming.content())
				|| Set.of("booking_change", "booking_requested", "callback_requested", "collection_booked").contains(answer.path("outcome").asString()))) {
			return bookingChangesUnavailable(session);
		}
		LOGGER.debug("Booking interpretation; stage={}, question={}, hasOpeningDecision={}", session.stage,
				answer.path("message_intent").asString("").equals("question"),
				Set.of("accepted", "declined").contains(answer.path("opening_decision").asString("")));
		if (session.stage.equals("SLOTS_OFFERED") && answer.path("outcome").asString().equals("more_dates")) {
			return moreDates(session, phone(incoming.channel(), incoming.sender()));
		}
		String template = answer.path("template_id").asString("");
		String outcome = answer.path("outcome").asString("");
		boolean question = answer.path("message_intent").asString("").equals("question");
		if (outcome.equals("opt_out") || outcome.equals("wrong_person")) {
			session.suppressed = true;
			return render(outcome.equals("opt_out") ? "OPTOUT_CONFIRM" : "ID_WRONG_PERSON", session, fields);
		}
		for (JsonNode flag : answer.path("flags")) {
			if (!question && flag.asString().equals("expired_registration")) session.registration = "expired";
		}
		if (!question && session.stage.equals("REG_STATUS_ASKED") && session.registration.equals("unknown")) {
			if (template.equals("REG_ALREADY_RENEWED")) session.registration = "renewed";
			if (template.equals("RENEWAL_OFFER_OPEN") || template.equals("RENEWAL_OFFER_UPCOMING")) session.registration = "not_yet";
			if (session.registration.equals("not_yet") && today().isAfter(LocalDate.parse(customer.path("reg_window_end").asString()))) session.registration = "expired";
		}
		if (answer.path("needs_human").asBoolean(false) || Set.of("handoff", "callback_requested", "collection_booked").contains(outcome)
				|| Set.of("HANDOFF", "CALLBACK_CONFIRM", "EXPIRED_COLLECTION").contains(template)) {
			return contactDealership(session);
		}
		if (!Set.of("CONFIRMED", "COMPLETED_NO_BOOKING").contains(session.stage) && !question
				&& knowledge.answer(answer.path("knowledge_topic").asString(""), session.language, session.channel) == null
				&& selectServices(session, answer)) {
			return offerDates(session, phone(incoming.channel(), incoming.sender()));
		}
		if (session.stage.equals("OPENER_SENT") && !question) {
			String decision = explicitDecision(session, incoming.content());
			if (decision == null) decision = answer.path("opening_decision").asString("");
			if (decision.equals("accepted")) {
				for (String service : SERVICES) {
					session.servicesAsked.add(service);
					session.serviceStatus.put(service, "accepted");
				}
				session.services = SERVICES;
				session.stage = "SERVICE_OFFERED";
				return offerDates(session, phone(incoming.channel(), incoming.sender()));
			}
			if (decision.equals("declined")) {
				session.stage = "SERVICE_CHOICE_PENDING";
				return render("SERVICE_CHOICE_PIVOT", session, fields);
			}
		}
		String approved = knowledge.answer(answer.path("knowledge_topic").asString(""), session.language, session.channel);
		if (question || approved != null) {
			String text = answer.path("reply_text").asString("");
			if (!outcome.equals("unavailable") && validReply(text, session) && !FALSE_CONFIRMATION.matcher(text).find()) return text;
			if (approved != null) return session.language.equals("en") ? fullEnglish(approved) : approved;
			LOGGER.debug("Booking question fallback; stage={}, unsupported={}, validReply={}",
					session.stage, outcome.equals("unavailable"), validReply(text, session));
			return chatUnavailable(session);
		}
		if (session.stage.equals("CONFIRMED") || session.stage.equals("COMPLETED_NO_BOOKING")) return chatUnavailable(session);
		if (session.stage.equals("SERVICE_CHOICE_PENDING")
				&& ("declined".equals(explicitDecision(session, incoming.content())) || outcome.equals("declined"))) {
			session.stage = "COMPLETED_NO_BOOKING";
			return render("DECLINE_CLOSE", session, fields);
		}
		if (session.stage.equals("REG_STATUS_ASKED") && allServicesAnswered(session)) {
			return session.registration.equals("unknown") ? render("REG_VALID_CHECK", session, fields)
					: offerDates(session, phone(incoming.channel(), incoming.sender()));
		}
		if (Set.of("CALLBACK_ASK", "THINKING_TIME").contains(template)) return render(template, session, fields);
		if (scheduling(session)) return bookingStep(session, incoming, answer, fields);
		if (updateServices(session, answer, incoming.content())) {
			return advanceServices(session, phone(incoming.channel(), incoming.sender()));
		}
		if (outcome.equals("unavailable")) return chatUnavailable(session);
		if (!template.isEmpty() && (!templates.containsKey(template) || !templates.get(template).path("used_by").asString().equals("assistant"))) return fallback(session, fields);
		if (session.stage.equals("REG_STATUS_ASKED") && "renewal".equals(session.focus)
				&& !session.registration.equals("unknown")) {
			session.stage = "SERVICE_OFFERED";
			return render(serviceOffer(session), session, fields);
		}
		if (outcome.equals("booking_requested")) return fallback(session, fields);
		if (template.startsWith("SLOTS_OFFER") || answer.path("state_after").asString().equals("SLOTS_OFFERED")) {
			return allServicesAnswered(session) ? offerDates(session, phone(incoming.channel(), incoming.sender())) : fallback(session, fields);
		}
		if (template.equals("DECLINE_CLOSE") || template.equals("ADDON_ASK") || template.equals("NEXT_SERVICE_LEAD_IN")) return fallback(session, fields);
		if (session.stage.equals("SLOTS_OFFERED") && session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (!answer.path("reply_text").isString()) return fallback(session, fields);
		String text = answer.path("reply_text").asString();
		if (!validReply(text, session) || FALSE_CONFIRMATION.matcher(text).find()) {
			LOGGER.debug("Booking response rejected; stage={}, reason=invalid_reply", session.stage);
			return fallback(session, fields);
		}
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

	private boolean scheduling(Session session) {
		return Set.of("SLOTS_OFFERED", "BOOKING_TIME_ASKED", "BOOKING_CONFIRMATION_PENDING").contains(session.stage);
	}

	private String bookingStep(Session session, Incoming incoming, JsonNode answer, Map<String, Object> fields) {
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (fields.get("window_status").equals("closed") && session.registration.equals("unknown")) {
			session.stage = "REG_STATUS_ASKED";
			return render("REG_VALID_CHECK", session, fields);
		}
		String content = incoming.content();
		if (session.stage.equals("BOOKING_CONFIRMATION_PENDING") && "declined".equals(explicitDecision(session, content))) {
			session.selectedTime = null;
			session.stage = "BOOKING_TIME_ASKED";
			return render("BOOKING_TIME_OFFER", session, fields(session, phone(incoming.channel(), incoming.sender())));
		}
		if (!definiteChoice(content)) return fallback(session, fields);
		TmbgBookingTime.Choice time = TmbgBookingTime.parse(content, session.selectedDate != null);
		String intent = answer.path("message_intent").asString("");
		String date = chosenSlot(session, time.mentioned() ? time.dateText() : content);
		if (date == null && intent.equals("date_selection")) date = answer.path("slot").asString("");
		boolean invalidDate = date != null && !date.isBlank()
				&& (!session.slots.containsKey(date) || !availableDates(session).containsKey(date));
		String dateText = time.mentioned() ? time.dateText() : content;
		if ((date == null || date.isBlank()) && (!time.mentioned() || time.time() != null)
				&& DATE_REFERENCE.matcher(dateText).find()) invalidDate = true;
		if (invalidDate) {
			session.selectedDate = null;
			session.selectedTime = null;
			session.stage = "SLOTS_OFFERED";
			return availableDates(session).isEmpty() ? noSlots(session) : render("SLOT_UNAVAILABLE", session, fields);
		}
		if (date != null && !date.isBlank()) {
			session.selectedDate = LocalDate.parse(date);
			session.selectedTime = null;
			session.stage = "BOOKING_TIME_ASKED";
		}
		LocalTime selectedTime = time.time();
		if (!time.mentioned() && Set.of("date_selection", "time_selection").contains(intent) && answer.path("selected_time").isString()) {
			// Natural-language expressions may be interpreted by the model; validate its normalized value.
			String value = answer.path("selected_time").asString();
			if (value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) selectedTime = LocalTime.parse(value);
		}
		if (session.selectedDate != null && (time.mentioned() || selectedTime != null || intent.equals("time_selection"))) {
			session.selectedTime = null;
			session.stage = "BOOKING_TIME_ASKED";
			if (!TmbgBookingTime.valid(selectedTime)) {
				return render("BOOKING_TIME_INVALID", session, fields(session, phone(incoming.channel(), incoming.sender())));
			}
			session.selectedTime = selectedTime;
			session.stage = "BOOKING_CONFIRMATION_PENDING";
			return render("BOOKING_TIME_CONFIRM", session, fields(session, phone(incoming.channel(), incoming.sender())));
		}
		if (date != null && !date.isBlank()) {
			return render("BOOKING_TIME_OFFER", session, fields(session, phone(incoming.channel(), incoming.sender())));
		}
		if (session.stage.equals("BOOKING_CONFIRMATION_PENDING")) {
			if (bookingConfirmed(session, content) || intent.equals("booking_confirmation")
					&& answer.path("outcome").asString("").equals("booking_requested")) return book(session, incoming, answer, fields);
		}
		return fallback(session, fields);
	}

	private boolean definiteChoice(String content) {
		String text = content.toLowerCase(Locale.ROOT).trim();
		if (text.matches("(?s).*\\b(maybe|perhaps|tentative|not sure|if|might|not|don't|cannot|can't|no|never|hindi|ayoko|huwag|baka|siguro)\\b.*")) return false;
		if (text.matches("^(?:is|are|what|which|when|anong|kailan)\\b.*")) return false;
		return !text.contains("?") || text.matches("^(?:please )?(?:(?:can|could|would) you (?:please )?)?(?:book|reserve|schedule|submit)\\b.*");
	}

	private boolean bookingConfirmed(Session session, String content) {
		String text = content.toLowerCase(Locale.ROOT).trim().replaceAll(",\\s*", " ");
		return "accepted".equals(explicitDecision(session, text))
				|| text.matches("(?:yes[, ]+)?(?:please )?(?:confirm|submit(?: it| the request)?|go ahead(?: and submit)?|that's correct|that is correct|tama(?: po)?|ituloy(?: po)?|i-confirm(?: po)?)[.! ]*");
	}

	private String book(Session session, Incoming incoming, JsonNode answer, Map<String, Object> fields) {
		if (!allServicesAnswered(session) || !session.stage.equals("BOOKING_CONFIRMATION_PENDING")
				|| session.selectedDate == null || !TmbgBookingTime.valid(session.selectedTime)) return fallback(session, fields);
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (session.services.isEmpty()
				|| (session.registration.equals("unknown") && fields.get("window_status").equals("closed"))) return fallback(session, fields);
		String code = session.selectedDate.toString();
		if (!availableDates(session).containsKey(code)) {
			session.slots = availableDates(session);
			session.selectedDate = null;
			session.selectedTime = null;
			session.stage = "SLOTS_OFFERED";
			if (session.slots.isEmpty()) return noSlots(session);
			return render("SLOT_TAKEN", session, fields(session, phone(incoming.channel(), incoming.sender())));
		}
		String selected = session.selectedDate.atTime(session.selectedTime).atZone(MANILA).toOffsetDateTime().toString();
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
		booking.put("appointment_date", code); booking.put("appointment_time", session.selectedTime.toString());
		booking.put("time_zone", MANILA.getId()); booking.put("customer_confirmed", true);
		booking.put("services_asked", List.copyOf(session.servicesAsked)); booking.put("all_services_answered", true);
		booking.put("service_selection_complete", session.serviceSelectionComplete);
		booking.put("created_at", clock.instant().atZone(MANILA).toOffsetDateTime().toString());
		fields.put("booking_ref", reference);
		fields.put("slot_selected", label(code) + " at " + TmbgBookingTime.label(session.selectedTime));
		String confirmation = render("BOOKING_CONFIRMED", session, fields);
		if (!validReply(confirmation, session)) return render("BOOKING_ERROR", session, fields);
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
		if (normalized.matches("(?s).*\\b(person|advisor|agent|tao|callback|call me)\\b.*")) {
			return json.valueToTree(Map.of("lang", session.language, "outcome", "handoff"));
		}
		List<String> selected = mockServiceSelection(text);
		if (!selected.isEmpty() && !Set.of("CONFIRMED", "COMPLETED_NO_BOOKING").contains(session.stage)) {
			return json.valueToTree(Map.of("lang", session.language, "message_intent", "decision", "selected_services", selected));
		}
		if (scheduling(session) && definiteChoice(text)) {
			var time = TmbgBookingTime.parse(text, session.selectedDate != null);
			String date = chosenSlot(session, time.mentioned() ? time.dateText() : text);
			if (time.mentioned() || date != null || bookingConfirmed(session, text)
					|| "declined".equals(explicitDecision(session, text))) {
				return json.valueToTree(Map.of("lang", session.language, "message_intent", "other"));
			}
		}
		String topic = normalized.matches("(?s).*\\b(magkano|how much|cost|fees?)\\b.*") ? "" : knowledge.mockTopic(text);
		if (!topic.isEmpty() || session.stage.equals("CONFIRMED") || session.stage.equals("COMPLETED_NO_BOOKING")) {
			return json.valueToTree(Map.of("lang", session.language, "knowledge_topic", topic,
					"outcome", session.stage.equals("CONFIRMED") && knowledge.bookingChange(text) ? "booking_change" : "unavailable"));
		}
		String template;
		String stage = session.stage;
		Map<String, Object> answer = new LinkedHashMap<>();
		if (normalized.matches("(?s).*\\b(busy|not now|mamaya)\\b.*")) {
			template = "CALLBACK_ASK";
		} else if (normalized.matches("(?s).*\\b(price|cost|magkano|fees?|how much)\\b.*")) {
			answer.put("message_intent", "question");
			template = "insurance".equals(session.focus) ? "INSURANCE_PRICE_INFO"
					: "renewal".equals(session.focus) ? "FEES" : "PMS_PRICE_INFO";
		} else if (normalized.matches("(?s).*\\bemission\\b.*")) {
			answer.put("message_intent", "question");
			template = "EMISSION";
		} else if (normalized.contains("ctpl")) {
			answer.put("message_intent", "question");
			template = "BASIC_INSURANCE_INFO";
		}
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

	private List<String> mockServiceSelection(String text) {
		String value = text.toLowerCase(Locale.ROOT).trim()
				.replaceFirst("^(?:i (?:want|choose|need|would like)|please book|book|gusto ko(?: po)?|paki-book)\\s+", "")
				.replaceFirst("[.!]+$", "").replaceFirst("(?:\\s+(?:only|lang|po|please))+$", "");
		List<String> selected = new ArrayList<>();
		for (String part : value.split("\\s*(?:,|&|\\band\\b|\\bat\\b|\\bplus\\b)\\s*")) {
			String service = switch (part.trim()) {
				case "registration", "registration renewal", "lto", "lto registration renewal", "rehistro", "renewal" -> "renewal";
				case "pms", "maintenance", "preventive maintenance", "preventative maintenance service" -> "pms";
				case "insurance", "comprehensive insurance", "insurance renewal", "comprehensive insurance renewal" -> "insurance";
				default -> null;
			};
			if (service == null || selected.contains(service)) return List.of();
			selected.add(service);
		}
		return selected;
	}

	private boolean selectServices(Session session, JsonNode answer) {
		if (!answer.path("message_intent").asString("").equals("decision")) return false;
		JsonNode selected = answer.path("selected_services");
		if (!selected.isArray() || selected.isEmpty() || selected.size() > SERVICES.size()) return false;
		Set<String> choices = new LinkedHashSet<>();
		for (JsonNode value : selected) {
			if (!value.isString() || !SERVICES.contains(value.asString()) || !choices.add(value.asString())) return false;
		}
		session.services = SERVICES.stream().filter(choices::contains).toList();
		session.selectedDate = null;
		session.selectedTime = null;
		for (String service : SERVICES) {
			session.serviceStatus.put(service, choices.contains(service) ? "accepted" : "skipped");
		}
		session.servicesAsked.addAll(session.services);
		session.serviceSelectionComplete = true;
		session.focus = null;
		session.stage = "SERVICE_OFFERED";
		return true;
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
		LOGGER.info("Booking service decision recorded; service={}, decision={}", session.focus, next);
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
		return session.serviceSelectionComplete || (session.servicesAsked.containsAll(SERVICES)
				&& SERVICES.stream().allMatch(service -> DECISIONS.contains(session.serviceStatus.get(service))));
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
		return session.focus == null ? reply : acknowledgement + " " + reply;
	}

	private String offerDates(Session session, String phone) {
		if (!allServicesAnswered(session) || session.services.isEmpty()) return fallback(session, fields(session, phone));
		Map<String, Object> fields = fields(session, phone);
		if (session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (fields.get("window_status").equals("closed") && session.registration.equals("unknown")) {
			session.stage = "REG_STATUS_ASKED";
			return render("REG_VALID_CHECK", session, fields);
		}
		if (session.registration.equals("renewed") && session.services.contains("renewal")) {
			session.serviceStatus.put("renewal", "skipped");
			session.services = session.services.stream().filter(service -> !service.equals("renewal")).toList();
			if (session.services.isEmpty()) {
				session.stage = "COMPLETED_NO_BOOKING";
				return render("REG_ALREADY_RENEWED", session, fields);
			}
		}
		session.selectedDate = null;
		session.selectedTime = null;
		session.slots = availableDates(session);
		if (session.slots.isEmpty()) return noSlots(session);
		session.stage = "SLOTS_OFFERED";
		String template = session.serviceSelectionComplete && session.services.size() == 2 ? "TWO_SERVICES_SELECTED" : "SLOTS_OFFER";
		return render(template, session, fields(session, phone));
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
			case "pms" -> "Preventative Maintenance Service";
			case "insurance" -> "Comprehensive Insurance Renewal";
			case "renewal" -> "LTO Registration Renewal";
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
		if (session.stage.equals("REG_STATUS_ASKED") && allServicesAnswered(session)
				&& value.matches("(?:yes|oo|opo|valid|still valid|valid pa)(?: po)?[.! ]*")) session.registration = "valid";
		else if (value.matches("(?s).*(expired|lapsed|window has passed|since june).*")) session.registration = "expired";
		else if (value.matches("(?s).*(not sure|unsure|hindi ko alam|di ko sure|nakalimutan).*")) session.registration = "unknown";
		else if (value.matches("(?s).*(not yet|not renewed|haven't renewed|have not renewed|hindi.*pa.*renew|di.*pa.*renew|hindi pa|di pa|wala pa).*")) session.registration = "not_yet";
		else if (value.matches("(?s).*(already renewed|na-renew ko na|narenew ko na|na-renew na|narenew na|tapos na|done na|last month pa).*")
				|| value.matches("(renewed|na-renew|narenew)( na)?( po)?[.! ]*")) session.registration = "renewed";
		else if (session.stage.equals("REG_STATUS_ASKED")) {
			if (allServicesAnswered(session) && value.matches("(?:no|hindi|hindi na)(?: po)?[.! ]*")) session.registration = "expired";
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
		LinkedHashMap<String, String> dates = availableDates(session);
		String first = dates.isEmpty() ? null : dates.keySet().iterator().next();
		String last = dates.isEmpty() ? null : dates.sequencedKeySet().getLast();
		fields.put("booking_window_start", first == null ? "no eligible dates" : label(first));
		fields.put("booking_window_end", last == null ? "no eligible dates" : label(last));
		fields.put("booking_window_start_iso", first); fields.put("booking_window_end_iso", last);
		fields.put("first_name", customer.path("first_name").asString()); fields.put("plate", customer.path("plate").asString());
		String model = customer.path("model_description").asString("your Toyota");
		fields.put("full_name", customer.path("full_name").asString(customer.path("first_name").asString()));
		fields.put("model", model);
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
		fields.put("service_name", session.focus != null ? serviceName(session.focus)
				: session.services.size() == 1 ? serviceName(session.services.getFirst()) : "service");
		fields.put("services_list", String.join(" and ", session.services.stream().map(this::serviceName).toList()));
		fields.put("next_service_name", SERVICES.stream().filter(service -> session.serviceStatus.get(service).equals("not_offered"))
				.findFirst().map(this::serviceName).orElse("service"));
		fields.put("slots_text", first == null ? "no dates available" : label(first) + " to " + label(last));
		fields.put("selected_date", session.selectedDate == null ? "" : label(session.selectedDate.toString()));
		fields.put("selected_time", session.selectedTime == null ? "" : TmbgBookingTime.label(session.selectedTime));
		fields.put("drop_off_start", TmbgBookingTime.label(TmbgBookingTime.OPEN));
		fields.put("drop_off_end", TmbgBookingTime.label(TmbgBookingTime.CLOSE));
		fields.put("time_zone", MANILA.getId());
		return fields;
	}

	private LinkedHashMap<String, String> availableDates(Session session) {
		LinkedHashMap<String, String> slots = new LinkedHashMap<>();
		LocalDate start = today().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
		LocalDate end = start.plusDays(4);
		if (session.services.contains("renewal")) {
			LocalDate renewalStart = LocalDate.parse(customer.path("reg_window_start").asString());
			LocalDate renewalEnd = LocalDate.parse(customer.path("reg_window_end").asString());
			if (start.isBefore(renewalStart)) start = renewalStart;
			if (end.isAfter(renewalEnd)) end = renewalEnd;
		}
		// A preferred day is a request, not a reservation of the branch's daily capacity.
		for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
			if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) slots.put(date.toString(), date.toString());
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
		LOGGER.debug("Booking fallback; stage={}", session.stage);
		if (session.stage.equals("OPENER_SENT")) return render("RESUME_COMBINED_OFFER", session, fields);
		if (session.stage.equals("SERVICE_CHOICE_PENDING")) return render("SERVICE_CHOICE_PIVOT", session, fields);
		if (session.stage.equals("CONFIRMED") || session.stage.equals("COMPLETED_NO_BOOKING")) return chatUnavailable(session);
		if (allServicesAnswered(session) && session.registration.equals("expired")) return render("DRIVE_IN_REFUSAL", session, fields);
		if (session.stage.equals("SLOTS_OFFERED")) {
			return availableDates(session).isEmpty() ? noSlots(session) : render("SLOTS_OFFER", session, fields);
		}
		if (session.stage.equals("BOOKING_TIME_ASKED")) return render("BOOKING_TIME_OFFER", session, fields);
		if (session.stage.equals("BOOKING_CONFIRMATION_PENDING")) return render("BOOKING_TIME_CONFIRM", session, fields);
		if (session.stage.equals("REG_STATUS_ASKED") && allServicesAnswered(session)) return render("REG_VALID_CHECK", session, fields);
		if (session.focus != null) {
			if (session.stage.equals("REG_STATUS_ASKED")) return render("RENEWAL_NOT_SURE", session, fields);
			return render(serviceOffer(session), session, fields);
		}
		if (allServicesAnswered(session) && availableDates(session).isEmpty()) return noSlots(session);
		return render("UNCLEAR", session, fields);
	}

	private String noSlots(Session session) {
		return render("DEMO_NO_ELIGIBLE_DATE", session, Map.of("services_list", String.join(" and ", session.services.stream().map(this::serviceName).toList())));
	}

	private String render(String id, Session session, Map<String, Object> fields) {
		return render(id, session.language, fields, session.channel.equals("SMS"));
	}
	private String render(String id, String language, Map<String, Object> fields) {
		return render(id, language, fields, false);
	}
	private String render(String id, String language, Map<String, Object> fields, boolean sms) {
		String text = templates.get(id).path(sms ? "sms_" + language : language).asString(templates.get(id).path(language).asString());
		if ((id.startsWith("INSURANCE_") && id.contains("PRICE") || id.startsWith("INSURANCE_OFFER"))
				&& !insurancePrices.path("price_range").path("low").isNumber()) {
			text = templates.get(id).path("fallback_" + language).asString(text);
		}
		if (id.equals("PMS_PRICE_INFO") && (customer.path("enrichment").path("pms_price").isNull()
				|| customer.path("enrichment").path("pms_price").isMissingNode())) {
			text = templates.get(id).path("fallback_" + language).asString(text);
		}
		for (var field : fields.entrySet()) text = text.replace("{{" + field.getKey() + "}}", field.getValue() == null ? "" : field.getValue().toString());
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

	private boolean validReply(String text, Session session) {
		// All supplied templates use ordinary GSM-7 punctuation. Reject extended characters and Unicode.
		return text != null && !text.isBlank() && text.length() <= (session.channel.equals("SMS") ? 306 : 4096)
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
		private LocalDate selectedDate;
		private LocalTime selectedTime;
		private List<String> services = List.of();
		private final Map<String, String> serviceStatus = new LinkedHashMap<>(Map.of("pms", "not_offered", "insurance", "not_offered", "renewal", "not_offered"));
		private final Set<String> servicesAsked = new LinkedHashSet<>();
		private String focus;
		private boolean suppressed;
		private boolean serviceSelectionComplete;
		private final List<Map<String, String>> history = new ArrayList<>();
		private final Map<String, String> replies = new LinkedHashMap<>();
		private Session(String channel) { this.channel = channel; }
	}
	private record ConversationKey(String channel, String accountId, String participant) { }
	private record ConversationalMessage(List<String> templateIds, String message) { }
	private record Incoming(String channel, String accountId, String sender, String id, String content) { }
}
