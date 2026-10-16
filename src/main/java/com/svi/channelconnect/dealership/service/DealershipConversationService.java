package com.svi.channelconnect.dealership.service;

import java.time.Clock;
import java.util.UUID;

import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.dealership.model.IncomingMessage;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.whatsapp.exception.WhatsAppApiException;

@Service
public class DealershipConversationService {
	private static final Logger LOGGER = LoggerFactory.getLogger(DealershipConversationService.class);
	private final DealershipRepository store;
	private final DealershipAssistant assistant;
	private final DealershipChannelGateway channels;
	private final CampaignService campaigns;
	private final Clock clock;
	private final Validator validator;

	public DealershipConversationService(DealershipRepository store, DealershipAssistant assistant,
			DealershipChannelGateway channels, CampaignService campaigns, Clock clock, Validator validator) {
		this.store = store;
		this.assistant = assistant;
		this.channels = channels;
		this.campaigns = campaigns;
		this.clock = clock;
		this.validator = validator;
	}
	public Result receive(IncomingMessage incoming) {
		if (!validator.validate(incoming).isEmpty()) throw new IllegalArgumentException("Invalid normalized message");
		// The demo is single-instance; serialize inbound decisions with outreach, with short DB transactions.
		synchronized (campaigns) {
			var receipt = store.receive(incoming, clock.millis());
			if (receipt.duplicate()) return new Result(null, null, "DUPLICATE");
			String reply;
			try {
				var answer = assistant.answer(receipt.customerId(), receipt.verified(), incoming.content());
				reply = answer.text();
				String digest = store.summary(receipt.customerId()) + "\nCustomer: " + incoming.content();
				if (digest.length() > 4096) digest = digest.substring(digest.length() - 4096);
				store.workflow(receipt.customerId(), answer.workflow(), digest, clock.millis());
			} catch (RuntimeException exception) {
				LOGGER.warn("Dealership workflow could not complete customer={}", receipt.customerId());
				reply = "I couldn't complete that request po. Please try again or ask for a dealership representative.";
			}
			String messageId = UUID.randomUUID().toString();
			store.insertMessage(messageId, receipt.customerId(), "OUTBOUND", incoming.channel(), incoming.accountId(), null, reply, "PENDING", clock.millis());
			try {
				var identity = store.identity(incoming.channel(), incoming.accountId(), incoming.senderId()).orElseThrow();
				var sent = channels.reply(identity, reply, clock.millis(), messageId);
				String status = incoming.channel().equals("SMS") ? sent.status()
						: sent.status().equals("SIMULATED") ? "SIMULATED" : "ACCEPTED_BY_PROVIDER";
				store.messageStatus(messageId, sent.providerMessageId(), status);
				store.event(receipt.customerId(), null, "MESSAGE_SENT", clock.millis());
				return new Result(receipt.customerId(), reply, status);
			} catch (RuntimeException exception) {
				String category = exception instanceof WhatsAppApiException failure
						? failure.category().name() : exception.getClass().getSimpleName();
				LOGGER.warn("Dealership reply send failed channel={} messageId={} category={}",
						incoming.channel(), messageId, category);
				store.messageStatus(messageId, null, "UNKNOWN");
				store.event(receipt.customerId(), null, "REPLY_SEND_FAILED", clock.millis());
				return new Result(receipt.customerId(), reply, "UNKNOWN");
			}
		}
	}
	public record Result(String customerId, String reply, String deliveryStatus) { }
}
