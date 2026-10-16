package com.svi.channelconnect.telegram.service;

import java.time.Clock;

import com.svi.channelconnect.telegram.config.TelegramCampaignProperties;
import com.svi.channelconnect.telegram.dto.request.TelegramCampaignStartRequest;
import com.svi.channelconnect.telegram.dto.response.TelegramCampaignStartResponse;

/** Bounded and process-local. In-flight reservations never expire. */
public class TelegramCampaignIdempotencyStore {
	private final TelegramRequestIdempotencyStore<TelegramCampaignStartRequest, TelegramCampaignStartResponse> requests;

	public TelegramCampaignIdempotencyStore(TelegramCampaignProperties properties, Clock clock) {
		this.requests = new TelegramRequestIdempotencyStore<>(properties, clock);
	}

	public TelegramCampaignStartResponse reserve(String key, TelegramCampaignStartRequest request) {
		return requests.reserve(key, request);
	}

	public void complete(String key, TelegramCampaignStartResponse response) {
		requests.complete(key, response);
	}

	public void release(String key) {
		requests.release(key);
	}
}
