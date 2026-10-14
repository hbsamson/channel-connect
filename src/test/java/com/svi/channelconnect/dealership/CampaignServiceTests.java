package com.svi.channelconnect.dealership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.dealership.config.DealershipProperties;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.controller.DemoController;
import com.svi.channelconnect.dealership.controller.DemoExceptionHandler;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.CampaignTemplates;
import com.svi.channelconnect.dealership.service.DealershipChannelGateway;
import com.svi.channelconnect.dealership.service.DealershipConversationService;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.service.SmsValidation;
import com.svi.channelconnect.support.SmsTestProperties;

class CampaignServiceTests {
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ZoneOffset.UTC);
	private final CampaignTemplates templates = new CampaignTemplates();
	private DealershipRepository store;
	private DemoConfiguration configuration;
	private DealershipProperties properties;
	private DealershipChannelGateway channels;
	private CampaignService campaigns;
	private JdbcClient db;
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		var datasource = new DriverManagerDataSource("jdbc:h2:mem:campaign-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
		db = JdbcClient.create(datasource);
		var json = new ObjectMapper();
		store = new DealershipRepository(db, json);
		properties = new DealershipProperties("Toyota Manila Bay", "classpath:dealership/catalog.json",
				"classpath:campaigns/registration-renewal.json", "classpath:dealership/customers.json",
				"classpath:prompts/dealership-assistant.md", false, 20, 3);
		configuration = new DemoConfiguration(json, new DefaultResourceLoader(), properties);
		store.seed(configuration.customers(), configuration.catalog(), clock.millis());
		channels = mock(DealershipChannelGateway.class);
		when(channels.canInitiate(any(), any(), anyString(), anyLong())).thenAnswer(invocation -> {
			com.svi.channelconnect.dealership.model.ChannelIdentity identity = invocation.getArgument(0);
			return identity.verified() && identity.consent() && identity.outboundAllowed();
		});
		when(channels.campaign(any(), any(), anyString(), anyString(), any(), anyString(), anyLong()))
				.thenAnswer(invocation -> new SendMessageResponseDTO("SIMULATED", "sim-" + UUID.randomUUID()));
		campaigns = new CampaignService(store, configuration, properties, templates, channels, clock);
		var controller = new DemoController(store, configuration, campaigns, mock(DealershipConversationService.class),
				clock, mock(SmsProperties.class));
		mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new DemoExceptionHandler()).build();
	}

	@AfterEach
	void shutdownDatabase() { db.sql("SHUTDOWN").update(); }

	@Test
	void approvedOpeningsFitTheDefaultSingleSmsSegment() {
		var smsProperties = SmsTestProperties.properties(Map.of());
		assertThat(smsProperties.maxSegments()).isEqualTo(1);
		for (var customer : configuration.customers()) {
			for (String script : List.of("script1", "script3")) {
				var variables = templates.values(customer, properties.name(), "registration renewal", null);
				String content = templates.render(configuration.campaign(), script, "en", variables);
				assertThatCode(() -> SmsValidation.content(content, smsProperties)).doesNotThrowAnyException();
				variables.put("first_name", "A".repeat(32));
				String longerName = templates.render(configuration.campaign(), script, "en", variables);
				assertThatCode(() -> SmsValidation.content(longerName, smsProperties)).doesNotThrowAnyException();
			}
		}
	}

	@Test
	void initialScriptIsRenderedLocallyAndStoredForLaterConversationContext() {
		for (String script : List.of("script1", "script3")) {
			String customer = script.equals("script1") ? "demo-renewal" : "demo-booking";
			var execution = campaigns.start(customer, "SMS", null, script);
			String expected = templates.render(configuration.campaign(), script, "en",
					templates.values(store.customer(customer), properties.name(), "registration renewal", null));
			assertThat(store.history(customer, 20)).singleElement().satisfies(message -> {
				assertThat(message.content()).isEqualTo(expected).doesNotContain("{{");
				assertThat(message.direction()).isEqualTo("OUTBOUND");
			});
			assertThat(store.attempts(customer)).singleElement().satisfies(attempt -> assertThat(attempt.scriptKey()).isEqualTo(script));
			assertThat(store.execution(customer, configuration.campaign().id()).orElseThrow().configuration().openingScript()).isEqualTo(script);
			assertThat(campaigns.start(customer, "SMS", null, script).id()).isEqualTo(execution.id());
		}
		verify(channels, times(2)).campaign(any(), any(), anyString(), anyString(), any(), anyString(), anyLong());
	}

	@Test
	void selectedOpeningDoesNotReplaceFollowupsOrOtherChannelScripts() {
		var execution = campaigns.start("demo-no-response", "SMS", null, "script3");
		campaigns.advance(execution.id(), true);
		campaigns.advance(execution.id(), true);
		campaigns.advance(execution.id(), true);
		assertThat(store.attempts("demo-no-response")).extracting(attempt -> attempt.scriptKey())
				.containsExactly("script3", "followup1", "followup2", "switch");
	}

	@Test
	void bulkStartIsIdempotentAndReportsMissingOptedOutAndConflictingRecipients() {
		store.optOut("demo-privacy", clock.millis());
		campaigns.start("demo-reschedule", "SMS", configuration.campaign().withOpeningScript("script1"));
		var alternateJson = new ObjectMapper();
		var alternate = (tools.jackson.databind.node.ObjectNode) alternateJson.valueToTree(configuration.campaign());
		alternate.put("id", "new-campaign");
		var selected = alternateJson.treeToValue(alternate, com.svi.channelconnect.dealership.model.CampaignConfiguration.class);
		var customers = List.of("demo-renewal", "absent-customer", "demo-privacy", "demo-reschedule", "demo-booking");
		var first = campaigns.startBulk(customers, "SMS", selected, "script3");
		assertThat(first).extracting(result -> result.customerId()).containsExactlyElementsOf(customers);
		assertThat(first.get(0).execution()).isNotNull();
		assertThat(first.get(1).errorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
		assertThat(first.get(2).errorCode()).isEqualTo("OPTED_OUT");
		assertThat(first.get(3).errorCode()).isEqualTo("CAMPAIGN_CONFLICT");
		assertThat(first.get(4).execution()).isNotNull();
		var repeated = campaigns.startBulk(customers, "SMS", selected, "script3");
		assertThat(repeated.get(0).execution().id()).isEqualTo(first.get(0).execution().id());
		assertThat(repeated.get(4).execution().id()).isEqualTo(first.get(4).execution().id());
		assertThat(store.history("demo-renewal", 20)).hasSize(1);
		assertThat(store.history("demo-booking", 20)).hasSize(1);
		verify(channels, times(3)).campaign(any(), any(), anyString(), anyString(), any(), anyString(), anyLong());
	}

	@Test
	void bulkStartUsesExistingDurableSmsQueueAndPreservesSubmissionState() {
		when(channels.campaign(any(), any(), anyString(), anyString(), any(), anyString(), anyLong()))
				.thenAnswer(invocation -> new SendMessageResponseDTO("QUEUED", "queued-" + UUID.randomUUID()));
		var recipients = List.of("demo-renewal", "demo-booking");
		var results = campaigns.startBulk(recipients, "SMS", null, "script1");
		assertThat(results).allSatisfy(result -> {
			assertThat(result.errorCode()).isNull();
			assertThat(result.execution().status()).isEqualTo("SMS_QUEUED");
			assertThat(store.acceptedCount(result.execution().id(), "SMS")).isZero();
			assertThat(store.history(result.customerId(), 20)).singleElement()
					.satisfies(message -> assertThat(message.deliveryStatus()).isEqualTo("QUEUED"));
		});
		campaigns.startBulk(recipients, "SMS", null, "script1");
		verify(channels, times(2)).campaign(any(), any(), anyString(), anyString(), any(), anyString(), anyLong());
	}

	@Test
	void rejectsInvalidBatchOrUnapprovedScriptBeforeSendingAnyMessage() {
		for (List<String> invalid : List.of(List.<String>of(), List.of("demo-renewal", "demo-renewal"),
				List.of("bad id"), IntStream.range(0, 101).mapToObj(index -> "customer-" + index).toList())) {
			assertThatThrownBy(() -> campaigns.startBulk(invalid, "SMS", null, "script1")).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> campaigns.startBulk(List.of("demo-renewal"), "SMS", null, "followup2"))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(channels);
		assertThat(store.executions()).isEmpty();
	}

	@Test
	void bulkApiAcceptsSelectedScriptAndReturnsIndependentResults() throws Exception {
		mvc.perform(post("/api/admin/demo/campaigns/bulk").contentType("application/json")
				.content("{\"customerIds\":[\"demo-renewal\",\"absent-customer\"],\"baseChannel\":\"SMS\",\"openingScript\":\"script3\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].execution.configuration.openingScript").value("script3"))
				.andExpect(jsonPath("$[1].errorCode").value("CUSTOMER_NOT_FOUND"));
	}

	@Test
	void bulkApiRejectsNullConfiguredChannelsBeforeStartingAnyRecipient() throws Exception {
		var json = new ObjectMapper();
		for (String field : List.of("fallbackChannels", "channelPriority")) {
			var embedded = (tools.jackson.databind.node.ObjectNode) json.valueToTree(configuration.campaign());
			embedded.putArray(field).addNull();
			var request = json.createObjectNode();
			request.putArray("customerIds").add("demo-renewal").add("demo-booking");
			request.put("baseChannel", "SMS");
			request.put("openingScript", "script1");
			request.set("configuration", embedded);
			mvc.perform(post("/api/admin/demo/campaigns/bulk").contentType("application/json")
					.content(json.writeValueAsString(request)))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
		}
		assertThat(store.executions()).isEmpty();
		verifyNoInteractions(channels);
	}

	@Test
	void campaignApisRejectMalformedSelectionAndMissingRecipientList() throws Exception {
		mvc.perform(post("/api/admin/demo/campaigns/start").contentType("application/json")
				.content("{\"customerId\":\"demo-renewal\",\"openingScript\":\"unapproved\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/admin/demo/campaigns/bulk").contentType("application/json")
				.content("{\"customerIds\":[],\"openingScript\":\"script1\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/admin/demo/campaigns/bulk").contentType("application/json")
				.content("{\"customerIds\":[\"bad id\"],\"openingScript\":\"script1\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(channels);
	}
}
