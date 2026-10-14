package com.svi.channelconnect.dealership.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.dealership.model.CampaignConfiguration;
import com.svi.channelconnect.dealership.model.CustomerProfile;

@Component
public class DemoConfiguration {
	private final ObjectMapper mapper;
	private final ResourceLoader resources;
	private final DealershipProperties properties;
	private final Catalog catalog;
	private final CampaignConfiguration campaign;
	private final String prompt;

	public DemoConfiguration(ObjectMapper mapper, ResourceLoader resources, DealershipProperties properties) {
		this.mapper = mapper;
		this.resources = resources;
		this.properties = properties;
		catalog = read(properties.catalog(), Catalog.class);
		campaign = read(properties.campaign(), CampaignConfiguration.class);
		validate(campaign);
		prompt = text(properties.prompt());
		for (Slot slot : catalog.slots()) {
			OffsetDateTime.parse(slot.startsAt());
			if (slot.capacity() < 1) throw new IllegalArgumentException("Slot capacity must be positive");
		}
	}

	public Catalog catalog() { return catalog; }
	public CampaignConfiguration campaign() { return campaign; }
	public String prompt() { return prompt; }
	public List<CustomerProfile> customers() {
		return List.of(read(properties.customers(), CustomerProfile[].class));
	}

	public void validate(CampaignConfiguration config) {
		Set<String> channels = Set.of("SMS", "TELEGRAM", "MESSENGER", "WHATSAPP");
		if (config == null || config.id() == null || config.offeringType() == null || config.baseChannel() == null
				|| config.fallbackChannels() == null || config.channelPriority() == null || config.scripts() == null
				|| config.serviceInclusions() == null) throw new IllegalArgumentException("Incomplete demo campaign configuration");
		if (!config.id().matches("[a-zA-Z0-9_-]{1,128}")
				|| !Set.of("1-in-1", "2-in-1", "3-in-1", "Custom").contains(config.offeringType())
				|| config.maxAttemptsPerChannel() < 1 || config.maxAttemptsPerChannel() > 3
				|| config.retryIntervalSeconds() < 1 || config.retryIntervalSeconds() > 2592000
				|| config.cooldownSeconds() < 0 || config.cooldownSeconds() > 2592000
				|| !config.stopOnResponse() || config.serviceInclusions().isEmpty()
				|| !channels.containsAll(config.channels()) || config.channelPriority() == null
				|| !channels.containsAll(config.channelPriority())) {
			throw new IllegalArgumentException("Invalid demo campaign configuration");
		}
		if (config.quietHoursStart() != null) LocalTime.parse(config.quietHoursStart());
		if (config.quietHoursEnd() != null) LocalTime.parse(config.quietHoursEnd());
		if ((config.quietHoursStart() == null) != (config.quietHoursEnd() == null)) {
			throw new IllegalArgumentException("Both quiet-hour boundaries are required");
		}
		for (String key : List.of("opening", "followup1", "followup2", "switch", "positive", "booking",
				"rescheduling", "cancellation", "privacy", "handoff", "closing", "redirect", "optout")) {
			if (config.scripts().get(key) == null || config.scripts().get(key).isBlank() || config.scripts().get(key).length() > 4096) {
				throw new IllegalArgumentException("Missing campaign script: " + key);
			}
		}
		if (config.whatsAppTemplates() != null) for (var template : config.whatsAppTemplates().values()) {
			if (template == null || template.name() == null || !template.name().matches("[a-z0-9_]{1,512}")
					|| template.language() == null || !template.language().matches("[a-z]{2,3}(?:_[A-Z]{2})?")
					|| template.variables() == null || template.variables().size() > 10
					|| !Set.of("first_name", "vehicle_model", "renewal_due", "dealer_name", "service_offering", "appointment_time").containsAll(template.variables())) {
				throw new IllegalArgumentException("Invalid approved WhatsApp template configuration");
			}
		}
	}

	private <T> T read(String location, Class<T> type) {
		return mapper.readValue(text(location), type);
	}

	private String text(String location) {
		try {
			return resources.getResource(location).getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalArgumentException("Cannot load dealership configuration resource", exception);
		}
	}

	public record Catalog(String disclaimer, String hours, String location, String privacyContact,
			Map<String, Service> services, List<Slot> slots) { }
	public record Service(String name, String price, List<String> inclusions) { }
	public record Slot(String id, String startsAt, int capacity) { }
}
