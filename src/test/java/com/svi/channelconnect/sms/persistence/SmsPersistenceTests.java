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
	@Test void archivesRemoteHistoryWithoutQueuingRepliesAndAppliesRetention() {
		try (var ds = datasource("jdbc:h2:mem:smsarchive;DB_CLOSE_DELAY=-1")) {
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			var store = repository(ds);
			var tx = transaction(ds);
			assertThat(tx.<Boolean>execute(status -> store.archiveRemote("sim-1", 100, "TESTBANK", "Historical message", clock.millis()))).isTrue();
			assertThat(tx.<Boolean>execute(status -> store.archiveRemote("sim-1", 100, "TESTBANK", "Historical message", clock.millis()))).isFalse();
			assertThat(store.inboundBacklog()).isZero();
			assertThat(store.pendingInbound()).isEmpty();
			assertThat(store.inbox(java.util.List.of("sim-1"), null, 0, 100).getFirst().status()).isEqualTo("STORED");
			JdbcClient.create(ds).sql("UPDATE sms_inbox SET received_at = ?").param(clock.millis() - java.time.Duration.ofDays(31).toMillis()).update();
			tx.executeWithoutResult(status -> store.maintain());
			assertThat(store.inbox(java.util.List.of("sim-1"), null, 0, 100)).isEmpty();
		}
	}
	@Test void listsRetainedInboxAcrossAccountsWithoutExposingOtherRoutesOrExpiredContent() {
		try (var ds = datasource("jdbc:h2:mem:smsinboxlisting;DB_CLOSE_DELAY=-1")) {
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			var store = repository(ds);
			transaction(ds).executeWithoutResult(status -> {
				store.acceptRemote("sim-1", 1, "+639000000001", "First", 200);
				store.acceptRemote("sim-2", 2, "+639000000002", "Second", 100);
				store.acceptRemote("unconfigured", 3, "+639000000001", "Other route", 300);
				store.acceptRemote("sim-1", 4, "+639000000001", "Expired", 400);
			});
			var db = JdbcClient.create(ds);
			db.sql("UPDATE sms_inbox SET status = 'PROCESSED' WHERE receive_id = 1").update();
			db.sql("UPDATE sms_inbox SET content = NULL WHERE receive_id = 4").update();
			var accounts = java.util.List.of("sim-1", "sim-2");
			var all = store.inbox(accounts, null, 0, 100);
			assertThat(all).extracting(com.svi.channelconnect.sms.dto.response.SmsInboxResponse.Message::content).containsExactly("First", "Second");
			assertThat(all).extracting(com.svi.channelconnect.sms.dto.response.SmsInboxResponse.Message::accountId).containsExactly("sim-1", "sim-2");
			assertThat(store.inbox(accounts, null, all.getFirst().cursor(), 1)).containsExactly(all.getLast());
			assertThat(store.inbox(accounts, "+639000000002", 0, 100)).containsExactly(all.getLast());
			assertThat(store.inbox(java.util.List.of("sim-1"), null, 0, 100)).containsExactly(all.getFirst());
		}
	}
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
	@Test void upgradesExistingInboxAndKeepsCursorAndRemoteBaselineAcrossReopen() {
		String url = "jdbc:h2:file:" + directory.resolve("upgrade").toString().replace('\\', '/') + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
		long firstCursor;
		try (var ds = datasource(url)) {
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("4").load().migrate();
			JdbcClient.create(ds).sql("INSERT INTO sms_inbox(id, account_id, receive_id, sender, content, fingerprint, received_at, status, next_attempt_at) VALUES ('old-1', 'sim-1', 77, '+639000000001', 'Retained receipt', 'test', 100, 'RECEIVED', 100)").update();
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			var store = repository(ds);
			firstCursor = store.received("sim-1", null, 0, 10).getFirst().cursor();
			assertThat(firstCursor).isPositive();
			assertThat(transaction(ds).<Long>execute(status -> store.receiveBaseline("source", 1234))).isEqualTo(1234L);
		}
		try (var ds = datasource(url)) {
			var store = repository(ds);
			assertThat(store.received("sim-1", null, 0, 10).getFirst().cursor()).isEqualTo(firstCursor);
			assertThat(transaction(ds).<Long>execute(status -> store.receiveBaseline("source", 9999))).isEqualTo(1234L);
			transaction(ds).executeWithoutResult(status -> store.acceptRemote("sim-1", 78, "+639000000001", "New reply", 200));
			var next = store.received("sim-1", "+639000000001", firstCursor, 10);
			assertThat(next).hasSize(1);
			assertThat(next.getFirst().content()).isEqualTo("New reply");
			assertThat(next.getFirst().receivedAt()).isEqualTo(200);
			assertThat(store.received("sim-2", null, 0, 10)).isEmpty();
		}
	}
	@Test void ordersConversationByTransmissionRatherThanQueueTime() {
		try (var ds = datasource("jdbc:h2:mem:smshistoryorder;DB_CLOSE_DELAY=-1")) {
			Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
			var db = JdbcClient.create(ds);
			var store = repository(ds);
			var tx = transaction(ds);
			tx.executeWithoutResult(status -> {
				store.acceptRemote("sim-1", 123, "+639000000001", "Customer first", 200);
				String id = store.pendingInbound().getFirst().id();
				store.claimInbound(id); store.completeInbound(id);
				String outbound = store.enqueue("delayed", "sim-1", "+639000000001", "Delayed assistant", null).messageId();
				db.sql("UPDATE sms_outbox SET created_at = 100, transmitted_at = 300, status = 'GATEWAY_SENT' WHERE id = ?").param(outbound).update();
			});
			assertThat(store.conversationHistory("sim-1", "+639000000001", "none")).extracting(turn -> turn.get("content"))
					.containsExactly("Customer first", "Delayed assistant");
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
