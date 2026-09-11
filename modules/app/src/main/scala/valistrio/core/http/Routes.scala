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
      case ValidateResponse.Failure(errors) => statusForValidate(errors.toList.map(_.`type`).toSet)
    }
    IO(Response[IO](status).withEntity(resp.asJson))
  }

  private def respondPost(resp: PostResponse): IO[Response[IO]] = {
    val status = resp match {
      case PostResponse.Written        => Status.Ok
      case PostResponse.Dlqd(_)        => Status.Ok
      case PostResponse.Failed(errors) => statusForPost(errors.toList.map(_.`type`).toSet)
    }
    IO(Response[IO](status).withEntity(resp.asJson))
  }

  /** /validate: only non-JSON is 400; a well-formed request whose referenced schema is missing
    * or whose payload doesn't conform is 422 (the request URI itself is fine, so 404 would mislead).
    */
  private def statusForValidate(types: Set[String]): Status =
    if (types.contains("malformed_json")) Status.BadRequest
    else if (types.contains("schema_registry_unavailable")) Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout")) Status.GatewayTimeout
    else Status.UnprocessableContent // schema_not_found, schema_validation_failed

  /** /post: only reached for a `Failed` outcome, which carries transient (Retry) errors — owned
    * failures are salvaged to the DLQ and return 200. So this maps the registry- and sink-transient
    * types; the `else` is unreachable and signals a classification bug.
    */
  private def statusForPost(types: Set[String]): Status =
    if (types.contains("schema_registry_unavailable") || types.contains("sink_unavailable")) Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout") || types.contains("sink_timeout")) Status.GatewayTimeout
    else if (types.contains("sink_write_failed")) Status.BadGateway
    else Status.InternalServerError
}
