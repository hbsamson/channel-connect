package com.svi.channelconnect.whatsapp.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.whatsapp.client.WhatsAppApiClient;
import com.svi.channelconnect.whatsapp.config.OutboundMessagingProperties;
import com.svi.channelconnect.whatsapp.dto.request.OutboundTemplateMessageRequest;
import com.svi.channelconnect.whatsapp.dto.response.OutboundMessageResponse;
import com.svi.channelconnect.whatsapp.exception.DuplicateOutboundSubmissionException;
import com.svi.channelconnect.whatsapp.exception.InvalidOutboundRequestException;
import com.svi.channelconnect.whatsapp.exception.OutboundAuthorizationException;
import com.svi.channelconnect.whatsapp.exception.OutboundCapacityException;
import com.svi.channelconnect.whatsapp.exception.WhatsAppApiException;

@Service
@Profile({"local", "test"})
public class OutboundMessageService {
	private static final Logger log = LoggerFactory.getLogger(OutboundMessageService.class);
	private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._:-]{1,128}";

	private final WhatsAppApiClient whatsAppApiClient;
	private final OutboundMessagingProperties properties;
	private final OutboundIdempotencyStore idempotencyStore;

	public OutboundMessageService(WhatsAppApiClient whatsAppApiClient,
			OutboundMessagingProperties properties,
			OutboundIdempotencyStore idempotencyStore) {
		this.whatsAppApiClient = whatsAppApiClient;
		this.properties = properties;
		this.idempotencyStore = idempotencyStore;
	}

	public OutboundMessageResponse sendTemplateMessage(String idempotencyKey,
			OutboundTemplateMessageRequest request) {
		validateIdempotencyKey(idempotencyKey);
		if (!properties.allowedRecipients().contains(request.recipient())
				|| !properties.allowedTemplates().contains(request.templateName())) {
			throw new OutboundAuthorizationException();
		}

		OutboundIdempotencyStore.Reservation reservation = idempotencyStore.reserve(
				idempotencyKey, fingerprint(request));
		switch (reservation.status()) {
			case REPLAY -> {
				return reservation.response().asReplay();
			}
			case CONFLICT -> throw new DuplicateOutboundSubmissionException(
					"Idempotency key was already used for a different request");
			case IN_PROGRESS -> throw new DuplicateOutboundSubmissionException(
					"Outbound submission with this idempotency key is still in progress");
			case INDETERMINATE -> throw new DuplicateOutboundSubmissionException(
					"Previous submission outcome is unknown; automatic resend is blocked");
			case OVERLOADED -> throw new OutboundCapacityException();
			case RESERVED -> {
				// Continue with the single provider call.
			}
		}

		try {
			String providerMessageId = whatsAppApiClient.sendTemplateMessage(
					request.recipient(), request.templateName(), request.languageCode(), request.parameters());
			OutboundMessageResponse response = OutboundMessageResponse.accepted(providerMessageId);
			idempotencyStore.complete(idempotencyKey, response);
			log.info("Outbound WhatsApp template accepted; recipientHash={}, template={}, messageIdHash={}",
					hashForLog(request.recipient()), request.templateName(), hashForLog(providerMessageId));
			return response;
		}
		catch (WhatsAppApiException exception) {
			if (isAmbiguous(exception.category())) {
				idempotencyStore.markIndeterminate(idempotencyKey);
			}
			else {
				idempotencyStore.release(idempotencyKey);
			}
			throw exception;
		}
		catch (RuntimeException exception) {
			idempotencyStore.markIndeterminate(idempotencyKey);
			throw exception;
		}
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (!StringUtils.hasText(idempotencyKey)
				|| !idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN)) {
			throw new InvalidOutboundRequestException(
					"Idempotency-Key must contain 1-128 letters, digits, periods, underscores, colons, or hyphens");
		}
	}

	private boolean isAmbiguous(ExternalFailureCategory category) {
		return switch (category) {
			case TIMEOUT, TRANSIENT, MALFORMED_RESPONSE, PERMANENT -> true;
			case INVALID_REQUEST, AUTHENTICATION, THROTTLED -> false;
		};
	}

	private String fingerprint(OutboundTemplateMessageRequest request) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, request.recipient());
			update(digest, request.templateName());
			update(digest, request.languageCode());
			request.parameters().forEach(parameter -> update(digest, parameter));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private String hashForLog(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)), 0, 6);
		}
		catch (NoSuchAlgorithmException exception) {
			return "unavailable";
		}
	}
}
