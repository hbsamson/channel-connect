package com.svi.channelconnect.dealership.model;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerProfile(String customerId, String firstName, String lastName, String phoneNumber,
		Map<String, String> contactInfo, String vin, String vehicleModel, String plateNumber,
		String renewalType, String renewalStatus, String renewalDue, List<String> serviceOfferings,
		String scriptUsed, String preferredChannel, List<String> availableChannels,
		Map<String, Boolean> channelConsent, String campaignId, String campaignStatus,
		String registrationDue, String insuranceDue, String ctplStatus, String dealerBranch) {
}
