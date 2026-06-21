# Changelog

All notable changes to Valistrio are documented here.
Format: one entry per release, newest first. Each release lists its features; chores and docs changes get a single line.

---

## [0.1.0] — unreleased

### Features
- **POST /validate** — validates a self-describing JSON envelope against schemas stored in Confluent Schema Registry. Accepts an envelope containing a typed event payload and zero or more typed context payloads. Returns `{"valid":true}` on success or a structured error list with per-error type, path, message, and recoverability flag. HTTP status reflects error class: 400 (malformed/structural), 404 (schema not found), 422 (schema validation failed), 503 (registry unavailable), 504 (registry timeout).

### Features
- **Integration test suite** — 9 tests running against the real shipped Docker image. Spins up Confluent Kafka (KRaft, no ZooKeeper) + Schema Registry 7.7.0 via Testcontainers, registers test schemas via the `SchemaRegistry` algebra, starts the Valistrio container with injected config, and makes real HTTP calls to verify the full stack end-to-end.

### Chores / Docs
- Added the `Sink[F[_]]` algebra (`valistrio.core.post`) for writing validated envelopes to a downstream transport, plus `SinkError` and the DLQ envelope/truncation format documented in CLAUDE.md. No live implementation yet — that's the Kafka sink, tracked separately.
- Added Confluent Schema Registry and networknt JSON Schema dependencies
- Added CLAUDE.md recording frozen design decisions for the `/validate` payload contract
- Updated dependencies: Scala 2.13.18, http4s 0.23.34, Cats Effect 3.5.7, Cats Core 2.13.0, Circe 0.14.15, Circe Config 0.10.2, FS2 3.13.0, log4cats 2.7.1, specs2 4.20.9, and Confluent packages to compatible patches
