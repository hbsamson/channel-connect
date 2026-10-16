package com.svi.channelconnect.telegram.service;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.dealership.model.IncomingMessage;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;
import com.svi.channelconnect.telegram.config.TelegramProperties;
import com.svi.channelconnect.telegram.exception.TelegramApiException;

/** Telegram transport for the same process-local TMBG booking assistant used by SMS. */
public class TelegramTmbgDemoService {
	private static final String CHANNEL = "TELEGRAM";
	private static final Set<ExternalFailureCategory> AMBIGUOUS = Set.of(ExternalFailureCategory.TIMEOUT,
			ExternalFailureCategory.TRANSIENT, ExternalFailureCategory.MALFORMED_RESPONSE);
	private final TmbgBookingAssistant assistant;
	private final DealershipRepository customers;
	private final CampaignService campaigns;
	private final TelegramMessagingChannel sender;
	private final String botId;
	private final Clock clock;

	public TelegramTmbgDemoService(TmbgBookingAssistant assistant, DealershipRepository customers,
			CampaignService campaigns, TelegramMessagingChannel sender, TelegramProperties properties, Clock clock) {
		this.assistant = assistant;
		this.customers = customers;
		this.campaigns = campaigns;
		this.sender = sender;
		this.botId = properties.botToken().substring(0, properties.botToken().indexOf(':'));
		this.clock = clock;
	}

	public String greeting(String recipient) {
		return assistant.greeting(CHANNEL, recipient);
	}

	/** Call only after an automatic opening has been accepted, never on an idempotency replay. */
	public void start(String recipient) {
		assistant.start(CHANNEL, botId, recipient);
	}

	public void process(IncomingMessage incoming) {
		// Use the same lock as independent sends, managed campaigns and general dealership replies.
		synchronized (campaigns) {
			var receipt = customers.receive(incoming, clock.millis());
			if (receipt.duplicate() || customers.optedOut(receipt.customerId())) return;
			String reply = "/reset".equalsIgnoreCase(incoming.content().trim())
					? assistant.start(CHANNEL, botId, incoming.senderId())
					: assistant.reply(CHANNEL, botId, incoming.senderId(), incoming.externalId(), incoming.content());
			if (assistant.isSuppressed(CHANNEL, botId, incoming.senderId())) {
				// Persist both explicit STOP and opt-outs/wrong-person outcomes recognized by the assistant.
				customers.optOut(receipt.customerId(), clock.millis());
			}
			if (reply == null) return;
			String messageId = UUID.randomUUID().toString();
			customers.insertMessage(messageId, receipt.customerId(), "OUTBOUND", CHANNEL, botId,
					null, reply, "PENDING", clock.millis());
			try {
				var sent = sender.sendMessage(new OutgoingMessageDTO(incoming.senderId(), reply));
				customers.messageStatus(messageId, sent.providerMessageId(), "ACCEPTED_BY_PROVIDER");
				customers.event(receipt.customerId(), null, "MESSAGE_SENT", clock.millis());
			}
			catch (RuntimeException exception) {
				String status = exception instanceof TelegramApiException failure && !AMBIGUOUS.contains(failure.category())
						? "FAILED" : "UNKNOWN";
				try {
					customers.messageStatus(messageId, null, status);
					customers.event(receipt.customerId(), null, "REPLY_SEND_FAILED", clock.millis());
				}
				catch (RuntimeException persistenceFailure) {
					exception.addSuppressed(persistenceFailure);
				}
				// sendMessage has no provider idempotency key; never automatically resend an uncertain reply.
				throw exception;
			}
		}
	}
}
