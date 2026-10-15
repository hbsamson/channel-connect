package com.svi.channelconnect.telegram.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.svi.channelconnect.telegram.config.TelegramCampaignProperties;
import com.svi.channelconnect.telegram.dto.request.TelegramCampaignStartRequest;
import com.svi.channelconnect.telegram.dto.response.TelegramCampaignStartResponse;
import com.svi.channelconnect.telegram.exception.TelegramCampaignException;

/** Bounded and process-local. In-flight reservations never expire. */
public class TelegramCampaignIdempotencyStore {
	private final Map<String, Entry> entries = new HashMap<>();
	private final TelegramCampaignProperties properties;
	private final Clock clock;

	public TelegramCampaignIdempotencyStore(TelegramCampaignProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public synchronized TelegramCampaignStartResponse reserve(String key, TelegramCampaignStartRequest request) {
		if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
			throw new TelegramCampaignException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key is required");
		}
		Instant now = clock.instant();
		entries.entrySet().removeIf(item -> item.getValue().expiresAt() != null
				&& !item.getValue().expiresAt().isAfter(now));
		Entry existing = entries.get(key);
		if (existing != null) {
			if (!existing.request().equals(request)) {
				throw new TelegramCampaignException(HttpStatus.CONFLICT, "Idempotency-Key was used with another request");
			}
			if (existing.response() == null) {
				throw new TelegramCampaignException(HttpStatus.CONFLICT,
						"Campaign request is pending or its outcome is unknown; inspect campaign status before retrying");
			}
			return existing.response();
		}
		if (entries.size() >= properties.idempotencyCapacity()) {
			throw new TelegramCampaignException(HttpStatus.SERVICE_UNAVAILABLE, "Campaign request storage is full");
		}
		entries.put(key, new Entry(request, null, null));
		return null;
	}

	public synchronized void complete(String key, TelegramCampaignStartResponse response) {
		entries.computeIfPresent(key, (ignored, entry) -> new Entry(entry.request(), response,
				clock.instant().plus(properties.idempotencyTtl())));
	}

	public synchronized void release(String key) {
		entries.remove(key);
	}

	// Unknown outcomes deliberately retain a pending reservation until process restart.
	private record Entry(TelegramCampaignStartRequest request, TelegramCampaignStartResponse response, Instant expiresAt) { }
}
