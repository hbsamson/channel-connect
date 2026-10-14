package com.svi.channelconnect.sms.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.dto.response.SmsReceiveResponse;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;

@Service
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
public class SmsReceiveService {
	private static final Logger LOGGER = LoggerFactory.getLogger(SmsReceiveService.class);
	private static final int MAX_WAITERS = 100;
	private final SmsProperties properties;
	private final SmsMessageRepository messages;
	private final Map<String, Poll> pending = new ConcurrentHashMap<>();

	public SmsReceiveService(SmsProperties properties, SmsMessageRepository messages) {
		this.properties = properties;
		this.messages = messages;
	}

	public DeferredResult<SmsReceiveResponse> receive(String account, String sender, long after, int limit, int waitSeconds) {
		if (after < 0 || limit < 1 || limit > 100 || waitSeconds < 0 || waitSeconds > 30) throw new SmsException(SmsException.Reason.INVALID);
		try { properties.channel(account); }
		catch (IllegalArgumentException exception) { throw new SmsException(SmsException.Reason.FORBIDDEN); }
		String participant = sender == null ? null : SmsValidation.phone(sender);
		var result = new DeferredResult<SmsReceiveResponse>(waitSeconds * 1000L);
		var poll = new Poll(account, participant, after, limit, result);
		SmsReceiveResponse available = read(poll);
		if (waitSeconds == 0 || !available.messages().isEmpty()) {
			result.setResult(available);
			return result;
		}
		String id = UUID.randomUUID().toString();
		synchronized (pending) {
			if (pending.size() >= MAX_WAITERS) throw new SmsException(SmsException.Reason.CAPACITY);
			pending.put(id, poll);
		}
		result.onCompletion(() -> pending.remove(id));
		result.onError(exception -> pending.remove(id));
		result.onTimeout(() -> complete(id, poll, true));
		// A receipt may have committed between the first read and registering this waiter.
		complete(id, poll, false);
		return result;
	}

	@Scheduled(fixedDelayString = "${sms.receive-poll-delay-ms:250}")
	public void refresh() {
		pending.forEach((id, poll) -> complete(id, poll, false));
	}

	private void complete(String id, Poll poll, boolean timeout) {
		if (poll.result().isSetOrExpired()) { pending.remove(id); return; }
		try {
			SmsReceiveResponse response = read(poll);
			if (timeout || !response.messages().isEmpty()) {
				poll.result().setResult(response);
				pending.remove(id);
			}
		} catch (RuntimeException exception) {
			poll.result().setErrorResult(new SmsException(SmsException.Reason.CAPACITY));
			pending.remove(id);
			LOGGER.warn("SMS receive poll failed; category={}", exception.getClass().getSimpleName());
		}
	}

	private SmsReceiveResponse read(Poll poll) {
		var received = messages.received(poll.account(), poll.sender(), poll.after(), poll.limit());
		long cursor = received.isEmpty() ? poll.after() : received.getLast().cursor();
		return new SmsReceiveResponse(received, cursor);
	}

	@PreDestroy
	public void close() {
		pending.values().forEach(poll -> poll.result().setErrorResult(new SmsException(SmsException.Reason.CAPACITY)));
		pending.clear();
	}

	private record Poll(String account, String sender, long after, int limit, DeferredResult<SmsReceiveResponse> result) { }
}
