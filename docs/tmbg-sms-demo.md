# TMBG 3-in-1 SMS, Messenger and WhatsApp demo

The supplied TMBG instructions run in the `local` and `test` profiles for SMS, Facebook Messenger and WhatsApp. All use the configured `AiClient`, the supplied fictional customer, and one shared booking file. Keep the existing provider profiles and repository-root `.env`. Local demo settings allow two SMS segments for the supplied templates.

## Messenger

Send a text such as `Hello` to the configured Facebook Page. The first message starts that sender's demo and returns the greeting without calling the LLM. Later messages use the same booking rules and English/Taglish templates as SMS. Choosing an offered time records a visit request; general service questions remain available afterward.

This uses the existing signed webhook at `/api/webhooks/messenger` and the existing `META_APP_SECRET`, `META_VERIFY_TOKEN`, `META_PAGE_ACCESS_TOKEN`, `META_PAGE_ID`, and `META_GRAPH_API_VERSION` settings. The Page webhook must already point to this application's public HTTPS URL. The same `OPENAI_*` settings apply. Restart the application with the `local` profile after updating the code; no separate campaign-start request or phone linking is needed for Messenger. Messenger also works when SMS is disabled.

Conversation history and duplicate-message tracking are separate for each channel, Page/account, and sender. Messenger never reads an SMS conversation. Slots and booking references are shared, so a slot taken on either channel is unavailable on the other. Messenger records add `channel: "MESSENGER"` and `sender_id` (the Page-scoped sender ID); `account_id` is the Page ID and `phone` remains the supplied fictional customer's phone. These demo sessions and duplicate-message records last until application restart.

## WhatsApp

In `local` and `test`, authenticated incoming WhatsApp text messages also use `TmbgBookingAssistant` instead of the legacy dealership workflow. Send a message to the configured WhatsApp number to start a separate demo session; the first message returns the greeting and later messages use the booking assistant. The existing signed `/webhooks/whatsapp` endpoint, configured phone-number ID, and provider client remain in use. The Meta app must also be subscribed to the WhatsApp Business Account. Messages outside the 24-hour reply window are ignored.

WhatsApp history is isolated by channel, account and sender. Slots and booking references are shared with SMS and Messenger. Saved WhatsApp bookings include `channel: "WHATSAPP"`, the WhatsApp `sender_id`, and the configured phone-number ID as `account_id`; `phone` remains the supplied fictional customer's phone. Sessions reset on restart. A failed reply is retained for the next incoming turn, following the Messenger demo behavior; provider acceptance does not confirm delivery. Production retains the existing dealership workflow and requires a durable dispatcher.

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

Every new SMS or Messenger conversation starts with the English `OPEN_GREETING`. Language is selected again on each turn: messages containing recognized Tagalog words use Taglish; English messages, numbers and unclear text without Tagalog default back to full English. English replies omit `po` and `opo`, including service-generated confirmations and fallbacks.

The service renders and sends `OPEN_GREETING` without an LLM call. Fresh replies from the demo participant use the supplied rules, templates, customer record, and conversation state instead of the legacy shared dealership workflow. Hosted inbox polling selects the latest fresh message by `received_at` for each demo account/participant, using the receive ID to break ties. Earlier messages in the snapshot remain available in the retained inbox without triggering replies.

The service supplies the rules, negotiation playbook, curated PDF reference, service catalog, insurance estimates, customer record, derived fields, conversation state, templates and approved general answers, then asks the LLM for one JSON response. The LLM has no tools or write access. The service validates the response before enqueuing SMS and owns booking creation. Each conversation starts with Jae Guanio from the supplied record. The English greeting identifies the AI assistant and offers regular maintenance, without an opt-out footer. A clear yes accepts that offer. Services are offered individually; only accepted services can be saved. A renewal includes required basic insurance internally. Callback and advisor requests direct the customer to dealership contact; no external action is promised. Clear opt-out and wrong-number requests suppress that conversation after one acknowledgement. Suppression lasts until application restart or a deliberate new start; it is not a durable or cross-channel unsubscribe registry.

Choosing an unambiguous offered date/time (for example, `October 19 at 1pm please`) confirms the customer's booking intent; no additional `Yes` is needed. A question, refusal, tentative date, or ambiguous choice does not create a booking. Saving the booking ends booking operations but leaves general service questions available. A failed save leaves booking open for another reply.

