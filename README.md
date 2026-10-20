# SVI Channel Connect

A Java 25 Spring Boot application for Messenger and WhatsApp, with one entry point: `com.svi.channelconnect.ChannelConnectApplication`.

SMS is available through an independent provider boundary, the documented GoIP UDP protocol, a direct GoIP8 HTTP web form, and a hosted PHP HTTP API matching the operator's Postman send/receive collection. It is disabled by default; explicit mock mode uses an actual loopback UDP gateway simulator. See [GoIP SMS setup, protocol details, local API examples, and hardware cutover prerequisites](docs/goip-sms.md), or start the supplied [TMBG 3-in-1 SMS demo](docs/tmbg-sms-demo.md) using the existing SMS configuration. Physical GoIP operation from this application has not been verified.

## Architecture

Connectors live under `com.svi.channelconnect.messenger` and `com.svi.channelconnect.whatsapp`. AI adapters live under `ai` and shared messaging contracts under `common`.

The SMS connector lives under `sms`, with a persistent inbox/outbox, bounded workers, scoped conversations, and explicit queued, gateway-reported, failed, and unknown statuses. Ordinary SMS conversations reuse the existing dealership assistant and AI boundary. The TMBG demo participant uses the supplied instructions and isolated demo state. Its customer-facing conversation uses Jae Guanio, free-text replies, one service at a time, and the curated Toyota Manila Bay reference; it omits reply codes and opt-out footers while honoring requests to stop. Saved visit requests still require dealership reservation confirmation. An unknown transmission is blocked from automatic resend.

SMS supports bearer-protected `/api/v1/sms/send`, cursor-based `/api/v1/sms/receive` polling, and `/api/v1/sms/receive-all` for paging through retained inbox history across configured SIMs. It also supports canned `script1`/`script3` bulk campaign openings and retained conversation context for appointment and service/price-objection replies. The `sms-http-api` profile uses the hosted Postman contract with continuous JSON inbox polling; direct HTTP sending can also opt into authenticated UDP reception. See [the conversation flow, bulk request, and receive configuration](docs/goip-sms.md). Booking uses configured slots; live scheduling and Redshift synchronization are not connected.

`MessagingAppHandler` selects a `MessagingChannel` adapter by its channel name. Messenger and WhatsApp normalize authenticated inbound events into the dealership conversation service, which resolves verified customer identities, persists cross-channel context, and executes grounded service/appointment workflows. Existing webhook authentication, queues and provider clients stay in each connector. Shared dealership behavior lives under `dealership`; channel-neutral AI interpretation remains under `ai`.

To add a platform, implement `common.service.MessagingChannel` as a Spring bean with a unique channel name, add its authenticated webhook and dispatch adapter, and call the handler from that workflow. Adding an adapter does not expose a public send endpoint. Provider acceptance is separate from delivery confirmation. The shared text operation is for channel workflows; WhatsApp application-initiated messages still use the guarded template endpoint.

## Configuration and local development

Use `.env.example` as the configuration inventory. The `local` profile loads the repository-root `.env` directly; no launcher script or manual PowerShell assignments are required. Use unquoted `KEY=value` entries (Java properties syntax); escape literal backslashes as `\\`. Operating-system environment variables and command-line arguments override `.env`. Other profiles do not load `.env` automatically. `AI_PROVIDER=mock` selects deterministic dealership intent parsing; `openai` uses the shared history-aware AI boundary. All dealership workflows use the shared AI adapter, configurable prompt, and approved catalog. The unused Messenger AI client and its separate `openai.*` configuration have been removed; mock mode no longer requires an OpenAI key.

For local campaign exploration, use the direct Maven command and protected API examples in [DEMO_GUIDE.md](DEMO_GUIDE.md). The `demo` profile supplies offline defaults for inactive connectors. SMS and Telegram use simulations unless explicitly enabled. Real provider delivery requires configured accounts and verification.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
.\mvnw.cmd "-Dmaven.test.skip=true" compile
.\mvnw.cmd "-Dmaven.test.skip=true" package
```

Configuration, logging and Flyway migrations are under `src/main/resources`. Test settings and webhook fixtures are under `src/test/resources`. Repository policy requires an explicit request before running or modifying tests.

## Meta webhook setup

Start the application with `local` and expose port 8080 through a public HTTPS tunnel, for example `ngrok http 8080`. Use the tunnel's public HTTPS origin in the callbacks below; `http://localhost:4040` is only ngrok's local request inspector. Update Meta's callbacks whenever the tunnel hostname changes.

### WhatsApp: subscribe the app to the business account

