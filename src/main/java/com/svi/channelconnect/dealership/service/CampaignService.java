package com.svi.channelconnect.dealership.service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.dealership.config.DealershipProperties;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.model.*;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.messenger.exception.ProviderException;
import com.svi.channelconnect.whatsapp.exception.WhatsAppApiException;

@Service
public class CampaignService {
	private static final List<String> ACTIVE = List.of("READY", "AWAITING_RESPONSE", "FOLLOW_UP_SCHEDULED", "CHANNEL_SWITCHED");
	private final DealershipRepository store;
	private final DemoConfiguration config;
	private final DealershipProperties properties;
	private final CampaignTemplates templates;
	private final DealershipChannelGateway channels;
	private final Clock clock;
	private volatile boolean applicationReady;

	public CampaignService(DealershipRepository store, DemoConfiguration config, DealershipProperties properties,
			CampaignTemplates templates, DealershipChannelGateway channels, Clock clock) {
		this.store = store;
		this.config = config;
		this.properties = properties;
		this.templates = templates;
		this.channels = channels;
		this.clock = clock;
	}
	public synchronized CampaignExecution start(String customer, String base, CampaignConfiguration campaign) {
		CampaignConfiguration selected = campaign == null ? config.campaign() : campaign;
		config.validate(selected);
		if (!selected.campaignEnabled()) throw new IllegalArgumentException("Campaign is disabled");
		String initial = base == null ? selected.baseChannel() : base;
		if (!selected.channels().contains(initial)) throw new IllegalArgumentException("Initial channel is not configured");
		selected = selected.withBase(initial);
		var execution = store.start(customer, selected, initial, clock.millis());
		advance(execution.id(), false);
		return store.execution(customer, selected.id()).orElseThrow();
	}
	@Scheduled(fixedDelayString = "${dealership.scheduler-delay-ms:1000}")
	public void tick() {
		if (applicationReady && properties.schedulerEnabled()) advance(null, false);
	}
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	public void applicationReady() { applicationReady = true; }
	/** force advances only waiting/cooldown deadlines; identity consent and quiet hours still apply. */
	public synchronized List<CampaignExecution> advance(String executionId, boolean force) {
		long now = clock.millis();
		for (var execution : store.executions()) {
			if (executionId != null && !execution.id().equals(executionId)) continue;
			if (!ACTIVE.contains(execution.status()) || !force && execution.nextAttemptAt() > now) continue;
			if (store.optedOut(execution.customerId())) {
				store.executionStatus(execution.customerId(), "OPTED_OUT");
				continue;
			}
			if (!execution.configuration().campaignEnabled()) continue;
			long quietUntil = quietUntil(execution.configuration(), now);
			if (quietUntil > now) {
				store.schedule(execution, execution.currentChannel(), execution.status(), quietUntil);
				continue;
			}
			process(execution, now);
		}
		return store.executions();
	}
	private void process(CampaignExecution execution, long now) {
		CampaignConfiguration campaign = execution.configuration();
		List<String> order = new ArrayList<>();
		order.add(execution.currentChannel());
		for (String channel : campaign.channelPriority()) if (campaign.fallbackChannels().contains(channel) && !order.contains(channel)) order.add(channel);
		for (String channel : campaign.fallbackChannels()) if (!order.contains(channel)) order.add(channel);
		ChannelIdentity selected = null;
		String script = null;
		int attempt = 0;
		for (String channel : order) {
			if (store.channelFinished(execution.id(), channel)) continue;
			int count = store.acceptedCount(execution.id(), channel);
			if (count >= campaign.maxAttemptsPerChannel()) {
				store.finishChannel(execution.id(), channel, "EXHAUSTED");
				store.event(execution.customerId(), execution.id(), "MAX_ATTEMPTS_REACHED", now);
				continue;
			}
			boolean switched = !channel.equals(execution.currentChannel());
			String key = count == 0 ? (switched || !channel.equals(campaign.baseChannel()) ? "switch" : "opening") : "followup" + count;
			if (count == 0 && campaign.scripts().containsKey("opening_" + channel.toLowerCase(java.util.Locale.ROOT)) && !switched) key = "opening_" + channel.toLowerCase(java.util.Locale.ROOT);
			final String scriptKey = key;
			selected = store.identities(execution.customerId()).stream().filter(identity -> identity.channel().equals(channel)
					&& channels.canInitiate(identity, campaign, scriptKey, now)).findFirst().orElse(null);
			if (selected != null) { script = key; attempt = count + 1; break; }
			store.finishChannel(execution.id(), channel, "INELIGIBLE");
			store.event(execution.customerId(), execution.id(), "CHANNEL_INELIGIBLE", now);
		}
		if (selected == null) {
			store.schedule(execution, execution.currentChannel(), "EXHAUSTED", now);
			store.event(execution.customerId(), execution.id(), "CAMPAIGN_EXHAUSTED", now);
			return;
		}
		if (!selected.channel().equals(execution.currentChannel())) {
			store.schedule(execution, selected.channel(), "CHANNEL_SWITCHED", now);
			store.event(execution.customerId(), execution.id(), "CHANNEL_SWITCHED", now);
		}
		var customer = store.customer(execution.customerId());
		var values = templates.values(customer, properties.name(), "registration renewal", null);
		String content;
		try { content = templates.render(campaign, script, "en", values); }
		catch (IllegalArgumentException exception) {
			store.schedule(execution, selected.channel(), "FAILED", now);
			store.event(execution.customerId(), execution.id(), "SCRIPT_VALIDATION_FAILED", now);
			return;
		}
		var claim = store.claim(execution, selected, attempt, script, content, now);
		if (claim.isEmpty()) return;
		try {
			var sent = channels.campaign(selected, campaign, script, content, values, claim.get().id(), now);
			store.complete(execution, claim.get(), sent.providerMessageId(), sent.status().equals("SIMULATED") ? "SIMULATED" : "ACCEPTED_BY_PROVIDER", clock.millis());
		} catch (RuntimeException exception) {
			boolean ambiguous = true;
			boolean retryable = false;
			if (exception instanceof WhatsAppApiException provider) {
				ambiguous = List.of(ExternalFailureCategory.TIMEOUT, ExternalFailureCategory.TRANSIENT, ExternalFailureCategory.MALFORMED_RESPONSE).contains(provider.category());
				retryable = provider.category() == ExternalFailureCategory.THROTTLED;
			} else if (exception instanceof ProviderException provider) {
				ambiguous = List.of("transport", "invalid_response", "server").contains(provider.getCategory());
				retryable = provider.getCategory().equals("rate_limit");
			} else if (exception instanceof IllegalArgumentException) ambiguous = false;
			store.fail(execution, claim.get(), ambiguous, retryable ? properties.maxProviderFailures() : 1, clock.millis());
		}
	}
	static long quietUntil(CampaignConfiguration campaign, long now) {
		if (campaign.quietHoursStart() == null) return now;
		ZonedDateTime time = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Manila"));
		LocalTime start = LocalTime.parse(campaign.quietHoursStart());
		LocalTime end = LocalTime.parse(campaign.quietHoursEnd());
		LocalTime current = time.toLocalTime();
		boolean overnight = !start.isBefore(end);
		boolean quiet = overnight ? !current.isBefore(start) || current.isBefore(end) : !current.isBefore(start) && current.isBefore(end);
		if (!quiet) return now;
		var endDate = overnight && !current.isBefore(start) ? time.toLocalDate().plusDays(1) : time.toLocalDate();
		return endDate.atTime(end).atZone(ZoneId.of("Asia/Manila")).toInstant().toEpochMilli();
	}
}
