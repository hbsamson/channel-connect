package com.svi.channelconnect.sms.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.exception.SmsException;

public final class SmsValidation {
	// GSM 03.38 default and extension alphabets. Extension characters use two septets.
	private static final String GSM = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
	private static final String EXTENDED = "\f^{}\\[~]|€";
	private SmsValidation() { }
	public static String phone(String raw) {
		if (raw == null || raw.length() > 32 || !raw.matches("[+0-9 ()-]+")) throw invalid();
		String value = raw.replace(" ", "").replace("(", "").replace(")", "").replace("-", "");
		if (value.matches("09\\d{9}")) value = "+63" + value.substring(1);
		else if (value.matches("639\\d{9}")) value = "+" + value;
		else if (value.matches("9\\d{9}")) value = "+63" + value;
		if (!value.matches("\\+639\\d{9}")) throw invalid();
		return value;
	}
	public static void content(String value, SmsProperties properties) {
		if (value == null || value.isBlank() || value.indexOf('\0') >= 0
				|| value.getBytes(StandardCharsets.UTF_8).length > properties.maxContentBytes()) throw invalid();
		int septets = 0;
		boolean gsm = true;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			// This firmware's non-BMP support is undocumented; never split or silently replace a surrogate.
			if (Character.isSurrogate(c)) throw invalid();
			if (GSM.indexOf(c) >= 0) septets++;
			else if (EXTENDED.indexOf(c) >= 0) septets += 2;
			else gsm = false;
		}
		int segments = gsm ? (septets <= 160 ? 1 : (septets + 152) / 153)
				: (value.length() <= 70 ? 1 : (value.length() + 66) / 67);
		if (segments > properties.maxSegments()) throw invalid();
	}
	public static String key(String value) {
		if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid(); return value;
	}
	public static String fingerprint(String... fields) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String field : fields) {
				byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
				digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
	}
	private static SmsException invalid() { return new SmsException(SmsException.Reason.INVALID); }
}
