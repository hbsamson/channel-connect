package com.svi.channelconnect.dealership.model;

public record Appointment(String id, String customerId, String serviceId, String slotId,
		String status, long startsAt) {
}
