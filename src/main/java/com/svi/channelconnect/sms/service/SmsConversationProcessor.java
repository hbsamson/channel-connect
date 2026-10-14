package com.svi.channelconnect.sms.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.svi.channelconnect.dealership.model.IncomingMessage;
import com.svi.channelconnect.dealership.model.Workflow;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.DealershipAssistant;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.model.SmsInbound;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;

@Service
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
public class SmsConversationProcessor {
	private static final String LENGTH_FALLBACK = "Please contact the dealership for the full response or ask for a representative.";
	private final SmsMessageRepository messages;
	private final DealershipRepository customers;
	private final DealershipAssistant assistant;
	private final CampaignService campaigns;
	private final SmsProperties properties;
	private final SmsService sms;
	private final JdbcClient db;
	private final Clock clock;
	private final TransactionTemplate transaction;

	public SmsConversationProcessor(SmsMessageRepository messages, DealershipRepository customers, DealershipAssistant assistant,
			CampaignService campaigns, SmsProperties properties, SmsService sms, JdbcClient db, Clock clock, PlatformTransactionManager manager) {
		this.messages = messages; this.customers = customers; this.assistant = assistant; this.campaigns = campaigns;
		this.properties = properties; this.sms = sms; this.db = db; this.clock = clock; transaction = new TransactionTemplate(manager);
	}
	public void process(String id) {
		// Retain the existing single-instance ordering of campaign/appointment decisions.
		synchronized (campaigns) {
			Prepared prepared = transaction.execute(status -> prepare(messages.inbound(id)));
			if (prepared == null) return;
			var intent = assistant.interpret(prepared.customerId(), prepared.verified(), prepared.message().content(),
					prepared.workflow(), prepared.history(), prepared.summary());
			transaction.executeWithoutResult(status -> {
				// No AI or gateway calls occur inside this transaction.
				SmsInbound incoming = messages.inbound(id);
				if (!incoming.status().equals("PROCESSING")) return;
				var answer = assistant.apply(prepared.customerId(), prepared.verified(), prepared.workflow(), intent);
				String reply = assistant.smsReply(answer);
				try { SmsValidation.content(reply, properties); }
				catch (SmsException tooLong) {
					reply = LENGTH_FALLBACK; SmsValidation.content(reply, properties);
					customers.event(prepared.customerId(), null, "SMS_RESPONSE_TOO_LONG", clock.millis());
				}
				String summary = prepared.summary() + "\nCustomer: " + incoming.content();
				if (summary.length() > 4096) summary = summary.substring(summary.length() - 4096);
				db.sql("UPDATE sms_conversations SET workflow = ?, summary = ?, last_activity_at = ? WHERE account_id = ? AND participant = ?")
						.params(customers.encode(answer.workflow()), summary, clock.millis(), incoming.accountId(), incoming.sender()).update();
				String dealerMessage = UUID.randomUUID().toString();
				// Replies to authenticated inbound messages, including STOP confirmations, are scoped to that receipt.
				String recipient = sms.validate(incoming.accountId(), incoming.sender(), reply);
				var queued = messages.enqueue("reply:" + incoming.id(), incoming.accountId(), recipient, reply, dealerMessage);
				customers.insertMessage(dealerMessage, prepared.customerId(), "OUTBOUND", "SMS", incoming.accountId(),
						queued.messageId(), reply, "QUEUED", clock.millis());
				messages.completeInbound(id);
			});
		}
	}
	private Prepared prepare(SmsInbound incoming) {
		if (!incoming.status().equals("PROCESSING")) return null;
		customers.receive(new IncomingMessage("SMS", incoming.accountId(), incoming.sender(), "sms-" + incoming.id(),
				incoming.content(), Instant.ofEpochMilli(incoming.receivedAt())), clock.millis());
		var identity = customers.identity("SMS", incoming.accountId(), incoming.sender()).orElseThrow();
		messages.customer(incoming.id(), identity.customerId());
		var state = db.sql("SELECT workflow, summary FROM sms_conversations WHERE account_id = ? AND participant = ?")
				.params(incoming.accountId(), incoming.sender()).query((rs, row) -> new State(customers.decode(rs.getString(1), Workflow.class), rs.getString(2))).optional();
		if (state.isEmpty()) {
			db.sql("INSERT INTO sms_conversations(account_id, participant, workflow, summary, last_activity_at) VALUES (?, ?, ?, '', ?)")
					.params(incoming.accountId(), incoming.sender(), customers.encode(Workflow.initial()), clock.millis()).update();
		}
		List<Map<String, Object>> history = messages.conversationHistory(incoming.accountId(), incoming.sender(), incoming.id());
		State scoped = state.orElse(new State(Workflow.initial(), ""));
		return new Prepared(incoming, identity.customerId(), identity.verified(), scoped.workflow(), scoped.summary(), history);
	}
	private record State(Workflow workflow, String summary) { }
	private record Prepared(SmsInbound message, String customerId, boolean verified, Workflow workflow, String summary, List<Map<String, Object>> history) { }
}
