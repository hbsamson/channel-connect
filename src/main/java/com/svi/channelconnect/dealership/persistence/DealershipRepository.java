package com.svi.channelconnect.dealership.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.model.*;

@Repository
public class DealershipRepository {
	private static final Logger LOGGER = LoggerFactory.getLogger(DealershipRepository.class);
	private final JdbcClient db;
	private final ObjectMapper json;

	public DealershipRepository(JdbcClient db, ObjectMapper json) {
		this.db = db;
		this.json = json;
	}

	public String encode(Object value) { return json.writeValueAsString(value); }
	public <T> T decode(String value, Class<T> type) { return json.readValue(value, type); }
	public List<CustomerProfile> customers() {
		return db.sql("SELECT profile FROM dealer_customers WHERE id NOT LIKE 'unknown-%' ORDER BY id")
				.query((rs, row) -> decode(rs.getString(1), CustomerProfile.class)).list();
	}
	public CustomerProfile customer(String id) {
		return db.sql("SELECT profile FROM dealer_customers WHERE id = ?").param(id)
				.query((rs, row) -> decode(rs.getString(1), CustomerProfile.class)).optional()
				.orElseThrow(() -> new DemoRequestException("Customer does not exist"));
	}
	public boolean optedOut(String id) {
		return db.sql("SELECT opted_out FROM dealer_customers WHERE id = ?").param(id).query(Boolean.class).single();
	}
	public List<ChannelIdentity> identities(String customerId) {
		return db.sql("SELECT * FROM dealer_identities WHERE customer_id = ?").param(customerId)
				.query(this::identityRow).list();
	}
	public Optional<ChannelIdentity> identity(String channel, String account, String external) {
		return db.sql("SELECT * FROM dealer_identities WHERE channel = ? AND account_id = ? AND external_id = ?")
				.params(channel, account, external).query(this::identityRow).optional();
	}
	private ChannelIdentity identityRow(ResultSet rs, int row) throws SQLException {
		return new ChannelIdentity(rs.getString("channel"), rs.getString("account_id"), rs.getString("external_id"),
				rs.getString("customer_id"), rs.getBoolean("verified"), rs.getBoolean("consent"),
				rs.getBoolean("outbound_allowed"), rs.getObject("last_inbound_at", Long.class));
	}

	@Transactional
	public void seed(List<CustomerProfile> customers, DemoConfiguration.Catalog catalog, long now) {
		for (CustomerProfile customer : customers) {
			if (customer.customerId() == null || !customer.customerId().matches("[a-zA-Z0-9_-]{1,128}")) {
				throw new DemoRequestException("Invalid customer_id");
			}
			if (db.sql("SELECT COUNT(*) FROM dealer_customers WHERE id = ?").param(customer.customerId())
					.query(Integer.class).single() > 0) continue;
			db.sql("INSERT INTO dealer_customers(id, profile) VALUES (?, ?)")
					.params(customer.customerId(), encode(customer)).update();
			ensureConversation(customer.customerId(), now);
			if (customer.contactInfo() == null) continue;
			for (var contact : customer.contactInfo().entrySet()) {
				// Seed fixtures may verify only fictional simulation identities. Real identities require admin linking.
				if (!List.of("SMS", "TELEGRAM").contains(contact.getKey()) || !contact.getValue().startsWith("demo-")) continue;
				boolean consent = customer.channelConsent() != null && Boolean.TRUE.equals(customer.channelConsent().get(contact.getKey()));
				insertIdentity(new ChannelIdentity(contact.getKey(), "demo", contact.getValue(), customer.customerId(), true, consent, consent, null));
			}
		}
		for (var slot : catalog.slots()) {
			if (db.sql("SELECT COUNT(*) FROM dealer_slots WHERE id = ?").param(slot.id()).query(Integer.class).single() == 0) {
				db.sql("INSERT INTO dealer_slots(id, starts_at, capacity) VALUES (?, ?, ?)")
						.params(slot.id(), OffsetDateTime.parse(slot.startsAt()).toInstant().toEpochMilli(), slot.capacity()).update();
			}
		}
	}

