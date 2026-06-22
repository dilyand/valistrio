package valistrio.core.http

import cats.effect.IO
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response, Status}
import valistrio.core.post.{PostResponse, PostService}
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
        http <- respondValidate(resp)
      } yield http
    }

  def post(service: PostService): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "post" =>
      for {
        body <- req.as[String]
        resp <- service.post(body)
        http <- respondPost(resp)
      } yield http
    }

  // ---- Private ----

  private def respondValidate(resp: ValidateResponse): IO[Response[IO]] = {
    val status = resp match {
      case ValidateResponse.Success         => Status.Ok
      case ValidateResponse.Failure(errors) => statusForTypes(errors.toList.map(_.`type`).toSet)
    }
    IO(Response[IO](status).withEntity(resp.asJson))
  }

  private def respondPost(resp: PostResponse): IO[Response[IO]] = {
    val status = resp match {
      case PostResponse.Written         => Status.Ok
      case PostResponse.Failure(errors) => statusForTypes(errors.toList.map(_.`type`).toSet)
    }
    IO(Response[IO](status).withEntity(resp.asJson))
  }

  /** Shared by /validate and /post — both error shapes carry the same `type` strings
    * for the cases they have in common (parse/decode/registry errors), and /post adds
    * its own sink-specific types on top.
    */
  private def statusForTypes(types: Set[String]): Status =
    if (types.exists(t => t == "malformed_json" || t == "structural_decode_error"))
      Status.BadRequest
    else if (types.contains("schema_not_found"))
      Status.NotFound
    else if (types.contains("schema_registry_unavailable") || types.contains("sink_unavailable"))
      Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout") || types.contains("sink_timeout"))
      Status.GatewayTimeout
    else if (types.contains("sink_write_failed"))
      Status.BadGateway
    else
      Status.UnprocessableEntity
}
