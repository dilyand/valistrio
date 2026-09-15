# Valistrio

> **AI assistants:** read [AGENTS.md](AGENTS.md) first — it carries the deployment, operations, and contributing context you need.

Valistrio is a JSON event validation and ingestion service. Clients POST a self-describing
event; Valistrio validates it against JSON Schemas held in a Confluent Schema Registry and,
on the write path, forwards the event to a Kafka topic — or, when the event is malformed in a
way Valistrio can own, to a dead-letter queue.

Its guiding philosophy: **once we get an event, we own it.** `/post` returns `200` whenever
Valistrio has durably taken responsibility for an event — whether it landed on the events
topic or, having failed validation, was salvaged to the DLQ. A non-2xx means Valistrio
could *not* take ownership (a transient infrastructure failure) and the client should retry.

## Quickstart

Valistrio needs two backing services: a Kafka broker and a Confluent Schema Registry. The
config defaults expect them at `localhost:9092` and `http://localhost:8081` respectively.

With those running, start the app from the repo root (Java 17):

```bash
sbt app/run
```

It binds `http://0.0.0.0:8080`. Check it is up:

```bash
curl -s localhost:8080/health   # -> ok
```

To see the whole stack exercised end-to-end without wiring up Kafka and the registry
yourself, run the integration suite, which spins both up in containers (needs Docker):

```bash
sbt it/test
```

## Operator's manual

Every request body is a self-describing event: a top-level `schema` names Valistrio's event
schema, and `data` carries the producer `meta`, the primary `body`, and any `contexts`. The
`body` and each context name their own `schema`, which must be registered in the Schema
Registry.

```json
{
  "schema": "io.github.dilyand.valistrio/event/1.0.0",
  "data": {
    "meta": {
      "event_id": "018f1e2a-dead-beef-cafe-000000000000",
      "produced_at": "2026-06-08T12:00:00Z"
    },
    "body": {
      "schema": "com.myorg/page_view/1.0.0",
      "data": { "page_url": "https://example.com" }
    },
    "contexts": [
      { "schema": "com.myorg/user/1.0.0", "data": { "user_id": "u-123" } }
    ]
  }
}
```

| Field | Required | Notes |
|---|---|---|
| `schema` | yes | Must be `io.github.dilyand.valistrio/event/1.0.0` |
| `data.meta.event_id` | yes | UUID; the idempotency key and Kafka record key |
| `data.meta.produced_at` | yes | Timestamp string, client clock at creation time |
| `data.body` | yes | The primary typed payload |
| `data.contexts` | no | If present, a non-empty array of typed payloads |

Unknown fields are **rejected** on the event, `body`, and each context. `meta` is the
exception: it is **lenient** — extra fields beyond `event_id`/`produced_at` are neither
rejected nor validated, and are written to the sink verbatim as part of the original event.
Payload `schema` names follow `group/name/version` (reverse-domain `group`, `snake_case`
`name`, integer SemVer `version`), e.g. `com.myorg/page_view/1.0.0`.

### `POST /validate`

Validates an event without writing it anywhere — useful for checking schemas and payloads
before going live.

```bash
curl -s localhost:8080/validate -H 'content-type: application/json' -d @event.json
```

`200` with `{"valid": true}` on success. Otherwise `{"valid": false, "errors": [...]}`, where
each error carries `type`, `recoverable`, an optional `path`, and a `message`. All errors
across the event and every context are collected — validation does not stop at the first.

| HTTP | `type` | Cause |
|---|---|---|
| 400 | `malformed_json` | Body is not JSON |
| 422 | `schema_not_found` | A referenced schema is not registered |
| 422 | `schema_validation_failed` | Payload data does not conform to its schema |
| 503 | `schema_registry_unavailable` | Schema Registry cannot be reached |
| 504 | `schema_registry_timeout` | Schema Registry did not respond in time |

`recoverable: false` means retrying the identical payload can never succeed (`malformed_json`).
`true` means it is fixable — by registering/fixing the schema, or by correcting the payload.

### `POST /post`

Validates, then writes. The response reports whether Valistrio took ownership and where the
event landed.

```bash
curl -s localhost:8080/post -H 'content-type: application/json' -d @event.json
```

- **Written** — valid, written to the events topic:
  `200 {"accepted": true, "written": "events"}`
- **DLQ'd** — failed validation in a way Valistrio owns (bad JSON, unknown schema, or a
  validation failure), salvaged to the DLQ:
  `200 {"accepted": true, "written": "dlq", "errors": [...]}`
- **Failed** — a transient infrastructure failure; Valistrio could not take ownership, so the
  client should retry: a 5xx `{"accepted": false, "errors": [...]}`.

| HTTP | `type` | Meaning |
|---|---|---|
| 200 | `written: events` | Written to the events topic |
| 200 | `written: dlq` | Owned failure, salvaged to the DLQ |
| 502 | `sink_write_failed` | Kafka write failed for an unmapped reason |
| 503 | `schema_registry_unavailable` / `sink_unavailable` | Registry or broker unreachable |
| 504 | `schema_registry_timeout` / `sink_timeout` | Registry or Kafka write timed out |

Note the deliberate asymmetry with `/validate`: malformed JSON is a `400` on `/validate`, but
on `/post` it is **owned** — salvaged to the DLQ and returned as `200 written: dlq`.

A DLQ record wraps the salvaged event with its errors and the failure time:

```json
{
  "original": { "...": "the original event JSON" },
  "errors": [ { "type": "schema_validation_failed", "recoverable": true, "path": "$.page_url", "message": "must be a string" } ],
  "failed_at": "2026-06-08T12:00:00Z"
}
```

If the serialized `original` exceeds `server.maxBytes` it is replaced with `null` and
`original_truncated: true` is added, so a DLQ record always fits the inbound size limit and an
oversized payload is flagged rather than silently dropped.

### `GET /health`

`200 OK` with body `ok`. No authentication; used as a liveness check.

## Configuration

Configuration is read from the `VALISTRIO_CONFIG` environment variable, a **base64-encoded
HOCON** document under the `valistrio` namespace. Unset or empty falls back to the built-in
defaults:

```hocon
valistrio {
  server {
    host           = "0.0.0.0"
    port           = 8080
    maxBytes       = 2097152   # 2 MB request body limit
    requestTimeout = 5s
  }
  schemaRegistry {
    url           = "http://localhost:8081"
    timeoutMs     = 3000       # also the REST client's connect/read timeouts
    cacheCapacity = 2000       # max entries in the client's schema cache
  }
  kafka {
    bootstrapServers = "localhost:9092"
    topics {
      events = "valistrio.events"
      dlq    = "valistrio.dlq"
    }
  }
}
```

Override just what you need:

```bash
export VALISTRIO_CONFIG=$(echo 'valistrio { schemaRegistry { url = "http://my-registry:8081" } }' | base64)
```

## Releasing

Releases are cut by pushing a plain-SemVer `X.Y.Z` tag; the version derives from the tag via
sbt-dynver (there is no version file). A tag push publishes the image to GHCR and, for a
prod-ready version, creates a GitHub Release. See [docs/releasing.md](docs/releasing.md).
