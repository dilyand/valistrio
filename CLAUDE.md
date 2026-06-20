# Valistrio — API reference for agents

Valistrio is a JSON event validation and ingestion service. Clients send structured event envelopes; Valistrio validates them against JSON Schemas stored in a Confluent Schema Registry and (on the write path) forwards valid events to a downstream sink.

---

## Envelope format

Every request body is a typed JSON payload: a `schema` field names the schema that describes the `data` field.

```json
{
  "schema": "com.valistrio/envelope/1.0.0",
  "data": {
    "meta": {
      "event_id": "<uuid>",
      "produced_at": "<ISO-8601 timestamp>"
    },
    "event": {
      "schema": "com.myorg/page_view/1.0.0",
      "data": { "page_url": "https://example.com" }
    },
    "contexts": [
      {
        "schema": "com.myorg/user/1.0.0",
        "data": { "user_id": "u-123" }
      }
    ]
  }
}
```

### Fields

| Field | Required | Notes |
|---|---|---|
| `schema` | yes | Must be `com.valistrio/envelope/1.0.0` |
| `data.meta.event_id` | yes | UUID, producer-generated, used for idempotency |
| `data.meta.produced_at` | yes | ISO-8601 timestamp, client clock at event creation time |
| `data.event` | yes | Typed payload — see schema naming below |
| `data.contexts` | no | Non-empty array of typed payloads if present |

Unknown fields are **rejected** at every structural level (`additionalProperties: false`). The `meta` object is lenient (unknown fields are ignored) so it can evolve without a version bump.

---

## Schema naming

Schema names follow the pattern `group/name/version`:

- **group** — reverse-domain style, lowercase, e.g. `com.myorg`
- **name** — `snake_case`, e.g. `page_view`
- **version** — strict SemVer integers, e.g. `1.0.0` (no prerelease or build metadata)

Example: `com.myorg/page_view/1.0.0`

Schemas under `com.valistrio` are reserved for Valistrio's own structural schemas and are seeded automatically at startup.

---

## POST /validate

Validates an envelope without writing it anywhere. Useful for testing schemas and payloads before going live.

**Request**: `Content-Type: application/json`, body is the envelope JSON above.

**Success response** (`200 OK`):
```json
{ "valid": true }
```

**Error response**:
```json
{
  "valid": false,
  "errors": [
    {
      "type": "schema_validation_failed",
      "recoverable": true,
      "path": "$.page_url",
      "message": "must be a string"
    }
  ]
}
```

### Error types and HTTP status

| HTTP | `type` | `recoverable` | Cause |
|---|---|---|---|
| 400 | `malformed_json` | false | Body is not valid JSON |
| 400 | `structural_decode_error` | false | Envelope shape is wrong (missing/extra fields, wrong types) |
| 404 | `schema_not_found` | true | No schema registered under the given name |
| 422 | `schema_validation_failed` | true | Payload data fails schema validation |
| 503 | `schema_registry_unavailable` | true | Schema Registry cannot be reached |
| 504 | `schema_registry_timeout` | true | Schema Registry did not respond in time |

**Recoverability**: `false` means retrying the identical payload can never succeed. `true` means the error is fixable — either by registering/fixing the schema (ops-side) or by correcting the payload data (payload-side).

All errors are collected across the event and all contexts — validation does not short-circuit after the first failure.

---

## GET /health

Returns `200 OK` with body `ok`. No authentication required. Used as a liveness check.

---

## Configuration

Valistrio reads configuration from the `VALISTRIO_CONFIG` environment variable, which must contain a **base64-encoded HOCON** string. Unset or empty falls back to built-in defaults.

Overridable keys (under the `valistrio` namespace):

```hocon
valistrio {
  server {
    host           = "0.0.0.0"
    port           = 8080
    maxBytes       = 2097152   # 2 MB request body limit
    requestTimeout = 5s
  }
  schemaRegistry {
    url       = "http://localhost:8081"
    timeoutMs = 3000
  }
}
```

Example — override the Schema Registry URL:
```bash
export VALISTRIO_CONFIG=$(echo 'valistrio { schemaRegistry { url = "http://my-registry:8081" } }' | base64)
```
