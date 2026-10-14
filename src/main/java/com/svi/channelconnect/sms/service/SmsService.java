package com.svi.channelconnect.sms.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.model.SmsSubmission;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;

@Service
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
public class SmsService {
	private final SmsProperties properties;
	private final SmsMessageRepository store;
	private final DealershipRepository customers;
	public SmsService(SmsProperties properties, SmsMessageRepository store, DealershipRepository customers) {
		this.properties = properties; this.store = store; this.customers = customers;
	}
	public SmsSubmission submit(String key, String account, String recipient, String content) {
		return submitWorkflow("api:" + SmsValidation.key(key), account, recipient, content, null);
	}
	public SmsSubmission submitWorkflow(String key, String account, String recipient, String content, String dealerMessageId) {
		String normalized = validate(account, recipient, content);
		var identity = customers.identity("SMS", account, normalized);
		if (identity.isPresent() && (customers.optedOut(identity.get().customerId()) || !identity.get().verified()
				|| !identity.get().consent() || !identity.get().outboundAllowed())) throw new SmsException(SmsException.Reason.FORBIDDEN);
		return store.enqueue(key, account, normalized, content, dealerMessageId);
	}
	public String validate(String account, String recipient, String content) {
		try { properties.channel(account); } catch (IllegalArgumentException exception) { throw new SmsException(SmsException.Reason.FORBIDDEN); }
		String normalized = SmsValidation.phone(recipient);
		SmsValidation.content(content, properties); return normalized;
	}
	public boolean canInitiate(String account, String recipient) {
		try {
			SmsValidation.phone(recipient); properties.channel(account);
			return true;
		} catch (RuntimeException exception) { return false; }
	}
}
