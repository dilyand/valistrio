package valistrio.core.http

import cats.effect.IO
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response, Status}
import valistrio.core.validate.{ValidateResponse, ValidateService}

object Routes {

  def health: HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "health" =>
      Ok("ok")
    }

  def validate(service: ValidateService): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "validate" =>
      for {
        body <- req.as[String]
        resp <- service.validate(body)
        http <- respond(resp)
      } yield http
    }

  // ---- Private ----

  private def respond(resp: ValidateResponse): IO[Response[IO]] = {
    val status = resp match {
      case ValidateResponse.Success         => Status.Ok
      case ValidateResponse.Failure(errors) => statusFor(errors)
    }
    IO(Response[IO](status).withEntity(resp.asJson))
  }

  private def statusFor(errors: cats.data.NonEmptyList[valistrio.core.validate.ValidateResponseError]): Status = {
    val types = errors.toList.map(_.`type`).toSet
    if (types.exists(t => t == "malformed_json" || t == "structural_decode_error"))
      Status.BadRequest
    else if (types.contains("schema_not_found"))
      Status.NotFound
    else if (types.contains("schema_registry_unavailable"))
      Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout"))
      Status.GatewayTimeout
    else
      Status.UnprocessableEntity
  }
}
