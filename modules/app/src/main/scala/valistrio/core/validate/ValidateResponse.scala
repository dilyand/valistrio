package valistrio.core.validate

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.http.ResponseError

/** The HTTP response body for POST /validate. */
sealed trait ValidateResponse

object ValidateResponse {
  case object Success extends ValidateResponse
  final case class Failure(errors: NonEmptyList[ResponseError]) extends ValidateResponse

  implicit val encoder: Encoder[ValidateResponse] = Encoder.instance {
    case Success         => Json.obj("valid" -> Json.True)
    case Failure(errors) => Json.obj("valid" -> Json.False, "errors" -> errors.toList.asJson)
  }
}
