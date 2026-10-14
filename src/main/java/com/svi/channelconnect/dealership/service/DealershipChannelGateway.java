package com.svi.channelconnect.dealership.service;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.MessagingAppHandler;
import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.dealership.model.CampaignConfiguration;
import com.svi.channelconnect.dealership.model.ChannelIdentity;
import com.svi.channelconnect.messenger.config.MessengerProperties;
import com.svi.channelconnect.whatsapp.config.OutboundMessagingProperties;
import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;
import com.svi.channelconnect.whatsapp.dto.request.OutboundTemplateMessageRequest;
import com.svi.channelconnect.whatsapp.service.OutboundMessageService;

@Service
public class DealershipChannelGateway {
	private final MessagingAppHandler messaging;
	private final MessengerProperties messenger;
	private final WhatsAppProperties whatsapp;
	private final ObjectProvider<OutboundMessageService> outbound;
	private final ObjectProvider<OutboundMessagingProperties> restrictions;
	private final boolean simulationsAvailable;

	public DealershipChannelGateway(MessagingAppHandler messaging, MessengerProperties messenger, WhatsAppProperties whatsapp,
			ObjectProvider<OutboundMessageService> outbound, ObjectProvider<OutboundMessagingProperties> restrictions, Environment env) {
		this.messaging = messaging;
		this.messenger = messenger;
		this.whatsapp = whatsapp;
		this.outbound = outbound;
		this.restrictions = restrictions;
		this.simulationsAvailable = env.matchesProfiles("local", "test");
	}

	public boolean canInitiate(ChannelIdentity identity, CampaignConfiguration config, String script, long now) {
		if (!identity.verified() || !identity.consent() || !identity.outboundAllowed()) return false;
		return switch (identity.channel()) {
			case "SMS", "TELEGRAM" -> simulationsAvailable && identity.accountId().equals("demo") && identity.externalId().startsWith("demo-");
			case "MESSENGER" -> identity.accountId().equals(messenger.pageId()) && inWindow(identity, now);
			case "WHATSAPP" -> {
				var template = config.whatsAppTemplates() == null ? null : config.whatsAppTemplates().get(script);
				var allowed = restrictions.getIfAvailable();
				yield identity.accountId().equals(whatsapp.phoneNumberId()) && template != null && allowed != null
						&& outbound.getIfAvailable() != null && allowed.allowedRecipients().contains(identity.externalId())
						&& allowed.allowedTemplates().contains(template.name());
			}
			default -> false;
		};
	}

	public SendMessageResponseDTO campaign(ChannelIdentity identity, CampaignConfiguration config, String script,
			String content, Map<String, String> variables, String idempotencyKey, long now) {
		if (!canInitiate(identity, config, script, now)) throw new IllegalArgumentException("Channel is ineligible for campaign outreach");
		if (identity.channel().equals("WHATSAPP")) {
			var template = config.whatsAppTemplates().get(script);
			var parameters = template.variables().stream().map(name -> {
				String value = variables.get(name);
				if (value == null || value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("Missing WhatsApp template variable");
				return value;
			}).toList();
			var response = outbound.getObject().sendTemplateMessage(idempotencyKey,
					new OutboundTemplateMessageRequest(identity.externalId(), template.name(), template.language(), parameters));
			return new SendMessageResponseDTO("ACCEPTED_BY_PROVIDER", response.providerMessageId());
		}
		return messaging.sendMessage(identity.channel(), new OutgoingMessageDTO(identity.externalId(), content));
	}

	public SendMessageResponseDTO reply(ChannelIdentity identity, String content, long now) {
		if (identity.channel().equals("MESSENGER") || identity.channel().equals("WHATSAPP")) {
			if (!inWindow(identity, now)) throw new IllegalArgumentException("Platform reply window has expired");
		}
		return messaging.sendMessage(identity.channel(), new OutgoingMessageDTO(identity.externalId(), content));
	}
	private boolean inWindow(ChannelIdentity identity, long now) {
		return identity.lastInboundAt() != null && identity.lastInboundAt() <= now
				&& now - identity.lastInboundAt() < Duration.ofHours(24).toMillis();
	}
}
