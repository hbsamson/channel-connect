package com.svi.channelconnect.dealership.model;

public record CampaignExecution(String id, String customerId, String campaignId,
		CampaignConfiguration configuration, String status, String currentChannel,
		long nextAttemptAt, Long lastContactAt, int failureCount) {
}
