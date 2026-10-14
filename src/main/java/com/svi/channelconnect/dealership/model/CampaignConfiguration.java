package com.svi.channelconnect.dealership.model;

import java.util.List;
import java.util.Map;

public record CampaignConfiguration(String id, String offeringType, List<String> serviceInclusions,
		String baseChannel, List<String> fallbackChannels, List<String> channelPriority,
		int maxAttemptsPerChannel, long retryIntervalSeconds, long cooldownSeconds,
		boolean campaignEnabled, boolean stopOnResponse, String quietHoursStart, String quietHoursEnd,
		Map<String, String> scripts, Map<String, String> taglishScripts,
		Map<String, Template> whatsAppTemplates, String openingScript) {
	public record Template(String name, String language, List<String> variables) { }
	public CampaignConfiguration withBase(String channel) {
		return new CampaignConfiguration(id, offeringType, serviceInclusions, channel, fallbackChannels, channelPriority,
				maxAttemptsPerChannel, retryIntervalSeconds, cooldownSeconds, campaignEnabled, stopOnResponse,
				quietHoursStart, quietHoursEnd, scripts, taglishScripts, whatsAppTemplates, openingScript);
	}
	public CampaignConfiguration withOpeningScript(String script) {
		return new CampaignConfiguration(id, offeringType, serviceInclusions, baseChannel, fallbackChannels, channelPriority,
				maxAttemptsPerChannel, retryIntervalSeconds, cooldownSeconds, campaignEnabled, stopOnResponse,
				quietHoursStart, quietHoursEnd, scripts, taglishScripts, whatsAppTemplates, script);
	}
	public List<String> channels() {
		java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
		result.add(baseChannel);
		result.addAll(fallbackChannels);
		return List.copyOf(result);
	}
}
