package com.svi.channelconnect.sms.persistence;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.support.SmsTestProperties;

class SmsPersistenceTests {
	@TempDir Path directory;
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);
	@Test void survivesDatabaseReopenAndRetainsUnknownIdempotencyTombstones() {
		String url = "jdbc:h2:file:" + directory.resolve("sms").toString().replace('\\', '/') + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0";
		String[] ids = new String[2];
		try (var ds = datasource(url)) {
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			var store = repository(ds); var tx = transaction(ds);
			tx.executeWithoutResult(status -> {
				store.accept("sim-1", 123, "+639000000001", "Durable test");
				ids[0] = store.enqueue("safe", "sim-1", "+639000000001", "Durable test", null).messageId();
				ids[1] = store.enqueue("uncertain", "sim-2", "+639000000002", "Durable test", null).messageId();
				store.claimOutbound(ids[0]); store.claimOutbound(ids[1]); store.transmitting(ids[1]);
			});
		}
		try (var ds = datasource(url)) {
			var store = repository(ds); var tx = transaction(ds);
			tx.executeWithoutResult(status -> store.recover());
			assertThat(store.outbound(ids[0]).orElseThrow().status()).isEqualTo("QUEUED");
			assertThat(store.outbound(ids[1]).orElseThrow().status()).isEqualTo("UNKNOWN");
			Boolean fresh = tx.execute(status -> store.accept("sim-1", 123, "+639000000001", "Durable test"));
			assertThat(fresh).isFalse();
			assertThat(tx.execute(status -> store.enqueue("uncertain", "sim-2", "+639000000002", "Durable test", null)).status()).isEqualTo("UNKNOWN");
			JdbcClient.create(ds).sql("UPDATE sms_outbox SET completed_at = 1 WHERE id = ?").param(ids[1]).update();
			tx.executeWithoutResult(status -> store.maintain());
			assertThat(store.outbound(ids[1]).orElseThrow().content()).isNull();
			assertThat(tx.execute(status -> store.enqueue("uncertain", "sim-2", "+639000000002", "Durable test", null)).replay()).isTrue();
		}
	}
	@Test void boundsQueueAcceptanceAndRollsBackFailedTransactions() {
		try (var ds = datasource("jdbc:h2:mem:smscapacity;DB_CLOSE_DELAY=-1")) {
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			var db = JdbcClient.create(ds);
			var store = new SmsMessageRepository(db, SmsTestProperties.properties(Map.of("sms.queue-capacity", "1")), clock, new DealershipRepository(db, new ObjectMapper()));
			var tx = transaction(ds);
			tx.executeWithoutResult(status -> store.accept("sim-1", 123, "+639000000001", "Durable test"));
			assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.accept("sim-1", 124, "+639000000001", "Durable test"))).isInstanceOf(SmsException.class);
			assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
				store.enqueue("rollback", "sim-1", "+639000000001", "Durable test", null); throw new IllegalStateException("Injected failure");
			})).isInstanceOf(IllegalStateException.class);
			assertThat(store.outboundBacklog()).isZero();
		}
	}
	@Test void selectsAnOldestMessageForEachChannelWithoutBacklogStarvation() {
		try (var ds = datasource("jdbc:h2:mem:smsfairness;DB_CLOSE_DELAY=-1")) {
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			var store = repository(ds); var tx = transaction(ds);
			tx.executeWithoutResult(status -> {
				for (int i = 0; i < 110; i++) store.enqueue("backlog-" + i, "sim-1", "+639000000001", "Durable test", null);
				store.enqueue("other-sim", "sim-2", "+639000000002", "Durable test", null);
			});
			assertThat(store.pendingOutbound()).extracting(com.svi.channelconnect.sms.model.SmsOutbound::accountId).containsExactlyInAnyOrder("sim-1", "sim-2");
		}
	}
	private SmsMessageRepository repository(HikariDataSource ds) {
		var db = JdbcClient.create(ds); return new SmsMessageRepository(db, SmsTestProperties.properties(Map.of()), clock, new DealershipRepository(db, new ObjectMapper()));
	}
	private TransactionTemplate transaction(HikariDataSource ds) { return new TransactionTemplate(new DataSourceTransactionManager(ds)); }
	private HikariDataSource datasource(String url) {
		var ds = new HikariDataSource(); ds.setJdbcUrl(url); ds.setUsername("sa"); ds.setPassword(""); ds.setMaximumPoolSize(2); return ds;
	}
}
