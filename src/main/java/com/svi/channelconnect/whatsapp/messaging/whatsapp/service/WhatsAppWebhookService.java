package com.svi.channelconnect.whatsapp.messaging.whatsapp.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.request.WhatsAppWebhookRequest;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.MalformedWebhookException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.mapper.WhatsAppMessageMapper;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.model.DispatchResult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WhatsAppWebhookService {
	private static final String WHATSAPP_OBJECT = "whatsapp_business_account";

	private final ObjectMapper objectMapper;
	private final WhatsAppMessageMapper mapper;
	private final InboundMessageDispatcher dispatcher;
	private final MeterRegistry meterRegistry;

	public WhatsAppWebhookService(ObjectMapper objectMapper, WhatsAppMessageMapper mapper,
			InboundMessageDispatcher dispatcher, MeterRegistry meterRegistry) {
		this.objectMapper = objectMapper;
		this.mapper = mapper;
		this.dispatcher = dispatcher;
		this.meterRegistry = meterRegistry;
	}

	public WebhookHandlingResult handle(byte[] rawBody) {
		WhatsAppWebhookRequest webhook = parse(rawBody);
		if (!WHATSAPP_OBJECT.equals(webhook.object()) || webhook.entry() == null) {
			throw new MalformedWebhookException("Unexpected webhook envelope");
		}

		WhatsAppMessageMapper.MappingResult mapping = mapper.map(webhook);
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
