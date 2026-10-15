# TMBG 3-in-1 SMS and Messenger demo

The supplied TMBG instructions run in the `local` and `test` profiles for SMS and Facebook Messenger. Both use the configured `AiClient`, the supplied fictional customer, and one shared booking file. Keep the existing provider profiles and repository-root `.env`. Local demo settings allow two SMS segments for the supplied templates.

## Messenger

Send a text such as `Hello` to the configured Facebook Page. The first message starts that sender's demo and returns the greeting without calling the LLM. Later messages use the same booking rules and English/Taglish templates as SMS. Choosing an offered slot confirms the booking, and subsequent messages stop calling the LLM.

This uses the existing signed webhook at `/api/webhooks/messenger` and the existing `META_APP_SECRET`, `META_VERIFY_TOKEN`, `META_PAGE_ACCESS_TOKEN`, `META_PAGE_ID`, and `META_GRAPH_API_VERSION` settings. The Page webhook must already point to this application's public HTTPS URL. The same `OPENAI_*` settings apply. Restart the application with the `local` profile after updating the code; no separate campaign-start request or phone linking is needed for Messenger. Messenger also works when SMS is disabled.

Conversation history and duplicate-message tracking are separate for each channel, Page/account, and sender. Messenger never reads an SMS conversation. Slots and booking references are shared, so a slot taken on either channel is unavailable on the other. Messenger records add `channel: "MESSENGER"` and `sender_id` (the Page-scoped sender ID); `account_id` is the Page ID and `phone` remains the supplied fictional customer's phone. These demo sessions and duplicate-message records last until application restart.

## Start an SMS conversation

Send an authenticated request using a configured SMS account:

```bash
curl --request POST 'http://localhost:8080/api/v1/sms/campaign/start' \
  --header 'Authorization: Bearer <SMS_API_TOKEN>' \
  --header 'Content-Type: application/json' \
  --data '{"accountId":"goip-line-1"}'
```

`recipient` is optional and defaults to the phone in the supplied fictional customer record. To use a test handset, include its number:

```json
{"accountId":"goip-line-1","recipient":"<test handset number>"}
```

The override changes only where SMS is sent. The assistant continues to use the supplied fictional customer's vehicle, renewal, and service details. The endpoint returns `202` with an application message ID and a `Location` URL for the existing SMS status endpoint. Submission status does not confirm handset delivery. Each deliberate start sends a new greeting and resets that account/participant's demo session.

## Conversation flow

Every new SMS or Messenger conversation starts with the English `OPEN_GREETING`. Replies stay in English until the customer uses Tagalog or Taglish, then follow that language. Numbers, slot codes and unclear messages retain the current conversation language, defaulting to English. Existing sessions keep their language until a new campaign start or application restart.

The service renders and sends `OPEN_GREETING` without an LLM call. Fresh replies from the demo participant use the supplied rules, templates, customer record, and conversation state instead of the legacy shared dealership workflow. Hosted inbox polling selects the latest fresh message by `received_at` for each demo account/participant, using the receive ID to break ties. Earlier messages in the snapshot remain available in the retained inbox without triggering replies.

The service merges the six context blocks and asks the LLM for one JSON response. The LLM has no tools or write access. The service validates the response before enqueuing SMS and owns booking creation. The demo continues until a booking is recorded; other terminal outcomes in the supplied materials do not end this demo. Callback and advisor requests do not contact anyone or trigger an external integration.

Choosing an offered slot (for example, `A`, `Book A please`, or an unambiguous offered date/time) confirms the customer's booking intent; no additional `Yes` is needed. A question, refusal, tentative date, or ambiguous choice does not create a booking. The service only ends the session after saving the booking successfully. A failed save leaves it open for another reply. Later inbound messages in a confirmed session are acknowledged internally without another LLM call or outgoing SMS.

Slot letters are optional. Customers can choose an available appointment with wording such as `October 19 please`, `Monday works for me`, or `Sa October 19 na lang po`. A date with multiple offered times needs a time before it can be booked. Unavailable or ambiguous choices prompt clarification with the offered dates and times.

Conversation state is process-local. Restarting the application or calling the start endpoint resets the demo session. The existing SMS inbox and outbox keep their normal persistence and transmission rules.

SMS receive diagnostics in `logs/channel-connect.log` distinguish import from AI processing. New receipts log `action=QUEUED` or `action=STORED`. Stored reasons include `no_active_demo_or_authorized_identity` (start the campaign for that account/number), `before_receive_baseline`, `non_phone_sender`, and `superseded_in_snapshot` (only the latest eligible demo reply in a snapshot is processed). Repeated snapshots do not repeat successful import logs. Slow receive requests and conflicting receipt IDs log warnings; processing and gateway submission logs include elapsed milliseconds. These diagnostic lines omit phone numbers and message bodies.

## Dates and booking records

The supplied templates contain October 2026 sample appointment dates. Availability is filtered using the actual date and time in `Asia/Manila`; elapsed slots are not offered. The sample calendar needs updating when its dates have passed.

Only the service writes bookings. A requested valid slot creates one JSON line in the repository-root `bookings.txt`, with a reference such as `TMB-00001`. This is a demo booking record, with no live scheduling, callback, or advisor delivery integration. The LLM cannot create a reference or write to the file.
