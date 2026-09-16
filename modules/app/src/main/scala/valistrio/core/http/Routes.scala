package valistrio.core.http

import cats.effect.IO
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response, Status}
import valistrio.core.domain.{PostResponse, ValidateResponse}
import valistrio.core.pipeline.{Ingestion, Validation}

/** The HTTP routes. Each handler decodes the request, delegates to the pipeline, and maps the
  * returned response to an HTTP status. Orchestration — and the resources it uses — lives in
  * [[Validation]]/[[Ingestion]]; the status mapping is the only HTTP-specific logic here.
  */
object Routes {

  def health: HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "health" =>
      Ok("ok")
    }

  def validate(validation: Validation): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "validate" =>
      for {
        body <- req.as[String]
        resp <- validation.validate(body)
        http <- respondValidate(resp)
      } yield http
    }

  def post(ingestion: Ingestion): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "post" =>
      for {
        body <- req.as[String]
        resp <- ingestion.ingest(body)
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

  private def respondPost(resp: PostResponse): IO[Response[IO]] =
    IO(Response[IO](PostStatus.of(resp)).withEntity(resp.asJson))

  /** /validate: only non-JSON is 400; a well-formed request whose referenced schema is missing
    * or whose payload doesn't conform is 422 (the request URI itself is fine, so 404 would mislead).
    */
  private def statusForValidate(types: Set[String]): Status =
    if (types.contains("malformed_json")) Status.BadRequest
    else if (types.contains("schema_registry_unavailable")) Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout")) Status.GatewayTimeout
    else Status.UnprocessableContent // schema_not_found, schema_validation_failed
}
