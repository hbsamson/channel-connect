package com.svi.channelconnect.whatsapp.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.whatsapp.dto.request.WhatsAppWebhookRequest;
import com.svi.channelconnect.whatsapp.exception.MalformedWebhookException;
import com.svi.channelconnect.whatsapp.mapper.WhatsAppMessageMapper;
import com.svi.channelconnect.whatsapp.model.DispatchResult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WhatsAppWebhookService {
	private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppWebhookService.class);
	private static final String WHATSAPP_OBJECT = "whatsapp_business_account";

	private final ObjectMapper objectMapper;
	private final WhatsAppMessageMapper mapper;
	private final InboundMessageDispatcher dispatcher;
	private final MeterRegistry meterRegistry;
	private final com.svi.channelconnect.dealership.persistence.DealershipRepository conversations;
	private final String accountId;

	public WhatsAppWebhookService(ObjectMapper objectMapper, WhatsAppMessageMapper mapper,
			InboundMessageDispatcher dispatcher, MeterRegistry meterRegistry,
			com.svi.channelconnect.dealership.persistence.DealershipRepository conversations,
			com.svi.channelconnect.whatsapp.config.WhatsAppProperties properties) {
		this.objectMapper = objectMapper;
		this.mapper = mapper;
		this.dispatcher = dispatcher;
		this.meterRegistry = meterRegistry;
		this.conversations = conversations;
		this.accountId = properties.phoneNumberId();
	}

	public WebhookHandlingResult handle(byte[] rawBody) {
		WhatsAppWebhookRequest webhook = parse(rawBody);
		if (!WHATSAPP_OBJECT.equals(webhook.object()) || webhook.entry() == null) {
			throw new MalformedWebhookException("Unexpected webhook envelope");
		}

		WhatsAppMessageMapper.MappingResult mapping = mapper.map(webhook);
		for (var entry : webhook.entry()) {
			if (entry == null || entry.changes() == null) continue;
			for (var change : entry.changes()) {
				if (change == null || !"messages".equals(change.field()) || change.value() == null) continue;
				var value = change.value();
				if (!"whatsapp".equals(value.messagingProduct()) || value.metadata() == null
						|| !accountId.equals(value.metadata().phoneNumberId()) || value.statuses() == null) continue;
				for (var status : value.statuses()) if (status != null && status.id() != null && status.status() != null) {
					conversations.delivery("WHATSAPP", accountId, status.id(), status.status(), System.currentTimeMillis());
				}
			}
		}
		int accepted = 0;
		int duplicates = 0;
		int overloaded = 0;
		for (var message : mapping.messages()) {
			DispatchResult result = dispatcher.dispatch(message);
			switch (result) {
				case ACCEPTED -> accepted++;
				case DUPLICATE -> duplicates++;
				case OVERLOADED -> overloaded++;
			}
		}

		meterRegistry.counter("whatsapp.webhook.events", "type", "status")
				.increment(mapping.statusEvents());
		meterRegistry.counter("whatsapp.webhook.events", "type", "unsupported")
				.increment(mapping.unsupportedEvents());
		meterRegistry.counter("whatsapp.webhook.events", "type", "invalid")
				.increment(mapping.invalidEvents());
		LOGGER.info("WhatsApp webhook handled accepted={} duplicates={} overloaded={} statuses={} unsupported={} invalid={}",
				accepted, duplicates, overloaded, mapping.statusEvents(), mapping.unsupportedEvents(), mapping.invalidEvents());

		return new WebhookHandlingResult(
				accepted, duplicates, overloaded,
				mapping.statusEvents(), mapping.unsupportedEvents(), mapping.invalidEvents());
	}

	private WhatsAppWebhookRequest parse(byte[] rawBody) {
		try {
			return objectMapper.readValue(rawBody, WhatsAppWebhookRequest.class);
		}
		catch (JacksonException exception) {
			throw new MalformedWebhookException("Webhook JSON could not be parsed", exception);
		}
	}

	public record WebhookHandlingResult(
			int accepted,
			int duplicates,
			int overloaded,
			int statuses,
			int unsupported,
			int invalid
	) {
	}
}