In the Meta app's WhatsApp setup, copy the **Phone Number ID** into `WHATSAPP_PHONE_NUMBER_ID` and the **WhatsApp Business Account ID** into `WHATSAPP_WABA_ID`. These are different identifiers. Set `WHATSAPP_ACCESS_TOKEN` to a token issued for the intended app with access to that account and the required WhatsApp permissions (`whatsapp_business_management` for subscription management and `whatsapp_business_messaging` for messaging). Keep these values in the untracked `.env`.

Configure the WhatsApp callback as `https://<public-host>/webhooks/whatsapp`, verify it using the same value as `WHATSAPP_VERIFY_TOKEN`, and subscribe to the `messages` field. `META_APP_SECRET` must belong to this app because the server validates Meta's webhook signatures.

There is also an **account-level app subscription**. The `messages` toggle and a successful dashboard sample event do not establish that this app is subscribed to the WABA. In the no-incoming-messages incident, the WABA initially listed only Meta's `WA DevX Webhook Events 1P App`. The fix was to verify that the configured access token belonged to the intended developer app, then call `POST /<WABA-ID>/subscribed_apps` using that token. A final GET confirmed that the intended app appeared alongside the existing subscription. This subscribes the token's app to the business account's phone-number events; it does not register or change the phone number.

To repeat that operation, run the following in PowerShell from the repository root. Replace the expected App ID with the ID shown in your Meta developer dashboard. This reads the plain, unquoted Meta values from `.env` without printing credentials. It does not change `.env` or send a WhatsApp message.

```powershell
$expectedAppId = '<your-meta-developer-app-id>'
$metaSettings = @{}
Get-Content -LiteralPath .env | ForEach-Object {
    if ($_ -match '^\s*([^#=\s]+)\s*=(.*)$') {
        $metaSettings[$matches[1]] = $matches[2].Trim()
    }
}

$version = $metaSettings['META_GRAPH_API_VERSION']
$wabaId = $metaSettings['WHATSAPP_WABA_ID']
if ($version -notmatch '^v\d+\.\d+$' -or $wabaId -notmatch '^\d+$' -or
    [string]::IsNullOrWhiteSpace($metaSettings['WHATSAPP_ACCESS_TOKEN'])) {
    throw 'Set META_GRAPH_API_VERSION, WHATSAPP_WABA_ID and WHATSAPP_ACCESS_TOKEN in .env first.'
}
$graphBase = 'https://graph.facebook.com/' + $version
$headers = @{ Authorization = 'Bearer ' + $metaSettings['WHATSAPP_ACCESS_TOKEN'] }

# Confirm which app will be subscribed. Stop if this is the wrong token.
$app = Invoke-RestMethod -Uri ($graphBase + '/app?fields=id,name') `
    -Headers $headers -TimeoutSec 15 -ErrorAction Stop
if ($app.id -ne $expectedAppId) {
    throw 'The configured WhatsApp token belongs to a different app.'
}

$subscriptionUri = $graphBase + '/' + $wabaId + '/subscribed_apps'
$before = Invoke-RestMethod -Uri $subscriptionUri -Headers $headers -TimeoutSec 15 -ErrorAction Stop
$before.data.whatsapp_business_api_data | Select-Object id, name

