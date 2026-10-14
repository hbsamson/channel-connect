# SVI Channel Connect

A Java 25 Spring Boot application for Messenger and WhatsApp, with one entry point: `com.svi.messaging.MessagingApplication`.

## Architecture

Connectors live under `com.svi.messaging.messenger` and `com.svi.messaging.whatsapp`. AI adapters live under `ai` and shared messaging contracts under `common`.

`MessagingApplicationHandler` selects a `MessagingChannel` adapter by its channel name. Messenger uses it to submit replies after generating them from persisted conversation history. WhatsApp uses its stateless AI reply operation. Existing webhook authentication, queues, deduplication and provider-specific validation stay in each connector.

To add a platform, implement `common.service.MessagingChannel` as a Spring bean with a unique channel name, add its authenticated webhook and dispatch adapter, and call the handler from that workflow. Adding an adapter does not expose a public send endpoint. Provider acceptance is separate from delivery confirmation. The shared text operation is for channel workflows; WhatsApp application-initiated messages still use the guarded template endpoint.

## Configuration and local development

Use `.env.example` as an environment-variable inventory; Spring does not load it automatically. Configure both connectors, the Messenger OpenAI client, and admin credentials through environment variables. `AI_PROVIDER=mock` selects the stateless mock AI used by WhatsApp; Messenger retains its history-aware OpenAI integration and requires its OpenAI configuration.

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
- Local WhatsApp templates: `POST /api/v1/whatsapp/messages`, requiring a bearer token, recipient/template allowlists and an `Idempotency-Key`.

Webhook POST requests require valid Meta signatures. A WhatsApp template response of `202 Accepted` means Meta accepted the submission; delivery is confirmed by a later webhook.

## Delivery limitations

WhatsApp's dispatcher, outbound endpoint and process-local idempotency store are restricted to `local` and `test`. Production startup requires a durable WhatsApp dispatcher. Messenger's work queue is also in memory and can lose queued events on restart; its conversation persistence does not make the queue durable. Production delivery requires durable dispatch, deduplication and operational recovery.
