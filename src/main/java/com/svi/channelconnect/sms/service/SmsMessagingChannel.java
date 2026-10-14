package com.svi.channelconnect.sms.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.common.service.MessagingChannel;

@Service
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
public class SmsMessagingChannel implements MessagingChannel {
	private final SmsService sms;
	public SmsMessagingChannel(SmsService sms) { this.sms = sms; }
	@Override public String getChannelName() { return "SMS"; }
	@Override public SendMessageResponseDTO sendMessage(OutgoingMessageDTO message) {
		String requestId = SmsValidation.key(message.requestId());
		String key = requestId.startsWith("campaign:") ? "workflow:" + requestId.substring("campaign:".length()) : "channel:" + requestId;
		var result = sms.submitWorkflow(key, message.accountId(), message.recipientId(), message.content(), message.dealerMessageId());
		return new SendMessageResponseDTO(result.status(), result.messageId());
	}
}
