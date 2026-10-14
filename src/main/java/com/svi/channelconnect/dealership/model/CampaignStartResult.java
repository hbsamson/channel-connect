package com.svi.channelconnect.dealership.model;

/** Each recipient is independent; a rejected customer does not interrupt the batch. */
public record CampaignStartResult(String customerId, CampaignExecution execution, String errorCode) {
}
