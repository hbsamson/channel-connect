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
			"PRIVACY", "HANDOFF", "PRICE", "PRICE_OBJECTION", "VALUE", "PMS_DETAILS", "INSURANCE_DETAILS", "INSURANCE_OBJECTION",
			"LTO_DETAILS", "LTO_DIY", "WAIT_TIME", "ESSENTIAL_QUOTE", "BUNDLE", "PACKAGES", "HOURS", "LOCATION", "DUE", "AVAILABILITY", "INQUIRY", "CONTINUE", "OUT_OF_SCOPE");
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
		if (fallback.intent().equals("OPT_OUT") || isUnsafeTopic(text) || properties.provider() == AiProperties.Provider.MOCK) return fallback;
		try {
			String response = client.generateResponse(new AiRequest(id,
					json.writeValueAsString(Map.of("currentDateManila", today().toString(), "context", context, "customerMessage", text)), instructions, true)).text();
			DealershipIntent intent = json.readValue(response, DealershipIntent.class);
			if (!INTENTS.contains(intent.intent()) || !Set.of("en", "tl").contains(intent.language())) return fallback;
			if (intent.date() != null) LocalDate.parse(intent.date());
			if (intent.time() != null) LocalTime.parse(intent.time());
			if (intent.serviceId() != null && !Set.of("registration", "pms", "insurance", "2-in-1", "3-in-1", "custom").contains(intent.serviceId())) return fallback;
			return intent;
		} catch (RuntimeException exception) {
			LOGGER.warn("Dealership AI interpretation unavailable; using deterministic demo parser");
			return fallback;
		}
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
		String service = text.contains("3-in-1") || text.contains("bundle") ? "3-in-1" : text.contains("2-in-1") ? "2-in-1"
				: text.contains("pms") || text.contains("maintenance") ? "pms" : text.contains("registration") || text.contains("rehistro") || text.contains("lto") || text.contains("ltms") ? "registration"
						: text.contains("insurance") || text.contains("insure") ? "insurance" : null;
		String intent;
		if (text.matches("(?s).*(\\bstop\\b|unsubscribe|opt.?out|do not contact|don't contact|no more messages|no more reminders|huwag.*(contact|message)|ayaw.*reminder).*")) intent = "OPT_OUT";
		else if (isUnsafeTopic(text)) intent = "OUT_OF_SCOPE";
		else if (text.matches("(?s).*(privacy|personal data|my information|got my|my number|why.*contact|bakit.*(contact|message)).*")) intent = "PRIVACY";
		else if (text.matches("(?s).*(human|representative|agent|tao|kausap).*")) intent = "HANDOFF";
		else if (text.matches("(?s).*(cancel|kansel).*")) intent = "CANCEL";
		else if (text.matches("(?s).*(reschedul|move|change|instead|lipat|palit).*")) intent = "RESCHEDULE";
		else if (text.matches("(?s).*(insurance|insure|broker).*") && text.matches("(?s).*(cheaper|expensive|mas mura|mahal|broker).*")) intent = "INSURANCE_OBJECTION";
		else if (text.matches("(?s).*(ltms|do.?it.?myself|do.?it.?yourself|\\bdiy\\b|ako na.*(lto|rehistro)|online.*lto|lto.*online).*")) intent = "LTO_DIY";
		else if (text.matches("(?s).*(essential.only|essentials|essential quote|upfront.*estimate|unwanted.*add|no.*add.ons|basic.*quote).*")) intent = "ESSENTIAL_QUOTE";
		else if (text.matches("(?s).*(no time.*wait|walang oras|long.*wait|waiting time|turnaround|express maintenance|how long.*(pms|service|maintenance)|gaano katagal).*")) intent = "WAIT_TIME";
		else if (text.matches("(?s).*(too expensive|expensive|cheaper|overpriced|mas mura|mahal|ibang (shop|store|talyer)|other (shop|store|garage)).*")) intent = "PRICE_OBJECTION";
		else if (text.matches("(?s).*(why.*(toyota|manila bay|dealer|branch|choose|you)|bakit.*(toyota|manila bay|dealer|branch|kayo)|certified parts|genuine parts|trained (mechanic|technician)|benefits|advantage).*")) intent = "VALUE";
		else if (text.matches("(?s).*(bundle|one (visit|stop)|isang (visit|punta|bisita)).*") && !text.matches("(?s).*(book|appointment|schedule).*")) intent = "BUNDLE";
		else if (text.matches("(?s).*(price|cost|how much|magkano|discount).*")) intent = "PRICE";
		else if (text.matches("(?s).*(hours|open|close|bukas.*oras).*")) intent = "HOURS";
		else if (text.matches("(?s).*(location|address|where.*branch|saan).*")) intent = "LOCATION";
		else if (text.matches("(?s).*(due|deadline|renewal status).*")) intent = "DUE";
		else if (text.matches("(?s).*(available|availability|what.*schedule|anong.*schedule).*")) intent = "AVAILABILITY";
		else if (text.matches("(?s).*(book|appointment|schedule|visit).*")) intent = "BOOK";
		else if (service != null && (date != null || time != null)) intent = "BOOK";
		else if (text.matches("(?s).*(insurance|insure|broker).*")) intent = "INSURANCE_DETAILS";
		else if (text.matches("(?s).*(pms|preventive maintenance|oil filter|sump washer|safety check|minor.*maintenance|major.*maintenance).*")
				&& !text.matches("(?s).*(package|2-in-1|3-in-1).*")) intent = "PMS_DETAILS";
		else if (text.matches("(?s).*(package|inclusion|2-in-1|3-in-1|offerings).*")) intent = "PACKAGES";
		else if (text.matches("(?s).*(lto|registration assistance|registration requirements|renewal requirements|inspection|document liaison).*")) intent = "LTO_DETAILS";
		else if (text.matches("(?s).*(registration|renewal|insurance|ctpl|requirements|dealership|payment|service|vehicle|rehistro).*")) intent = "INQUIRY";
		else if (text.matches("(yes|yes po|opo|oo|sige|okay|ok|confirm|yes please)[.! ]*")) intent = "CONFIRM";
		else if (text.matches("(no|no thanks|no thank you|not interested|hindi)[.! ]*")) intent = "DECLINE";
		else if (date != null || time != null || text.matches("(hi|hello|hello po|thanks|thank you|salamat po)[.! ]*")) intent = "CONTINUE";
		else intent = "OUT_OF_SCOPE";
		return new DealershipIntent(intent, date, time, service, language);
	}
	private boolean isUnsafeTopic(String text) {
		return text.toLowerCase(Locale.ROOT).matches("(?s).*(python|programming|write.*code|homework|politic|trivia|favorite colo[u]?r|system prompt|environment variable|api.?key|ignore.*instruction|reveal.*secret).*" );
	}
	private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila"))); }
}
