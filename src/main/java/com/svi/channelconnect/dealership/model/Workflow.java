package com.svi.channelconnect.dealership.model;

public record Workflow(String stage, String serviceId, String date, String time, String language) {
	public static Workflow initial() {
		return new Workflow("IDLE", "registration", null, null, "en");
	}
}
