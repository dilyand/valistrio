package valistrio.core.post

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.ValistrioError.SinkError
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.validate.ValidateResponseError

/** The HTTP response body for POST /post. */
sealed trait PostResponse

object PostResponse {
  case object Written extends PostResponse
  final case class Failure(errors: NonEmptyList[PostResponseError]) extends PostResponse

  implicit val encoder: Encoder[PostResponse] = Encoder.instance {
    case Written         => Json.obj("written" -> Json.True)
    case Failure(errors) => Json.obj("written" -> Json.False, "errors" -> errors.toList.asJson)
  }
}

/** A single error entry in a [[PostResponse.Failure]] body.
  *
  * Shares its shape with [[ValidateResponseError]] (type/recoverable/path/message) so
  * validation failures and sink failures render consistently in the /post response.
  */
final case class PostResponseError(`type`: String, recoverable: Boolean, path: Option[String], message: String)

object PostResponseError {
  implicit val encoder: Encoder[PostResponseError] = Encoder.instance { e =>
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

  def fromValidate(e: ValidateResponseError): PostResponseError =
    PostResponseError(e.`type`, e.recoverable, e.path, e.message)

  /** Maps a [[SinkError]] to a response entry. All three are ops-side issues
    * (broker unreachable, slow, or a write that failed) — recoverable once the
    * underlying Kafka issue is fixed, same convention as schema registry errors.
    */
  def fromSink(e: SinkError): PostResponseError = e match {
    case Unavailable(cause) =>
      PostResponseError("sink_unavailable", recoverable = true, path = None, cause)
    case Timeout =>
      PostResponseError("sink_timeout", recoverable = true, path = None, Timeout.msg)
    case WriteFailed(cause) =>
      PostResponseError("sink_write_failed", recoverable = true, path = None, cause)
  }
}
