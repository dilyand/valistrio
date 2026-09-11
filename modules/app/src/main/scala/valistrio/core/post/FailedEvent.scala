package valistrio.core.post

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.http.ResponseError

import java.nio.charset.StandardCharsets
import java.time.Instant

/** A failed event packaged for the DLQ: the faithful original JSON of the event Valistrio
  * accepted, the collected validation errors, and the time it failed.
  *
  * If the serialized `original` exceeds `maxBytes` it is dropped (`original` → `None`,
  * `truncated` → `true`) so the DLQ record stays within the inbound size limit — an oversized
  * payload is flagged, never silently lost.
  */
final case class FailedEvent private (
  original: Option[Json],
  truncated: Boolean,
  errors: NonEmptyList[ResponseError],
  failedAt: Instant
)

object FailedEvent {

  def of(original: Json, errors: NonEmptyList[ResponseError], failedAt: Instant, maxBytes: Long): FailedEvent = {
    val tooBig = original.noSpaces.getBytes(StandardCharsets.UTF_8).length > maxBytes
    FailedEvent(Option.unless(tooBig)(original), tooBig, errors, failedAt)
  }

  implicit val encoder: Encoder[FailedEvent] = Encoder.instance { fe =>
    val base = Json.obj(
      "original"  -> fe.original.getOrElse(Json.Null),
      "errors"    -> fe.errors.toList.asJson,
      "failed_at" -> fe.failedAt.toString.asJson
    )
    if (fe.truncated) base.deepMerge(Json.obj("original_truncated" -> Json.True)) else base
  }
}
