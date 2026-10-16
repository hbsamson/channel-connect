package com.svi.channelconnect.telegram.service;

import com.svi.channelconnect.telegram.dto.request.TelegramWebhookRequest;
import com.svi.channelconnect.telegram.exception.MalformedTelegramWebhookException;
import com.svi.channelconnect.telegram.mapper.TelegramMessageMapper;
import com.svi.channelconnect.telegram.model.TelegramDispatchResult;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public class TelegramWebhookService {
	private final ObjectMapper objectMapper;
	private final TelegramMessageMapper mapper;
	private final TelegramInboundMessageDispatcher dispatcher;
	private final MeterRegistry registry;

	public TelegramWebhookService(ObjectMapper objectMapper, TelegramMessageMapper mapper,
			TelegramInboundMessageDispatcher dispatcher, MeterRegistry registry) {
		this.objectMapper = objectMapper;
		this.mapper = mapper;
		this.dispatcher = dispatcher;
		this.registry = registry;
	}

	public TelegramDispatchResult handle(byte[] rawBody) {
		TelegramWebhookRequest update;
		try {
			update = objectMapper.readValue(rawBody, TelegramWebhookRequest.class);
		}
		catch (JacksonException exception) {
			throw new MalformedTelegramWebhookException();
		}
		var message = mapper.map(update);
		if (message.isEmpty()) {
			registry.counter("telegram.webhook.events", "result", "ignored").increment();
			return TelegramDispatchResult.ACCEPTED;
		}
		return dispatcher.dispatch(message.get());
	}
}
