package com.svi.channelconnect.ai.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.DealershipIntent;

/** Channel-neutral language interpretation. No access to transport or booking side effects. */
@Service
public class DealershipIntentInterpreter {
	private static final Logger LOGGER = LoggerFactory.getLogger(DealershipIntentInterpreter.class);
	private static final Set<String> INTENTS = Set.of("BOOK", "RESCHEDULE", "CANCEL", "CONFIRM", "DECLINE", "OPT_OUT",
			"PRIVACY", "HANDOFF", "REQUEST_STATUS", "VERIFY_HELP", "SERVICES", "CLARIFY", "PRICE", "PACKAGES", "HOURS", "LOCATION", "DUE", "AVAILABILITY", "INQUIRY", "CONTINUE", "OUT_OF_SCOPE");
	private final AiClient client;
	private final AiProperties properties;
	private final ObjectMapper json;
	private final Clock clock;

	public DealershipIntentInterpreter(AiClient client, AiProperties properties, ObjectMapper json, Clock clock) {
		this.client = client;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
	}

	public DealershipIntent interpret(String id, String text, String instructions, Map<String, Object> context) {
		DealershipIntent fallback = parse(text);
		String stage = String.valueOf(context.getOrDefault("workflowStage", "IDLE"));
		if (fallback.intent().equals("OUT_OF_SCOPE") && stage.equals("HANDOFF_REQUESTED") && !isUnsafeTopic(text)
				&& text.toLowerCase(Locale.ROOT).matches("(?s).*(notifi|notified|understand|mean|what+t?|huh|\\?\\?).*")) {
			fallback = new DealershipIntent("CLARIFY", null, null, null, fallback.language());
		}
		if (fallback.intent().equals("OPT_OUT") || isUnsafeTopic(text)) return fallback;
		if (properties.provider() == AiProperties.Provider.MOCK) return uncertainFallback(fallback);
		if (Set.of("SERVICES", "REQUEST_STATUS", "VERIFY_HELP", "CLARIFY").contains(fallback.intent())
				|| Set.of("HANDOFF_CONFIRM", "VERIFY_REQUIRED", "CANCEL_CONFIRM").contains(stage)
				&& Set.of("CONFIRM", "DECLINE").contains(fallback.intent())) return fallback;
		try {
			String response = client.generateResponse(new AiRequest(id,
					json.writeValueAsString(Map.of("currentDateManila", today().toString(), "context", context, "customerMessage", text)), instructions, true)).text();
			DealershipIntent intent = json.readValue(response, DealershipIntent.class);
			if (!INTENTS.contains(intent.intent()) || !Set.of("en", "tl").contains(intent.language())) return uncertainFallback(fallback);
			if (intent.date() != null) LocalDate.parse(intent.date());
			if (intent.time() != null) LocalTime.parse(intent.time());
			if (intent.serviceId() != null && !Set.of("registration", "pms", "2-in-1", "3-in-1", "custom").contains(intent.serviceId())) return uncertainFallback(fallback);
			return intent;
		} catch (RuntimeException exception) {
			LOGGER.warn("Dealership AI interpretation unavailable; using deterministic demo parser");
			return uncertainFallback(fallback);
		}
	}
	private DealershipIntent uncertainFallback(DealershipIntent fallback) {
		return fallback.intent().equals("OUT_OF_SCOPE")
				? new DealershipIntent("CLARIFY", null, null, null, fallback.language()) : fallback;
	}