# Add this app if missing; leave the existing app subscriptions in place.
if (@($before.data.whatsapp_business_api_data.id) -notcontains $expectedAppId) {
    $result = Invoke-RestMethod -Method Post -Uri $subscriptionUri `
        -Headers $headers -TimeoutSec 15 -ErrorAction Stop
    if (-not $result.success) { throw 'Meta did not confirm the subscription.' }
}

$after = Invoke-RestMethod -Uri $subscriptionUri -Headers $headers -TimeoutSec 15 -ErrorAction Stop
$after.data.whatsapp_business_api_data | Select-Object id, name
if (@($after.data.whatsapp_business_api_data.id) -notcontains $expectedAppId) {
    throw 'The expected app is still missing from the WABA subscriptions.'
}
Write-Output 'Expected app is subscribed to the WhatsApp Business Account.'
```

The administrative command uses `WHATSAPP_WABA_ID`; the running application's send endpoint and inbound account filter use `WHATSAPP_PHONE_NUMBER_ID`. Use the account that owns the configured phone number. Subscription changes take effect on Meta without restarting the application; `.env` changes require a restart. See [Meta's account-subscription API instructions](https://www.postman.com/meta/whatsapp-business-platform/documentation/wlk6lh4/whatsapp-cloud-api?entity=request-13382743-eb9a6721-1056-4db2-b00a-2290e04b5b5d).

To check reception, click **Test**, then **Send to My Server** beside `messages` and look for `POST /webhooks/whatsapp` in ngrok. A `200` from a sample event proves endpoint reachability and authentication; samples using a different phone-number ID are ignored and do not produce replies. Then send a real text from your test handset to the Meta test number. Incoming messages for the configured number should produce `WhatsApp webhook handled` logs. In `local`, the first fresh conversation message starts the TMBG demo greeting; later messages use the booking assistant. A received reply confirms more than the dashboard sample alone.

### Messenger: configure the Page and add more testers

Keep Messenger's callback separate: `https://<public-host>/api/webhooks/messenger`. Verify it with `META_VERIFY_TOKEN`. In the app's Messenger setup, connect the intended Facebook Page, generate its Page access token, and set `META_PAGE_ID` and `META_PAGE_ACCESS_TOKEN` in `.env`. Subscribe the Page to the app's Messenger webhooks, including `messages`. The Page token needs `pages_messaging`; connecting/subscribing the Page also requires the appropriate Page-management permissions and access. Restart after changing local configuration. See [Meta's Messenger setup documentation](https://developers.facebook.com/docs/messenger-platform/get-started/).

For additional people testing an app in Development mode:

1. Each tester uses their own Facebook account and registers for Meta for Developers if required by the invitation flow.
2. An app administrator opens the correct app at [Meta for Developers](https://developers.facebook.com/apps/), then **App roles → Roles → Add people** (some dashboard layouts show an **Add testers** button).
3. Select the **Tester** role and invite the person's Facebook account using the identifier requested by the dashboard. This is a real person assigned a tester role; the separate generated **Test Users** feature is not needed for this flow.
4. The invited person signs in to that Facebook account and accepts the app-role invitation through their notification or the [developer requests page](https://developers.facebook.com/requests/). Confirm their role is accepted rather than pending before proceeding. See [Meta's app-role documentation](https://developers.facebook.com/docs/development/build-and-test/app-roles/).
5. From that same person's Messenger account, open the configured Facebook Page and send a fresh text such as `Hello`. Send as the person, rather than switching into the Page identity. Check ngrok for `POST /api/webhooks/messenger` and the application for `Messenger processing stage=booking_demo` in `local`.

Repeat the invitation and acceptance for each tester. Adding testers does not require changing the Page token, adding WhatsApp recipients, or restarting this application. Each sender receives a separate local demo conversation; no separate campaign-start request is needed. Page access alone is not a substitute for an accepted app role when testing in Development mode. Availability to people without app roles requires the applicable Meta publication, permission access and App Review requirements; adding a tester does not grant public access.

If one tester works and another produces no incoming request, first check that the second person accepted the invitation for this exact app and is messaging the correct Page. If nobody produces incoming requests, check the Messenger callback and the Page's app subscription. A successful WhatsApp WABA subscription does not subscribe a Messenger Page.

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

## Production deployment gates

This repository is not yet ready for production delivery. Cleanup and successful compilation do not verify delivery guarantees or provider integration. Do not activate `local`, `test`, or `demo` to bypass the missing production dispatcher: these profiles enable nondurable delivery, simulations, and administrative demo controls.

Before deployment:

- Implement durable WhatsApp dispatch and replace Messenger's in-memory queue with durable admission, deduplication, bounded retries, and recovery. Enabled Telegram also requires a durable dispatcher outside `local`/`test`. Acknowledge webhooks only after durable admission; preserve provider acceptance versus confirmed delivery and unknown-send handling.
- Supply real approved catalog, campaign, and customer configuration. Bundled fictional customers and October 2026 appointment slots are demonstration data. Scheduling and external reservation confirmation remain integration work.
- Provision persistent storage and verify backup/restore, migrations, retention, and access control. The bundled database is file-based H2 for a single instance. Changing its JDBC URL does not establish support for another database; verify SQL compatibility and supply its driver before migrating. Preserve applied Flyway migrations and legacy history tables.
- Supply secrets through the runtime environment or a secret store; keep admin access private and terminate HTTPS at a trusted ingress. Validate request limits, provider permissions, webhook signatures, and actual device/provider delivery in the target environment. GoIP hardware operation remains unverified.
- Explicitly authorize and run automated tests, provider integration checks, security/dependency review, and load/restart/recovery verification before release. These checks are not covered by a production compilation.

The runtime enables graceful HTTP shutdown. Messenger workers wait up to `MESSENGER_SHUTDOWN_TIMEOUT` (default `10s`) before interruption and report discarded queue counts; this does not prevent loss after a crash. Allow enough process termination time for HTTP shutdown and all connector drains; `SHUTDOWN_PHASE_TIMEOUT` defaults to `30s` per Spring lifecycle phase.

Use `GET /actuator/health/liveness` for process availability and `GET /actuator/health/readiness` for application readiness plus database connectivity. These public endpoints hide details. They do not certify durable dispatch or external provider availability. Rolling logs default to `10MB` per file with a `1GB` archive cap and the configured retention period; override `LOGGING_FILE_MAX_SIZE`, `LOGGING_FILE_TOTAL_SIZE_CAP`, and `LOG_DIR` for the host. Pre-existing daily archives from the older naming pattern require separate retention management.