Customers can ask general dealership and service questions during booking or after confirmation, such as why to choose Toyota Manila Bay, PMS inclusions, insurance options, concierge assistance, and coordinating several services when the customer asks. The model selects an approved topic from `dealership/catalog.json`; the service sends its approved English or Taglish answer. Knowledge replies preserve the current booking stage and offered slots, so a customer can ask about services and then return to choosing a date. Missing facts, unrelated questions and unsupported requests receive a limited-chat message directing the customer to contact Toyota Manila Bay directly. The chat does not invent contact details, offers or dealership facts.

After confirmation, requests to change, reschedule or cancel receive: "Bookings cannot be changed or cancelled through this chat at this time. Please contact Toyota Manila Bay directly for help." The confirmed session never re-enters booking or writes another booking through follow-up replies. Other unsupported requests receive: "This chat can only answer general service questions right now. Please contact Toyota Manila Bay directly for further help." General questions remain available afterward; this is a restricted capability, not a timed shutdown or advisor handoff. Duplicate receipts reuse their prior reply. These conversation protections follow the existing process-local session lifecycle; restarting or deliberately starting a new campaign resets that session.

There are no customer-facing letter codes or numbered reply instructions. Customers can choose an available appointment with wording such as `October 19 please`, `Monday works for me`, or `Sa October 19 na lang po`. A date with multiple offered times needs a time before it can be booked. Unavailable or ambiguous choices prompt clarification with the offered dates and times.

Available times come from `sms/tmbg/customer_records_3in1.json`, sorted chronologically and limited to three choices at a time. Only future times within the supplied renewal period are offered; already saved times are excluded. Customers can ask for other dates in their own words, such as `other dates` or `ibang petsa`. Browsing wraps after the last available set. General knowledge questions preserve the current choices. Fixed dates expire as the clock advances.

Selections accept a unique offered date, weekday, time, or natural wording such as `October 19 would work for me`, `this coming Monday`, or `the earliest one`. A date with multiple offered times needs clarification. Questions, tentative statements and refusals do not create bookings. Internal booking-file codes remain an implementation detail and are never shown as reply instructions.

Conversation state is process-local. Restarting the application or calling the start endpoint resets the demo session. The existing SMS inbox and outbox keep their normal persistence and transmission rules.

SMS receive diagnostics in `logs/channel-connect.log` distinguish import from AI processing. New receipts log `action=QUEUED` or `action=STORED`. Stored reasons include `no_active_demo_or_authorized_identity` (start the campaign for that account/number), `before_receive_baseline`, `non_phone_sender`, and `superseded_in_snapshot` (only the latest eligible demo reply in a snapshot is processed). Repeated snapshots do not repeat successful import logs. Slow receive requests and conflicting receipt IDs log warnings; processing and gateway submission logs include elapsed milliseconds. These diagnostic lines omit phone numbers and message bodies.

## Dates and booking records

The supplied templates contain October 2026 sample appointment dates. Availability is filtered using the actual date and time in `Asia/Manila`; elapsed slots are not offered. The sample calendar needs updating when its dates have passed.

Only the service writes bookings. A requested valid slot creates one JSON line in the repository-root `bookings.txt`, with a reference such as `TMB-00001`. This is a local booking record, with no live scheduling, callback, or advisor delivery integration. The customer acknowledgement says the dealership must confirm the reservation and requirements; it does not promise a real reservation. The LLM cannot create a reference or write to the file.

## Prompt and reference maintenance

`system-prompt.md` and `rules_3in1.txt` define free-text English/Taglish conversation. `negotiation_playbook.txt` covers hesitation without pressure. `service_catalog.json` supplies the service order and estimate rules; `insurance_prices.json` leaves insurance prices and the expiry threshold unset until confirmed. With an unset threshold, ask about insurance status instead of claiming an active policy is due. Null prices never become numeric estimates. Existing `PHP` prefixes in the record are retained without duplication.

`dealership-reference.txt` integrates relevant content from the owner-supplied **Toyota Manila Bay 2.pdf**, with page references. Numbered scripts and automatic bundle pitches are replaced by the requested natural flow. Warranty outcomes, resale percentages, turnaround guarantees, legal penalties, insurer accreditation, ratings, addresses and hours from the document are not approved operational facts. The reference explains maintenance inclusions, required versus optional work, policy comparisons, and registration coordination without promising unsupported actions.

Customer-facing responses omit the word ?demo?; internal profile names, class names, routes and operational documentation retain it where it identifies the actual deployment mode. These wording changes do not enable production delivery or live dealership scheduling. Existing automated tests still contain assertions for the previous name, footer and reply-code contract; tests were neither changed nor run under repository policy.
