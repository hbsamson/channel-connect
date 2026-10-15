package com.svi.channelconnect.telegram.service;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.dealership.config.DealershipProperties;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.CampaignTemplates;
import com.svi.channelconnect.telegram.dto.request.TelegramOutboundMessageRequest;
import com.svi.channelconnect.telegram.dto.response.TelegramOutboundMessageResponse;
import com.svi.channelconnect.telegram.exception.TelegramApiException;
import com.svi.channelconnect.telegram.exception.TelegramCampaignException;

/** Independent sends retain dealership history without starting or restarting a campaign. */
public class TelegramOutboundMessageService {
	private static final Set<ExternalFailureCategory> AMBIGUOUS = Set.of(ExternalFailureCategory.TIMEOUT,
			ExternalFailureCategory.TRANSIENT, ExternalFailureCategory.MALFORMED_RESPONSE);
	private final DealershipRepository customers;
	private final CampaignService campaigns;
	private final TelegramCampaignPolicy policy;
	private final TelegramMessagingChannel channel;
	private final TelegramRequestIdempotencyStore<TelegramOutboundMessageRequest, TelegramOutboundMessageResponse> requests;
	private final Clock clock;
	private final DemoConfiguration configuration;
	private final DealershipProperties dealership;
	private final CampaignTemplates templates;

	public TelegramOutboundMessageService(DealershipRepository customers, CampaignService campaigns,
			TelegramCampaignPolicy policy, TelegramMessagingChannel channel,
			TelegramRequestIdempotencyStore<TelegramOutboundMessageRequest, TelegramOutboundMessageResponse> requests,
			Clock clock, DemoConfiguration configuration, DealershipProperties dealership, CampaignTemplates templates) {
		this.customers = customers;
		this.campaigns = campaigns;
		this.policy = policy;
		this.channel = channel;
		this.requests = requests;
		this.clock = clock;
		this.configuration = configuration;
		this.dealership = dealership;
		this.templates = templates;
	}

	public TelegramOutboundMessageResponse send(String key, TelegramOutboundMessageRequest request) {
		if (request.accountId() != null && !policy.botId().equals(request.accountId())) {
			throw new TelegramCampaignException(HttpStatus.BAD_REQUEST, "accountId must match the configured Telegram bot ID, or be omitted");
		}
		String script = request.content() == null ? "opening" : "custom";
		if (!policy.permits(request.recipient(), script)) {
			throw new TelegramCampaignException(HttpStatus.FORBIDDEN,
					"Recipient must be allowlisted and " + script + " must be enabled in TELEGRAM_CAMPAIGN_ALLOWED_SCRIPTS");
		}
		var normalized = new TelegramOutboundMessageRequest(policy.botId(), request.recipient(), request.content());
		synchronized (campaigns) {
			var replay = requests.reserve(key, normalized);
			if (replay != null) return replay.asReplay();
			String messageId = UUID.randomUUID().toString();
			boolean mayHaveSent = false;
			try {
				var identity = customers.identity("TELEGRAM", policy.botId(), request.recipient())
						.orElseThrow(() -> new TelegramCampaignException(HttpStatus.CONFLICT,
								"Recipient must first message the bot and be linked to a verified, consenting customer"));
				if (!policy.canInitiate(identity, script) || customers.optedOut(identity.customerId())) {
					throw new TelegramCampaignException(HttpStatus.CONFLICT, "A verified, consenting customer chat without an opt-out is required");
				}
				String content = request.content() == null ? greeting(identity.customerId()) : request.content();
				customers.insertMessage(messageId, identity.customerId(), "OUTBOUND", "TELEGRAM", policy.botId(),
						null, content, "PENDING", clock.millis());
				mayHaveSent = true;
				var sent = channel.sendMessage(new OutgoingMessageDTO(request.recipient(), content));
				customers.messageStatus(messageId, sent.providerMessageId(), "ACCEPTED_BY_PROVIDER");
				var response = new TelegramOutboundMessageResponse(messageId, "ACCEPTED_BY_PROVIDER", false, sent.providerMessageId());
				requests.complete(key, response);
				return response;
			}
			catch (TelegramApiException exception) {
				boolean ambiguous = AMBIGUOUS.contains(exception.category());
				recordFailure(messageId, ambiguous ? "UNKNOWN" : "FAILED", exception);
				if (!ambiguous) requests.release(key);
				throw exception;
			}
			catch (RuntimeException exception) {
				if (mayHaveSent) recordFailure(messageId, "UNKNOWN", exception);
				else requests.release(key);
				throw exception;
			}
		}
	}

	private String greeting(String customerId) {
		// Match the campaign context that the shared dealership assistant uses for subsequent replies.
		var campaign = customers.activeCampaign(customerId).orElse(configuration.campaign());
		var customer = customers.customer(customerId);
		String script = campaign.scripts().containsKey("opening_telegram") ? "opening_telegram" : "opening";
		try {
			return templates.render(campaign, script, "en",
					templates.values(customer, dealership.name(), "registration renewal", null));
		}
		catch (IllegalArgumentException exception) {
			throw new TelegramCampaignException(HttpStatus.BAD_REQUEST,
					"Customer details or the approved dealership opening script are incomplete");
		}
	}

	private void recordFailure(String messageId, String status, RuntimeException failure) {
		try {
			customers.messageStatus(messageId, null, status);
		}
		catch (RuntimeException persistenceFailure) {
			failure.addSuppressed(persistenceFailure);
		}
	}
}
