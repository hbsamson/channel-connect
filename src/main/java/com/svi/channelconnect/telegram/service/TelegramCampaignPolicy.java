package com.svi.channelconnect.telegram.service;

import com.svi.channelconnect.dealership.model.ChannelIdentity;
import com.svi.channelconnect.telegram.config.TelegramCampaignProperties;
import com.svi.channelconnect.telegram.config.TelegramProperties;

/** Outbound campaign permissions; automatic replies retain their inbound authorization. */
public class TelegramCampaignPolicy {
	private final TelegramCampaignProperties properties;
	private final String botId;

	public TelegramCampaignPolicy(TelegramCampaignProperties properties, TelegramProperties telegram) {
		this.properties = properties;
		this.botId = telegram.botToken().substring(0, telegram.botToken().indexOf(':'));
	}

	public String botId() {
		return botId;
	}

	public boolean permits(String recipient, String script) {
		return properties.allowedRecipients().contains(recipient) && properties.allowedScripts().contains(script);
	}

	public boolean canInitiate(ChannelIdentity identity, String script) {
		return "TELEGRAM".equals(identity.channel()) && botId.equals(identity.accountId())
				&& identity.verified() && identity.consent() && identity.outboundAllowed()
				&& identity.lastInboundAt() != null && permits(identity.externalId(), script);
	}
}
