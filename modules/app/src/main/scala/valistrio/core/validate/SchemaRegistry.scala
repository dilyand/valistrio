package valistrio.core.validate

import io.circe.Json
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.domain.SchemaRef

/** Algebra for interacting with the Schema Registry.
  *
  * The algebra hides all registry client and JSON Schema validator details,
  * allowing the validation service to remain independent of the Confluent client
  * or any particular JSON Schema validation library.
  */
trait SchemaRegistry[F[_]] {

  /** Validate `data` against the JSON Schema registered under `name`.
    *
    * Returns:
    *  - [[scala.Right]] if `data` conforms to the schema
    *  - [[scala.Left]] with [[valistrio.core.ValistrioError.ValidateError.SchemaNotFound]]
    *    if no schema is registered under `name`
    *  - [[scala.Left]] with [[valistrio.core.ValistrioError.ValidateError.SchemaRegistryUnavailable]]
    *    if the registry cannot be reached
    *  - [[scala.Left]] with [[valistrio.core.ValistrioError.ValidateError.SchemaRegistryTimeout]]
    *    if the registry call exceeds the configured timeout
    *  - [[scala.Left]] with [[valistrio.core.ValistrioError.ValidateError.ValidationFailed]]
    *    if `data` does not conform to the schema (all errors collected, not short-circuited)
    */
  def validate(name: SchemaRef, data: Json): F[Either[ValidateError, Unit]]

  /** Register `schemaJson` in the registry under `name` as the Confluent subject.
    *
    * Idempotent: re-registering the same schema content is safe. Used at startup
    * to seed Valistrio-owned schemas. Raises a fatal IO error on failure.
    */
  def register(name: SchemaRef, schemaJson: String): F[Unit]
}
