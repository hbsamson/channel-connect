# Dealership campaign demo — October 16, 2026

The demo stores shared history, customer identity links, workflow state, outreach attempts, and appointments in file-based H2. All bundled customers, prices, hours, locations, and slots are fictional. A demo booking is an internal record, not a real dealership reservation. Dealership schedules use `Asia/Manila`.

The default demo still uses fictional SMS identities. Explicit GoIP mock mode replaces that adapter with loopback UDP and requires configured mobile-shaped test identities and SMS-sized scripts. See [the SMS integration guide](docs/goip-sms.md) for its separate launch command and Postman collection. In that mode use the protected UDP simulator ingress, rather than `/api/admin/demo/replies`, for SMS. Hardware operation remains a separate authorized milestone.

## Start and run

Use Java 25 and the checked-in Maven wrapper. From the repository root, in the first PowerShell terminal:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Demo.ps1
```

The launcher explicitly loads nonempty `.env` entries without evaluating them, activates `local,demo`, and selects deterministic mock AI. Spring itself does not load `.env`. Demo HTTP binds to `127.0.0.1:8080`; use an authenticated tunnel if testing real webhooks remotely.

In a second terminal:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Run-Demo.ps1 -Reset
```

`-Reset` deletes the new dealership demo tables' contents, including tester links and demo appointments, and reseeds six fictional customers. It leaves the legacy Messenger tables intact. Without `-Reset`, seeding is idempotent and previously completed scenarios remain completed.

The command scopes script execution permission to that PowerShell process; it does not change the machine's execution policy. If direct script execution is already allowed, the shorter `.\scripts\Start-Demo.ps1` and `.\scripts\Run-Demo.ps1 -Reset` invocations also work. The launcher disables inherited Spring debug tracing for the demo.

For an entirely offline setup, use `Start-Demo.ps1 -NoEnv` and `Run-Demo.ps1 -NoEnv -Reset`. Default credentials in this mode are `demo` / `demo-local-only`; they are for the loopback demo only. Otherwise both scripts use `ADMIN_USERNAME` and `ADMIN_PASSWORD` from environment variables or `.env`.

