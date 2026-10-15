package com.svi.channelconnect.sms.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.svi.channelconnect.sms.dto.response.SmsReceiveResponse;
import com.svi.channelconnect.sms.dto.response.SmsInboxResponse;
import com.svi.channelconnect.sms.service.SmsReceiveService;

@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@RequestMapping("/api/v1/sms")
public class SmsReceiveController {
	private final SmsReceiveService sms;

	public SmsReceiveController(SmsReceiveService sms) { this.sms = sms; }

	@GetMapping(path = "/receive-all", produces = "application/json")
	public SmsInboxResponse receiveAll(@RequestParam(required = false) String accountId,
			@RequestParam(required = false) String sender, @RequestParam(defaultValue = "0") long after,
			@RequestParam(defaultValue = "100") int limit) {
		return sms.receiveAll(accountId, sender, after, limit);
	}

	@GetMapping(path = "/receive", produces = "application/json")
	public DeferredResult<SmsReceiveResponse> receive(@RequestParam String accountId,
			@RequestParam(required = false) String sender, @RequestParam(defaultValue = "0") long after,
			@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "-1") int waitSeconds) {
		return sms.receive(accountId, sender, after, limit, waitSeconds);
	}
}
