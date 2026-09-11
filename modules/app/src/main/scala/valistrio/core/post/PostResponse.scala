package valistrio.core.post

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.domain.ResponseError

/** The outcome of POST /post. `/post` returns 200 exactly when Valistrio owns the event:
  *
  *  - [[Written]]: valid, written to the events topic.
  *  - [[Dlqd]]: failed validation but salvaged to the DLQ (still owned).
  *  - [[Failed]]: a transient infrastructure failure — the event could not be owned (5xx).
  */
sealed trait PostResponse

object PostResponse {
  case object Written extends PostResponse
  final case class Dlqd(errors: NonEmptyList[ResponseError]) extends PostResponse
  final case class Failed(errors: NonEmptyList[ResponseError]) extends PostResponse

  implicit val encoder: Encoder[PostResponse] = Encoder.instance {
    case Written => Json.obj("accepted" -> Json.True, "written" -> "events".asJson)
    case Dlqd(errors) =>
      Json.obj("accepted" -> Json.True, "written" -> "dlq".asJson, "errors" -> errors.toList.asJson)
    case Failed(errors) =>
      Json.obj("accepted" -> Json.False, "errors" -> errors.toList.asJson)
  }
}
