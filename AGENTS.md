# AGENTS — Valistrio

Valistrio is a JSON event validation and ingestion service: clients POST a self-describing
event, Valistrio validates the `body` and each context against JSON Schemas held in a Confluent
Schema Registry, and on the write path forwards the event to a Kafka topic — or, when the event
fails in a way Valistrio can own, to a dead-letter queue.

[README.md](README.md) is the human-facing doc (intro, quickstart, the full endpoint walkthrough
with `curl`). This file is the context for AI assistants. It has two halves — **Deployment &
Operations** (deploy it, send data to it, the endpoint contract) and **Contributing** (topology,
design principles, ways of working).

---

# Deployment & Operations

## Running

The app is a single container published to `ghcr.io/dilyand/valistrio:<version>` on release.
It binds `0.0.0.0:8080` and needs two backing services, located purely by config:

- a **Kafka** broker (the events and DLQ sinks share one producer),
- a **Confluent Schema Registry** (the validation authority).

It **fails fast at startup**: it probes Kafka cluster reachability and seeds its own event
schema into the registry during resource acquisition, so an unreachable dependency crashes the
process at boot rather than surfacing on the first request.

Configuration comes from one env var, `VALISTRIO_CONFIG` — a **base64-encoded HOCON** document
under the `valistrio` namespace (`server`, `schemaRegistry`, `kafka.topics.{events,dlq}`). Unset
or empty falls back to built-in defaults. See the config block in [README.md](README.md#configuration).

## Provisioning

Two things must exist before Valistrio can serve `/post`; it does **not** create either:

- **The events and DLQ topics** (`kafka.topics.{events,dlq}`). The startup probe checks broker
  reachability, not topic existence, so provision both out-of-band, or run the broker with
  auto-topic-creation enabled. A missing topic surfaces as a sink write failure on `/post`, not at
  startup.
- **Your payload schemas.** Valistrio seeds only its own event schema at startup; the `body`/context
  schemas your events reference must be registered in the Schema Registry first, or `/post` and
  `/validate` return `schema_not_found` (422). Register each under the **subject equal to its schema
  reference** (the literal `group/name/version` string), as a plain JSON Schema (draft-07).

## Endpoints

Full request/response detail and status tables are in [README.md](README.md#operators-manual);
the contract essentials:

- **`POST /validate`** — validate only. `200 {"valid":true}` or `{"valid":false,"errors":[...]}`.
  Statuses: `400` malformed JSON, `422` schema-not-found / validation-failed, `503`/`504`
  registry unavailable / timeout. Errors are collected across the event and all contexts.
- **`POST /post`** — validate then write, under the **"once we get it, we own it"** contract:
  - `200 {"accepted":true,"written":"events"}` — valid, on the events topic.
  - `200 {"accepted":true,"written":"dlq","errors":[...]}` — an **owned** failure (malformed
    JSON, unknown schema, or validation failure), salvaged to the DLQ.
  - `5xx {"accepted":false,"errors":[...]}` — a transient infrastructure failure Valistrio could
    *not* own (registry/broker unavailable → 503, timeout → 504, other sink write failure → 502);
    the client should retry.
  - Deliberate asymmetry: malformed JSON is `400` on `/validate` but **owned** (`200 written:dlq`)
    on `/post`.
- **`POST /v1/track`, `POST /v1/page`** — the RudderStack adapter. Terminates the RudderStack
  browser SDK's data-plane protocol and maps each event into the event document, then forwards it to
  the `/post` write path. The producer carries the body schema ref as `properties.schema` and any
  contexts as `properties.contexts`; `messageId`/`originalTimestamp` map to `event_id`/`produced_at`
  (generated when omitted). Status-only (the SDK ignores the body): `200` owned, `5xx` transient (the
  same mapping as `/post`), `400` when the event can't be decoded/mapped. Serves the CORS preflight;
  other `/v1/<type>` paths are unserved (404).
- **`GET /health`** — `200 ok`, no auth, liveness only.

A DLQ record is `{"original": <event JSON | null>, "errors": [...], "failed_at": "<ISO-8601>"}`,
plus `"original_truncated": true` when the original exceeds `server.maxBytes` (dropped to `null`
so the record still fits the inbound limit).

`meta` is **lenient**: fields beyond the required `event_id`/`produced_at` are neither rejected
nor validated, and are written to the sink verbatim as part of the faithful original event. The
event, `body`, and each context reject unknown fields.

## Idempotency

`meta.event_id` is the idempotency key and the Kafka record key, so all writes for one event land
on one partition. Deduplicating repeated `event_id`s is the sink's responsibility for 0.1.0 — the
service does not check for or reject duplicates.

---

# Contributing

## Topology

Two sbt modules: `modules/app` (the service) and `modules/it` (the Testcontainers integration
suite, which runs against the shipped `valistrio:it` image). Within `app`, one home per concern:

- **`core.domain`** — the models and the wire response ADTs (`Event`, `TypedData`, `SchemaRef`,
  `Writable` with its nested `ValidatedEvent`/`FailedEvent`, `Disposition`, `ResponseError`,
  `ValidateResponse`, `PostResponse`). No effects.
- **`core.pipeline`** — the orchestration: `Validation` (the multi-pass validation pipeline) and
  `Ingestion` (parse → validate → write | DLQ). This is the **only** layer that touches the
  resources.
- **`core.http`** — `Routes` (the health/validate/post handlers: decode → delegate → map the
  response to a status), `PostStatus` (the shared `/post` status mapping, reused by the status-only
  adapters) and `Server` (Ember + the middleware stack, where the adapter routes are composed in).
- **`core.adapters`** — inbound protocol adapters that terminate a producer SDK's wire format and
  forward to the pipeline. `EventDocument`/`TypedPayload` assemble the event document and `AdapterCors`
  is the shared browser CORS policy; `adapters.rudderstack` holds the RudderStack wire model, mapper,
  and routes. Reuses `pipeline.Ingestion` — it has no write logic of its own.
- **`core.resources`** — the algebras and their live implementations, split into `resources.sinks`
  (`Sink`, `KafkaSink`, the shared `Kafka` producer) and `resources.schemas` (`SchemaRegistry`,
  `ConfluentSchemaRegistry`); `Logging` (the single shared logger) sits at the root.

## Design principles

- **The schema registry is the structural authority.** The seeded event schema
  (`io.github.dilyand.valistrio/event/1.0.0`) is the sole source of truth for event structure —
  there are no hand-rolled structural decoders. The `data` of the body and each context stays
  `Json`, validated against its registered schema. Whatever the schema requires, the schema enforces; nothing is
  normalised away, so both the events topic and the DLQ carry the faithful original JSON.
- **Parse, don't validate.** Prefer a smart constructor returning a proof-carrying type over a
  `Unit`/`Bool`-returning validator. `Validation` is the sole producer of a `ValidatedEvent` (its
  constructor is `private[core]`), and the events `Sink` accepts only that type — so an unvalidated
  event can't reach the sink from outside `core`, and the pipeline is the single place that builds
  one. Use `Validated`/`ValidatedNel` to accumulate every error in one pass rather than
  short-circuiting.
- **One error channel.** Effects raise `ValistrioError` (which extends `Throwable`) on the `IO`
  error channel and are recovered at the boundary with `attemptNarrow`; we do not return
  `IO[Either[...]]`. Pure, offline logic (JSON parsing, `SchemaRef` parsing) may return `Either`
  and is unit-tested directly.

## Ways of working

- **Issues** — tracked as GitHub issues; create one before implementing. An issue closes only via
  `closes #N` on a merge to `main` — never close one by hand.
- **Releases** — a pushed plain-SemVer `X.Y.Z` tag (no `v` prefix) publishes the image and, for a
  prod-ready version, a GitHub Release. The version derives from the tag via sbt-dynver. Full
  runbook: [docs/releasing.md](docs/releasing.md).
- **Changelog** — `CHANGELOG.md` follows Keep a Changelog with one entry per commit; it is
  finalised (and the `## [X.Y.Z]` header date-stamped) as the last step before a release.
- **Commits** — one task per commit; a change and its deployment contract (env vars, config the
  code expects) move together.

## Building and testing

Java 17, sbt, Scala 2.13.

- **Unit** (no Docker, matches CI): `sbt compile app/test`.
- **Integration** (needs Docker): builds the `valistrio:it` image, then runs the suite —
  `sbt it/test` (the suite depends on `app/Docker/publishLocal`). Structural-validation rules are
  covered here, against a real Schema Registry, rather than in unit tests.
