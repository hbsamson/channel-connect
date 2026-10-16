package com.svi.channelconnect.telegram.service;

import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.svi.channelconnect.dealership.model.ChannelIdentity;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.telegram.exception.TelegramCampaignException;

/** Replaces an unused fictional identity before applying the shared ownership and history rules. */
public class TelegramIdentityLinker {
	private final DealershipRepository customers;
	private final JdbcClient db;

	public TelegramIdentityLinker(DealershipRepository customers, JdbcClient db) {
		this.customers = customers;
		this.db = db;
	}

	@Transactional
	public void link(ChannelIdentity identity, long now) {
		if (!"TELEGRAM".equals(identity.channel()) || !identity.verified()) {
			throw new TelegramCampaignException(HttpStatus.CONFLICT, "A verified Telegram identity is required");
		}
		ChannelIdentity source = customers.identity("TELEGRAM", identity.accountId(), identity.externalId())
				.orElseThrow(() -> new TelegramCampaignException(HttpStatus.CONFLICT, "No authenticated Telegram chat is recorded"));
		// Consistent lock ordering protects both customer histories during reassociation.
		Stream.of(identity.customerId(), source.customerId()).distinct().sorted().forEach(customerId ->
				db.sql("SELECT id FROM dealer_customers WHERE id = ? FOR UPDATE")
						.param(customerId).query(String.class).single());

		boolean replaced = false;
		for (ChannelIdentity target : customers.identities(identity.customerId())) {
			if (!"TELEGRAM".equals(target.channel()) || target.accountId().equals(identity.accountId())
					&& target.externalId().equals(identity.externalId())) continue;
			// The demo seeder reserves this namespace; real chat IDs and bot IDs are numeric.
			if (!"demo".equals(target.accountId()) || !target.externalId().startsWith("demo-")
					|| target.lastInboundAt() != null) {
				throw new TelegramCampaignException(HttpStatus.CONFLICT,
						"Customer already has another Telegram identity; only an unused fictional demo identity can be replaced");
			}
			int removed = db.sql("DELETE FROM dealer_identities WHERE customer_id = ? AND channel = 'TELEGRAM' "
					+ "AND account_id = 'demo' AND external_id = ? AND last_inbound_at IS NULL")
					.params(identity.customerId(), target.externalId()).update();
			if (removed != 1) {
				throw new TelegramCampaignException(HttpStatus.CONFLICT, "Telegram identity changed; review the customer link before retrying");
			}
			replaced = true;
		}
		// Joins this transaction: any ownership, opt-out or uniqueness failure restores the placeholder.
		customers.link(identity, now);
		if (replaced) customers.event(identity.customerId(), null, "TELEGRAM_DEMO_IDENTITY_REPLACED", now);
	}
}
