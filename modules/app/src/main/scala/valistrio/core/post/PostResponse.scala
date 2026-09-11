package valistrio.core.post

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.http.ResponseError

/** The HTTP response body for POST /post. */
sealed trait PostResponse

object PostResponse {
  case object Written extends PostResponse
  final case class Failure(errors: NonEmptyList[ResponseError]) extends PostResponse

  implicit val encoder: Encoder[PostResponse] = Encoder.instance {
    case Written         => Json.obj("written" -> Json.True)
    case Failure(errors) => Json.obj("written" -> Json.False, "errors" -> errors.toList.asJson)
  }
}
