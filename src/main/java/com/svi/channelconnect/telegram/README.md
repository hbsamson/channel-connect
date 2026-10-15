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

Incoming messages use the same `DealershipConversationService` as WhatsApp and
the shared dealership assistant used by the regular SMS workflow. This supplies
the configured dealership prompt, approved service catalog, campaign configuration,
workflow, chronological history and conversation summary. Replies are stored and
sent through the registered `TELEGRAM` channel. `AI_PROVIDER=mock` uses the shared
deterministic dealership intent parser; it no longer returns a generic mock echo.
Existing dealership and AI configuration applies; no new AI settings are required.

Identities are scoped to the bot's numeric ID and the recipient's chat/topic ID.
New identities are unverified and can ask general dealership questions; personal
customer records and booking actions remain subject to the shared verification
rules. A Telegram message does not automatically link a chat to an existing
customer profile. The separate SMS/Messenger TMBG booking demo is not selected.

Messages sent directly to Telegram's `sendMessage` API bypass this application
and are not added to its history or campaign workflow. Incoming replies still use
dealership context, but the assistant cannot see that externally sent campaign
text. Use the campaign-start endpoint below to retain the approved opener as
conversation context instead of calling Telegram's API directly.

## Start a dealership campaign from Postman

`POST http://localhost:8080/api/v1/telegram/campaign/start` is available only with
`local`/`test`, `TELEGRAM_ENABLED=true`, and `TELEGRAM_CAMPAIGN_ENABLED=true`.
It reuses the existing dealership campaign engine, scripts, opt-out handling,
quiet hours, attempt limits, persistence and conversation history. Incoming
webhook replies stop campaign follow-ups and use the linked customer context.
It starts a Telegram-only campaign; other channels are not used as fallback.

Configure these values in your local `.env` (keep real tokens and recipients
outside source control):

```dotenv
TELEGRAM_CAMPAIGN_ENABLED=true
TELEGRAM_CAMPAIGN_API_TOKEN=<a-separate-random-token-of-at-least-32-characters>
TELEGRAM_CAMPAIGN_ALLOWED_RECIPIENTS=<your-numeric-chat-id>
TELEGRAM_CAMPAIGN_ALLOWED_SCRIPTS=opening,followup1,followup2,script1,script3
```

The API token must contain 32–256 letters, digits, underscores or hyphens. It is
separate from both the Telegram bot token and webhook secret. The recipient
allowlist uses exact chat IDs, optionally `chatId:topicId`, separated by commas.
Restart after changing configuration. The application still needs its normal
Meta, AI, database and admin settings.

Before calling the endpoint:

1. Have the customer send `/start` or another text message to this bot while its
   application webhook is working. This records the chat identity in the app.
2. Ensure the dealership customer record exists. The existing admin API can seed
   configured demo records with `POST /api/admin/demo/seed` or import records via
   `POST /api/admin/demo/customers/import`. These admin routes use Basic Auth with
   `ADMIN_USERNAME` and `ADMIN_PASSWORD`; `GET /api/admin/demo/customers` lists the
   available `customer_id` values.
3. Verify that the chat belongs to the selected customer and obtain campaign
   consent. The endpoint treats the two `true` flags below as the authenticated
   operator's explicit confirmation. Knowing a chat ID alone does not verify
   ownership. It will not reassign an already verified identity to another
   customer or restore an existing opt-out. Seeded demo customers already have a
   fictional Telegram identity (`accountId=demo`, `externalId=demo-...`). Starting
   a real Telegram campaign replaces that placeholder only if it has never
   received a message. Replacement and transfer of the real chat's existing
   history run in one database transaction; failure rolls back both operations.
   A different real identity or a demo identity with inbound activity returns
   409 and requires review. Existing messages and other channel links are kept.

In Postman choose **Bearer Token** and use `TELEGRAM_CAMPAIGN_API_TOKEN`. Add:

```http
Content-Type: application/json
Idempotency-Key: telegram-opening-001
```

Raw JSON body (replace placeholders with an existing customer ID and allowed chat):