	public DealershipIntent parse(String raw) {
		String text = raw.toLowerCase(Locale.ROOT).trim();
		String language = Pattern.compile("\\b(po|opo|ako|gusto|petsa|oras|mag|pwede|puwede|kailan|saan|magkano|salamat|huwag|ayaw)\\b").matcher(text).find() ? "tl" : "en";
		String date = null;
		String time = null;
		var dateMatch = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b").matcher(text);
		try {
			if (dateMatch.find()) date = LocalDate.parse(dateMatch.group(1)).toString();
			else if (text.contains("tomorrow") || text.contains("bukas")) date = today().plusDays(1).toString();
			else if (text.contains("today") || text.contains("ngayon")) date = today().toString();
			else {
				for (DayOfWeek day : DayOfWeek.values()) {
					if (text.contains(day.name().toLowerCase(Locale.ROOT))) {
						date = today().with(text.contains("next ") ? TemporalAdjusters.next(day) : TemporalAdjusters.nextOrSame(day)).toString();
						break;
					}
				}
				String[] filipinoDays = {"lunes", "martes", "miyerkules", "huwebes", "biyernes", "sabado", "linggo"};
				for (int i = 0; i < filipinoDays.length; i++) if (text.contains(filipinoDays[i])) date = today().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(i + 1))).toString();
			}
			var timeMatch = Pattern.compile("(?<![\\d-])(\\d{1,2})(?::([0-5]\\d))?\\s*(am|pm)\\b|\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").matcher(text);
			if (timeMatch.find()) {
				int hour = Integer.parseInt(timeMatch.group(1) != null ? timeMatch.group(1) : timeMatch.group(4));
				int minute = timeMatch.group(1) != null ? (timeMatch.group(2) == null ? 0 : Integer.parseInt(timeMatch.group(2))) : Integer.parseInt(timeMatch.group(5));
				if (timeMatch.group(3) != null) hour = hour % 12 + (timeMatch.group(3).equals("pm") ? 12 : 0);
				time = LocalTime.of(hour, minute).toString();
			}
		} catch (RuntimeException ignored) { date = null; time = null; }
		String service = text.contains("3-in-1") ? "3-in-1" : text.contains("2-in-1") ? "2-in-1"
				: text.contains("pms") || text.contains("maintenance") ? "pms" : text.contains("registration") || text.contains("rehistro") ? "registration" : null;
		String intent;
		if (text.matches("(?s).*(\\bstop\\b|unsubscribe|opt.?out|do not contact|don't contact|no more messages|no more reminders|huwag.*(contact|message)|ayaw.*reminder).*")) intent = "OPT_OUT";
		else if (isUnsafeTopic(text)) intent = "OUT_OF_SCOPE";
		else if (text.matches("(?s).*(\\breq-[a-z0-9]{16}\\b|tracking|reference number|where.*request|request.*status|what.*happen.*request|did.*record.*request|follow.*up.*request).*")) intent = "REQUEST_STATUS";
		else if (text.matches("(?s).*(how.*(verify|link)|verification|verify.*account|link.*account).*")) intent = "VERIFY_HELP";
		else if (text.matches("[?!]+|(?:what+|huh|pardon|sorry|how can i do that|how do i do that|what do you mean)[?! .]*")) intent = "CLARIFY";
		else if (text.matches("(?s).*(privacy|personal data|my information|got my|my number|why.*contact|bakit.*(contact|message)).*")) intent = "PRIVACY";
		else if (text.matches("(?s).*(human|representative|agent|tao|kausap).*")) intent = "HANDOFF";
		else if (text.matches("(?s).*(cancel|kansel).*")) intent = "CANCEL";
		else if (text.matches("(?s).*(reschedule|move|change|instead|lipat|palit).*")) intent = "RESCHEDULE";
		else if (text.matches("(?s).*(price|cost|how much|magkano|discount).*")) intent = "PRICE";
		else if (service == null && text.matches("(?s).*(what|which|ano|anong).*(services?|offer|packages?).*")) intent = "SERVICES";
		else if (text.matches("(?s).*(package|inclusion|2-in-1|3-in-1|offerings).*")) intent = "PACKAGES";
		else if (text.matches("(?s).*(hours|open|close|bukas.*oras).*")) intent = "HOURS";
		else if (text.matches("(?s).*(location|address|where.*branch|saan).*")) intent = "LOCATION";
		else if (text.matches("(?s).*(due|deadline|renewal status).*")) intent = "DUE";
		else if (text.matches("(?s).*(available|availability|what.*schedule|anong.*schedule).*")) intent = "AVAILABILITY";
		else if (text.matches("(?s).*(book|appointment|schedule|visit).*")) intent = "BOOK";
		else if (text.matches("(?s).*(registration|renewal|insurance|ctpl|requirements|dealership|payment|service|vehicle|rehistro).*")) intent = "INQUIRY";
		else if (text.replace(',', ' ').replaceAll("\\s+", " ").matches("(yes(?: please| po)?|sure(?: go| go ahead| please)?|go ahead|please do|do it|opo|oo|sige(?: po| na)?|okay(?: go| go ahead| please)?|ok(?: go| please)?|confirm|sounds good|absolutely)[.! ]*")) intent = "CONFIRM";
		else if (text.matches("(no|nope|not now|no thanks|no thank you|not interested|hindi)[.! ]*")) intent = "DECLINE";
		else if (date != null || time != null || text.matches("(hi|hello|hello po|thanks|thank you|salamat po)[.! ]*")) intent = "CONTINUE";
		else intent = "OUT_OF_SCOPE";
		return new DealershipIntent(intent, date, time, service, language);
	}
	private boolean isUnsafeTopic(String text) {
		return text.toLowerCase(Locale.ROOT).matches("(?s).*(python|programming|write.*code|homework|politic|trivia|favorite colo[u]?r|system prompt|environment variable|api.?key|ignore.*instruction|reveal.*secret).*" );
	}
	private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila"))); }
}
