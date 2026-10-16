package com.svi.channelconnect.telegram.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.svi.channelconnect.telegram.config.TelegramCampaignProperties;
import com.svi.channelconnect.telegram.exception.TelegramCampaignException;

/** Bounded, process-local request reservations. Pending/unknown outcomes never expire. */
public class TelegramRequestIdempotencyStore<TRequest, TResponse> {
	private final Map<String, Entry<TRequest, TResponse>> entries = new HashMap<>();
	private final TelegramCampaignProperties properties;
	private final Clock clock;

	public TelegramRequestIdempotencyStore(TelegramCampaignProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public synchronized TResponse reserve(String key, TRequest request) {
		if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
			throw new TelegramCampaignException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key is required");
		}
		Instant now = clock.instant();
		entries.entrySet().removeIf(item -> item.getValue().expiresAt() != null
				&& !item.getValue().expiresAt().isAfter(now));
		Entry<TRequest, TResponse> existing = entries.get(key);
		if (existing != null) {
			if (!existing.request().equals(request)) {
				throw new TelegramCampaignException(HttpStatus.CONFLICT, "Idempotency-Key was used with another request");
			}
			if (existing.response() == null) {
				throw new TelegramCampaignException(HttpStatus.CONFLICT,
						"Request is pending or its outcome is unknown; inspect message or campaign status before retrying");
			}
			return existing.response();
		}
		if (entries.size() >= properties.idempotencyCapacity()) {
			throw new TelegramCampaignException(HttpStatus.SERVICE_UNAVAILABLE, "Outbound request storage is full");
		}
		entries.put(key, new Entry<>(request, null, null));
		return null;
	}

	public synchronized void complete(String key, TResponse response) {
		entries.computeIfPresent(key, (ignored, entry) -> new Entry<>(entry.request(), response,
				clock.instant().plus(properties.idempotencyTtl())));
	}

	public synchronized void release(String key) {
		entries.remove(key);
	}

	private record Entry<TRequest, TResponse>(TRequest request, TResponse response, Instant expiresAt) { }
}
