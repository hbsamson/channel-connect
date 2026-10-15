package com.svi.channelconnect.sms.goip;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.sms.service.SmsValidation;

/** Reads firmware data literals only; never evaluates JavaScript or follows page links. */
final class GoipWebInboxParser {
	private static final Pattern TIME = Pattern.compile("id=[\"']time_text[\"'][^>]*>\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
	private static final Pattern LINE = Pattern.compile(
			"\\bsms\\s*=\\s*(\\[(?:\\s*\"(?:\\\\.|[^\"\\\\])*+\"\\s*,?)*\\s*\\])\\s*;\\s*"
			+ "sms_row_insert\\(l([1-8])_sms_store,\\s*sms,\\s*pos,\\s*\\2\\s*\\);");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
	private final ObjectMapper json;

	GoipWebInboxParser(ObjectMapper json) { this.json = json; }

	List<Message> parse(String html, ZoneId zone) {
		var time = TIME.matcher(html);
		if (!time.find()) throw new IllegalArgumentException("GoIP inbox device time is missing");
		LocalDateTime deviceTime = LocalDateTime.parse(time.group(1), DATE);
		var lines = LINE.matcher(html);
		var seenLines = new HashSet<Integer>();
		List<Message> messages = new ArrayList<>();
		while (lines.find()) {
			int line = Integer.parseInt(lines.group(2));
			if (!seenLines.add(line)) throw new IllegalArgumentException("Duplicate GoIP inbox line");
			var entries = json.readTree(lines.group(1));
			if (!entries.isArray() || entries.size() > 20) throw new IllegalArgumentException("Invalid GoIP inbox array");
			for (var entry : entries) {
				if (!entry.isString()) throw new IllegalArgumentException("Invalid GoIP inbox entry");
				String record = entry.stringValue();
				if (record.isEmpty()) continue;
				String[] fields = record.split(",", 3);
				if (fields.length != 3) throw new IllegalArgumentException("Invalid GoIP inbox fields");
				LocalDateTime received = LocalDateTime.parse(deviceTime.getYear() + "-" + fields[0], DATE);
				// The firmware omits the year; dates ahead of its current clock belong to last year.
				if (received.isAfter(deviceTime)) received = LocalDateTime.parse((deviceTime.getYear() - 1) + "-" + fields[0], DATE);
				String sender = HtmlUtils.htmlUnescape(fields[1]).replace("\r", "").replace("\n", "").trim();
				String content = HtmlUtils.htmlUnescape(fields[2]);
				long receivedAt = received.atZone(zone).toInstant().toEpochMilli();
				// Slot positions change whenever new messages arrive, so they cannot be receipt IDs.
				long id = Long.parseLong(SmsValidation.fingerprint(Integer.toString(line), received.toString(), sender, content).substring(0, 15), 16) + 1;
				messages.add(new Message(line, id, sender, content, receivedAt));
			}
		}
		if (seenLines.size() != 8) throw new IllegalArgumentException("Incomplete GoIP8 inbox page");
		return messages;
	}

	record Message(int line, long id, String sender, String content, long receivedAt) {
		@Override public String toString() { return "GoipInboxMessage[line=" + line + ", content=<redacted>]"; }
	}
}
