package com.svi.channelconnect.sms.service;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;
import com.svi.channelconnect.sms.model.SmsInbound;
import com.svi.channelconnect.sms.model.SmsSubmission;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;

/** SMS transport and receipt handling for the booking demo. */
@Service
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
public class SmsTmbgDemoService {
	private final TmbgBookingAssistant assistant;
	private final SmsService sms;
	private final SmsMessageRepository messages;

	public SmsTmbgDemoService(TmbgBookingAssistant assistant, SmsService sms, SmsMessageRepository messages) {
		this.assistant = assistant;
		this.sms = sms;
		this.messages = messages;
	}

	public synchronized SmsSubmission start(String account, String recipient) {
		String phone = SmsValidation.phone(recipient == null || recipient.isBlank() ? assistant.customerPhone() : recipient);
		String greeting = assistant.greeting("SMS", phone);
		SmsSubmission submission = sms.submit("tmbg-open:" + UUID.randomUUID(), account, phone, greeting);
		assistant.start("SMS", account, phone);
		return submission;
	}

	public boolean accepts(String account, String sender) {
		return assistant.accepts("SMS", account, sender);
	}

	public boolean handles(SmsInbound incoming) {
		return incoming.status().equals("PROCESSING")
				&& (isReset(incoming.content()) || accepts(incoming.accountId(), incoming.sender()));
	}

	public synchronized void process(SmsInbound incoming) {
		if (!handles(incoming)) return;
		boolean reset = isReset(incoming.content());
		String reply = reset ? assistant.greeting("SMS", incoming.sender())
				: assistant.reply("SMS", incoming.accountId(), incoming.sender(), incoming.id(), incoming.content());
		if (reply != null) {
			sms.validate(incoming.accountId(), incoming.sender(), reply);
			messages.enqueue("reply:" + incoming.id(), incoming.accountId(), incoming.sender(), reply, null);
		}
		if (reset) assistant.start("SMS", incoming.accountId(), incoming.sender());
		messages.completeInbound(incoming.id());
	}

	public static boolean isReset(String content) {
		return "/reset".equalsIgnoreCase(content.trim());
	}
}