	@Transactional
	public void link(ChannelIdentity identity, long now) {
		customer(identity.customerId());
		if (!identity.verified()) throw new DemoRequestException("Admin must verify account ownership before linking");
		Optional<ChannelIdentity> existing = identity(identity.channel(), identity.accountId(), identity.externalId());
		if (existing.isPresent()) {
			if (!existing.get().customerId().equals(identity.customerId())) {
				if (existing.get().verified() || !existing.get().customerId().startsWith("unknown-")) {
					throw new DemoRequestException("Identity is already associated with a different customer");
				}
				if (optedOut(existing.get().customerId())) {
					throw new DemoRequestException("This identity previously opted out; linking cannot restore outreach consent");
				}
				db.sql("UPDATE dealer_messages SET customer_id = ? WHERE customer_id = ?")
						.params(identity.customerId(), existing.get().customerId()).update();
				db.sql("UPDATE dealer_requests SET customer_id = ? WHERE customer_id = ?")
						.params(identity.customerId(), existing.get().customerId()).update();
				db.sql("UPDATE dealer_events SET customer_id = ? WHERE customer_id = ?")
						.params(identity.customerId(), existing.get().customerId()).update();
			}
			db.sql("UPDATE dealer_identities SET customer_id = ?, verified = ?, consent = ?, outbound_allowed = ? WHERE channel = ? AND account_id = ? AND external_id = ?")
					.params(identity.customerId(), true, identity.consent(), identity.outboundAllowed(), identity.channel(), identity.accountId(), identity.externalId()).update();
		} else insertIdentity(identity);
		ensureConversation(identity.customerId(), now);
		// Import only the exact explicitly verified Messenger identity; legacy tables remain intact.
		if (identity.channel().equals("MESSENGER")) {
			var legacy = db.sql("SELECT m.id, m.direction, m.external_message_id, m.content, m.created_at FROM messages m JOIN conversations c ON c.id = m.conversation_id WHERE c.channel = 'MESSENGER' AND c.page_id = ? AND c.psid = ? ORDER BY m.message_sequence")
					.params(identity.accountId(), identity.externalId()).query((rs, row) -> new LegacyMessage(
							rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class).toInstant().toEpochMilli())).list();
			for (var message : legacy) {
				String id = "legacy-" + message.id();
				if (db.sql("SELECT COUNT(*) FROM dealer_messages WHERE id = ?").param(id).query(Integer.class).single() > 0) continue;
				insertMessage(id, identity.customerId(), message.direction().equals("INCOMING") ? "INBOUND" : "OUTBOUND",
						identity.channel(), identity.accountId(), "legacy-" + message.id(), message.content(), "LEGACY", message.at());
			}
		}
		event(identity.customerId(), null, "IDENTITY_LINKED", now);
	}

	private void insertIdentity(ChannelIdentity identity) {
		db.sql("INSERT INTO dealer_identities(channel, account_id, external_id, customer_id, verified, consent, outbound_allowed, last_inbound_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
				.params(identity.channel(), identity.accountId(), identity.externalId(), identity.customerId(), identity.verified(),
						identity.consent(), identity.outboundAllowed(), identity.lastInboundAt()).update();
	}

	@Transactional
	public Receipt receive(IncomingMessage incoming, long now) {
		if (db.sql("SELECT COUNT(*) FROM dealer_messages WHERE channel = ? AND account_id = ? AND direction = 'INBOUND' AND external_id = ?")
				.params(incoming.channel(), incoming.accountId(), incoming.externalId()).query(Integer.class).single() > 0) {
			return new Receipt(null, false, true);
		}
		ChannelIdentity identity = identity(incoming.channel(), incoming.accountId(), incoming.senderId()).orElse(null);
		if (identity == null) {
			String id = "unknown-" + UUID.randomUUID();
			CustomerProfile profile = decode("{\"customer_id\":\"" + id + "\"}", CustomerProfile.class);
			db.sql("INSERT INTO dealer_customers(id, profile) VALUES (?, ?)").params(id, encode(profile)).update();
			identity = new ChannelIdentity(incoming.channel(), incoming.accountId(), incoming.senderId(), id, false, false, false, null);
			insertIdentity(identity);
		}
		ensureConversation(identity.customerId(), now);
		insertMessage(UUID.randomUUID().toString(), identity.customerId(), "INBOUND", incoming.channel(), incoming.accountId(),
				incoming.externalId(), incoming.content(), "RECEIVED", incoming.timestamp().toEpochMilli());
		// A replayed old webhook never opens a fresh platform messaging window.
		long eventTime = Math.min(now, incoming.timestamp().toEpochMilli());
		db.sql("UPDATE dealer_identities SET last_inbound_at = CASE WHEN last_inbound_at IS NULL OR last_inbound_at < ? THEN ? ELSE last_inbound_at END WHERE channel = ? AND account_id = ? AND external_id = ?")
				.params(eventTime, eventTime, incoming.channel(), incoming.accountId(), incoming.senderId()).update();
		db.sql("UPDATE dealer_conversations SET last_activity_at = ? WHERE customer_id = ?").params(now, identity.customerId()).update();
		db.sql("UPDATE dealer_executions SET status = 'CUSTOMER_RESPONDED' WHERE customer_id = ? AND status IN ('READY','AWAITING_RESPONSE','FOLLOW_UP_SCHEDULED','CHANNEL_SWITCHED','SENDING')")
				.param(identity.customerId()).update();
		event(identity.customerId(), null, "CUSTOMER_RESPONDED", now);
		return new Receipt(identity.customerId(), identity.verified(), false);
	}

	private void ensureConversation(String id, long now) {
		if (db.sql("SELECT COUNT(*) FROM dealer_conversations WHERE customer_id = ?").param(id).query(Integer.class).single() == 0) {
			db.sql("INSERT INTO dealer_conversations(customer_id, workflow, summary, last_activity_at) VALUES (?, ?, '', ?)")
					.params(id, encode(Workflow.initial()), now).update();
		}
	}
	public Workflow workflow(String id) {
		return decode(db.sql("SELECT workflow FROM dealer_conversations WHERE customer_id = ?").param(id).query(String.class).single(), Workflow.class);
	}
	public String summary(String id) {
		return db.sql("SELECT summary FROM dealer_conversations WHERE customer_id = ?").param(id).query(String.class).single();
	}
	public void workflow(String id, Workflow workflow, String summary, long now) {
		db.sql("UPDATE dealer_conversations SET workflow = ?, summary = ?, last_activity_at = ? WHERE customer_id = ?")
				.params(encode(workflow), summary, now, id).update();
	}
	public List<ConversationMessage> history(String id, int limit) {
		return db.sql("SELECT sequence, id, direction, channel, external_id, content, delivery_status, occurred_at FROM (SELECT * FROM dealer_messages WHERE customer_id = ? ORDER BY sequence DESC LIMIT ?) ORDER BY sequence")
				.params(id, limit).query(ConversationMessage.class).list();
	}
	public void insertMessage(String id, String customer, String direction, String channel, String account,
			String external, String content, String status, long at) {
		db.sql("INSERT INTO dealer_messages(id, customer_id, direction, channel, account_id, external_id, content, delivery_status, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
				.params(id, customer, direction, channel, account, external, content, status, at).update();
	}
	public void messageStatus(String id, String external, String status) {
		db.sql("UPDATE dealer_messages SET external_id = ?, delivery_status = ? WHERE id = ?").params(external, status, id).update();
	}
	public void delivery(String channel, String account, String external, String status, long now) {
		if (!List.of("sent", "delivered", "read", "failed").contains(status)) return;
		String mapped = status.toUpperCase(java.util.Locale.ROOT);
		int changed = db.sql("UPDATE dealer_messages SET delivery_status = ? WHERE channel = ? AND account_id = ? AND external_id = ? AND direction = 'OUTBOUND' AND delivery_status NOT IN ('READ','FAILED') AND NOT (delivery_status = 'DELIVERED' AND ? = 'SENT')")
				.params(mapped, channel, account, external, mapped).update();
		if (changed > 0) LOGGER.info("Dealership event={} channel={}", "MESSAGE_" + mapped, channel);
	}
	@Transactional
	public void optOut(String id, long now) {
		db.sql("UPDATE dealer_customers SET opted_out = TRUE WHERE id = ?").param(id).update();
		db.sql("UPDATE dealer_identities SET consent = FALSE, outbound_allowed = FALSE WHERE customer_id = ?").param(id).update();
		executionStatus(id, "OPTED_OUT");
		event(id, null, "OPTED_OUT", now);
	}
	public void executionStatus(String customer, String status) {
		db.sql("UPDATE dealer_executions SET status = ? WHERE customer_id = ? AND status NOT IN ('OPTED_OUT','EXHAUSTED','FAILED')")
				.params(status, customer).update();
	}
	public List<CampaignExecution> executions() {
		return db.sql("SELECT * FROM dealer_executions ORDER BY id").query(this::executionRow).list();
	}
	public Optional<CampaignExecution> execution(String customer, String campaign) {
		return db.sql("SELECT * FROM dealer_executions WHERE customer_id = ? AND campaign_id = ?")
				.params(customer, campaign).query(this::executionRow).optional();
	}
	private CampaignExecution executionRow(ResultSet rs, int row) throws SQLException {
		return new CampaignExecution(rs.getString("id"), rs.getString("customer_id"), rs.getString("campaign_id"),
				decode(rs.getString("configuration"), CampaignConfiguration.class), rs.getString("status"), rs.getString("current_channel"),
				rs.getLong("next_attempt_at"), rs.getObject("last_contact_at", Long.class), rs.getInt("failure_count"));
	}
	@Transactional
	public CampaignExecution start(String customer, CampaignConfiguration config, String base, long now) {
		customer(customer);
		if (optedOut(customer)) throw new DemoRequestException("Customer has opted out");
		Optional<CampaignExecution> existing = execution(customer, config.id());
		if (existing.isPresent()) return existing.get();
		if (executions().stream().anyMatch(e -> e.customerId().equals(customer) && !List.of("COMPLETED", "BOOKED", "CANCELLED", "EXHAUSTED", "FAILED").contains(e.status()))) {
			throw new DemoRequestException("Customer already has an active campaign");
		}
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO dealer_executions(id, customer_id, campaign_id, configuration, status, current_channel, next_attempt_at) VALUES (?, ?, ?, ?, 'READY', ?, ?)")
				.params(id, customer, config.id(), encode(config), base, now).update();
		db.sql("UPDATE dealer_conversations SET campaign_id = ? WHERE customer_id = ?").params(config.id(), customer).update();
		event(customer, id, "CAMPAIGN_STARTED", now);
		return execution(customer, config.id()).orElseThrow();
	}
	public int acceptedCount(String execution, String channel) {
		return db.sql("SELECT COUNT(*) FROM dealer_attempts WHERE execution_id = ? AND channel = ? AND status = 'ACCEPTED'")
				.params(execution, channel).query(Integer.class).single();
	}
	public boolean channelFinished(String execution, String channel) {
		return db.sql("SELECT COUNT(*) FROM dealer_channel_progress WHERE execution_id = ? AND channel = ?")
				.params(execution, channel).query(Integer.class).single() > 0;
	}
	public void finishChannel(String execution, String channel, String reason) {
		if (!channelFinished(execution, channel)) {
			db.sql("INSERT INTO dealer_channel_progress(execution_id, channel, reason) VALUES (?, ?, ?)")
					.params(execution, channel, reason).update();
		}
	}
	public Optional<CampaignConfiguration> activeCampaign(String customer) {
		return db.sql("SELECT e.configuration FROM dealer_executions e JOIN dealer_conversations c ON c.customer_id = e.customer_id AND c.campaign_id = e.campaign_id WHERE c.customer_id = ?")
				.param(customer).query((rs, row) -> decode(rs.getString(1), CampaignConfiguration.class)).optional();
	}
	public void schedule(CampaignExecution execution, String channel, String status, long next) {
		db.sql("UPDATE dealer_executions SET current_channel = ?, status = ?, next_attempt_at = ? WHERE id = ?")
				.params(channel, status, next, execution.id()).update();
	}
	@Transactional
	public Optional<Claim> claim(CampaignExecution execution, ChannelIdentity identity, int number, String script, String content, long now) {
		int acquired = db.sql("UPDATE dealer_executions SET status = 'SENDING' WHERE id = ? AND status IN ('READY','AWAITING_RESPONSE','FOLLOW_UP_SCHEDULED','CHANNEL_SWITCHED') AND NOT EXISTS (SELECT 1 FROM dealer_customers WHERE id = ? AND opted_out = TRUE)")
				.params(execution.id(), execution.customerId()).update();
		if (acquired == 0) return Optional.empty();
		var previous = db.sql("SELECT id, message_id FROM dealer_attempts WHERE execution_id = ? AND channel = ? AND attempt_number = ? AND status = 'FAILED'")
				.params(execution.id(), identity.channel(), number).query((rs, row) -> new Claim(rs.getString(1), rs.getString(2))).optional();
		Claim claim = previous.orElseGet(() -> new Claim(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
		if (previous.isPresent()) {
			db.sql("UPDATE dealer_attempts SET status = 'CLAIMED', attempted_at = ? WHERE id = ?").params(now, claim.id()).update();
			db.sql("UPDATE dealer_messages SET delivery_status = 'PENDING' WHERE id = ?").param(claim.messageId()).update();
		} else {
			insertMessage(claim.messageId(), execution.customerId(), "OUTBOUND", identity.channel(), identity.accountId(), null, content, "PENDING", now);
			db.sql("INSERT INTO dealer_attempts(id, execution_id, channel, attempt_number, script_key, status, message_id, attempted_at) VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?, ?)")
					.params(claim.id(), execution.id(), identity.channel(), number, script, claim.messageId(), now).update();
		}
		event(execution.customerId(), execution.id(), "OUTREACH_ATTEMPTED", now);
		return Optional.of(claim);
	}
	@Transactional
	public void complete(CampaignExecution execution, Claim claim, String providerId, String delivery, long now) {
		messageStatus(claim.messageId(), providerId, delivery);
		db.sql("UPDATE dealer_attempts SET status = 'ACCEPTED' WHERE id = ?").param(claim.id()).update();
		long delay = Math.max(execution.configuration().retryIntervalSeconds(), execution.configuration().cooldownSeconds()) * 1000;
		db.sql("UPDATE dealer_executions SET status = 'FOLLOW_UP_SCHEDULED', last_contact_at = ?, next_attempt_at = ?, failure_count = 0 WHERE id = ? AND status = 'SENDING'")
				.params(now, now + delay, execution.id()).update();
		event(execution.customerId(), execution.id(), "MESSAGE_SENT", now);
		event(execution.customerId(), execution.id(), "FOLLOW_UP_SCHEDULED", now);
	}
	@Transactional
	public void fail(CampaignExecution execution, Claim claim, boolean ambiguous, int maximumFailures, long now) {
		messageStatus(claim.messageId(), null, ambiguous ? "UNKNOWN" : "FAILED");
		db.sql("UPDATE dealer_attempts SET status = ? WHERE id = ?").params(ambiguous ? "UNKNOWN" : "FAILED", claim.id()).update();
		String status = ambiguous || execution.failureCount() + 1 >= maximumFailures ? "FAILED" : "FOLLOW_UP_SCHEDULED";
		db.sql("UPDATE dealer_executions SET status = ?, failure_count = failure_count + 1, next_attempt_at = ? WHERE id = ? AND status = 'SENDING'")
				.params(status, now + execution.configuration().retryIntervalSeconds() * 1000, execution.id()).update();
		event(execution.customerId(), execution.id(), ambiguous ? "SEND_OUTCOME_UNKNOWN" : "PROVIDER_SEND_FAILED", now);
	}
	@Transactional
	public void recover(long now) {
		db.sql("UPDATE dealer_messages SET delivery_status = 'UNKNOWN' WHERE delivery_status = 'PENDING'").update();
		db.sql("UPDATE dealer_attempts SET status = 'UNKNOWN' WHERE status = 'CLAIMED'").update();
		db.sql("UPDATE dealer_executions SET status = 'FAILED' WHERE status = 'SENDING'").update();
	}
	public Optional<Appointment> appointment(String customer) {
		return db.sql("SELECT a.id, a.customer_id, a.service_id, a.slot_id, a.status, s.starts_at FROM dealer_appointments a JOIN dealer_slots s ON a.slot_id = s.id WHERE a.customer_id = ? AND a.status = 'BOOKED'")
				.param(customer).query(Appointment.class).optional();
	}
	public List<AvailableSlot> slots(long now) {
		return db.sql("SELECT s.id, s.starts_at, s.capacity - (SELECT COUNT(*) FROM dealer_appointments a WHERE a.slot_id = s.id AND a.status = 'BOOKED') AS remaining FROM dealer_slots s WHERE s.starts_at > ? ORDER BY s.starts_at")
				.param(now).query(AvailableSlot.class).list().stream().filter(s -> s.remaining() > 0).toList();
	}
	@Transactional
	public Appointment book(String customer, String service, String slotId, boolean reschedule, long now) {
		db.sql("SELECT id FROM dealer_customers WHERE id = ? FOR UPDATE").param(customer).query(String.class).single();
		db.sql("SELECT id FROM dealer_slots WHERE id = ? FOR UPDATE").param(slotId).query(String.class).single();
		Appointment existing = appointment(customer).orElse(null);
		if (existing != null && !reschedule) return existing;
		if (existing == null && reschedule) throw new DemoRequestException("No appointment to reschedule");
		if (existing != null && existing.slotId().equals(slotId)) return existing;
		if (slots(now).stream().noneMatch(s -> s.id().equals(slotId))) throw new DemoRequestException("Appointment slot is unavailable");
		String id = existing == null ? UUID.randomUUID().toString() : existing.id();
		if (existing == null) db.sql("INSERT INTO dealer_appointments(id, customer_id, service_id, slot_id, status, updated_at) VALUES (?, ?, ?, ?, 'BOOKED', ?)")
				.params(id, customer, service, slotId, now).update();
		else db.sql("UPDATE dealer_appointments SET slot_id = ?, updated_at = ? WHERE id = ?").params(slotId, now, id).update();
		executionStatus(customer, "BOOKED");
		event(customer, null, existing == null ? "APPOINTMENT_CREATED" : "APPOINTMENT_RESCHEDULED", now);
		return appointment(customer).orElseThrow();
	}
	@Transactional
	public void cancel(String customer, long now) {
		db.sql("UPDATE dealer_appointments SET status = 'CANCELLED', updated_at = ? WHERE customer_id = ? AND status = 'BOOKED'")
				.params(now, customer).update();
		executionStatus(customer, "CANCELLED");
		event(customer, null, "APPOINTMENT_CANCELLED", now);
	}
	public void event(String customer, String execution, String type, long now) {
		db.sql("INSERT INTO dealer_events(customer_id, execution_id, event_type, occurred_at) VALUES (?, ?, ?, ?)")
				.params(customer, execution, type, now).update();
		LOGGER.info("Dealership event={} customer={} execution={}", type, customer, execution);
	}
	public List<Event> events(String customer) {
		return db.sql("SELECT sequence, event_type, occurred_at FROM dealer_events WHERE customer_id = ? ORDER BY sequence DESC LIMIT 100")
				.param(customer).query(Event.class).list();
	}
	public boolean hasHandoffRequest(String customer) {
		return representativeRequest(customer, null).isPresent();
	}
	public Optional<RepresentativeRequest> representativeRequest(String customer, String tracking) {
		return db.sql("SELECT tracking_number, status, service_id, requested_date, requested_time, created_at FROM dealer_requests WHERE customer_id = ? AND (? IS NULL OR tracking_number = ?) ORDER BY created_at DESC, tracking_number LIMIT 1")
				.params(customer, tracking, tracking).query(RepresentativeRequest.class).optional();
	}
	@Transactional
	public RepresentativeRequest recordRepresentativeRequest(String customer, Workflow workflow, long now) {
		db.sql("SELECT id FROM dealer_customers WHERE id = ? FOR UPDATE").param(customer).query(String.class).single();
		var pending = representativeRequest(customer, null).filter(r -> r.status().equals("PENDING_REVIEW"));
		if (pending.isPresent()) return pending.get();
		String tracking = "REQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(java.util.Locale.ROOT);
		db.sql("INSERT INTO dealer_requests(tracking_number, customer_id, status, service_id, requested_date, requested_time, created_at) VALUES (?, ?, 'PENDING_REVIEW', ?, ?, ?, ?)")
				.params(tracking, customer, workflow.serviceId(), workflow.date(), workflow.time(), now).update();
		event(customer, null, "HUMAN_HANDOFF_REQUESTED", now);
		return representativeRequest(customer, tracking).orElseThrow();
	}
	public List<Attempt> attempts(String customer) {
		return db.sql("SELECT a.id, a.execution_id, a.channel, a.attempt_number, a.script_key, a.status, a.attempted_at FROM dealer_attempts a JOIN dealer_executions e ON e.id = a.execution_id WHERE e.customer_id = ? ORDER BY a.attempted_at, a.channel, a.attempt_number")
				.param(customer).query(Attempt.class).list();
	}
	@Transactional
	public void reset() {
		for (String table : List.of("dealer_events", "dealer_requests", "dealer_attempts", "dealer_channel_progress", "dealer_executions", "dealer_messages", "dealer_appointments", "dealer_slots", "dealer_conversations", "dealer_identities", "dealer_customers")) {
			db.sql("DELETE FROM " + table).update();
		}
	}
	public record Receipt(String customerId, boolean verified, boolean duplicate) { }
	public record Claim(String id, String messageId) { }
	public record AvailableSlot(String id, long startsAt, int remaining) { }
	public record Event(long sequence, String eventType, long occurredAt) { }
	public record Attempt(String id, String executionId, String channel, int attemptNumber, String scriptKey, String status, long attemptedAt) { }
	private record LegacyMessage(String id, String direction, String external, String content, long at) { }
}
