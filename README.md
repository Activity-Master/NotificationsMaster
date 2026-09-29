# Notification Master

Recipient-addressed notifications for ActivityMaster: an FSDM store, append-only read state,
and delivery over mail, webhook and the Vert.x event bus. REST base `/{enterprise}/notifications`.

No notification table is created. A notification is an `Event`; state is an `Event`; a delivery
attempt is an `Event`. Nothing is ever updated in place, so the record of who was told what, who
saw it and what reached them survives intact.

## FSDM model

```
Event (Notification) ──EventXInvolvedParty[NotificationPublisher]──► InvolvedParty
   │                 ──EventXInvolvedParty[NotificationRecipient]──► InvolvedParty (one per recipient)
   │                 ──EventXClassification──► category / severity / subject / context
   │                 ──EventXResourceItem[NotificationBody]──► ResourceItem (body + payload as DATA)
   ├─EventXEvent[NotificationStateOf]────► Event (Notification State)    → READ / DISMISSED / ACKNOWLEDGED
   └─EventXEvent[NotificationDeliveryOf]─► Event (Notification Delivery) → channel + result + detail
```

| Item | Value |
|------|-------|
| System name | `Notification Master` |
| Event types | `Notification`, `Notification State`, `Notification Delivery` |
| Resource item type | `Notification Body` |
| Install | `NotificationInstall` (`sortOrder = 1400`) — taxonomy and scoped-behaviour vocabulary only |

The body and structured payload are the **data** of a private resource item, not relationship
values. A relationship `value` is `varchar(150)` and is for short discriminators, so anything
larger belongs in resource item data. Only short fields ride as link values: subject (≤150),
category (≤128), severity, context and delivery detail (≤150).

## Security contract

The host binds `NotificationIdentityProvider` to a **verified, call-scoped** party, enterprise,
realm/owner context and ActivityMaster identifying credential. The default provider denies every
request. No path or body parameter ever names the actor.

* **Publishing** requires the `notifications.publish` scoped behaviour grant.
* **Reading delivery attempts** requires `notifications.audit`.
* **Everything else** is available only to a recipient of the notification in question.

`NotificationInstall` provisions the scoped-behaviour *vocabulary* and nothing else. A fresh
enterprise has no provider installation and no actor grants, so nobody can publish until a
privileged administrator issues them. This is what stops a logged-in party from notifying other
parties — Conversation Master already covers party-to-party messaging.

Every read joins to a live `NotificationRecipient` link for the calling party. A caller who is not
a recipient gets **404, not 403**, so the API never confirms that a notification they cannot see
exists. Notification, state and delivery rows get the restricted security matrix plus a grant to
the acting credential; they have **no default public grants**. Hosts must not expose privileged
generic FSDM row reads to notification clients.

Writes require a caller-owned transaction. Bodies are plain Unicode up to 65,536 UTF-16 code
units, rejected on null characters and broken surrogate pairs; renderers must escape them.

## REST API

