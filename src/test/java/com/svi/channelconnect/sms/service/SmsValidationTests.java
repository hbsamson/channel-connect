package com.svi.channelconnect.sms.service;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import com.svi.channelconnect.support.SmsTestProperties;
import com.svi.channelconnect.sms.exception.SmsException;

class SmsValidationTests {
	@Test void normalizesPhilippineMobileFormsAndRejectsOtherRecipients() {
		for (String raw : new String[] {"09000000001", "639000000001", "+63 (900) 000-0001", "9000000001"}) {
			assertThat(SmsValidation.phone(raw)).isEqualTo("+639000000001");
		}
		for (String raw : new String[] {"+15550000001", "+63900000000x", "++639000000001", "09123"}) {
			assertThatThrownBy(() -> SmsValidation.phone(raw)).isInstanceOf(SmsException.class);
		}
	}
	@Test void distinguishesGsmExtensionsUnicodeAndTheBulkByteLimit() {
		var single = SmsTestProperties.properties(Map.of());
		SmsValidation.content("a".repeat(160), single);
		SmsValidation.content("^".repeat(80), single);
		SmsValidation.content("漢".repeat(70), single);
		for (String text : new String[] {"a".repeat(161), "^".repeat(81), "漢".repeat(71), "😀", "\0"}) {
			assertThatThrownBy(() -> SmsValidation.content(text, single)).isInstanceOf(SmsException.class);
		}
		var multi = SmsTestProperties.properties(Map.of("sms.max-segments", "2"));
		SmsValidation.content("a".repeat(306), multi);
		assertThatThrownBy(() -> SmsValidation.content("a".repeat(307), multi)).isInstanceOf(SmsException.class);
	}
}
