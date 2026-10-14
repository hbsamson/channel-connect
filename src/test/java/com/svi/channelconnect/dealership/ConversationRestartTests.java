package com.svi.channelconnect.dealership;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.dealership.model.*;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;

class ConversationRestartTests {
	@TempDir Path directory;
	@Test
	void migratesExistingHandoffEventsIntoTrackedRequestsWithoutDuplicatingThem() {
		var datasource = new DriverManagerDataSource("jdbc:h2:mem:oldhandoff;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		Flyway.configure().dataSource(datasource).locations("classpath:db/migration").target("3").load().migrate();
		var db = JdbcClient.create(datasource);
		db.sql("INSERT INTO dealer_customers(id, profile) VALUES ('legacy-request', '{}')").update();
		db.sql("INSERT INTO dealer_events(customer_id, event_type, occurred_at) VALUES ('legacy-request', 'HUMAN_HANDOFF_REQUESTED', 1000)").update();
		Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
		var store = new DealershipRepository(db, new ObjectMapper());
		var migrated = store.representativeRequest("legacy-request", null).orElseThrow();
		assertThat(migrated.trackingNumber()).matches("REQ-[0-9]{16}");
		assertThat(migrated.status()).isEqualTo("PENDING_REVIEW");
		assertThat(store.recordRepresentativeRequest("legacy-request", Workflow.initial(), 2000)).isEqualTo(migrated);
		assertThat(store.events("legacy-request")).hasSize(1);
	}
	@Test
	void messagesWorkflowAttemptsAndAppointmentsSurviveClosingAndReopeningDatabase() throws Exception {
		String url = "jdbc:h2:file:" + directory.resolve("history").toString().replace('\\', '/') + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
		var datasource = new DriverManagerDataSource(url, "sa", "");
		Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
		var mapper = new ObjectMapper();
		var store = new DealershipRepository(JdbcClient.create(datasource), mapper);
		var customers = List.of(mapper.readValue(getClass().getResourceAsStream("/dealership/customers.json"), CustomerProfile[].class));
		var catalog = mapper.readValue(getClass().getResourceAsStream("/dealership/catalog.json"), com.svi.channelconnect.dealership.config.DemoConfiguration.Catalog.class);
		var config = mapper.readValue(getClass().getResourceAsStream("/campaigns/registration-renewal.json"), CampaignConfiguration.class);
		long now = Instant.parse("2026-10-14T01:00:00Z").toEpochMilli();
		store.seed(customers, catalog, now);
		var message = new IncomingMessage("SMS", "demo", "demo-renewal-sms", "in-1", "Friday 9am", Instant.ofEpochMilli(now));
		store.receive(message, now);
		store.workflow("demo-renewal", new Workflow("BOOK", "registration", "2026-10-16", "09:00", "en"), "Customer prefers Friday morning", now);
		store.book("demo-renewal", "registration", "2026-10-16-0900", false, now);
		var request = store.recordRepresentativeRequest("demo-renewal", new Workflow("HANDOFF_CONFIRM", "registration", "2026-10-16", "09:00", "en"), now);
		var execution = store.start("demo-no-response", config, "SMS", now);
		var identity = store.identities("demo-no-response").stream().filter(i -> i.channel().equals("SMS")).findFirst().orElseThrow();
		var claim = store.claim(execution, identity, 1, "opening", "demo reminder", now).orElseThrow();
		store.complete(execution, claim, "out-1", "SIMULATED", now);
		// DriverManagerDataSource closes every connection after use, so reopening loads disk state.
		var reopened = new DealershipRepository(JdbcClient.create(new DriverManagerDataSource(url, "sa", "")), mapper);
		assertThat(reopened.history("demo-renewal", 10)).singleElement().satisfies(m -> assertThat(m.content()).isEqualTo("Friday 9am"));
		assertThat(reopened.workflow("demo-renewal").date()).isEqualTo("2026-10-16");
		assertThat(reopened.appointment("demo-renewal")).isPresent();
		assertThat(reopened.representativeRequest("demo-renewal", request.trackingNumber())).contains(request);
		assertThat(reopened.acceptedCount(execution.id(), "SMS")).isEqualTo(1);
		assertThat(reopened.receive(message, now).duplicate()).isTrue();
		var second = reopened.claim(reopened.execution("demo-no-response", config.id()).orElseThrow(), identity, 2, "followup1", "demo followup", now).orElseThrow();
		reopened.recover(now);
		assertThat(reopened.execution("demo-no-response", config.id()).orElseThrow().status()).isEqualTo("FAILED");
		assertThat(reopened.history("demo-no-response", 10)).filteredOn(m -> m.id().equals(second.messageId())).singleElement().satisfies(m -> assertThat(m.deliveryStatus()).isEqualTo("UNKNOWN"));
	}
}
