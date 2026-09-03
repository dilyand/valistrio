# Changelog

All notable changes to Valistrio are documented here.
Format: one entry per release, newest first. Each release lists its features; chores and docs changes get a single line.

---

## [0.1.0] — unreleased

### Features
- **POST /validate** — validates a self-describing JSON envelope against schemas stored in Confluent Schema Registry. Accepts an envelope containing a typed event payload and zero or more typed context payloads. Returns `{"valid":true}` on success or a structured error list with per-error type, path, message, and recoverability flag. HTTP status reflects error class: 400 (malformed/structural), 404 (schema not found), 422 (schema validation failed), 503 (registry unavailable), 504 (registry timeout).

### Features
- **Integration test suite** — 9 tests running against the real shipped Docker image. Spins up Confluent Kafka (KRaft, no ZooKeeper) + Schema Registry 7.7.0 via Testcontainers, registers test schemas via the `SchemaRegistry` algebra, starts the Valistrio container with injected config, and makes real HTTP calls to verify the full stack end-to-end.

### Features
- **Kafka sink** — live `Sink[IO]` implementation (`valistrio.core.post.KafkaSink`) backed by `fs2-kafka`, writing validated envelopes to a configured topic keyed by `event_id`. Probes broker connectivity at startup so the app fails fast if Kafka is unreachable. Configurable via the new `kafka.bootstrapServers`/`kafka.topic` keys. Added `Encoder` instances for the envelope domain types and a `StubSink` for testing consumers of the algebra without a broker.

### Features
- **POST /post service** — `valistrio.core.post.PostService` orchestrates the write path: parse → decode → validate (reusing `ValidateService`) → write to `Sink`. Returns `{"written":true}` on success or `{"written":false,"errors":[...]}` on validation or sink failure, sharing its error entry shape with `/validate`. Refactored `ValidateService` to expose `parseAndDecode`/`validateAll` so both `/validate` and `/post` share the same parse/decode/validate logic without duplication.

### Features
- **POST /post route** — wires `PostService` into the HTTP layer. Returns 200 on success; 400/404/422/503/504 mirror `/validate`'s mapping for shared error types, plus 502 for `sink_write_failed` (any sink failure not specifically a timeout or unreachable-broker). Disabled response caching for `/post`, matching `/validate` and `/health`. Fixed a startup bug along the way: `KafkaSink`'s connectivity probe used `partitionsFor(topic)`, which requires the destination topic to already exist — many brokers run with auto-topic-creation disabled, so the app would refuse to start before the topic was provisioned. Switched to `AdminClient.describeCluster()`, which checks broker reachability without depending on any specific topic.

### Features
- **POST /post integration test suite** — 4 tests running against the real shipped Docker image, same Testcontainers topology as `/validate`'s suite. Covers a valid envelope landing on the configured Kafka topic, repeated `event_id`s producing two messages (documents that deduplication is not enforced in 0.1.0), schema validation failures producing no Kafka message, and Schema Registry unavailability returning 503.
- Fixed a real startup-latency bug surfaced by the registry-unavailable test: `ConfluentSchemaRegistry` wrapped every registry call in `IO.timeout(config.timeoutMs)`, but the underlying Confluent REST client had no connect/read timeout of its own — cancelling an `IO` fiber doesn't interrupt a blocking native call underneath it, so a network partition blocked for the JVM's default blocking-IO timeout (tens of seconds) instead of failing within the configured SLA. Now constructs the client with an explicit `RestService` and sets `httpConnectTimeoutMs`/`httpReadTimeoutMs` to `config.timeoutMs`, so the bound is enforced at the source.

### Chores / Docs
- Added the release workflow — a pushed `vX.Y.Z` tag publishes the app image to GHCR (`ghcr.io/dilyand/valistrio:<version>` and `:latest`) and creates a GitHub Release from this changelog. The version is derived from the git tag via sbt-dynver; see `docs/releasing.md`.
- Fixed `it/compile` — `modules/it`'s `scalaVersion` was left at `2.13.16` when `app`'s was bumped to `2.13.18` in the dependency audit (#8), which sbt 1.11's SIP-51 check rejects outright. Bumped to match.
- Fixed broken `sbt compile`/`test` — the dependency audit bumped Confluent Schema Registry to `7.7.10` and JSON Schema Serializer to `7.4.15`, both of which pin a non-existent `jetty-bom` version (`9.4.61`/`9.4.59`, missing the `.vYYYYMMDD` qualifier) in their parent POM, making the whole build unresolvable. Pinned back to `7.7.6`/`7.4.12`, the last patch versions on each line with a valid `jetty-bom` reference.
- Added the `Sink[F[_]]` algebra (`valistrio.core.post`) for writing validated envelopes to a downstream transport, plus `SinkError` and the DLQ envelope/truncation format documented in CLAUDE.md. No live implementation yet — that's the Kafka sink, tracked separately.
- Added Confluent Schema Registry and networknt JSON Schema dependencies
- Added CLAUDE.md recording frozen design decisions for the `/validate` payload contract
- Updated dependencies: Scala 2.13.18, http4s 0.23.34, Cats Effect 3.5.7, Cats Core 2.13.0, Circe 0.14.15, Circe Config 0.10.2, FS2 3.13.0, log4cats 2.7.1, specs2 4.20.9, and Confluent packages to compatible patches
