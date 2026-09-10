package valistrio.core.validate

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.ValistrioError.{ValidateError, ValidationError}
import valistrio.core.ValistrioError.ValidateError._

/** The HTTP response body for POST /validate. */
sealed trait ValidateResponse

object ValidateResponse {
  case object Success extends ValidateResponse
  final case class Failure(errors: NonEmptyList[ValidateResponseError]) extends ValidateResponse

  implicit val encoder: Encoder[ValidateResponse] = Encoder.instance {
    case Success         => Json.obj("valid" -> Json.True)
    case Failure(errors) => Json.obj("valid" -> Json.False, "errors" -> errors.toList.asJson)
  }
}

/** A single error entry in a [[ValidateResponse.Failure]] body. */
final case class ValidateResponseError(
  `type`: String,
  recoverable: Boolean,
  path: Option[String],
  message: String
)

object ValidateResponseError {
  implicit val encoder: Encoder[ValidateResponseError] = Encoder.instance { e =>
    val base = Json.obj(
      "type"        -> e.`type`.asJson,
      "recoverable" -> e.recoverable.asJson,
      "message"     -> e.message.asJson
    )
    e.path match {
      case Some(p) => base.deepMerge(Json.obj("path" -> p.asJson))
      case None    => base
    }
  }

  /** Maps a [[ValidateError]] to one or more [[ValidateResponseError]] entries.
    *
    * Most errors map to a single entry. [[ValidationFailed]] maps to one entry
    * per individual [[ValidationError]], preserving the full path/message detail.
    */
  def from(e: ValidateError): NonEmptyList[ValidateResponseError] = e match {
    case MalformedJson(message) =>
      NonEmptyList.one(ValidateResponseError("malformed_json", recoverable = false, path = None, message))

    case SchemaNotFound(schemaName) =>
      NonEmptyList.one(ValidateResponseError("schema_not_found", recoverable = true, path = None, schemaName.toString))

    case SchemaRegistryUnavailable(cause) =>
      NonEmptyList.one(ValidateResponseError("schema_registry_unavailable", recoverable = true, path = None, cause))

    case SchemaRegistryTimeout =>
      NonEmptyList.one(ValidateResponseError("schema_registry_timeout", recoverable = true, path = None, SchemaRegistryTimeout.msg))

    case ValidationFailed(errors) =>
      errors.map { case ValidationError(path, message) =>
        ValidateResponseError("schema_validation_failed", recoverable = true, path = Some(path), message)
      }
  }
}