```json
{
  "customerId": "<existing-customer-id>",
  "recipient": "<numeric-chat-id>",
  "identityVerified": true,
  "consent": true,
  "openingScript": "opening"
}
```

`openingScript` defaults to `opening`, the same dealership registration reminder
used by the common campaign flow, personalized with the configured customer name,
vehicle and renewal date. `script1` and `script3` select the shorter approved SMS
openers. The request accepts no arbitrary outbound message text, bot token,
account ID or campaign configuration. Required customer template fields must be
populated. The database permits one Telegram identity per customer, across all
bots and topics. An existing real link is never automatically replaced.

A successful campaign operation returns HTTP 202:

```json
{
  "executionId": "<execution-id>",
  "campaignId": "registration-renewal",
  "status": "FOLLOW_UP_SCHEDULED"
}
```

202 acknowledges the campaign operation, not recipient delivery. Inspect `status`:
`FOLLOW_UP_SCHEDULED` means the opener was accepted by the provider;
`READY` can mean it is waiting for configured quiet hours; `FAILED` means sending
failed or had an unknown outcome. Telegram does not provide a recipient delivery
receipt here. `GET /api/admin/demo/campaigns` (admin Basic Auth) returns current
execution states. Automatic follow-ups or quiet-hour advancement require the
existing `DEALERSHIP_SCHEDULER_ENABLED=true` setting; otherwise the admin campaign
advance endpoint is available. Any customer reply stops scheduled follow-ups.

Reuse the same `Idempotency-Key` and body when retrying a request. A completed
request replays its original response; a different body with that key returns
409. Starting the same customer/campaign again with a new key returns the existing
execution without advancing it or sending another opener. Different script or
campaign configuration for an existing execution is rejected. Starting a new
campaign uses a separately configured campaign ID; this endpoint has no reset or
force-resend option.

Errors: 400 invalid fields/key or missing template data; 401 missing/invalid bearer
token; 403 recipient/script not allowed; 409 missing chat identity, opt-out,
ownership/campaign conflicts, or pending/unknown idempotency outcome; 413 body
over 16 KiB; 503 full idempotency storage. The bounded request store defaults to
1000 entries retained for 24 hours after completion. Pending/unknown reservations
do not expire and require inspection; all request-cache state is lost on restart.
Campaign/history records remain in the configured database. This local API and
request cache are not a production delivery mechanism.

## Webhook setup and behavior

Register a public HTTPS URL ending in `/webhooks/telegram` through Telegram's
[`setWebhook`](https://core.telegram.org/bots/api#setwebhook) method. Pass
`secret_token` matching `TELEGRAM_WEBHOOK_SECRET_TOKEN` and
`allowed_updates=["message"]`. Registration is an operator action; application
startup does not register or replace an existing webhook.

Only new private text messages from non-bot users generate replies. Other update
types are acknowledged without processing. Topic IDs are preserved in replies
and dispatcher conversation IDs (`telegram:<chat-id>[:<topic-id>]`). Replies use plain text
and are limited to 4096 characters. The shared channel adapter accepts recipients
as `<chat-id>` or `<chat-id>:<topic-id>`; sends return `ACCEPTED_BY_PROVIDER` and
the Telegram message ID. This does not confirm recipient delivery or reading.
The existing dealership campaign eligibility rules continue to apply.

The Telegram-specific Spring Security chain requires exactly one
`X-Telegram-Bot-Api-Secret-Token` header on `POST /webhooks/telegram`, authenticates
before reading the body, and bounds declared and streamed body sizes. It applies
only to this path. When disabled, the application's existing security rules apply.
Outbound campaign requests use the separately authenticated local/test endpoint
described above; the webhook secret does not authorize campaign requests.

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
AI processing or sending. Queued work and dispatcher deduplication state are lost
on restart. The shared dealership repository persists received messages, workflow
and history using the configured datasource, with updates deduplicated by bot ID
and `update_id`. Topic IDs remain part of each recipient identity. Database receipt
deduplication does not provide durable work recovery or guaranteed reply delivery.
Failures are counted and logged by update ID and failure category,
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
