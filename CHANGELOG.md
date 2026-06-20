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
- Added Confluent Schema Registry and networknt JSON Schema dependencies
- Added CLAUDE.md recording frozen design decisions for the `/validate` payload contract
