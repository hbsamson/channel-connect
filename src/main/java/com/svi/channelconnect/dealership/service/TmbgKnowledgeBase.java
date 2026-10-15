package com.svi.channelconnect.dealership.service;

import java.io.IOException;
import java.util.Locale;

import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Approved general answers only; excludes fictional hours, prices and customer records. */
final class TmbgKnowledgeBase {
	private final JsonNode answers;

	TmbgKnowledgeBase(ResourceLoader resources, ObjectMapper json) throws IOException {
		try (var stream = resources.getResource("classpath:dealership/catalog.json").getInputStream()) {
			answers = json.readTree(stream).path("cannedAnswers");
		}
	}

	JsonNode answers() { return answers; }

	String answer(String topic, String language, String channel) {
		JsonNode approved = answers.path(topic);
		String field = channel.equals("SMS") ? language.equals("tl") ? "smsTaglish" : "smsEnglish"
				: language.equals("tl") ? "taglish" : "english";
		return approved.path(field).isString() ? approved.path(field).asString() : null;
	}

	boolean bookingChange(String text) {
		String value = text.toLowerCase(Locale.ROOT);
		return value.matches("(?s).*\\b(reschedul\\w*|cancel\\w*|i-?cancel|another date|different date|ibang (?:araw|petsa|date))\\b.*")
				|| value.matches("(?s).*\\b(move|change|switch|palit\\w*|lipat\\w*|ilipat\\w*|baguhin)\\b.*\\b(appointment|booking|date|schedule|petsa|araw|oras)\\b.*")
				|| value.matches("(?s).*\\b(appointment|booking|schedule)\\b.*\\b(move|change|switch|palit\\w*|lipat\\w*|baguhin)\\b.*");
	}

	String mockTopic(String text) {
		String value = text.toLowerCase(Locale.ROOT);
		if (value.matches("(?s).*\\b(book|reserve|schedule|reschedule|cancel|choose|select)\\b.*")) return "";
		if (!value.contains("?") && !value.matches("(?s).*\\b(why|what|how|tell|explain|bakit|ano|anong|paano|magkano|mahal|expensive|cheaper)\\b.*")) return "";
		if (value.matches("(?s).*\\b(why|choose|pick|trust|bakit|value|advantage|benefit)\\b.*")) return "VALUE";
		if (value.matches("(?s).*\\b(expensive|mahal|cheaper|price concern)\\b.*")) return "PRICE_OBJECTION";
		if (value.matches("(?s).*\\b(essential|add-ons|optional work)\\b.*")) return "ESSENTIAL_QUOTE";
		if (value.matches("(?s).*\\b(wait|waiting|turnaround|express maintenance)\\b.*")) return "WAIT_TIME";
		if (value.matches("(?s).*\\b(broker|deductible)\\b.*")) return "INSURANCE_OBJECTION";
		if (value.matches("(?s).*\\b(insurance|insure|claims|coverage)\\b.*")) return "INSURANCE_DETAILS";
		if (value.matches("(?s).*\\b(bundle|3-in-1|one visit)\\b.*")) return "BUNDLE";
		if (value.matches("(?s).*\\b(diy|ltms|myself)\\b.*")) return "LTO_DIY";
		if (value.matches("(?s).*\\b(pms|maintenance|oil|filter|parts|technician\\w*)\\b.*")) return "PMS_DETAILS";
		if (value.matches("(?s).*\\b(concierge|registration assistance|lto assistance)\\b.*")) return "LTO_DETAILS";
		return "";
	}
}
