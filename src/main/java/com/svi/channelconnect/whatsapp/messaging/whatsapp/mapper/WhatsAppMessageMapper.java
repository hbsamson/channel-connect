package com.svi.channelconnect.whatsapp.messaging.whatsapp.mapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.svi.channelconnect.whatsapp.messaging.whatsapp.config.WhatsAppProperties;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.request.WhatsAppWebhookRequest;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.model.InboundMessage;

@Component
public class WhatsAppMessageMapper {
	private static final int MAXIMUM_MESSAGE_ID_LENGTH = 512;
	private static final int MAXIMUM_TEXT_LENGTH = 4096;

	private final String configuredPhoneNumberId;

	public WhatsAppMessageMapper(WhatsAppProperties properties) {
		this.configuredPhoneNumberId = properties.phoneNumberId();
	}

	public MappingResult map(WhatsAppWebhookRequest webhook) {
		List<InboundMessage> messages = new ArrayList<>();
		int statusEvents = 0;
		int unsupportedEvents = 0;
		int invalidEvents = 0;

		for (WhatsAppWebhookRequest.Entry entry : safe(webhook.entry())) {
			if (entry == null) {
				invalidEvents++;
				continue;
			}
			for (WhatsAppWebhookRequest.Change change : safe(entry.changes())) {
				if (change == null || !"messages".equals(change.field()) || change.value() == null) {
					unsupportedEvents++;
					continue;
				}

				WhatsAppWebhookRequest.Value value = change.value();
				statusEvents += safe(value.statuses()).size();
				if (!isExpectedAccount(value)) {
					invalidEvents += safe(value.messages()).size();
					continue;
				}

				for (WhatsAppWebhookRequest.Message message : safe(value.messages())) {
					MappingOutcome outcome = mapMessage(message, value.metadata());
					if (outcome.message() != null) {
						messages.add(outcome.message());
					}
					else if (outcome.unsupported()) {
						unsupportedEvents++;
					}
					else {
						invalidEvents++;
					}
				}
			}
		}

		return new MappingResult(List.copyOf(messages), statusEvents, unsupportedEvents, invalidEvents);
	}

	private boolean isExpectedAccount(WhatsAppWebhookRequest.Value value) {
		return "whatsapp".equals(value.messagingProduct())
				&& value.metadata() != null
				&& configuredPhoneNumberId.equals(value.metadata().phoneNumberId());
	}

	private MappingOutcome mapMessage(WhatsAppWebhookRequest.Message message,
			WhatsAppWebhookRequest.Metadata metadata) {
		if (message == null || !"text".equals(message.type())) {
			return MappingOutcome.unsupportedEvent();
		}
		if (!StringUtils.hasText(message.id()) || message.id().length() > MAXIMUM_MESSAGE_ID_LENGTH
				|| !isValidWaId(message.from())
				|| message.text() == null || !StringUtils.hasText(message.text().body())
				|| message.text().body().length() > MAXIMUM_TEXT_LENGTH
				|| isBusinessNumber(message.from(), metadata.displayPhoneNumber())) {
			return MappingOutcome.invalidEvent();
		}

		try {
			Instant timestamp = Instant.ofEpochSecond(Long.parseLong(message.timestamp()));
			return MappingOutcome.message(new InboundMessage(
					message.id(), message.from(), message.from(), message.text().body(), timestamp));
		}
		catch (NumberFormatException | DateTimeException exception) {
			return MappingOutcome.invalidEvent();
		}
	}

	private boolean isValidWaId(String value) {
		return value != null && value.matches("\\d{5,20}");
	}

	private boolean isBusinessNumber(String sender, String displayNumber) {
		return StringUtils.hasText(displayNumber)
				&& digitsOnly(sender).equals(digitsOnly(displayNumber));
	}

	private String digitsOnly(String value) {
		return value.chars()
				.filter(Character::isDigit)
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString();
	}

	private <T> List<T> safe(List<T> values) {
		return Objects.requireNonNullElse(values, List.of());
	}

	public record MappingResult(
			List<InboundMessage> messages,
			int statusEvents,
			int unsupportedEvents,
			int invalidEvents
	) {
	}

	private record MappingOutcome(InboundMessage message, boolean unsupported) {
		private static MappingOutcome message(InboundMessage message) {
			return new MappingOutcome(message, false);
		}

		private static MappingOutcome unsupportedEvent() {
			return new MappingOutcome(null, true);
		}

		private static MappingOutcome invalidEvent() {
			return new MappingOutcome(null, false);
		}
	}
}
