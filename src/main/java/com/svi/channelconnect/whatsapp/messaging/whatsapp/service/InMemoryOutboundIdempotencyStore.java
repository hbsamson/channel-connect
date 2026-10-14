package com.svi.channelconnect.whatsapp.messaging.whatsapp.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.whatsapp.messaging.whatsapp.config.OutboundMessagingProperties;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.response.OutboundMessageResponse;

@Component
@Profile({"local", "test"})
public class InMemoryOutboundIdempotencyStore implements OutboundIdempotencyStore {
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final OutboundMessagingProperties properties;
	private final Clock clock;

	@Autowired
	public InMemoryOutboundIdempotencyStore(OutboundMessagingProperties properties) {
		this(properties, Clock.systemUTC());
	}

	InMemoryOutboundIdempotencyStore(OutboundMessagingProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public synchronized Reservation reserve(String idempotencyKey, String requestFingerprint) {
		Instant now = clock.instant();
		removeExpired(now);
		Entry existing = entries.get(idempotencyKey);
		if (existing != null) {
			if (!existing.requestFingerprint().equals(requestFingerprint)) {
				return Reservation.of(ReservationStatus.CONFLICT);
			}
			return switch (existing.state()) {
				case PENDING -> Reservation.of(ReservationStatus.IN_PROGRESS);
				case COMPLETE -> Reservation.replay(existing.response());
				case INDETERMINATE -> Reservation.of(ReservationStatus.INDETERMINATE);
			};
		}
		if (entries.size() >= properties.idempotencyCapacity()) {
			return Reservation.of(ReservationStatus.OVERLOADED);
		}
		entries.put(idempotencyKey, new Entry(
				requestFingerprint, State.PENDING, null, now.plus(properties.idempotencyTtl())));
		return Reservation.of(ReservationStatus.RESERVED);
	}

	@Override
	public synchronized void complete(String idempotencyKey, OutboundMessageResponse response) {
		entries.computeIfPresent(idempotencyKey, (key, entry) -> entry.with(State.COMPLETE, response));
	}

	@Override
	public synchronized void markIndeterminate(String idempotencyKey) {
		entries.computeIfPresent(idempotencyKey,
				(key, entry) -> entry.with(State.INDETERMINATE, null));
	}

	@Override
	public synchronized void release(String idempotencyKey) {
		entries.remove(idempotencyKey);
	}

	private void removeExpired(Instant now) {
		Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry entry = iterator.next().getValue();
			if (entry.state() != State.PENDING && !entry.expiresAt().isAfter(now)) {
				iterator.remove();
			}
		}
	}

	private enum State {
		PENDING,
		COMPLETE,
		INDETERMINATE
	}

	private record Entry(
			String requestFingerprint,
			State state,
			OutboundMessageResponse response,
			Instant expiresAt
	) {
		Entry with(State newState, OutboundMessageResponse newResponse) {
			return new Entry(requestFingerprint, newState, newResponse, expiresAt);
		}
	}
}
