package com.svi.channelconnect.dealership.controller;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.model.*;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.DealershipConversationService;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/admin/demo")
public class DemoController {
	private final DealershipRepository store;
	private final DemoConfiguration config;
	private final CampaignService campaigns;
	private final DealershipConversationService conversations;
	private final Clock clock;

	public DemoController(DealershipRepository store, DemoConfiguration config, CampaignService campaigns,
			DealershipConversationService conversations, Clock clock) {
		this.store = store;
		this.config = config;
		this.campaigns = campaigns;
		this.conversations = conversations;
		this.clock = clock;
	}
	@PostMapping("/seed")
	public List<CustomerProfile> seed() {
		synchronized (campaigns) { store.seed(config.customers(), config.catalog(), clock.millis()); }
		return store.customers();
	}
	@PostMapping("/reset")
	public List<CustomerProfile> reset() {
		synchronized (campaigns) { store.reset(); return seed(); }
	}
	@PostMapping("/customers/import")
	public List<CustomerProfile> ingest(@RequestBody @Size(min = 1, max = 100) List<CustomerProfile> customers) {
		synchronized (campaigns) { store.seed(customers, config.catalog(), clock.millis()); }
		return store.customers();
	}
	@GetMapping("/customers")
	public List<CustomerProfile> customers() { return store.customers(); }
	@GetMapping("/customers/{id}")
	public CustomerView customer(@PathVariable String id) {
		return new CustomerView(store.customer(id), store.optedOut(id), store.identities(id), store.workflow(id),
				store.summary(id), store.history(id, 100), store.appointment(id).orElse(null), store.attempts(id), store.events(id), store.representativeRequest(id, null).orElse(null));
	}
	@PostMapping("/customers/{id}/identities")
	public CustomerView link(@PathVariable String id, @Valid @RequestBody LinkIdentity identity) {
		if (List.of("SMS", "TELEGRAM").contains(identity.channel()) && (!identity.accountId().equals("demo") || !identity.externalId().startsWith("demo-"))) {
			throw new IllegalArgumentException("SMS and Telegram are simulations; use fictional demo identities");
		}
		synchronized (campaigns) {
			store.link(new ChannelIdentity(identity.channel(), identity.accountId(), identity.externalId(), id,
					identity.verified(), identity.consent(), identity.outboundAllowed(), null), clock.millis());
		}
		return customer(id);
	}
	@PostMapping("/campaigns/start")
	public CampaignExecution start(@Valid @RequestBody StartCampaign request) {
		return campaigns.start(request.customerId(), request.baseChannel(), request.configuration());
	}
	@GetMapping("/campaigns")
	public List<CampaignExecution> executions() { return store.executions(); }
	@PostMapping("/campaigns/advance")
	public List<CampaignExecution> advance(@RequestBody Advance request) { return campaigns.advance(request.executionId(), request.force()); }
	@PostMapping("/replies")
	public DealershipConversationService.Result reply(@Valid @RequestBody IncomingMessage message) {
		if (!List.of("SMS", "TELEGRAM").contains(message.channel()) || !message.accountId().equals("demo") || !message.senderId().startsWith("demo-")) {
			throw new IllegalArgumentException("Only fictional SMS/Telegram messages can be simulated; real channel replies require authenticated webhooks");
		}
		return conversations.receive(message);
	}
	@GetMapping("/configuration")
	public Map<String, Object> configuration() {
		return Map.of("campaign", config.campaign(), "catalog", config.catalog(), "channels",
				Map.of("SMS", "SIMULATED", "TELEGRAM", "SIMULATED", "MESSENGER", "REAL_ADAPTER_REQUIRES_LIVE_VERIFICATION", "WHATSAPP", "REAL_ADAPTER_REQUIRES_LIVE_VERIFICATION"));
	}
	public record StartCampaign(@NotBlank @Size(max = 128) String customerId,
			@Pattern(regexp = "SMS|TELEGRAM|MESSENGER|WHATSAPP") String baseChannel, CampaignConfiguration configuration) { }
	public record Advance(@Size(max = 128) String executionId, boolean force) { }
	public record LinkIdentity(@NotBlank @Pattern(regexp = "SMS|TELEGRAM|MESSENGER|WHATSAPP") String channel,
			@NotBlank @Size(max = 128) String accountId, @NotBlank @Size(max = 512) String externalId,
			boolean verified, boolean consent, boolean outboundAllowed) { }
	public record CustomerView(CustomerProfile customer, boolean optedOut, List<ChannelIdentity> identities,
			Workflow workflow, String summary, List<ConversationMessage> messages, Appointment appointment,
			List<DealershipRepository.Attempt> attempts, List<DealershipRepository.Event> events, RepresentativeRequest representativeRequest) { }
}
