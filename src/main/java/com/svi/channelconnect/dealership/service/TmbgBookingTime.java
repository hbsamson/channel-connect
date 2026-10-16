package com.svi.channelconnect.dealership.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** Maps customer time expressions to preferred Manila drop-off times, without claiming availability. */
final class TmbgBookingTime {

	static final LocalTime OPEN = LocalTime.of(8, 0);
	static final LocalTime CLOSE = LocalTime.of(14, 0);
	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
	private static final Pattern TIME = Pattern.compile(
			"(?i)(?<![\\w:/-])(?:(?:at|by|alas|sa)\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*"
			+ "(a\\.?\\s*m\\.?|p\\.?\\s*m\\.?|(?:in the |ng |sa )?(?:morning|afternoon|umaga|hapon))?(?![\\w:/-])");
	private static final Pattern NOON = Pattern.compile("(?i)\\b(?:12\\s*)?(?:noon|midday|tanghali)\\b");

	private TmbgBookingTime() { }

	static Choice parse(String text, boolean timePending) {
		var noon = NOON.matcher(text);
		if (noon.find()) {
			String date = text.substring(0, noon.start()) + text.substring(noon.end());
			if (noon.find() || parse(date, false).mentioned()) return new Choice(null, text, true);
			return new Choice(LocalTime.NOON, cleanDate(date), true);
		}
		var matcher = TIME.matcher(text);
		Choice choice = new Choice(null, text, false);
		while (matcher.find()) {
			boolean marked = matcher.group(2) != null || matcher.group(3) != null
					|| matcher.group().matches("(?i)^(?:at|by|alas|sa)\\s+.*");
			String remainder = text.substring(0, matcher.start()) + text.substring(matcher.end());
			if (!marked && (!timePending || !cleanDate(remainder).isBlank())) continue;
			if (choice.mentioned()) return new Choice(null, text, true);
			int hour = Integer.parseInt(matcher.group(1));
			int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
			String period = matcher.group(3);
			if (minute > 59 || hour > 23) return new Choice(null, cleanDate(remainder), true);
			if (period != null) {
				if (hour < 1 || hour > 12) return new Choice(null, cleanDate(remainder), true);
				boolean afternoon = period.toLowerCase(Locale.ROOT).matches("p.*|.*(?:afternoon|hapon).*");
				hour = hour % 12 + (afternoon ? 12 : 0);
			} else if ((hour == 1 || hour == 2) && !matcher.group(1).startsWith("0")) {
				// Only the afternoon occurrence falls within the supplied drop-off window.
				hour += 12;
			}
			choice = new Choice(LocalTime.of(hour, minute), cleanDate(remainder), true);
		}
		return choice;
	}

	static boolean valid(LocalTime time) {
		return time != null && !time.isBefore(OPEN) && !time.isAfter(CLOSE);
	}

	static String label(LocalTime time) { return LABEL.format(time); }

	private static String cleanDate(String text) {
		return text.trim().replaceAll("(?i)^(?:(?:yes|okay|ok|sige|oo|opo|please|at|by|sa)\\b[, ]*)+", "")
				.replaceFirst("(?i)^(?:i (?:prefer|want)|i(?:'d| would) like|my preferred time is|make it|let's do)\\s*", "")
				.replaceAll("(?i)(?:[, ]*(?:po|please|na lang|works for me|is fine|instead|at|by))+$", "")
				.replaceAll("^[,.! ]+|[,.! ]+$", "").trim();
	}

	record Choice(LocalTime time, String dateText, boolean mentioned) { }
}
