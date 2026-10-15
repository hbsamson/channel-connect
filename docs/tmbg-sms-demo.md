# TMBG 3-in-1 SMS demo

The supplied TMBG instructions run as an SMS-only demo in the `local` and `test` profiles when SMS is enabled. Keep the existing SMS provider profile, repository-root `.env`, bearer token, and OpenAI settings. No additional setup or separate application instance is required. The configured `AiClient` is reused. Local demo settings allow two SMS segments for the supplied templates.

## Start a conversation

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

The service renders and sends `OPEN_GREETING` without an LLM call. Fresh replies from the demo participant use the supplied rules, templates, customer record, and conversation state instead of the legacy shared dealership workflow. Hosted inbox polling selects the latest fresh message by `received_at` for each demo account/participant, using the receive ID to break ties. Earlier messages in the snapshot remain available in the retained inbox without triggering replies.

The service merges the six context blocks and asks the LLM for one JSON response. The LLM has no tools or write access. The service validates the response before enqueuing SMS and owns booking creation. The demo continues until a booking is recorded; other terminal outcomes in the supplied materials do not end this demo. Callback and advisor requests do not contact anyone or trigger an external integration.

Conversation state is process-local. Restarting the application or calling the start endpoint resets the demo session. The existing SMS inbox and outbox keep their normal persistence and transmission rules.

## Dates and booking records

The supplied templates contain October 2026 sample appointment dates. Availability is filtered using the actual date and time in `Asia/Manila`; elapsed slots are not offered. The sample calendar needs updating when its dates have passed.

Only the service writes bookings. A requested valid slot creates one JSON line in the repository-root `bookings.txt`, with a reference such as `TMB-00001`. This is a demo booking record, with no live scheduling, callback, or advisor delivery integration. The LLM cannot create a reference or write to the file.
