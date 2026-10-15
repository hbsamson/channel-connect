# Telegram connector

Adapted from the Telegram implementation in `svi-whatsapp-connector-main.zip`.
The connector lives entirely in this package and registers a `TELEGRAM`
`MessagingChannel` with the existing `MessagingAppHandler`.

## Configuration

The connector is disabled by default. Set `TELEGRAM_ENABLED=true`,
`TELEGRAM_BOT_TOKEN` (from BotFather), and `TELEGRAM_WEBHOOK_SECRET_TOKEN`.
The secret must contain 1–256 letters, digits, underscores or hyphens.
The complete inventory and optional settings are in the repository-root
`.env.example`. Supply real values through environment variables or the local
profile's `.env` import. Existing application credentials are still required.

| Variable | Default | Valid range |
| --- | --- | --- |
| `TELEGRAM_CONNECT_TIMEOUT` | `3s` | 1–30 seconds |
| `TELEGRAM_READ_TIMEOUT` | `10s` | 1–60 seconds |
| `TELEGRAM_WEBHOOK_MAX_REQUEST_BYTES` | `262144` | 1024–1048576 bytes |
| `TELEGRAM_WORKER_COUNT` | `4` | 1–32 |
| `TELEGRAM_QUEUE_CAPACITY_PER_WORKER` | `25` | 1–10000 |
| `TELEGRAM_DEDUPLICATION_CAPACITY` | `10000` | 1–1000000 updates |
| `TELEGRAM_DEDUPLICATION_TTL` | `24h` | 1 second–7 days after completion |
| `TELEGRAM_SHUTDOWN_TIMEOUT` | `10s` | 1–60 seconds |

AI replies use the channel-neutral `AiClient` and its existing `AI_PROVIDER`
configuration. With `AI_PROVIDER=mock`, accepted messages still send real Telegram
replies containing the mock AI output. The connector introduces no new dependency
or AI provider configuration.

## Webhook setup and behavior

Register a public HTTPS URL ending in `/webhooks/telegram` through Telegram's
[`setWebhook`](https://core.telegram.org/bots/api#setwebhook) method. Pass
`secret_token` matching `TELEGRAM_WEBHOOK_SECRET_TOKEN` and
`allowed_updates=["message"]`. Registration is an operator action; application
startup does not register or replace an existing webhook.

Only new private text messages from non-bot users generate replies. Other update
types are acknowledged without processing. Topic IDs are preserved in replies
and conversation IDs (`telegram:<chat-id>[:<topic-id>]`). Replies use plain text
and are limited to 4096 characters. The shared channel adapter accepts recipients
as `<chat-id>` or `<chat-id>:<topic-id>`; sends return `ACCEPTED_BY_PROVIDER` and
the Telegram message ID. This does not confirm recipient delivery or reading.
The existing dealership campaign eligibility rules continue to apply.

The Telegram-specific Spring Security chain requires exactly one
`X-Telegram-Bot-Api-Secret-Token` header on `POST /webhooks/telegram`, authenticates
before reading the body, and bounds declared and streamed body sizes. It applies
only to this path. When disabled, the application's existing security rules apply.
There is no public outbound Telegram REST endpoint.

| Webhook result | HTTP response |
| --- | --- |
| Accepted, duplicate, or unsupported update | 200, empty body |
| Missing, invalid, or repeated secret header | 401, safe JSON error |
| Malformed JSON or invalid supported-message fields | 400, safe JSON error |
| Authenticated payload too large | 413, safe JSON error |
| Dispatcher queue or deduplication storage full | 503, empty body |

Outbound calls use the official HTTPS Bot API host, refuse redirects, and use
explicit connection/read timeouts. HTTP observations are disabled for this client
because Telegram embeds the bot token in request URLs. Keep HTTP wire/debug
logging disabled to avoid exposing credentials or customer content.

## Dispatch and production

The bounded in-memory dispatcher exists only in `local` and `test`. It assigns
each conversation to one serial worker and deduplicates by `update_id`. It
preserves acceptance order within that worker, without reordering updates that
arrive out of order. Queued/in-flight reservations do not expire. Completed
reservations, including failures, remain for the configured TTL. Full storage
rejects new work rather than evicting active reservations.

A webhook 200 acknowledges ownership of an update. It does not confirm successful
AI processing or sending. Accepted work and deduplication state are lost on
restart. Failures are counted and logged by update ID and failure category,
without customer text, chat IDs, or credentials. Failed work is not automatically
replayed. Outbound calls are attempted once because `sendMessage` has no
idempotency key and timeout/server failures can have ambiguous outcomes.

Enabling Telegram outside `local`/`test` requires a durable
`TelegramInboundMessageDispatcher` bean. Startup fails without one. The durable
implementation must persist before acceptance, deduplicate updates, preserve
conversation ordering, and own recovery and ambiguous-send handling.

Counters: `telegram.webhook.messages` (`accepted`, `duplicate`, `overloaded`),
`telegram.webhook.events` (`ignored`), and `telegram.message.processing` (`failed`).

Repository policy prohibits creating, changing, or running tests unless explicitly
requested. Production compilation can be checked with `./mvnw compile` (Windows:
`.\mvnw.cmd compile`).
