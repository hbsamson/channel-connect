package com.svi.channelconnect.dealership.model;

public record ChannelIdentity(String channel, String accountId, String externalId, String customerId,
		boolean verified, boolean consent, boolean outboundAllowed, Long lastInboundAt) {
}
