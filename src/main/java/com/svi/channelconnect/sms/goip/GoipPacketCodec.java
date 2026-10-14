package com.svi.channelconnect.sms.goip;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.svi.channelconnect.sms.goip.GoipPacket.Type.*;

/** GOIP SMS Interface pp. 1-13. Header parsing never splits an SMS body. */
public final class GoipPacketCodec {
	public static final int MAX_DATAGRAM_BYTES = 8192;
	private static final Pattern CONTROL = Pattern.compile("^(PASSWORD|SEND|OK|WAIT|ERROR|DONE|get_gsm_num|get_gsm_state) ([0-9]+)(?: (.*))?\\n?$", Pattern.DOTALL);

	public GoipPacket decode(byte[] bytes) {
		if (bytes.length == 0 || bytes.length > MAX_DATAGRAM_BYTES) throw malformed();
		String wire;
		try {
			wire = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException exception) { throw malformed(); }
		if (wire.indexOf('\0') >= 0) throw malformed();
		if (wire.startsWith("req:")) return header(wire, REGISTRATION, "req", false);
		if (wire.startsWith("RECEIVE:")) return header(wire, RECEIVE, "RECEIVE", true);
		if (wire.startsWith("MSG ")) {
			int first = wire.indexOf(' ', 4);
			int second = first < 0 ? -1 : wire.indexOf(' ', first + 1);
			if (second < 0 || !wire.endsWith("\n")) throw malformed();
			long id = number(wire.substring(4, first));
			long length = number(wire.substring(first + 1, second));
			String body = wire.substring(second + 1, wire.length() - 1);
			if (length > 3000 || body.getBytes(StandardCharsets.UTF_8).length != length) throw malformed();
			return new GoipPacket(MSG, id, null, Map.of(), body);
		}
		String control = wire.endsWith("\n") ? wire.substring(0, wire.length() - 1) : wire;
		if (control.indexOf('\n') >= 0 || control.indexOf('\r') >= 0) throw malformed();
		var matcher = CONTROL.matcher(control);
		if (!matcher.matches()) throw malformed();
		long id = number(matcher.group(2));
		String tail = matcher.group(3);
		var type = switch (matcher.group(1)) {
			case "get_gsm_num" -> GSM_NUMBER;
			case "get_gsm_state" -> GSM_STATE;
			default -> GoipPacket.Type.valueOf(matcher.group(1));
		};
		Long recipient = null;
		String body = tail;
		if (type == OK || type == WAIT) {
			if (tail == null || tail.contains(" ")) throw malformed();
			recipient = number(tail); body = null;
		} else if (type == SEND && tail != null) {
			int split = tail.indexOf(' ');
			if (split < 1 || !tail.substring(split + 1).matches("\\+?[0-9]{8,15}")) throw malformed();
			recipient = number(tail.substring(0, split)); body = tail.substring(split + 1);
		} else if (type == ERROR && tail != null && tail.matches("[0-9]+ errorstatus:\\s*[0-9]+")) {
			recipient = number(tail.substring(0, tail.indexOf(' ')));
			body = tail.substring(tail.indexOf(':') + 1).strip();
		} else if (type == DONE && tail != null || type == ERROR && tail == null) throw malformed();
		return new GoipPacket(type, id, recipient, Map.of(), body);
	}

	private GoipPacket header(String wire, GoipPacket.Type type, String firstKey, boolean hasBody) {
		Map<String, String> fields = new LinkedHashMap<>();
		String body = null;
		int offset = 0;
		while (offset < wire.length()) {
			int colon = wire.indexOf(':', offset);
			if (colon <= offset) throw malformed();
			String key = wire.substring(offset, colon);
			if (!key.matches("[A-Za-z_]+")) throw malformed();
			if (hasBody && key.equals("msg")) { body = wire.substring(colon + 1); break; }
			int semi = wire.indexOf(';', colon + 1);
			if (semi < 0) throw malformed();
			String value = wire.substring(colon + 1, semi);
			if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || fields.putIfAbsent(key, value) != null) throw malformed();
			offset = semi + 1;
		}
		if (!fields.containsKey(firstKey) || !fields.containsKey("id") || fields.get("id").isBlank()
				|| fields.containsKey("pass") == fields.containsKey("password")) throw malformed();
		String password = fields.getOrDefault("pass", fields.get("password"));
		if (password.isEmpty()) throw malformed();
		if (hasBody && (body == null || !fields.containsKey("srcnum"))) throw malformed();
		return new GoipPacket(type, number(fields.get(firstKey)), null, fields, body);
	}

	public byte[] message(long id, String content) {
		int length = content.getBytes(StandardCharsets.UTF_8).length;
		if (length == 0 || length > 3000) throw malformed();
		return utf8("MSG " + positive(id) + " " + length + " " + content + "\n");
	}
	public byte[] password(long id, String password) {
		if (password == null || !password.matches("[^\\s;:\\p{Cntrl}]{1,128}")) throw malformed();
		return utf8("PASSWORD " + positive(id) + " " + password + "\n");
	}
	public byte[] send(long id, long sequence, String number) {
		if (number == null || !number.matches("\\+?[0-9]{8,15}")) throw malformed();
		return utf8("SEND " + positive(id) + " " + positive(sequence) + " " + number + "\n");
	}
	public byte[] done(long id) { return utf8("DONE " + positive(id) + "\n"); }
	public byte[] registrationAck(long count) { return utf8("reg:" + positive(count) + ";status:0;"); }
	public byte[] receiveAck(long id) { return utf8("RECEIVE " + positive(id) + " OK\n"); }
	public byte[] query(long id, boolean number, String password) {
		password(id, password);
		return utf8((number ? "get_gsm_num " : "get_gsm_state ") + positive(id) + " " + password);
	}
	private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
	private static long positive(long value) { if (value < 1 || value > Integer.MAX_VALUE) throw malformed(); return value; }
	private static long number(String value) {
		try { if (value == null || !value.matches("[0-9]{1,10}")) throw malformed(); return positive(Long.parseLong(value)); }
		catch (NumberFormatException exception) { throw malformed(); }
	}
	private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed GoIP packet"); }
}
