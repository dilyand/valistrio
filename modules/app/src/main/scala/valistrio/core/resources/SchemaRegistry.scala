package valistrio.core.resources

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

  /** Register `schemaJson` under `ref` as the Confluent subject. Idempotent; used at startup
    * to seed Valistrio-owned schemas. Raises on failure — a seed failure is fatal at startup.
    */
  def register(ref: SchemaRef, schemaJson: String): IO[Unit]
}
