package com.svi.channelconnect.dealership.model;

public record RepresentativeRequest(String trackingNumber, String status, String serviceId,
		String requestedDate, String requestedTime, long createdAt) {
}
