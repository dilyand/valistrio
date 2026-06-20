# Changelog

All notable changes to Valistrio are documented here.
Format: one entry per release, newest first. Each release lists its features; chores and docs changes get a single line.

---

## [0.1.0] — unreleased

### Features
- **POST /validate** — validates a self-describing JSON envelope against schemas stored in Confluent Schema Registry. Accepts an envelope containing a typed event payload and zero or more typed context payloads. Returns `{"valid":true}` on success or a structured error list with per-error type, path, message, and recoverability flag. HTTP status reflects error class: 400 (malformed/structural), 404 (schema not found), 422 (schema validation failed), 503 (registry unavailable), 504 (registry timeout).

### Chores / Docs
- Added Confluent Schema Registry and networknt JSON Schema dependencies
- Added CLAUDE.md recording frozen design decisions for the `/validate` payload contract
