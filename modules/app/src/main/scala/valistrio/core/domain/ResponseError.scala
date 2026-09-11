package valistrio.core.domain

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.ValistrioError.{SinkError, ValidateError, ValidationError}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.SinkError._

/** A single error entry in an API error response — `type`/`recoverable`/`message`, plus an
  * optional `path`. Route-agnostic: shared by /validate, /post, and future routes.
  */
final case class ResponseError(`type`: String, recoverable: Boolean, path: Option[String], message: String)

object ResponseError {

  implicit val encoder: Encoder[ResponseError] = Encoder.instance { e =>
    val base = Json.obj(
      "type"        -> e.`type`.asJson,
      "recoverable" -> e.recoverable.asJson,
      "message"     -> e.message.asJson
    )
    e.path.fold(base)(p => base.deepMerge(Json.obj("path" -> p.asJson)))
  }

  /** A [[ValidateError]] maps to one or more entries; [[ValidationFailed]] expands to one entry
    * per field violation, preserving each path/message.
    */
  def from(e: ValidateError): NonEmptyList[ResponseError] = e match {
    case MalformedJson(message) =>
      NonEmptyList.one(ResponseError("malformed_json", recoverable = false, None, message))
    case SchemaNotFound(ref) =>
      NonEmptyList.one(ResponseError("schema_not_found", recoverable = true, None, ref.toString))
    case SchemaRegistryUnavailable(cause) =>
      NonEmptyList.one(ResponseError("schema_registry_unavailable", recoverable = true, None, cause))
    case SchemaRegistryTimeout =>
      NonEmptyList.one(ResponseError("schema_registry_timeout", recoverable = true, None, SchemaRegistryTimeout.msg))
    case ValidationFailed(errors) =>
      errors.map { case ValidationError(path, message) =>
        ResponseError("schema_validation_failed", recoverable = true, Some(path), message)
      }
  }

  /** A [[SinkError]] maps to a single entry. `SinkError` stays transport-only — this mapping
    * lives at the HTTP boundary, not in the sink algebra.
    */
  def fromSink(e: SinkError): ResponseError = e match {
    case Unavailable(cause) => ResponseError("sink_unavailable", recoverable = true, None, cause)
    case Timeout            => ResponseError("sink_timeout", recoverable = true, None, Timeout.msg)
    case WriteFailed(cause) => ResponseError("sink_write_failed", recoverable = true, None, cause)
  }
}