To enable real OpenAI intent interpretation with the configured model:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Demo.ps1 -UseOpenAI
```

Set `OPENAI_API_KEY` and `OPENAI_MODEL` first. The shared AI adapter uses the Responses API with the dealership prompt, recent cross-channel messages, an extractive context digest, approved catalog, customer facts, appointment, and workflow. Application services generate concise approved replies and commit booking actions. Model errors or invalid structured output fall back to the deterministic parser; this is visible in logs. The legacy Messenger file-search client remains in the repository but is no longer the dealership message processor.

The shared request uses JSON output and the configured `OPENAI_REASONING_EFFORT`/`OPENAI_MAX_OUTPUT_TOKENS`. The default `minimal` effort suits the existing GPT-5 nano classification use case; see [OpenAI's GPT-5 parameter guide](https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_new_params_and_tools). Keep these values compatible with the configured model.

## Configuration

`.env.example` inventories settings. The `demo` profile supplies explicit offline placeholders for inactive Meta connectors; it does not make these connectors functional.

| Purpose | Settings |
| --- | --- |
| Admin controls | `ADMIN_USERNAME`, `ADMIN_PASSWORD` |
| Messenger | `META_APP_SECRET`, `META_VERIFY_TOKEN`, `META_PAGE_ACCESS_TOKEN`, `META_PAGE_ID`, `META_GRAPH_API_VERSION` |
| WhatsApp | `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_VERIFY_TOKEN`, `META_APP_SECRET`, `META_GRAPH_API_VERSION` |
| Guarded WhatsApp templates | `OUTBOUND_LOCAL_AUTH_TOKEN`, `OUTBOUND_ALLOWED_RECIPIENTS`, `OUTBOUND_ALLOWED_TEMPLATES` |
| Shared AI | `AI_PROVIDER=mock` or `openai`, `OPENAI_API_KEY`, `OPENAI_MODEL`, optional `OPENAI_BASE_URL`, `OPENAI_TIMEOUT`, `OPENAI_MAX_RETRIES` |
| Demo resources | `DEALERSHIP_NAME`, `DEALERSHIP_CATALOG`, `DEALERSHIP_CAMPAIGN`, `DEALERSHIP_CUSTOMERS`, `DEALERSHIP_PROMPT` |
| Execution | `DEALERSHIP_SCHEDULER_ENABLED` (default false), `DEALERSHIP_MAX_PROVIDER_FAILURES` (default 3), `DEALERSHIP_HISTORY_LIMIT` (default 20) |
| Database | `SPRING_DATASOURCE_URL` (default `jdbc:h2:file:./data/channel-connect;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`) |

Resources default to `src/main/resources/dealership/catalog.json`, `dealership/customers.json`, `campaigns/registration-renewal.json`, and `prompts/dealership-assistant.md`. To edit without rebuilding, use environment values such as `DEALERSHIP_CAMPAIGN=file:./data/campaign.json` and restart. Execution configuration is snapshotted when a campaign starts; changing the resource affects new executions. Seeded existing customer profiles and slots are preserved until reset. JSON customer import is available through the admin API; CSV is deferred.

The campaign includes `1-in-1`, `2-in-1`, `3-in-1`, and `Custom` configuration types; inclusions are explicit configuration. CTPL eligibility comes from the customer's configured record. Default outreach is SMS simulation, then Telegram simulation, then eligible real Meta channels; three accepted unanswered attempts per channel, 30-second delay/cooldown. Optional `opening_sms`, `opening_telegram`, `opening_messenger`, and `opening_whatsapp` scripts override the corresponding opening. Follow-ups use `followup1`, then `followup2`; channel switching uses `switch`. Missing variables prevent sending.

Set `quietHoursStart` and `quietHoursEnd` to `HH:mm`, or both to null to disable quiet hours. Enable the scheduler to process due executions automatically. The default manual runner advances waiting/cooldown deadlines; it still enforces opt-out, identity verification, consent, platform eligibility, and quiet hours. A response on any linked channel pauses all unanswered outreach. Declining completes the campaign without a global opt-out; STOP opts out across linked channels.

## Admin API

All routes below require HTTP Basic admin authentication and exist only in `local`/`test` profiles. Reuse these helpers in PowerShell; environment credential values are not printed:

```powershell
$pair = $env:ADMIN_USERNAME + ':' + $env:ADMIN_PASSWORD
$auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)) }
$api = 'http://127.0.0.1:8080/api/admin/demo'
Invoke-RestMethod -Method Post -Uri "$api/seed" -Headers $auth
$body = @{ customerId = 'demo-no-response'; baseChannel = 'SMS' } | ConvertTo-Json
$execution = Invoke-RestMethod -Method Post -Uri "$api/campaigns/start" -Headers $auth -ContentType 'application/json' -Body $body
$body = @{ executionId = $execution.id; force = $true } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/campaigns/advance" -Headers $auth -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$api/customers/demo-no-response" -Headers $auth
```

In a new terminal, load the admin environment values first; the runner reads these from `.env` automatically.

| Route | Behavior |
| --- | --- |
| `POST /seed`, `POST /reset` | Seed without overwriting; explicitly reset dealership data |
| `POST /customers/import` | Import an array using the snake_case customer JSON schema |
| `GET /customers`, `GET /customers/{id}` | Profiles; identities, opt-out, workflow, last 100 messages, appointment, per-channel attempts, events |
| `POST /customers/{id}/identities` | Explicitly verified tester identity association |
| `POST /campaigns/start` | `{customerId, baseChannel?, configuration?}`; repeated starts return the persisted execution |
| `GET /campaigns` | Persisted execution status/configuration |
| `POST /campaigns/advance` | `{executionId?, force:false}`; one due attempt per eligible execution |
| `POST /replies` | Simulated inbound SMS/Telegram only; cannot impersonate real webhook traffic |
| `GET /configuration` | Approved campaign/catalog and real-versus-simulated adapter labels |

Example simulation payload:

```json
{
  "channel": "TELEGRAM",
  "accountId": "demo",
  "senderId": "demo-booking-telegram",
  "externalId": "unique-demo-message-1",
  "content": "Actually 2026-10-17 instead",
  "timestamp": "2026-10-16T01:00:00Z"
}
```

Use a new external ID for each real interaction; replaying one returns `DUPLICATE`. The messages retain provider timestamps, account scope, provider IDs, channel, and delivery state. Provider acceptance is not delivery or a customer response.

## Real channels and verified identities

Messenger and WhatsApp use the existing authenticated webhooks, queues, and send clients. They have automated tests against mocked boundaries; real sending/receiving has **not** been verified in this implementation session. SMS and Telegram are **simulated only**; neither has a provider gateway or bot webhook. Viber and voice are absent.

Keep tester identifiers in ignored local files, not committed fixtures. After verifying control of an account, link it through `POST /customers/demo-booking/identities`:

```json
{
  "channel": "MESSENGER",
  "accountId": "<configured-page-id>",
  "externalId": "<verified-page-scoped-tester-id>",
  "verified": true,
  "consent": true,
  "outboundAllowed": true
}
```

For WhatsApp use `channel: WHATSAPP`, the configured phone-number ID as `accountId`, and the verified WhatsApp sender ID as `externalId`. Similar names/phone numbers never link accounts. Unknown senders receive isolated histories and generic dealership answers; they cannot retrieve private customer facts or book appointments. An admin may associate an unverified isolated account after verifying ownership; verified identities cannot be reassigned. Existing history from the exact verified Messenger page/PSID is imported without deleting legacy rows. An identity's prior opt-out blocks reassociation that could restore campaign contact.

Test Messenger via `GET/POST /api/webhooks/messenger`, and WhatsApp via `GET/POST /webhooks/whatsapp`, using genuine provider traffic with the existing Meta signature checks. Send an initial customer message to establish a valid reply window, then book through that channel and continue using a second explicitly linked account.

Messenger proactive outreach in this demo is limited to the standard recent-inbound messaging window; see [Meta's Messenger Send API collection](https://www.postman.com/meta/messenger-platform-api/folder/vilwbh4/send-api). WhatsApp campaign outreach always requires configured approved templates and the existing recipient/template allowlists. Configure `whatsAppTemplates` by script key, for example:

```json
{
  "opening": {
    "name": "<approved_template_name>",
    "language": "en_US",
    "variables": ["first_name", "dealer_name", "renewal_due"]
  }
}
```

Use the actual approved template name and exact ordered body variables; its text must match the configured approved campaign script. Also configure `followup1`, `followup2`, and `switch` if using WhatsApp for those stages. Missing mappings make that stage ineligible. Keep the existing bearer-protected `POST /api/v1/whatsapp/messages` endpoint, its `Idempotency-Key`, and allowlists when manually testing templates.

## Deterministic scenarios and persistence

Run individual scenarios with `Run-Demo.ps1 -Scenario Booking`, `Switching`, `History`, `Reschedule`, `Inquiries`, or `Privacy`. Run with `-Reset` for a fresh scenario. Slots are fixed to October 16, 17, and 19, 2026 at 09:00, 10:00, and 14:00 Manila time, capacity two. Update the catalog for later demos.

The `All` runner demonstrates booking, three SMS attempts followed by Telegram fallback, a pending booking continued across channels, rescheduling and confirmed cancellation, grounded prices/hours/packages, an unrelated question redirected to services, and privacy/global opt-out. A real Messenger-to-Telegram scenario requires a real Telegram implementation; use linked Messenger/WhatsApp or an explicitly simulated Telegram step instead.

To verify persistence, run a booking, stop the application with Ctrl+C, restart using the same database path, and retrieve that customer's view without resetting. Messages, workflow, appointments, attempts, identity links, and opt-out remain. Repeating the same campaign start or webhook ID does not create another accepted attempt/message. An interrupted in-flight send is marked `UNKNOWN`, and its campaign is `FAILED` for operator review; it is never automatically resent. The application cannot guarantee exactly-once external delivery across a crash without provider-side idempotency/reconciliation.

## Verification and limits

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

Focused tests cover grounded/scope-limited replies, bilingual booking, cross-channel context, durable database reopening, duplicate webhooks/schedulers, fallback exhaustion, global opt-out, slot capacity, failed booking persistence, reply routing, delivery status, and authenticated controls.

The V3 migration adds dealership customer/identity/conversation/message, execution/attempt/channel-progress, appointment/slot, and event tables. V1/V2 Messenger data remains intact. No new dependencies or framework upgrades are needed.

The H2 pool initializes connections with `SET WRITE_DELAY 0` so newly committed messages, attempts, and opt-outs are flushed without H2's default delay. This also applies when a custom file database URL is supplied. See [H2's write-delay documentation](https://h2database.com/html/commands.html#set_write_delay). A future migration to another database must replace this H2-specific setting.

Remaining limitations: single application instance with file H2; existing nondurable inbound queues; bounded extractive context digest rather than semantic summarization; no real SMS/Telegram/Viber, calendar/CRM, interactive identity verification, browser dashboard, or external human notification. Human handoff records an event and clearly says no person has been notified. All bookings are demo records. The existing process-local WhatsApp template idempotency store remains bounded and restricted to local/test; durable campaign claims prevent automatic restarts from using it as a production delivery system. Production startup still requires a durable WhatsApp dispatcher. Verify real Meta delivery and multilingual OpenAI interpretation with tester accounts before advertising those as live functionality.
