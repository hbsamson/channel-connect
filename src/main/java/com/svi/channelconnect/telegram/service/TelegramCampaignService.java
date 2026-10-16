package com.svi.channelconnect.telegram.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.svi.channelconnect.dealership.config.DealershipProperties;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.model.CampaignConfiguration;
import com.svi.channelconnect.dealership.model.CampaignExecution;
import com.svi.channelconnect.dealership.model.ChannelIdentity;
import com.svi.channelconnect.dealership.model.DemoRequestException;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.CampaignTemplates;
import com.svi.channelconnect.telegram.dto.request.TelegramCampaignStartRequest;
import com.svi.channelconnect.telegram.dto.response.TelegramCampaignStartResponse;
import com.svi.channelconnect.telegram.exception.TelegramCampaignException;

/** Starts the existing dealership workflow with an explicitly verified Telegram identity. */
public class TelegramCampaignService {
	private final DealershipRepository customers;
	private final CampaignService campaigns;
	private final DemoConfiguration configuration;
	private final DealershipProperties dealership;
	private final CampaignTemplates templates;
	private final TelegramCampaignPolicy policy;
	private final TelegramCampaignIdempotencyStore idempotency;
	private final TelegramIdentityLinker identities;
	private final Clock clock;

	public TelegramCampaignService(DealershipRepository customers, CampaignService campaigns,
			DemoConfiguration configuration, DealershipProperties dealership, CampaignTemplates templates,
			TelegramCampaignPolicy policy, TelegramCampaignIdempotencyStore idempotency,
			TelegramIdentityLinker identities, Clock clock) {
		this.customers = customers;
		this.campaigns = campaigns;
		this.configuration = configuration;
		this.dealership = dealership;
		this.templates = templates;
		this.policy = policy;
		this.idempotency = idempotency;
		this.identities = identities;
		this.clock = clock;
	}

	public TelegramCampaignStartResponse start(String key, TelegramCampaignStartRequest request) {
		validateRecipient(request.recipient());
		if (!request.identityVerified() || !request.consent()) {
			throw new TelegramCampaignException(HttpStatus.BAD_REQUEST, "Verified chat ownership and customer consent are required");
		}
		if (!policy.permits(request.recipient(), request.openingScript()) || !policy.permits(request.recipient(), "opening")) {
			throw new TelegramCampaignException(HttpStatus.FORBIDDEN, "Recipient or opening script is not allowed");
		}
		// Serialize linking/outreach with inbound responses, opt-outs, and scheduled campaign decisions.
		synchronized (campaigns) {
			TelegramCampaignStartResponse replay = idempotency.reserve(key, request);
			if (replay != null) return replay;
			boolean mayHaveStarted = false;
			try {
				var customer = customers.customer(request.customerId());
				if (customers.optedOut(request.customerId())) {
					throw new TelegramCampaignException(HttpStatus.CONFLICT, "Customer has opted out of outreach");
				}
				ChannelIdentity identity = customers.identity("TELEGRAM", policy.botId(), request.recipient())
						.orElseThrow(() -> new TelegramCampaignException(HttpStatus.CONFLICT,
								"The recipient must first message this bot while its application webhook is active"));
				if (identity.lastInboundAt() == null) {
					throw new TelegramCampaignException(HttpStatus.CONFLICT, "No authenticated inbound message is recorded for this chat");
				}
				if (!identity.customerId().equals(request.customerId())
						&& (identity.verified() || !identity.customerId().startsWith("unknown-"))) {
					throw new TelegramCampaignException(HttpStatus.CONFLICT, "Chat is already linked to another customer");
				}
				if (customers.identities(request.customerId()).stream().anyMatch(other -> policy.canInitiate(other, "opening")
						&& !other.externalId().equals(request.recipient()))) {
					throw new TelegramCampaignException(HttpStatus.CONFLICT, "Customer already has another eligible Telegram recipient");
				}
				CampaignConfiguration selected = campaign(request.openingScript());
				configuration.validate(selected);
				if (!selected.campaignEnabled()) {
					throw new TelegramCampaignException(HttpStatus.CONFLICT, "The configured dealership campaign is disabled");
				}
				// Fail before linking or creating a campaign if the customer's template fields are incomplete.
				templates.render(selected, "opening", "en",
						templates.values(customer, dealership.name(), "registration renewal", null));
				var existing = customers.execution(request.customerId(), selected.id());
				if (existing.isPresent()) {
					if (!existing.get().configuration().equals(selected) || !identity.customerId().equals(request.customerId())
							|| !policy.canInitiate(identity, "opening")) {
						throw new TelegramCampaignException(HttpStatus.CONFLICT, "An existing campaign has a different configuration or identity");
					}
					// A new key must not advance an existing campaign and send an early follow-up.
					var response = response(existing.get());
					idempotency.complete(key, response);
					return response;
				}
				identities.link(new ChannelIdentity("TELEGRAM", policy.botId(), request.recipient(), request.customerId(),
						true, true, true, identity.lastInboundAt()), clock.millis());
				mayHaveStarted = true;
				var response = response(campaigns.start(request.customerId(), "TELEGRAM", selected));
				idempotency.complete(key, response);
				return response;
			}
			catch (DemoRequestException exception) {
				if (!mayHaveStarted) idempotency.release(key);
				throw new TelegramCampaignException(HttpStatus.CONFLICT, exception.getMessage());
			}
			catch (IllegalArgumentException exception) {
				if (!mayHaveStarted) idempotency.release(key);
				throw new TelegramCampaignException(HttpStatus.BAD_REQUEST, "Campaign script or customer data is invalid");
			}
			catch (RuntimeException exception) {
				if (!mayHaveStarted) idempotency.release(key);
				throw exception;
			}
		}
	}

	private CampaignConfiguration campaign(String script) {
		CampaignConfiguration source = configuration.campaign();
		var scripts = new HashMap<>(source.scripts());
		String opener = "opening".equals(script) ? scripts.getOrDefault("opening_telegram", scripts.get("opening"))
				: scripts.get(script);
		if (opener == null || opener.isBlank()) throw new IllegalArgumentException("Opening script is not configured");
		scripts.put("opening", opener);
		scripts.remove("opening_telegram");
		// This endpoint authorizes Telegram outreach only; it cannot initiate other channels as fallback.
		return new CampaignConfiguration(source.id(), source.offeringType(), source.serviceInclusions(), "TELEGRAM",
				List.of(), List.of("TELEGRAM"), source.maxAttemptsPerChannel(), source.retryIntervalSeconds(),
				source.cooldownSeconds(), source.campaignEnabled(), source.stopOnResponse(), source.quietHoursStart(),
				source.quietHoursEnd(), scripts, source.taglishScripts(), source.whatsAppTemplates(), null);
	}

	private void validateRecipient(String recipient) {
		if (recipient == null || !recipient.matches("[1-9][0-9]{0,18}(:[1-9][0-9]{0,18})?")) {
			throw new TelegramCampaignException(HttpStatus.BAD_REQUEST, "A numeric private chat ID with an optional topic ID is required");
		}
		try {
			for (String part : recipient.split(":")) Long.parseLong(part);
		}
		catch (NumberFormatException exception) {
			throw new TelegramCampaignException(HttpStatus.BAD_REQUEST, "Telegram recipient ID is outside the supported range");
		}
	}

	private TelegramCampaignStartResponse response(CampaignExecution execution) {
		return new TelegramCampaignStartResponse(execution.id(), execution.campaignId(), execution.status());
	}
}
