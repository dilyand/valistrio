package valistrio.core.resources.schemas

import cats.effect.IO
import io.circe.Json
import valistrio.core.domain.SchemaRef

/** Algebra for interacting with the Schema Registry.
  *
  * Failures are raised as a [[valistrio.core.ValistrioError.ValidateError]] on the IO error
  * channel; success is `Unit`. Callers recover them where needed (e.g. to collect the
  * per-payload validation errors of one request).
  */
trait SchemaRegistry {

  /** Validate `data` against the schema registered under `ref`. Raises `SchemaNotFound`,
    * `SchemaRegistryUnavailable`, `SchemaRegistryTimeout`, or `ValidationFailed` on failure.
    */
  def validate(ref: SchemaRef, data: Json): IO[Unit]

  /** Register `schemaJson` under `ref` (the Confluent subject). Used at startup to seed
    * Valistrio-owned schemas, and shaped for ongoing registration too. Idempotency and evolution
    * are the registry's: re-registering an identical schema is a no-op returning the existing
    * version, while a changed schema under the same subject is accepted or rejected by Confluent's
    * compatibility rules — registration does not hard-block re-registration.
    *
    * Failures surface as a recoverable [[valistrio.core.ValistrioError.RegisterError]] on the IO
    * error channel (incompatible/invalid schema, registry unavailable, timeout), so a caller can
    * recover them: startup seeding lets it propagate as a fatal seed, while a request-path caller
    * would map it to a response — rather than an unclassified exception crashing the process.
    */
  def register(ref: SchemaRef, schemaJson: String): IO[Unit]
}
