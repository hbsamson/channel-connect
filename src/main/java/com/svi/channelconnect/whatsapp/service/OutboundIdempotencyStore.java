package com.svi.channelconnect.whatsapp.service;

import com.svi.channelconnect.whatsapp.dto.response.OutboundMessageResponse;

public interface OutboundIdempotencyStore {
	Reservation reserve(String idempotencyKey, String requestFingerprint);

	void complete(String idempotencyKey, OutboundMessageResponse response);

	void markIndeterminate(String idempotencyKey);

	void release(String idempotencyKey);

	enum ReservationStatus {
		RESERVED,
		REPLAY,
		CONFLICT,
		IN_PROGRESS,
		INDETERMINATE,
		OVERLOADED
	}

	record Reservation(ReservationStatus status, OutboundMessageResponse response) {
		public static Reservation of(ReservationStatus status) {
			return new Reservation(status, null);
		}

		public static Reservation replay(OutboundMessageResponse response) {
			return new Reservation(ReservationStatus.REPLAY, response);
		}
	}
}
