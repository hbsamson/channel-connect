package com.svi.channelconnect.sms.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.model.SmsInbound;
import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.model.SmsSubmission;
import com.svi.channelconnect.sms.service.SmsValidation;

@Repository
public class SmsMessageRepository {
	private final JdbcClient db;
	private final SmsProperties properties;
	private final Clock clock;
	private final com.svi.channelconnect.dealership.persistence.DealershipRepository customers;
	public SmsMessageRepository(JdbcClient db, SmsProperties properties, Clock clock,
			com.svi.channelconnect.dealership.persistence.DealershipRepository customers) {
		this.db = db; this.properties = properties; this.clock = clock; this.customers = customers;
	}
	private void lock() { db.sql("SELECT id FROM sms_lock WHERE id = 1 FOR UPDATE").query(Integer.class).single(); }

	@Transactional
	public boolean accept(String account, long receiveId, String sender, String content) {
		return acceptReceipt(account, receiveId, sender, content, clock.millis());
	}
	@Transactional
	public boolean acceptRemote(String account, long receiveId, String sender, String content, long receivedAt) {
		return acceptReceipt(account, receiveId, sender, content, receivedAt);
	}
	@Transactional
	public boolean archiveRemote(String account, long receiveId, String sender, String content, long receivedAt) {
		return acceptReceipt(account, receiveId, sender, content, receivedAt, false);
	}
	private boolean acceptReceipt(String account, long receiveId, String sender, String content, long receivedAt) {
		return acceptReceipt(account, receiveId, sender, content, receivedAt, true);
	}
	private boolean acceptReceipt(String account, long receiveId, String sender, String content, long receivedAt, boolean processReply) {
		lock();
		String fingerprint = SmsValidation.fingerprint(sender, content);
		var old = db.sql("SELECT fingerprint FROM sms_inbox WHERE account_id = ? AND receive_id = ?")
				.params(account, receiveId).query(String.class).optional();
		if (old.isPresent()) {
			if (!old.get().equals(fingerprint)) throw new SmsException(SmsException.Reason.CONFLICT);
			return false;
		}
		if (processReply && inboundBacklog() >= properties.queueCapacity()) throw new SmsException(SmsException.Reason.CAPACITY);
		db.sql("INSERT INTO sms_inbox(id, account_id, receive_id, sender, content, fingerprint, received_at, status, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
				.params(UUID.randomUUID().toString(), account, receiveId, sender, content, fingerprint, receivedAt, processReply ? "RECEIVED" : "STORED", clock.millis()).update();
		return true;
	}
	@Transactional
	public long receiveBaseline(String sourceId, long initialReceivedAt) {
		lock();
		var previous = db.sql("SELECT received_from FROM sms_poll_baselines WHERE source_id = ?").param(sourceId).query(Long.class).optional();
		if (previous.isPresent()) return previous.get();
		db.sql("INSERT INTO sms_poll_baselines(source_id, received_from) VALUES (?, ?)").params(sourceId, initialReceivedAt).update();
		return initialReceivedAt;
	}
	@Transactional
	public SmsSubmission enqueue(String requestKey, String account, String recipient, String content, String dealerMessageId) {
		lock();
		String fingerprint = SmsValidation.fingerprint(account, recipient, content);
		var old = db.sql("SELECT id, status, fingerprint FROM sms_outbox WHERE request_key = ?").param(requestKey)
				.query((rs, row) -> new Existing(rs.getString(1), rs.getString(2), rs.getString(3))).optional();
		if (old.isPresent()) {
			if (!old.get().fingerprint().equals(fingerprint)) throw new SmsException(SmsException.Reason.CONFLICT);
			return new SmsSubmission(old.get().id(), old.get().status(), true);
		}
		if (outboundBacklog() >= properties.queueCapacity()) throw new SmsException(SmsException.Reason.CAPACITY);
		String id = UUID.randomUUID().toString();
		long session = db.sql("SELECT NEXT VALUE FOR sms_session_id").query(Long.class).single();
		db.sql("INSERT INTO sms_outbox(id, request_key, fingerprint, account_id, recipient, content, session_id, created_at, status, dealer_message_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)")
				.params(id, requestKey, fingerprint, account, recipient, content, session, clock.millis(), dealerMessageId).update();
		return new SmsSubmission(id, "QUEUED", false);
	}
	public Optional<SmsOutbound> outbound(String id) { return db.sql("SELECT * FROM sms_outbox WHERE id = ?").param(id).query(this::outboundRow).optional(); }
	public String errorCode(String id) {
		String code = db.sql("SELECT COALESCE(error_code, '') FROM sms_outbox WHERE id = ?").param(id).query(String.class).single();
		return code.isEmpty() ? null : code;
	}
	public long nextSessionId() { return db.sql("SELECT NEXT VALUE FOR sms_session_id").query(Long.class).single(); }
	public List<SmsOutbound> pendingOutbound() {
		// An unavailable channel's backlog must not starve another SIM's oldest message.
		return db.sql("SELECT * FROM (SELECT sms_outbox.*, ROW_NUMBER() OVER (PARTITION BY account_id ORDER BY created_at, id) AS work_rank FROM sms_outbox WHERE status = 'QUEUED') ranked WHERE work_rank = 1 ORDER BY created_at, id LIMIT 64")
				.query(this::outboundRow).list();
	}
	public List<SmsInbound> pendingInbound() {
		return db.sql("SELECT * FROM sms_inbox WHERE status = 'RECEIVED' AND next_attempt_at <= ? ORDER BY received_at, inbox_sequence LIMIT 1")
				.param(clock.millis()).query(this::inboundRow).list();
	}
	public int inboundBacklog() { return db.sql("SELECT COUNT(*) FROM sms_inbox WHERE status IN ('RECEIVED','PROCESSING')").query(Integer.class).single(); }
	public int outboundBacklog() { return db.sql("SELECT COUNT(*) FROM sms_outbox WHERE status IN ('QUEUED','CLAIMED','TRANSMITTING')").query(Integer.class).single(); }

	@Transactional
	public boolean claimOutbound(String id) {
		lock();
		SmsOutbound message = outbound(id).orElseThrow();
		return db.sql("UPDATE sms_outbox SET status = 'CLAIMED' WHERE id = ? AND status = 'QUEUED' AND (SELECT COUNT(*) FROM sms_outbox other WHERE other.account_id = sms_outbox.account_id AND other.status IN ('CLAIMED','TRANSMITTING')) < ?")
				.params(id, properties.channel(message.accountId()).maxConcurrentSessions()).update() == 1;
	}
	@Transactional
	public void transmitting(String id) {
		lock();
		SmsOutbound message = outbound(id).orElseThrow();
		if (!authorized(message)) throw new SmsException(SmsException.Reason.FORBIDDEN);
		var channel = properties.channel(message.accountId());
		long now = clock.millis();
		if (now - message.createdAt() >= properties.queueExpiry().toMillis()) throw new SmsException(SmsException.Reason.EXPIRED);
		long last = db.sql("SELECT COALESCE(MAX(transmitted_at), 0) FROM sms_outbox WHERE account_id = ?")
				.param(channel.accountId()).query(Long.class).single();
		int hourly = db.sql("SELECT COUNT(*) FROM sms_outbox WHERE account_id = ? AND transmitted_at > ?")
				.params(channel.accountId(), now - 3600000).query(Integer.class).single();
		if (hourly >= channel.sendsPerHour() || last > now - channel.sendInterval().toMillis()) throw new SmsException(SmsException.Reason.CAPACITY);
		if (db.sql("UPDATE sms_outbox SET status = 'TRANSMITTING', transmitted_at = ? WHERE id = ? AND status = 'CLAIMED'")
				.params(now, id).update() != 1) throw new IllegalStateException("SMS claim lost");
		mirror(message, "TRANSMITTING");
	}
	public boolean rateAvailable(String account) {
		long now = clock.millis(); var channel = properties.channel(account);
		return db.sql("SELECT COUNT(*) FROM sms_outbox WHERE account_id = ? AND transmitted_at > ?")
				.params(account, now - 3600000).query(Integer.class).single() < channel.sendsPerHour()
				&& db.sql("SELECT COALESCE(MAX(transmitted_at), 0) FROM sms_outbox WHERE account_id = ?")
				.param(account).query(Long.class).single() <= now - channel.sendInterval().toMillis();
	}
	public boolean authorized(SmsOutbound message) {
		// A customer-initiated reply (including an opt-out acknowledgement) is authorized by its durable receipt.
		if (message.requestKey().startsWith("reply:")) return true;
		var identity = customers.identity("SMS", message.accountId(), message.recipient());
		if (identity.isPresent() && (customers.optedOut(identity.get().customerId()) || !identity.get().verified()
				|| !identity.get().consent() || !identity.get().outboundAllowed())) return false;
		if (message.requestKey().startsWith("workflow:")) {
			return db.sql("SELECT COUNT(*) FROM dealer_attempts a JOIN dealer_executions e ON e.id = a.execution_id WHERE a.id = ? AND a.status IN ('CLAIMED','QUEUED') AND e.status IN ('SENDING','SMS_QUEUED')")
					.param(message.requestKey().substring("workflow:".length())).query(Integer.class).single() == 1;
		}
		return true;
	}
	@Transactional
	public void finish(String id, String status, String error) {
		SmsOutbound message = outbound(id).orElseThrow();
		int changed = db.sql("UPDATE sms_outbox SET status = ?, error_code = ?, completed_at = ? WHERE id = ? AND status IN ('CLAIMED','TRANSMITTING','QUEUED')")
				.params(status, error, status.equals("QUEUED") ? null : clock.millis(), id).update();
		if (changed == 0) return;
		mirror(message, status);
		if (!status.equals("QUEUED")) customers.smsResult(id, status, clock.millis());
	}
	private void mirror(SmsOutbound message, String status) {
		db.sql("UPDATE dealer_messages SET delivery_status = ? WHERE channel = 'SMS' AND direction = 'OUTBOUND' AND (id = ? OR external_id = ?)")
				.params(status, message.dealerMessageId(), message.id()).update();
	}
	@Transactional
	public boolean claimInbound(String id) {
		return db.sql("UPDATE sms_inbox SET status = 'PROCESSING', attempts = attempts + 1 WHERE id = ? AND status = 'RECEIVED'").param(id).update() == 1;
	}
	public SmsInbound inbound(String id) { return db.sql("SELECT * FROM sms_inbox WHERE id = ?").param(id).query(this::inboundRow).single(); }
	public List<com.svi.channelconnect.sms.dto.response.SmsReceiveResponse.Message> received(String account, String sender, long after, int limit) {
		return db.sql("SELECT inbox_sequence, id, receive_id, sender, content, received_at, status FROM sms_inbox WHERE account_id = :account AND inbox_sequence > :after AND (:sender IS NULL OR sender = :sender) AND content IS NOT NULL ORDER BY inbox_sequence LIMIT :limit")
				.param("account", account).param("sender", sender, java.sql.Types.VARCHAR).param("after", after).param("limit", limit)
				.query((rs, row) -> new com.svi.channelconnect.sms.dto.response.SmsReceiveResponse.Message(rs.getLong(1),
						rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7))).list();
	}
	public List<com.svi.channelconnect.sms.dto.response.SmsInboxResponse.Message> inbox(List<String> accounts, String sender, long after, int limit) {
		if (accounts.isEmpty()) return List.of();
		return db.sql("SELECT inbox_sequence, id, account_id, receive_id, sender, content, received_at, status FROM sms_inbox WHERE account_id IN (:accounts) AND inbox_sequence > :after AND (:sender IS NULL OR sender = :sender) AND content IS NOT NULL ORDER BY inbox_sequence LIMIT :limit")
				.param("accounts", accounts).param("sender", sender, java.sql.Types.VARCHAR).param("after", after).param("limit", limit)
				.query((rs, row) -> new com.svi.channelconnect.sms.dto.response.SmsInboxResponse.Message(rs.getLong(1),
						rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8))).list();
	}
	public List<Map<String, Object>> conversationHistory(String account, String participant, String currentInboundId) {
		// Retain every available turn on this SIM/participant route. Unsent or failed messages were not told to the customer.
		return db.sql("SELECT direction, content, delivery_status FROM (SELECT 'INBOUND' AS direction, content, status AS delivery_status, received_at AS occurred_at, 0 AS turn_order, inbox_sequence AS sequence FROM sms_inbox WHERE account_id = ? AND sender = ? AND content IS NOT NULL AND (status = 'PROCESSED' OR id = ?) UNION ALL SELECT 'OUTBOUND', content, status, COALESCE(transmitted_at, created_at), 1, session_id FROM sms_outbox WHERE account_id = ? AND recipient = ? AND content IS NOT NULL AND status IN ('GATEWAY_SENT','TRANSMITTING','UNKNOWN')) ORDER BY occurred_at, turn_order, sequence")
				.params(account, participant, currentInboundId, account, participant)
				.query((rs, row) -> Map.<String, Object>of("direction", rs.getString(1), "content", rs.getString(2), "deliveryStatus", rs.getString(3))).list();
	}
	public void customer(String id, String customer) { db.sql("UPDATE sms_inbox SET customer_id = ? WHERE id = ?").params(customer, id).update(); }
	public void completeInbound(String id) { db.sql("UPDATE sms_inbox SET status = 'PROCESSED' WHERE id = ? AND status = 'PROCESSING'").param(id).update(); }
	public void retryInbound(String id) {
		db.sql("UPDATE sms_inbox SET status = CASE WHEN attempts >= ? THEN 'DEAD_LETTER' ELSE 'RECEIVED' END, next_attempt_at = ? WHERE id = ? AND status = 'PROCESSING'")
				.params(properties.processingAttempts(), clock.millis() + properties.processingRetryDelay().toMillis(), id).update();
	}
	public List<DeadLetter> deadLetters() {
		return db.sql("SELECT id, account_id, received_at, attempts FROM sms_inbox WHERE status = 'DEAD_LETTER' ORDER BY received_at LIMIT 100")
				.query((rs, row) -> new DeadLetter(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4))).list();
	}
	@Transactional
	public void retryDeadLetter(String id) {
		lock();
		if (inboundBacklog() >= properties.queueCapacity()) throw new SmsException(SmsException.Reason.CAPACITY);
		if (db.sql("UPDATE sms_inbox SET status = 'RECEIVED', attempts = 0, next_attempt_at = ? WHERE id = ? AND status = 'DEAD_LETTER' AND content IS NOT NULL")
				.params(clock.millis(), id).update() != 1) throw new SmsException(SmsException.Reason.NOT_FOUND);
	}
	@Transactional
	public void recover() {
		db.sql("UPDATE sms_inbox SET status = CASE WHEN attempts >= ? THEN 'DEAD_LETTER' ELSE 'RECEIVED' END WHERE status = 'PROCESSING'").param(properties.processingAttempts()).update();
		db.sql("UPDATE sms_outbox SET status = 'QUEUED' WHERE status = 'CLAIMED' AND transmitted_at IS NULL").update();
		for (SmsOutbound message : db.sql("SELECT * FROM sms_outbox WHERE status = 'TRANSMITTING'").query(this::outboundRow).list()) finish(message.id(), "UNKNOWN", "PROCESS_RESTART");
	}
	@Transactional
	public void maintain() {
		long now = clock.millis();
		for (SmsOutbound message : db.sql("SELECT * FROM sms_outbox WHERE status = 'QUEUED' AND created_at < ?")
				.param(now - properties.queueExpiry().toMillis()).query(this::outboundRow).list()) finish(message.id(), "FAILED", "QUEUE_EXPIRED");
		db.sql("UPDATE sms_inbox SET status = 'DEAD_LETTER' WHERE status = 'RECEIVED' AND received_at < ?").param(now - properties.queueExpiry().toMillis()).update();
		db.sql("UPDATE dealer_messages SET content = '' WHERE channel = 'SMS' AND direction = 'INBOUND' AND external_id IN (SELECT 'sms-' || id FROM sms_inbox WHERE status IN ('PROCESSED','DEAD_LETTER') AND received_at < ?)").param(now - properties.contentRetention().toMillis()).update();
		db.sql("UPDATE dealer_messages SET content = '' WHERE channel = 'SMS' AND direction = 'OUTBOUND' AND external_id IN (SELECT id FROM sms_outbox WHERE completed_at < ?)").param(now - properties.contentRetention().toMillis()).update();
		db.sql("UPDATE sms_inbox SET content = NULL WHERE status IN ('PROCESSED','DEAD_LETTER','STORED') AND received_at < ?").param(now - properties.contentRetention().toMillis()).update();
		db.sql("UPDATE sms_outbox SET content = NULL WHERE completed_at < ?").param(now - properties.contentRetention().toMillis()).update();
		db.sql("DELETE FROM sms_inbox WHERE status IN ('PROCESSED','DEAD_LETTER','STORED') AND received_at < ?").param(now - properties.idRetention().toMillis()).update();
		// Unknown transmission tombstones retain their keys; expiration must never authorize an uncertain resend.
		db.sql("DELETE FROM sms_outbox WHERE status <> 'UNKNOWN' AND completed_at < ?").param(now - properties.idRetention().toMillis()).update();
		db.sql("DELETE FROM sms_conversations WHERE last_activity_at < ?").param(now - properties.contentRetention().toMillis()).update();
	}
	private SmsOutbound outboundRow(ResultSet rs, int row) throws SQLException {
		return new SmsOutbound(rs.getString("id"), rs.getString("account_id"), rs.getString("recipient"), rs.getString("content"),
				rs.getLong("session_id"), rs.getLong("created_at"), rs.getString("status"), rs.getString("request_key"), rs.getString("dealer_message_id"));
	}
	private SmsInbound inboundRow(ResultSet rs, int row) throws SQLException {
		return new SmsInbound(rs.getString("id"), rs.getString("account_id"), rs.getLong("receive_id"), rs.getString("sender"), rs.getString("content"),
				rs.getLong("received_at"), rs.getString("status"), rs.getInt("attempts"));
	}
	private record Existing(String id, String status, String fingerprint) { }
	public record DeadLetter(String messageId, String accountId, long receivedAt, int attempts) { }
}
