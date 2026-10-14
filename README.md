# SVI Channel Connect

A Java 25 Spring Boot application for Messenger and WhatsApp, with one entry point: `com.svi.channelconnect.ChannelConnectApplication`.

SMS is available through an independent provider boundary, the documented GoIP UDP protocol, a direct GoIP8 HTTP web form, and a hosted PHP HTTP API matching the operator's Postman send/receive collection. It is disabled by default; explicit mock mode uses an actual loopback UDP gateway simulator. See [GoIP SMS setup, protocol details, local API examples, and hardware cutover prerequisites](docs/goip-sms.md). Physical GoIP operation from this application has not been verified.

## Architecture

Connectors live under `com.svi.channelconnect.messenger` and `com.svi.channelconnect.whatsapp`. AI adapters live under `ai` and shared messaging contracts under `common`.

The SMS connector lives under `sms`, with a persistent inbox/outbox, bounded workers, scoped conversations, and explicit queued, gateway-reported, failed, and unknown statuses. It reuses the existing dealership assistant and AI boundary. An unknown transmission is blocked from automatic resend.

SMS supports bearer-protected `/api/v1/sms/send` and cursor-based `/api/v1/sms/receive` polling, canned `script1`/`script3` bulk campaign openings, and retained conversation context for appointment and service/price-objection replies. The `sms-http-api` profile uses the hosted Postman contract with continuous JSON inbox polling; direct HTTP sending can also opt into authenticated UDP reception. See [the conversation flow, bulk request, and receive configuration](docs/goip-sms.md). Booking uses configured slots; live scheduling and Redshift synchronization are not connected.

`MessagingAppHandler` selects a `MessagingChannel` adapter by its channel name. Messenger and WhatsApp normalize authenticated inbound events into the dealership conversation service, which resolves verified customer identities, persists cross-channel context, and executes grounded service/appointment workflows. Existing webhook authentication, queues and provider clients stay in each connector. Shared dealership behavior lives under `dealership`; channel-neutral AI interpretation remains under `ai`.

To add a platform, implement `common.service.MessagingChannel` as a Spring bean with a unique channel name, add its authenticated webhook and dispatch adapter, and call the handler from that workflow. Adding an adapter does not expose a public send endpoint. Provider acceptance is separate from delivery confirmation. The shared text operation is for channel workflows; WhatsApp application-initiated messages still use the guarded template endpoint.

## Configuration and local development

Use `.env.example` as an environment-variable inventory; Spring does not load it automatically. Configure credentials through environment variables. `AI_PROVIDER=mock` selects deterministic dealership intent parsing; `openai` uses the shared history-aware AI boundary. The legacy Messenger OpenAI client remains available in the repository, but dealership workflows use the configurable prompt and approved catalog.

For the October 16 campaign demo, run the PowerShell launcher and scenario runner described in [DEMO_GUIDE.md](DEMO_GUIDE.md). The launcher explicitly loads `.env` and supplies offline defaults for inactive connectors. SMS and Telegram are simulations; real Meta delivery requires configured tester accounts and live verification. The guide covers script execution, identity linking, campaign controls, persistence, and channel limitations.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

Configuration, logging and Flyway migrations are under `src/main/resources`. Test settings and webhook fixtures are under `src/test/resources`. Tests use fake credentials and mocked external providers.

## HTTP contracts

- Messenger webhook: `GET` and `POST /api/webhooks/messenger`.
- WhatsApp webhook: `GET` and `POST /webhooks/whatsapp`.
- Admin conversation deletion: `DELETE /api/admin/conversations/{id}`, requiring the admin role through HTTP Basic authentication.
- Dealership demo controls: `/api/admin/demo/**`, requiring HTTP Basic admin authentication and the `local` or `test` profile.
- Local WhatsApp templates: `POST /api/v1/whatsapp/messages`, requiring a bearer token, recipient/template allowlists and an `Idempotency-Key`.

Webhook POST requests require valid Meta signatures. A WhatsApp template response of `202 Accepted` means Meta accepted the submission; delivery is confirmed by a later webhook.

## Delivery limitations

WhatsApp's dispatcher, outbound endpoint and process-local idempotency store are restricted to `local` and `test`. Production startup requires a durable WhatsApp dispatcher. Messenger's work queue is also in memory and can lose queued events on restart; its conversation persistence does not make the queue durable. Production delivery requires durable dispatch, deduplication and operational recovery.

Campaign claims and attempts persist in H2; an interrupted send becomes an unknown outcome and is blocked from automatic resend. The demo supports one application instance, configured Manila appointment slots, and internal demo bookings. Human handoff records an event without sending external notifications. V3 adds dealership tables while preserving V1/V2 Messenger history, imported only for an explicitly verified identity.