Base: `/{enterprise}/notifications`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/` | Publish. Requires `notifications.publish` |
| `GET` | `/` | List the caller's notifications (`state`, `category`, `offset`, `limit`) |
| `GET` | `/counts` | `{"unread":n,"total":n}` for the caller |
| `GET` | `/{id}` | Read one, including its body |
| `POST` | `/{id}/read` | Mark read |
| `POST` | `/{id}/dismiss` | Dismiss |
| `POST` | `/{id}/acknowledge` | Acknowledge |
| `POST` | `/read-all` | Mark every unread read (`category` optional) |
| `GET` | `/{id}/deliveries` | Delivery attempts. Requires `notifications.audit` |

Publish body:

```json
{
  "category": "billing",
  "severity": "WARNING",
  "subject": "Invoice overdue",
  "body": "Invoice INV-1 is 14 days overdue.",
  "data": "{\"invoice\":\"INV-1\"}",
  "recipients": ["bc641baa-957a-4434-9108-22e5da735abd"],
  "channels": ["MAIL", "EVENT_BUS"]
}
```

Paging is `offset` 0..10,000 and `limit` 1..100; responses carry `hasMore` and never an unfiltered
count. List responses omit bodies — fetch one notification to get its body. With no `state` filter,
dismissed notifications are excluded from the list but remain directly readable.

`GET /counts` returns `{"unread":n,"total":n,"capped":false}`. `capped` is true when the count hit
its ceiling, so the figures are lower bounds and a badge should render `999+`.

State transitions are idempotent: recording a state the caller already holds writes nothing.
`UNREAD` is the absence of a state event and cannot be recorded. `read-all` transitions at most 200
notifications per call; repeat until it returns zero. At most 500 recipients per publish; unknown or dead parties are
dropped and the response reports who was actually addressed.

## Behaviour under load

Every statement the service issues is bounded, and every ordered result is newest first.

| Concern | How it is bounded |
|---------|-------------------|
| Existence probes (token, party, grants) | `limit 1` — they ask whether a row exists, never how many |
| Taxonomy lookups (event type, role, resource type) | `limit 2` — still enough to detect an ambiguous definition |
| List / deliveries | `limit` + 1 to compute `hasMore`, offset ≤ 10,000, limit ≤ 100 |
| Recipient working set | Narrowed to the newest 10,100 before any filter or sort |
| `counts` | Stops at 1,000 and reports `capped` |
| `read-all` | 200 notifications per call |
| `publish` | 500 recipients, and the recipient lookup is limited to that |

Reads are **driven from the recipient link** (`event.eventxinvolvedparty.involvedpartyid`), so the
actor selects the working set rather than the enterprise's whole event graph being scanned and then
filtered. That inner set is ordered newest first and capped at 10,100 — sized to `offset` + `limit`
so every page a caller may ask for is inside the window, while a recipient holding a million
notifications still cannot make the database sort a million rows. A filtered query therefore
searches the newest 10,100 notifications, not all of history.

Per-row correlated subqueries were replaced with `LEFT JOIN LATERAL` pivots: category, severity and
subject come back in one pass instead of three, body text and payload in one instead of two, and
state is evaluated once rather than once for the projection and again for the `WHERE`. The pivots
also make a duplicated link impossible to multiply out into duplicate rows, which the previous
three-way join to `eventxclassification` in the delivery query could have done.

Classification ids, event type ids and the caller's active flag, group tokens and credential are
resolved **once per operation** and reused. Publishing to 500 recipients now resolves the recipient
role once rather than 500 times, and each inserted row costs three statements rather than six.

The access paths rely on FK indexes the FSDM schema already creates — notably
`event.eventxinvolvedparty (involvedpartyid)` and `event.eventxevent (parenteventid)` — so no
additional index is required.

## Delivery

Publish returns as soon as the notification is durable. Channel delivery runs **after** the
transaction commits — holding a database transaction open across an SMTP conversation or an HTTPS
round trip would let a slow third party pin a connection, and a rollback would leave a notification
that had already been emailed. Outcomes are recorded as `Notification Delivery` events and read
back through `GET /{id}/deliveries`.

| Channel | Behaviour |
|---------|-----------|
| `STORE` | The FSDM record itself; always written, never dispatched |
| `MAIL` | Sends through Mail Master `IMailTransportService`. Plain text only. **Optional** |
| `WEBHOOK` | `POST` JSON over HTTPS, no redirects followed, response body discarded |
| `EVENT_BUS` | Publishes to `activitymaster.notifications.{enterpriseId}` |

Destinations come from host bindings, **never** from the notification: webhook and event bus from
`INotificationTransport`, email from `IMailNotificationTransport` in the optional `channels.mail`
package. Both default to returning nothing, so an unwired deployment delivers to the store only and
records `SKIPPED`. The webhook channel re-checks that a URL is `https`, has a host and carries no
credentials before calling it; a shared secret can be supplied and is sent as
`X-ActivityMaster-Token`. Mail bodies are sent as text, never HTML — a body is arbitrary Unicode
from a publishing system, and rendering it as HTML would make every publisher an injector into a
recipient's mail client.

The event bus channel does not authorise anything. The host bridges the bus to websocket or SSE
sessions and must check that the connected session belongs to `recipientId` before forwarding.

### Mail Master is optional

`mail-master` is an optional Maven dependency and a `requires static` module dependency. Without it
the module still starts, the mail channel still advertises itself, and every mail dispatch is
recorded as `SKIPPED` with `Mail Master is not installed`.

A guard alone would not be enough: the JVM verifies a method the first time it runs, so a single
mention of a Mail Master type would fail verification even on a path that never executes. So
`MailNotificationChannel` contains **no** Mail Master reference at all and delegates to
`MailDelivery`, which holds every one of them and is only touched once availability is confirmed.
`MailChannelIsolationTest` scans the compiled class files and fails if that regresses. The
dispatcher's channel sweep also tolerates a channel that cannot be loaded, so a broken optional
channel can never take the working ones down with it.

Register an `INotificationChannel` with a lower `sortOrder()` to replace a built-in channel.

| Environment key | Default | Effect |
|-----------------|---------|--------|
| `NOTIFICATIONS_WEBHOOK_TIMEOUT_MS` | `10000` | Per-attempt webhook timeout |

## Tests

`NotificationContractTest` and `MailChannelIsolationTest` are fast unit suites: identity
invariants, defensive copying, webhook destination rules, and the optional-dependency isolation
guard. `NotificationStorageTest` runs the real FSDM path against a containerised PostgreSQL and
needs Docker.
